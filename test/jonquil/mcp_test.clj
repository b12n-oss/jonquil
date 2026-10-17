(ns jonquil.mcp-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.mcp :as mcp]
            [jonquil.mcp.json :as json]))

(def ^:private stub-defs
  [{"name" "echo" "description" "echo s" "inputSchema" {"type" "object"}}
   {"name" "boom" "description" "throws" "inputSchema" {"type" "object"}}
   {"name" "nope" "description" "error result" "inputSchema" {"type" "object"}}])

(def ^:private tools
  {:defs stub-defs
   :call (fn [tname args]
           (case tname
             "echo" {:text (get args "s") :error? false}
             "boom" (throw (ex-info "bad" {}))
             "nope" {:text "nope" :error? true}))})

(defn- req [id method params]
  {"jsonrpc" "2.0" "id" id "method" method "params" params})

(deftest initialize-echoes-supported-version
  (let [r (mcp/handle-message tools (req 1 "initialize" {"protocolVersion" "2025-03-26"}))]
    (is (= 1 (get r "id")))
    (is (= {"protocolVersion" "2025-03-26"
            "capabilities" {"tools" {}}
            "serverInfo" {"name" "jonquil" "version" "0.1.0"}}
           (get r "result")))))

(deftest initialize-unknown-version-gets-newest
  (is (= "2025-06-18"
         (get-in (mcp/handle-message tools (req 1 "initialize" {"protocolVersion" "1999-01-01"}))
                 ["result" "protocolVersion"]))))

(deftest initialized-notification-has-no-reply
  (is (nil? (mcp/handle-message tools {"jsonrpc" "2.0" "method" "notifications/initialized"}))))

(deftest ping
  (is (= {} (get (mcp/handle-message tools (req 2 "ping" nil)) "result"))))

(deftest tools-list
  (is (= {"tools" stub-defs}
         (get (mcp/handle-message tools (req 3 "tools/list" nil)) "result"))))

(deftest tools-call-text
  (is (= {"content" [{"type" "text" "text" "hi"}] "isError" false}
         (get (mcp/handle-message tools (req 4 "tools/call" {"name" "echo" "arguments" {"s" "hi"}}))
              "result"))))

(deftest tools-call-error-result
  (let [r (get (mcp/handle-message tools (req 5 "tools/call" {"name" "nope" "arguments" {}})) "result")]
    (is (true? (get r "isError")))
    (is (= "nope" (get-in r ["content" 0 "text"])))))

(deftest tools-call-throw
  (let [r (get (mcp/handle-message tools (req 6 "tools/call" {"name" "boom" "arguments" {}})) "result")]
    (is (true? (get r "isError")))
    (is (= "Error: bad" (get-in r ["content" 0 "text"])))))

(deftest tools-call-unknown-tool
  (let [r (mcp/handle-message tools (req 7 "tools/call" {"name" "zap" "arguments" {}}))]
    (is (= 7 (get r "id")))
    (is (= -32602 (get-in r ["error" "code"])))
    (is (re-find #"zap" (get-in r ["error" "message"])))))

(deftest unknown-method
  (is (= -32601 (get-in (mcp/handle-message tools (req 8 "frob" nil)) ["error" "code"])))
  (is (nil? (mcp/handle-message tools {"jsonrpc" "2.0" "method" "frob"}))))

(deftest batch-refused
  (doseq [m [[(req 1 "ping" nil)] "ping"]]
    (let [r (mcp/handle-message tools m)]
      (is (= -32600 (get-in r ["error" "code"])))
      (is (contains? r "id"))
      (is (nil? (get r "id"))))))

(deftest serve-loop
  (let [lines (atom [(json/encode (req 1 "initialize" {"protocolVersion" "2025-06-18"}))
                     ""
                     "{bad"
                     (json/encode (req 2 "tools/call" {"name" "echo" "arguments" {"s" "yo"}}))])
        out (atom [])]
    (mcp/serve! {:tools tools
                 :read-line (fn [] (let [l (first @lines)] (swap! lines (comp vec rest)) l))
                 :write-line (fn [s] (swap! out conj s))})
    (is (= 3 (count @out)))
    (let [rs (mapv json/decode @out)]
      (is (= 1 (get (rs 0) "id")))
      (is (= -32700 (get-in rs [1 "error" "code"])))
      (is (contains? (rs 1) "id"))
      (is (nil? (get (rs 1) "id")))
      (is (= "yo" (get-in rs [2 "result" "content" 0 "text"]))))))

(defn- serve-lines [tools lines]
  (let [in (atom (vec lines))
        out (atom [])]
    (mcp/serve! {:tools tools
                 :read-line (fn [] (let [l (first @in)] (swap! in (comp vec rest)) l))
                 :write-line (fn [s] (swap! out conj s))})
    (mapv json/decode @out)))

(def ^:private throwing-defs-tools
  {:defs (map (fn [_] (throw (ex-info "defs broke" {}))) [1])
   :call (fn [_ _] nil)})

(deftest handler-throw-answers-internal-error-and-loop-continues
  (let [rs (serve-lines throwing-defs-tools
                        [(json/encode (req 7 "tools/call" {"name" "echo"}))
                         (json/encode (req 8 "ping" nil))])]
    (is (= 2 (count rs)))
    (is (= 7 (get (rs 0) "id")))
    (is (= -32603 (get-in rs [0 "error" "code"])))
    (is (= {} (get-in rs [1 "result"])))))

(deftest handler-throw-on-notification-is-silent
  (is (= [] (serve-lines throwing-defs-tools
                         [(json/encode {"jsonrpc" "2.0" "method" "tools/call"
                                        "params" {"name" "x"}})]))))

(deftest unencodable-response-does-not-end-loop
  (let [bad {:defs [{"name" "bad" "x" (fn [] 1)}] :call (fn [_ _] nil)}
        rs (serve-lines bad [(json/encode (req 3 "tools/list" nil))
                             (json/encode (req 4 "ping" nil))])]
    (is (= 2 (count rs)))
    (is (= 3 (get (rs 0) "id")))
    (is (= -32603 (get-in rs [0 "error" "code"])))
    (is (= 4 (get (rs 1) "id")))))

(deftest notifications-to-known-methods-get-no-reply
  (doseq [m ["ping" "tools/list" "tools/call" "initialize"]]
    (is (nil? (mcp/handle-message tools {"jsonrpc" "2.0" "method" m
                                         "params" {"name" "echo"}})))))

(deftest id-zero-is-a-request
  (is (= 0 (get (mcp/handle-message tools (req 0 "ping" nil)) "id"))))

(deftest nil-returning-tool-is-an-error
  (let [t {:defs stub-defs :call (fn [_ _] nil)}
        r (get (mcp/handle-message t (req 1 "tools/call" {"name" "echo"})) "result")]
    (is (true? (get r "isError")))
    (is (string? (get-in r ["content" 0 "text"])))))

(deftest nil-text-becomes-a-string
  (let [t {:defs stub-defs :call (fn [_ _] {:text nil :error? false})}
        r (get (mcp/handle-message t (req 1 "tools/call" {"name" "echo"})) "result")]
    (is (= "" (get-in r ["content" 0 "text"])))))

(deftest throw-without-message
  (let [t {:defs stub-defs :call (fn [_ _] (throw (ex-info nil {})))}
        r (get (mcp/handle-message t (req 1 "tools/call" {"name" "echo"})) "result")]
    (is (true? (get r "isError")))
    (is (not= "Error: " (get-in r ["content" 0 "text"])))))

(deftest unencodable-id-does-not-end-the-loop
  (let [rs (serve-lines throwing-defs-tools
                        ["{\"jsonrpc\":\"2.0\",\"id\":1e400,\"method\":\"ping\"}"
                         (json/encode (req 9 "ping" nil))])]
    (is (= 2 (count rs)))
    (is (contains? #{-32603 -32600} (get-in rs [0 "error" "code"])))
    (is (nil? (get (rs 0) "id")))
    (is (= 9 (get (rs 1) "id")))
    (is (= {} (get-in rs [1 "result"])))))
