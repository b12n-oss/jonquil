(ns jonquil.gtk.window.run-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
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
           (#'win-run/tab-record tab (atom e) :form "(f)" "(ns a.b)\n(f)")))
    (is (= {:source :form :code "(f)" :path "/r/a.clj" :ns "zz.other"}
           (#'win-run/tab-record tab (atom e) :form "(f)" "(f)")))
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
            [::ed ::cv ev* ::l "(+ 1 2)"
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
