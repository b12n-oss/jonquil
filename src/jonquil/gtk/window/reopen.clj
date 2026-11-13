(ns jonquil.gtk.window.reopen
  "The window's startup reopen: the entry sessions.edn holds for the tree's root (session-entry!, which also re-roots the tree when the launch named no folder), its tabs opened in order once the window has painted its first frame (reopen-later!), and restore-session!, the same reopen at once. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.last-session :as last-session]
            [jonquil.tabs :as tabs]))

;; --- last session's tabs -----------------------------------------------------
;; ctx is the window's tab map (tab-ctx), which carries :tree-pane,
;; :tree-saved! and :folder-result* (the atom holding Open Folder's result
;; handler, set once activate! has built it).

(defn session-entry!
  "The sessions.edn entry for the tree's root, or nil when :reopen-tabs? is
  off or the file can't be read (said on the console). With reroot? and
  no root, takes the most recent entry and roots the tree at its folder
  when that exists."
  [{:keys [cv tree-pane settings-path* folder-result*]} {:keys [reroot?]}]
  (when (win-session/reopen-tabs? @settings-path*)
    (let [{:keys [sessions warning]} (last-session/load-sessions (last-session/sessions-path @settings-path*))
          root (:root @(:state tree-pane))]
      (cond
        warning (do (cview/append! cv :system (str "; " warning "\n")) nil)
        (and reroot? (nil? root))
        (let [e (last-session/entry-for sessions nil)]
          (when (and (:root e) (.isDirectory (java.io.File. ^String (:root e))))
            (@folder-result* {:path (:root e)}))
          e)
        :else (last-session/entry-for sessions root)))))

(defn- reopen-failed?
  "Opens path as a tab; true when it's gone or couldn't be opened."
  [ctx path]
  (try (or (not (.isFile (java.io.File. ^String path))) (boolean (:error (win-tabs/open-file! ctx path))))
       (catch :default _ true)))

(defn- finish-reopen!
  "Selects the tab entry had active and names the missing files (their
  base names) on the console."
  [{:keys [cv tabs tree-saved!] :as ctx} entry missing]
  (when-let [t (and (:active entry)
                    (some (fn [t] (when (= (:active entry) @(:path t)) t)) @tabs))]
    (win-tabs/select-tab! ctx (:id t))
    (tree-saved! t))
  (when-let [line (last-session/missing-line missing)]
    (cview/append! cv :system line)))

(defn- reopen-entry!
  "Opens entry's tabs in order, then selects its active one and names the
  files that couldn't be opened."
  [ctx entry]
  (finish-reopen! ctx entry
                  (reduce (fn [acc p] (if (reopen-failed? ctx p) (conj acc (.getName (java.io.File. ^String p))) acc))
                          [] (:tabs entry))))

(defn- pristine?
  "True when the window holds one clean Untitled tab."
  [{:keys [tabs]}]
  (let [ts @tabs
        t (first ts)]
    (boolean (and (= 1 (count ts))
                  (tabs/untouched-untitled? {:path @(:path t) :text (editor/editor-text (:ed t))})))))

(declare restore-session!)

(defn folder-arrived!
  "Brings the tabs in step with a folder the tree was just re-rooted to
  from outside (last-session/arrival-step): the folder's own remembered tabs take the
  place of a reopen still pending, or open now in an unused window."
  [{:keys [session-restoring? session-pending] :as ctx}]
  (case (last-session/arrival-step {:restoring? @session-restoring? :pristine? (pristine? ctx)})
    :swap (reset! session-pending (session-entry! ctx {:reroot? false}))
    :reopen (restore-session! ctx {:reroot? false})
    :keep nil))

(defn restore-session!
  "Reopens the tabs sessions.edn holds for the tree's root, in order, and
  selects the one that was active (session-entry! picks the entry and does
  any re-root). Files that are gone (or can't be opened) are named on the
  console. Does nothing when :reopen-tabs? is off."
  [ctx opts]
  (when-let [entry (session-entry! ctx opts)]
    (reopen-entry! ctx entry))
  nil)

(def ^:private FIRST-FRAME-FALLBACK-MS
  "How long after-first-frame! waits for a frame before running anyway (a
  window that starts minimized may not paint)."
  1000)

(defn- after-first-frame!
  "Runs f on a later main-loop turn, once win's first frame has been
  painted, or after FIRST-FRAME-FALLBACK-MS when no frame comes. Call it
  after gtk_window_present, since the frame clock exists only once the
  window is realized."
  [win f]
  (let [done? (atom false)
        handler (atom nil)
        clock (g/gtk-widget-get-frame-clock win)
        once! (fn [] (when (compare-and-set! done? false true) (on-gui! f)))]
    (when-not (ffi/null? clock)
      ;; Disconnected only from inside its own emission, where the clock is
      ;; alive. After the timeout wins it stays connected and does nothing,
      ;; since the timeout may fire after the window, and its clock, is gone.
      (reset! handler (g/g-signal-connect-data clock "after-paint"
                                               (callable (fn [clk _data]
                                                           (when @handler (g/g-signal-handler-disconnect clk @handler))
                                                           (once!)
                                                           nil)
                                                         [:pointer :pointer] :void)
                                               ffi/null ffi/null g/CONNECT-DEFAULT)))
    (g/g-timeout-add FIRST-FRAME-FALLBACK-MS (callable (fn [_data] (once!) 0) [:pointer] :int :on-throw 0) ffi/null)))

(defn reopen-later!
  "Startup's reopen, once the window has painted its first frame: every
  tab in one main-loop turn, so nothing the user does can land between
  two of them. Then done! runs, even when the reopen threw (said on the
  console). Does nothing if :session-restoring? went false first, which a
  quit does."
  [{:keys [cv win session-restoring? session-pending] :as ctx} entry done!]
  (reset! session-pending entry)
  (reset! session-restoring? true)
  (after-first-frame!
   win
   (fn []
     (when @session-restoring?
       (reset! session-restoring? false)
       (try
         ;; the entry as it is now: a folder that arrived meanwhile
         ;; replaced it with its own (folder-arrived!)
         (let [e @session-pending]
           (reset! session-pending nil)
           (when (seq (:tabs e)) (reopen-entry! ctx e)))
         (catch :default e
           (cview/append! cv :error (str "; reopen tabs: " (ex-message e) "\n"))))
       (done!)))))
