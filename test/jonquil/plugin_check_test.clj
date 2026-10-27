(ns jonquil.plugin-check-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as registry]
            [jonquil.keymap :as km]
            [jonquil.plugin-check :as pc]))

(def ^:private request
  (pc/request-from-registry {:commands registry/commands
                             :conditions (into (set (keys registry/window-conditions))
                                               (keys registry/editor-conditions))
                             :keymap km/shipped :os "Linux"}))

(def ^:private good
  {:id :wrap-when
   :doc "Wrap the form at the caret in (when ...)."
   :effects #{}
   :commands [{:id :wrap-when/wrap :doc "Wrap the form at the caret in (when ...)."
               :scope :editor :edit (fn [s] s)}]
   ;; the spec's example chord is Cmd+Alt+W, which the window's Cmd+W
   ;; (close the tab) takes first; J is free in both scopes
   :bindings [{:key "j" :mods #{:cmd :alt} :command :wrap-when/wrap}]})

(defn- check
  ([defs] (check defs "wrap-when"))
  ([defs file-id] (pc/validate (assoc request :definitions defs :file-id file-id))))

(defn- one-problem [result]
  (and (false? (:ok? result)) (= 1 (count (:problems result)))))

(defn- with-command [over]
  (assoc good :commands [(merge (first (:commands good)) over)] :bindings []))

(deftest good-plugin-passes-test
  (is (= {:ok? true :problems [] :warnings []} (check [good]))))

(deftest definition-count-test
  (is (one-problem (check [])) "no defplugin")
  (is (one-problem (check [good good])) "two"))

(deftest id-test
  (is (one-problem (check [(assoc good :id :Wrap)] "Wrap")) "file id fails the regex")
  (is (one-problem (check [(assoc good :id :other)])) ":id differs from the file")
  (is (one-problem (check [(assoc good :id "wrap-when")])) ":id is a string")
  (is (one-problem (check [(dissoc good :doc)])) "no :doc"))

(deftest effects-test
  (let [r (check [(dissoc good :effects)])]
    (is (one-problem r))
    (is (str/includes? (first (:problems r)) "#{}")))
  (is (one-problem (check [(assoc good :effects [:console])])) "not a set")
  (is (one-problem (check [(assoc good :effects #{:disk})])) "unknown effect")
  (is (:ok? (check [(assoc good :effects #{})]))))

(deftest commands-test
  (doseq [[why over] [["wrong namespace" {:id :other/wrap}]
                      ["no doc" {:doc nil}]
                      ["console scope" {:scope :console}]
                      ["both" {:run (fn [_ _] :handled)}]
                      ["neither" {:edit nil}]
                      ["edit in window scope" {:scope :window}]]]
    (is (one-problem (check [(with-command over)])) why))
  (is (one-problem (check [(assoc good :commands [(first (:commands good)) (first (:commands good))]
                                  :bindings [])]))
      "duplicate id")
  (let [run-cmd {:id :wrap-when/say :doc "Say hi." :scope :window :run (fn [_ _] nil)}]
    (is (one-problem (check [(assoc good :commands [run-cmd] :bindings [])])) ":run in a pure plugin")
    (is (:ok? (check [(assoc good :effects #{:console} :commands [run-cmd] :bindings [])])))))

(deftest hooks-test
  (is (one-problem (check [(assoc good :hooks {:before-save (fn [_ e] e)})])) "unknown hook")
  (is (one-problem (check [(assoc good :hooks {:after-save "no"})])) "not a fn")
  (is (:ok? (check [(assoc good :hooks {:after-save (fn [_ e] e)})]))))

(deftest unknown-keys-test
  (is (one-problem (check [(assoc good :bindngs [])]))))

(deftest malformed-shapes-test
  (doseq [[why defs] [["definition is a vector" [[:id :wrap-when]]]
                      [":commands is a map" [(assoc good :commands {:a 1} :bindings [])]]
                      ["a command is a string" [(assoc good :commands ["wrap"] :bindings [])]]
                      [":effects is a string" [(assoc good :effects "pure")]]
                      [":hooks is a vector" [(assoc good :hooks [:after-save])]]]]
    (let [r (try (check defs) (catch :default e {:threw (ex-message e)}))]
      (is (nil? (:threw r)) (str why ": " (:threw r)))
      (is (and (false? (:ok? r)) (seq (:problems r))) why))))

(deftest ok-agrees-with-problems-test
  (doseq [defs [[good] [] [(dissoc good :effects)]]]
    (let [r (check defs)]
      (is (= (:ok? r) (empty? (:problems r)))))))

;; --- bindings --------------------------------------------------------------

(def ^:private both
  ;; an :editor and a :window command, so bindings can reach either scope
  (assoc good :effects #{:console}
         :commands [(first (:commands good))
                    {:id :wrap-when/say :doc "Say hi." :scope :window :run (fn [_ _] nil)}]))

(defn- bind [b] (check [(assoc both :bindings [b])]))

(defn- mentions? [result k s]
  (some #(str/includes? % s) (get result k)))

(deftest typing-keys-test
  (doseq [b [{:key "w" :command :wrap-when/wrap}
             {:key "w" :mods #{:shift} :command :wrap-when/wrap}
             {:key "w" :mods #{:alt} :command :wrap-when/wrap}
             {:key "Return" :command :wrap-when/wrap}
             {:key "Return" :mods #{:shift} :command :wrap-when/wrap}
             {:key "space" :command :wrap-when/wrap}
             {:key "w" :command :wrap-when/say}
             {:key "w" :command :run/run}]]
    (let [r (bind b)]
      (is (one-problem r) (pr-str b))
      (is (mentions? r :problems "a key you type") (pr-str b))))
  (is (:ok? (bind {:key "j" :mods #{:cmd :alt} :command :wrap-when/wrap})))
  (is (:ok? (bind {:key "F2" :mods #{:shift :alt} :command :wrap-when/wrap}))))

(deftest unreachable-test
  (let [r (bind {:key "r" :mods #{:cmd} :command :wrap-when/wrap})]
    (is (one-problem r))
    (is (mentions? r :problems ":run/run"))
    (is (mentions? r :problems ":window")))
  (is (one-problem (bind {:key "r" :mods #{:cmd :alt} :command :wrap-when/wrap}))
      "Cmd+Alt+R too: the window's Cmd+R doesn't exclude Alt")
  (is (:ok? (bind {:key "j" :mods #{:cmd :alt} :command :wrap-when/wrap}))))

(deftest shadow-test
  (let [r (bind {:key "w" :mods #{:cmd} :command :wrap-when/say})]
    (is (:ok? r))
    (is (mentions? r :warnings ":tab/close")))
  (let [r (bind {:key "slash" :mods #{:cmd} :command :wrap-when/say})]
    (is (:ok? r))
    (is (mentions? r :warnings ":edit/toggle-comment")))
  (let [r (bind {:key "b" :mods #{:ctrl} :command :wrap-when/wrap})]
    (is (:ok? r))
    (is (mentions? r :warnings ":help/jump")))
  (is (= [] (:warnings (bind {:key "F2" :mods #{:shift :alt} :command :wrap-when/wrap})))))

(deftest binding-scope-test
  (let [r (bind {:key "j" :mods #{:cmd :alt} :command :console/complete})]
    (is (one-problem r))
    (is (mentions? r :problems "console")))
  (is (:ok? (bind {:key "j" :mods #{:cmd :alt} :command :run/run}))
      "a registered window command, validated as :window"))

(deftest binding-parse-test
  (doseq [b [{:key "nope" :mods #{:cmd} :command :wrap-when/wrap}
             {:key "j" :mods #{:cmd} :command :wrap-when/missing}
             {:key "j" :mods #{:cmd} :when [:nope] :command :wrap-when/wrap}]]
    (let [r (bind b)]
      (is (one-problem r) (pr-str b))
      (is (mentions? r :problems "bindings: ") (pr-str b)))))

;; --- review fixes ----------------------------------------------------------

(deftest conditional-window-binding-is-not-unreachable-test
  ;; the window takes these only sometimes: a :when guard (Cmd+G while the
  ;; find bar can navigate), a command that can pass the key on (Escape's
  ;; popover close, Cmd+['s jump back), the chain's own :to-gtk end
  (doseq [[b shadowed] [[{:key "g" :mods #{:cmd} :command :wrap-when/wrap} ":find/next"]
                        [{:key "Escape" :command :wrap-when/wrap} ":find/bar-key"]
                        [{:key "bracketleft" :mods #{:cmd} :command :wrap-when/wrap} ":source/back"]]]
    (let [r (bind b)]
      (is (:ok? r) (pr-str b r))
      (is (mentions? r :warnings shadowed) (pr-str b r)))))

(deftest guarded-then-unconditional-is-unreachable-test
  ;; Cmd+Shift+O: Open Here while the source panel has focus, else Open
  ;; (Shift isn't in its :not), so the window takes it whichever way
  (let [r (bind {:key "o" :mods #{:cmd :shift} :command :wrap-when/wrap})]
    (is (one-problem r) (pr-str r))
    (is (mentions? r :problems "can never fire"))))

(deftest malformed-bindings-never-throw-test
  (doseq [b [{:key "j" :mods :cmd :command :wrap-when/wrap}
             {:key "j" :mods 5 :command :wrap-when/wrap}
             {:key "j" :not :alt :mods #{:cmd} :command :wrap-when/wrap}
             {:keys :j :mods #{:cmd} :command :wrap-when/wrap}]]
    (let [r (try (bind b) (catch :default e {:threw (ex-message e)}))]
      (is (nil? (:threw r)) (str (pr-str b) " threw " (:threw r)))
      (is (one-problem r) (pr-str b r))))
  (doseq [cs [:x 5]]
    (let [r (try (check [(assoc good :commands cs)]) (catch :default e {:threw (ex-message e)}))]
      (is (nil? (:threw r)) (str cs " threw " (:threw r)))
      (is (false? (:ok? r))))))

(deftest request-conditions-as-any-collection-test
  (doseq [cs [nil [] [:writable] :x]]
    (let [r (try (pc/validate (assoc request :conditions cs :file-id "wrap-when"
                                      :definitions [(assoc both :bindings [{:key "j" :mods #{:cmd :alt} :when [:writable]
                                                                            :command :wrap-when/wrap}])]))
                 (catch :default e {:threw (ex-message e)}))]
      (is (nil? (:threw r)) (str (pr-str cs) " threw " (:threw r))))))

(deftest bad-command-scope-is-one-problem-test
  (let [r (check [(assoc both :commands [(assoc (first (:commands both)) :scope :console)]
                         :bindings [{:key "j" :mods #{:cmd :alt} :command :wrap-when/wrap}])])]
    (is (one-problem r) (pr-str r))))

(deftest meta-and-super-labels-test
  (is (mentions? (bind {:key "w" :mods #{:meta} :command :wrap-when/say}) :warnings "Meta+W"))
  (is (mentions? (bind {:key "a" :mods #{:super} :command :wrap-when/say}) :problems "Super+A")))

(deftest conditions-per-scope-test
  ;; with conditions given per scope, a binding's :when must name its own
  ;; scope's (review I3: :tree-focused is a window condition)
  (let [per-scope (assoc request :conditions {:window (set (keys registry/window-conditions))
                                              :editor (set (keys registry/editor-conditions))})
        run (fn [b] (pc/validate (assoc per-scope :file-id "wrap-when" :definitions [(assoc both :bindings [b])])))]
    (is (one-problem (run {:key "j" :mods #{:cmd :alt} :when [:tree-focused] :command :wrap-when/wrap})))
    (is (:ok? (run {:key "j" :mods #{:cmd :alt} :when [:writable] :command :wrap-when/wrap})))
    (is (:ok? (run {:key "j" :mods #{:cmd :alt} :when [:tree-focused] :command :wrap-when/say})))))

(deftest earlier-plugin-chord-never-fires-test
  ;; an earlier plugin's binding (tagged :plugin/source in the request's
  ;; keymap) on the same chord always wins, so this binding never fires: a
  ;; warning that says so and names that plugin (review I-A)
  (let [req (-> request
                (assoc-in [:registered :p-a/wrap] {:scope :editor :passes? false :plugin :p-a})
                (update-in [:keymap :editor] #(into [{:key "j" :mods #{:cmd :alt} :command :p-a/wrap
                                                      :plugin/source :p-a}] %)))
        r (pc/validate (assoc req :file-id "wrap-when" :definitions [good]))]
    (is (:ok? r) (pr-str r))
    (is (mentions? r :warnings "never fires"))
    (is (mentions? r :warnings "plugin p-a"))))

(deftest earlier-plugin-window-binding-is-a-warning-test
  ;; an earlier plugin binding a built-in window command to a chord makes a
  ;; later :editor binding there dead: a warning naming that plugin, not a
  ;; problem blaming the built-in (review I-B)
  (let [req (update-in request [:keymap :window]
                       #(into [{:key "j" :mods #{:cmd :alt} :command :run/run :plugin/source :p-a}] %))
        r (pc/validate (assoc req :file-id "wrap-when" :definitions [good]))]
    (is (:ok? r) (pr-str r))
    (is (mentions? r :warnings "plugin p-a"))
    (is (mentions? r :warnings "never fires"))))

;; --- review of 140c969: every case walked as dispatch walks it -------------

(defn- with-earlier
  "request with earlier plugin p-a's bindings bs (tagged) ahead of the
  shipped ones in their scopes; p-a's own command :p-a/x is :editor."
  [bs]
  (reduce (fn [req [scope b]]
            (update-in req [:keymap scope] #(into [(assoc b :plugin/source :p-a)] %)))
          (assoc-in request [:registered :p-a/x] {:scope :editor :passes? false :plugin :p-a})
          bs))

(defn- check-with [req defs] (pc/validate (assoc req :file-id "wrap-when" :definitions defs)))

(deftest guarded-earlier-binding-is-sometimes-test
  ;; a guarded earlier binding takes the chord only while its guard holds
  (doseq [[why bs defs]
          [["editor vs guarded earlier editor"
            [[:editor {:key "j" :mods #{:cmd :alt} :when [:writable] :command :p-a/x}]] [good]]
           ["window vs guarded earlier window"
            [[:window {:key "j" :mods #{:cmd :alt} :when [:tree-focused] :command :run/run}]]
            [(assoc both :bindings [{:key "j" :mods #{:cmd :alt} :command :wrap-when/say}])]]
           ["editor vs guarded earlier window"
            [[:window {:key "j" :mods #{:cmd :alt} :when [:tree-focused] :command :run/run}]] [good]]]]
    (let [r (check-with (with-earlier bs) defs)]
      (is (:ok? r) (str why " " (pr-str r)))
      (is (not (mentions? r :warnings "never fires")) (str why " " (pr-str r)))
      (is (mentions? r :warnings "plugin p-a") (str why " " (pr-str r))))))

(deftest built-in-unreachable-survives-an-earlier-plugin-test
  ;; a guarded earlier plugin binding ahead of the built-in Cmd+R: the
  ;; built-in still always takes it, so it's the load-blocking problem
  (let [r (check-with (with-earlier [[:window {:key "r" :mods #{:cmd} :when [:tree-focused] :command :run/run}]])
                      [(assoc good :bindings [{:key "r" :mods #{:cmd} :command :wrap-when/wrap}])])]
    (is (false? (:ok? r)) (pr-str r))
    (is (mentions? r :problems "can never fire"))
    (is (mentions? r :problems ":run/run"))))

(deftest unconditional-earlier-plugin-is-never-fires-test
  (doseq [[why bs defs]
          [["editor vs earlier editor" [[:editor {:key "j" :mods #{:cmd :alt} :command :p-a/x}]] [good]]
           ["editor vs earlier window" [[:window {:key "j" :mods #{:cmd :alt} :command :run/run}]] [good]]
           ["window vs earlier window" [[:window {:key "j" :mods #{:cmd :alt} :command :run/run}]]
            [(assoc both :bindings [{:key "j" :mods #{:cmd :alt} :command :wrap-when/say}])]]]]
    (let [r (check-with (with-earlier bs) defs)]
      (is (:ok? r) (str why " " (pr-str r)))
      (is (mentions? r :warnings "never fires") (str why " " (pr-str r))))))

(deftest window-over-earlier-editor-takes-it-test
  ;; the window sees keys first, so this one takes the earlier editor chord
  (let [r (check-with (with-earlier [[:editor {:key "j" :mods #{:cmd :alt} :command :p-a/x}]])
                      [(assoc both :bindings [{:key "j" :mods #{:cmd :alt} :command :wrap-when/say}])])]
    (is (:ok? r))
    (is (mentions? r :warnings "takes that chord from plugin p-a"))
    (is (not (mentions? r :warnings "never fires")))))

;; --- review of c8735f3 -----------------------------------------------------

(deftest built-in-always-take-is-a-problem-behind-a-plugin-test
  ;; p-a's Cmd+R window binding hides the built-in Run, but Run would take
  ;; it too, so this editor binding is refused whether or not p-a is there
  (let [r (check-with (with-earlier [[:window {:key "r" :mods #{:cmd} :command :tab/new}]])
                      [(assoc good :bindings [{:key "r" :mods #{:cmd} :command :wrap-when/wrap}])])]
    (is (false? (:ok? r)) (pr-str r))
    (is (mentions? r :problems ":run/run"))))

(deftest partly-unreachable-is-a-warning-test
  ;; :mods #{:cmd} on P also matches Cmd+Shift+P, the palette's; the chord
  ;; meant (Cmd+P) works, so that's a warning naming the taken chord, not a
  ;; refusal (only a binding unreachable on every chord it matches is)
  (let [r (bind {:key "p" :mods #{:cmd} :command :wrap-when/wrap})]
    (is (:ok? r) (pr-str r))
    (is (mentions? r :warnings ":palette/open"))
    (is (mentions? r :warnings "Shift"))))

(deftest no-sometimes-where-unreachable-test
  ;; a guarded earlier plugin binding ahead of the built-in Cmd+R: only the
  ;; problem, not also "takes that chord only when ... doesn't"
  (let [r (check-with (with-earlier [[:window {:key "r" :mods #{:cmd} :when [:tree-focused] :command :tab/new}]])
                      [(assoc good :bindings [{:key "r" :mods #{:cmd} :command :wrap-when/wrap}])])]
    (is (false? (:ok? r)))
    (is (not (mentions? r :warnings "only when")) (pr-str r))))

;; --- :mcp-tools rows (plugins P10b) ------------------------------------------

(def ^:private tool-row
  {:name "echo" :doc "Echo the text back."
   :args {"text" {:type "string" :doc "What to echo." :required? true}}
   :run (fn [_ctx args] (get args "text"))})

(defn- with-tools [rows] {:id :mt :doc "Tools." :effects #{} :mcp-tools rows})

(deftest mcp-tools-valid-test
  (is (:ok? (check [(with-tools [tool-row])] "mt")))
  (is (:ok? (check [(with-tools [(dissoc tool-row :args)])] "mt")))
  (is (:ok? (check [(dissoc (with-tools []) :mcp-tools)] "mt")))
  (is (= "plugin_mt_echo" (pc/served-tool-name "mt" "echo"))))

(deftest mcp-tools-problems-test
  (let [has (fn [rows sentence]
              (let [r (check [(with-tools rows)] "mt")]
                (is (some #{sentence} (:problems r)) (pr-str (:problems r)))))
        l "mcp tool 0 (\"echo\")"]
    (has [:x] "mcp tool 0 isn't a map")
    (has [(assoc tool-row :name "Echo")]
         "mcp tool 0 (\"Echo\"): :name isn't lower-case letters, digits and _, starting with a letter")
    (has [tool-row tool-row] "mcp tool 1 (\"echo\"): its name is used twice")
    (let [n (apply str (repeat 60 "a"))]
      (has [(assoc tool-row :name n)]
           (str "mcp tool 0 (\"" n "\"): its served name plugin_mt_" n " is longer than 64 characters")))
    (has [(assoc tool-row :x 1)] (str l ": has unknown keys [:x]"))
    (has [(assoc tool-row :doc " ")] (str l ": :doc isn't a sentence"))
    (has [(assoc tool-row :args [])] (str l ": :args isn't a map of argument name to {:type :doc :required?}"))
    (has [(assoc tool-row :args {:text {:type "string" :doc "t"}})]
         (str l ": argument :text isn't a non-blank string name"))
    (has [(assoc tool-row :args {"text" {:type "object" :doc "t"}})]
         (str l ": argument text's :type \"object\" isn't one of [\"boolean\" \"integer\" \"number\" \"string\"]"))
    (has [(assoc tool-row :args {"text" {:type "string" :doc "t" :default 1}})]
         (str l ": argument text has unknown keys [:default]"))
    (has [(assoc tool-row :args {"text" {:type "string" :doc ""}})]
         (str l ": argument text's :doc isn't a sentence"))
    (has [(assoc tool-row :args {"text" {:type "string" :doc "t" :required? "yes"}})]
         (str l ": argument text's :required? isn't true or false"))
    (has [(assoc tool-row :run "nope")] (str l ": :run isn't a function")))
  (is (some #{":mcp-tools isn't a vector"} (:problems (check [(with-tools {})] "mt")))))
