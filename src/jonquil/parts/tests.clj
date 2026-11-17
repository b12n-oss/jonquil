(ns jonquil.parts.tests
  "Run Tests (the tests of the file in the active tab) as a window part's
  data half: its two commands. The install half is jonquil.gtk.parts.tests.")

(def spec
  "The tests part's commands, run through the window context's
  :test-run-file-fn and :test-run-caret-fn."
  {:id :tests
   :commands
   {:test/run-file {:doc "Run the tests in this file: run the tab, then its tests, and list the failures."
                    :scope :window :call [:test-run-file-fn]}
    :test/run-at-caret {:doc "Run the test at the caret: run the tab, then the one test the caret is in."
                        :scope :window :call [:test-run-caret-fn]}}})
