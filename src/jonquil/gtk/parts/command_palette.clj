(ns jonquil.gtk.parts.command-palette
  "The command palette, the console's ! line and the describe-keys commands
  as a window part (the commands are jonquil.parts.command-palette/spec).
  Moved out of jonquil.gtk.window: the palette's entries, the ! line's
  handler, and Describe Key's pending flag, the recent keys and the key
  lists that the window's key handler reads from its context."
  (:require [clojure.string :as str]
            [jonquil.describe :as describe]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.layouts :as layouts]
            [jonquil.palette :as pal-data]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.settings :as settings]))

(defn- palette-entries
  "The palette's entries for the live plugins' commands and keys."
  []
  (let [plugins @plugin-registry/live]
    (pal-data/entries {:commands (plugin-registry/all-commands plugins)
                       :keymap {:window (plugin-registry/window-bindings plugins)
                                :editor (plugin-registry/editor-bindings plugins)}
                       :os (System/getProperty "os.name")})))

(defn- open-palette!
  "The palette lists the registry's commands and opens on
  the active tab, whose palette runs the picked one"
  [{:keys [active-tab tabs tab-by-id recent-tabs right showing-panel settings-path*]}]
  (palette/open! (:palette (active-tab))
                 (into
                  (pal-data/tab-entries
                   (keep (fn [id] (when-let [t (tab-by-id tabs id)]
                                    {:id id :label (g/label-text (:label t))}))
                         @recent-tabs)
                   (:id (active-tab)))
                  (into (into (pal-data/panel-entries @(:recent-panels right)
                                                      (showing-panel right))
                              (layouts/entries (:layouts (settings/load-settings @settings-path*))))
                        (palette-entries)))))

(defn- run-bang!
  "The console's ! line: runs the palette command text names, or says what
  matches."
  [{:keys [cv ev* active-tab]} text]
  (let [es (palette-entries)
        say (fn [s] (cview/append! cv :system (str s "\n")))
        {:keys [run doc choices words none] :as r} (pal-data/bang-pick es text)]
    ;; not an entry: Clear the last entry's output reaches the one before
    (cview/restore-entry-start! cv)
    ;; Up brings the line back, as for any entry
    (swap! (ev/history @ev*) eval/history-add (str/trim text))
    (cond
      (:help r) (say "; ! runs a command by name, as the palette does: !clear line counts")
      none (say (str "; ! No command matches \"" none "\". Cmd/Ctrl+Shift+P lists them all."))
      choices (say (str "; ! " (count choices) " commands match \"" words "\":\n"
                        (str/join "\n" (map #(str ";   " %) (take 8 choices)))
                        (when (< 8 (count choices)) "\n;   ...")))
      :else (do (say (str "; ! " doc))
                (try ((deref (:on-run (:palette (active-tab)))) run)
                     (catch :default e (say (str "; ! failed: " (or (ex-message e) (str e))))))))))

(defn- live-keys
  "The live plugins' keymap and commands, as the describe fns read them."
  []
  (let [plugins @plugin-registry/live]
    {:keymap {:window (plugin-registry/window-bindings plugins)
              :editor (plugin-registry/editor-bindings plugins)}
     :commands (plugin-registry/all-commands plugins)}))

(defn- print-lines!
  [{:keys [cv]} lines]
  (doseq [l lines] (cview/append! cv :system (str l "\n"))))

(defn- describe-fns
  "The describe-keys fns over pending (Describe Key's flag) and recent-keys
  (jonquil.describe's ring), both read and written on the GTK thread only."
  [ctx pending recent-keys]
  (let [print! (partial print-lines! ctx)]
    {:describe-key-fn (fn [] (reset! pending true)
                        (print! ["; Press the key to describe."]))
     :key-bindings-fn (fn [] (print! (describe/binding-lines
                                      (assoc (live-keys) :os (System/getProperty "os.name")))))
     :recent-keys-fn (fn [] (print! (describe/recent-lines @recent-keys)))}))

(defn- install
  "The command palette part for one window: opens the palette, resets the
  console's ! line handler (wctx's :bang*) and answers the describe state and
  fns, which the window's key handler reads from its context. ui's
  :open-palette!* is the palette opener."
  [wctx]
  (let [pending (atom false)
        recent-keys (atom [])
        open-palette-fn (fn [] (open-palette! wctx))]
    (reset! (:bang* wctx) (fn [text] (run-bang! wctx text)))
    {:fns (merge {:open-palette-fn open-palette-fn
                  :describe-pending pending :recent-keys recent-keys
                  :live-keys live-keys :print-lines! (partial print-lines! wctx)}
                 (describe-fns wctx pending recent-keys))
     :ui {:open-palette!* open-palette-fn}}))

(def part
  "The command palette part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :command-palette}))
