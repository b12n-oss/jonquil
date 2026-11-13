(ns jonquil.parts-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.parts :as gtk-parts]
            [jonquil.gtk.parts.layouts :as layouts-part]
            [jonquil.gtk.parts.replay :as replay-part]
            [jonquil.gtk.parts.time-form :as time-form-part]
            [jonquil.gtk.parts.worlds :as worlds-part]
            [jonquil.palette :as palette]
            [jonquil.replay :as replay]
            [jonquil.settings :as settings]
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
  (is (= [:messages :disk-poll :layouts :worlds :time-form :processes :trace :live-results :locations :debugger :breakpoints :command-palette :file-tree :plugins :change-sets :reader :replay] (mapv :id parts/specs)))
  (is (= #{:plugins/review :plugins/list :plugins/reload :tree/toggle :panel/messages :layout/save :layout/forget :world/save :world/restore :world/restore-earlier :run/time-form :session/open-replay :session/forget-last
           :panel/processes :processes/show
           :change-set/make :change-set/browse
           :panel/trace :trace/toggle :trace/show :trace/clear :trace/untrace-all
           :result/inspect :result/copy :result/define :result/jump :result/doc :result/open-location
           :panel/locations :locations/uses :locations/definitions :locations/matches
           :locations/usages-in-project :locations/next :locations/previous
           :compare/disk :compare/head :compare/take :compare/last-run
           :compare-form/disk :compare-form/head :compare-form/last-run
           :panel/frames :debug/show-frames :debug/locals-mode :run/line-counts :run/clear-line-counts
           :debug/break-on-error :debug/continue :debug/step :debug/step-over :debug/step-out :restart/retry :restart/edit :restart/define
           :palette/open :help/describe-key :help/key-bindings :help/recent-keys :help/version
           :help/install-cli :help/remove-cli
           :debug/toggle-breakpoint :debug/clear-breakpoints :debug/instrument-tab :debug/uninstrument-tab
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
          :processes "Processes" :trace "Trace" :change-sets "Change Sets" :reader "Reader"}
         palette/PANEL-LABELS)))
