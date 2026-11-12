(ns jonquil.gtk.parts.docs
  "LM6's docs panel as a window part (the commands and the label are
  jonquil.parts.docs/spec): a symbol's doc, or a guide page, kept in the
  right-hand slot with a history ([ and ]) and bookmarks in the settings
  file's :doc-bookmarks. The doc comes from the same lookup full doc and quick
  doc use, in the window's dialect; the pages' data is jonquil.docs-panel and
  the widgets jonquil.gtk.docs-pane."
  (:require [jonquil.doc-examples :as doc-examples]
            [jonquil.docs-panel :as dp]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.docs-pane :as docs-pane]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.palette :as palette]
            [jonquil.help :as help]
            [jonquil.settings :as settings]
            [jonquil.source :as source]))

(defn- say!
  "Says msg on the panel's footer and in the status bar."
  [{:keys [docp set-status!]} msg]
  (docs-pane/report! docp msg)
  (set-status! msg))

(defn- saved
  "The bookmarked pages in the settings file, [] when there are none."
  [{:keys [settings-path*]}]
  (vec (:doc-bookmarks (settings/load-settings @settings-path*))))

(defn- guide-directory
  "The guide's directory beside the running source tree, nil when the app
  has none (the installed app is one compiled file)."
  []
  (dp/guide-dir (source/classpath-file "jonquil/keymap.edn")))

;; --- showing a page ---------------------------------------------------------

(defn- render!
  "Shows the page cached for page, with the history and the bookmark button."
  [{:keys [docp history* contents*] :as ctx} page]
  (docs-pane/show! docp (assoc (get @contents* page)
                               :title (dp/page-label page)
                               :history @history*
                               :bookmarked? (dp/bookmarked? (saved ctx) page))))

(defn- land!
  "On the GTK thread: shows content ({:text :examples}) for page and opens the
  panel. With push? the page joins the history (the pages that were ahead of
  it go), else the history already stands on it. The cache keeps only the
  pages the history holds."
  [{:keys [history* contents* right show-right-panel!] :as ctx} page content push?]
  (when push? (swap! history* dp/push page))
  (swap! contents* (fn [m] (select-keys (assoc m page content) (:items @history*))))
  (render! ctx page)
  (show-right-panel! right :docs))

(defn- land-full!
  "A full-doc answer for sym: its text split into the doc and its ClojureDocs
  examples, shown under the qualified name the title gives. :no-op (a server
  without the op) falls back to quick doc; any other status says why."
  [ctx sym result quick!]
  (if (= :no-op (:status result))
    (quick!)
    (if (= :ok (:status result))
      (let [text (:text result)
            {:keys [doc title examples]} (try (doc-examples/split text)
                                              (catch :default _ {:doc text :examples []}))]
        (land! ctx (dp/symbol-page (dp/canonical-id sym title))
               {:text doc :examples (vec examples)} true))
      (say! ctx (help/full-doc-status sym result)))))

(defn- not-found?
  "True for a lookup answer that knows nothing of the symbol."
  [doc]
  (or (nil? doc) (= :not-found (:status doc))))

(defn- open-symbol!
  "Looks sym up in the window's dialect and shows its doc: the full doc when
  the evaluator has one (with its examples), else the quick doc text. pos is
  where sym stands in the active tab's text, 0 for a symbol that came from a
  page. A newer lookup drops this one's answer if it comes late."
  [{:keys [ev* active-tab status-label ask-lookup! seq*] :as ctx} sym pos]
  (let [n (swap! seq* inc)
        fresh? (fn [] (= n @seq*))
        text (editor/editor-text (:ed (active-tab)))
        quick! (fn []
                 (ask-lookup! {:ev* ev* :status-label status-label}
                              {:sym sym :text text :pos pos :still? fresh? :label "doc panel"
                               :on-doc (fn [doc]
                                         (if (not-found? doc)
                                           (say! ctx (str "No docs for " sym "."))
                                           (land! ctx (dp/symbol-page sym)
                                                  {:text (help/doc-text sym doc) :examples []} true)))}))]
    (if (satisfies? ev/FullDoc @ev*)
      (do (say! ctx (str "Loading docs for " sym "..."))
          (ev/full-doc @ev* sym text
                       (fn [result]
                         (on-gui! (fn []
                                    (try (when (fresh?) (land-full! ctx sym result quick!))
                                         (catch :default e (say! ctx (str "; docs panel: " (ex-message e))))))))))
      (quick!))))

(defn- open-guide!
  "Shows guide page id; push? as for land!. Says so when the guide or the page
  isn't there."
  [ctx id push?]
  (if-let [path (dp/guide-file (guide-directory) id)]
    (land! ctx (dp/guide-page id) {:text (dp/guide-text (slurp path)) :examples []} push?)
    (say! ctx (if (guide-directory)
                (str "; The guide has no page " id ".")
                "; The guide isn't installed: Jonquil found no docs/guide beside its source."))))

(defn- open-page!
  "Opens page, a symbol's doc or a guide page, as a new step in the history."
  [ctx page]
  (case (:kind page)
    :symbol (open-symbol! ctx (:id page) 0)
    :guide (open-guide! ctx (:id page) true)))

(defn- step!
  "Back or Forward (f is dp/back or dp/forward): moves the history and shows
  the page it stands on, from the cache, else looked up again."
  [{:keys [history* contents*] :as ctx} f none-msg]
  (if-let [h (f @history*)]
    (let [page (dp/current h)]
      (reset! history* h)
      (if (contains? @contents* page)
        (render! ctx page)
        (case (:kind page)
          :symbol (open-symbol! ctx (:id page) 0)
          :guide (open-guide! ctx (:id page) false))))
    (say! ctx none-msg)))

;; --- the commands -----------------------------------------------------------

(defn- show!
  "Show Doc in Panel: the symbol at the caret, else the panel as it was."
  [{:keys [active-tab right show-right-panel! docp history*] :as ctx}]
  (let [snap (editor/snapshot (:ed (active-tab)))]
    (if-let [{:keys [symbol start]} (help/target snap)]
      (open-symbol! ctx symbol start)
      (do (show-right-panel! right :docs)
          (docs-pane/focus! docp)
          (when (empty? (:items @history*))
            (say! ctx "No symbol at the caret."))))))

(defn- bookmark!
  "Bookmark This Page: the page shown is saved, or its bookmark removed."
  [{:keys [history* settings-path* docp] :as ctx}]
  (if-let [page (dp/current @history*)]
    (let [{:keys [added? bookmarks]} (dp/toggle-bookmark (saved ctx) page)]
      (if-let [err (settings/save-settings! @settings-path* {:doc-bookmarks bookmarks})]
        (say! ctx (str "; settings: " err))
        (do (docs-pane/set-nav! docp {:history @history* :bookmarked? added?})
            (say! ctx (if added?
                        (str "; Bookmarked " (dp/page-label page) ".")
                        (str "; Removed the bookmark for " (dp/page-label page) "."))))))
    (say! ctx "; Nothing to bookmark: show a doc first.")))

(defn- list-in-palette!
  "Lists entries in the active tab's palette."
  [{:keys [active-tab]} entries]
  (palette/open! (:palette (active-tab)) entries))

(defn- bookmarks!
  "Doc Bookmarks…: the bookmarks in the palette."
  [ctx]
  (let [bs (saved ctx)]
    (if (empty? bs)
      (say! ctx "; No doc bookmarks yet: Bookmark This Page saves the page you are reading.")
      (list-in-palette! ctx (dp/bookmark-entries bs)))))

(defn- guide!
  "Open a Guide Page…: the guide's pages in the palette."
  [ctx]
  (if-let [dir (guide-directory)]
    (list-in-palette! ctx (dp/guide-entries (dp/guide-pages dir)))
    (say! ctx "; The guide isn't installed: Jonquil found no docs/guide beside its source.")))

(defn- page-from-palette!
  "The palette's [:docs/page key] entry: opens the page the key names."
  [ctx k]
  (if-let [page (dp/key->page k)]
    (open-page! ctx page)
    (say! ctx (str "; No doc page " (pr-str k) "."))))

;; --- the examples' buttons --------------------------------------------------

(defn- run-code!
  "Run in Interactions on an example's code, as the full-doc popover's button does."
  [{:keys [run-example! active-tab cv ev* sel* status-label]} code]
  (run-example! {:tab (active-tab) :cv cv :ev* ev* :sel* sel* :status-label status-label} code))

(defn- copy-example!
  "Copy to Definitions: the code goes into the active tab after the form at
  the caret, one undo step; a read-only tab says so."
  [{:keys [active-tab] :as ctx} code]
  (let [ed (:ed (active-tab))]
    (if (editor/apply-op-plain! ed (doc-examples/insert-op (editor/snapshot ed) code))
      (on-gui! #(when-not @(:destroyed? ed) (editor/focus! ed)))
      (say! ctx "; This tab is read-only."))))

(defn- install
  "The docs part for one window: builds the pane, wires its handlers and
  answers the commands' fns. The history (the last 50 pages) and the cache of
  what they showed are per window; the evaluator is read from ev* on every
  lookup."
  [{:keys [right hide-right-panel! show-right-panel! active-tab] :as wctx}]
  (let [docp (docs-pane/make-pane)
        ctx (assoc wctx :docp docp :history* (atom dp/empty-history) :contents* (atom {}) :seq* (atom 0))]
    (docs-pane/set-handlers!
     docp {:on-back (fn [] (step! ctx dp/back "Nothing earlier."))
           :on-forward (fn [] (step! ctx dp/forward "Nothing later."))
           :on-bookmark (fn [] (bookmark! ctx))
           :on-link (fn [sym] (open-symbol! ctx sym 0))
           :on-run (fn [code] (run-code! ctx code))
           :on-copy (fn [code] (copy-example! ctx code))
           :on-close (fn [] (hide-right-panel! right :docs) (editor/focus! (:ed (active-tab))))})
    {:fns {:docs-show-fn (fn [] (show! ctx))
           :docs-bookmark-fn (fn [] (bookmark! ctx))
           :docs-bookmarks-fn (fn [] (bookmarks! ctx))
           :docs-guide-fn (fn [] (guide! ctx))
           :docs-page-fn (fn [k] (page-from-palette! ctx k))}
     :ui {:docs-pane docp}
     :panels {:docs {:root (:root docp)
                     :focus! (fn [] (docs-pane/focus! docp))
                     :open! (fn [] (show-right-panel! right :docs) (docs-pane/focus! docp))}}}))

(def part
  "The docs part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :docs}))
