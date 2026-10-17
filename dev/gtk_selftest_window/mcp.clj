(ns gtk-selftest-window.mcp
  "The notifying hooks the MCP server drives (:mcp-run!*, :mcp-eval!*,
  :mcp-run-selection!*): each calls notify exactly once and returns a reader
  of the output collected so far.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each.
  A hook runs on the GTK thread through on-gtk; the row thread then derefs
  the notify promise with a deadline, never inside on-gtk."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.tabs :as tabs]
            [temp-dirs]))

(def ^:private DEADLINE-MS 5000)

(defn- fire!
  "Runs the hook (f notify) or (f a notify) ... on the GTK thread; returns
  [reader done-promise], where the row's own notify delivers the map."
  [f & args]
  (on-gtk (let [done (promise)
                reader (apply f (concat args [(fn [m] (deliver done m))]))]
            [reader done])))

(defn- await-done [done]
  (deref done DEADLINE-MS ::timeout))

(defn row-mcp-eval-done [ui]
  (go-row :mcp-eval-done support/check
          (let [mark (on-gtk (support/out-end-offset ui))
                [reader done] (fire! (:mcp-eval!* ui) "(println \"hi\") (+ 1 2)")
                m (await-done done)
                {:keys [result output]} (:done m)
                echoed? (await-until 3000 20 #(let [t (support/since ui mark)]
                                                (and (str/includes? t "(println \"hi\") (+ 1 2)")
                                                     (str/includes? t "\n=> 3"))))
                read (on-gtk (reader))]
            (on-gtk (support/check :mcp-eval-done
                                   (and (= :ok (:status result)) (= "hi\n" output)
                                        (= "hi\n" read) echoed?)
                                   {:msg m :read read :echoed echoed?
                                    :console (support/since ui mark)})))))

(defn row-mcp-eval-busy [ui]
  (go-row :mcp-eval-busy support/check
          (let [[_ done1] (fire! (:mcp-eval!* ui) "(Thread/sleep 1500)")
                mark (on-gtk (support/out-end-offset ui))
                [_ done2] (fire! (:mcp-eval!* ui) "(+ 1 1)")
                m2 (await-done done2)
                busy (:busy m2)
                shown? (await-until 3000 20 #(let [t (support/since ui mark)]
                                               (and (str/includes? t "(+ 1 1)")
                                                    (str/includes? t "already in progress"))))
                m1 (await-done done1)
                idle? (await-until 3000 20 #(support/idle? ui))]
            (on-gtk (support/check :mcp-eval-busy
                                   (and (string? busy) (not (str/blank? busy)) shown?
                                        (:done m1) idle?)
                                   {:m2 m2 :m1 m1 :shown shown? :idle idle?
                                    :console (support/since ui mark)})))))

(defn row-mcp-run-done [ui]
  (go-row :mcp-run-done support/check
          (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                               (editor/set-text! (:editor ui) "(def a 6) (* a 7)")
                               m))
                [_ done] (fire! (:mcp-run!* ui))
                m (await-done done)
                {:keys [result output]} (:done m)
                shown? (await-until 3000 20 #(let [t (support/since ui mark)]
                                               (and (str/includes? t "; --- Running ")
                                                    (str/includes? t "\n=> 42"))))]
            (on-gtk (support/check :mcp-run-done
                                   (and (= :ok (:status result)) (= "" output) shown?)
                                   {:msg m :shown shown? :console (support/since ui mark)})))))

(defn row-mcp-run-problem [ui]
  (go-row :mcp-run-problem support/check
          (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                               (editor/set-text! (:editor ui) "(+ 1")
                               m))
                [_ done] (fire! (:mcp-run!* ui))
                m (await-done done)
                problem (:problem m)
                idle? (on-gtk (support/idle? ui))
                shown? (await-until 3000 20 #(let [t (support/since ui mark)
                                                   i (str/index-of t "; --- Running ")]
                                               (and i (str/includes? (subs t i) "is never closed"))))]
            (on-gtk (support/check :mcp-run-problem
                                   (and (string? problem) (str/includes? problem "is never closed")
                                        idle? shown?)
                                   {:msg m :idle idle? :shown shown? :console (support/since ui mark)})))))

(defn- cljd-project!
  "A temp ClojureDart project (pubspec.yaml) with lib/x.cljd, like the tabs
  feature file's own fixture (private there); the file's path."
  []
  (let [dir (temp-dirs/temp-dir! "jonquil-selftest-mcp-")]
    (spit (io/file dir "pubspec.yaml") "name: other\n")
    (let [f (io/file dir "lib" "x.cljd")]
      (io/make-parents f)
      (spit f "(ns other.x)\n")
      (.getCanonicalPath f))))

(defn- close-extra-tabs!
  "Marks every tab but the first saved and closes it, then selects the
  first. GTK-thread only."
  [ui]
  (doseq [t (rest ((:tabs* ui)))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first ((:tabs* ui)))))
  (reset! (:alert-pending? ui) false))

(defn row-mcp-run-refused [ui]
  (go-row :mcp-run-refused support/check
    ;; Two tabs first: with one tab, opening a foreign file switches the
    ;; window's project instead of opening a refused third tab.
          (let [[mark expected] (on-gtk
                                 (editor/set-text! (:ed (first ((:tabs* ui)))) ";; mcp\n")
                                 ((:new-tab!* ui))
                                 (editor/set-text! (:ed ((:active-tab* ui))) ";; two\n")
                                 ((:open-file! ui) (cljd-project!))
                                 (let [t ((:active-tab* ui))]
                                   [(support/out-end-offset ui)
                                    (tabs/refusal-message {:file "x.cljd" :tab-sel @(:project t)
                                                           :window-sel ((:selection* ui))})]))]
            (try
              (let [[_ done] (fire! (:mcp-run!* ui))
                    m (await-done done)
                    s (:refused m)
                    shown? (await-until 3000 20 #(str/includes? (support/since ui mark) "so it can't run here."))
                    [idle? banner?] (on-gtk [(support/idle? ui)
                                             (str/includes? (support/since ui mark) "; --- Running")])]
                (on-gtk (support/check :mcp-run-refused
                                       (and (= expected s) shown? idle? (not banner?))
                                       {:msg m :expected expected :shown shown? :idle idle?
                                        :console (support/since ui mark)})))
              (finally (on-gtk (close-extra-tabs! ui)))))))

(defn row-mcp-run-selection-done [ui]
  (go-row :mcp-run-selection-done support/check
          (let [text "(+ 1 1)\n(+ 2 2)"
                mark (on-gtk (let [m (support/out-end-offset ui)]
                               (editor/set-text! (:editor ui) text)
                               m))
                [_ done] (fire! (:mcp-run-selection!* ui) 8 15)
                m (await-done done)
                {:keys [result]} (:done m)
                shown? (await-until 3000 20 #(let [t (support/since ui mark)]
                                               (and (str/includes? t "(+ 2 2)")
                                                    (str/includes? t "\n=> 4"))))
                sel (on-gtk (g/selection-offsets (:buf (:editor ui))))
          ;; A blank range: the empty line of "(+ 1 1)\n\n".
                _ (on-gtk (editor/set-text! (:editor ui) "(+ 1 1)\n\n"))
                [_ done2] (fire! (:mcp-run-selection!* ui) 8 8)
                m2 (await-done done2)]
            (on-gtk (support/check :mcp-run-selection-done
                                   (and (= :ok (:status result)) (= "4" (:printed result))
                                        shown? (= [8 15] sel)
                                        (string? (:none m2)) (not (str/blank? (:none m2))))
                                   {:msg m :shown shown? :sel sel :m2 m2
                                    :console (support/since ui mark)})))))
