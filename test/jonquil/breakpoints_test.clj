(ns jonquil.breakpoints-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.breakpoints :as bp]
            [jonquil.changed :as changed]
            ;; the inserted form names both
            [jolt.debug]
            [jonquil.debugger :as debugger]))

(defn- heads
  "The runtime's answer about text's heads, from this process's user
  namespace: what the window asks before placing a breakpoint."
  [text]
  (debugger/macro-heads "user" (bp/head-symbols text)))

(defn- at
  "[snippet kind] of the spot a breakpoint on line attaches to, or nil
  when nothing on that line can pause. The runtime answers about the heads
  unless opts says otherwise."
  ([text line] (at text line (heads text)))
  ([text line opts]
   (when-let [s (first (filter #(= line (:line %)) (bp/spots text opts)))]
     [(subs text (:start s) (:end s)) (:kind s)])))

(defn- place-h [t line] (bp/place t line (heads t)))

(defn- instrument-h
  ([t ls] (bp/instrument t ls (heads t)))
  ([t ls r] (bp/instrument t ls (merge r (heads t)))))

(defn- lines [& ls] (str/join "\n" ls))

(deftest body-forms-are-statements-test
  (let [t (lines "(defn f [x]"
                 "  (println x)"
                 "  (inc x))")]
    (is (nil? (at t 1)) "the defn line: the defn, its name and params hold none")
    (is (= ["(println x)" :statement] (at t 2)))
    (is (= ["(inc x)" :statement] (at t 3)))))

(deftest let-names-and-inits-test
  (let [t (lines "(defn f [x]"
                 "  (let [a"
                 "        (inc x)"
                 "        b x]"
                 "    (* a b)))")]
    (is (= ["(let [a\n        (inc x)\n        b x]\n    (* a b))" :statement] (at t 2))
        "the let itself is a body form")
    (is (= ["(inc x)" :expression] (at t 3)) "an init is code")
    (is (nil? (at (lines "(let [a 1" "      b" "      2] b)") 2)) "a binding name isn't")
    (is (= ["x" :expression] (at t 4)) "b is a name, x on the same line is its init")
    (is (= ["(* a b)" :statement] (at t 5)))))

(deftest destructuring-names-hold-none-test
  (let [t (lines "(let [{:keys [a]"
                 "       :or {a (dflt)}} m]"
                 "  a)")]
    (is (= ["m" :expression] (at t 2))
        "an :or default sits in a binding name and is left alone: m, the init, is chosen")))

(deftest fn-params-and-arities-test
  (let [t (lines "(defn g"
                 "  \"doc\""
                 "  ([x]"
                 "   (g x 1))"
                 "  ([x"
                 "    y]"
                 "   (+ x y)))")]
    (is (nil? (at t 1)))
    (is (nil? (at t 2)) "the docstring")
    (is (nil? (at t 3)) "an arity's list and params")
    (is (= ["(g x 1)" :statement] (at t 4)))
    (is (nil? (at t 6)) "a param")
    (is (= ["(+ x y)" :statement] (at t 7))))
  (let [t (lines "(fn named [a]" "  {:pre [(pos? a)]}" "  (dec a))")]
    (is (nil? (at t 2)) "a {:pre} map before the body")
    (is (= ["(dec a)" :statement] (at t 3)))))

(deftest case-tests-hold-none-test
  (let [t (lines "(case (kind x)"
                 "  :a"
                 "  (one)"
                 "  (b c)"
                 "  (two)"
                 "  (other))")]
    (is (= :statement (second (at (lines "(do" "  (case (kind x)" "    :a 1))") 2))) "case itself, as a body form")
    (is (nil? (at t 2)))
    (is (= ["(one)" :expression] (at t 3)))
    (is (nil? (at t 4)) "a list of tests is not a call")
    (is (= ["(two)" :expression] (at t 5)))
    (is (= ["(other)" :expression] (at t 6)) "the default")))

(deftest try-catch-finally-test
  (let [t (lines "(try"
                 "  (risky)"
                 "  (catch Exception e"
                 "    (log e))"
                 "  (finally"
                 "    (done)))")]
    (is (= ["(risky)" :statement] (at t 2)))
    (is (nil? (at t 3)) "the catch clause, its class and name")
    (is (= ["(log e)" :statement] (at t 4)))
    (is (nil? (at t 5)) "the finally clause")
    (is (= ["(done)" :statement] (at t 6)))))

(deftest cond-tests-and-exprs-test
  (let [t (lines "(cond"
                 "  (neg? x)"
                 "  (fix x)"
                 "  :else"
                 "  x)")]
    (is (= ["(neg? x)" :expression] (at t 2)))
    (is (= ["(fix x)" :expression] (at t 3)))
    (is (nil? (at t 4)))
    (is (= ["x" :expression] (at t 5)))))

(deftest quoted-forms-hold-none-test
  (let [t (lines "(f '(a"
                 "     (b))"
                 "   `(c"
                 "     ~(d))"
                 "   #?(:clj"
                 "      (e)))")]
    (is (nil? (at t 1)) "f's call is top level, and the quoted list holds none")
    (is (nil? (at t 2)))
    (is (nil? (at t 4)) "an unquote inside a syntax quote is still left alone")
    (is (nil? (at t 6)) "a reader conditional")))

(deftest letfn-specs-test
  (let [t (lines "(letfn [(sq [n]"
                 "          (* n n))]"
                 "  (sq 3))")]
    (is (= ["(* n n)" :statement] (at t 2)))
    (is (= ["(sq 3)" :statement] (at t 3)))))

(deftest reify-and-deftype-methods-test
  (let [t (lines "(deftype P [a]"
                 "  Proto"
                 "  (m [this x]"
                 "    (use-it a x)))"
                 "(reify Proto"
                 "  (m [_ y]"
                 "    (h y)))")]
    (is (nil? (at t 2)) "a protocol name")
    (is (nil? (at t 3)) "a method's name and params")
    (is (= ["(use-it a x)" :statement] (at t 4)))
    (is (nil? (at t 6)))
    (is (= ["(h y)" :statement] (at t 7)))))

(deftest ns-and-comment-test
  (let [t (lines "(ns demo"
                 "  (:require [x.y :as z]))"
                 "(comment"
                 "  (f 1)"
                 "  )")]
    (is (nil? (at t 2)) "nothing in the ns form")
    (is (nil? (at t 4)) "a form in a comment block is top level: (f 1) itself holds none")
    (is (= ["(g)" :expression] (at "(comment\n  (f\n   (g)))" 3)) "but code inside it does")))

(deftest threading-and-unknown-macros-test
  (let [t (lines "(-> (start)"
                 "    (step 1)"
                 "    (end))"
                 "(with-thing [x 1]"
                 "  (body))")]
    (is (nil? (at t 2)) "a threaded form isn't an expression of its own")
    (is (= ["(body)" :expression] (at t 5)) "an unresolved head stays a call")
    (is (nil? (at t 5 {})) "with no answer from the runtime it may be a macro")
    (let [t2 (lines "(do (->" "  (start)" "  (end)))")]
      (is (= ["(start)" :expression] (at t2 2)) "the threaded value itself is code")
      (is (nil? (at t2 3))))))

(deftest place-snaps-to-the-next-line-test
  (let [t (lines "(ns demo)"
                 ""
                 "(defn f [x]"
                 "  ;; note"
                 "  (inc x))")]
    (is (= {:line 5} (place-h t 5)))
    (is (= {:line 5 :moved? true} (place-h t 1)))
    (is (= {:line 5 :moved? true} (place-h t 3)))
    (is (= {:line 5 :moved? true} (place-h t 4)) "a comment line")
    (is (= {:error bp/NOWHERE} (place-h t 6)))))

(deftest instrument-keeps-lines-and-values-test
  (let [t (lines "(defn f [x]"
                 "  (let [y (inc x)]"
                 "    (+ y"
                 "       (dec x))))")
        {out :text ls :lines} (instrument-h t [2 4 1])]
    (is (= [2 4] ls) "line 1 holds none, so it's skipped")
    (is (= (count (str/split-lines t)) (count (str/split-lines out))) "no newline is added")
    (is (str/includes? out (str "  " (bp/breakpoint-form 2) " (let [y")))
    (is (str/includes? out (str "(do " (bp/breakpoint-form 4) " (dec x))")))))

(deftest instrument-a-range-test
  (let [t (lines "(defn a [] (one))" "(defn b []" "  (two))")
        r {:start (inc (str/index-of t "\n")) :end (count t)}
        {out :text ls :lines} (instrument-h t [1 3] r)]
    (is (= [3] ls) "a breakpoint outside the range doesn't count")
    (is (str/starts-with? out "(defn b []"))
    (is (str/includes? out (str (bp/breakpoint-form 3) " (two)")))))

(deftest adjacent-wraps-nest-right-test
  (let [t "(f (a)\n(b))"
        {out :text} (instrument-h t [1 2])]
    (is (= 1 (count (filter #{\newline} out))))
    (is (str/includes? out "(do ") "both wrapped")
    (is (= (count (re-seq #"\(" out)) (count (re-seq #"\)" out))) "brackets balance")))

(deftest instrumented-code-runs-test
  (let [t (lines "(fn [x]"
                 "  (let [y (inc x)]"
                 "    (+ y"
                 "       (dec x))))")
        {out :text} (instrument-h t [2 3 4])]
    (testing "with the hook empty the breakpoints do nothing and the value is kept"
      (is (= 10 ((eval (read-string out)) 5))))))

(deftest breakpoint-throw-test
  (is (bp/breakpoint-throw? {:jonquil/breakpoint 3}))
  (is (not (bp/breakpoint-throw? {:x 1})))
  (is (not (bp/breakpoint-throw? nil))))

(deftest resend-only-forms-that-define-code-test
  (let [t (lines "(def db (connect!))"
                 "(defn f [x]"
                 "  (inc x))"
                 "(def g (fn [y] (dec y)))"
                 "(def h #(* % 2))"
                 "(defonce server (start!))"
                 "(println (f 1))"
                 "(comment"
                 "  (defn k [] (side-effect!)))"
                 "(defmethod area :sq [s]"
                 "  (* (:a s) (:a s)))")]
    (is (nil? (bp/resend-range t 1)) "(def x (side-effect!)) never runs again")
    (is (= "(defn f [x]\n  (inc x))" (let [{:keys [start end]} (bp/resend-range t 3)] (subs t start end))))
    (is (some? (bp/resend-range t 4)) "a def of a fn")
    (is (some? (bp/resend-range t 5)) "a def of #()")
    (is (nil? (bp/resend-range t 6)))
    (is (nil? (bp/resend-range t 7)) "a top-level call")
    (is (nil? (bp/resend-range t 9)) "a form inside a comment block, even a defn")
    (is (some? (bp/resend-range t 11)) "defmethod")))

;; --- the pause, with the real jolt.debug ----------------------------------------

(defn- armed
  "An armed hook over a fresh break: {:brk :paused (promise of the pause's
  data) :resumed (atom)}. can-pause? defaults to always."
  ([] (armed (constantly true)))
  ([can-pause?]
   (let [brk (debugger/make-break)
         paused (promise)
         resumed (atom [])
         hook (debugger/breakpoint-handler brk (fn [] {:on-pause (fn [d] (deliver paused d))
                                                       :on-resume (fn [how] (swap! resumed conj how))})
                                           can-pause?)]
     {:brk brk :paused paused :resumed resumed :hook hook :arm (debugger/arm-breakpoints! hook)})))

(defn- compile-instrumented
  "The fn the text of a (fn ...) compiles to with breakpoints on lines."
  [text lines]
  (eval (read-string (:text (bp/instrument text lines (heads text))))))

(def ^:private adder
  (lines "(fn [x]"
         "  (let [y (* x 2)]"
         "    (+ y 1)))"))

(defn- ask [brk i code]
  (let [p (promise)]
    (debugger/break-eval! brk i code (fn [r] (deliver p r)))
    (deref p 3000 :no-reply)))

(deftest breakpoint-pauses-with-locals-and-continues-test
  (if-not (debugger/break-available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [{:keys [brk paused resumed hook arm]} (armed)]
      (try
        (is (:on? arm))
        (let [f (compile-instrumented adder [3])
              fut (future (f 20))
              d (deref paused 5000 nil)]
          (is (= "Breakpoint at line 3" (:title d)))
          (is (= 3 (:breakpoint d)))
          (is (some #(= {:name "y" :text "40"} %) (:locals (first (:frames d))))
              (str "the first frame is the fn's own, with its locals: " (pr-str (first (:frames d)))))
          (is (= {:out "" :text "41"} (ask brk 0 "(inc y)")))
          (is (= 40 (debugger/paused-local brk (:error-id d) 0 "y")))
          (testing "a second thread reaching a breakpoint while one is paused runs on"
            (is (= 21 (deref (future (f 10)) 5000 :blocked))))
          (is (true? (debugger/continue! brk)))
          (is (= 41 (deref fut 5000 :blocked)) "the code runs on with its value")
          (is (= [{:continued? true}] @resumed)))
        (finally (debugger/disarm-breakpoints! hook) (debugger/set-debug-mode! false))))))

(deftest breakpoint-stop-ends-the-evaluation-test
  (if-not (debugger/break-available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [{:keys [brk paused resumed hook]} (armed)]
      (try
        (let [f (compile-instrumented adder [2])
              fut (future (try (f 1) (catch Throwable t t)))]
          (is (some? (deref paused 5000 nil)))
          (debugger/break-stop! brk)
          (is (instance? InterruptedException (deref fut 5000 :blocked)))
          (is (= [{:continued? false}] @resumed)))
        (finally (debugger/disarm-breakpoints! hook) (debugger/set-debug-mode! false))))))

(deftest breakpoint-runs-on-when-it-cant-pause-test
  (if-not (debugger/break-available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [{:keys [paused hook]} (armed (constantly false))]
      (try
        (is (= 41 ((compile-instrumented adder [3]) 20)))
        (is (not (realized? paused)))
        (finally (debugger/disarm-breakpoints! hook) (debugger/set-debug-mode! false))))))

(deftest disarm-leaves-another-hook-test
  (let [a (fn [_ _]) b (fn [_ _])]
    (reset! debugger/breakpoint-hook a)
    (debugger/disarm-breakpoints! b)
    (is (identical? a @debugger/breakpoint-hook))
    (debugger/disarm-breakpoints! a)
    (is (nil? @debugger/breakpoint-hook))))

(deftest toggle-message-test
  (is (= "; Breakpoint set; it takes effect when you next run this form."
         (str/replace (bp/toggle-message {:added? true :line 4}) " at line 4" ""))
      "the words the design names")
  (is (= "; Breakpoint removed; it takes effect when you next run this form."
         (bp/toggle-message {:added? false :line 4})))
  (is (= "; Breakpoint set at line 4; it's live now." (bp/toggle-message {:added? true :line 4 :sent :ok})))
  (is (= "; Breakpoint moved to line 6; it's live now." (bp/toggle-message {:added? true :moved? true :line 6 :sent :ok})))
  (is (= "; Breakpoint removed; the form runs without it now." (bp/toggle-message {:line 4 :sent :ok})))
  (is (str/includes? (bp/toggle-message {:added? true :line 4 :sent :failed :error "Unable to resolve symbol: q"})
                     "sending its form again failed"))
  (is (str/ends-with? (bp/toggle-message {:added? true :line 4 :sent :ok :debug-on? true})
                      "Debug mode is on now, so frames keep their locals.")))

;; --- macros: what the runtime says a head is ------------------------------------

(def ^:private spec-def {"s/def" {:var "clojure.spec.alpha/def" :macro true}})

(deftest qualified-macro-heads-keep-their-namespace-test
  (let [t (lines "(do"
                 "  (s/def ::age"
                 "    (pred x)))")]
    (is (= ["(s/def ::age\n    (pred x))" :statement] (at t 2 {:heads spec-def})))
    (is (nil? (at t 3 {:heads spec-def})) "s/def is spec's macro, not def: its init is left alone")
    (is (nil? (at t 3 {})) "with no answer it may be a macro too"))
  (let [t (lines "(do"
                 "  (my.ns/def x"
                 "    (f)))")]
    (is (= ["(f)" :expression] (at t 3 {:heads {"my.ns/def" {:var "my.ns/def"} "f" nil}}))
        "a qualified non-core def that is a fn is a call")
    (is (nil? (at t 3 {:heads {"my.ns/def" {:var "my.ns/def" :macro true}}})))
    (is (= ["(f)" :expression] (at t 3 {:heads {"my.ns/def" nil}})) "unresolved: a call")))

(deftest interop-targets-are-never-wrapped-test
  (let [t (lines "(do"
                 "  (. Math"
                 "     (abs x))"
                 "  (.. System"
                 "      (getProperties)"
                 "      (get \"a\"))"
                 "  (."
                 "    Math"
                 "    abs 1))")]
    (is (= :statement (second (at t 2))))
    (is (nil? (at t 3)) "a member call inside . is not a call")
    (is (nil? (at t 5)))
    (is (nil? (at t 8)) "the class target")))

(deftest unknown-binding-macros-test
  (let [t (lines "(go-loop [x"
                 "         (init)]"
                 "  (body x))")
        go {"go-loop" {:var "clojure.core.async/go-loop" :macro true}}]
    (is (nil? (at t 2 {:heads go})) "a macro's binding vector is left alone")
    (is (nil? (at t 3 {:heads go})))
    (is (nil? (at t 2 {}))))
  (let [t (lines "(let* [a"
                 "       (f)]"
                 "  (g a))")]
    (is (= ["(f)" :expression] (at t 2)) "let* is let's special form")
    (is (= ["(g a)" :statement] (at t 3)))
    (is (nil? (at (lines "(let* [a" "       b" "       c" "       d]" "  a)") 3))
        "c is a binding name")))

(deftest a-defmacro-in-the-text-is-a-macro-test
  (let [t (lines "(defmacro mm [& body] `(do ~@body))"
                 "(defn g []"
                 "  (mm"
                 "    (f)))")]
    (is (= ["(mm\n    (f))" :statement] (at t 3)) "the macro call itself is a body form")
    (is (nil? (at t 4)) "mm isn't loaded yet, but the text defines it")))

(deftest tagged-literals-test
  (let [t (lines "(do"
                 "  (f #my/tag"
                 "     (g)))")]
    (is (nil? (at t 3)) "a tagged literal's value is the tag reader's")))

(deftest discard-meta-and-anon-fn-test
  (is (= ["(g)" :statement] (at (lines "(do" "  #_(f)" "  (g))") 3)))
  (is (nil? (at (lines "(do" "  #_(f)" "  (g))") 2)) "a discarded form isn't there")
  (let [t (lines "(do" "  ^:k (f)" "  (g))")]
    (is (= ["^:k (f)" :statement] (at t 2)) "the metadata goes with the form"))
  (let [t (lines "(mapv #(+ %" "         (inc %)) [1 2])")]
    (is (= ["(inc %)" :expression] (at t 2)))
    (is (= [3 5] (eval (read-string (:text (instrument-h t [2])))))
        "a breakpoint inside #() keeps % and the value")))

(deftest condp-test
  (let [t (lines "(condp = x"
                 "  1 (one)"
                 "  (two))")]
    (is (= ["(one)" :expression] (at t 2)) "the test constant can't pause; its result can")
    (is (= ["(two)" :expression] (at t 3)))))

(deftest resend-leaves-types-and-dispatch-tables-test
  (let [t (lines "(deftype P [a])"
                 "(defrecord R [b])"
                 "(defprotocol Q (q [x]))"
                 "(defmulti area :shape)"
                 "(defmacro m [x] x)")]
    (doseq [l [1 2 3 4]]
      (is (nil? (bp/resend-range t l)) (str "line " l ": a new class or dispatch table changes the program")))
    (is (some? (bp/resend-range t 5)))))

(deftest resend-skips-a-form-with-edits-that-havent-run-test
  (let [ran-text (lines "(defn f [x]" "  (inc x))")
        ran (changed/record nil ran-text :run)
        edited (lines "(defn f [x]" "  (dec x))")]
    (is (false? (bp/edited? ran-text ran (bp/resend-range ran-text 2))) "as it ran")
    (is (true? (bp/edited? edited ran (bp/resend-range edited 2))) "edited since")
    (is (false? (bp/edited? edited nil (bp/resend-range edited 2))) "a tab that never ran marks nothing")
    (is (= "; Breakpoint set; this form has edits that haven't run, so Run Form to apply both."
           (str/replace (bp/toggle-message {:added? true :line 2 :sent :edited}) " at line 2" "")))
    (is (= "; Breakpoint removed; this form has edits that haven't run, so Run Form to apply both."
           (bp/toggle-message {:added? false :line 2 :sent :edited})))))

;; --- one pause at a time, and who ends it -----------------------------------------

(deftest an-error-while-a-breakpoint-is-paused-goes-on-test
  (if-not (debugger/break-available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [{:keys [brk paused hook]} (armed)]
      (try
        (let [f (compile-instrumented adder [3])
              fut (future (f 20))
              _ (deref paused 5000 nil)
              boom (ex-info "boom" {})
              wrap (debugger/break-wrapper brk {:on-pause (fn [_] (throw (ex-info "must not pause" {})))})
              err (deref (future (try (wrap (fn [] (throw boom))) (catch Throwable t t))) 5000 :blocked)]
          (is (identical? boom err) "Break on error lets the throw go on while the breakpoint holds the pause")
          (is (true? (debugger/continue! brk)))
          (is (= 41 (deref fut 5000 :blocked))))
        (finally (debugger/disarm-breakpoints! hook) (debugger/set-debug-mode! false))))))

(deftest end-stale-pause-and-another-threads-pause-test
  (if-not (debugger/break-available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [{:keys [brk paused resumed hook]} (armed)]
      (try
        (let [f (compile-instrumented adder [3])
              fut (future (f 20))]
          (is (some? (deref paused 5000 nil)))
          (testing "left alone: the window can still show and continue it"
            (debugger/end-stale-pause! brk {} {:other-thread :leave})
            (is (some? @(:paused brk)))
            (is (not (realized? fut))))
          (testing "woken: it runs on"
            (debugger/end-stale-pause! brk {} {:other-thread :wake})
            (is (= 41 (deref fut 5000 :blocked)))
            (is (= [{:continued? true}] @resumed))))
        (finally (debugger/disarm-breakpoints! hook) (debugger/set-debug-mode! false))))))

;; --- re-review: heads the runtime couldn't resolve, stale heads, dropped dots ----

(deftest unresolved-heads-are-macros-when-the-namespace-wasnt-found-test
  (let [t (lines "(do"
                 "  (go-loop [x"
                 "           (init)]"
                 "    (body x)))")
        h {"do" nil "go-loop" nil "init" nil "body" nil}]
    (is (= ["(init)" :expression] (at t 3 {:heads h})) "found namespace: unresolved is a call")
    (is (nil? (at t 3 {:heads h :ns-found? false}))
        "the buffer's namespace isn't loaded: go-loop may come from its :refer")))

(deftest cached-heads-follow-the-ns-form-test
  (let [cache {:ns-form "(ns demo.a)" :heads {"go-loop" {:var "clojure.core.async/go-loop" :macro true}} :ns-found? true}]
    (is (= {:heads (:heads cache) :ns-found? true} (bp/cached-heads cache "(ns demo.a)\n(f)")))
    (is (= {} (bp/cached-heads cache "(ns demo.b)\n(f)")) "the ns form changed: the answer is stale")
    (is (= {} (bp/cached-heads nil "(f)")))
    (is (= {:heads {"f" nil} :ns-found? true} (bp/cached-heads {:ns-form nil :heads {"f" nil} :ns-found? true} "(f)"))
        "no ns form, asked without one")))

(deftest dropped-lines-name-dots-that-wont-pause-test
  (let [t (lines "(defn f [x]"
                 "  (mm"
                 "    (g x)))")
        h {:heads {"defn" {:var "clojure.core/defn" :macro true} "mm" {:var "user/mm" :macro true} "g" nil}
           :ns-found? true}]
    (is (= [3] (bp/dropped-lines t [2 3] h)) "line 3 is inside a macro now")
    (is (= [] (bp/dropped-lines t [2] h)))
    (is (= "; Breakpoint at line 3 is inside a macro, so it won't pause." (bp/dropped-message [3])))
    (is (= "; Breakpoints at lines 3, 7 are inside a macro, so they won't pause." (bp/dropped-message [3 7])))))

(deftest a-tab-that-never-ran-counts-unsaved-or-changed-forms-as-edited-test
  (let [t (lines "(defn f [x]" "  (inc x))")
        rng (bp/resend-range t 2)]
    (is (false? (bp/edited? t nil rng {:dirty? false :disk-text t})) "saved and as on disk")
    (is (true? (bp/edited? t nil rng {:dirty? true :disk-text nil})) "unsaved edits")
    (is (true? (bp/edited? t nil rng {:dirty? false :disk-text (lines "(defn f [x]" "  (dec x))")}))
        "the form differs from the file on disk")
    (is (false? (bp/edited? t nil rng {:dirty? false :disk-text nil})) "no file, nothing typed since")))

(deftest a-claim-that-outlives-its-eval-runs-on-test
  (if-not (debugger/break-available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [brk (debugger/make-break)
          paused (promise)
          answers (atom [true false])
          ;; true when the claim is taken, false by the time it publishes:
          ;; the eval ended in between
          can-pause? (fn [] (let [[a & more] @answers] (reset! answers (or more [false])) a))
          hook (debugger/breakpoint-handler brk (fn [] {:on-pause (fn [d] (deliver paused d))}) can-pause?)]
      (try
        (debugger/arm-breakpoints! hook)
        (is (= 41 (deref (future ((compile-instrumented adder [3]) 20)) 5000 :blocked))
            "the pause is let go at once instead of waiting for a Continue nobody can send")
        (is (nil? @(:paused brk)))
        (is (not (realized? paused)) "and the window never hears of a pause already let go")
        (finally (debugger/disarm-breakpoints! hook) (debugger/set-debug-mode! false))))))

;; --- third review ------------------------------------------------------------------

(deftest heads-the-answer-doesnt-name-are-macros-test
  (let [before (lines "(ns demo.c)" "(defn f [x]" "  (inc x))")
        cache (assoc (debugger/macro-heads "user" (bp/head-symbols before)) :ns-form "(ns demo.c)")
        after (lines "(ns demo.c)" "(defn f [x]" "  (frob" "    (inc x)))")
        opts (bp/cached-heads cache after)]
    (is (= :statement (second (at after 3 opts))) "the new call itself is a body form")
    (is (nil? (at after 4 opts)) "frob was typed after the answer: it may be a macro")
    (is (= [4] (bp/dropped-lines after [4] opts)) "so a dot there is named as dropped")))

(deftest an-added-refer-makes-the-answer-stale-test
  (let [before (lines "(ns demo.d)" "(defn f [x]" "  (go-loop" "    (inc x)))")
        cache {:ns-form "(ns demo.d)" :ns-found? true :heads {"defn" {:var "clojure.core/defn" :macro true}
                                                              "go-loop" nil "inc" {:var "clojure.core/inc"}}}
        after (lines "(ns demo.d (:require [clojure.core.async :refer [go-loop]]))"
                     "(defn f [x]" "  (go-loop" "    (inc x)))")]
    (is (= ["(inc x)" :expression] (at before 4 (bp/cached-heads cache before))) "as asked: go-loop names nothing")
    (is (= {} (bp/cached-heads cache after)) "the ns form changed, though its name didn't")
    (is (nil? (at after 4 (bp/cached-heads cache after))))))

(deftest refresh-report-only-from-an-answer-on-unchanged-text-test
  (let [t (lines "(defn f [x]" "  (mm" "    (g x)))")
        r {:heads {"defn" {:var "clojure.core/defn" :macro true} "mm" {:var "user/mm" :macro true} "g" nil}
           :ns-found? true}]
    (is (= "; Breakpoint at line 3 is inside a macro, so it won't pause."
           (bp/refresh-report {:asked t :now t :answer r :lines [3]})))
    (is (nil? (bp/refresh-report {:asked t :now t :answer {:error "timed out"} :lines [3]}))
        "no answer: nothing is known to be dropped")
    (is (nil? (bp/refresh-report {:asked t :now (str t "\n") :answer r :lines [3]})) "the text moved on")))

(deftest a-toggle-says-nothing-once-its-dot-is-gone-test
  (is (true? (bp/still-set? {:added? true :mark :m1} [:m1 :m2])))
  (is (false? (bp/still-set? {:added? true :mark :m1} [])) "a failed arm took the dots away")
  (is (true? (bp/still-set? {:added? false} [])) "a removal has no dot to keep"))

(deftest edited-normalizes-line-ends-and-caps-the-disk-read-test
  (let [t (lines "(defn f [x]" "  (inc x))")
        rng (bp/resend-range t 2)]
    (is (false? (bp/edited? t nil rng {:disk-text (str/replace t "\n" "\r\n")})) "CRLF on disk is the same form")
    (is (true? (bp/edited? t nil rng {:disk-text :too-big})) "a file too big to read counts as edited")))
