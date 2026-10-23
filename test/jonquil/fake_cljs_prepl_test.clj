(ns jonquil.fake-cljs-prepl-test
  "The fake prepl answers the way ClojureScript 1.12.42's prepl was
  measured to: one ret per top-level form, :out lines first, and a wedged
  connection on a throw before (require 'cljs.repl)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [fake-cljs-prepl :as fake]))

(defn- connect [srv]
  (let [s (java.net.Socket. "127.0.0.1" (int (:port srv)))]
    {:sock s
     :in (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s) "UTF-8"))
     :out (java.io.OutputStreamWriter. (.getOutputStream s) "UTF-8")}))

(defn- send! [{:keys [^java.io.Writer out]} text]
  (.write out text) (.flush out))

(defn- recv
  "The next reply map, or ::none when nothing arrives within ms."
  [{:keys [^java.net.Socket sock ^java.io.BufferedReader in]} ms]
  (.setSoTimeout sock (int ms))
  (let [line (try (.readLine in)
                  (catch java.net.SocketTimeoutException _ ::none))]
    (if (= ::none line) ::none (edn/read-string line))))

(defn- with-fake [opts f]
  (let [srv (fake/start! opts)
        c (connect srv)]
    (try (f srv c)
         (finally (try (.close ^java.net.Socket (:sock c)) (catch Throwable _ nil))
                  (fake/stop! srv)))))

(deftest ret-per-form-test
  (with-fake {}
    (fn [_ c]
      (send! c "(+ 1 2)\n")
      (let [r (recv c 2000)]
        (is (= :ret (:tag r)))
        (is (= "3" (:val r)))
        (is (= "cljs.user" (:ns r)))
        (is (= "(+ 1 2)" (:form r)))
        (is (integer? (:ms r)))))))

(deftest out-then-rets-test
  (with-fake {}
    (fn [_ c]
      (send! c "(println \"hi\") :k\n")
      (let [msgs (vec (repeatedly 3 #(recv c 2000)))]
        (is (= {:tag :out :val "hi\n"} (first msgs)))
        (is (= [:ret "nil"] ((juxt :tag :val) (second msgs))))
        (is (= [:ret ":k"] ((juxt :tag :val) (nth msgs 2))))))))

(deftest sandbox-state-and-ns-test
  (with-fake {}
    (fn [srv c]
      (send! c "(require 'cljs.repl)\n(def a 1)\n(+ a 2)\n(in-ns 'demo.core)\n:x\n")
      (let [rs (vec (repeatedly 5 #(recv c 2000)))]
        (is (= "nil" (:val (first rs))))
        (is (= "3" (:val (nth rs 2))))
        (is (= ["demo.core" "demo.core"] [(:ns (nth rs 3)) (:ns (nth rs 4))])))
      (is (= ["(require 'cljs.repl)" "(def a 1)" "(+ a 2)" "(in-ns 'demo.core)" ":x"]
             (fake/received-forms srv))))))

(deftest exception-ret-test
  (with-fake {}
    (fn [_ c]
      (send! c "(throw (ex-info \"boom\" {}))\n:after\n")
      (let [r (recv c 2000)
            v (edn/read-string (:val r))]
        (is (true? (:exception r)))
        (is (= "boom" (:cause v)))
        (is (= "boom" (get-in v [:via 0 :message]))))
      (is (= ":after" (:val (recv c 2000)))))))

(deftest wedge-without-setup-test
  (with-fake {:wedge-on-throw-without-setup? true}
    (fn [_ c]
      (send! c "(throw (ex-info \"boom\" {}))\n")
      (is (= ::none (recv c 500)) "no reply to a throw before the setup form")))
  (with-fake {:wedge-on-throw-without-setup? true}
    (fn [_ c]
      (send! c "(require 'cljs.repl)\n")
      (is (= "nil" (:val (recv c 2000))))
      (send! c "(throw (ex-info \"boom\" {}))\n")
      (is (true? (:exception (recv c 2000))))
      (send! c ":still\n")
      (is (= ":still" (:val (recv c 2000)))))))

(deftest slow-ms-test
  (with-fake {:slow-ms 300}
    (fn [_ c]
      (send! c "(+ 1 1)\n")
      (is (= "2" (:val (recv c 100))) "forms without 'slow' answer at once")
      (send! c "(str \"slow\")\n")
      (is (= ::none (recv c 100)))
      (is (= "\"slow\"" (:val (recv c 2000)))))))

(deftest apropos-val-answers-without-eval-test
  (with-fake {:apropos-val "(a.b/c d.e/f)"}
    (fn [srv c]
      (send! c "(cljs.repl/apropos #\"\")\n")
      (let [r (recv c 2000)]
        (is (= "(a.b/c d.e/f)" (:val r)))
        (is (not (:exception r))))
      (is (some #(re-find #"apropos" %) (fake/received-forms srv)) "the form is recorded"))))

(deftest stdin-dance-test
  (with-fake {:stdin-dance? true :stdin-script ["abc"]}
    (fn [_ c]
      (send! c "(str \"got:\" (read-line))\n")
      (let [m1 (recv c 2000) m2 (recv c 2000) m3 (recv c 2000)]
        (is (= {:tag :err :val "\u0001jonquil:need-input\u0001\n"} m1))
        (is (= {:tag :err :val "\u0001jonquil:got-input\u0001\n"} m2))
        (is (= [:ret "\"got:abc\""] ((juxt :tag :val) m3)))))))
