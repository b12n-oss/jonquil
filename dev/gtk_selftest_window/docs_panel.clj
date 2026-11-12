(ns gtk-selftest-window.docs-panel
  "LM6's docs panel: Cmd/Ctrl+Alt+9 shows the doc of the symbol at the caret,
  a click on a symbol in the page follows it, [ and ] move through the
  history, Bookmark saves into the settings file and Doc Bookmarks opens
  one, a guide page opens from the palette, and an example's buttons run and
  copy. In process, so the doc is quick doc's text."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.doc-examples :as doc-examples]
            [jonquil.docs-panel :as dp]
            [jonquil.gtk.docs-pane :as docs-pane]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]
            [jonquil.settings :as settings]))

(defn- panel-key!
  "Cmd/Ctrl+Alt+digit through the window's key handler."
  [ui digit]
  ((:handle-window-key! ui) ffi/null (int digit) 0 (bit-or g/META-MASK g/ALT-MASK) ffi/null))

(defn- vis? [w] (pos? (g/gtk-widget-get-visible w)))

(defn- title [ui] (docs-pane/title-text (:docs-pane ui)))

(defn- view [ui] (docs-pane/view-text (:docs-pane ui)))

(defn- saved [ui] (:doc-bookmarks (settings/load-settings ((:settings-path* ui)))))

(defn- handler [ui k] (k @(:handlers (:docs-pane ui))))

(defn- open-list!
  "Runs the palette command found by query, which lists entries in the same
  palette; returns the docs it lists."
  [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)
    (palette/shown-docs p)))

(defn- pick!
  "Runs the listed entry whose doc contains needle."
  [ui needle]
  (let [p (:palette ui)]
    (palette/set-query! p needle)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn- clear-bookmarks! [ui]
  (settings/save-settings! ((:settings-path* ui)) {:doc-bookmarks []}))

(defn row-docs-panel-show-follow-back-bookmark
  "Show Doc in Panel (Cmd/Ctrl+Alt+9) opens the panel on the doc of map;
  a click on the qualified name in the page follows it; [ and ] move through
  the two pages; Bookmark writes :doc-bookmarks and Doc Bookmarks opens the
  bookmark again; Close hides the panel."
  [ui]
  (go-row :docs-panel-show-follow-back-bookmark support/check
    (let [pane (:docs-pane ui)
          _ (clear-bookmarks! ui)
          _ (on-gtk (editor/set-text! (:editor ui) "(map inc [1 2 3])")
                    (support/place-cursor! (:buf (:editor ui)) 2)
                    (panel-key! ui \9))
          shown? (await-until 3000 20 #(and (vis? (:root pane)) (str/starts-with? (title ui) "map")))
          text1 (on-gtk (view ui))
          has-name? (str/includes? text1 "clojure.core/map")
          at (when has-name? (str/index-of text1 "clojure.core/map"))
          followed (on-gtk (when at (docs-pane/follow-offset! pane (+ at 3))))
          followed? (await-until 3000 20 #(str/includes? (title ui) "page 2 of 2"))
          _ (on-gtk (docs-pane/handle-key! pane 0x5b))
          back? (await-until 3000 20 #(and (str/starts-with? (title ui) "map") (str/includes? (title ui) "page 1 of 2")))
          _ (on-gtk (docs-pane/handle-key! pane 0x5d))
          fwd? (await-until 3000 20 #(and (str/starts-with? (title ui) "clojure.core/map") (str/includes? (title ui) "page 2 of 2")))
          _ (on-gtk ((handler ui :on-bookmark)))
          bookmarked? (await-until 3000 20 #(= [(dp/symbol-page "clojure.core/map")] (saved ui)))
          label (docs-pane/bookmark-label pane)
          _ (on-gtk (docs-pane/handle-key! pane 0x5b))
          _ (await-until 3000 20 #(str/starts-with? (title ui) "map"))
          listed (on-gtk (open-list! ui "doc bookmarks"))
          _ (on-gtk (pick! ui "clojure.core/map"))
          reopened? (await-until 3000 20 #(and (str/starts-with? (title ui) "clojure.core/map") (str/includes? (title ui) "page 2 of 2")))
          _ (on-gtk ((handler ui :on-close)))
          hidden? (await-until 3000 20 #(not (vis? (:root pane))))]
      (on-gtk (clear-bookmarks! ui)
              (support/check :docs-panel-show-follow-back-bookmark
                             (and shown? has-name? (= "clojure.core/map" followed) followed? back? fwd?
                                  bookmarked? (= "Bookmarked" label) (= ["Doc bookmark: clojure.core/map"] listed)
                                  reopened? hidden?)
                             {:shown shown? :name has-name? :followed followed :followed-title followed? :back back?
                              :fwd fwd? :bookmarked bookmarked? :label label :listed listed :reopened reopened?
                              :hidden hidden? :title (title ui)})))))

(defn row-docs-panel-guide-page
  "Open a Guide Page lists the checkout's guide, picking Keys shows the page
  as plain text with its heading underlined, and Bookmark This Page saves it
  as a guide bookmark."
  [ui]
  (go-row :docs-panel-guide-page support/check
    (let [pane (:docs-pane ui)
          _ (clear-bookmarks! ui)
          listed (on-gtk (open-list! ui "open a guide page"))
          has-keys? (boolean (some #{"Guide page: Keys (keys)"} listed))
          _ (on-gtk (pick! ui "(keys)"))
          shown? (await-until 3000 20 #(and (vis? (:root pane)) (str/starts-with? (title ui) "Guide: keys")))
          text (on-gtk (view ui))
          plain? (and (str/starts-with? text "Keys\n====") (not (str/includes? text "<details>")))
          _ (on-gtk ((handler ui :on-bookmark)))
          saved? (await-until 3000 20 #(= [(dp/guide-page "keys")] (saved ui)))
          _ (on-gtk ((handler ui :on-close)))]
      (on-gtk (clear-bookmarks! ui)
              (support/check :docs-panel-guide-page (and (<= 20 (count listed)) has-keys? shown? plain? saved?)
                             {:listed (count listed) :has-keys has-keys? :shown shown? :plain plain? :saved saved?
                              :head (subs text 0 (min 60 (count text)))})))))

(def ^:private example-text
  "clojure.core/inc\n([x])\n  Returns a number one greater than num.\n\n== clojure.core/inc\n\n= example 1\n\n(inc 41)\n;;=> 42\n")

(defn row-docs-panel-example-buttons
  "A page with a ClojureDocs example shows its Run and Copy buttons; Run
  evaluates the code in the console and Copy puts it in the editor."
  [ui]
  (go-row :docs-panel-example-buttons support/check
    (let [pane (:docs-pane ui)
          {:keys [doc examples]} (doc-examples/split example-text)
          mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (editor/set-text! (:editor ui) "(def a 1)")
                    (support/place-cursor! (:buf (:editor ui)) 9)
                    (docs-pane/show! pane {:title "clojure.core/inc" :text doc :examples examples
                                           :history (dp/push dp/empty-history (dp/symbol-page "inc"))
                                           :bookmarked? false}))
          buttons (on-gtk (docs-pane/example-buttons pane))
          both? (and (= 1 (count buttons)) (some? (:run (first buttons))) (some? (:copy (first buttons))))
          _ (on-gtk ((handler ui :on-run) (:code (first examples))))
          ran? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "42")))
          _ (on-gtk ((handler ui :on-copy) (:code (first examples))))
          copied? (await-until 3000 20 #(str/includes? (editor/editor-text (:editor ui)) "(inc 41)"))]
      (on-gtk (support/check :docs-panel-example-buttons (and both? ran? copied?)
                             {:buttons (count buttons) :ran ran? :copied copied?
                              :editor (editor/editor-text (:editor ui))})))))
