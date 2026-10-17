(ns jonquil.mcp
  "MCP protocol dispatch and the stdio loop.

  Transport-free: `serve!` takes its line reader and writer as functions, so
  this namespace never touches stdin or stdout itself. The tool table is
  `{:defs [tool-def ...] :call (fn [name args] {:text s :error? b})}`."
  (:require [clojure.string :as str]
            [jonquil.mcp.json :as json]))

;; Oldest to newest. An unknown client version gets the last one.
(def ^:private supported-versions ["2024-11-05" "2025-03-26" "2025-06-18"])

(def ^:private server-info {"name" "jonquil" "version" "0.1.0"})

(defn- rpc-result [id result] {"jsonrpc" "2.0" "id" id "result" result})

(defn- rpc-error [id code message]
  {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}})

(defn- initialize [_ msg]
  (let [client-v (get-in msg ["params" "protocolVersion"])
        v (if (some #{client-v} supported-versions)
            client-v
            (last supported-versions))]
    (rpc-result (get msg "id")
                {"protocolVersion" v
                 "capabilities" {"tools" {}}
                 "serverInfo" server-info})))

(defn- text-result [text error?]
  {"content" [{"type" "text" "text" text}] "isError" (boolean error?)})

(defn- tools-call [tools msg]
  (let [id (get msg "id")
        tname (get-in msg ["params" "name"])
        args (or (get-in msg ["params" "arguments"]) {})]
    (if-not (some #(= tname (get % "name")) (:defs tools))
      (rpc-error id -32602 (str "Unknown tool: " tname))
      (rpc-result
       id
       (try
         (let [{:keys [text error?]} ((:call tools) tname args)]
           (text-result text error?))
         (catch Throwable e
           (text-result (str "Error: " (ex-message e)) true)))))))

(def ^:private handlers
  {"initialize" initialize
   "notifications/initialized" (fn [_ _] nil)
   "ping" (fn [_ msg] (rpc-result (get msg "id") {}))
   "tools/list" (fn [tools msg] (rpc-result (get msg "id") {"tools" (:defs tools)}))
   "tools/call" tools-call})

(defn handle-message
  "Decoded request -> response map, or nil for a notification. A non-map
  envelope (a batch array or a bare scalar) gets -32600 with a null id."
  [tools msg]
  (if-not (map? msg)
    (rpc-error nil -32600 "Invalid Request")
    (if-let [h (get handlers (get msg "method"))]
      (h tools msg)
      (when (contains? msg "id")
        (rpc-error (get msg "id") -32601 "Method not found")))))

(def ^:private parse-error-response
  (rpc-error nil -32700 "Parse error"))

(defn serve!
  "Reads one JSON-RPC message per line via :read-line until it returns nil,
  writing each response with :write-line. Blank lines are skipped."
  [{:keys [tools read-line write-line]}]
  (loop []
    (when-let [line (read-line)]
      (when-not (str/blank? line)
        (let [resp (try (handle-message tools (json/decode line))
                        (catch Throwable _ parse-error-response))]
          (when resp (write-line (json/encode resp)))))
      (recur))))
