(ns jonquil.nrepl.ops-test
  "Reply shapes are the ones measured against live nREPL servers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.nrepl.ops :as ops]))

(deftest op-names-test
  (is (= #{"clone" "eval" "interrupt" "lookup"}
         (ops/op-names [{:id "1" :ops {"clone" {} "eval" {} "interrupt" {} "lookup" {}} :status ["done"]}]))))

(deftest candidates-test
  (testing "jolt + middleware: {candidate type}"
    (is (= [{:symbol "myproj-fn" :category :user}]
           (ops/candidates [{:completions [{"candidate" "myproj-fn" "type" "var"}] :status ["done"]}]))))
  (testing "vanilla jolt: {candidate ns}"
    (is (= [{:symbol "map" :category :builtin} {:symbol "zork" :category :user}]
           (ops/candidates [{:completions [{"candidate" "map" "ns" "clojure.core"} {"candidate" "zork" "ns" "user"}]}]))))
  (testing "jank: {candidate type ns}, keyword or string keys"
    (is (= [{:symbol "let*" :category :special} {:symbol "map" :category :builtin}]
           (ops/candidates [{:completions [{:candidate "let*" :type "special-form" :ns "clojure.core"}
                                           {"candidate" "map" "type" "function" "ns" "clojure.core"}]}]))))
  (is (= [] (ops/candidates [{:status ["done" "unknown-op"]}]))))

(deftest merge-candidates-test
  (let [offline [{:symbol "map" :category :builtin} {:symbol "mapv" :category :builtin}]
        server [{:symbol "map" :category :user} {:symbol "my-map" :category :user}]]
    (is (= [{:symbol "map" :category :builtin} {:symbol "mapv" :category :builtin} {:symbol "my-map" :category :user}]
           (ops/merge-candidates "ma" offline server))
        "offline wins on a shared name; a name not starting with the prefix sorts last"))
  (is (= [{:symbol "mz" :category :user} {:symbol "ma" :category :builtin}]
         (ops/merge-candidates "m" [{:symbol "ma" :category :builtin}] [{:symbol "mz" :category :user}])))
  (is (= [] (ops/merge-candidates "x" [] nil))))

(deftest doc-info-test
  (testing "jolt + middleware"
    (is (= {:status :found :symbol "myproj-fn" :ns "myproj.core" :name "myproj-fn" :arglists "([x])" :doc "Doubles x."}
           (ops/doc-info "myproj-fn" [{:info {"arglists-str" "([x])" "doc" "Doubles x." "name" "myproj-fn" "ns" "myproj.core"}
                                       :status ["done"]}]))))
  (testing "jank: flat fields, :arglists"
    (is (= {:status :found :symbol "map" :ns "user" :name "map" :arglists "([f] [f coll])" :doc "d"}
           (ops/doc-info "map" [{:ns "user" :name "map" :arglists "([f] [f coll])" :doc "d" :status ["done"]}]))))
  (testing "no info: unknown symbol, special form, vanilla server"
    (is (nil? (ops/doc-info "nope" [{:status ["done"]}])))
    (is (nil? (ops/doc-info "zork" [{:status ["done" "unknown-op"]}])))))

(deftest doc-info-file-line-test
  (let [r [{:info {"ns" "demo.core" "name" "greet" "doc" "Says hello." "arglists-str" "([who])"
                   "file" "/p/src/demo/core.clj" "line" 4}
            :status ["done"]}]]
    (is (= "/p/src/demo/core.clj" (:file (ops/doc-info "greet" r))))
    (is (= 4 (:line (ops/doc-info "greet" r)))))
  (is (nil? (ops/doc-info "nope" [{:info [] :status ["done"]}]))))

(deftest eval-result-test
  (is (= {:status :ok :printed "3" :ns 'user}
         (ops/eval-result [{:ns "user" :status ["done"] :value "3"}] false)))
  (is (= {:status :ok :printed "42" :ns 'user}
         (ops/eval-result [{:out "hi\n"} {:ns "user" :status ["done"] :value "42"}] false)))
  (is (= {:status :ok :printed "#'myproj.core/myproj-fn" :ns 'myproj.core}
         (ops/eval-result [{:ns "myproj.core" :status ["done"] :value "#'myproj.core/myproj-fn"}] false)))
  (is (= {:status :error :error-text "Error: Divide by zero"}
         (ops/eval-result [{:err "Divide by zero\n"} {:ex "Divide by zero" :status ["eval-error" "done"]}] false)))
  (testing "an eval-error with neither err nor ex still says something"
    (is (= {:status :error :error-text "Error: evaluation failed"}
           (ops/eval-result [{:status ["eval-error" "done"]}] false)))
    (is (= "Error: evaluation failed"
           (:error-text (ops/eval-result [{:err "  \n"} {:status ["eval-error" "done"]}] false)))
        "blank err text counts as none"))
  (testing "a jolt trace after the message is cut"
    (is (= "Error: EOF while reading"
           (:error-text (ops/eval-result [{:ex "EOF while reading\n  trace:\n    nrepl.middleware.caught/strip-config (x.clj:111)\n"
                                           :status ["eval-error" "done"]}] false)))))
  (testing "JVM nREPL's shape: the message is the line after err's header, not ex's class"
    (is (= "Error: Divide by zero"
           (:error-text (ops/eval-result [{:err "Execution error (ArithmeticException) at (REPL:1).\nDivide by zero\n"}
                                          {:ex "class java.lang.ArithmeticException"
                                           :root-ex "class java.lang.ArithmeticException" :status ["eval-error"]}
                                          {:status ["done"]}] false))))
    (is (= "Error: EOF while reading"
           (:error-text (ops/eval-result [{:err "Syntax error reading source at (REPL:1:17).\nEOF while reading\n"}
                                          {:ex "class java.lang.RuntimeException" :status ["eval-error"]}
                                          {:status ["done"]}] false))))
    (testing "the last header wins, so the program's own *err* output before it isn't taken"
      (is (= "Error: boom"
             (:error-text (ops/eval-result [{:err "warning: Execution error looks like this\n"}
                                            {:err "Execution error (ExceptionInfo) at user/f (REPL:3).\nfirst\n"}
                                            {:err "Execution error (ExceptionInfo) at (REPL:1).\nboom\n"}
                                            {:ex "class clojure.lang.ExceptionInfo" :status ["eval-error"]}
                                            {:status ["done"]}] false)))))
    (testing "every clojure.main err->msg phase"
      (doseq [[err want] [["Unexpected error (ClassCastException) compiling at (REPL:1:1).\ncast\n" "cast"]
                          ["Unexpected error (NullPointerException) macroexpanding m at (REPL:1:1).\nnil\n" "nil"]
                          ["Syntax error macroexpanding let at (REPL:1:1).\nbad binding" "bad binding"]
                          ["Syntax error compiling at (REPL:1:1).\nUnable to resolve symbol: x\n" "Unable to resolve symbol: x"]
                          ["Error printing return value (ArithmeticException) at clojure.lang.Numbers/divide (Numbers.java:190).\nDivide by zero\n" "Divide by zero"]
                          ["Error reading eval result (IOException) at x/y (x.clj:1).\nclosed\n" "closed"]
                          ["Execution error - invalid arguments to f at (REPL:1).\nspec text\n" "spec text"]]]
        (is (= (str "Error: " want)
               (:error-text (ops/eval-result [{:err err} {:ex "class X" :status ["eval-error" "done"]}] false)))
            err)))
    (testing "a header right after *err* output with no newline"
      (is (= "Error: Divide by zero"
             (:error-text (ops/eval-result [{:err "step 1..."}
                                            {:err "Execution error (ArithmeticException) at (REPL:1).\nDivide by zero\n"}
                                            {:ex "class java.lang.ArithmeticException" :status ["eval-error" "done"]}] false)))))
    (testing "a message that starts like a header is the message, not a header"
      (doseq [msg ["Unexpected error occurred." "Syntax error: bad token." "Execution error." "Error printing return value."
                   "Error reading eval result failed."]]
        (is (= (str "Error: " msg)
               (:error-text (ops/eval-result [{:err (str "Execution error (ExceptionInfo) at (REPL:1).\n" msg "\n")}
                                              {:ex "class clojure.lang.ExceptionInfo" :status ["eval-error" "done"]}] false)))
            msg)))
    (testing "CRLF line ends, from a Windows server"
      (is (= "Error: boom"
             (:error-text (ops/eval-result [{:err "Execution error (ExceptionInfo) at (REPL:1).\r\nboom\r\n"}
                                            {:ex "class X" :status ["eval-error" "done"]}] false)))))
    (testing "a blank message falls back to ex, not an earlier header's message"
      (is (= "Error: class clojure.lang.ExceptionInfo"
             (:error-text (ops/eval-result [{:err "Execution error (ExceptionInfo) at (REPL:1).\nolder\n"}
                                            {:err "Execution error (ExceptionInfo) at (REPL:2).\n\n"}
                                            {:ex "class clojure.lang.ExceptionInfo" :status ["eval-error" "done"]}] false)))))
    (testing "a message whose first line is blank shows its first real line"
      (is (= "Error: real text"
             (:error-text (ops/eval-result [{:err "Execution error (ExceptionInfo) at (REPL:1).\n\nreal text\n"}
                                            {:ex "class clojure.lang.ExceptionInfo" :status ["eval-error" "done"]}] false)))))
    (testing "with no header, ex is used as before"
      (is (= "Error: class java.lang.ArithmeticException"
             (:error-text (ops/eval-result [{:ex "class java.lang.ArithmeticException" :status ["eval-error" "done"]}] false))))))
  (is (= {:status :interrupted} (ops/eval-result [{:status ["interrupted" "done"]}] false)))
  (is (= {:status :error :error-text "The Jolt runtime exited before this evaluation finished."}
         (ops/eval-result [{:status ["done" "jonquil-disconnected"]}] false)))
  (testing "after Stop, a lost connection or an error reads as interrupted"
    (is (= {:status :interrupted} (ops/eval-result [{:status ["done" "jonquil-disconnected"]}] true)))
    (is (= {:status :interrupted} (ops/eval-result [{:ex "x" :status ["eval-error" "done"]}] true))))
  (is (= "nil" (:printed (ops/eval-result [{:status ["done"]}] false)))))

(deftest reset-code-test
  (let [code (ops/reset-code '[tabs.a tabs.b])
        form (read-string code)]
    (is (str/includes? code "'[tabs.a tabs.b]"))
    (is (str/includes? code "(clojure.core/remove-ns 'user) (clojure.core/create-ns 'user)"))
    (is (str/includes? code "(var clojure.core/*1)") "keeps the user's *1, *2 and *3")
    (is (seq? form)))
  (testing "an empty owner set still resets user"
    (is (str/includes? (ops/reset-code []) "(clojure.core/remove-ns 'user) (clojure.core/create-ns 'user)"))))

(deftest ns-snapshot-code-test
  ;; One form, printing the snapshot and returning the old *1; the live test history-vars-survive-owner-snapshots-
  ;; test checks the effect on a real server.
  (let [form (read-string ops/ns-snapshot-code)]
    (is (= 'clojure.core/let (first form)))
    (is (= 'o1 (last form)))
    (is (str/includes? ops/ns-snapshot-code "(clojure.core/all-ns)"))))

(deftest reads-stdin-test
  (is (ops/reads-stdin? "(println (read-line))"))
  (is (ops/reads-stdin? "(let [x (read)] x)"))
  (is (ops/reads-stdin? "(binding [*in* r] 1)"))
  (is (ops/reads-stdin? "(clojure.core/read-line)"))
  (is (not (ops/reads-stdin? "(read-string \"1\")")))
  (is (not (ops/reads-stdin? "; (read-line)\n\"read-line\" :read-line")))
  (is (not (ops/reads-stdin? nil))))

(deftest eval-result-lost-text-test
  (is (= "The Jolt runtime exited before this evaluation finished."
         (:error-text (ops/eval-result [{:status ["done" "jonquil-disconnected"]}] false))))
  (is (= "Gone." (:error-text (ops/eval-result [{:status ["done" "jonquil-disconnected"]}] false "Gone.")))))

(deftest nrepl-version-test
  (let [reply (fn [v] [{:versions {"nrepl" v} :status ["done"]}])]
    (is (= [1 8] (ops/nrepl-version (reply {"major" 1 "minor" 8 "incremental" 0 "version-string" "1.8.0"}))))
    (is (= [1 7] (ops/nrepl-version (reply {"major" 1 "minor" 7 "incremental" 0 "version-string" "1.7.0"}))))
    (is (= [2 0] (ops/nrepl-version (reply {"major" 2 "minor" 0}))))
    (is (nil? (ops/nrepl-version [{:status ["done"]}])) "no versions")
    (is (nil? (ops/nrepl-version [{:versions {"clojure" {"major" 1}} :status ["done"]}])) "no nrepl entry")
    (is (nil? (ops/nrepl-version (reply {"major" "1" "minor" "8"}))) "string values")
    (is (nil? (ops/nrepl-version (reply {"major" 1}))) "minor missing")
    (is (nil? (ops/nrepl-version :timeout)) "request-sync's non-vector result")))

(deftest error-line-without-a-header-test
  ;; babashka's eval-error (measured 2026-10-18): no clojure.main header,
  ;; and :ex holds only the class.
  (is (= "Error: java.lang.ArithmeticException: Divide by zero user REPL:1:1"
         (:error-text (ops/eval-result [{:err "java.lang.ArithmeticException: Divide by zero user REPL:1:1\n"}
                                        {:ex "class java.lang.ArithmeticException"
                                         :root-ex "class java.lang.ArithmeticException" :status ["eval-error"]}
                                        {:status ["done"]}] false))))
  (testing "earlier stderr that names the class isn't taken"
    (is (= "Error: clojure.lang.ExceptionInfo: boom user REPL:1:1"
           (:error-text (ops/eval-result [{:err "retrying after clojure.lang.ExceptionInfo\n"}
                                          {:err "clojure.lang.ExceptionInfo: boom user REPL:1:1\n"}
                                          {:ex "class clojure.lang.ExceptionInfo" :status ["eval-error"]}
                                          {:status ["done"]}] false)))))
  (testing "an :ex that is a message, not a class, is taken as is"
    (is (= "Error: EOF while reading"
           (:error-text (ops/eval-result [{:err "something about EOF while reading\n"}
                                          {:ex "EOF while reading" :status ["eval-error"]}
                                          {:status ["done"]}] false)))))
  (testing "the program's own stderr before the error isn't taken"
    (is (= "Error: clojure.lang.ExceptionInfo: boom user REPL:1:1"
           (:error-text (ops/eval-result [{:err "a warning first\n"}
                                          {:err "clojure.lang.ExceptionInfo: boom user REPL:1:1\n"}
                                          {:ex "class clojure.lang.ExceptionInfo" :status ["eval-error"]}
                                          {:status ["done"]}] false))))))

(def ^:private basilisp-traceback
  ;; Basilisp 0.5.1's runtime error ex (measured 2026-10-18), frames cut.
  (str "Traceback (most recent call last):\n"
       "  File \"/site-packages/basilisp/contrib/nrepl_server.lpy\", line 141, in do_handle_eval\n"
       "    (let [result (last\n"
       "                 ^^^^^\n"
       "  File \"/lib/python3.12/fractions.py\", line 281, in __new__\n"
       "    raise ZeroDivisionError('Fraction(%s, 0)' % numerator)\n"
       "ZeroDivisionError: Fraction(1, 0)\n"))

(deftest basilisp-error-line-test
  (testing "a runtime error: the traceback's last line, in ex and err"
    (is (= "Error: ZeroDivisionError: Fraction(1, 0)"
           (:error-text (ops/eval-result [{:err basilisp-traceback}
                                          {:ex basilisp-traceback :status ["eval-error"]}
                                          {:status ["done"]}] false)))))
  (testing "a compiler error: the message line of the block in err, not the traceback"
    (is (= "Error: unable to resolve symbol 'undefined-thing' in this context"
           (:error-text (ops/eval-result [{:err (str "\n  exception: <class 'basilisp.lang.compiler.exception.CompilerException'>\n"
                                                     "      phase: :analyzing\n"
                                                     "    message: unable to resolve symbol 'undefined-thing' in this context\n"
                                                     "       form: undefined-thing\n"
                                                     "   location: <nREPL Input>:1\n")}
                                          {:ex basilisp-traceback :status ["eval-error"]}
                                          {:status ["done"]}] false)))))
  (testing "a reader error"
    (is (= "Error: Unexpected EOF in list"
           (:error-text (ops/eval-result [{:err "\n  exception: <class 'basilisp.lang.reader.UnexpectedEOFError'>\n    message: Unexpected EOF in list\n       line: 1:4\n"}
                                          {:ex basilisp-traceback :status ["eval-error"]}
                                          {:status ["done"]}] false)))))
  (testing "a program's own message: line before a traceback isn't a compiler block"
    (is (= "Error: ZeroDivisionError: Fraction(1, 0)"
           (:error-text (ops/eval-result [{:err "message: hello\n"}
                                          {:ex basilisp-traceback :status ["eval-error"]}
                                          {:status ["done"]}] false)))))
  (testing "a program's own stderr shaped like a compiler block, then a runtime error"
    (is (= "Error: ZeroDivisionError: Fraction(1, 0)"
           (:error-text (ops/eval-result [{:err "  exception: <class 'X'>\n    message: hello\n"}
                                          {:err basilisp-traceback}
                                          {:ex basilisp-traceback :status ["eval-error"]}
                                          {:status ["done"]}] false)))))
  (testing "a message on more than one line: its first line, not its last"
    (let [tb (str "Traceback (most recent call last):\n"
                  "  File \"<nREPL Input>\", line 1, in <module>\n"
                  "basilisp.lang.exception.ExceptionInfo: boom {:a 1,\n"
                  " :b 2}\n")]
      (is (= "Error: basilisp.lang.exception.ExceptionInfo: boom {:a 1,"
             (:error-text (ops/eval-result [{:err tb} {:ex tb :status ["eval-error"]} {:status ["done"]}] false))))))
  (testing "a SyntaxError's caret lines come before its exception line"
    (let [tb (str "Traceback (most recent call last):\n"
                  "  File \"x.py\", line 1\n"
                  "    (+ 1\n"
                  "        ^\n"
                  "SyntaxError: invalid syntax\n")]
      (is (= "Error: SyntaxError: invalid syntax"
             (:error-text (ops/eval-result [{:ex tb :status ["eval-error"]} {:status ["done"]}] false)))))))

(deftest doc-info-blank-file-test
  (is (not (contains? (ops/doc-info "map" [{:name "map" :ns "clojure.core" :file "" :status ["done"]}]) :file))
      "babashka's empty :file is no file")
  (is (= "/x.clj" (:file (ops/doc-info "f" [{:name "f" :ns "u" :file "/x.clj" :line 3}])))))

(defn- run-quiet
  "quiet-code's output in this process, the root *1 *2 put back after (the
  wrap moves them, as it would a server's)."
  ([code token] (run-quiet code token nil))
  ([code token ns-name]
   (let [r1 *1 r2 *2]
     (try (with-out-str (load-string (ops/quiet-code code token ns-name)))
          (finally (alter-var-root #'*1 (constantly r1)) (alter-var-root #'*2 (constantly r2)))))))

(deftest quiet-code-test
  (is (= {:out "hi\n" :value "3"} (ops/quiet-result (run-quiet "(println \"hi\") (+ 1 2)" "TOK:") "TOK:")))
  (is (= {:out "" :error "boom"} (ops/quiet-result (run-quiet "(throw (ex-info \"boom\" {}))" "TOK:") "TOK:"))
      "a throw comes back as data, not out of the eval")
  (is (= {:out "" :error "outer"}
         (ops/quiet-result (run-quiet "(throw (ex-info \"outer\" {} (ex-info \"inner\" {})))" "TOK:") "TOK:"))
      "only load-string's own wrapper is unwrapped, not a cause the code gave its throw")
  (is (= {:out "" :error "First argument to def must be a Symbol"}
         (ops/quiet-result (run-quiet "(def 123)" "TOK:") "TOK:"))
      "a compile error keeps its message")
  (is (= {:out "" :value "1"} (ops/quiet-result (run-quiet "1 ; a trailing comment" "TOK:") "TOK:"))
      "a trailing comment doesn't swallow the wrap")
  (create-ns 'jonquil.quiet-ns-probe)
  (binding [*ns* (the-ns 'user)]
    (is (= "\"jonquil.quiet-ns-probe\"" (:value (ops/quiet-result (run-quiet "(str (ns-name *ns*))" "TOK:" "jonquil.quiet-ns-probe") "TOK:")))
        "it runs in the named namespace")
    (is (= "\"user\"" (:value (ops/quiet-result (run-quiet "(str (ns-name *ns*))" "TOK:" "no.such.ns") "TOK:")))
        "a namespace that doesn't exist falls back to the session's")
    (is (= 'user (ns-name *ns*)) "the caller's namespace didn't move"))
  (remove-ns 'jonquil.quiet-ns-probe))

(deftest quiet-result-test
  ;; a line that looks like the token line but isn't this call's stays :out
  (let [out (str "OTHER:{:value \"9\"}\n" "\nTOK:{:value \"3\"}")]
    (is (= {:out "OTHER:{:value \"9\"}\n" :value "3"} (ops/quiet-result out "TOK:"))))
  (is (= {:out "plain"} (ops/quiet-result "plain" "TOK:"))))

(deftest quiet-reply-test
  ;; the wrap returns the person's old *1, so an :ok reply whose
  ;; token line is missing must never hand that back as the value
  (is (= [{:status :ok :printed "42" :ns 'user} "side\n"]
         (ops/quiet-reply {:status :ok :printed "OLD-STAR-1" :ns 'user} "TOK:" "side\n\nTOK:{:value \"42\"}")))
  (is (= [{:status :error :error-text "Error: boom" :ns 'user} ""]
         (ops/quiet-reply {:status :ok :printed "OLD" :ns 'user} "TOK:" "\nTOK:{:error \"boom\"}")))
  (is (= [{:status :error :error-text "Error: the quiet eval's answer was lost" :ns 'user} "x"]
         (ops/quiet-reply {:status :ok :printed "OLD-STAR-1" :ns 'user} "TOK:" "x")))
  (is (= [{:status :interrupted} "partial"]
         (ops/quiet-reply {:status :interrupted} "TOK:" "partial"))
      "a Stop or a lost connection stands, its output passed on"))
