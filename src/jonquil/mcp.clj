(ns jonquil.mcp
  "MCP protocol dispatch and the stdio loop.

  Transport-free: `serve!` takes its line reader and writer as functions, so
  this namespace never touches stdin or stdout itself. The tool table is
  `{:defs [tool-def ...] :call (fn [name args] {:text s :error? b})}`, or
  a no-argument fn answering one, read once per message, so
  the tools a window serves can change between calls. A table with
  :list-changed? true declares the capability, and serve!'s :subscribe
  is how the server learns when to send
  notifications/tools/list_changed."
  (:require [clojure.string :as str]
            [jonquil.mcp.json :as json]))

;; Oldest to newest. An unknown client version gets the last one.
(def ^:private supported-versions ["2024-11-05" "2025-03-26" "2025-06-18"])

(def ^:private server-info {"name" "jonquil" "version" "0.1.0"})

(defn- rpc-result [id result] {"jsonrpc" "2.0" "id" id "result" result})

(defn- rpc-error [id code message]
  {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}})

(defn- initialize [tools msg]
  (let [client-v (get-in msg ["params" "protocolVersion"])
        v (if (some #{client-v} supported-versions)
            client-v
            (last supported-versions))]
    (rpc-result (get msg "id")
                {"protocolVersion" v
                 "capabilities" {"tools" (if (:list-changed? tools) {"listChanged" true} {})}
                 "serverInfo" server-info})))

(defn- text-result [text error?]
  {"content" [{"type" "text" "text" text}] "isError" (boolean error?)})

(defn- tools-call [tools msg]
  (let [id (get msg "id")
        tname (get-in msg ["params" "name"])
        args (or (get-in msg ["params" "arguments"]) {})]
    (if-not (some #(= tname (get % "name")) (:defs tools))
      (do ;; the window's Messages log the miss too (:on-unknown, optional);
          ;; the reply is the same either way
        (when-let [f (:on-unknown tools)]
          (try (f tname args) (catch Throwable _ nil)))
        (rpc-error id -32602 (str "Unknown tool: " tname)))
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
  "{:msg decoded :resp response-or-nil} for one input line. Decode failure
  is -32700 with a null id; a throw inside dispatch is -32603 with the
  request's id, and no reply when the message had none."
  [tools line]
  (let [decoded (try {:msg (json/decode line)}
                     (catch Throwable _ nil))]
    (if-not decoded
      {:resp (rpc-error nil -32700 "Parse error")}
      (let [msg (:msg decoded)]
        {:msg msg
         :resp (try (handle-message tools msg)
                    (catch Throwable _
                      (when (and (map? msg) (contains? msg "id"))
                        (rpc-error (get msg "id") -32603 "Internal error"))))}))))

(defn- encode-response [resp]
  (try (json/encode resp)
       (catch Throwable _
         ;; the id may be what failed to encode (1e400 reads as ##Inf), so
         ;; the last resort carries a null id, which always encodes
         (try (json/encode (rpc-error (get resp "id") -32603 "Internal error"))
              (catch Throwable _
                (json/encode (rpc-error nil -32603 "Internal error")))))))

(def ^:private list-changed-line
  (json/encode {"jsonrpc" "2.0" "method" "notifications/tools/list_changed"}))

(defn- notifier
  "notify! for one session: safe from any thread, and returns
  at once. Nothing is sent until ready? holds (the client's
  notifications/initialized). The send runs on a future, so a swap on the
  GTK thread never waits on a slow client, and a burst makes one send in
  flight plus one queued: pending is cleared under the write lock, just
  before the write, so a change during a write sends once more after it,
  while a stuck write holds back every later one without a thread each. A
  failed write says one line on err; the serve loop finds the dead client
  on its own read."
  [lock write-line ready? err]
  (let [pending (atom false)]
    (fn []
      (when (and @ready? (compare-and-set! pending false true))
        (future
          (try (locking lock
                 (reset! pending false)
                 (write-line list-changed-line))
               (catch Throwable e
                 (binding [*out* err]
                   (println (str "jonquil: couldn't send tools/list_changed: " (or (ex-message e) (str e))))))))
        nil))))

(defn- handle-line!
  "One non-blank input line of the serve loop: decode, dispatch, run the
  :on-initialize hook, write the reply, and mark ready? on
  notifications/initialized."
  [{:keys [tools on-initialize write! ready?]} line]
  (let [{:keys [msg resp]} (respond tools line)]
    (when (and on-initialize (map? msg) (= "initialize" (get msg "method")))
      (try (on-initialize (get-in msg ["params" "clientInfo"]))
           (catch Throwable _ nil)))
    (when resp (write! (encode-response resp)))
    (when (and (map? msg) (= "notifications/initialized" (get msg "method")))
      (reset! ready? true))))

(defn serve!
  "Reads one JSON-RPC message per line via :read-line until it returns nil,
  writing each response with :write-line. Blank lines are skipped. A bad
  request never ends the loop. A :write-line failure (a broken pipe) is not
  caught: nobody is listening, so the loop ends with it.

  :subscribe (optional) is (fn [notify!] -> unsubscribe fn),
  called once before the loop; its unsubscribe runs when the loop ends,
  however it ends. notify! sends notifications/tools/list_changed (see
  notifier). Every write, response or notification, holds one lock, so
  lines never interleave.

  :on-initialize (optional) is (fn [client-info]), called with an
  initialize request's params.clientInfo (any value, nil when absent)
  before its response is written. A throw from it is ignored: it only
  names the client for the window's Messages panel."
  [{:keys [tools read-line write-line subscribe on-initialize]}]
  (let [lock (Object.)
        write! (fn [s] (locking lock (write-line s)))
        ready? (atom false)
        unsubscribe (when subscribe (subscribe (notifier lock write-line ready? *err*)))]
    (try
      (loop []
        (when-let [line (read-line)]
          (when-not (str/blank? line)
            (handle-line! {:tools tools :on-initialize on-initialize :write! write! :ready? ready?} line))
          (recur)))
      (finally
        (when (fn? unsubscribe) (unsubscribe))))))
