(ns jonquil.gtk.window.status
  "The window's status line: set-status! (safe from any thread), the Messages
  log each status label routes to, and contain-click, which reports a clicked
  handler's throw there. Internal to the window: its public vars are for
  jonquil.gtk.window and its sibling namespaces, not an API. Moved out of
  jonquil.gtk.window unchanged; the lock caller rule in its docstring applies
  here."
  (:require [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.ffi :as g]))

;; The Messages log (LM8) for each window, keyed by its status label: the
;; one handle every set-status! call already passes, so status lines, the
;; runtime's start and its connection reach the log without threading it
;; through sixty call sites. A pointer is a long, so it's a plain map key.
;; Kept for the process's life: the window is the process.
(defonce ^:private status-logs (atom {}))

(defn register-status-log!
  "Routes entries logged against status-label to log!."
  [status-label log!]
  (swap! status-logs assoc status-label log!)
  nil)

(defn log-at!
  "Logs entry to the Messages of the window whose status label this is,
  when it keeps them (a test's label may not). Safe from any thread."
  [status-label entry]
  (when-let [f (get @status-logs status-label)] (f entry))
  nil)

(defn set-status!
  "Safe from any thread: hands the label update to the GTK thread, and
  logs the text to the window's Messages as a status line."
  [status-label text]
  (log-at! status-label {:kind :status :summary text})
  (on-gui! (fn [] (g/gtk-label-set-text status-label text))))

(defn contain-click
  "A clicked/toggled handler (two pointers in, nothing out) that runs
  (f button) and puts a throw on the status label as \"; where: message\",
  the way the editor's and console's own handlers report theirs."
  [status-label where f]
  (fn [button _data]
    (try (f button)
         (catch :default e
           (set-status! status-label (str "; " where ": " (ex-message e)))))
    nil))
