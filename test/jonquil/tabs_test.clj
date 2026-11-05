(ns jonquil.tabs-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.dialect :as dialect]
            [jonquil.tabs :as tabs]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(deftest labels-test
  (is (= ["a.clj" "b.clj ●"] (tabs/labels [{:path "/p/a.clj"} {:path "/p/b.clj" :dirty? true}])))
  (is (= ["core.clj · talk" "core.clj · lib"] (tabs/labels [{:path "/x/talk/core.clj"} {:path "/y/lib/core.clj"}])))
  (is (= ["Untitled" "a.clj" "Untitled 2 ●"] (tabs/labels [{} {:path "/p/a.clj"} {:dirty? true}]))))

(deftest find-open-test
  (let [dir (temp-dirs/temp-dir! "jonquil-tabs-test-")
        f (java.io.File. dir "a.clj")
        link (java.io.File. dir "link.clj")]
    (spit f "1")
    (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath f) (make-array java.nio.file.attribute.FileAttribute 0))
    (is (= 1 (tabs/find-open [{} {:path (str f)}] (str link))))
    (is (nil? (tabs/find-open [{} {:path (str f)}] (str dir "/other.clj"))))
    (is (nil? (tabs/find-open [{}] nil)))))

(deftest untouched-untitled-test
  (is (tabs/untouched-untitled? {:path nil :text ""}))
  (is (not (tabs/untouched-untitled? {:path nil :text " "})))
  (is (not (tabs/untouched-untitled? {:path "/a.clj" :text ""}))))

(deftest runnable-here-test
  (let [jolt {:profile dialect/jolt :runtime :in-process :project-dir "/a"}]
    (is (tabs/runnable-here? jolt nil))
    (is (tabs/runnable-here? jolt (assoc jolt :project-dir "/elsewhere")) "in-process ignores the dir")
    (is (not (tabs/runnable-here? (assoc jolt :runtime :nrepl) (assoc jolt :runtime :nrepl :project-dir "/b"))))
    (is (not (tabs/runnable-here? jolt (assoc jolt :profile dialect/cljd :runtime :attach))))))

(deftest a-lone-loose-tab-keeps-the-windows-jolt
  (let [project {:profile dialect/jolt :runtime :nrepl :project-dir "/p"}
        loose {:profile dialect/jolt :runtime :in-process :project-dir nil}]
    (is (tabs/keeps-window-jolt? project loose) "Save As of the lone tab to a loose path")
    (is (not (tabs/keeps-window-jolt? project (assoc loose :project-dir "/q"))) "into another project: follow it")
    (is (not (tabs/keeps-window-jolt? loose project)) "a loose window follows its tab into a project")
    (is (not (tabs/keeps-window-jolt? {:profile dialect/cljd :runtime :attach :project-dir "/app"} loose))
        "a ClojureDart window follows its tab to Jolt")))

(deftest a-loose-jolt-file-runs-in-any-jolt-window
  (let [project {:profile dialect/jolt :runtime :nrepl :project-dir "/p"}
        loose {:profile dialect/jolt :runtime :in-process :project-dir nil}]
    (is (tabs/runnable-here? project loose) "a scratch file in a deps.edn window runs in its child")
    (is (tabs/runnable-here? (assoc loose :project-dir nil) loose))
    (is (not (tabs/runnable-here? project (assoc loose :project-dir "/q")))
        "a file of another project still refuses, even one kept in process")
    (is (not (tabs/runnable-here? {:profile dialect/cljd :runtime :attach :project-dir "/app"} loose))
        "only a Jolt window")
    (is (not (tabs/runnable-here? project {:profile dialect/cljs :runtime :attach :project-dir nil}))
        "only a Jolt file")))

(deftest close-alert-test
  (is (= {:message "Save changes to a.clj before closing?" :buttons ["Save" "Don't Save" "Cancel"]}
         (tabs/close-alert ["a.clj"])))
  (is (= {:message "2 files have unsaved changes: a.clj, Untitled" :buttons ["Save All" "Don't Save" "Cancel"]}
         (tabs/close-alert ["a.clj" "Untitled"]))))

(deftest run-banner-test
  (is (= "; --- Running Definitions ---\n" (tabs/run-banner nil)))
  (is (= "; --- Running demo.clj ---\n" (tabs/run-banner "/p/talk/demo.clj"))))

(deftest index-test
  (is (= 0 (tabs/cycle-index 3 2 1)))
  (is (= 2 (tabs/cycle-index 3 0 -1)))
  (is (= 1 (tabs/index-for-digit 3 2)))
  (is (nil? (tabs/index-for-digit 3 5)))
  (is (= 2 (tabs/index-for-digit 3 9)))
  (is (= [:a :x :b] (tabs/insert-after [:a :b] 0 :x))))

(deftest refusal-message-test
  (let [home (System/getProperty "user.home")
        msg (tabs/refusal-message {:file "demo.cljd"
                                   :tab-sel {:profile dialect/cljd :runtime :attach :project-dir (str home "/dev/other")}
                                   :window-sel {:profile dialect/jolt :runtime :in-process :project-dir (str home "/dev/my-talk")}})]
    (is (= "; demo.cljd belongs to ~/dev/other (ClojureDart (not running)). This window runs ~/dev/my-talk (Jolt), so it can't run here.\n" msg))))

(deftest disk-sync-test
  (is (= :reload (tabs/disk-sync {:saved "a" :disk "b" :dirty? false})))
  (is (= :warn (tabs/disk-sync {:saved "a" :disk "b" :dirty? true})))
  (is (nil? (tabs/disk-sync {:saved "a" :disk "a" :dirty? true})))
  (is (nil? (tabs/disk-sync {:saved "a" :disk nil :dirty? false})))
  ;; edits that already equal the new disk text lose nothing
  (is (= :rebase (tabs/disk-sync {:saved "a" :disk "b" :dirty? true :buffer "b"}))))

(deftest nul-line-test
  (is (nil? (tabs/nul-line "")))
  (is (nil? (tabs/nul-line "(def a 1)\n")))
  (is (= 1 (tabs/nul-line "\u0000binary")))
  (is (= 3 (tabs/nul-line "a\nb\nc\u0000d")))
  ;; CRLF counts by \n, so the line a person sees
  (is (= 2 (tabs/nul-line "a\r\nb\u0000")))
  (is (= 1 (tabs/nul-line "\u0000\u0000\u0000\u0000"))))

(deftest disk-change-test
  (is (nil? (tabs/disk-change {:saved "a" :disk "a" :reported nil})) "unchanged")
  (is (nil? (tabs/disk-change {:saved "b" :disk "b" :reported nil})) "the tab's own save")
  (is (= :changed (tabs/disk-change {:saved "a" :disk "b" :reported nil})) "first change")
  (is (nil? (tabs/disk-change {:saved "a" :disk "b" :reported "b"})) "same change again")
  (is (= :changed (tabs/disk-change {:saved "a" :disk "c" :reported "b"})) "a second, different change")
  (is (= :missing (tabs/disk-change {:saved "a" :disk nil :reported nil})) "deleted")
  (is (nil? (tabs/disk-change {:saved "a" :disk nil :reported ::tabs/missing})) "deleted, said once")
  (is (= :clear (tabs/disk-change {:saved "s" :disk "s" :reported "x"}))
      "the disk back to what the tab saved forgets the report, so the same edit again is said again")
  (is (= :clear (tabs/disk-change {:saved "s" :disk "s" :reported ::tabs/missing})) "restored after a delete"))
