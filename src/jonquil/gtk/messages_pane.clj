(ns jonquil.gtk.messages-pane
  "The Messages panel in the right-hand slot (LM8): the window's log of
  background events (jonquil.messages), newest at the top, a row of kind
  filters, a footer, and Close. A row with detail opens it on Enter.
  jonquil.gtk.window decides what a row's jump does."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.messages :as messages]))

(def KIND-TAGS
  {:agent "agent" :agent-read "read" :plugin "plugin" :file "file" :connection "conn"
   :runtime "runtime" :status "status"})

(def FILTERS
  "[kind label] for the filter buttons, in their order."
  [[:agent "Agent"] [:file "Files"] [:agent-read "Reads"] [:plugin "Plugins"]
   [:connection "Connections"] [:runtime "Runtime"] [:status "Status"]])

(def ^:private EMPTY
  "No messages yet. Agent actions, connections and restarts show up here.")

(defn clock-text
  "Epoch ms as local HH:MM:SS. java.util.Date's print is local time; this
  Jolt has no java.time zones (measured 2026-11-05)."
  [ms]
  (or (re-find #"\d\d:\d\d:\d\d" (str (java.util.Date. (long ms)))) "--:--:--"))

(defn row-text
  "An entry as a row: [! ]HH:MM:SS  tag  summary[ ×N], the summary's
  \"tab <id>\" swapped for (tab-label id), \"a closed tab\" when that's nil.
  {:expanded? true} adds the detail below. Plain text, never markup."
  ([entry tab-label clock] (row-text entry tab-label clock nil))
  ([{:keys [kind summary summary-tab repeat error? detail at]} tab-label clock {:keys [expanded?]}]
   (let [summary (if summary-tab
                   (str/replace summary (str "tab " summary-tab) (or (tab-label summary-tab) "a closed tab"))
                   summary)]
     (str (when error? "! ") (clock at) "  " (KIND-TAGS kind (name kind)) "  " summary
          (when repeat (str " ×" repeat))
          (when (and expanded? detail) (str "\n" detail))))))

(defn footer-text
  "The footer for log under the kinds shown: how many entries and how many
  were dropped, or the empty text when nothing is shown."
  [log kinds]
  (let [n (count (:entries log))
        shown (count (messages/visible log kinds))
        d (:dropped log)]
    (if (zero? shown)
      EMPTY
      (str (when (< shown n) (str shown " of "))
           n (if (= 1 n) " entry" " entries") (when (pos? d) (str ", " d " dropped"))))))

(defn report!
  "Puts msg on the panel's footer."
  [pane msg]
  (g/gtk-label-set-text (:footer pane) msg)
  nil)

(defn set-handlers!
  "Registers {:on-activate (fn [entry]) :on-close (fn []) :on-filter (fn [])}."
  [pane m]
  (reset! (:handlers pane) m)
  nil)

(defn selected-index
  "Where the entry with id sits in es, so a rebuild keeps the row the
  person was on; 0 when it's gone (or none was), nil when es is empty."
  [es id]
  (when (seq es)
    (or (first (keep-indexed (fn [i e] (when (= id (:id e)) i)) es)) 0)))

(defn- selected-id [pane]
  (let [row (g/gtk-list-box-get-selected-row (:list pane))]
    (when-not (ffi/null? row)
      (:id (get @(:entries pane) (g/gtk-list-box-row-get-index row))))))

(defn- append-row-labels!
  "Replaces the list's rows with one label per row text."
  [lb rows]
  (g/gtk-list-box-remove-all lb)
  (doseq [r rows]
    ;; plain text, never markup: a row carries an agent's own text
    (let [label (g/gtk-label-new r)]
      (g/gtk-label-set-xalign label 0.0)
      (g/gtk-label-set-wrap label 1)
      (g/gtk-widget-set-halign label g/ALIGN-START)
      (g/gtk-list-box-append lb label))))

(defn show!
  "Shows log's entries of the ticked kinds, newest at the top, keeping the
  selected entry selected (the top one at first). When no shown row
  changed (a hidden status line arrived), only the footer is set, so the
  list and its scroll stay where the person left them. tab-label is
  (fn [tab-id]) -> label or nil."
  [pane log tab-label]
  (let [lb (:list pane)
        es (messages/visible log @(:kinds* pane))
        open @(:expanded* pane)
        rows (mapv #(row-text % tab-label clock-text {:expanded? (contains? open (:id %))}) es)]
    (if (and (= rows @(:rows pane)) (= (map :id es) (map :id @(:entries pane))))
      (report! pane (footer-text log @(:kinds* pane)))
      (let [keep-id (selected-id pane)]
        (reset! (:entries pane) es)
        (reset! (:rows pane) rows)
        (append-row-labels! lb rows)
        (when-let [i (selected-index es keep-id)]
          (g/gtk-list-box-select-row lb (g/gtk-list-box-get-row-at-index lb i)))
        (report! pane (footer-text log @(:kinds* pane)))))))

(defn row-texts
  "The shown rows, in order."
  [pane]
  @(:rows pane))

(defn- toggle-member
  "open with id removed when it is in there, else added."
  [open id]
  (if (contains? open id) (disj open id) (conj open id)))

(defn activate!
  "Runs what row i does, as Enter on it: opens or closes its detail, then
  :on-activate with its entry."
  [pane i]
  (when-let [e (get @(:entries pane) i)]
    (when (:detail e)
      (swap! (:expanded* pane) toggle-member (:id e)))
    (when-let [f (:on-activate @(:handlers pane))] (f e))))

(defn set-kind!
  "Ticks or unticks kind's filter button, as a click would (its toggled
  handler updates :kinds* and calls :on-filter)."
  [pane kind on?]
  (when-let [btn (get (into {} (:filter-btns pane)) kind)]
    (g/gtk-check-button-set-active btn (if on? 1 0))))

(defn- connect-filter-btns!
  "Ticks each filter button as SHOWN-KINDS says, packs it into the filter
  row for its position (four, then three, so seven labels fit the panel's
  width) and connects its toggled signal to :kinds* and :on-filter."
  [{:keys [filter-btns] :as pane} filter-rows]
  (doseq [[i [k btn]] (map-indexed vector filter-btns)]
    (g/gtk-check-button-set-active btn (if (contains? messages/SHOWN-KINDS k) 1 0))
    (g/gtk-box-append (filter-rows (quot i 4)) btn)
    (g/g-signal-connect-data btn "toggled"
                             (callable (fn [b _data]
                                         (try (swap! (:kinds* pane) (if (pos? (g/gtk-check-button-get-active b)) conj disj) k)
                                              (when-let [f (:on-filter @(:handlers pane))] (f))
                                              (catch :default e (report! pane (str "; " (ex-message e)))))
                                         nil)
                                       [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)))

(defn- lay-out!
  "Sets up the list and the labels and packs the widgets into the root."
  [{:keys [root title scroll close-btn footer] lb :list} filter-rows]
  (g/gtk-list-box-set-selection-mode lb g/SELECTION-MODE-BROWSE)
  (g/gtk-scrolled-window-set-child scroll lb)
  (g/gtk-widget-set-vexpand scroll 1)
  (doseq [l [title footer]]
    (g/gtk-label-set-xalign l 0.0)
    (g/gtk-label-set-wrap l 1))
  (doseq [w (concat [title] filter-rows [scroll close-btn footer])] (g/gtk-box-append root w))
  (g/gtk-widget-set-size-request root source-pane/WIDTH-REQUEST -1))

(defn- connect-list-and-close!
  "Connects the list's row-activated and the Close button's clicked to their
  handlers."
  [{:keys [close-btn] lb :list :as pane}]
  (g/g-signal-connect-data lb "row-activated"
                           (callable (fn [_box row _data]
                                       (try (activate! pane (g/gtk-list-box-row-get-index row))
                                            (catch :default e (report! pane (str "; " (ex-message e)))))
                                       nil)
                                     [:pointer :pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT)
  (g/g-signal-connect-data close-btn "clicked"
                           (callable (fn [_button _data]
                                       (try (when-let [f (:on-close @(:handlers pane))] (f))
                                            (catch :default e (report! pane (str "; " (ex-message e)))))
                                       nil)
                                     [:pointer :pointer] :void)
                           ffi/null ffi/null g/CONNECT-DEFAULT))

(defn make-pane
  "Builds the panel: {:root :title :filter-btns :list :scroll :close-btn
  :footer :kinds* :expanded* :rows :entries :handlers}. :filter-btns is
  [[kind button] ...] in FILTERS' order, ticked as messages/SHOWN-KINDS
  says. :root starts visible; the window hides it."
  []
  (let [filter-rows [(g/gtk-box-new g/ORIENTATION-HORIZONTAL 8) (g/gtk-box-new g/ORIENTATION-HORIZONTAL 8)]
        pane {:root (g/gtk-box-new g/ORIENTATION-VERTICAL 4)
              :title (g/gtk-label-new "Messages")
              :filter-btns (mapv (fn [[k label]] [k (g/gtk-check-button-new-with-label label)]) FILTERS)
              :list (g/gtk-list-box-new)
              :scroll (g/gtk-scrolled-window-new)
              :close-btn (g/gtk-button-new-with-label "Close")
              :footer (g/gtk-label-new "")
              :kinds* (atom messages/SHOWN-KINDS)
              :expanded* (atom #{}) :rows (atom []) :entries (atom []) :handlers (atom {})}]
    (connect-filter-btns! pane filter-rows)
    (lay-out! pane filter-rows)
    (connect-list-and-close! pane)
    pane))
