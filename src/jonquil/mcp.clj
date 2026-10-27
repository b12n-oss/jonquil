(ns jonquil.mcp
  "MCP protocol dispatch and the stdio loop.

  Transport-free: `serve!` takes its line reader and writer as functions, so
  this namespace never touches stdin or stdout itself. The tool table is
  `{:defs [tool-def ...] :call (fn [name args] {:text s :error? b})}`, or
  (plugins P10b) a no-argument fn answering one, read once per message, so
  the tools a window serves can change between calls."
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
         (let [r ((:call tools) tname args)]
           (if (map? r)
             (text-result (str (:text r)) (:error? r))
             (text-result (str "Error: tool returned " (pr-str r)) true)))
         (catch Throwable e
           (text-result (str "Error: " (or (ex-message e) (str e))) true)))))))

(def ^:private handlers
  {"initialize" initialize
   "notifications/initialized" (fn [_ _] nil)
   "ping" (fn [_ msg] (rpc-result (get msg "id") {}))
   "tools/list" (fn [tools msg] (rpc-result (get msg "id") {"tools" (:defs tools)}))
   "tools/call" tools-call})

(defn- table-of [tools] (if (fn? tools) (tools) tools))

(defn handle-message
  "Decoded request -> response map, or nil when there is nothing to answer:
  a message without an \"id\" is a notification and never gets a reply. A
  non-map envelope (a batch array or a bare scalar) gets -32600 with a null
  id."
  [tools msg]
  (cond
    (not (map? msg)) (rpc-error nil -32600 "Invalid Request")
    (not (contains? msg "id")) nil
    :else (if-let [h (get handlers (get msg "method"))]
            (h (table-of tools) msg)
            (rpc-error (get msg "id") -32601 "Method not found"))))

(defn- respond
  "Response map for one input line, or nil. Decode failure is -32700 with a
  null id; a throw inside dispatch is -32603 with the request's id, and no
  reply when the message had none."
  [tools line]
  (let [decoded (try {:msg (json/decode line)}
                     (catch Throwable _ nil))]
    (if-not decoded
      (rpc-error nil -32700 "Parse error")
      (let [msg (:msg decoded)]
        (try (handle-message tools msg)
             (catch Throwable _
               (when (and (map? msg) (contains? msg "id"))
                 (rpc-error (get msg "id") -32603 "Internal error"))))))))

(defn- encode-response [resp]
  (try (json/encode resp)
       (catch Throwable _
         ;; the id may be what failed to encode (1e400 reads as ##Inf), so
         ;; the last resort carries a null id, which always encodes
         (try (json/encode (rpc-error (get resp "id") -32603 "Internal error"))
              (catch Throwable _
                (json/encode (rpc-error nil -32603 "Internal error")))))))

(defn serve!
  "Reads one JSON-RPC message per line via :read-line until it returns nil,
  writing each response with :write-line. Blank lines are skipped. A bad
  request never ends the loop. A :write-line failure (a broken pipe) is not
  caught: nobody is listening, so the loop ends with it."
  [{:keys [tools read-line write-line]}]
  (loop []
    (when-let [line (read-line)]
      (when-not (str/blank? line)
        (when-let [resp (respond tools line)]
          (write-line (encode-response resp))))
      (recur))))
