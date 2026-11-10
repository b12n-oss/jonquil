(ns jonquil.gtk.window.tree-ops-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.file-ops :as ops]
            [jonquil.file-tree :as ft]
            [jonquil.gtk.callbacks]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.file-tree-pane :as file-tree-pane]
            [jonquil.gtk.trash :as trash]
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.gtk.window.tree-ops :as win-tree-ops]))

(defn- retarget
  "ops/retarget on plain strings: new for old itself, new plus the rest
  under old, else nil."
  [p old new]
  (cond (= p old) new
        (str/starts-with? p (str old "/")) (str new (subs p (count old)))
        :else nil))

(deftest tree-later-and-failed-test
  (let [log (atom []) l (fn [x] (swap! log conj x))]
    (with-redefs [jonquil.gtk.callbacks/on-gui! (fn [f] (f))
                  cview/append! (fn [_ k s] (l [:append k s]))
                  file-tree-pane/refresh! (fn [tp] (l [:refresh tp]))]
      (#'win-tree-ops/tree-later! ::cv "x" (fn [] (l :ran)))
      (#'win-tree-ops/tree-later! ::cv "x" (fn [] (throw (ex-info "boom" {}))))
      (is (= [:ran [:append :error "; x: boom\n"]] @log))
      (reset! log [])
      (is (= "bad" (#'win-tree-ops/tree-failed! {:cv ::cv :tree-pane ::tp} "rename" "bad")))
      (is (= [[:append :error "; rename: bad\n"] [:refresh ::tp]] @log)))))

(deftest show-and-note-test
  (let [log (atom []) l (fn [x] (swap! log conj x))
        tp {:state (atom {:ignore ::ig})}]
    (with-redefs [file-tree-pane/show-model! (fn [_ m] (l [:show m]))
                  file-tree-pane/select-path! (fn [_ p] (l [:select p]))
                  ft/prune (fn [m] [:prune m])
                  ft/expand-to (fn [m p] [:expand m p])
                  ft/ignored? (fn [ig n dir?] (l [:ignored? ig n dir?]) (= n ".x"))
                  cview/append! (fn [_ k s] (l [:append k s]))]
      (#'win-tree-ops/show-in-tree! tp "/r/a")
      (is (= [[:show [:expand [:prune {:ignore ::ig}] "/r/a"]] [:select "/r/a"]] @log))
      (reset! log [])
      (#'win-tree-ops/note-if-hidden! {:cv ::cv :tree-pane tp} "/r/.x" true)
      (#'win-tree-ops/note-if-hidden! {:cv ::cv :tree-pane tp} "/r/y" false)
      (is (= [[:ignored? ::ig ".x" true] [:append :system (ops/hidden-message ".x")] [:ignored? ::ig "y" false]] @log)))))

(deftest stale-tab-problem-test
  (let [t1 {:path (atom "/r/a.clj")} t2 {:path (atom "/r/b/c.clj")}]
    (with-redefs [ops/retarget retarget]
      (is (= "a.clj is open in a tab; close that tab first." (#'win-tree-ops/stale-tab-problem [t1 t2] "/r/a.clj" [])))
      (is (nil? (#'win-tree-ops/stale-tab-problem [t1 t2] "/r/a.clj" [t1])))
      (is (= "b is open in a tab; close that tab first." (#'win-tree-ops/stale-tab-problem [t1 t2] "/r/b" [])))
      (is (nil? (#'win-tree-ops/stale-tab-problem [t1 t2] "/r/z.clj" []))))))

(deftest tree-new-file-and-folder-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) made (atom nil)
        ctx {:cv ::cv :tree-pane ::tp :tabs (atom [{:path (atom "/d/held.clj")}])}]
    (with-redefs [ops/retarget retarget
                  ops/create-file! (fn [d n] (l [:create d n]) @made)
                  ops/create-folder! (fn [d n] (l [:create-folder d n]) @made)
                  jonquil.gtk.callbacks/on-gui! (fn [f] (f))
                  win-tree-ops/show-in-tree! (fn [tp p] (l [:show tp p]))
                  win-tree-ops/note-if-hidden! (fn [_ p dir?] (l [:note p dir?]))
                  win-tabs/open-file! (fn [c p] (l [:open (= c ctx) p]))
                  win-tree-ops/tree-failed! (fn [_ what msg] (l [:failed what msg]) msg)]
      (reset! made {:path "/d/a.clj"})
      (is (nil? (#'win-tree-ops/tree-new-file! ctx "/d" " a.clj ")))
      (is (= [[:create "/d" " a.clj "] [:show ::tp "/d/a.clj"] [:open true "/d/a.clj"] [:note "/d/a.clj" false]] @log))
      (reset! log [])
      (is (= "held.clj is open in a tab; close that tab first." (#'win-tree-ops/tree-new-file! ctx "/d" "held.clj")))
      (is (= [[:failed "new file" "held.clj is open in a tab; close that tab first."]] @log))
      (reset! log [])
      (reset! made {:error "exists"})
      (is (= "exists" (#'win-tree-ops/tree-new-file! ctx "/d" "b.clj")))
      (is (= [[:create "/d" "b.clj"] [:failed "new file" "exists"]] @log))
      (reset! log [])
      (reset! made {:path "/d/f"})
      (is (nil? (#'win-tree-ops/tree-new-folder! ctx "/d" "f")))
      (is (= [[:create-folder "/d" "f"] [:show ::tp "/d/f"] [:note "/d/f" true]] @log))
      (reset! log [])
      (reset! made {:error "E"})
      (is (= "E" (#'win-tree-ops/tree-new-folder! ctx "/d" "g")))
      (is (= [[:create-folder "/d" "g"] [:failed "new folder" "E"]] @log)))))

(deftest tree-rename-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) renamed (atom nil)
        t1 {:id 1 :ed ::ed :path (atom "/r/src/a.clj")}
        t2 {:id 2 :ed ::ed2 :path (atom "/r/other.clj")}
        t3 {:id 3 :ed ::ed3 :path (atom "/r/taken.clj")}
        state (atom {:expanded #{"/r/src" "/r/src/x" "/r/lib"}})
        ctx {:cv ::cv :tree-pane {:state state} :tabs (atom [t1 t2 t3]) :win ::win :active-tab (fn [] t1)}]
    (with-redefs [ops/retarget retarget
                  ops/rename! (fn [o n] (l [:rename o n]) @renamed)
                  jonquil.gtk.callbacks/on-gui! (fn [f] (f))
                  win-tabs/refresh-labels! (fn [c] (l [:labels (= c ctx)]))
                  win-files/update-title! (fn [ed win path] (l [:title ed win @path]))
                  win-tree-ops/show-in-tree! (fn [_ p] (l [:show p]))
                  win-tree-ops/tree-failed! (fn [_ what msg] (l [:failed what msg]) msg)]
      (reset! renamed {:path "/r/lib2"})
      (is (nil? (#'win-tree-ops/tree-rename! ctx "/r/src" "lib2")))
      (is (= "/r/lib2/a.clj" @(:path t1)))
      (is (= "/r/other.clj" @(:path t2)))
      (is (= #{"/r/lib2" "/r/lib2/x" "/r/lib"} (:expanded @state)))
      (is (= [[:rename "/r/src" "lib2"] [:labels true] [:title ::ed ::win "/r/lib2/a.clj"] [:show "/r/lib2"]] @log))
      (reset! log [])
      (is (= "taken.clj is open in a tab; close that tab first." (#'win-tree-ops/tree-rename! ctx "/r/other.clj" "taken.clj")))
      (is (= [[:failed "rename" "taken.clj is open in a tab; close that tab first."]] @log))
      (reset! log [])
      (reset! renamed {:error "E"})
      (is (= "E" (#'win-tree-ops/tree-rename! ctx "/r/other.clj" "o2.clj")))
      (is (= [[:rename "/r/other.clj" "o2.clj"] [:failed "rename" "E"]] @log))
      (is (= "/r/other.clj" @(:path t2))))))

(deftest listed-dir-test
  (let [dir (doto (java.io.File/createTempFile "jonquil-tree-ops" "") (.delete) (.mkdirs))
        f (java.io.File. dir "a.clj")
        link (java.io.File. dir "link")]
    (try
      (spit f "x")
      (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath dir) (into-array java.nio.file.attribute.FileAttribute []))
      (is (true? (#'win-tree-ops/listed-dir? (.getPath dir))))
      (is (false? (#'win-tree-ops/listed-dir? (.getPath f))))
      (is (false? (#'win-tree-ops/listed-dir? (.getPath link))))
      (is (false? (#'win-tree-ops/listed-dir? (str (.getPath dir) "/missing"))))
      (finally (.delete link) (.delete f) (.delete dir)))))

(deftest trash-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) res (atom {}) choice (atom nil)
        ctx {:cv ::cv :win ::win :tree-pane ::tp
             :tabs (atom [{:path (atom "/r/d/a.clj")} {:path (atom "/r/d/b.clj")} {:path (atom "/r/x.clj")}])}]
    (with-redefs [ops/retarget retarget
                  trash/trash! (fn [p] (l [:trash p]) @res)
                  win-tree-ops/listed-dir? (fn [p] (= p "/r/d"))
                  jonquil.gtk.callbacks/on-gui! (fn [f] (f))
                  file-tree-pane/refresh! (fn [tp] (l [:refresh tp]))
                  cview/append! (fn [_ k s] (l [:append k s]))]
      (#'win-tree-ops/trash-choice! ctx "/r/d" {:index 0})
      (is (= [[:trash "/r/d"] [:append :system (ops/trash-message "d" 2 true)] [:refresh ::tp]] @log))
      (reset! log [])
      (#'win-tree-ops/trash-choice! ctx "/r/d" {:index 1})
      (#'win-tree-ops/trash-choice! ctx "/r/d" {:error "dismissed"})
      (is (= [] @log))
      (reset! res {:error "E"})
      (#'win-tree-ops/trash-choice! ctx "/r/x.clj" {:index 0})
      (is (= [[:trash "/r/x.clj"] [:append :error "; trash: E\n"] [:refresh ::tp]] @log)))
    (reset! log [])
    (reset! win-alerts/alert-pending? false)
    (try
      (with-redefs [win-tree-ops/listed-dir? (constantly false)
                    win-alerts/show-alert! (fn [win m opts f] (l [:alert win m opts]) (reset! choice f))
                    win-tree-ops/trash-choice! (fn [c p r] (l [:choice (= c ctx) p r]))
                    cview/append! (fn [_ k s] (l [:append k s]))]
        (is (nil? (#'win-tree-ops/tree-trash! ctx "/r/x.clj")))
        (is (true? @win-alerts/alert-pending?))
        (is (nil? (#'win-tree-ops/tree-trash! ctx "/r/x.clj")))
        (is (= [[:alert ::win (ops/trash-alert "x.clj" false) win-alerts/TRASH-ALERT]] @log))
        (@choice {:index 0})
        (is (= [:choice true "/r/x.clj" {:index 0}] (last @log)))
        (reset! log [])
        (reset! win-alerts/alert-pending? false)
        (with-redefs [win-alerts/show-alert! (fn [& _] (throw (ex-info "boom" {})))]
          (is (nil? (#'win-tree-ops/tree-trash! ctx "/r/x.clj")))
          (is (false? @win-alerts/alert-pending?))
          (is (= [[:append :error "; trash: boom\n"]] @log))))
      (finally (reset! win-alerts/alert-pending? false)))))
