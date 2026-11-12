(ns jonquil.parts-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.parts :as gtk-parts]
            [jonquil.gtk.messages-pane :as messages-pane]
            [jonquil.gtk.parts.command-palette :as command-palette-part]
            [jonquil.gtk.parts.debugger :as debugger-part]
            [jonquil.gtk.parts.file-tree :as file-tree-part]
            [jonquil.gtk.file-tree-pane :as file-tree-pane]
            [jonquil.gtk.parts.disk-poll :as disk-poll-part]
            [jonquil.gtk.debugger-pane :as debugger-pane]
            [jonquil.gtk.parts.layouts :as layouts-part]
            [jonquil.gtk.parts.live-results :as live-results-part]
            [jonquil.gtk.console-view :as cview]
            [jonquil.help :as help]
            [jonquil.gtk.parts.messages :as messages-part]
            [jonquil.gtk.parts.plugins :as plugins-part]
            [jonquil.gtk.plugin-list-pane :as plugin-list-pane]
            [jonquil.gtk.parts.processes :as processes-part]
            [jonquil.gtk.processes-pane :as processes-pane]
            [jonquil.gtk.parts.replay :as replay-part]
            [jonquil.gtk.parts.time-form :as time-form-part]
            [jonquil.gtk.parts.trace :as trace-part]
            [jonquil.gtk.trace-pane :as trace-pane]
            [jonquil.gtk.parts.worlds :as worlds-part]
            [jonquil.palette :as palette]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.replay :as replay]
            [jonquil.settings :as settings]
            [jonquil.usages :as usages]
            [jonquil.parts :as parts]))

(defn- call [k & args]
  (fn [ctx _args] (apply (get ctx k) args) :handled))

(def ^:private go-spec
  {:id :p :commands {:p/go {:doc "Go." :scope :window :call [:go-fn 1]}}})

(deftest merge-commands-adds-a-spec-command
  (let [seen (atom nil)
        merged (parts/merge-commands {} [go-spec] :window call)
        cmd (:p/go merged)]
    (is (= "Go." (:doc cmd)))
    (is (not (contains? cmd :call)))
    (is (= :handled ((:run cmd) {:go-fn (fn [n] (reset! seen n))} nil)))
    (is (= 1 @seen))))

(deftest merge-commands-filters-by-scope
  (let [spec {:id :p :commands {:p/ed {:doc "Ed." :scope :editor :call [:ed-fn]}}}]
    (is (not (contains? (parts/merge-commands {} [spec] :window call) :p/ed)))
    (is (contains? (parts/merge-commands {} [spec] :editor call) :p/ed))))

(defn- message [f]
  (try (f) nil (catch Exception e (ex-message e))))

(deftest merge-commands-refuses-a-duplicate
  (testing "a spec id already in core"
    (is (= "duplicate command :p/go: :core and :p"
           (message #(parts/merge-commands {:p/go {:doc "x" :scope :window}} [go-spec] :window call)))))
  (testing "two specs"
    (let [other {:id :q :commands {:p/go {:doc "Again." :scope :window :call [:go-fn 2]}}}]
      (is (= "duplicate command :p/go: :p and :q"
             (message #(parts/merge-commands {} [go-spec other] :window call)))))))

(deftest check-clashes-refuses-a-cross-scope-duplicate
  (let [cores {:window {:run/run {:doc "x" :scope :window}}
               :editor {:edit/redo {:doc "y" :scope :editor}}}
        ed-spec (fn [id owner] {:id owner :commands {id {:doc "z" :scope :editor :call [:f]}}})]
    (testing "a spec :editor command reusing a core :window id"
      (is (= "duplicate command :run/run: :core and :p"
             (message #(parts/check-clashes cores [(ed-spec :run/run :p)])))))
    (testing "two specs in different scopes"
      (let [win {:id :q :commands {:x/y {:doc "w" :scope :window :call [:f]}}}
            ed {:id :p :commands {:x/y {:doc "z" :scope :editor :call [:f]}}}]
        (is (= "duplicate command :x/y: :q and :p"
               (message #(parts/check-clashes cores [win ed]))))))
    (testing "no clash passes"
      (is (nil? (parts/check-clashes cores [(ed-spec :p/new :p)]))))))

(deftest registry-tables-hold-the-specs
  (is (= [:messages :disk-poll :layouts :worlds :time-form :processes :trace :live-results :locations :debugger :command-palette :file-tree :plugins :reader :replay] (mapv :id parts/specs)))
  (is (= #{:plugins/review :plugins/list :plugins/reload :tree/toggle :panel/messages :layout/save :layout/forget :world/save :world/restore :world/restore-earlier :run/time-form :session/open-replay :session/forget-last
           :panel/processes :processes/show
           :panel/trace :trace/toggle :trace/show :trace/clear :trace/untrace-all
           :result/inspect :result/copy :result/define :result/jump :result/doc :result/open-location
           :panel/locations :locations/uses :locations/definitions :locations/matches
           :locations/usages-in-project :locations/next :locations/previous
           :compare/disk :compare/head :compare/take :compare/last-run
           :compare-form/disk :compare-form/head :compare-form/last-run
           :panel/frames :debug/show-frames :debug/locals-mode :run/line-counts :run/clear-line-counts
           :debug/break-on-error :debug/continue :restart/retry :restart/edit :restart/define
           :palette/open :help/describe-key :help/key-bindings :help/recent-keys
           :pages/show :pages/bookmark :pages/bookmarks :pages/guide}
         (set (filter (set (mapcat (comp keys :commands) parts/specs)) (keys registry/window-commands)))))
  (is (= (count registry/commands)
         (+ (count registry/window-commands)
            (count registry/editor-commands)
            (count registry/console-commands)))))

(defn- install-fn [id f] (with-meta f {:part/id id}))

(deftest install-merges-in-order
  (let [seen (atom nil)
        a (install-fn :a (fn [_] {:fns {:a-fn inc} :ui {:a-ui 1} :hooks {:on-quit :a-quit}
                                  :exports {:a-ex 10}}))
        b (install-fn :b (fn [wctx] (reset! seen (:a-ex wctx))
                           {:fns {:b-fn dec} :hooks {:on-quit :b-quit :on-start :b-start}}))
        c (parts/install [a b] {:win :w} (fn [& _] (throw (ex-info "no error expected" {}))))]
    (is (= 10 @seen))
    (is (= #{:a-fn :b-fn} (set (keys (:fns c)))))
    (is (= {:a-ui 1} (:ui c)))
    (is (= {:a-ex 10} (:exports c)))
    (is (= {:on-quit [:a-quit :b-quit] :on-start [:b-start]} (:hooks c)))))

(deftest install-merges-panels
  (let [go (fn [] :go)
        a (install-fn :p (fn [_] {:panels {:p {:root :w :focus! go :open! go}}}))
        c (parts/install [a] {} (fn [& _] (throw (ex-info "no error expected" {})))
                         {:specs [{:id :p :panels [{:id :p :label "P"}]}]})]
    (is (= {:root :w :focus! go :open! go} (get-in c [:panels :p])))
    (is (= {:p :p} (get-in c [:owners :panels])))))

(deftest install-refuses-an-undeclared-panel
  (let [errors (atom [])
        a (install-fn :p (fn [_] {:fns {:p-fn inc} :panels {:other {:root :w}}}))
        c (parts/install [a] {} (fn [id ex] (swap! errors conj [id (ex-message ex)]))
                         {:specs [{:id :p :panels [{:id :p :label "P"}]}]})]
    (is (= 1 (count @errors)))
    (is (= :p (ffirst @errors)))
    (is (re-find #":other" (second (first @errors))))
    (is (empty? (:fns c)))
    (is (empty? (:panels c)))))

(deftest install-refuses-a-malformed-panel-entry
  (let [f (fn [] :go)]
    (doseq [entry [{:focus! f :open! f}
                   {:root :w :focus! f :open! :not-a-fn}
                   {:root :w :focus! :not-a-fn :open! f}]]
      (let [errors (atom [])
            a (install-fn :p (fn [_] {:fns {:p-fn inc} :panels {:p entry}}))
            c (parts/install [a] {} (fn [id ex] (swap! errors conj [id (ex-message ex)]))
                             {:specs [{:id :p :panels [{:id :p :label "P"}]}]})]
        (is (= 1 (count @errors)) (pr-str entry))
        (is (= :p (ffirst @errors)))
        (is (empty? (:fns c)))
        (is (empty? (:panels c)))))))

(deftest install-refuses-duplicate-keys
  (let [a (install-fn :a (fn [_] {:ui {:shared 1}}))
        b (install-fn :b (fn [_] {:ui {:shared 2}}))]
    (is (= "duplicate ui key :shared: :a and :b"
           (message #(parts/install [a b] {} (fn [& _] nil)))))))

(deftest install-contains-a-throwing-part
  (let [errors (atom [])
        a (install-fn :a (fn [_] (throw (ex-info "boom" {}))))
        b (install-fn :b (fn [_] {:fns {:b-fn inc}}))
        c (parts/install [a b] {} (fn [id ex] (swap! errors conj [id (ex-message ex)])))]
    (is (= [[:a "boom"]] @errors))
    (is (= #{:b-fn} (set (keys (:fns c)))))))

(deftest install-names-an-unlabelled-part-by-index
  (let [errors (atom [])]
    (parts/install [(fn [_] (throw (ex-info "boom" {})))] {} (fn [id _] (swap! errors conj id)))
    (is (= [0] @errors))))

(deftest merge-with-core-refuses-a-core-key
  (let [c {:fns {:x 1} :owners {:fns {:x :p}}}]
    (is (= {:y 2 :x 1} (parts/merge-with-core :fns {:y 2} c)))
    (is (= "duplicate fns key :x: :core and :p"
           (message #(parts/merge-with-core :fns {:x 0} c))))))

(deftest run-hooks-isolates-a-throwing-hook
  (let [ran (atom []) errors (atom 0)
        errored (atom [])
        contribution {:hooks {:on-quit [(fn [x] (swap! ran conj [1 x]))
                                        (fn [_] (throw (ex-info "bad" {})))
                                        (fn [x] (swap! ran conj [3 x]))]}
                      :owners {:hooks {:on-quit [:a :b :c]}}}]
    (is (nil? (parts/run-hooks! contribution :on-quit [:arg] (fn [id _] (swap! errors inc) (swap! errored conj id)))))
    (is (= [[1 :arg] [3 :arg]] @ran))
    (is (= 1 @errors))
    (is (= [:b] @errored))
    (is (nil? (parts/run-hooks! contribution :on-start [] (fn [_ _] (swap! errors inc)))))))

(deftest missing-calls-names-unreachable-keys
  (let [cmds {:p/a {:doc "a" :scope :window :call [:here 1]}
              :p/b {:doc "b" :scope :window :call [:nope-fn]}
              :p/c {:doc "c" :scope :window}}]
    (is (= #{:nope-fn} (parts/missing-calls cmds #{:here})))
    (is (= #{} (parts/missing-calls cmds #{:here :nope-fn})))))

(deftest specs-and-installs-name-the-same-parts
  (is (= (mapv :id parts/specs)
         (mapv (comp :part/id meta) gtk-parts/installs))))

(deftest check-clashes-refuses-a-bad-scope-or-call
  (let [spec (fn [cmd] {:id :p :commands {:p/go cmd}})]
    (doseq [scope [nil :windw "window"]]
      (is (= "command :p/go of :p: :scope must be :window, :editor or :console"
             (message #(parts/check-clashes {} [(spec {:doc "x" :scope scope :call [:f]})])))))
    (doseq [call [nil [] :f '(:f) "f"]]
      (is (= "command :p/go of :p: :call must be a non-empty vector"
             (message #(parts/check-clashes {} [(spec {:doc "x" :scope :window :call call})])))))
    (is (nil? (parts/check-clashes {} [(spec {:doc "x" :scope :console :call [:f]})])))))

(deftest install-refuses-an-exports-key-already-in-wctx
  (let [a (install-fn :a (fn [_] {:exports {:win 1}}))
        errors (atom [])
        c (parts/install [a] {:win :w} (fn [id ex] (swap! errors conj [id (ex-message ex)])))]
    (is (= [[:a "duplicate exports key :win: :core and :a"]] @errors))
    (is (= {} (:exports c)))))

(deftest install-refuses-unknown-contribution-keys-and-hooks
  (let [errors (atom [])
        a (install-fn :a (fn [_] {:fns {:a-fn inc} :uii {:x 1}}))
        b (install-fn :b (fn [_] {:hooks {:on-quitt (fn [])}}))
        c (install-fn :c (fn [_] {:fns {:c-fn inc}}))
        r (parts/install [a b c] {} (fn [id ex] (swap! errors conj [id (ex-message ex)])))]
    (is (= [[:a "part :a: unknown contribution key :uii"]
            [:b "part :b: unknown hook :on-quitt"]]
           @errors))
    (is (= #{:c-fn} (set (keys (:fns r)))))))

(deftest replay-part-reads-the-tab-map-late
  (let [tab-ctx* (atom nil)
        cv {:recorder* (atom nil) :replay-root* (atom nil) :replay-tab* (atom nil)}
        c (replay-part/part {:cv cv :tab-ctx* tab-ctx*})
        root-fn @(:replay-root* cv)]
    (testing "before activate! sets the tab map"
      (is (nil? (root-fn)))
      (is (nil? (@(:recorder* cv) {} {}))))
    (testing "once it is set, the same fns see it"
      (reset! tab-ctx* {:tree-pane {:state (atom {:root "/proj"})}})
      (is (= "/proj" (root-fn))))
    (is (= #{:on-start :on-reroot :on-quit} (set (keys (:hooks c)))))))

(deftest replay-part-schedules-with-one-callable
  (let [wctx {:cv {} :tab-ctx* (atom {:tree-pane {:state (atom {:root "/proj"})}})}
        state (@#'replay-part/new-state wctx)
        ctx (@#'replay-part/part-ctx wctx state)
        seen (atom [])]
    (with-redefs [g/g-timeout-add (fn [_ms cb _data] (swap! seen conj cb) 1)]
      (dotimes [_ 3]
        (reset! (:replay-save-pending? state) false)
        (@#'replay-part/schedule-replay-save! ctx)))
    (is (= 3 (count @seen)))
    (is (some? (first @seen)))
    (is (= 1 (count (distinct @seen))) "every scheduled save reuses one callable")))

(defn- record-env
  "ctx for record-eval! with window state st, replay tab rt and the
  evaluator's history items; log collects the stubbed effects."
  [st rt items]
  (let [h (atom {:items items})]
    {:cv {:replay-tab* (atom rt)}
     :ev* (atom #_{:clj-kondo/ignore [:missing-protocol-method]}
           (reify ev/Evaluator (history [_] h)))
     :sel* (atom {:profile {:id :jolt :name "Jolt"}})
     :settings-path* (atom "settings.edn")
     :replay* (atom st)}))

(defn- run-record!
  "Runs record-eval! with remember-session? answering remember?, the save
  schedule and append-to-root! logged. Returns [ret log]."
  [ctx remember? record result]
  (let [log (atom [])]
    (with-redefs-fn {#'replay-part/remember-session? (fn [_ _] remember?)
                     #'replay-part/schedule-replay-save! (fn [_] (swap! log conj [:schedule]) nil)
                     #'replay-part/append-to-root! (fn [_ root es opts]
                                                     (swap! log conj [:append root (mapv :text es) opts])
                                                     nil)}
      (fn [] [(@#'replay-part/record-eval! ctx record result) @log]))))

(defn- texts [st k] (mapv :text (get-in st [:record k])))

(def ^:private prev-es
  (replay/entries {:source :repl :code "(+ 1 2) (f)" :at 1 :dialect "Jolt"}))

(deftest record-eval-off-does-nothing
  (let [st {:root "/r/a" :path "p" :record (replay/empty-record) :dirty? false}
        ctx (record-env st nil ["(a)"])
        [ret log] (run-record! ctx false {:source :repl :root "/r/a" :code "(x)"} {:status :ok})]
    (is (nil? ret))
    (is (= [] log))
    (is (= st @(:replay* ctx)))))

(deftest record-eval-asks-about-the-records-root
  (testing "the evaluation's own root decides, not the window's (a window
           re-rooted mid-evaluation still files under the old project's say)"
    (let [seen (atom [])
          ctx (record-env {:root "/r/b" :path "p" :record (replay/empty-record) :dirty? false} nil [])]
      (with-redefs-fn {#'replay-part/remember-session? (fn [path root] (swap! seen conj [path root]) false)}
        (fn [] (@#'replay-part/record-eval! ctx {:source :repl :root "/r/a" :code "(x)"} {:status :ok})))
      (is (= [["settings.edn" "/r/a"]] @seen)))))

(deftest record-eval-files-in-the-window
  (let [ctx (record-env {:root "/r/a" :path "p" :record (replay/empty-record) :dirty? false} nil ["(a)" "(b)"])
        [_ log] (run-record! ctx true {:source :repl :root "/r/a" :code "(x) (y)"} {:status :ok})
        st @(:replay* ctx)]
    (is (= [[:schedule]] log))
    (is (= ["(x)" "(y)"] (texts st :current)))
    (is (= ["(a)" "(b)"] (get-in st [:record :history])))
    (is (true? (:dirty? st)))))

(deftest record-eval-other-root-appends
  (let [st {:root "/r/b" :path "p" :record (replay/empty-record) :dirty? false}
        ctx (record-env st nil [])
        [_ log] (run-record! ctx true {:source :repl :root "/r/a" :code "(x)"} {:status :error})]
    (is (= [[:append "/r/a" [] {:forget? false :run-text "(x)"}]] log))
    (is (= st @(:replay* ctx)) "an error files nothing and leaves the window's record")))

(deftest record-eval-replay-run-forgets-its-own-root
  (let [ctx (record-env {:root "/r/a" :path "p" :dirty? false
                         :record (assoc (replay/empty-record) :previous prev-es)}
                        {:id 1 :root "/r/a"} [])
        [_ log] (run-record! ctx true {:source :run :tab-id 1 :root "/r/a" :code "#_(f) (def x 1)"}
                             {:status :ok})
        st @(:replay* ctx)]
    (is (= [[:schedule]] log))
    (is (= [] (texts st :previous)))
    (is (= ["(f)" "(def x 1)"] (texts st :current)) "the skipped form is carried ahead of the run's own")))

(deftest record-eval-replay-run-forgets-the-tab-root
  (let [ctx (record-env {:root "/r/b" :path "p" :dirty? false
                         :record (assoc (replay/empty-record) :previous prev-es)}
                        {:id 1 :root "/r/b"} [])
        [_ log] (run-record! ctx true {:source :run :tab-id 1 :root "/r/a" :code "#_(f) (def x 1)"}
                             {:status :ok})
        st @(:replay* ctx)]
    (is (= [[:append "/r/a" ["(def x 1)"] {:forget? false :run-text "#_(f) (def x 1)"}] [:schedule]] log))
    (is (= [] (texts st :previous)))
    (is (= ["(f)"] (texts st :current)))
    (is (true? (:dirty? st)))))

(deftest replay-is-the-last-part
  (is (= :replay (:id (last parts/specs)))))

(deftest layouts-part-owns-its-commands
  (let [spec (some #(when (= :layouts (:id %)) %) parts/specs)]
    (is (= [:layout-save-fn] (get-in spec [:commands :layout/save :call])))
    (is (= [:layout-forget-fn] (get-in spec [:commands :layout/forget :call])))
    (is (contains? registry/window-commands :layout/forget))))

;; The worlds part: its commands, and what it does on a dialect whose
;; evaluator has no ev/World (a plain map doesn't satisfy the protocol).
(deftest worlds-part-owns-its-commands
  (let [spec (some #(when (= :worlds (:id %)) %) parts/specs)]
    (is (= [:world-save-fn] (get-in spec [:commands :world/save :call])))
    (is (= [:world-restore-fn] (get-in spec [:commands :world/restore :call])))
    (is (= [:world-restore-earlier-fn] (get-in spec [:commands :world/restore-earlier :call])))
    (is (contains? registry/window-commands :world/restore-earlier))))

(deftest worlds-need-jolt
  (let [statuses (atom [])
        effects (atom [])
        fns (:fns ((deref #'worlds-part/install)
                   {:ev* (atom {:not :a-world}) :settings-path* (atom "/nonexistent/settings.edn") :cv {}
                    :tree-pane {:state (atom {:root "r"})}
                    :active-tab (fn [] (swap! effects conj :active-tab) nil)
                    :set-status! #(swap! statuses conj %)}))]
    ;; the restore-earlier fn lists the (read-only) world folder before it
    ;; checks for Jolt, as it did in window.clj; no folder exists here
    ((:world-save-fn fns))
    ((:world-restore-fn fns))
    ((:world-restore-earlier-fn fns))
    ((:world-restore-from-fn fns) "p")
    (is (= ["; Save World needs Jolt." "; Restore World needs Jolt."
            "; Restore World needs Jolt." "; Restore World needs Jolt."]
           @statuses))
    (is (= [] @effects))))

;; The layouts part's settle timer. The tick body is a plain fn (settle!) so a
;; test can run it: a foreign callable can't be invoked from Clojure.
(defn- layouts-fns
  "The layouts part installed with stub wctx; answers its :fns. Two saved
  layouts, \"L\" and \"M\", differ in their split."
  []
  (let [layouts {"L" {:tree? false :split 100} "M" {:tree? false :split 200}}]
    (with-redefs [settings/load-settings (fn [_] {:layouts layouts})
                  g/label-text (fn [_] "before")
                  g/gtk-toggle-button-set-active (fn [_ _] nil)]
      (:fns ((deref #'layouts-part/install)
             {:settings-path* (atom "x") :status-label :lbl :cv {} :right :right
              :tree-pane {} :tree-paned :tp :paned :p :files-btn :fb
              :panel-focus-fn (fn [_]) :set-status! (fn [_])
              :showing-panel (fn [_] nil)
              :show-right-panel! (fn [& _]) :hide-right-panel! (fn [& _])})))))

(defn- use-layout!
  "Runs :layout-use-fn for n with the settings and GTK stubs in place."
  [fns n]
  (with-redefs [settings/load-settings (fn [_] {:layouts {"L" {:tree? false :split 100}
                                                          "M" {:tree? false :split 200}}})
                g/label-text (fn [_] "before")
                g/gtk-toggle-button-set-active (fn [_ _] nil)]
    ((:layout-use-fn fns) n)))

(deftest layouts-timer-can-be-armed-again-after-arm-throws
  (let [fns (layouts-fns)
        armed (atom 0)]
    (with-redefs [g/g-timeout-add (fn [_ms _cb _d] (swap! armed inc) (throw (ex-info "no loop" {})))]
      (is (thrown? Exception (use-layout! fns "L")))
      (is (thrown? Exception (use-layout! fns "L")))
      (is (= 2 @armed) "the second use arms again"))))

(deftest layouts-two-uses-arm-one-timer
  (let [fns (layouts-fns)
        armed (atom 0)]
    (with-redefs [g/g-timeout-add (fn [_ms _cb _d] (swap! armed inc) 1)]
      (use-layout! fns "L")
      (use-layout! fns "M")
      (is (= 1 @armed) "the second use takes over the running timer"))))

(deftest layouts-timer-can-be-armed-again-after-the-tick-throws
  (let [pending* (atom {:panel :trace :tree? false :split 100 :tries 0})
        running? (atom true)
        deps {:pending* pending* :running? running? :right :r :tree-paned :tp :paned :p
              :showing-panel (fn [_] (throw (ex-info "boom" {})))
              :set-status! (fn [_] (throw (ex-info "the status line is not used" {})))}]
    (is (thrown-with-msg? Exception #"boom" (@#'layouts-part/settle! deps))
        "the throw reaches the callable's :on-throw 0 reporting")
    (is (false? @running?) "so the next use can arm a timer")))

;; The time-form part: its command, and the per-window confirmation.
(deftest time-form-part-owns-its-command
  (let [spec (some #(when (= :time-form (:id %)) %) parts/specs)]
    (is (= [:time-form-fn] (get-in spec [:commands :run/time-form :call])))
    (is (contains? registry/window-commands :run/time-form))))

(deftest time-form-confirmation-is-per-install
  (let [install #(@#'time-form-part/install
                  {:win :w :tabs (atom []) :active-tab (fn [] nil) :cv {} :ev* (atom nil)
                   :sel* (atom nil) :status-label :lbl :set-status! (fn [_]) :alert-pending? (atom false)
                   :show-alert! (fn [& _]) :make-eval-opts (fn [& _]) :refuse-foreign! (fn [_])})
        c1 (install)
        c2 (install)]
    (is (fn? (get-in c1 [:fns :time-form-fn])))
    (reset! (:time-confirmed? (:ui c1)) true)
    (is (false? @(:time-confirmed? (:ui c2))))
    (is (not (identical? (:time-confirmed? (:ui c1)) (:time-confirmed? (:ui c2)))))
    (is (not (identical? (:time-ask* (:ui c1)) (:time-ask* (:ui c2)))))))

(deftest merge-panels-adds-and-refuses
  (let [core {:processes "Processes"}
        spec {:id :p :panels [{:id :p-panel :label "P"}]}]
    (is (= {:processes "Processes" :p-panel "P"} (parts/merge-panels core [spec])))
    (is (= "duplicate panel :processes: :core and :p"
           (message #(parts/merge-panels core [{:id :p :panels [{:id :processes :label "X"}]}]))))
    (is (= "duplicate panel :p-panel: :p and :q"
           (message #(parts/merge-panels core [spec {:id :q :panels [{:id :p-panel :label "Q"}]}]))))))

(deftest merge-panels-reserves-review-and-history
  (doseq [id [:review :history]]
    (is (= (str "duplicate panel " id ": :core and :p")
           (message #(parts/merge-panels {:processes "Processes"}
                                         [{:id :p :panels [{:id id :label "X"}]}]))))))

(deftest bindings-concatenate-in-spec-order
  (let [b (fn [k] {:key k :mods #{:cmd} :command :p/go})
        specs [{:id :a :bindings {:window [(b "1")] :editor [(b "2")]}}
               {:id :b}
               {:id :c :bindings {:window [(b "3") (b "4")]}}]]
    (is (= [(b "1") (b "3") (b "4")] (parts/bindings specs :window)))
    (is (= [(b "2")] (parts/bindings specs :editor)))
    (is (= [] (parts/bindings [{:id :b}] :window)))))

(deftest check-clashes-refuses-a-bad-panel-or-binding
  (let [bad (fn [spec] (message #(parts/check-clashes {} [(merge {:id :p} spec)])))]
    (is (= "part :p: a panel needs a keyword :id and a string :label"
           (bad {:panels [{:id :x}]})))
    (is (= "part :p: a panel needs a keyword :id and a string :label"
           (bad {:panels [{:id :x :label :y}]})))
    (is (= "part :p: :bindings scope must be :window or :editor, got :console"
           (bad {:bindings {:console []}})))
    (is (= "part :p: :bindings :window must be a vector"
           (bad {:bindings {:window '()}})))
    (is (nil? (bad {:panels [{:id :x :label "X"}]
                    :bindings {:window [{:key "9" :command :p/go}] :editor []}})))))

(deftest palette-labels-hold-the-core-panels
  (is (= "Processes" (:processes palette/PANEL-LABELS)))
  (is (= {:inspector "Inspector" :source "Source" :locations "Locations"
          :debugger "Frames" :plugins "Plugins" :messages "Messages"
          :processes "Processes" :trace "Trace" :reader "Reader"}
         palette/PANEL-LABELS)))

(deftest processes-part-owns-its-commands-and-panel
  (let [spec (some #(when (= :processes (:id %)) %) parts/specs)]
    (is (= [:panel-focus-fn :processes] (get-in spec [:commands :panel/processes :call])))
    (is (= [:processes-show-fn] (get-in spec [:commands :processes/show :call])))
    (is (= [{:id :processes :label "Processes"}] (:panels spec)))
    (is (contains? registry/window-commands :processes/show))
    (is (= "Processes" (:processes palette/PANEL-LABELS)))))

;; Review focus 2: a dialect switch swaps the evaluator in ev*, and the
;; next refresh must read the new one.
(deftest processes-refresh-reads-the-evaluator-per-call
  (let [statuses (atom [])
        ops (atom [])
        ev* (atom {:not :fibers})
        right {:recent-panels (atom [])}
        wctx {:ev* ev* :right right :showing-panel (fn [_] nil)
              :show-right-panel! (fn [& _]) :hide-right-panel! (fn [& _])
              :set-status! #(swap! statuses conj %) :status-label :lbl
              :active-tab (fn [] nil)}]
    (with-redefs [g/g-timeout-add (fn [& _] 1)
                  processes-pane/make-pane (fn [] {:root :root :list :list})
                  processes-pane/set-handlers! (fn [& _])]
      (let [show (get-in ((deref #'processes-part/install) wctx) [:fns :processes-show-fn])]
        (show)
        (is (= ["; Processes need Jolt."] @statuses))
        (reset! ev* (reify ev/Fibers
                      (fibers-op! [_ op _ _] (swap! ops conj op))))
        (show)
        (is (= [:watch] @ops))
        (is (= 1 (count @statuses)))))))

;; Messages: the log panel, its unread count's click and the status log.
(deftest messages-part-owns-its-command-and-panel
  (let [spec (some #(when (= :messages (:id %)) %) parts/specs)]
    (is (= [:panel-focus-fn :messages] (get-in spec [:commands :panel/messages :call])))
    (is (= "Focus the Messages panel, or open it." (get-in spec [:commands :panel/messages :doc])))
    (is (= [{:id :messages :label "Messages"}] (:panels spec)))
    (is (contains? registry/window-commands :panel/messages))
    (is (= "Messages" (:messages palette/PANEL-LABELS)))))

(deftest messages-part-registers-its-log
  (let [registered (atom [])
        wctx {:right {:recent-panels (atom [])} :tabs (atom []) :active-tab (fn [] nil)
              :showing-panel (fn [_] nil) :show-right-panel! (fn [& _]) :hide-right-panel! (fn [& _])
              :set-status! (fn [_]) :status-label :lbl :unread-label :unread
              :register-status-log! #(swap! registered conj %)
              :plugin-step! (fn [& _]) :settings-path* (atom nil) :select-tab! (fn [& _])
              :tab-ctx* (atom nil) :panel-focus-fn (fn [& _])}]
    (with-redefs [messages-pane/make-pane (fn [] {:root :root :list :list})
                  messages-pane/set-handlers! (fn [& _])
                  g/gtk-gesture-click-new (fn [] :click)
                  g/g-signal-connect-data (fn [& _])
                  g/gtk-widget-add-controller (fn [& _])]
      (let [c ((deref #'messages-part/install) wctx)]
        (is (= 1 (count @registered)))
        (is (fn? (get-in c [:exports :log-entry!])))
        (is (identical? (first @registered) (get-in c [:exports :log-entry!])))
        (is (identical? (first @registered) (get-in c [:ui :log!*])))
        (is (fn? (get-in c [:ui :unread-click!*])))
        (is (= :root (get-in c [:panels :messages :root])))))))

(deftest messages-installs-first
  (is (= :messages (:part/id (meta (first gtk-parts/installs)))))
  (is (= :replay (:part/id (meta (last gtk-parts/installs))))))

;; Disk polling: open tabs follow their files; logs through Messages' export.
(deftest disk-poll-needs-the-messages-log
  (let [timers (atom [])
        errors (atom [])]
    (with-redefs [g/g-timeout-add (fn [& args] (swap! timers conj args) 1)]
      (let [c (parts/install [disk-poll-part/part]
                             {:tabs (atom []) :settings-path* (atom nil) :refresh-labels! (fn [])}
                             (fn [id ex] (swap! errors conj [id (ex-message ex)])))]
        (is (= [[:disk-poll "disk-poll needs :log-entry! from the messages part"]] @errors))
        (is (= {} (:ui c)))
        (is (empty? @timers))))))

(deftest disk-poll-arms-one-timer
  (let [timers (atom [])]
    (with-redefs [g/g-timeout-add (fn [& args] (swap! timers conj args) 1)]
      (let [c ((deref #'disk-poll-part/install)
               {:tabs (atom []) :settings-path* (atom nil) :refresh-labels! (fn [])
                :log-entry! (fn [_])})]
        (is (= 1 (count @timers)))
        (is (= 2000 (ffirst @timers)))
        (is (fn? (get-in c [:ui :disk-tick!*])))))))

(deftest disk-poll-spec-has-no-commands
  (is (= {:id :disk-poll :commands {}} (some #(when (= :disk-poll (:id %)) %) parts/specs))))

;; Trace: the panel, its four commands and the after-eval refresh.
(deftest trace-part-owns-its-commands-and-panel
  (let [spec (some #(when (= :trace (:id %)) %) parts/specs)]
    (is (= [:panel-focus-fn :trace] (get-in spec [:commands :panel/trace :call])))
    (is (= [:trace-toggle-fn] (get-in spec [:commands :trace/toggle :call])))
    (is (= [:trace-show-fn] (get-in spec [:commands :trace/show :call])))
    (is (= [:trace-clear-fn] (get-in spec [:commands :trace/clear :call])))
    (is (= [:trace-untrace-all-fn] (get-in spec [:commands :trace/untrace-all :call])))
    (is (= [{:id :trace :label "Trace"}] (:panels spec)))
    (is (= "Trace" (:trace palette/PANEL-LABELS)))))

(defn- trace-wctx [ev* statuses]
  {:ev* ev* :cv {:after-eval* (atom nil)} :right {:slot :slot :recent-panels (atom [])}
   :set-status! #(swap! statuses conj %) :status-label :lbl
   :active-tab (fn [] nil) :show-right-panel! (fn [& _]) :hide-right-panel! (fn [& _])
   :inspect! (fn [_])})

;; Review focus 3: a dialect switch swaps the evaluator in ev*, and the
;; next call must read the new one.
(deftest trace-reads-the-evaluator-per-call
  (let [statuses (atom [])
        ops (atom [])
        ev* (atom {:not :trace})
        wctx (trace-wctx ev* statuses)]
    (with-redefs [trace-pane/make-pane (fn [] {:root :root :list :list})
                  trace-pane/set-handlers! (fn [& _])]
      (let [show (get-in ((deref #'trace-part/install) wctx) [:fns :trace-show-fn])]
        (show)
        (is (= ["; Trace needs Jolt."] @statuses))
        (reset! ev* (reify ev/Trace
                      (trace-op! [_ op _ _] (swap! ops conj op))))
        (show)
        (is (= [:log] @ops))
        (is (= 1 (count @statuses)))))))

(deftest trace-part-sets-after-eval
  (let [wctx (trace-wctx (atom nil) (atom []))]
    (with-redefs [trace-pane/make-pane (fn [] {:root :root :list :list})
                  trace-pane/set-handlers! (fn [& _])]
      (let [c ((deref #'trace-part/install) wctx)]
        (is (fn? @(:after-eval* (:cv wctx))))
        (is (= :root (get-in c [:panels :trace :root])))))))

(deftest live-results-part-owns-its-commands
  (let [spec (some #(when (= :live-results (:id %)) %) parts/specs)]
    (is (= [:result-inspect-fn] (get-in spec [:commands :result/inspect :call])))
    (is (= [:result-copy-fn] (get-in spec [:commands :result/copy :call])))
    (is (= [:result-define-fn] (get-in spec [:commands :result/define :call])))
    (is (= [:result-jump-fn] (get-in spec [:commands :result/jump :call])))
    (is (= [:result-doc-fn] (get-in spec [:commands :result/doc :call])))
    (is (= [:result-open-location-fn] (get-in spec [:commands :result/open-location :call])))
    (is (= 6 (count (:commands spec))))))

(deftest live-results-open-file-line
  (let [statuses (atom [])
        opened (atom [])
        line (atom "    at demo.core$f.invoke (core.clj:12)")
        wctx {:cv {:view :view :buf :buf :results (atom [])}
              :set-status! #(swap! statuses conj %) :status-label :lbl
              :ev* (atom nil) :active-tab (fn [] {:path (atom nil)})
              :tree-pane {:state (atom {:root "/p"})}
              :inspect! (fn [_]) :ask-lookup! (fn [& _]) :jump-to! (fn [& _])
              :source-ctx (fn [_] {:ctx true})
              :open-file-line! (fn [ctx label path n] (swap! opened conj [ctx label path n]))}]
    (with-redefs [cview/line-at (fn [_ _] {:text @line :start 100})
                  usages/project-files (fn [_] ["/p/src/demo/core.clj" "/p/src/other/core.clj"])
                  g/cursor-offset (fn [_] 130)
                  g/gtk-gesture-click-new (fn [] :gesture)
                  g/gtk-gesture-single-set-button (fn [& _])
                  g/gtk-event-controller-set-propagation-phase (fn [& _])
                  g/g-signal-connect-data (fn [& _] 1)
                  g/gtk-widget-add-controller (fn [& _])]
      (let [open! (get-in ((deref #'live-results-part/install) wctx) [:fns :result-open-location-fn])]
        (testing "a stack frame's file opens at its line, the frame's namespace picking among same-named files"
          (open!)
          (is (= [[{:ctx true} "core.clj:12" "/p/src/demo/core.clj" 12]] @opened)))
        (testing "several candidates and no namespace to pick by: says so, opens none"
          (reset! opened []) (reset! line "Location (core.clj:12)")
          (with-redefs [g/cursor-offset (fn [_] 112)] (open!))
          (is (= [] @opened))
          (is (= ["; 2 project files could be core.clj, so none was opened."] @statuses)))
        (testing "no file:line at the caret"
          (reset! statuses []) (reset! line "plain output")
          (open!)
          (is (= ["; No file:line here."] @statuses)))))))

(deftest live-results-say-no-result
  (let [statuses (atom [])
        wctx {:cv {:view :view :buf :buf :results (atom [])}
              :set-status! #(swap! statuses conj %) :status-label :lbl
              :ev* (atom nil) :active-tab (fn [] nil)
              :inspect! (fn [_]) :ask-lookup! (fn [& _]) :jump-to! (fn [& _])
              :source-ctx (fn [_] {})}]
    (with-redefs [cview/result-at (fn [_ _] nil)
                  cview/result-text-at (fn [_ _] nil)
                  g/cursor-offset (fn [_] 0)
                  g/gtk-gesture-click-new (fn [] :gesture)
                  g/gtk-gesture-single-set-button (fn [& _])
                  g/gtk-event-controller-set-propagation-phase (fn [& _])
                  g/g-signal-connect-data (fn [& _] 1)
                  g/gtk-widget-add-controller (fn [& _])]
      (let [c ((deref #'live-results-part/install) wctx)]
        (doseq [k [:result-inspect-fn :result-copy-fn :result-define-fn :result-jump-fn :result-doc-fn]]
          (reset! statuses [])
          ((get-in c [:fns k]))
          (is (= ["; No result here."] @statuses) (str k)))
        (is (= #{:result-click!* :result-release!* :console-right-press!*} (set (keys (:ui c)))))))))

(deftest live-results-doc-prints-the-var-doc
  (testing "the quick-doc verb looks the var up and prints its doc on the
           console as comment lines; nothing found goes to the status bar"
    (let [statuses (atom []) printed (atom []) asked (atom [])
          answer (atom {:doc "Returns one more."})
          wctx {:cv {:view :view :buf :buf :results (atom [])}
                :set-status! #(swap! statuses conj %) :status-label :lbl
                :ev* (atom nil) :active-tab (fn [] nil)
                :inspect! (fn [_]) :jump-to! (fn [& _]) :source-ctx (fn [_] {})
                :ask-lookup! (fn [_ {:keys [sym label on-doc]}] (swap! asked conj [sym label]) (on-doc @answer))}]
      (with-redefs [cview/result-at (fn [_ _] {:id 1 :epoch "e" :kind :var})
                    cview/result-text-at (fn [_ _] "=> #'clojure.core/inc\n")
                    cview/append! (fn [_ style s] (swap! printed conj [style s]))
                    help/doc-text (fn [sym doc] (str sym "\n\n" (:doc doc)))
                    g/cursor-offset (fn [_] 0)
                    g/gtk-gesture-click-new (fn [] :gesture)
                    g/gtk-gesture-single-set-button (fn [& _])
                    g/gtk-event-controller-set-propagation-phase (fn [& _])
                    g/g-signal-connect-data (fn [& _] 1)
                    g/gtk-widget-add-controller (fn [& _])]
        (let [doc! (get-in ((deref #'live-results-part/install) wctx) [:fns :result-doc-fn])]
          (doc!)
          (is (= [["clojure.core/inc" "quick doc"]] @asked))
          (is (= [[:system "; clojure.core/inc\n;\n; Returns one more.\n"]] @printed))
          (reset! answer nil)
          (doc!)
          (is (= ["No documentation found for clojure.core/inc."] @statuses))
          (is (= 1 (count @printed)) "nothing printed when nothing was found"))))))

(deftest run-tab-hooks-calls-each-part
  (let [seen (atom [])
        ctx {:part-contribution {:hooks {:on-tab-removed [(fn [c t] (swap! seen conj [:a c t]))
                                                          (fn [c t] (swap! seen conj [:b c t]))]}
                                 :owners {:hooks {:on-tab-removed [:a :b]}}}
             :part-error! (fn [& _] (throw (ex-info "no error expected" {})))}]
    (is (nil? (parts/run-tab-hooks! ctx :on-tab-removed {:id 7})))
    (is (= [[:a ctx {:id 7}] [:b ctx {:id 7}]] @seen))))

(deftest run-tab-hooks-reports-a-throw
  (let [seen (atom []) errors (atom [])
        ctx {:part-contribution {:hooks {:on-tab-removed [(fn [_ _] (throw (ex-info "boom" {})))
                                                          (fn [_ t] (swap! seen conj (:id t)))]}
                                 :owners {:hooks {:on-tab-removed [:a :b]}}}
             :part-error! (fn [id ex] (swap! errors conj [id (ex-message ex)]))}]
    (is (nil? (parts/run-tab-hooks! ctx :on-tab-removed {:id 3})))
    (is (= [[:a "boom"]] @errors))
    (is (= [3] @seen))))

(deftest run-tab-hooks-without-a-contribution
  (is (nil? (parts/run-tab-hooks! {} :on-tab-removed {:id 1})))
  (is (nil? (parts/run-tab-hooks! {:part-error! (fn [& _])} :on-tab-removed {:id 1}))))

(deftest tab-hook-kinds-are-accepted
  (let [errors (atom [])
        removed (install-fn :removed (fn [_] {:hooks {:on-tab-removed (fn [_ _])}}))
        wired (install-fn :wired (fn [_] {:hooks {:on-tab-wired (fn [_ _])}}))
        other (install-fn :other (fn [_] {:hooks {:on-tab-sideways (fn [_ _])}}))
        c (parts/install [removed wired other] {} (fn [id ex] (swap! errors conj [id (ex-message ex)])))]
    (is (= #{:on-tab-removed :on-tab-wired} (set (keys (:hooks c)))))
    (is (= [[:other "part :other: unknown hook :on-tab-sideways"]] @errors))))

(deftest locations-part-owns-its-commands-and-panel
  (let [spec (some #(when (= :locations (:id %)) %) parts/specs)]
    (is (= {:panel/locations [:panel-focus-fn :locations]
            :locations/uses [:locations-uses-fn] :locations/definitions [:locations-definitions-fn]
            :locations/matches [:locations-matches-fn]
            :locations/usages-in-project [:locations-project-usages-fn]
            :locations/next [:locations-step-fn 1] :locations/previous [:locations-step-fn -1]
            :compare/disk [:compare-disk-fn] :compare/head [:compare-head-fn]
            :compare/take [:compare-take-fn] :compare/last-run [:compare-last-run-fn]
            :compare-form/disk [:compare-form-disk-fn] :compare-form/head [:compare-form-head-fn]
            :compare-form/last-run [:compare-form-last-run-fn]}
           (update-vals (:commands spec) :call)))
    (is (= [{:id :locations :label "Locations"}] (:panels spec)))
    (is (= "Locations" (:locations palette/PANEL-LABELS)))))

(deftest debugger-part-owns-its-commands-and-panel
  (let [spec (some #(when (= :debugger (:id %)) %) parts/specs)]
    (is (= {:panel/frames [:panel-focus-fn :debugger]
            :debug/show-frames [:show-frames-fn] :debug/locals-mode [:locals-mode-fn]
            :run/line-counts [:line-counts-fn] :run/clear-line-counts [:clear-line-counts-fn]
            :debug/break-on-error [:break-fn] :debug/continue [:continue-fn]
            :restart/retry [:retry-fn] :restart/edit [:edit-retry-fn]
            :restart/define [:define-retry-fn]}
           (update-vals (:commands spec) :call)))
    (is (= [{:id :debugger :label "Frames"}] (:panels spec)))
    (is (= "Frames" (:debugger palette/PANEL-LABELS)))))

(defn- debugger-wctx [ev* failure* statuses rerun]
  {:ev* ev* :cv {:failure* failure*} :sel* (atom nil)
   :set-status! #(swap! statuses conj %) :status-label :lbl
   :tabs (atom [{:id 4}]) :tab-by-id (fn [tabs id] (some #(when (= id (:id %)) %) @tabs))
   :active-tab (fn [] {:id 4}) :right {:slot :slot :recent-panels (atom [])}
   :show-right-panel! (fn [& _]) :hide-right-panel! (fn [& _])
   :rerun-failure! rerun :inspect! (fn [_]) :open-in-tab! (fn [& _]) :show-source! (fn [& _])
   :source-ctx (fn [_] {})})

(def ^:private stub-break
  (fn [handlers*]
    (reify ev/Break
      (set-break-handlers! [_ h] (swap! handlers* conj h))
      (toggle-break! [_ _] nil)
      (break-eval! [_ _ _ _] nil)
      (continue-break! [_] false))))

;; Review focus 4: a dialect switch swaps the evaluator in ev*, and Break on
;; error must register its handlers on the new one.
(deftest debugger-registers-break-on-a-new-evaluator
  (let [seen (atom [])
        ev* (atom {:not :break})
        wctx (debugger-wctx ev* (atom nil) (atom []) (fn [& _]))]
    (with-redefs [debugger-pane/make-pane (fn [] {:root :root})
                  debugger-pane/set-handlers! (fn [& _])
                  debugger-pane/set-restart-handler! (fn [& _])]
      ((deref #'debugger-part/install) wctx)
      (is (= [] @seen))
      (reset! ev* (stub-break seen))
      (is (= 1 (count @seen)))
      (is (= #{:on-pause :on-resume :on-eval-start :on-eval-end} (set (keys (first @seen))))))))

(deftest retry-says-when-nothing-failed
  (let [statuses (atom []) reruns (atom [])
        wctx (debugger-wctx (atom nil) (atom nil) statuses (fn [f t] (swap! reruns conj [f t])))]
    (with-redefs [debugger-pane/make-pane (fn [] {:root :root})
                  debugger-pane/set-handlers! (fn [& _])
                  debugger-pane/set-restart-handler! (fn [& _])]
      (let [c ((deref #'debugger-part/install) wctx)]
        ((get-in c [:fns :retry-fn]))
        (is (= ["; Nothing has failed since the last success."] @statuses))
        (is (= [] @reruns))))))

(deftest retry-hands-the-failure-to-the-core
  (let [statuses (atom []) reruns (atom [])
        f {:kind :run :tab-id 4 :code "x"}
        wctx (debugger-wctx (atom nil) (atom f) statuses (fn [f t] (swap! reruns conj [f t])))]
    (with-redefs [debugger-pane/make-pane (fn [] {:root :root})
                  debugger-pane/set-handlers! (fn [& _])
                  debugger-pane/set-restart-handler! (fn [& _])]
      (let [c ((deref #'debugger-part/install) wctx)]
        ((get-in c [:fns :retry-fn]))
        (is (= [[f {:id 4}]] @reruns))
        (is (= [] @statuses))))))

;; Review focus 3: the hook runs for every tab, the first and later ones.
(deftest tab-wired-hook-sets-counting
  (let [counting? (atom nil) counts-refresh (atom nil)
        connects (atom [])
        tab {:ed {:buf :buf :destroyed? (atom false)
                  :changed {:counting? counting? :counts-refresh counts-refresh :gutter {:counts (atom {})}}}}
        wctx (debugger-wctx (atom nil) (atom nil) (atom []) (fn [& _]))]
    (with-redefs [debugger-pane/make-pane (fn [] {:root :root})
                  debugger-pane/set-handlers! (fn [& _])
                  debugger-pane/set-restart-handler! (fn [& _])
                  g/g-signal-connect-data (fn [& args] (swap! connects conj args) 1)]
      (let [c ((deref #'debugger-part/install) wctx)]
        ((get-in c [:hooks :on-tab-wired]) {} tab)
        (is (fn? @counting?))
        (is (false? (@counting?)))
        (is (fn? @counts-refresh))
        (is (= [[:buf "changed"]] (mapv (fn [a] [(first a) (second a)]) @connects)))))))

(deftest command-palette-part-owns-its-commands
  (let [spec (some #(when (= :command-palette (:id %)) %) parts/specs)]
    (is (= {:palette/open [:open-palette-fn]
            :help/describe-key [:describe-key-fn]
            :help/key-bindings [:key-bindings-fn]
            :help/recent-keys [:recent-keys-fn]}
           (update-vals (:commands spec) :call)))
    (is (false? (:palette? (:palette/open (:commands spec)))))
    (is (every? #(nil? (get @#'registry/core-window-commands %))
                [:palette/open :help/describe-key :help/key-bindings :help/recent-keys])
        "the core table keeps none of them")
    (is (every? #(some? (get registry/window-commands %))
                [:palette/open :help/describe-key :help/key-bindings :help/recent-keys]))
    (is (some? (:help/key-hints registry/window-commands)))))

(defn- palette-wctx [bang*]
  {:bang* bang* :cv :cv :ev* (atom nil) :active-tab (fn [] {:id 1})
   :tabs (atom []) :tab-by-id (fn [& _]) :recent-tabs (atom [])
   :right {:recent-panels (atom [])} :showing-panel (fn [_]) :settings-path* (atom "x")})

(deftest command-palette-describe-key-arms-the-next-key
  (let [lines (atom [])
        c ((deref #'command-palette-part/install) (palette-wctx (atom (fn [_]))))
        fns (:fns c)]
    (with-redefs [cview/append! (fn [cv kind text] (swap! lines conj [cv kind text]))]
      ((:describe-key-fn fns))
      (is (true? @(:describe-pending fns)))
      (is (= [[:cv :system "; Press the key to describe.\n"]] @lines)))))

(deftest command-palette-installs-the-bang-line
  (let [before (fn [_text])
        bang* (atom before)
        c ((deref #'command-palette-part/install) (palette-wctx bang*))]
    (is (not= before @bang*))
    (is (fn? @bang*))
    (is (fn? (get-in c [:ui :open-palette!*])))
    (is (= (get-in c [:ui :open-palette!*]) (get-in c [:fns :open-palette-fn])))))

(deftest on-switch-is-a-tab-hook-kind
  (let [seen (atom [])
        errors (atom [])
        sw (install-fn :sw (fn [_] {:hooks {:on-switch (fn [ctx t] (swap! seen conj [ctx t]))}}))
        c (parts/install [sw] {} (fn [id ex] (swap! errors conj [id (ex-message ex)])))]
    (is (= [] @errors))
    (is (= [:on-switch] (keys (:hooks c))))
    (is (nil? (parts/run-tab-hooks! {:part-contribution c :part-error! (fn [& _]) :k 1} :on-switch {:id 7})))
    (is (= [[1 {:id 7}]] (mapv (fn [[ctx t]] [(:k ctx) t]) @seen))
        "called with ctx and the tab")))

(deftest file-tree-part-owns-its-command
  (let [spec (some #(when (= :file-tree (:id %)) %) parts/specs)]
    (is (= {:tree/toggle [:toggle-tree-fn]} (update-vals (:commands spec) :call)))
    (is (nil? (get @#'registry/core-window-commands :tree/toggle)))
    (is (some? (get @#'registry/core-window-commands :tree/close-menu)))
    (is (some? (get @#'registry/core-window-commands :tree/leave)))
    (is (some? (get registry/window-commands :tree/toggle)))))

(defn- file-tree-hooks [tree-pane]
  (:hooks ((deref #'file-tree-part/install)
           {:win :win :cv :cv :status-label :status :active-tab (fn [])
            :settings-path* (atom "/s.edn") :tree-pane tree-pane :tree-paned :paned
            :files-btn :btn :contain-click (fn [_ f] f)})))

(deftest file-tree-quit-saves-width-only-while-showing
  (let [saved (atom [])
        hook (:on-quit (file-tree-hooks {:root :root}))]
    (with-redefs [settings/save-settings! (fn [& args] (swap! saved conj args) nil)
                  g/gtk-paned-get-position (fn [_] 312)]
      (with-redefs [g/gtk-widget-get-visible (fn [_] 0)]
        (hook)
        (is (= [] @saved)))
      (with-redefs [g/gtk-widget-get-visible (fn [_] 1)]
        (hook)
        (is (= [["/s.edn" {:file-tree {:visible? true :width 312}}]] @saved))))))

(deftest file-tree-quit-reports-a-failed-save
  (let [hook (:on-quit (file-tree-hooks {:root :root}))
        said (fn [save]
               (let [w (java.io.StringWriter.)]
                 (with-redefs [settings/save-settings! save
                               g/gtk-paned-get-position (fn [_] 300)
                               g/gtk-widget-get-visible (fn [_] 1)]
                   (binding [*err* w] (hook)))
                 (str w)))]
    (is (re-find #"jonquil: saving the file tree's width failed: disk full"
                 (said (fn [& _] "disk full"))))
    (is (re-find #"jonquil: saving the file tree's width failed: boom"
                 (said (fn [& _] (throw (ex-info "boom" {}))))))))

(deftest file-tree-switch-selects-the-tab-file
  (let [recorded (atom [])
        tp {:root :root}
        hook (:on-switch (file-tree-hooks tp))]
    (with-redefs [file-tree-pane/select-path! (fn [p path] (swap! recorded conj [p path]))]
      (hook {} {:path (atom "/p/a.clj")})
      (is (= [[tp "/p/a.clj"]] @recorded)))))

;; Plugins: the review, list and history panels' behaviour.

(defn- plugins-wctx
  "A stub wctx for the plugins part; steps and step-nows record into calls."
  [calls]
  {:review-pane {:root :r :handlers (atom {})}
   :plugin-list-pane {:root :l :handlers (atom {})}
   :plugin-history-pane {:root :h :handlers (atom {})}
   :plugin-step! (fn [o] (swap! calls conj o) nil)
   :settings-path* (atom "/s/settings.edn")
   :plugin-history-shown (atom "p1")
   :plugins-on* (atom true)
   :active-tab (fn [] {:id 1})
   :plugin-console (atom nil)
   :plugin-reviews-changed (atom nil)
   :on-saved (atom nil)})

(deftest plugins-part-owns-its-commands
  (let [spec (some #(when (= :plugins (:id %)) %) parts/specs)]
    (is (= {:plugins/review [:review-proposals-fn] :plugins/list [:plugins-list-fn]
            :plugins/reload [:reload-plugins-fn]}
           (update-vals (:commands spec) :call)))
    (is (nil? (get @#'registry/core-window-commands :plugins/review)))
    (is (nil? (get @#'registry/core-window-commands :plugins/list)))
    (is (some? (get registry/window-commands :plugins/review)))
    (is (some? (get registry/window-commands :plugins/list)))
    (is (nil? (get @#'registry/core-window-commands :plugins/reload)))
    (is (some? (get registry/window-commands :plugins/reload)))))

(deftest plugins-part-answers-its-ui-keys
  (let [c ((deref #'plugins-part/install) (plugins-wctx (atom [])))]
    (is (= #{:review-proposals!* :review-accept!* :review-reject!* :review-next!*
             :plugins-list!* :plugin-enable!* :plugin-disable!* :plugin-open!*
             :plugin-history!* :plugin-revert!* :plugin-history-back!*
             :plugin-history-close!* :plugin-history-shown* :plugin-list-shown*
             :review-shown*
             :load-plugins!* :reload-plugins!* :plugins-on!* :plugins-dir*
             :plugin-state* :plugin-tools* :watch-plugin-tools* :plugin-propose!*}
           (set (keys (:ui c)))))
    (is (= 23 (count (:ui c))))
    (is (fn? (get-in c [:fns :reload-plugins-fn])))
    (is (fn? (get-in c [:fns :plugins-start!])))))

(deftest plugins-review-and-list-ask-for-a-step
  (let [calls (atom [])
        c ((deref #'plugins-part/install) (plugins-wctx calls))]
    ((get-in c [:fns :review-proposals-fn]))
    ((get-in c [:fns :plugins-list-fn]))
    (is (= 2 (count @calls)))
    (let [[a b] @calls]
      (is (= "/s/settings.edn" (:settings-path a)))
      (is (true? (:open? a)))
      (is (= [] ((:step a))))
      (is (= "/s/settings.edn" (:settings-path b)))
      (is (true? (:open-list? b)))
      (is (= [] ((:step b)))))))

(deftest plugins-install-sets-the-pane-handlers
  (let [w (plugins-wctx (atom []))]
    ((deref #'plugins-part/install) w)
    (is (= #{:on-accept :on-reject :on-next :on-close} (set (keys @(:handlers (:review-pane w))))))
    (is (= #{:on-enable :on-disable :on-open :on-history :on-review :on-close}
           (set (keys @(:handlers (:plugin-list-pane w))))))
    (is (= #{:on-revert :on-back :on-close} (set (keys @(:handlers (:plugin-history-pane w))))))))

(deftest plugins-toggle-without-a-row-says-so
  (let [calls (atom [])
        reports (atom [])
        w (plugins-wctx calls)]
    ((deref #'plugins-part/install) w)
    (with-redefs [plugin-list-pane/selected-row (fn [_] nil)
                  plugin-list-pane/report! (fn [p text] (swap! reports conj [p text]))]
      ((:on-enable @(:handlers (:plugin-list-pane w)))))
    (is (= [[(:plugin-list-pane w) "No plugin is selected."]] @reports))
    (is (empty? @calls))))

(deftest plugins-history-back-clears-the-shown-id
  (let [calls (atom [])
        w (plugins-wctx calls)]
    ((deref #'plugins-part/install) w)
    ((:on-back @(:handlers (:plugin-history-pane w))))
    (is (nil? @(:plugin-history-shown w)))
    (is (= 1 (count @calls)))
    (is (true? (:open-list? (first @calls))))))

(defn- call-restoring-effects [f]
  (let [saved @plugin-registry/effects]
    (try (f) (finally (reset! plugin-registry/effects saved)))))

(defmacro ^:private with-effects-restored [& body]
  `(call-restoring-effects (fn [] ~@body)))

(deftest plugins-start-sets-the-globals-at-start
  (with-effects-restored
    (let [w (plugins-wctx (atom []))
          console @(:plugin-console w)
          changed @(:plugin-reviews-changed w)
          saved @(:on-saved w)
          effects @plugin-registry/effects
          c ((deref #'plugins-part/install) w)]
      (is (identical? console @(:plugin-console w)))
      (is (identical? changed @(:plugin-reviews-changed w)))
      (is (identical? saved @(:on-saved w)))
      (is (identical? effects @plugin-registry/effects))
      (reset! (:plugins-on* w) false)
      ((get-in c [:fns :plugins-start!]))
      (is (fn? @(:plugin-console w)))
      (is (fn? @(:plugin-reviews-changed w)))
      (is (fn? @(:on-saved w)))
      (is (not (identical? effects @plugin-registry/effects)))
      (is (= 1 ((:active-tab @plugin-registry/effects)))))))

(deftest plugins-later-window-wins
  (with-effects-restored
    (let [shared (select-keys (plugins-wctx (atom [])) [:plugin-console :plugin-reviews-changed :on-saved])
          w1 (merge (plugins-wctx (atom [])) shared {:plugins-on* (atom false) :active-tab (fn [] {:id 1})})
          w2 (merge (plugins-wctx (atom [])) shared {:plugins-on* (atom false) :active-tab (fn [] {:id 2})})
          s1 (get-in ((deref #'plugins-part/install) w1) [:fns :plugins-start!])
          s2 (get-in ((deref #'plugins-part/install) w2) [:fns :plugins-start!])]
      (s1)
      (let [console1 @(:plugin-console w1)]
        (is (= 1 ((:active-tab @plugin-registry/effects))))
        (s2)
        (is (= 2 ((:active-tab @plugin-registry/effects))))
        (is (not (identical? console1 @(:plugin-console w1))))
        (s1)
        (is (= 1 ((:active-tab @plugin-registry/effects))))))))

(deftest plugins-start-loads-only-when-on
  (with-effects-restored
    (let [calls (atom [])
          w (assoc (plugins-wctx calls) :plugin-step! (fn [o] (swap! calls conj o) nil))
          start (get-in ((deref #'plugins-part/install) w) [:fns :plugins-start!])]
      (reset! (:plugins-on* w) false)
      (start)
      (is (empty? @calls))
      (reset! (:plugins-on* w) true)
      (start)
      (is (= 1 (count @calls)))
      (is (= "/s/settings.edn" (:settings-path (first @calls))))
      (is (fn? (:step (first @calls)))))))
