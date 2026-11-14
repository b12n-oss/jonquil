(ns jonquil.gtk.window.files-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.window.files :as win-files]))

(deftest file-name-test
  (is (= "c.clj" (#'win-files/file-name "/a/b/c.clj")))
  (is (= "c.clj" (#'win-files/file-name "c.clj"))))

(deftest temp-path-for-test
  (is (re-matches #"/a/b/\.c\.clj\.jonquil-tmp--?\d+" (#'win-files/temp-path-for "/a/b/c.clj")))
  (is (re-matches #"\.c\.clj\.jonquil-tmp--?\d+" (#'win-files/temp-path-for "c.clj"))))

(deftest name-user-path-test
  (is (= "can't write /u/c.clj (was /u/c.clj)"
         (#'win-files/name-user-path "can't write /t/.c.tmp (was /r/c.clj)" "/t/.c.tmp" "/r/c.clj" "/u/c.clj"))))

(deftest open-result-decisions-test
  (let [opened (atom []) appended (atom [])]
    (with-redefs [win-files/dialog-dismissed? (fn [r] (= 2 (:code r)))
                  cview/append! (fn [_ kind text] (swap! appended conj [kind text]))]
      (#'win-files/contained-open-result! {:cv ::cv :open! (fn [p] (swap! opened conj p))} {:path "/p.clj"})
      (#'win-files/contained-open-result! {:cv ::cv :open! (fn [_] (throw (ex-info "boom" {})))} {:path "/q.clj"})
      (#'win-files/contained-open-result! {:cv ::cv :open! (fn [p] (swap! opened conj p))} {:error "dismissed" :code 2})
      (#'win-files/contained-open-result! {:cv ::cv :open! (fn [p] (swap! opened conj p))} {:error "no disk" :code 9}))
    (is (= ["/p.clj"] @opened))
    (is (= [[:error "; open: boom\n"] [:error "; open: no disk\n"]] @appended))))

(deftest save-result-decisions-test
  (let [log (atom [])
        ctx {:ed ::ed :cv ::cv :win ::win :path-atom (atom nil)}]
    (with-redefs [win-files/save-to! (fn [_ _ _ path] (swap! log conj [:save path]))
                  win-files/update-title! (fn [_ _ _] (swap! log conj [:title]))
                  win-files/dialog-dismissed? (fn [r] (= 2 (:code r)))
                  cview/append! (fn [_ kind text] (swap! log conj [kind text]))]
      (#'win-files/contained-save-result! ctx {:path "/s.clj"})
      (#'win-files/contained-save-result! ctx {:error "dismissed" :code 2})
      (#'win-files/contained-save-result! ctx {:error "full" :code 9}))
    (is (= [[:save "/s.clj"] [:title] [:error "; save: full\n"]] @log))
    (reset! log [])
    (with-redefs [win-files/save-to! (fn [& _] (throw (ex-info "boom" {})))
                  win-files/update-title! (fn [_ _ _] (swap! log conj [:title]))
                  win-files/dialog-dismissed? (fn [r] (= 2 (:code r)))
                  cview/append! (fn [_ kind text] (swap! log conj [kind text]))]
      (#'win-files/contained-save-result! ctx {:path "/s.clj"}))
    (is (= [[:error "; save: boom\n"]] @log))))

(deftest folder-text-test
  ;; what the header bar shows under the file's name
  (is (= "~/dev/demo/src" (win-files/folder-text "/Users/me/dev/demo/src/core.clj" "/Users/me")))
  (is (= "~" (win-files/folder-text "/Users/me/notes.clj" "/Users/me")))
  (is (= "/tmp/x" (win-files/folder-text "/tmp/x/a.clj" "/Users/me")))
  (is (= "/Users/meow" (win-files/folder-text "/Users/meow/a.clj" "/Users/me")) "another home with the same start")
  (is (= "/" (win-files/folder-text "/a.clj" "/Users/me")))
  (is (= "" (win-files/folder-text nil "/Users/me")) "an Untitled buffer has no folder")
  (is (= "" (win-files/folder-text "a.clj" "/Users/me")) "a bare name has none either"))
