;; Smoke of `jonquil --mcp` over real stdio. A babashka script (cheshire and
;; babashka.process are built in); it never touches the jolt process from the
;; inside. Run via `bb mcp:smoke` (a checkout, `jolt -M -m jonquil.main --mcp
;; DIR`) or `bb mcp:smoke --bin ./jonquil` (the built binary).
;;
;; Every response is read with a deadline, so a hang fails a check instead of
;; blocking. stderr is inherited, so a hang or a crash is diagnosable. Every
;; process this script starts is destroyed on every exit path.
(ns mcp-smoke
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private results (atom []))

(defn- check! [step ok detail]
  (swap! results conj (boolean ok))
  (println (str "[mcp-smoke] " step " " (if ok "PASS" "FAIL")
                (when-not ok (str " " (pr-str detail))))))

(defn- bin-arg []
  (let [args *command-line-args*
        i (.indexOf ^java.util.List (vec args) "--bin")]
    (when (>= i 0) (nth args (inc i)))))

(defn- command [dir]
  (if-let [bin (bin-arg)]
    [(str (fs/absolutize bin)) "--mcp" dir]
    ["jolt" "-M" "-m" "jonquil.main" "--mcp" dir]))

(defn- launch! [dir xdg]
  (let [proc (p/process (command dir)
                        {:err :inherit
                         :extra-env {"XDG_CONFIG_HOME" xdg}
                         :shutdown p/destroy-tree})
        q (LinkedBlockingQueue.)
        reader (io/reader (:out proc))]
    (future
      (try (loop []
             (when-let [l (.readLine ^java.io.BufferedReader reader)]
               (.put q l) (recur)))
           (catch Exception _ nil)
           (finally (.put q ::eof))))
    {:proc proc :q q :in (io/writer (:in proc)) :id (atom 0) :lines (atom [])}))

(defn- send! [{:keys [^java.io.Writer in]} msg]
  (.write in (str (json/generate-string msg) "\n"))
  (.flush in))

(defn- next-line
  "The next stdout line, or ::timeout / ::eof."
  [{:keys [^LinkedBlockingQueue q lines]} ms]
  (let [l (.poll q ms TimeUnit/MILLISECONDS)]
    (cond (nil? l) ::timeout
          (= l ::eof) ::eof
          :else (do (swap! lines conj l) l))))

(defn- request!
  "Sends a request and returns the parsed response, or {:fail why}."
  [srv method params ms]
  (let [id (swap! (:id srv) inc)]
    (send! srv {"jsonrpc" "2.0" "id" id "method" method "params" params})
    (let [l (next-line srv ms)]
      (if (string? l)
        (try (json/parse-string l)
             (catch Exception _ {:fail (str "not JSON: " l)}))
        {:fail l}))))

(defn- tool! [srv name args ms]
  (let [r (request! srv "tools/call" {"name" name "arguments" args} ms)]
    (if (:fail r)
      r
      {:text (get-in r ["result" "content" 0 "text"]) :error? (get-in r ["result" "isError"])})))

(defn- children [pid]
  (let [{:keys [out]} @(p/process ["pgrep" "-P" (str pid)] {:out :string :err :string})]
    (set (remove str/blank? (str/split-lines out)))))

(defn- handshake! [srv label]
  (let [r (request! srv "initialize" {"protocolVersion" "2025-06-18" "capabilities" {}
                                      "clientInfo" {"name" "mcp-smoke" "version" "0"}}
                    60000)]
    (check! (str label " initialize") (= "jonquil" (get-in r ["result" "serverInfo" "name"])) r)
    (send! srv {"jsonrpc" "2.0" "method" "notifications/initialized"})))

(defn- stop! [srv label]
  (.close ^java.io.Writer (:in srv))
  (let [r (deref (:proc srv) 20000 ::timeout)]
    (check! (str label " exits after stdin closes") (and (not= r ::timeout) (zero? (:exit r)))
            (if (= r ::timeout) "still running after 20 s" (:exit r)))
    (when (= r ::timeout) (p/destroy-tree (:proc srv)))))

(defn- main []
  (let [tmp (str (fs/create-temp-dir {:prefix "jonquil-mcp-smoke-"}))
        xdg (str tmp "/xdg")
        proj (str tmp "/proj")
        nrepl-proj (str tmp "/nrepl-proj")]
    (doseq [d [proj nrepl-proj]]
      (fs/create-dirs d)
      (spit (str d "/deps.edn") "{}")
      (spit (str d "/a.clj") "(ns a)\n(def x 1)\n"))
    (spit (str nrepl-proj "/.jonquil.edn") "{:runtime :nrepl}")
    (let [srv (launch! proj xdg)]
      (try
        (handshake! srv "in-process")
        (let [r (request! srv "tools/list" {} 20000)]
          (check! "tools/list has six tools" (= 6 (count (get-in r ["result" "tools"]))) r))
        (let [r (tool! srv "jonquil_eval" {"code" "(+ 1 2)"} 60000)]
          (check! "eval (+ 1 2) => 3" (and (string? (:text r)) (str/includes? (:text r) "=> 3")) r))
        (let [r1 (tool! srv "jonquil_eval" {"code" "(do (.start (Thread. (fn [] (println \"noise\")))) (Thread/sleep 200) :ok)"} 30000)
              r2 (tool! srv "jonquil_eval" {"code" "(+ 2 2)"} 30000)]
          (check! "background println does not reach stdout"
                  (and (not (:fail r1)) (string? (:text r2)) (str/includes? (:text r2) "=> 4")
                       (every? (fn [l] (try (json/parse-string l) true (catch Exception _ false)))
                               @(:lines srv)))
                  {:r1 r1 :r2 r2 :lines @(:lines srv)}))
        (let [r (tool! srv "jonquil_check_brackets" {"text" "(+ 1"} 20000)]
          (check! "check_brackets reports the unclosed form"
                  (and (string? (:text r)) (not (str/blank? (:text r)))) r))
        (stop! srv "in-process")
        (finally (p/destroy-tree (:proc srv))))
      ;; second project: a real Jolt child over nREPL
      (let [srv (launch! nrepl-proj xdg)
            pid (.pid ^java.lang.Process (:proc (:proc srv)))]
        (try
          (handshake! srv "nrepl")
          (let [r (tool! srv "jonquil_eval" {"code" "(+ 5 5)"} 60000)]
            (check! "nrepl eval (+ 5 5) => 10" (and (string? (:text r)) (str/includes? (:text r) "=> 10")) r))
          (let [kids (children pid)]
            (check! (str "nrepl run started a child (pid " (str/join "," kids) ")") (seq kids) kids)
            (stop! srv "nrepl")
            (Thread/sleep 500)
            (let [alive (filter (fn [k] (zero? (:exit @(p/process ["kill" "-0" k] {:err :string :out :string})))) kids)]
              (check! "no jolt child remains" (empty? alive) alive)))
          (finally (p/destroy-tree (:proc srv))))))
    (fs/delete-tree tmp)
    (let [fails (count (remove true? @results))]
      (println (str "[mcp-smoke] " (- (count @results) fails) "/" (count @results) " passed"))
      (System/exit (if (zero? fails) 0 1)))))

(main)
