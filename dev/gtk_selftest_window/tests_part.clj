(ns gtk-selftest-window.tests-part
  "Run Tests in This File in the real window: the failures in the
  Locations list and on the console, a renamed test dropped on the next
  run, the refusal for a tab with no ns form, the warning about another
  tab's unrun edits, and an agent's jonquil_test on an unsaved tab."
  (:require [selftest-keys]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.locations-pane :as lpane]
            [jonquil.mcp.window :as window]
            [temp-dirs]))

(def ^:private KEY-F8 0xffc5)

(def ^:private head
  "(ns jonquil-selftest.run-tests-test\n  (:require [clojure.test :refer [deftest is]]))\n\n")

(def ^:private failing
  (str head "(deftest passes (is (= 1 1)))\n\n(deftest fails\n  (is (= 1 2) \"msg\"))\n"))

(def ^:private fixed
  (str head "(deftest passes (is (= 1 1)))\n\n(deftest renamed\n  (is (= 2 2)))\n"))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- run-tests! [ui] (support/run-palette! ui "run the tests in this file"))

(defn- close-extra-tabs! [ui]
  (when-let [f (:on-close @(:handlers (:locations-pane ui)))] (f))
  (doseq [t (rest ((:tabs* ui)))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first ((:tabs* ui))))))

(defn row-run-tests-lists-failures-then-forgets-a-rename
  "Run Tests in This File on a saved test file lists its one failure in
  the Locations list and on the console, and F8 selects the failing line.
  The buffer is then edited without saving, so the failing test is renamed
  and passes: the next run counts two tests, the old name gone, clears the
  failures list, and leaves the file on disk as it was."
  [ui]
  (go-row :run-tests-lists-failures-then-forgets-a-rename support/check
    (let [d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-tests-")))
          f (io/file d "run_tests_test.clj")
          _ (spit f failing)
          pane (:locations-pane ui)
          since (count (on-gtk (support/console-text ui)))
          console (fn [] (subs (support/console-text ui) since))]
      (on-gtk ((:open-file! ui) (.getPath f)) (run-tests! ui))
      (let [_ (await-until 15000 50 #(str/includes? (console) "; Tests: 1 failure in"))
            rows (on-gtk (lpane/row-texts pane))
            first-console (on-gtk (console))
            picked (on-gtk ((:handle-window-key! ui) ffi/null KEY-F8 0 0 ffi/null)
                           (let [s (editor/snapshot (:ed ((:active-tab* ui))))]
                             (subs (:text s) (:sel-start s) (:sel-end s))))
            _ (on-gtk (editor/set-text! (:ed ((:active-tab* ui))) fixed) (run-tests! ui))
            _ (await-until 15000 50 #(str/includes? (console) "; Tests: all passed in"))
            second-console (on-gtk (console))
            _ (on-gtk ((:handle-window-key! ui) ffi/null KEY-F8 0 0 ffi/null))
            _ (await-until 2000 20 #(= "; No locations list." (status ui)))
            after-status (on-gtk (status ui))]
        (on-gtk
         (try
           (support/check :run-tests-lists-failures-then-forgets-a-rename
                          (and (= 1 (count rows))
                               (str/ends-with? (first rows) "run_tests_test.clj:7: fail: fails: msg")
                               (str/includes? first-console
                                              "; Tests: 1 failure in jonquil-selftest.run-tests-test: 2 tests, 2 assertions, 1 passed, 1 failed, 0 errors")
                               (str/includes? first-console "; FAIL fails (run_tests_test.clj:7): msg")
                               (= "(is (= 1 2) \"msg\"))" picked)
                               (str/includes? second-console
                                              "; Tests: all passed in jonquil-selftest.run-tests-test: 2 tests, 2 assertions")
                               (= "; No locations list." after-status)
                               (= failing (slurp f)))
                          {:rows rows :picked picked :after-status after-status
                           :first-console first-console :second-console second-console
                           :disk-unchanged (= failing (slurp f))})
           (finally
             (close-extra-tabs! ui)
             (remove-ns 'jonquil-selftest.run-tests-test))))))))

(defn row-run-tests-needs-an-ns-form
  "Run Tests in This File on a tab with no ns form runs nothing and says
  why on the status line."
  [ui]
  (go-row :run-tests-needs-an-ns-form support/check
    (let [want "; No ns form in this tab, so there is no test namespace to run."
          since (count (on-gtk (support/console-text ui)))]
      (on-gtk (editor/set-text! (:editor ui) "(+ 1 2)") (run-tests! ui))
      (await-until 2000 20 #(= want (status ui)))
      (on-gtk
       (let [got (status ui)
             added (subs (support/console-text ui) since)]
         (support/check :run-tests-needs-an-ns-form
                        (and (= want got) (not (str/includes? added "Tests:")))
                        {:status got :console added}))))))

(defn row-run-tests-warns-about-unrun-edits
  "Another tab that has run and was edited since is named on the console
  before the summary: the tests ran against what that tab last ran."
  [ui]
  (go-row :run-tests-warns-about-unrun-edits support/check
    (let [d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-tests-")))
          f (io/file d "run_tests_test.clj")
          _ (spit f fixed)
          [ran? _] (support/run-and-await ui "(def selftest-unrun 1)" 10000
                                          #(str/includes? % "selftest-unrun"))
          since (count (on-gtk (editor/set-text! (:editor ui) "(def selftest-unrun 2)")
                               (support/console-text ui)))
          console (fn [] (subs (support/console-text ui) since))]
      (on-gtk ((:open-file! ui) (.getPath f)) (run-tests! ui))
      (await-until 15000 50 #(str/includes? (console) "; Tests: all passed in"))
      (on-gtk
       (let [text (console)
             warning (re-find #"; .+ has 1 form changed since it last ran\. The tests use what last ran\." text)]
         (try
           (support/check :run-tests-warns-about-unrun-edits
                          (and ran? warning
                               (< (str/index-of text warning) (str/index-of text "; Tests: all passed in")))
                          {:ran ran? :console text})
           (finally
             (close-extra-tabs! ui)
             (remove-ns 'jonquil-selftest.run-tests-test))))))))

(defn row-run-tests-warns-about-a-file-never-run
  "A source file opened, edited and left unrun is named on the console
  before the summary: its tab has no last run to compare with, so the
  unsaved edits are what gives it away. Once the tab reads saved, that line
  is gone (the saved-since row covers what a saved file gets)."
  [ui]
  (go-row :run-tests-warns-about-a-file-never-run support/check
    (let [d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-tests-")))
          f (io/file d "run_tests_test.clj")
          src (io/file d "never_run_source.clj")
          _ (spit f fixed)
          _ (spit src "(ns jonquil-selftest.never-run-source)\n(def a 1)\n")
          since (count (on-gtk ((:open-file! ui) (.getPath src))
                               ;; an edit as typing makes it: set-text! is a load and
                               ;; leaves the tab saved
                               (let [ed (:ed ((:active-tab* ui)))
                                     i (str/index-of (editor/editor-text ed) "1")]
                                 (editor/apply-op-plain! ed {:edits [[i (inc i) "2"]] :caret i}))
                               (support/console-text ui)))
          console (fn [] (subs (support/console-text ui) since))
          want "; never_run_source.clj has unsaved edits that were never run, so the tests don't see them."]
      (on-gtk ((:open-file! ui) (.getPath f)) (run-tests! ui))
      (await-until 15000 50 #(str/includes? (console) "; Tests: all passed in"))
      (let [first-text (on-gtk (console))
            mark (count (on-gtk (doseq [t ((:tabs* ui))] (editor/mark-saved! (:ed t)))
                                (run-tests! ui)
                                (support/console-text ui)))
            _ (await-until 15000 50 #(str/includes? (subs (support/console-text ui) mark) "; Tests: all passed in"))
            second-text (on-gtk (subs (support/console-text ui) mark))]
        (on-gtk
         (try
           (support/check :run-tests-warns-about-a-file-never-run
                          (and (str/includes? first-text want)
                               (< (str/index-of first-text want) (str/index-of first-text "; Tests: all passed in"))
                               (not (str/includes? second-text "unsaved edits")))
                          {:first first-text :second second-text})
           (finally
             (close-extra-tabs! ui)
             (remove-ns 'jonquil-selftest.run-tests-test))))))))

(defn row-run-tests-warns-about-a-file-saved-since
  "A source file that never ran, saved after the tests first ran in this
  window, is named before the summary: the tests may have loaded it
  before that save. A file saved before the tests' first run isn't named,
  and neither is a file with no ns form, however recently it was saved."
  [ui]
  (go-row :run-tests-warns-about-a-file-saved-since support/check
    (let [d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-tests-")))
          f (io/file d "run_tests_test.clj")
          src (io/file d "saved_since_source.clj")
          _ (spit f fixed)
          _ (spit src "(ns jonquil-selftest.saved-since-source)\n(def a 1)\n")
          ;; long before any run in this window, whichever row ran first
          _ (.setLastModified src (- (System/currentTimeMillis) 86400000))
          run-and-read! (fn []
                          (let [mark (count (on-gtk ((:open-file! ui) (.getPath f))
                                                    (run-tests! ui)
                                                    (support/console-text ui)))]
                            (await-until 15000 50 #(str/includes? (subs (support/console-text ui) mark)
                                                                  "; Tests: all passed in"))
                            (on-gtk (subs (support/console-text ui) mark))))
          notes (io/file d "notes.md")
          _ (spit notes "# Notes\n")
          _ (.setLastModified notes (+ (System/currentTimeMillis) 5000))
          _ (on-gtk ((:open-file! ui) (.getPath src)) ((:open-file! ui) (.getPath notes)))
          before (run-and-read!)
          _ (spit src "(ns jonquil-selftest.saved-since-source)\n(def a 2)\n")
          _ (.setLastModified src (+ (System/currentTimeMillis) 5000))
          after (run-and-read!)
          want "; saved_since_source.clj was saved after the tests first ran here and was never run."]
      (on-gtk
       (try
         (support/check :run-tests-warns-about-a-file-saved-since
                        (and (not (str/includes? before "saved_since_source.clj"))
                             (not (str/includes? (str before after) "notes.md"))
                             (str/includes? after want)
                             (< (str/index-of after want) (str/index-of after "; Tests: all passed in")))
                        {:before before :after after})
         (finally
           (close-extra-tabs! ui)
           (remove-ns 'jonquil-selftest.run-tests-test)))))))

(defn row-agent-test-runs-the-unsaved-tab
  "jonquil_test over the live window, for a namespace open in a tab whose
  edits aren't saved: the tab runs first, so the answer counts the tab's
  tests and not the file's. The console names the agent and shows the
  summary, none of the tool's own code, and the file stays as it was."
  [ui]
  (go-row :agent-test-runs-the-unsaved-tab support/check
    (let [d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-tests-")))
          f (io/file d "run_tests_test.clj")
          _ (spit f failing)
          since (count (on-gtk ((:open-file! ui) (.getPath f))
                               (editor/set-text! (:ed ((:active-tab* ui))) fixed)
                               (support/console-text ui)))
          tools (window/make-window-tools {:ui ui :dir d})
          r ((:call tools) "jonquil_test" {"ns" "jonquil-selftest.run-tests-test"})
          _ (await-until 5000 50 #(str/includes? (subs (support/console-text ui) since) "; Tests: all passed in"))]
      (on-gtk
       (let [text (subs (support/console-text ui) since)
             d' (:data r)]
         (try
           (support/check :agent-test-runs-the-unsaved-tab
                          (and (not (:error? r))
                               (= "ok" (get d' "status"))
                               (= {"tests" 2 "assertions" 2 "pass" 2 "fail" 0 "error" 0} (get d' "counts"))
                               (str/includes? text "; An agent ran the tests in jonquil-selftest.run-tests-test")
                               (str/includes? text "; Tests: all passed in jonquil-selftest.run-tests-test: 2 tests, 2 assertions")
                               (not (str/includes? text "clojure.core/require"))
                               (= failing (slurp f)))
                          {:answer r :console text :disk-unchanged (= failing (slurp f))})
           (finally
             (close-extra-tabs! ui)
             (remove-ns 'jonquil-selftest.run-tests-test))))))))
