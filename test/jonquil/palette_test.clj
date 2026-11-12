(ns jonquil.palette-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.keymap :as km]
            [jonquil.palette :as palette]))

(deftest period-labels-test
  (is (= "Cmd+." (palette/chord-label {:key "period" :mods #{:cmd}} "Mac OS X")))
  (is (= "Ctrl+>" (palette/chord-label {:key "greater" :mods #{:cmd}} "Linux"))))

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

(deftest review-plugin-proposals-is-found-test
  ;; the console's "(Review Plugin Proposals in the palette)"
  ;; finds the command
  (let [es (palette/entries {:commands registry/window-commands :keymap {} :os "Linux"})]
    (is (some #(= :plugins/review (:id %)) (palette/filter-entries es "Review Plugin Proposals")))))

(deftest plugins-is-found-test
  ;; the guide's "Plugins…" finds the list's command
  (let [es (palette/entries {:commands registry/window-commands :keymap {} :os "Linux"})]
    (is (some #(= :plugins/list (:id %)) (palette/filter-entries es "Plugins")))))

(deftest tab-entries-test
  ;; every other open tab, most recently used first; never the current one
  (let [recent [{:id 3 :label "c.clj"} {:id 1 :label "a.clj"} {:id 2 :label "b.clj"}]]
    (is (= [{:id [:tab/switch 1] :doc "Switch to tab: a.clj" :chords [] :source "tab"}
            {:id [:tab/switch 2] :doc "Switch to tab: b.clj" :chords [] :source "tab"}]
           (palette/tab-entries recent 3)))
    (is (= [] (palette/tab-entries [{:id 1 :label "a.clj"}] 1)) "one tab: nothing to switch to")))

(deftest compare-tab-entries-test
  ;; LM3: every other open tab to compare the current one with, most
  ;; recently used first; never the current one
  (let [recent [{:id 3 :label "c.clj"} {:id 1 :label "a.clj"} {:id 2 :label "b.clj"}]]
    (is (= [{:id [:compare/tab 1] :doc "Compare with tab: a.clj" :chords [] :source "compare"}
            {:id [:compare/tab 2] :doc "Compare with tab: b.clj" :chords [] :source "compare"}]
           (palette/compare-tab-entries recent 3)))
    (is (= [] (palette/compare-tab-entries [{:id 1 :label "a.clj"}] 1)) "one tab: nothing to compare with")))

;; the panels a switch can bring back, most recently shown first; never the
;; one showing
(deftest panel-entries-test
  (is (= [{:id [:panel/switch :inspector] :doc "Switch to panel: Inspector" :chords [] :source "panel"}
          {:id [:panel/switch :trace] :doc "Switch to panel: Trace" :chords [] :source "panel"}]
         (palette/panel-entries [:locations :inspector :trace] :locations)))
  (is (= ["Switch to panel: Locations" "Switch to panel: Frames"]
         (map :doc (palette/panel-entries [:locations :debugger] nil)))
      "nothing showing: every recent panel, Frames for the debugger")
  (is (= [] (palette/panel-entries [:source] :source)) "only the one showing")
  (is (= ["Switch to panel: Messages"] (map :doc (palette/panel-entries [:messages] nil))))
  (is (= ["Switch to panel: Processes"] (map :doc (palette/panel-entries [:processes] nil))))
  (is (= [] (palette/panel-entries [:review :history] nil)) "review and history are never offered"))

(deftest world-queries-pick-their-own-command-test
  (testing "the palette runs the first match, so each world query must reach its own command first"
    (let [es (shipped-entries)
          first-id (fn [q] (:id (first (palette/filter-entries es q))))]
      (is (= :world/save (first-id "save world")))
      (is (= :world/restore (first-id "restore world")))
      (is (= :world/restore-earlier (first-id "earlier world")))
      (is (= [:world/save] (map :id (palette/filter-entries es "save world")))
          "Restore World's doc holds no save, so it doesn't match at all"))))

(deftest compare-queries-pick-the-whole-tab-test
  (testing "the form-scoped compare commands share no query with the whole-tab ones"
    (let [es (shipped-entries)
          first-id (fn [q] (:id (first (palette/filter-entries es q))))]
      (doseq [[q id] [["compare with the file on disk" :compare/disk]
                      ["compare with git head" :compare/head]
                      ["compare with the last run" :compare/last-run]]]
        (is (= id (first-id q)) q)
        (is (= id (:run (palette/bang-pick es (str "!" q)))) (str "!" q " runs it at the prompt")))
      (is (= :compare-form/head (:run (palette/bang-pick es "!this form vs git head")))))))

(deftest breakpoint-queries-keep-their-neighbours-test
  (testing "the breakpoint commands take no query the debugger's own commands answered"
    (let [es (shipped-entries)
          first-id (fn [q] (:id (first (palette/filter-entries es q))))]
      (doseq [[q id] [["break on error" :debug/break-on-error]
                      ["show frames" :debug/show-frames]
                      ["continue from the break" :debug/continue]
                      ["clear line counts" :run/clear-line-counts]
                      ["count how often each line runs" :run/line-counts]
                      ["toggle a breakpoint" :debug/toggle-breakpoint]
                      ["clear all breakpoints" :debug/clear-breakpoints]
                      ["step to the next line" :debug/step]
                      ["go to the next place" :locations/next]]]
        (is (= id (first-id q)) q)
        (if (#{:debug/toggle-breakpoint :debug/clear-breakpoints :debug/step} id)
          (is (= id (:run (palette/bang-pick es (str "!" q)))) (str "!" q " runs it at the prompt"))
          (is (= (palette/bang-pick (remove (comp #{:debug/toggle-breakpoint :debug/clear-breakpoints :debug/step} :id) es)
                                    (str "!" q))
                 (palette/bang-pick es (str "!" q)))
              (str "!" q " answers as it did before breakpoints")))))))

(deftest bang-pick-test
  (let [es [{:id :a/clear :doc "Clear line counts: zero Jolt's counters and hide them."}
            {:id :a/count :doc "Count how often each line runs (Jolt)."}
            {:id :a/out :doc "Clear the console"}
            {:id :a/out2 :doc "Clear the console output and the Trace panel"}]]
    (is (true? (palette/bang? "  !clear the console")))
    (is (true? (palette/bang? "!")) "! alone asks for the hint")
    (is (true? (palette/bang? "! clear")) "one word after a space")
    (is (false? (palette/bang? "(! x)")))
    (testing "real Clojure still evaluates"
      (is (false? (palette/bang? "!x")) "a symbol")
      (is (false? (palette/bang? "!=")))
      (is (false? (palette/bang? "!x\n(+ 1 2)")) "a multi-line entry")
      (is (false? (palette/bang? "!x (+ 1 2)")) "brackets")
      (is (false? (palette/bang? "!a \"b\"")) "a string"))
    (is (= {:help true} (palette/bang-pick es "!")))
    (is (= {:run :a/clear :doc "Clear line counts: zero Jolt's counters and hide them."}
           (palette/bang-pick es "!clear line counts")))
    (is (= {:run :a/out :doc "Clear the console"} (palette/bang-pick es "!  Clear the CONSOLE "))
        "a doc equal to the words wins over the longer one it also matches")
    (is (= {:choices ["Clear line counts: zero Jolt's counters and hide them." "Clear the console"
                      "Clear the console output and the Trace panel"]
            :words "clear"}
           (palette/bang-pick es "!clear")))
    (is (= {:none "zap"} (palette/bang-pick es "!zap")))
    (is (= {:run :b/c :doc "Clear the console."}
           (palette/bang-pick [{:id :b/c :doc "Clear the console."} {:id :b/o :doc "Clear the console output."}]
                              "!clear the console"))
        "the doc's closing period doesn't count against an exact match")))

(deftest last-session-commands-pick-first-test
  (let [es (shipped-entries)
        first-id (fn [q] (:id (first (palette/filter-entries es q))))]
    (is (= :session/open-replay (first-id "replay")))
    (is (= :session/forget-last (first-id "forget last")))
    (is (= :layout/forget (first-id "forget")) "bare forget sorts the layout command first, by doc")))

(deftest change-set-queries-keep-their-neighbours-test
  (testing "the change set commands share no whole query with the commands they sit beside"
    (let [es (shipped-entries)
          first-id (fn [q] (:id (first (palette/filter-entries es q))))]
      (doseq [[q id] [["run the forms changed since they last ran" :run/changed]
                      ["save the buffer under a new name" :file/save-as]
                      ["review the plugin proposals" :plugins/review]
                      ["reload your code" :debug/locals-mode]
                      ["compare with git head" :compare/head]
                      ["this form vs git head" :compare-form/head]
                      ["this form" :compare-form/head]
                      ["make the text larger" :view/zoom-in]
                      ["save world" :world/save]]]
        (is (= id (first-id q)) q))
      (doseq [[q id] [["forms changed since" :run/changed]
                      ["under a new name" :file/save-as]
                      ["review the plugin" :plugins/review]
                      ["reload your code" :debug/locals-mode]
                      ["compare with git head" :compare/head]]]
        (is (= id (:run (palette/bang-pick es (str "!" q)))) (str "!" q " runs it at the prompt")))))
  (testing "their own words reach them"
    (let [es (shipped-entries)]
      (is (= :change-set/browse (:run (palette/bang-pick es "!change sets"))))
      (is (= :change-set/make (:run (palette/bang-pick es "!make a change set"))))
      (is (= :change-set/make (:run (palette/bang-pick es "!make a change set from the forms changed in the file")))))))

;; LM6's docs panel adds four commands. A query that picked one command by
;; the `!` line or the palette's first row must still pick that command.
(defn- pages? [e] (= "pages" (namespace (:id e))))

(defn- queries-from
  "Every word of the docs, every pair of neighbouring words and every whole
  doc: the queries a person would type to reach those commands."
  [entries]
  (let [words (fn [doc] (remove empty? (str/split (str/lower-case doc) #"[^\p{L}\p{N}'/-]+")))]
    (distinct
     (mapcat (fn [{:keys [doc]}]
               (let [ws (words doc)]
                 (concat ws (map #(str/join " " %) (partition 2 1 ws)) [(str/join " " ws)])))
             entries))))

(deftest docs-panel-commands-take-over-no-query-test
  (let [es (shipped-entries)
        others (vec (remove pages? es))
        mine (filterv pages? es)
        qs (queries-from others)
        first-id (fn [entries q] (:id (first (palette/filter-entries entries q))))]
    (is (= 4 (count mine)))
    (is (< 500 (count qs)) "a broad set of queries")
    (doseq [q qs
            :let [before (palette/bang-pick others (str "! " q))
                  after (palette/bang-pick es (str "! " q))]
            :when (:run before)]
      (is (= before after) (str "`! " q "` still runs the same command")))
    (testing "the palette's first row for a distinctive query (three letters or more,
              at most two commands matched it) is unchanged"
      (doseq [q qs :when (and (<= 3 (count q)) (<= 1 (count (palette/filter-entries others q)) 2))]
        (is (= (first-id others q) (first-id es q))
            (str "first row of \"" q "\" unchanged"))))
    (testing "the queries named in the roadmap"
      (doseq [q ["show frames" "compare with git head" "show docs" "docs" "quick doc"]]
        (is (empty? (filter pages? (palette/filter-entries es q))) q)))))

(deftest docs-panel-commands-are-found-by-their-words-test
  (let [es (shipped-entries)
        pick (fn [q] (palette/bang-pick es (str "! " q)))]
    (is (= :pages/show (:run (pick "show the documentation in a panel"))))
    (is (= :pages/bookmark (:run (pick "bookmark this page"))))
    (is (= :pages/bookmarks (:run (pick "bookmarked page"))))
    (is (= :pages/guide (:run (pick "guide page"))))
    (is (= :pages/guide (:run (pick "open a guide page"))))
    (is (= :pages/show (:id (first (palette/filter-entries es "documentation")))))))

(deftest bare-doc-and-docs-queries-still-reach-quick-doc-test
  (let [es (shipped-entries)
        others (vec (remove pages? es))
        with-panel (into (palette/panel-entries [:reader] nil) es)
        first-id (fn [entries q] (:id (first (palette/filter-entries entries q))))]
    (doseq [q ["do" "doc" "docs" "show docs" "quick doc" "the docs"]]
      (is (= (first-id others q) (first-id es q)) (str "palette first row of \"" q "\""))
      (is (= (first-id others q) (first-id with-panel q))
          (str "first row of \"" q "\" with the Reader panel entry listed"))
      (is (= (:run (palette/bang-pick others (str "!" q))) (:run (palette/bang-pick es (str "!" q))))
          (str "`!" q "` picks as on main")))
    (is (= :help/quick-doc (first-id es "doc")))
    (is (= :help/quick-doc (:run (palette/bang-pick es "!docs"))))))
