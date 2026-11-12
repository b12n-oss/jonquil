(ns jonquil.gtk.parts.live-results
  "LM1's live console results as a window part (the commands are
  jonquil.parts.live-results/spec). Moved out of jonquil.gtk.window: the
  result verbs, the right-click caret move and the click-to-inspect gesture,
  and the file:line references in console text, opened by a click or by
  Open This File and Line."
  (:require [jolt.ffi :as ffi]
            [jonquil.file-refs :as file-refs]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.help :as help]
            [jonquil.inspect :as inspect]
            [jonquil.results :as results]
            [jonquil.usages :as usages]))

(def ^:private no-result "; No result here.")

;; Live console results (LM1): a click on a result line inspects
;; its value; the verbs act on the result at the console's caret
;; (a right-click moves the caret there first)
(defn- result-at-caret
  [{:keys [cv]}]
  (let [off (g/cursor-offset (:buf cv))]
    [(cview/result-at cv off) (cview/result-text-at cv off)]))

;; labelled by its line, since *1 names only the latest result
(defn- inspect-result!
  [{:keys [inspect!]} ref text]
  (inspect! [:start-result {:id (:id ref) :epoch (:epoch ref)
                            :label (inspect/root-label (results/copy-text (or text "")))}]))

;; each verb asks the kind's table (jonquil.results) before acting
(defn- with-result
  "Runs (f ref text) on the result at the caret when the verb is offered for
  its kind; else says why in the status bar. spec is {:verb :not-offered}."
  [{:keys [set-status!] :as ctx} {:keys [verb not-offered]} f]
  (let [[ref text] (result-at-caret ctx)]
    (cond (nil? ref) (set-status! no-result)
          (not (results/offered? verb (:kind ref))) (set-status! not-offered)
          :else (f ref text))))

(defn- result-inspect!
  [ctx]
  (with-result ctx {:verb :inspect :not-offered no-result}
    (fn [ref text] (inspect-result! ctx ref text))))

(defn- result-copy!
  [{:keys [cv set-status!] :as ctx}]
  (with-result ctx {:verb :copy :not-offered no-result}
    (fn [_ text]
      (g/gdk-clipboard-set-text (g/gtk-widget-get-clipboard (:view cv)) (results/copy-text text))
      (set-status! "; Copied the result."))))

(defn- result-define!
  [{:keys [cv] :as ctx}]
  (with-result ctx {:verb :define :not-offered no-result}
    (fn [ref _]
      (let [{:keys [text caret]} (results/define-input ref)]
        (cview/set-input! cv text caret)))))

(defn- jump-to-var!
  "Looks sym up and jumps to its definition, or says none was found."
  [{:keys [ev* set-status! active-tab ask-lookup! jump-to! source-ctx status-label]} sym]
  (ask-lookup! {:ev* ev* :status-label status-label}
               {:sym sym :text "" :pos 0 :still? (constantly true) :label "jump"
                :on-doc (fn [doc]
                          (if doc
                            (jump-to! (source-ctx (active-tab)) sym {:kind :external :doc doc})
                            (set-status! (str "No definition found for " sym "."))))}))

(defn- result-jump!
  [{:keys [set-status!] :as ctx}]
  (with-result ctx {:verb :jump :not-offered "; That result isn't a var."}
    (fn [_ text]
      (if-let [sym (results/var-name text)]
        (jump-to-var! ctx sym)
        (set-status! "; That result isn't a var.")))))

(defn- show-var-doc!
  "Looks sym up and prints its quick doc in the console as comment lines,
  or says none was found."
  [{:keys [ev* cv set-status! ask-lookup! status-label]} sym]
  (ask-lookup! {:ev* ev* :status-label status-label}
               {:sym sym :text "" :pos 0 :still? (constantly true) :label "quick doc"
                :on-doc (fn [doc]
                          (if doc
                            (cview/append! cv :system (results/doc-console-text (help/doc-text sym doc)))
                            (set-status! (str "No documentation found for " sym "."))))}))

(defn- result-doc!
  [{:keys [set-status!] :as ctx}]
  (with-result ctx {:verb :doc :not-offered "; That result isn't a var."}
    (fn [_ text]
      (if-let [sym (results/var-name text)]
        (show-var-doc! ctx sym)
        (set-status! "; That result isn't a var.")))))

;; file:line references (LM1's file:line kind): plain console text, not a
;; result, so they need no handle; the reference names a file and a line
(defn- file-ref-at
  "The file:line reference in the console line holding offset, with the
  line's ns-hint, or nil."
  [{:keys [cv]} offset]
  (let [{:keys [text start]} (cview/line-at cv offset)]
    (when-let [r (file-refs/ref-at text (- offset start))]
      (assoc r :ns-hint (file-refs/ns-hint text)))))

(defn- project-root
  "The folder the file tree shows, else the active tab's file's folder, or nil."
  [{:keys [tree-pane active-tab]}]
  (or (some-> tree-pane :state deref :root)
      (some-> (active-tab) :path deref (java.io.File.) .getCanonicalFile .getParent)))

(defn- open-file-ref!
  "Opens the file and line r names, or says why it can't."
  [{:keys [set-status! source-ctx active-tab open-file-line!] :as ctx} {:keys [file line ns-hint]}]
  (let [root (project-root ctx)
        label (str file ":" line)
        r (file-refs/resolve-file file {:root root
                                        :files (if root (usages/project-files root) [])
                                        :exists? #(.isFile (java.io.File. ^String %))
                                        :ns-hint ns-hint})]
    (cond
      (:path r) (open-file-line! (source-ctx (active-tab)) label (:path r) line)
      (:candidates r) (set-status! (str "; " (count (:candidates r)) " project files could be " file
                                        ", so none was opened."))
      :else (set-status! (str "; No file " file " in this project.")))))

(defn- result-open-location!
  [{:keys [cv set-status!] :as ctx}]
  (if-let [r (file-ref-at ctx (g/cursor-offset (:buf cv)))]
    (open-file-ref! ctx r)
    (set-status! "; No file:line here.")))

;; GTK's own right-click opens the menu without moving the caret
;; (gtk_text_view_click_gesture_pressed: a context-menu press goes
;; to do_popup), so this moves it to the click first, in the capture
;; phase before GTK's gesture claims the press; a right-click inside
;; a selection leaves it, for GTK's own Copy
(defn- console-right-press!
  [{:keys [cv]} x y]
  (let [view (:view cv) buf (:buf cv)
        out (ffi/alloc 8) it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-view-window-to-buffer-coords view g/TEXT-WINDOW-WIDGET (int x) (int y) out (+ out 4))
      (g/gtk-text-view-get-iter-at-location view it (ffi/read out :int 0) (ffi/read out :int 4))
      (let [off (g/gtk-text-iter-get-offset it)
            [a b] (g/selection-offsets buf)]
        (when-not (and (< a b) (<= a off) (< off b))
          (g/with-iters* buf off off (fn [i _] (g/gtk-text-buffer-place-cursor buf i)))))
      (finally (ffi/free out) (ffi/free it)))))

(defn- add-right-click!
  "Adds the right-press gesture to the console's view, its callable built once."
  [{:keys [cv] :as ctx}]
  (let [rclick (g/gtk-gesture-click-new)]
    (g/gtk-gesture-single-set-button rclick 3)
    (g/gtk-event-controller-set-propagation-phase rclick g/GTK-PHASE-CAPTURE)
    (g/g-signal-connect-data rclick "pressed"
                             (callable (fn [_gesture _n x y _data] (console-right-press! ctx x y) nil)
                                       [:pointer :int :double :double :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller (:view cv) rclick)))

(defn- result-click!
  [{:keys [cv] :as ctx} offset]
  (when-let [ref (cview/result-at cv offset)]
    (inspect-result! ctx ref (cview/result-text-at cv offset))))

(defn- inspect-after-wait!
  "On the GUI thread, once the click's wait is up: inspects the result if
  that click is still the last, nothing is selected since, and its line is
  still on screen."
  [{:keys [cv click-gen] :as ctx} {:keys [n ref text]}]
  (let [[a2 b2] (g/selection-offsets (:buf cv))]
    (when (and (= n @click-gen) (= a2 b2)
               (some #(= ref (:ref %)) @(:results cv)))
      (inspect-result! ctx ref text))))

(defn- open-after-wait!
  "On the GUI thread, once the click's wait is up: opens the file:line
  reference r if that click is still the last and nothing is selected
  since."
  [{:keys [cv click-gen] :as ctx} {:keys [n r]}]
  (let [[a2 b2] (g/selection-offsets (:buf cv))]
    (when (and (= n @click-gen) (= a2 b2))
      (open-file-ref! ctx r))))

;; a single primary click, with no text selected by it, on a result
;; line. It waits out GTK's gtk-double-click-time, which on macOS
;; follows the system's double-click speed, and a second press
;; cancels it, so a double-click that selects a word never opens the
;; inspector first.
(defn- result-release!
  [{:keys [cv click-gen] :as ctx} off]
  (let [[a b] (g/selection-offsets (:buf cv))
        ;; the result clicked, resolved now: output
        ;; trimmed while the click waits moves every span
        ref (cview/result-at cv off)
        text (cview/result-text-at cv off)
        ;; a result wins; else a file:line reference in the clicked line
        fref (when (and (nil? ref) (= a b)) (file-ref-at ctx off))
        n (swap! click-gen inc)
        wait (g/double-click-time (g/gtk-settings-get-default))]
    (when (and (or ref fref) (= a b))
      (future
        (Thread/sleep wait)
        (on-gui! (fn [] (if ref
                          (inspect-after-wait! ctx {:n n :ref ref :text text})
                          (open-after-wait! ctx {:n n :r fref}))))))))

(defn- add-click!
  "Adds the click gesture to the console's view, its callables built once."
  [{:keys [cv click-gen] :as ctx}]
  (let [click (g/gtk-gesture-click-new)]
    (g/g-signal-connect-data click "pressed"
                             (callable (fn [_gesture n-press _x _y _data]
                                         (when (> n-press 1) (swap! click-gen inc))
                                         nil)
                                       [:pointer :int :double :double :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-signal-connect-data click "released"
                             (callable (fn [_gesture n-press _x _y _data]
                                         (when (= 1 n-press)
                                           (result-release! ctx (g/cursor-offset (:buf cv))))
                                         nil)
                                       [:pointer :int :double :double :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/gtk-widget-add-controller (:view cv) click)))

(defn- install
  "The live-results part for one window: answers the six result fns and the
  three gesture fns, and adds the right-press and click gestures to the
  console's view, each callable built once."
  [wctx]
  (let [ctx (assoc wctx :click-gen (atom 0))]
    (add-right-click! ctx)
    (add-click! ctx)
    {:fns {:result-inspect-fn (fn [] (result-inspect! ctx)) :result-copy-fn (fn [] (result-copy! ctx))
           :result-define-fn (fn [] (result-define! ctx)) :result-jump-fn (fn [] (result-jump! ctx))
           :result-doc-fn (fn [] (result-doc! ctx))
           :result-open-location-fn (fn [] (result-open-location! ctx))}
     :ui {:result-click!* (fn [offset] (result-click! ctx offset))
          :result-release!* (fn [off] (result-release! ctx off))
          :console-right-press!* (fn [x y] (console-right-press! ctx x y))}}))

(def part
  "The live-results part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :live-results}))
