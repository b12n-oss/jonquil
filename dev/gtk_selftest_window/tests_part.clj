(ns gtk-selftest-window.tests-part
  "Run Tests in This File in the real window: the failures in the
  Locations list and on the console, a renamed test dropped on the next
  run, and the refusal for a tab with no ns form."
  (:require [selftest-keys]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.locations-pane :as lpane]
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
