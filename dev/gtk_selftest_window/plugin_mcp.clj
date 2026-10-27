(ns gtk-selftest-window.plugin-mcp
  "Plugins P10's rows: the plugin MCP tools over the real window, called
  through the window tool table's :call off the GTK thread, the way the
  MCP server calls them. A propose runs the real isolated check in a jolt
  child and records into the selftest's own plugins folder (beside its
  temp settings file). Cleanup is gtk-selftest-window.review's
  with-proposals."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [go-row on-gtk]]
            [gtk-selftest-window.review :as review]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.review-pane :as review-pane]
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
