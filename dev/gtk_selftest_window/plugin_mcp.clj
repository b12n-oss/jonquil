(ns gtk-selftest-window.plugin-mcp
  "Plugins P10's rows: the plugin MCP tools over the real window, called
  through the window tool table's :call off the GTK thread, the way the
  MCP server calls them. A propose runs the real isolated check in a jolt
  child and records into the selftest's own plugins folder (beside its
  temp settings file). Cleanup is gtk-selftest-window.review's
  with-proposals."
  (:require [clojure.java.io :as io]
            [jonquil.mcp.json :as json]
            [clojure.string :as str]
            [gtk-async :refer [go-row on-gtk]]
            [gtk-selftest-window.review :as review]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.review-pane :as review-pane]
            [jonquil.mcp :as mcp]
            [jonquil.mcp.window :as mcp-window]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-loader :as plugin-loader]
            [jonquil.plugin-registry :as plugin-registry]))

(defn- call [ui tname args]
  ((:call (mcp-window/make-window-tools {:ui ui :dir (System/getProperty "java.io.tmpdir")})) tname args))

(defn- propose [ui id text]
  (call ui "jonquil_plugin_propose" {"id" id "source" text "reason" (str "an agent's " id)}))

(defn row-plugin-mcp-propose-pending
  "A good proposal over MCP is pending, counted, and listed."
  [ui]
  (go-row :plugin-mcp-propose-pending support/check
    (review/with-proposals ui {:ids ["mcpa"]}
      (fn []
        (let [r (propose ui "mcpa" (review/source "mcpa"))
              {:keys [count count-visible]} (review/panel ui)
              l (:text (call ui "jonquil_plugins_list" {}))]
          (support/check :plugin-mcp-propose-pending
                         (and (str/starts-with? (:text r) "status: pending") (not (:error? r))
                              (= "1 plugin proposal" count) count-visible
                              (some #(str/starts-with? % "mcpa: no live version, not loaded, effects unknown, pending v1.")
                                    (str/split-lines l)))
                         {:r r :count count :list l}))))))

(defn row-plugin-mcp-propose-rejected-by-checks
  "A proposal whose defplugin id isn't its file's is refused by its check:
  not counted, and its status says so."
  [ui]
  (go-row :plugin-mcp-propose-rejected-by-checks support/check
    (review/with-proposals ui {:ids ["mcpc"]}
      (fn []
        (let [before (:count (review/panel ui))
              r (propose ui "mcpc" (review/source "mcpb"))
              after (:count (review/panel ui))
              st (:text (call ui "jonquil_plugin_status" {"id" "mcpc"}))]
          (support/check :plugin-mcp-propose-rejected-by-checks
                         (and (str/starts-with? (:text r) "status: rejected-by-checks") (:error? r)
                              (str/includes? (:text r) "\nproblems:\n- ")
                              (= before after) (str/starts-with? st "mcpc v1: rejected-by-checks."))
                         {:r r :before before :after after :status st}))))))

(defn row-plugin-mcp-propose-refused-without-check
  "A bad id is refused before any check runs, and records nothing."
  [ui]
  (go-row :plugin-mcp-propose-refused-without-check support/check
    (let [checks (atom 0)
          real plugin-loader/propose-check
          r (with-redefs [plugin-loader/propose-check (fn [m] (swap! checks inc) (real m))]
              (propose ui "Bad" (review/source "bad")))
          folder (io/file (history/history-dir ((:plugins-dir* ui)) "Bad"))]
      (support/check :plugin-mcp-propose-refused-without-check
                     (and (str/starts-with? (:text r) "status: refused") (:error? r)
                          (zero? @checks) (not (.exists folder)))
                     {:r r :checks @checks}))))

(defn row-plugin-mcp-status-after-reject
  "A proposal the user rejects with a note: its status over MCP carries
  the note and the call that continues it."
  [ui]
  (go-row :plugin-mcp-status-after-reject support/check
    (review/with-proposals ui {:ids ["mcpr"]}
      (fn []
        (propose ui "mcpr" (review/source "mcpr"))
        ((:review-proposals!* ui))
        (on-gtk (review-pane/set-note! (:review-pane ui) "not now"))
        ((:review-reject!* ui))
        (let [st (:text (call ui "jonquil_plugin_status" {"id" "mcpr"}))]
          (support/check :plugin-mcp-status-after-reject
                         (str/ends-with? st "The user's note: not now Call jonquil_plugin_propose with a new version to try again.")
                         st))))))

(defn row-plugin-mcp-supersede-while-shown-needs-a-second-accept
  "Review I2: an agent's newer proposal replacing the one the user is
  reading makes the next Accept refuse and say so, so a click aimed at v1
  never accepts v2 unseen; the Accept after that takes v2."
  [ui]
  (go-row :plugin-mcp-supersede-while-shown-needs-a-second-accept support/check
    (review/with-proposals ui {:ids ["mcps"]}
      (fn []
        (propose ui "mcps" (review/source "mcps"))
        ((:review-proposals!* ui))
        (propose ui "mcps" (review/source "mcps" "Wrap it, v2."))
        (let [shown ((:review-shown* ui))
              first-lines ((:review-accept!* ui))
              {:keys [footer]} (review/panel ui)
              live-after-first? (some? (get-in @plugin-registry/live [:plugins :mcps]))
              second-lines ((:review-accept!* ui))
              live? (some? (get-in @plugin-registry/live [:plugins :mcps]))]
          (support/check :plugin-mcp-supersede-while-shown-needs-a-second-accept
                         (and (= 2 (:n shown)) (empty? first-lines) (not live-after-first?)
                              (= "plugin mcps: v2 replaced v1 while it was shown, so it wasn't accepted. Read v2, then press Accept again."
                                 footer)
                              (= "plugin mcps: v2 accepted" (last second-lines)) live?)
                         {:shown shown :first first-lines :footer footer :second second-lines}))))))

;; --- a plugin's own MCP tools (plugins P10b) ---------------------------------

(def ^:private tools-source
  (str "(ns jonquil.plugins.mcpt\n  (:require [jonquil.plugin :as p]))\n\n"
       "(p/defplugin\n  {:id :mcpt :doc \"MCP tools.\" :effects #{:console :buffer}\n"
       "   :mcp-tools [{:name \"echo\" :doc \"Echo it.\"\n"
       "                :args {\"text\" {:type \"string\" :doc \"What to echo.\" :required? true}}\n"
       "                :run (fn [ctx a] (p/print! ctx (str \"mcpt says \" (get a \"text\"))) (get a \"text\"))}\n"
       "               {:name \"buf\" :doc \"The active tab's text.\" :run (fn [ctx _] (p/buffer-text ctx))}]})\n"))

(def ^:private no-tools-source
  (str "(ns jonquil.plugins.mcpt\n  (:require [jonquil.plugin :as p]))\n\n"
       "(p/defplugin\n  {:id :mcpt :doc \"MCP tools.\" :effects #{}})\n"))

(defn- table [ui]
  ((mcp-window/with-plugin-tools (mcp-window/make-window-tools {:ui ui :dir (System/getProperty "java.io.tmpdir")}) ui)))

(defn row-plugin-mcp-tool-listed-and-called
  "A loaded plugin's tool is served beside the built-ins and runs with its
  declared :console effect."
  [ui]
  (go-row :plugin-mcp-tool-listed-and-called support/check
    (review/with-proposals ui {:files {"mcpt.clj" tools-source} :ids ["mcpt"]}
      (fn []
        (let [t (table ui)
              names (mapv #(get % "name") (:defs t))
              r ((:call t) "plugin_mcpt_echo" {"text" "hi"})
              console (on-gtk (g/buffer-text (:buf (:console ui))))]
          (support/check :plugin-mcp-tool-listed-and-called
                         (and (= ["plugin_mcpt_echo" "plugin_mcpt_buf"] (subvec names 19))
                              (= {:text "hi" :error? false} r)
                              (str/includes? console "mcpt says hi"))
                         {:names (subvec names (min 19 (count names))) :r r}))))))

(defn row-plugin-mcp-tool-gone-after-reload
  "A reload that drops the tools drops them from the next table, and a
  call to one is the server's Unknown tool error."
  [ui]
  (go-row :plugin-mcp-tool-gone-after-reload support/check
    (review/with-proposals ui {:files {"mcpt.clj" tools-source} :ids ["mcpt"]}
      (fn []
        (let [before (count (:defs (table ui)))
              _ (spit (io/file ((:plugins-dir* ui)) "mcpt.clj") no-tools-source)
              _ ((:reload-plugins!* ui))
              tf (mcp-window/with-plugin-tools (mcp-window/make-window-tools {:ui ui :dir "/tmp"}) ui)
              after (count (:defs (tf)))
              resp (mcp/handle-message tf {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                                           "params" {"name" "plugin_mcpt_echo" "arguments" {"text" "x"}}})]
          (support/check :plugin-mcp-tool-gone-after-reload
                         (and (= 21 before) (= 19 after)
                              (= "Unknown tool: plugin_mcpt_echo" (get-in resp ["error" "message"])))
                         {:before before :after after :resp resp}))))))

(defn row-plugin-mcp-tool-reads-the-buffer
  "A :buffer tool called from the MCP thread reads the active tab, taken
  through a GTK turn."
  [ui]
  (go-row :plugin-mcp-tool-reads-the-buffer support/check
    (review/with-proposals ui {:files {"mcpt.clj" tools-source} :ids ["mcpt"]}
      (fn []
        (let [want (on-gtk (editor/editor-text (:ed ((:active-tab* ui)))))
              r ((:call (table ui)) "plugin_mcpt_buf" {})]
          (support/check :plugin-mcp-tool-reads-the-buffer
                         (= {:text want :error? false} r)
                         {:want want :r r}))))))

(defn- serve-queues
  "mcp/serve! on a future over a queue, as the stdio session is served:
  the window table with plugin tools and :subscribe from the ui."
  [ui]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        out (atom [])
        done (future
               (mcp/serve! {:tools (mcp-window/with-plugin-tools
                                    (mcp-window/make-window-tools {:ui ui :dir (System/getProperty "java.io.tmpdir")}) ui)
                            :subscribe (:watch-plugin-tools* ui)
                            :read-line (fn [] (let [l (.take q)] (when-not (= l ::eof) l)))
                            :write-line (fn [s] (swap! out conj (json/decode s)))}))]
    {:send! (fn [m] (.put q (json/encode m))) :eof! (fn [] (.put q ::eof)) :out out :done done}))

(defn- wait-for [f ms]
  (let [t0 (System/currentTimeMillis)]
    (loop [] (or (f) (when (< (- (System/currentTimeMillis) t0) ms) (Thread/sleep 20) (recur))))))

(def ^:private list-changed {"jsonrpc" "2.0" "method" "notifications/tools/list_changed"})

(defn row-plugin-mcp-list-changed-after-reload
  "Plugins P10c: a reload that adds a plugin's tools sends list_changed to
  a session that's initialized, and the next tools/list has them; a reload
  that changes nothing sends none; the session ends on EOF."
  [ui]
  (go-row :plugin-mcp-list-changed-after-reload support/check
    (review/with-proposals ui {:files {"mcpt.clj" no-tools-source} :ids ["mcpt"]}
      (fn []
        (let [{:keys [send! eof! out done]} (serve-queues ui)
              notes #(count (filter #{list-changed} @out))
              _ (send! {"jsonrpc" "2.0" "id" 1 "method" "initialize" "params" {"protocolVersion" "2025-06-18"}})
              _ (send! {"jsonrpc" "2.0" "method" "notifications/initialized"})
              _ (send! {"jsonrpc" "2.0" "id" 2 "method" "ping"})
              _ (wait-for #(= 2 (count @out)) 5000)
              cap (get-in (first @out) ["result" "capabilities" "tools"])
              _ (spit (io/file ((:plugins-dir* ui)) "mcpt.clj") tools-source)
              _ ((:reload-plugins!* ui))
              first-note? (wait-for #(= 1 (notes)) 5000)
              _ (send! {"jsonrpc" "2.0" "id" 3 "method" "tools/list"})
              listed (wait-for #(some (fn [m] (when (= 3 (get m "id")) m)) @out) 5000)
              names (set (map #(get % "name") (get-in listed ["result" "tools"])))
              _ ((:reload-plugins!* ui))
              _ (Thread/sleep 1000)
              after (notes)
              _ (eof!)
              ended (deref done 5000 ::timeout)]
          (support/check :plugin-mcp-list-changed-after-reload
                         (and (= {"listChanged" true} cap) first-note?
                              (contains? names "plugin_mcpt_echo") (= 1 after) (not= ::timeout ended))
                         {:cap cap :first first-note? :names (count names) :after after :ended ended}))))))
