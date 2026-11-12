(ns jonquil.docs-part-test
  "LM6's docs panel part with its pane and the editor stubbed: the history,
  the cache Back and Forward read, the bookmarks in the settings file, the
  full-doc path with its examples, and the commands' messages."
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.docs-panel :as dp]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :as callbacks]
            [jonquil.gtk.docs-pane :as docs-pane]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.palette :as gtk-palette]
            [jonquil.gtk.parts :as gtk-parts]
            [jonquil.gtk.parts.docs :as docs-part]
            [jonquil.palette :as palette]
            [jonquil.parts :as parts]
            [jonquil.settings :as settings]))

(deftest the-spec-names-four-commands-and-a-panel
  (let [spec (some #(when (= :docs (:id %)) %) parts/specs)]
    (is (= #{:doc-panel/show :doc-panel/bookmark :doc-panel/bookmarks :doc-panel/guide}
           (set (keys (:commands spec)))))
    (is (= [:docs-show-fn] (get-in spec [:commands :doc-panel/show :call])))
    (is (= [{:id :docs :label "Doc Pages"}] (:panels spec)))
    (is (every? #(contains? registry/window-commands %) (keys (:commands spec))))
    (is (= "Doc Pages" (:docs palette/PANEL-LABELS)))
    (is (= :docs (:part/id (meta (nth gtk-parts/installs (- (count gtk-parts/installs) 2))))))))

(def ^:private fake-ed {:destroyed? (atom false)})

(defn- temp-settings []
  (let [d (java.io.File. (str (System/getProperty "java.io.tmpdir") "/docs-part-test-" (System/nanoTime)))]
    (.mkdirs d)
    (str d "/settings.edn")))

(def ^:private full-text
  "clojure.core/map\n([f coll])\n  Returns a lazy seq.\n\n== clojure.core/map\n\n= example 1\n\n(map inc [1 2 3])\n;;=> (2 3 4)\n")

(defn- harness
  "Installs the part over stubs; returns {:fns :handlers :shown :said :panels
  :lookups :ev* :settings-path}. lookup is (fn [sym]) -> a lookup answer."
  [{:keys [ev lookup caret] :or {lookup (fn [sym] {:status :found :name sym :doc (str "doc of " sym)})}}]
  (let [shown (atom []) said (atom []) panels (atom []) lookups (atom []) handlers (atom {})
        listed (atom []) navs (atom [])
        path (temp-settings)
        wctx {:ev* (atom (or ev :plain)) :status-label :lbl
              :active-tab (fn [] {:ed fake-ed :palette :pal})
              :ask-lookup! (fn [_ {:keys [sym on-doc]}] (swap! lookups conj sym) (on-doc (lookup sym)))
              :set-status! #(swap! said conj %)
              :settings-path* (atom path)
              :right {:recent-panels (atom [])}
              :show-right-panel! (fn [_ k] (swap! panels conj k))
              :hide-right-panel! (fn [_ k] (swap! panels conj [:hide k]))
              :run-example! (fn [ctx code] (swap! lookups conj [:run code (select-keys ctx [:tab])]))
              :cv :cv :sel* (atom nil)}]
    (with-redefs [docs-pane/make-pane (fn [] {:root :root})
                  docs-pane/set-handlers! (fn [_ m] (reset! handlers m))
                  docs-pane/show! (fn [_ page] (swap! shown conj page))
                  docs-pane/set-nav! (fn [_ m] (swap! navs conj m))
                  docs-pane/report! (fn [_ m] (swap! said conj m))
                  docs-pane/focus! (fn [_] (swap! panels conj :focus))
                  editor/editor-text (fn [_] "(map inc xs)")
                  editor/snapshot (fn [_] {:text (or caret "(map inc xs)") :caret 2 :sel-start 2 :sel-end 2})
                  gtk-palette/open! (fn [pal entries] (swap! listed conj [pal (mapv :doc entries)]))
                  callbacks/on-gui! (fn [f] (f))]
      (let [c ((deref #'docs-part/install) wctx)]
        {:fns (:fns c) :panels-contribution (:panels c) :handlers handlers :shown shown :said said
         :panels panels :lookups lookups :ev* (:ev* wctx) :settings-path path :listed listed
         :navs navs}))))

(deftest contributes-its-fns-ui-and-panel
  (let [h (harness {})]
    (is (= #{:docs-show-fn :docs-bookmark-fn :docs-bookmarks-fn :docs-guide-fn :docs-page-fn}
           (set (keys (:fns h)))))
    (is (= :root (get-in h [:panels-contribution :docs :root])))
    (is (fn? (get-in h [:panels-contribution :docs :open!])))))

(defn- run-with
  "Calls f with the harness's atoms rebound as the macro expects, with a
  caret on a symbol."
  [h f]
  (let [{:keys [shown navs said panels listed]} h
        snap {:text "(map inc xs)" :caret 2 :sel-start 2 :sel-end 2}]
    (with-redefs [docs-pane/show! (fn [_ page] (swap! shown conj page))
                  docs-pane/set-nav! (fn [_ m] (swap! navs conj m))
                  docs-pane/report! (fn [_ m] (swap! said conj m))
                  docs-pane/focus! (fn [_] (swap! panels conj :focus))
                  editor/editor-text (fn [_] "(map inc xs)")
                  editor/snapshot (fn [_] snap)
                  gtk-palette/open! (fn [pal entries] (swap! listed conj [pal (mapv :doc entries)]))
                  callbacks/on-gui! (fn [f] (f))]
      (f))))

(deftest show-doc-puts-the-symbol-at-the-caret-in-the-panel
  (let [h (harness {})]
    (run-with h (fn [] ((:docs-show-fn (:fns h)))))
    (is (= ["map"] @(:lookups h)))
    (is (= [:docs] @(:panels h)) "the panel opens")
    (let [page (last @(:shown h))]
      (is (= "map" (:title page)))
      (is (re-find #"doc of map" (:text page)))
      (is (= [] (:examples page)) "quick doc has no examples")
      (is (= "page 1 of 1" (dp/position-text (:history page)))))))

(deftest a-symbol-with-no-docs-says-so-and-shows-nothing
  (let [h (harness {:lookup (fn [_] {:status :not-found})})]
    (run-with h (fn [] ((:docs-show-fn (:fns h)))))
    (is (empty? @(:shown h)))
    (is (some #{"No docs for map."} @(:said h)))
    (is (not (some #{:docs} @(:panels h))) "the panel stays as it was")))

(deftest following-links-back-and-forward-read-the-cache
  (let [h (harness {})
        on (:handlers h)]
    (run-with h (fn []
                  ((:on-link @on) "map")
                  ((:on-link @on) "filter")
                  ((:on-link @on) "reduce")))
    (is (= ["map" "filter" "reduce"] @(:lookups h)))
    (is (= "page 3 of 3" (dp/position-text (:history (last @(:shown h))))))
    (run-with h (fn [] ((:on-back @on))))
    (let [page (last @(:shown h))]
      (is (= "filter" (:title page)))
      (is (re-find #"doc of filter" (:text page)) "the cached text")
      (is (= "page 2 of 3" (dp/position-text (:history page)))))
    (is (= ["map" "filter" "reduce"] @(:lookups h)) "back asked nothing again")
    (run-with h (fn [] ((:on-forward @on))))
    (is (= "reduce" (:title (last @(:shown h)))))
    (testing "the ends say so"
      (run-with h (fn [] ((:on-forward @on))))
      (is (= "Nothing later." (last @(:said h))))
      (run-with h (fn [] ((:on-back @on)) ((:on-back @on)) ((:on-back @on))))
      (is (= "Nothing earlier." (last @(:said h)))))
    (testing "a link after going back drops the pages ahead"
      (run-with h (fn [] ((:on-link @on) "into")))
      (is (= "page 2 of 2" (dp/position-text (:history (last @(:shown h)))))))))

(deftest the-history-keeps-fifty-pages-and-the-cache-follows-it
  (let [h (harness {})
        on (:handlers h)]
    (run-with h (fn [] (doseq [i (range 60)] ((:on-link @on) (str "s" i)))))
    (is (= "page 50 of 50" (dp/position-text (:history (last @(:shown h))))))
    (run-with h (fn [] (dotimes [_ 49] ((:on-back @on)))))
    (is (= "s10" (:title (last @(:shown h)))) "the oldest kept page")
    (is (= 60 (count @(:lookups h))) "going back 49 pages looked nothing up")))

(deftest bookmarking-saves-and-removes-in-the-settings-file
  (let [h (harness {})
        on (:handlers h)
        saved #(:doc-bookmarks (settings/load-settings (:settings-path h)))]
    (run-with h (fn [] ((:on-bookmark @on))))
    (is (some #{"; Nothing to bookmark: show a doc first."} @(:said h)))
    (is (nil? (saved)))
    (run-with h (fn [] ((:on-link @on) "map") ((:on-bookmark @on))))
    (is (= [(dp/symbol-page "map")] (saved)))
    (is (= {:history (:history (last @(:shown h))) :bookmarked? true}
           (update (last @(:navs h)) :history identity)))
    (is (some #{"; Bookmarked map."} @(:said h)))
    (run-with h (fn [] ((:on-link @on) "filter") ((:on-bookmark @on))))
    (is (= [(dp/symbol-page "map") (dp/symbol-page "filter")] (saved)))
    (testing "the shown page knows it is bookmarked"
      (run-with h (fn [] ((:on-back @on))))
      (is (true? (:bookmarked? (last @(:shown h)))))
      (run-with h (fn [] ((:on-bookmark @on))))
      (is (= [(dp/symbol-page "filter")] (saved)))
      (is (some #{"; Removed the bookmark for map."} @(:said h))))))

(deftest the-bookmarks-list-opens-in-the-palette
  (let [h (harness {})
        on (:handlers h)]
    (run-with h (fn [] ((:docs-bookmarks-fn (:fns h)))))
    (is (some #(re-find #"No doc bookmarks yet" %) @(:said h)))
    (is (empty? @(:listed h)))
    (run-with h (fn [] ((:on-link @on) "map") ((:on-bookmark @on)) ((:docs-bookmarks-fn (:fns h)))))
    (is (= [[:pal ["Doc bookmark: map"]]] @(:listed h)))))

(deftest a-palette-page-entry-opens-its-page
  (let [h (harness {})]
    (run-with h (fn [] ((:docs-page-fn (:fns h)) "symbol:reduce")))
    (is (= ["reduce"] @(:lookups h)))
    (is (= "reduce" (:title (last @(:shown h)))))
    (run-with h (fn [] ((:docs-page-fn (:fns h)) "bogus")))
    (is (some #(re-find #"No doc page" %) @(:said h)))))

(deftest full-doc-lands-with-its-examples-and-its-qualified-name
  (let [asked (atom [])
        e (reify ev/FullDoc
            (full-doc [_ sym text on-result]
              (swap! asked conj [sym text])
              (on-result {:status :ok :text full-text})))
        h (harness {:ev e})]
    (run-with h (fn [] ((:docs-show-fn (:fns h)))))
    (is (= [["map" "(map inc xs)"]] @asked))
    (is (empty? @(:lookups h)) "the quick doc lookup isn't used")
    (let [page (last @(:shown h))]
      (is (= "clojure.core/map" (:title page)) "the qualified name from the title")
      (is (= "clojure.core/map\n([f coll])\n  Returns a lazy seq." (:text page)))
      (is (= [1] (map :n (:examples page)))))
    (run-with h (fn [] ((:on-bookmark @(:handlers h)))))
    (is (= [(dp/symbol-page "clojure.core/map")]
           (:doc-bookmarks (settings/load-settings (:settings-path h)))))))

(deftest full-doc-statuses-and-the-quick-doc-fallback
  (let [answer (atom {:status :not-found})
        e (reify ev/FullDoc
            (full-doc [_ _ _ on-result] (on-result @answer)))
        h (harness {:ev e})]
    (run-with h (fn [] ((:docs-show-fn (:fns h)))))
    (is (some #{"No full docs: the server doesn't know map."} @(:said h)))
    (is (empty? @(:shown h)))
    (reset! answer {:status :timeout})
    (run-with h (fn [] ((:docs-show-fn (:fns h)))))
    (is (some #{"Full docs for map timed out."} @(:said h)))
    (reset! answer {:status :no-op})
    (run-with h (fn [] ((:docs-show-fn (:fns h)))))
    (is (= ["map"] @(:lookups h)) "a server without the op falls back to quick doc")
    (is (re-find #"doc of map" (:text (last @(:shown h)))))))

(deftest a-late-full-doc-answer-for-an-older-lookup-is-dropped
  (let [pending (atom [])
        e (reify ev/FullDoc
            (full-doc [_ sym _ on-result] (swap! pending conj [sym on-result])))
        h (harness {:ev e})
        on (:handlers h)]
    (run-with h (fn [] ((:on-link @on) "map") ((:on-link @on) "filter")))
    (let [[[_ first-answer] [_ second-answer]] @pending]
      (run-with h (fn [] (second-answer {:status :ok :text "filter text"})
                    (first-answer {:status :ok :text "map text"}))))
    (is (= ["filter"] (map :title @(:shown h))) "only the newest lookup lands")))

(deftest example-buttons-run-and-copy
  (let [h (harness {})]
    (run-with h (fn [] ((:on-run @(:handlers h)) "(+ 1 2)")))
    (is (= [[:run "(+ 1 2)" {:tab {:ed fake-ed :palette :pal}}]] @(:lookups h)))))

(deftest copy-example-inserts-in-the-active-tab
  (let [h (harness {})
        applied (atom [])]
    (with-redefs [editor/apply-op-plain! (fn [ed op] (swap! applied conj [ed op]) true)
                  editor/focus! (fn [_])]
      (run-with h (fn [] ((:on-copy @(:handlers h)) "(+ 1 2)"))))
    (is (= 1 (count @applied)))
    (is (= fake-ed (ffirst @applied)))
    (with-redefs [editor/apply-op-plain! (fn [_ _] false)]
      (run-with h (fn [] ((:on-copy @(:handlers h)) "(+ 1 2)"))))
    (is (= "; This tab is read-only." (last @(:said h))))))

(deftest the-guide-lists-and-opens-from-the-checkout
  (let [h (harness {})]
    (run-with h (fn [] ((:docs-guide-fn (:fns h)))))
    (let [[[pal docs]] @(:listed h)]
      (is (= :pal pal))
      (is (<= 20 (count docs)))
      (is (some #{"Guide page: Keys (keys)"} docs)))
    (run-with h (fn [] ((:docs-page-fn (:fns h)) "guide:keys")))
    (let [page (last @(:shown h))]
      (is (= "Guide: keys" (:title page)))
      (is (re-find #"(?m)^Keys\n====" (:text page)))
      (is (= [] (:examples page))))
    (run-with h (fn [] ((:docs-page-fn (:fns h)) "guide:no/such/page")))
    (is (some #(re-find #"The guide has no page no/such/page" %) @(:said h)))))

(deftest guide-links-follow-symbols-too
  (let [h (harness {})]
    (run-with h (fn [] ((:docs-page-fn (:fns h)) "guide:keys")
                  ((:on-link @(:handlers h)) "inspect")))
    (is (= ["inspect"] @(:lookups h)))
    (is (= "inspect" (:title (last @(:shown h)))))
    (run-with h (fn [] ((:on-back @(:handlers h)))))
    (is (= "Guide: keys" (:title (last @(:shown h)))) "back reaches the guide page again")))

(deftest no-symbol-at-the-caret-opens-the-panel-as-it-was
  (let [h (harness {:caret "   "})]
    (with-redefs [editor/snapshot (fn [_] {:text "   " :caret 1 :sel-start 1 :sel-end 1})
                  docs-pane/show! (fn [_ p] (swap! (:shown h) conj p))
                  docs-pane/report! (fn [_ m] (swap! (:said h) conj m))
                  docs-pane/focus! (fn [_] (swap! (:panels h) conj :focus))]
      ((:docs-show-fn (:fns h))))
    (is (= [:docs :focus] @(:panels h)))
    (is (some #{"No symbol at the caret."} @(:said h)))))

(deftest close-hides-the-panel
  (let [h (harness {})]
    (with-redefs [editor/focus! (fn [_] (swap! (:panels h) conj :editor-focus))]
      ((:on-close @(:handlers h))))
    (is (= [[:hide :docs] :editor-focus] @(:panels h)))))
