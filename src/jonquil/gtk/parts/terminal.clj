(ns jonquil.gtk.parts.terminal
  "The terminals as a window part (the commands and the key are
  jonquil.parts.terminal/spec). The first time one is shown, the console's
  place below the editor becomes a notebook: Interactions, then a tab for
  each terminal, with a + at the end of the strip that opens another. Each
  terminal is a login shell in the active file's project folder, and its
  tab has a close button. Until the first one the window is as it was.
  Colours follow the theme and the text size the editor's
  (jonquil.terminal-colors); numbering and which terminal to show are
  jonquil.terminals'.

  The terminal is Anchan's (anchan.core), on its VTE backend. Where VTE
  can't be used the Terminal tab says why. A release Jonquil.app carries
  its own GTK and may load only its own libraries, so it uses the VTE in
  its Frameworks folder and never the system's."
  (:require [anchan.core :as anchan]
            [anchan.vte :as vte]
            [jolt.ffi :as ffi]
            [jolt.process :as p]
            [jonquil.bundle-env :as bundle-env]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.terminal-colors :as terminal-colors]
            [jonquil.terminal-keys :as terminal-keys]
            [jonquil.terminal-open :as terminal-open]
            [jonquil.terminals :as terminals]
            [jonquil.theme :as theme]))

(def CONSOLE-PAGE 0)
;; GtkPackType's GTK_PACK_END: the + goes at the end of the tab strip
(def PACK-END 1)
;; the tab's close question: Close Anyway is 0 and Cancel 1, and Cancel is
;; both the cancel and the default button, as in the window's own alert
(def CLOSE-ALERT {:cancel 1 :default 1})

(defn- os [] (System/getProperty "os.name"))

(def VTE-FILE
  "VTE's library as a release Jonquil.app carries it in its Frameworks."
  "libvte-2.91-gtk4.0.dylib")

(defn bundled-vte
  "The path of the VTE library inside a release Jonquil.app whose
  Frameworks folder is frameworks, or nil outside one (frameworks nil)."
  [frameworks]
  (when frameworks (str frameworks "/" VTE-FILE)))

(defn unavailable
  "Why this Jonquil has no terminal, as a sentence, or nil when it has
  one. bundle? is true in a release Jonquil.app, which uses only the VTE
  it carries: without one it says so, whatever the system has."
  [bundle? backend-problem]
  (cond
    (and bundle? backend-problem)
    (str "This download of Jonquil doesn't include the terminal. "
         "\"Open a terminal in the project folder\", in the Tools menu, starts your system's terminal.")
    backend-problem backend-problem))

(defn- apply-theme!
  "Gives the terminal the active theme's colours: its text and background,
  and the 16 ANSI colours for a light or a dark theme."
  [{:keys [theme-name]} term]
  (when (and term theme-name)
    (anchan/set-colors! term (terminal-colors/colors (theme/theme @theme-name)))))

(defn- apply-font!
  "Gives the terminal the code views' text size, so Larger Text and
  Smaller Text change it with the editor."
  [{:keys [zoom-px]} term]
  (when (and term zoom-px)
    (anchan/set-font! term (terminal-colors/font @zoom-px))))

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

(defn- on-click!
  "Runs f when button is clicked."
  [button f]
  (g/g-signal-connect-data button "clicked" (callable (fn [_b _d] (f) nil) [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn- flat-button
  "A frameless button saying text, with tooltip tip."
  [text tip]
  (let [b (g/gtk-button-new-with-label text)]
    (g/gtk-button-set-has-frame b 0)
    (g/gtk-widget-set-tooltip-text b tip)
    b))

(defn- build!
  "Turns the console's place into a notebook, once: Interactions first.
  With VTE the + at the end of the strip opens a terminal (on-new); without
  it a Terminal tab says why. Answers the part's state: {:notebook :why
  :terms [] :last nil :term nil}."
  [{:keys [paned cv]} on-new]
  (let [bundled (bundled-vte (bundle-env/frameworks))
        ;; a release app loads the VTE beside its own GTK and no other: a
        ;; VTE from Homebrew would bring a second GTK into the process
        _ (when bundled (swap! vte/library-paths assoc :darwin [bundled] :linux [bundled]))
        why (unavailable (some? bundled) (anchan/problem vte/backend))
        scroll (:scroll cv)
        nb (g/gtk-notebook-new)]
    ;; the console's scrolled window moves from the paned into the
    ;; notebook: a reference keeps it alive while it has no parent
    (g/g-object-ref scroll)
    (g/gtk-paned-set-end-child paned ffi/null)
    (g/gtk-notebook-set-show-border nb 0)
    (g/gtk-notebook-set-scrollable nb 1)
    (g/gtk-notebook-append-page nb scroll (g/gtk-label-new "Interactions"))
    (if why
      (g/gtk-notebook-append-page nb (message-widget why) (g/gtk-label-new "Terminal"))
      (let [plus (flat-button "+" "Open another terminal")]
        (on-click! plus on-new)
        (g/gtk-notebook-set-action-widget nb plus PACK-END)))
    (g/gtk-paned-set-end-child paned nb)
    (g/g-object-unref scroll)
    {:notebook nb :why why :terms [] :last nil :term nil}))

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

(defn- by-number [state n]
  (first (filter #(= n (:n %)) (:terms @state))))

(defn- showing-number
  "The number of the terminal whose tab is showing, or nil on Interactions
  and before anything is built."
  [state]
  (when-let [{:keys [notebook terms]} @state]
    (let [page (g/gtk-notebook-get-current-page notebook)]
      (:n (first (filter #(= page (g/gtk-notebook-page-num notebook (:widget %))) terms))))))

(defn- focused? [{:keys [win]} state]
  (boolean (when-let [{:keys [terms]} @state]
             (let [focus (g/gtk-root-get-focus win)]
               (some #(= focus (:widget %)) terms)))))

(defn- showing? [state]
  (boolean (when-let [{:keys [notebook]} @state]
             (pos? (g/gtk-notebook-get-current-page notebook)))))

(defn- go-to!
  "Shows terminal number n's tab, starts its shell when it has none, and
  gives it the focus. Remembers it as the one used last."
  [ctx state n]
  (when-let [{:keys [term widget]} (by-number state n)]
    (let [{:keys [notebook]} @state]
      (g/gtk-notebook-set-current-page notebook (g/gtk-notebook-page-num notebook widget))
      (swap! state assoc :last n :term term)
      (start-shell! ctx term)
      (g/gtk-widget-grab-focus widget))))

(defn- focus-terminal!
  "Focuses the terminal whose tab is showing: true when it did."
  [state]
  (boolean (when-let [n (showing-number state)]
             (when-let [{:keys [widget]} (by-number state n)]
               (g/gtk-widget-grab-focus widget)
               true))))

(defn- remove-terminal!
  "Ends terminal number n and takes its tab away. The tab beside it shows
  next, with the focus when the closed one had it; with no terminal left
  the Interactions tab and the editor do."
  [{:keys [active-tab] :as ctx} state n]
  (when-let [{:keys [term widget]} (by-number state n)]
    (let [{:keys [notebook terms]} @state
          had-focus? (focused? ctx state)
          was-showing? (= n (showing-number state))
          then (terminals/after-close (map :n terms) n)]
      (anchan/dispose! term)
      (g/gtk-notebook-remove-page notebook (g/gtk-notebook-page-num notebook widget))
      (swap! state (fn [m]
                     (let [left (filterv #(not= n (:n %)) (:terms m))]
                       (assoc m :terms left
                              :last (when (not= n (:last m)) (:last m))
                              :term (when (not= term (:term m)) (:term m))))))
      (cond
        (and then was-showing? had-focus?) (go-to! ctx state then)
        (and then was-showing?)
        (let [{w :widget t :term} (by-number state then)]
          (g/gtk-notebook-set-current-page notebook (g/gtk-notebook-page-num notebook w))
          (swap! state assoc :last then :term t))
        (nil? then)
        (do (g/gtk-notebook-set-current-page notebook CONSOLE-PAGE)
            (when had-focus? (editor/focus! (:ed (active-tab)))))))))

(defn- close-terminal!
  "The tab's close button: closes terminal number n, asking first when a
  program is still running in it. The question shares the window's one
  alert guard, so it never opens over another."
  [{:keys [win cv alert-pending? show-alert!] :as ctx} state n]
  (when-let [{:keys [term]} (by-number state n)]
    (cond
      (not (running-something? term)) (remove-terminal! ctx state n)
      @alert-pending? nil
      :else
      (do (reset! alert-pending? true)
          (try
            (show-alert! win (terminals/close-alert n) CLOSE-ALERT
                         (fn [{:keys [index]}] (when (= 0 index) (remove-terminal! ctx state n))))
            (catch :default e
              (reset! alert-pending? false)
              (cview/append! cv :error (str "; terminal: " (ex-message e) "\n"))))))))

(defn- tab-label
  "The tab for terminal number n: its name and a close button."
  [ctx state n]
  (let [box (g/gtk-box-new g/ORIENTATION-HORIZONTAL 4)
        close (flat-button "×" (str "Close " (terminals/label n)))]
    (g/gtk-box-append box (g/gtk-label-new (terminals/label n)))
    (g/gtk-box-append box close)
    (on-click! close (fn [] (close-terminal! ctx state n)))
    box))

(defn- add-terminal!
  "Makes one more terminal with its tab at the end. Answers its number."
  [ctx state]
  (let [{:keys [notebook terms]} @state
        n (terminals/next-number (map :n terms))
        term (anchan/terminal vte/backend)
        widget (anchan/widget term)]
    (apply-theme! ctx term)
    (apply-font! ctx term)
    (connect-clipboard-keys! term)
    (g/gtk-notebook-append-page notebook widget (tab-label ctx state n))
    (swap! state update :terms conj {:n n :term term :widget widget})
    n))

(declare new!)

(defn- ensure-built! [ctx state]
  (when-not @state (reset! state (build! ctx (fn [] (new! ctx state))))))

(defn- say-why!
  "With no VTE: shows the Terminal tab that says why, and says it in the
  status line."
  [{:keys [set-status!]} state]
  (let [{:keys [notebook why]} @state]
    (g/gtk-notebook-set-current-page notebook 1)
    (set-status! (str "; " why))))

(defn- new!
  "The + and New Terminal: opens one more terminal and goes to it."
  [ctx state]
  (ensure-built! ctx state)
  (if (:why @state)
    (say-why! ctx state)
    (go-to! ctx state (add-terminal! ctx state))))

(defn- show!
  "Show the terminal: builds the tabs the first time, then goes to the
  terminal whose tab is showing, else the one used last, opening one when
  there is none. From a terminal itself it goes back to the Interactions
  tab and the editor."
  [{:keys [active-tab] :as ctx} state]
  (ensure-built! ctx state)
  (let [{:keys [notebook why terms last]} @state]
    (cond
      why (say-why! ctx state)
      (focused? ctx state) (do (g/gtk-notebook-set-current-page notebook CONSOLE-PAGE)
                               (editor/focus! (:ed (active-tab))))
      :else (if-let [n (terminals/target {:numbers (map :n terms) :showing (showing-number state) :last last})]
              (go-to! ctx state n)
              (new! ctx state)))))

(defn- install
  "The terminal part for one window. Nothing is built until a terminal is
  first shown. ui's :terminal is {:state :show! :new! :close! :numbers
  :busy? :focused? :showing?}, for the self-tests; :state holds nil, then
  {:notebook :why :terms [{:n :term :widget}] :last :term}, :term being
  the terminal used last."
  [{:keys [bottom-focus* theme-name zoom-px] :as wctx}]
  (let [state (atom nil)
        each-term (fn [f] (doseq [{:keys [term]} (:terms @state)] (f term)))
        show-fn (fn [] (show! wctx state))
        new-fn (fn [] (new! wctx state))
        busy-fn (fn [] (boolean (some #(running-something? (:term %)) (:terms @state))))]
    (when bottom-focus* (reset! bottom-focus* (fn [] (focus-terminal! state))))
    (when theme-name
      (add-watch theme-name ::terminal (fn [_ _ _ _] (each-term #(apply-theme! wctx %)))))
    (when zoom-px
      (add-watch zoom-px ::terminal (fn [_ _ _ _] (each-term #(apply-font! wctx %)))))
    {:fns {:terminal-show-fn show-fn
           :terminal-new-fn new-fn
           :terminal-focused? (fn [] (focused? wctx state))
           ;; the window's close flow asks these (jonquil.gtk.window.quit)
           :terminal-busy? busy-fn
           :terminal-end! (fn [] (each-term anchan/dispose!))}
     :ui {:terminal {:state state :show! show-fn :new! new-fn
                     :close! (fn [n] (close-terminal! wctx state n))
                     :numbers (fn [] (mapv :n (:terms @state)))
                     :busy? busy-fn
                     :focused? (fn [] (focused? wctx state))
                     :showing? (fn [] (showing? state))}}}))

(def part
  "The terminal part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :terminal}))
