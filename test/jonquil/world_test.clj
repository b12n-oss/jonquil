(ns jonquil.world-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.world :as world]))

(deftest world-dir-test
  (testing "beside the settings file, one folder per project, named for it"
    (is (= "/cfg/jonquil/worlds/proj-29c1d184"
           (world/world-dir "/cfg/jonquil/settings.edn" "/Users/x/dev/proj"))))
  (testing "the same folder always gets the same place"
    (is (= (world/world-dir "/c/settings.edn" "/a/b") (world/world-dir "/c/settings.edn" "/a/b"))))
  (testing "no folder"
    (is (= "/c/worlds/no-folder" (world/world-dir "/c/settings.edn" nil))))
  (testing "a folder name that isn't a plain word is made into one"
    (is (str/starts-with? (world/world-dir "/c/settings.edn" "/x/my proj!") "/c/worlds/my-proj--"))))

(deftest entry-path-test
  (is (= "/w/1793709658295.world" (world/entry-path "/w" 1793709658295)))
  (is (= "/w/0000000000042.world" (world/entry-path "/w" 42)) "padded, so names sort by time"))

(deftest to-prune-test
  (let [es (mapv (fn [i] {:path (str i) :at i}) (range 12 0 -1))]
    (is (= ["2" "1"] (map :path (world/to-prune es 10))) "the oldest past the newest ten")
    (is (= [] (world/to-prune (take 3 es) 10))))
  (testing "saves made before a restore are kept apart, so they never push out one you made"
    (let [es (vec (concat (map (fn [i] {:path (str "auto" i) :at (+ 100 i) :kind :before-restore}) (range 5 0 -1))
                          (map (fn [i] {:path (str "save" i) :at i :kind :save}) (range 3 0 -1))))]
      (is (= ["save1" "auto3" "auto2" "auto1"] (map :path (world/to-prune es 2))) "two of each kind kept"))))

(deftest newest-save-test
  (testing "Restore World's pick: the newest save you made, never one made before a restore"
    (is (= "s2" (:path (world/newest-save [{:path "a1" :kind :before-restore} {:path "s2" :kind :save}
                                            {:path "s1" :kind :save}]))))
    (is (= "old" (:path (world/newest-save [{:path "old"}]))) "an entry with no record counts as a save")
    (is (nil? (world/newest-save [{:path "a1" :kind :before-restore}])))))

(deftest ago-text-test
  (let [now 1000000000]
    (is (= "just now" (world/ago-text now (- now 30000))))
    (is (= "1 minute ago" (world/ago-text now (- now 61000))))
    (is (= "12 minutes ago" (world/ago-text now (- now (* 12 60000)))))
    (is (= "1 hour ago" (world/ago-text now (- now (* 65 60000)))))
    (is (= "3 hours ago" (world/ago-text now (- now (* 3 3600000)))))
    (is (= "2 days ago" (world/ago-text now (- now (* 49 3600000)))))))

(deftest entry-label-test
  (let [now 1000000000]
    (is (= "Restore World saved 12 minutes ago (3 vars, 1 placeholder)"
           (world/entry-label now {:at (- now (* 12 60000)) :kind :save :saved 3 :placeholders 1})))
    (is (= "Restore World saved before a restore, just now (1 var)"
           (world/entry-label now {:at now :kind :before-restore :saved 1 :placeholders 0})))
    (is (= "Restore World saved just now"
           (world/entry-label now {:at now})) "an entry with no record says only when")))

(defn- temp-dir []
  (.getCanonicalPath (.toFile (java.nio.file.Files/createTempDirectory
                               "jonquil-worlds" (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest entries-record-prune-test
  (let [dir (temp-dir)
        p1 (world/entry-path dir 1000)
        p2 (world/entry-path dir 2000)
        p3 (world/entry-path dir 3000)]
    (doseq [p [p1 p2 p3]] (spit p "image"))
    (world/record! p2 {:at 2000 :kind :save :saved 2 :placeholders 0})
    (testing "newest first, with each one's record, or just its time"
      (is (= [[p3 3000 nil] [p2 2000 2] [p1 1000 nil]]
             (map (juxt :path :at :saved) (world/entries dir))))
      (is (= [] (world/entries (str dir "/none"))) "no folder yet"))
    (testing "prune! deletes past keep, the image and its record"
      (world/prune! dir 1)
      (is (= [p3] (map :path (world/entries dir))))
      (is (not (.exists (io/file (str/replace p2 #"\.world$" ".edn"))))))))

(deftest save-report-test
  (is (= {:status "; Saved 2 vars to this project's world history." :lines []}
         (world/save-report {:saved 2 :placeholders []})))
  (is (= {:status "; Saved 1 var to this project's world history, 1 as a placeholder (see the console)."
          :lines ["; Save World: saved as placeholders, since they can't be written as they are:"
                  ";   demo/port (java.io.FileReader)"]}
         (world/save-report {:saved 1 :placeholders [{:var "demo/port" :kind "java.io.FileReader"}]})))
  (is (= {:status "; Save World: nothing was saved (see the console)."
          :lines ["; Nothing was saved: demo/k can't be written, even as a placeholder."
                  ";   demo/k (demo.Thing)"]}
         (world/save-report {:error "Nothing was saved: demo/k can't be written, even as a placeholder."
                             :unwritable [{:var "demo/k" :kind "demo.Thing"}]})))
  (is (= {:status "; Save World: Save World needs Jolt." :lines []}
         (world/save-report {:error "Save World needs Jolt."}))))

(deftest restore-report-test
  (is (= {:status "; No saved world for this project yet: Save World makes one." :lines []}
         (world/restore-report {:missing true})))
  (is (= {:status "; Restored 2 vars from this project's world." :lines []}
         (world/restore-report {:restored 2 :placeholders []})))
  (is (= {:status "; Restored 2 vars from this project's world. What you had first is saved: Restore an Earlier World brings it back."
          :lines []}
         (world/restore-report {:restored 2 :placeholders []} {:saved-first? true})))
  (is (= {:status "; Restored 2 vars from this project's world, 1 a placeholder (see the console)."
          :lines ["; Restore World: placeholders for what the world couldn't carry:"
                  ";   demo/fut: a future that has not completed"]}
         (world/restore-report {:restored 2 :placeholders [{:var "demo/fut" :kind "x" :description "a future that has not completed"}]})))
  (is (= {:status "; Restore World: not a world image" :lines []}
         (world/restore-report {:error "not a world image"}))))
