(ns jonquil.breakpoints-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.breakpoints :as bp]
            ;; the inserted form names both
            [jolt.debug]
            [jonquil.debugger :as debugger]))

(defn- at
  "[snippet kind] of the spot a breakpoint on line attaches to, or nil
  when nothing on that line can pause."
  [text line]
  (when-let [s (first (filter #(= line (:line %)) (bp/spots text)))]
    [(subs text (:start s) (:end s)) (:kind s)]))

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
    (is (nil? (at t 5)) "an unknown with- macro's body is left alone")
    (let [t2 (lines "(do (->" "  (start)" "  (end)))")]
      (is (= ["(start)" :expression] (at t2 2)) "the threaded value itself is code")
      (is (nil? (at t2 3))))))

(deftest place-snaps-to-the-next-line-test
  (let [t (lines "(ns demo)"
                 ""
                 "(defn f [x]"
                 "  ;; note"
                 "  (inc x))")]
    (is (= {:line 5} (bp/place t 5)))
    (is (= {:line 5 :moved? true} (bp/place t 1)))
    (is (= {:line 5 :moved? true} (bp/place t 3)))
    (is (= {:line 5 :moved? true} (bp/place t 4)) "a comment line")
    (is (= {:error bp/NOWHERE} (bp/place t 6)))))

(deftest instrument-keeps-lines-and-values-test
  (let [t (lines "(defn f [x]"
                 "  (let [y (inc x)]"
                 "    (+ y"
                 "       (dec x))))")
        {out :text ls :lines} (bp/instrument t [2 4 1])]
    (is (= [2 4] ls) "line 1 holds none, so it's skipped")
    (is (= (count (str/split-lines t)) (count (str/split-lines out))) "no newline is added")
    (is (str/includes? out (str "  " (bp/breakpoint-form 2) " (let [y")))
    (is (str/includes? out (str "(do " (bp/breakpoint-form 4) " (dec x))")))))

(deftest instrument-a-range-test
  (let [t (lines "(defn a [] (one))" "(defn b []" "  (two))")
        r {:start (inc (str/index-of t "\n")) :end (count t)}
        {out :text ls :lines} (bp/instrument t [1 3] r)]
    (is (= [3] ls) "a breakpoint outside the range doesn't count")
    (is (str/starts-with? out "(defn b []"))
    (is (str/includes? out (str (bp/breakpoint-form 3) " (two)")))))

(deftest adjacent-wraps-nest-right-test
  (let [t "(f (a)\n(b))"
        {out :text} (bp/instrument t [1 2])]
    (is (= 1 (count (filter #{\newline} out))))
    (is (str/includes? out "(do ") "both wrapped")
    (is (= (count (re-seq #"\(" out)) (count (re-seq #"\)" out))) "brackets balance")))

(deftest instrumented-code-runs-test
  (let [t (lines "(fn [x]"
                 "  (let [y (inc x)]"
                 "    (+ y"
                 "       (dec x))))")
        {out :text} (bp/instrument t [2 3 4])]
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
  (eval (read-string (:text (bp/instrument text lines)))))

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
