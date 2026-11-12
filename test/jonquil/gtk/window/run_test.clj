(ns jonquil.gtk.window.run-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.changed :as changed]
            [jonquil.commands :as commands]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.plugin-hooks :as plugin-hooks]
            [jonquil.tabs :as tabs]))

(defn- evaluator
  "An Evaluator stub: evaluating? answers busy?, prompt-ns ns, history the
  atom history; send-input!, send-eof! and eval! log [:stdin text], [:eof]
  and [:eval code opts] into log (eval! answers nil)."
  [{:keys [busy? ns history log]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ev/Evaluator
    (evaluating? [_] busy?)
    (prompt-ns [_] ns)
    (history [_] history)
    (send-input! [_ text] (swap! log conj [:stdin text]))
    (send-eof! [_] (swap! log conj [:eof]))
    (eval! [_ code opts] (swap! log conj [:eval code opts]) nil)))

(deftest refuse-foreign-says-the-refusal-test
  (let [log (atom [])
        sel* (atom ::win)]
    (with-redefs [tabs/runnable-here? (fn [w t] (= w t))
                  tabs/refusal-message pr-str
                  cview/append! (fn [cv kind text] (swap! log conj [cv kind text]))]
      (let [msg (#'win-run/refuse-foreign! {:tab {:project (atom ::other) :path (atom "/r/a.clj")}
                                            :cv ::cv :sel* sel*})]
        (is (= (pr-str {:file "a.clj" :tab-sel ::other :window-sel ::win}) msg))
        (is (= [[::cv :system msg]] @log)))
      (reset! log [])
      (is (nil? (#'win-run/refuse-foreign! {:tab {:project (atom ::win) :path (atom "/r/a.clj")}
                                            :cv ::cv :sel* sel*})))
      (is (= [] @log)))))

(deftest before-run-stopped-test
  (let [log (atom [])
        tab {:id 7 :path (atom nil)}
        ctx {:cv ::cv :status-label ::l}]
    (with-redefs [plugin-hooks/before-run! (fn [m] (swap! log conj m) "held by fmt")
                  cview/append! (fn [cv kind text] (swap! log conj [cv kind text]))
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (is (= "held by fmt" (#'win-run/before-run-stopped ctx :run tab "(f)")))
      (is (= [{:kind :run :text "(f)" :path nil :tab 7}
              [::cv :system "; held by fmt\n"]
              [::l "; held by fmt"]]
             @log)))
    (reset! log [])
    (with-redefs [plugin-hooks/before-run! (fn [m] (swap! log conj m) nil)
                  cview/append! (fn [cv kind text] (swap! log conj [cv kind text]))
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (is (nil? (#'win-run/before-run-stopped ctx :run tab "(f)")))
      (is (= [{:kind :run :text "(f)" :path nil :tab 7}] @log)))))

(deftest tab-record-test
  (let [e (evaluator {:ns 'zz.other :log (atom [])})
        tab {:path (atom "/r/a.clj")}]
    (is (= {:source :form :code "(f)" :path "/r/a.clj" :ns "a.b"}
           (#'win-run/tab-record tab (atom e) {:source :form :code "(f)" :buffer-text "(ns a.b)\n(f)"})))
    (is (= {:source :form :code "(f)" :path "/r/a.clj" :ns "zz.other"}
           (#'win-run/tab-record tab (atom e) {:source :form :code "(f)" :buffer-text "(f)"})))
    (is (= "zz.other" (#'win-run/run-record-ns e)))))

(deftest history-adapters-test
  (let [h (atom {:items ["a" "b"] :index 2 :draft ""})
        ev* (atom (evaluator {:history h}))
        prev (#'win-run/history-prev-fn ev*)
        next (#'win-run/history-next-fn ev*)]
    (is (= [nil "b" "a" "b" "typed" nil]
           [(next) (prev "typed") (prev "x") (next) (next) (next)]))
    (is (= {:items ["a" "b"] :index 2 :draft "typed"} @h))))

(deftest make-on-submit-dispatch-test
  (let [log (atom [])
        ev* (atom (evaluator {:ns 'user :log log}))
        *cv (atom nil)
        bang* (atom (fn [t] (swap! log conj [:bang t])))]
    (with-redefs [win-run/do-repl-eval! (fn [& args] (swap! log conj (vec args)))]
      (let [submit (#'win-run/make-on-submit (fn [] {:id 3 :ed ::ed}) *cv ev* ::l bang*)]
        (reset! *cv ::cv)
        (submit {:action :eval :text "!clear the console"})
        (submit {:action :eval :text "(+ 1 2)"})
        (submit {:action :send-stdin :text "hi\n"})
        (submit {:action :eof})))
    (is (= [[:bang "!clear the console"]
            [{:cv ::cv :ev* ev* :status-label ::l} ::ed "(+ 1 2)"
             {:hook-source :repl :hook-tab 3
              :record {:source :repl :code "(+ 1 2)" :path nil :ns "user"}}]
            [:stdin "hi\n"]
            [:eof]]
           @log))))

(deftest rerun-failure-dispatch-test
  (let [log (atom [])
        t {:id 5 :ed ::ed}
        ev* (atom (evaluator {:ns 'user :log (atom [])}))
        ctx {:tab t :cv ::cv :ev* ev* :sel* ::sel :status-label ::l}
        f-repl {:kind :repl :code "(x)"}
        rerun (fn [f] (#'win-run/rerun-failure! {:failure f :tab t :cv ::cv :ev* ev* :sel* ::sel :status-label ::l}))]
    (with-redefs [win-run/do-run! (fn [c] (swap! log conj [:run c]))
                  win-run/do-run-example! (fn [c code] (swap! log conj [:example c code]))
                  win-run/re-eval-failure! (fn [_ f t] (swap! log conj [:re-eval f t]))
                  win-run/refuse-foreign! (fn [_] "refused")
                  win-run/before-run-stopped (fn [& _] nil)]
      (rerun {:kind :run :code "r"})
      (rerun {:kind :example :code "c"})
      (rerun {:kind :selection :code "s"})
      (rerun f-repl))
    (is (= [[:run ctx] [:example ctx "c"] [:re-eval f-repl t]] @log))))

(deftest notifying-opts-chains-both-callbacks-test
  (let [log (atom [])
        base {:on-output (fn [kind s] (swap! log conj [:out kind s]))
              :on-complete (fn [r] (swap! log conj [:base r]))}
        notified (atom [])
        o (#'win-run/notifying-opts base (atom []) (fn [m] (swap! notified conj m)))]
    ((:on-output o) :out "a")
    ((:on-output o) :out "b")
    ((:on-complete o) ::r)
    (is (= [[:out :out "a"] [:out :out "b"] [:base ::r]] @log))
    (is (= [{:done {:result ::r :output "ab"}}] @notified))))

(deftest build-adds-the-run-fns-test
  (let [log (atom [])
        active (atom ::t1)
        w {:active-tab (fn [] @active) :cv ::cv :ev* ::ev :sel* ::sel :status-label ::l :x 1}]
    (with-redefs [win-run/do-run! (fn [c] (swap! log conj [:run c]))
                  win-run/do-stop! (fn [ev* cv] (swap! log conj [:stop ev* cv]))
                  win-run/do-run-selection! (fn [c] (swap! log conj [:sel c]))
                  win-run/do-run-changed! (fn [c] (swap! log conj [:changed c]))]
      (let [built (win-run/build w)
            c {:tab ::t2 :cv ::cv :ev* ::ev :sel* ::sel :status-label ::l}]
        (is (= (set (keys w)) (set (keys (dissoc built :run-fn :stop-fn :run-selection-fn :run-changed-fn)))))
        (is (= #{:run-fn :stop-fn :run-selection-fn :run-changed-fn}
               (set (remove (set (keys w)) (keys built)))))
        (is (= 1 (:x built)))
        (reset! active ::t2)
        ((:run-fn built))
        ((:stop-fn built))
        ((:run-selection-fn built))
        ((:run-changed-fn built))
        (is (= [[:run c] [:stop ::ev ::cv] [:sel c] [:changed c]] @log))))))

(defn- hook-env
  "The :changed and :recorder values hooked-opts takes, logging into log:
  :refresh, [:counts source code] and [:recorder record result] (the
  recorder answers ::rec-ret)."
  [log]
  {:changed {:ran (atom nil)
             :refresh (atom (fn [] (swap! log conj :refresh)))
             :counts-refresh (atom (fn [s c] (swap! log conj [:counts s c])))}
   :recorder (atom (fn [rec res] (swap! log conj [:recorder rec res]) ::rec-ret))})

(deftest hooked-opts-without-a-source-test
  (let [log (atom [])
        roots (atom 0)
        oc (fn [_] nil)
        in (merge {:x 1 :on-complete oc :hook-tab 3 :hook-code "c" :failure-kind :example
                   :failure* (atom nil)
                   :record {:source :run}
                   :replay-root (atom (fn [] (swap! roots inc) "/r"))}
                  (hook-env log))
        out (#'win-run/hooked-opts in)]
    (is (= {:x 1 :on-complete oc} out))
    (is (identical? oc (:on-complete out)))
    (is (= 1 @roots))
    (is (= {:x 1} (#'win-run/hooked-opts {:x 1 :hook-tab 3})))))

(deftest hooked-opts-completion-runs-every-step-in-order-test
  (let [log (atom [])
        failure* (atom ::old)
        env (hook-env log)
        r {:status :ok :printed "#'user/a"}
        o (#'win-run/hooked-opts
           (merge {:hook-source :run :hook-tab 3 :hook-code "(def a 1)" :failure* failure*
                   :on-complete (fn [r] (swap! log conj [:on-complete r]))
                   :record {:source :run :code "(def a 1)" :path nil :ns "user"}
                   :replay-root (atom (fn [] "/r"))}
                  env))]
    (with-redefs [plugin-hooks/after-eval! (fn [m] (swap! log conj [:after-eval m]))]
      (is (= ::rec-ret ((:on-complete o) r))))
    (is (= [[:on-complete r]
            :refresh
            [:counts :run "(def a 1)"]
            [:after-eval {:source :run :tab 3 :result r}]
            [:recorder {:source :run :code "(def a 1)" :path nil :ns "user" :tab-id 3 :root "/r"} r]]
           @log))
    (is (nil? @failure*))
    (is (= (changed/record nil "(def a 1)" :run) @(:ran (:changed env))))
    (is (not-any? #{:hook-source :failure-kind :hook-tab :hook-code :failure* :changed :record :recorder :replay-root}
                  (keys (dissoc o :on-complete))))))

(deftest hooked-opts-error-and-kinds-test
  (let [log (atom [])
        failure* (atom nil)
        env (hook-env log)
        base {:hook-tab 3 :hook-code "c" :failure* failure*}]
    (with-redefs [plugin-hooks/after-eval! (fn [m] (swap! log conj [:after-eval m]))]
      (let [o (#'win-run/hooked-opts (merge base env {:hook-source :selection :failure-kind :example}))]
        ((:on-complete o) {:status :error :error-text "boom"})
        (is (= {:kind :example :code "c" :tab-id 3 :error-text "boom"} @failure*))
        (is (some #{[:counts :selection "c"]} @log))
        (is (not-any? #{:refresh} @log))
        (is (nil? @(:ran (:changed env)))))
      (reset! log [])
      (let [o (#'win-run/hooked-opts (merge base env {:hook-source :repl}))]
        ((:on-complete o) {:status :ok})
        (is (not-any? #{:refresh} @log))
        (is (not-any? #(and (vector? %) (= :counts (first %))) @log))
        (is (nil? @failure*))
        (is (= :repl (get-in (first (filter #(= :after-eval (first %)) (filter vector? @log))) [1 :source])))))))

(deftest hooked-opts-hooks-never-throw-test
  (let [log (atom [])
        boom (fn [& _] (throw (ex-info "x" {})))
        env {:changed {:ran (atom nil) :refresh (atom boom) :counts-refresh (atom boom)}
             :recorder (atom boom)}
        o (#'win-run/hooked-opts
           (merge {:hook-source :run :hook-tab 3 :hook-code "(def a 1)"
                   :record {:source :run :code "(def a 1)"}}
                  env))]
    (with-redefs [plugin-hooks/after-eval! (fn [m] (swap! log conj [:after-eval m]))]
      (is (nil? ((:on-complete o) {:status :ok}))))
    (is (= [[:after-eval {:source :run :tab 3 :result {:status :ok}}]] @log))
    (is (= (changed/record nil "(def a 1)" :run) @(:ran (:changed env))))))

(deftest hooked-opts-root-is-read-at-submit-test
  (let [log (atom [])
        root* (atom "/r")
        mk (fn [replay-root]
             (#'win-run/hooked-opts
              (merge {:hook-source :repl :hook-tab 3 :record {:source :repl}
                      :replay-root replay-root}
                     (hook-env log))))]
    (with-redefs [plugin-hooks/after-eval! (fn [_] nil)]
      (let [o (mk (atom (fn [] @root*)))]
        (reset! root* "/s")
        ((:on-complete o) {:status :ok})
        (is (= "/r" (:root (second (last @log))))))
      (reset! log [])
      ((:on-complete (mk (atom nil))) {:status :ok})
      (is (nil? (:root (second (last @log)))))
      (is (= :recorder (first (last @log)))))))

(defn- run-env
  "Runs f with the console, status, plugin and tab seams stubbed to log into
  log. opts: busy? (the engine is evaluating), warning (what eval! answers),
  stop-line (what :before-run answers), runnable? (default true). Answers
  the atom holding eval!'s last opts."
  [log {:keys [busy? warning stop-line runnable?] :or {runnable? true}} f]
  (let [seen (atom nil)
        e #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify ev/Evaluator
          (evaluating? [_] busy?)
          (prompt-ns [_] 'user)
          (eval! [_ code opts]
            (swap! log conj [:eval code])
            (reset! seen opts)
            warning))]
    (with-redefs [editor/editor-text :text
                  cview/append! (fn [_ kind text] (swap! log conj [:append kind text]))
                  cview/mark-entry-start! (fn [_] (swap! log conj [:mark]))
                  cview/note-run-start! (fn [_] (swap! log conj [:note-start]))
                  win-status/set-status! (fn [_ text] (swap! log conj [:status text]))
                  plugin-hooks/before-run! (fn [m] (swap! log conj [:before-run m]) stop-line)
                  tabs/runnable-here? (fn [_ _] runnable?)
                  tabs/refusal-message (fn [_] "; refused\n")]
      (f e seen))))

(defn- run-tab [text ed-extra]
  {:id 7 :ed (merge {:text text} ed-extra) :path (atom "/r/a.clj") :project (atom nil)})

(defn- run-cv []
  {:replay-tab* (atom nil) :failure* (atom nil) :recorder* (atom nil) :replay-root* (atom nil)})

(defn- run-ctx [tab cv e]
  {:tab tab :cv cv :ev* (atom e) :status-label ::l :sel* (atom nil)})

(deftest do-run-refuses-a-foreign-tab-test
  (is (= '([ctx] [{:keys [tab cv ev* status-label] :as ctx} notify])
         (:arglists (meta #'win-run/do-run!))))
  (let [log (atom [])
        got (atom [])
        t "(+ 1 2)"]
    (run-env log {:runnable? false}
             (fn [e _]
               (let [ret (win-run/do-run! (run-ctx (run-tab t nil) (run-cv) e) #(swap! got conj %))]
                 (is (= "" (ret)))
                 (is (= [{:refused "; refused\n"}] @got))
                 (is (= [[:append :system "; refused\n"]] @log)))
               (reset! log [])
               (is (nil? (win-run/do-run! (run-ctx (run-tab t nil) (run-cv) e) nil)))))))

(deftest do-run-while-busy-says-the-warning-test
  (let [t "(+ 1 2)"
        log (atom [])
        got (atom [])]
    (run-env log {:busy? true :warning "; busy\n"}
             (fn [e _]
               (win-run/do-run! (run-ctx (run-tab t nil) (run-cv) e) #(swap! got conj %))
               (is (= [[:eval t] [:append :system "; busy\n"]] @log))
               (is (= [{:busy "; busy\n"}] @got))))
    (let [log (atom [])
          got (atom [])]
      (run-env log {:busy? true :warning nil}
               (fn [e _]
                 (win-run/do-run! (run-ctx (run-tab t nil) (run-cv) e) #(swap! got conj %))
                 (is (= [[:eval t]] @log))
                 (is (= [] @got)))))))

(deftest do-run-bracket-problem-test
  (let [t "(+ 1"
        log (atom [])
        got (atom [])
        problem (commands/pre-run-problem t)]
    (run-env log {}
             (fn [e _]
               (win-run/do-run! (run-ctx (run-tab t nil) (run-cv) e) #(swap! got conj %))
               (is (= [[:mark]
                       [:append :system (tabs/run-banner "/r/a.clj")]
                       [:append :error (str problem "\n")]
                       [:status "Ready"]]
                      @log))
               (is (= [{:problem problem}] @got))))))

(deftest do-run-plugin-stop-test
  (let [t "(+ 1 2)"
        log (atom [])
        got (atom [])]
    (run-env log {:stop-line "held"}
             (fn [e _]
               (win-run/do-run! (run-ctx (run-tab t nil) (run-cv) e) #(swap! got conj %))
               (is (= [[:before-run {:kind :run :text t :path "/r/a.clj" :tab 7}]
                       [:append :system "; held\n"]
                       [:status "; held"]]
                      @log))
               (is (= [{:refused "held"}] @got))))))

(deftest do-run-starts-and-hands-over-its-opts-test
  (let [t "(+ 1 2)"
        log (atom [])
        got (atom [])]
    (run-env log {}
             (fn [e seen]
               (let [ret (win-run/do-run! (run-ctx (run-tab t nil) (run-cv) e) #(swap! got conj %))
                     o @seen]
                 (is (= [[:before-run {:kind :run :text t :path "/r/a.clj" :tab 7}]
                         [:mark]
                         [:append :system (tabs/run-banner "/r/a.clj")]
                         [:note-start]
                         [:eval t]]
                        @log))
                 (is (= {:run? true :path "/r/a.clj" :owner 7}
                        (select-keys o [:run? :path :owner :reset? :clear-line-counts?])))
                 (is (not-any? #(contains? o %)
                               [:hook-source :hook-tab :hook-code :failure* :changed
                                :recorder :replay-root :record]))
                 ((:on-output o) :out "x")
                 (is (= "x" (ret)))))))
  (let [log (atom [])]
    (run-env log {}
             (fn [e seen]
               (is (nil? (win-run/do-run! (run-ctx (run-tab "(f)" nil) (run-cv) e))))
               (is (= 7 (:owner @seen)))))))

(deftest do-run-opts-follow-the-replay-tab-and-counting-test
  (let [log (atom [])
        cv (assoc (run-cv) :replay-tab* (atom {:id 7}))
        tab (run-tab "(f)" {:changed {:counting? (atom (constantly true))}})]
    (run-env log {}
             (fn [e seen]
               (win-run/do-run! (run-ctx tab cv e) nil)
               (is (= {:run? true :path "/r/a.clj" :owner :repl :reset? false
                       :clear-line-counts? true}
                      (select-keys @seen [:run? :path :owner :reset? :clear-line-counts?])))))))

(deftest hooked-opts-a-throwing-refresh-keeps-the-counts-test
  (let [log (atom [])
        env (assoc-in (hook-env log) [:changed :refresh] (atom (fn [] (throw (ex-info "x" {})))))
        o (#'win-run/hooked-opts
           (merge {:hook-source :run :hook-tab 3 :hook-code "(def a 1)"} env))]
    (with-redefs [plugin-hooks/after-eval! (fn [m] (swap! log conj [:after-eval m]))]
      (is (nil? ((:on-complete o) {:status :ok}))))
    (is (= [[:counts :run "(def a 1)"]
            [:after-eval {:source :run :tab 3 :result {:status :ok}}]]
           @log))))

(deftest hooked-opts-form-source-records-a-form-test
  (let [log (atom [])
        env (hook-env log)
        o (#'win-run/hooked-opts
           (merge {:hook-source :form :hook-tab 3 :hook-code "(f)"} env))]
    (with-redefs [plugin-hooks/after-eval! (fn [_] nil)]
      ((:on-complete o) {:status :ok}))
    (is (= (changed/record nil "(f)" :form) @(:ran (:changed env))))
    (is (some #{[:counts :form "(f)"]} @log))))

(deftest hooked-opts-a-throwing-on-complete-skips-the-later-steps-test
  (let [log (atom [])
        env (hook-env log)
        o (#'win-run/hooked-opts
           (merge {:hook-source :run :hook-tab 3 :hook-code "(f)"
                   :record {:source :run :code "(f)"}
                   :on-complete (fn [_] (throw (ex-info "own" {})))}
                  env))]
    (with-redefs [plugin-hooks/after-eval! (fn [m] (swap! log conj [:after-eval m]))]
      (is (thrown? Exception ((:on-complete o) {:status :ok}))))
    (is (= [] @log))
    (is (nil? @(:ran (:changed env))))))

(defn- counting-eval-opts
  "Redefs make-eval-opts to count its calls in calls, then runs f."
  [calls f]
  (let [orig win-run/make-eval-opts]
    (with-redefs [win-run/make-eval-opts (fn [& args] (swap! calls inc) (apply orig args))]
      (f))))

(deftest do-run-opts-thunk-stays-lazy-on-early-paths-test
  (let [t "(+ 1 2)"]
    (doseq [[label env text]
            [["refuse" {:runnable? false} t]
             ["problem" {} "(+ 1"]
             ["plugin stop" {:stop-line "held"} t]]]
      (let [log (atom [])
            calls (atom 0)]
        (run-env log env
                 (fn [e _]
                   (counting-eval-opts
                    calls
                    #(win-run/do-run! (run-ctx (run-tab text nil) (run-cv) e) (fn [_] nil)))
                   (is (= 0 @calls) label)))))
    (let [log (atom [])
          calls (atom 0)]
      (run-env log {}
               (fn [e _]
                 (counting-eval-opts
                  calls
                  #(win-run/do-run! (run-ctx (run-tab t nil) (run-cv) e) nil))
                 (is (= 1 @calls) "an idle start builds its opts once"))))))

(deftest do-run-idle-eval-warning-says-busy-test
  (let [t "(+ 1 2)"
        log (atom [])
        got (atom [])]
    (run-env log {:warning "; busy\n"}
             (fn [e _]
               (win-run/do-run! (run-ctx (run-tab t nil) (run-cv) e) #(swap! got conj %))
               (is (= [{:busy "; busy\n"}] @got))
               (is (some #{[:append :system "; busy\n"]} @log))))))

(deftest do-run-second-busy-check-skips-note-run-start-test
  (let [t "(+ 1 2)"
        log (atom [])
        n (atom 0)
        e #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify ev/Evaluator
          (evaluating? [_] (> (swap! n inc) 1))
          (prompt-ns [_] 'user)
          (eval! [_ code _] (swap! log conj [:eval code]) nil))]
    (run-env log {}
             (fn [_ _]
               (win-run/do-run! (run-ctx (run-tab t nil) (run-cv) e) nil)
               (is (= 2 @n))
               (is (= [[:eval t]] (filterv #{[:eval t] [:note-start]} @log)))))))

(deftest do-run-without-notify-returns-nil-on-early-paths-test
  (doseq [[label env text] [["busy" {:busy? true :warning "; busy\n"} "(+ 1 2)"]
                            ["problem" {} "(+ 1"]
                            ["plugin stop" {:stop-line "held"} "(+ 1 2)"]]]
    (let [log (atom [])]
      (run-env log env
               (fn [e _]
                 (is (nil? (win-run/do-run! (run-ctx (run-tab text nil) (run-cv) e) nil)) label))))))

(deftest do-run-record-reaches-hooked-opts-test
  (let [log (atom [])
        got (atom nil)
        tab (run-tab "(f)" nil)]
    (run-env log {}
             (fn [e _]
               (with-redefs [win-run/hooked-opts (fn [o] (reset! got o) {})]
                 (win-run/do-run! (run-ctx tab (run-cv) e) nil)
                 (is (= {:source :run :code "(f)" :path "/r/a.clj" :ns (#'win-run/run-record-ns e)}
                        (:record @got)))
                 (is (= "user" (:ns (:record @got))))
                 (reset! got nil)
                 (win-run/do-run! (run-ctx tab (run-cv) e) (fn [_] nil))
                 (is (contains? @got :record))
                 (is (nil? (:record @got))))))))
