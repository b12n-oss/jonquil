(ns jonquil.gtk.parts.terminal
  "The terminal as a window part (the command and its key are
  jonquil.parts.terminal/spec). The first time it is shown, the console's
  place below the editor becomes a notebook of two tabs, Interactions and
  Terminal, and a login shell starts in the active file's project folder.
  Until then the window is as it was.

  The terminal is Anchan's (anchan.core), on its VTE backend. Where VTE
  can't be used the Terminal tab says why: a release Jonquil.app carries
  its own GTK and may load only its own libraries, so it never tries the
  system's VTE."
  (:require [anchan.core :as anchan]
            [anchan.vte :as vte]
            [jolt.ffi :as ffi]
            [jolt.process :as p]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.terminal-keys :as terminal-keys]
            [jonquil.terminal-open :as terminal-open]
            [jonquil.theme :as theme]))

(def CONSOLE-PAGE 0)
(def TERMINAL-PAGE 1)

(defn- os [] (System/getProperty "os.name"))

(defn unavailable
  "Why this Jonquil has no terminal, as a sentence, or nil when it has
  one. bundle? is true in a release Jonquil.app."
  [bundle? backend-problem]
  (cond
    bundle? (str "This download of Jonquil doesn't include the terminal yet. "
                 "\"Open a terminal in the project folder\", in the Tools menu, starts your system's terminal.")
    backend-problem backend-problem))

(defn- colors-for
  "The terminal's colours for the theme named k: the editor's text on its
  background."
  [k]
  (let [{:keys [text background]} (theme/theme k)]
    {:foreground text :background background}))

(defn- apply-theme! [{:keys [theme-name]} term]
  (when (and term theme-name)
    (anchan/set-colors! term (colors-for @theme-name))))

(defn- message-widget
  "A label saying text, for the Terminal tab of a Jonquil with no terminal."
  [text]
  (let [label (g/gtk-label-new text)]
    (g/gtk-label-set-wrap label 1)
    (g/gtk-label-set-selectable label 1)
    (g/gtk-label-set-xalign label 0.0)
    (g/gtk-widget-set-valign label g/ALIGN-START)
    (g/gtk-widget-set-margin-start label 12)
    (g/gtk-widget-set-margin-end label 12)
    (g/gtk-widget-set-margin-top label 10)
    label))

(defn- connect-clipboard-keys!
  "Copy and paste on the terminal's own widget: the window passes these
  keys on (jonquil.terminal-keys), and VTE binds neither."
  [term]
  (let [ctl (g/gtk-event-controller-key-new)]
    (g/gtk-event-controller-set-propagation-phase ctl g/GTK-PHASE-CAPTURE)
    (g/g-signal-connect-data
     ctl "key-pressed"
     (callable (fn [_ctl keyval _keycode state _data]
                 (case (terminal-keys/clipboard-action {:keyval (g/gdk-keyval-to-lower keyval) :state state} (os))
                   :copy (do (anchan/copy! term) 1)
                   :paste (do (anchan/paste! term) 1)
                   0))
               [:pointer :uint :uint :uint :pointer] :int :on-throw 0)
     ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller (anchan/widget term) ctl)))

(defn- build!
  "Turns the console's place into a notebook of Interactions and Terminal,
  once. Answers the part's state: {:notebook :term (nil without one)
  :widget (the Terminal tab's child)}."
  [{:keys [paned cv] :as ctx}]
  (let [why (unavailable (some? (System/getenv "JONQUIL_BUNDLE_FRAMEWORKS"))
                         (anchan/problem vte/backend))
        term (when-not why (anchan/terminal vte/backend))
        widget (if term (anchan/widget term) (message-widget why))
        scroll (:scroll cv)
        nb (g/gtk-notebook-new)]
    ;; the console's scrolled window moves from the paned into the
    ;; notebook: a reference keeps it alive while it has no parent
    (g/g-object-ref scroll)
    (g/gtk-paned-set-end-child paned ffi/null)
    (g/gtk-notebook-set-show-border nb 0)
    (g/gtk-notebook-append-page nb scroll (g/gtk-label-new "Interactions"))
    (g/gtk-notebook-append-page nb widget (g/gtk-label-new "Terminal"))
    (g/gtk-paned-set-end-child paned nb)
    (g/g-object-unref scroll)
    (when term
      (apply-theme! ctx term)
      (connect-clipboard-keys! term))
    {:notebook nb :term term :widget widget :why why}))

(defn- start-shell!
  "Starts a login shell in the active file's project folder, unless one is
  running."
  [{:keys [active-tab set-status!]} term]
  (when-not (anchan/running? term)
    (let [shell (or (System/getenv "SHELL") "/bin/sh")
          dir (terminal-open/folder (some-> (:path (active-tab)) deref) (System/getProperty "user.home"))]
      (anchan/start! term {:argv [shell "-l"] :dir dir
                           :on-start (fn [{:keys [error]}]
                                       (when error (set-status! (str "; The terminal couldn't start " shell ": " error))))
                           :on-exit (fn [_]
                                      (set-status! "; The terminal's shell ended. Show the terminal starts a new one."))}))))

(defn- has-children?
  "True when the process pid has a child process: pgrep -P answers 0."
  [pid]
  (try (zero? (:exit (p/sh "pgrep" "-P" (str pid))))
       (catch Exception _ false)))

(defn- running-something?
  "True when closing the terminal would end something: its shell is
  running a command in the foreground (anchan/busy?), or has any child
  process, which is what a command started with & leaves behind."
  [term]
  (or (anchan/busy? term)
      (boolean (when-let [pid (anchan/pid term)] (has-children? pid)))))

(defn- focused? [{:keys [win]} state]
  (boolean (when-let [{:keys [term widget]} @state]
             (and term (= (g/gtk-root-get-focus win) widget)))))

(defn- showing? [state]
  (boolean (when-let [{:keys [notebook]} @state]
             (= TERMINAL-PAGE (g/gtk-notebook-get-current-page notebook)))))

(defn- focus-terminal!
  "Focuses the terminal when its tab is the one showing: true when it did."
  [state]
  (boolean (when (showing? state)
             (when-let [{:keys [term widget]} @state]
               (when term (g/gtk-widget-grab-focus widget) true)))))

(defn- show!
  "Show the terminal: builds the tabs the first time, shows the Terminal
  tab, starts the shell and gives it the focus. From the terminal itself it
  goes back to the Interactions tab and the editor."
  [{:keys [active-tab set-status!] :as ctx} state]
  (when-not @state (reset! state (build! ctx)))
  (let [{:keys [notebook term widget why]} @state]
    (if (focused? ctx state)
      (do (g/gtk-notebook-set-current-page notebook CONSOLE-PAGE)
          (editor/focus! (:ed (active-tab))))
      (do (g/gtk-notebook-set-current-page notebook TERMINAL-PAGE)
          (if term
            (do (start-shell! ctx term)
                (g/gtk-widget-grab-focus widget))
            (set-status! (str "; " why)))))))

(defn- install
  "The terminal part for one window. Nothing is built until the terminal is
  first shown. ui's :terminal is {:state :show! :busy? :focused? :showing?}, for
  the self-tests; :state holds nil, then build!'s map."
  [{:keys [bottom-focus* theme-name] :as wctx}]
  (let [state (atom nil)
        show-fn (fn [] (show! wctx state))
        busy-fn (fn [] (boolean (when-let [t (:term @state)] (running-something? t))))]
    (when bottom-focus* (reset! bottom-focus* (fn [] (focus-terminal! state))))
    (when theme-name
      (add-watch theme-name ::terminal (fn [_ _ _ _] (when-let [t (:term @state)] (apply-theme! wctx t)))))
    {:fns {:terminal-show-fn show-fn
           :terminal-focused? (fn [] (focused? wctx state))
           ;; the window's close flow asks these (jonquil.gtk.window.quit)
           :terminal-busy? busy-fn
           :terminal-end! (fn [] (when-let [t (:term @state)] (anchan/dispose! t)))}
     :ui {:terminal {:state state :show! show-fn :busy? busy-fn
                     :focused? (fn [] (focused? wctx state))
                     :showing? (fn [] (showing? state))}}}))

(def part
  "The terminal part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :terminal}))
