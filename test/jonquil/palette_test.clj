(ns jonquil.palette-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.keymap :as km]
            [jonquil.palette :as palette]))

(deftest chord-label-test
  (doseq [[b mac linux] [[{:key "p" :mods #{:cmd :shift}} "Cmd+Shift+P" "Ctrl+Shift+P"]
                         [{:key "q" :mods #{:ctrl} :not #{:meta}} "Ctrl+Q" "Ctrl+Q"]
                         [{:key "F1" :exact #{:shift}} "Shift+F1" "Shift+F1"]
                         [{:keys #{"f" "l"} :mods #{:cmd :alt}}
                          "Cmd+Option+F, Cmd+Option+L" "Ctrl+Alt+F, Ctrl+Alt+L"]
                         [{:keys #{"equal" "plus"} :mods #{:cmd}} "Cmd+=, Cmd++" "Ctrl+=, Ctrl++"]
                         [{:keys #{"Return" "KP_Enter"} :mods #{:cmd}} "Cmd+Enter" "Ctrl+Enter"]
                         [{:key "F5"} "F5" "F5"]]]
    (is (= mac (palette/chord-label b "Mac OS X")) (pr-str b))
    (is (= linux (palette/chord-label b "Linux")) (pr-str b))))

(defn- shipped-entries []
  (palette/entries {:commands registry/commands :keymap km/shipped :os "Linux"}))

(deftest entries-test
  (let [es (shipped-entries)
        by-id (into {} (map (juxt :id identity)) es)]
    (is (= (set (keep (fn [[id c]] (when-not (false? (:palette? c)) id)) registry/commands))
           (set (map :id es))))
    (is (= (count es) (count (set (map :id es)))))
    (is (= (sort (map :doc es)) (map :doc es)) "sorted by doc")
    (is (every? #(= "built-in" (:source %)) es))
    (is (= ["Ctrl+Q" "Shift+F1"] (:chords (by-id :help/quick-doc))))
    (is (= [] (:chords (by-id :edit/indent))))
    (is (= ["F5" "Ctrl+R"] (:chords (by-id :run/run))))))

(deftest filter-entries-test
  (let [es (shipped-entries)
        ids (fn [q] (map :id (palette/filter-entries es q)))]
    (testing "every word, from the doc or the id"
      (is (some #{:edit/toggle-comment} (ids "toggle comment")))
      (is (some #{:edit/toggle-comment} (ids "COMMENT"))))
    (is (= [] (ids "zzz")))
    (is (= (map :id es) (ids "  ")) "a blank query keeps all")
    (let [hits (palette/filter-entries es "tab")]
      (is (< 1 (count hits)))
      (is (= (filter (set hits) es) hits) "input order kept"))))

(deftest row-text-test
  (is (= "Run the buffer.  F5, Ctrl+R  built-in"
         (palette/row-text {:doc "Run the buffer." :chords ["F5" "Ctrl+R"] :source "built-in"})))
  (is (= "Indent the lines.  built-in"
         (palette/row-text {:doc "Indent the lines." :chords [] :source "built-in"}))))

(deftest plugin-source-test
  (let [cmds (assoc registry/commands :p3-x/wrap {:doc "Wrap it." :scope :editor :plugin :p3-x
                                                  :run (fn [_ _] :handled)})
        e (some #(when (= :p3-x/wrap (:id %)) %)
                (palette/entries {:commands cmds :keymap km/shipped :os "Linux"}))]
    (is (= "p3-x" (:source e)))))

(deftest move-test
  (let [st {:shown [:a :b :c] :index 0}]
    (is (= 1 (:index (palette/move st 1))))
    (is (= 0 (:index (palette/move st -1))))
    (is (= 2 (:index (palette/move st 10)))))
  (is (= 0 (:index (palette/move {:shown [] :index 0} 1))))
  (is (= 0 (:index (palette/move {:shown [] :index 0} -1)))))
