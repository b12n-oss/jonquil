(ns jonquil.nrepl.ops-test
  "Reply shapes are the ones measured in the dialects spike and the plan's
  live probe (spikes/dialects-plan/probes)."
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

(deftest eval-result-test
  (is (= {:status :ok :printed "3" :ns 'user}
         (ops/eval-result [{:ns "user" :status ["done"] :value "3"}] false)))
  (is (= {:status :ok :printed "42" :ns 'user}
         (ops/eval-result [{:out "hi\n"} {:ns "user" :status ["done"] :value "42"}] false)))
  (is (= {:status :ok :printed "#'myproj.core/myproj-fn" :ns 'myproj.core}
         (ops/eval-result [{:ns "myproj.core" :status ["done"] :value "#'myproj.core/myproj-fn"}] false)))
  (is (= {:status :error :error-text "Error: Divide by zero"}
         (ops/eval-result [{:err "Divide by zero\n"} {:ex "Divide by zero" :status ["eval-error" "done"]}] false)))
  (testing "a jolt trace after the message is cut"
    (is (= "Error: EOF while reading"
           (:error-text (ops/eval-result [{:ex "EOF while reading\n  trace:\n    nrepl.middleware.caught/strip-config (x.clj:111)\n"
                                           :status ["eval-error" "done"]}] false)))))
  (is (= {:status :interrupted} (ops/eval-result [{:status ["interrupted" "done"]}] false)))
  (is (= {:status :error :error-text "The Jolt runtime exited before this evaluation finished."}
         (ops/eval-result [{:status ["done" "jonquil-disconnected"]}] false)))
  (testing "after Stop, a lost connection or an error reads as interrupted"
    (is (= {:status :interrupted} (ops/eval-result [{:status ["done" "jonquil-disconnected"]}] true)))
    (is (= {:status :interrupted} (ops/eval-result [{:ex "x" :status ["eval-error" "done"]}] true))))
  (is (= "nil" (:printed (ops/eval-result [{:status ["done"]}] false)))))

(deftest reset-code-test
  (let [code (ops/reset-code '[clojure.core user.x])]
    (is (str/starts-with? code "(let [keep (into (set '[clojure.core user.x]) (loaded-libs))]"))
    (is (str/includes? code "(remove-ns 'user) (create-ns 'user) nil)"))
    (is (= 'let (first (read-string code))))))

(deftest reads-stdin-test
  (is (ops/reads-stdin? "(println (read-line))"))
  (is (ops/reads-stdin? "(let [x (read)] x)"))
  (is (ops/reads-stdin? "(binding [*in* r] 1)"))
  (is (ops/reads-stdin? "(clojure.core/read-line)"))
  (is (not (ops/reads-stdin? "(read-string \"1\")")))
  (is (not (ops/reads-stdin? "; (read-line)\n\"read-line\" :read-line")))
  (is (not (ops/reads-stdin? nil))))
