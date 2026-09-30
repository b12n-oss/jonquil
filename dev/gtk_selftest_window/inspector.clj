(ns gtk-selftest-window.inspector
  "P2.T1: the inspector pane widget in isolation. Every row builds its own
  pane (jonquil.gtk.inspector-pane/make-pane) and hosts its :root in a
  fresh, real, transient GtkWindow -- never the self-test's own main
  window, and never disturbing it for later rows -- long enough to check
  rendering, keys and measurement, then closes that window. The window
  WIRING (opening a real inspect! round trip from the real Jonquil window)
  is P2.T2's own rows, not these."
  (:require [clojure.string :as str]
            [gtk-async :refer [on-gtk await-until go-row]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.inspect :as inspect]))

;; gdkkeysyms.h (GTK 4.22.4 installed headers): Down 0xff54, Return 0xff0d,
;; Escape 0xff1b -- this file's own private copies, same convention as
;; every other gtk-selftest-window.* namespace's local key table.
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-ESCAPE 0xff1b)

(defn- three-row-view []
  {:path "*1" :info "3 items" :header "" :start 0 :total 3
   :entries ["0" "1" "2"]})

(defn- leaf-view []
  {:path "*1" :info "String" :header "" :leaf "\"hello\""})

(defn- with-pane
  "Builds a pane (inspector-pane/make-pane opts) inside a fresh, real,
  transient GtkWindow, shows it and waits for the rows view to have a real
  allocated size, calls (f pane), then closes that window. Every GTK call
  here runs on the GTK thread; the wait parks the calling go block
  (dev/gtk_async.clj's own rule: the GTK thread never parks).

  jonquil.gtk.popover parents a popover directly to the rows view's own
  container (gtk_widget_set_parent, not gtk_scrolled_window_set_child), so
  destroying win would otherwise finalize that container while the
  popover is still attached to it -- harmless in the real app (a
  production popover lives for the whole session, per that namespace's
  own docstring), but this pane is thrown away fresh every row, so the
  def popover is unparented explicitly first, the same way every
  throwaway popover row elsewhere in this file's siblings already does."
  [opts f]
  (let [{:keys [win pane]}
        (on-gtk (let [win (g/gtk-window-new)
                      pane (inspector-pane/make-pane opts)]
                  (g/gtk-window-set-child win (:root pane))
                  (g/gtk-window-present win)
                  {:win win :pane pane}))]
    (try
      (await-until 2000 20 #(pos? (g/gtk-widget-get-width (:view pane))))
      (f pane)
      ;; A row that throws still tears its transient window down.
      (finally
        (on-gtk (g/gtk-widget-unparent (:popover (:def-popover pane)))
                (g/gtk-window-close win))))))

(defn- noop-opts []
  {:on-request (fn [_] nil) :on-close (fn [] nil) :on-def (fn [_] nil)})

(defn row-pane-renders-view
  "make-pane + show-state! on a literal 3-entry view: header, info, three
  row lines, the footer \"1-3 of 3\", the selected tag on line 0 only."
  [_ui]
  (go-row :row-pane-renders-view support/check
    (with-pane (noop-opts)
      (fn [pane]
        (on-gtk
         (inspector-pane/show-state! pane (inspect/open (three-row-view)))
         (let [header (g/label-text (:header pane))
               info (g/label-text (:info pane))
               lines (str/split-lines (g/buffer-text (:buf pane)))
               footer (g/label-text (:footer pane))
               tag0? (g/has-tag-at? (:buf pane) 0 (:selected-tag pane))
               line1-start (inc (count (first lines)))
               tag1? (g/has-tag-at? (:buf pane) line1-start (:selected-tag pane))]
           (support/check :pane-renders-view
                          (and (= "*1" header) (= "3 items" info)
                               (= 3 (count lines)) (= "1-3 of 3" footer)
                               tag0? (not tag1?))
                          {:header header :info info :lines lines :footer footer
                           :tag0 tag0? :tag1 tag1?})))))))

(defn row-pane-keys-step
  "Down moves the tag to line 1 with no request; Return calls on-request
  with [:in {:idx 1}]; Escape calls on-close."
  [_ui]
  (go-row :row-pane-keys-step support/check
    (let [requests (atom []) closed? (atom false)]
      (with-pane {:on-request (fn [r] (swap! requests conj r))
                  :on-close (fn [] (reset! closed? true))
                  :on-def (fn [_] nil)}
        (fn [pane]
          (on-gtk
           (inspector-pane/show-state! pane (inspect/open (three-row-view)))
           (let [tag0-before? (g/has-tag-at? (:buf pane) 0 (:selected-tag pane))
                 h-down (inspector-pane/dispatch-key! pane KEY-DOWN)
                 lines (str/split-lines (g/buffer-text (:buf pane)))
                 line1-start (inc (count (first lines)))
                 tag1-after? (g/has-tag-at? (:buf pane) line1-start (:selected-tag pane))
                 no-request-yet? (empty? @requests)
                 h-return (inspector-pane/dispatch-key! pane KEY-RETURN)
                 request (last @requests)
                 h-escape (inspector-pane/dispatch-key! pane KEY-ESCAPE)]
             (support/check :pane-keys-step
                            (and (= 1 h-down) tag0-before? tag1-after? no-request-yet?
                                 (= 1 h-return) (= [:in {:idx 1}] request)
                                 (= 1 h-escape) @closed?)
                            {:h-down h-down :tag1-after tag1-after? :no-request no-request-yet?
                             :h-return h-return :request request
                             :h-escape h-escape :closed @closed?}))))))))

(defn row-pane-leaf
  "A leaf view shows the leaf text verbatim and an empty footer."
  [_ui]
  (go-row :row-pane-leaf support/check
    (with-pane (noop-opts)
      (fn [pane]
        (on-gtk
         (inspector-pane/show-state! pane (inspect/open (leaf-view)))
         (let [text (g/buffer-text (:buf pane))
               footer (g/label-text (:footer pane))]
           (support/check :pane-leaf
                          (and (= "\"hello\"" text) (= "" footer))
                          {:text text :footer footer})))))))

(defn row-pane-measure
  "measure gives :cols between 20 and 200 and :rows of at least 5."
  [_ui]
  (go-row :row-pane-measure support/check
    (with-pane (noop-opts)
      (fn [pane]
        (on-gtk
         (inspector-pane/show-state! pane (inspect/open (three-row-view)))
         (let [{:keys [cols rows]} (inspector-pane/measure pane)]
           (support/check :pane-measure
                          (and (<= 20 cols 200) (>= rows 5))
                          {:cols cols :rows rows})))))))

(defn row-pane-note-clears
  "A state with :note \"not tabular\" shows it in the footer, and the
  footer drops it again within 4 s (NOTE-CLEAR-MS is 3000)."
  [_ui]
  (go-row :row-pane-note-clears support/check
    (with-pane (noop-opts)
      (fn [pane]
        (let [state (assoc (inspect/open (three-row-view)) :note "not tabular")
              before (on-gtk (inspector-pane/show-state! pane state)
                             (g/label-text (:footer pane)))
              cleared? (await-until 4000 50
                                    #(not (str/includes? (g/label-text (:footer pane)) "not tabular")))]
          (support/check :pane-note-clears
                         (and (str/includes? before "not tabular") cleared?)
                         {:before before :cleared cleared?}))))))
