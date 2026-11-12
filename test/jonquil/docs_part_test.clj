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
  (let [spec (some #(when (= :reader (:id %)) %) parts/specs)]
    (is (= #{:pages/show :pages/bookmark :pages/bookmarks :pages/guide}
           (set (keys (:commands spec)))))
    (is (= [:docs-show-fn] (get-in spec [:commands :pages/show :call])))
    (is (= [{:id :reader :label "Reader"}] (:panels spec)))
    (is (every? #(contains? registry/window-commands %) (keys (:commands spec))))
    (is (= "Reader" (:reader palette/PANEL-LABELS)))
    (is (= :reader (:part/id (meta (nth gtk-parts/installs (- (count gtk-parts/installs) 2))))))))

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
        {:fns (:fns c) :hooks (:hooks c) :cache* (get-in c [:ui :docs-cache*])
         :panels-contribution (:panels c) :handlers handlers :shown shown :said said
         :panels panels :lookups lookups :ev* (:ev* wctx) :settings-path path :listed listed
         :navs navs}))))

(deftest contributes-its-fns-ui-and-panel
  (let [h (harness {})]
    (is (= #{:docs-show-fn :docs-bookmark-fn :docs-bookmarks-fn :docs-guide-fn :docs-page-fn}
           (set (keys (:fns h)))))
    (is (= :root (get-in h [:panels-contribution :reader :root])))
    (is (fn? (get-in h [:panels-contribution :reader :open!])))))

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
    (is (= [:reader] @(:panels h)) "the panel opens")
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
    (is (not (some #{:reader} @(:panels h))) "the panel stays as it was")))

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
    (is (some #(re-find #"No bookmarked pages yet" %) @(:said h)))
    (is (empty? @(:listed h)))
    (run-with h (fn [] ((:on-link @on) "map") ((:on-bookmark @on)) ((:docs-bookmarks-fn (:fns h)))))
    (is (= [[:pal ["Bookmarked page: map"]]] @(:listed h)))))

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
    (is (some #(re-find #"The guide has no page \"no/such/page\"" %) @(:said h)))))

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
    (is (= [:reader :focus] @(:panels h)))
    (is (some #{"No symbol at the caret."} @(:said h)))))

(deftest close-hides-the-panel
  (let [h (harness {})]
    (with-redefs [editor/focus! (fn [_] (swap! (:panels h) conj :editor-focus))]
      ((:on-close @(:handlers h))))
    (is (= [[:hide :reader] :editor-focus] @(:panels h)))))

(defn- pending-ev
  "A FullDoc evaluator that holds each answer back: pending is an atom of
  [sym on-result] pairs."
  [pending]
  (reify ev/FullDoc
    (full-doc [_ sym _ on-result] (swap! pending conj [sym on-result]))))

(def ^:private ok-text "x text")

(deftest a-late-answer-is-dropped-after-back-a-guide-page-close-or-quit
  (doseq [[label interrupt]
          [["back" (fn [h] ((:on-back @(:handlers h))))]
           ["a guide page" (fn [h] ((:docs-page-fn (:fns h)) "guide:using-jonquil"))]
           ["close" (fn [h] (with-redefs [editor/focus! (fn [_])] ((:on-close @(:handlers h)))))]
           ["quit" (fn [h] ((get-in h [:hooks :on-quit])))]]]
    (let [pending (atom [])
          h (harness {:ev (pending-ev pending)})]
      (run-with h (fn [] ((:docs-page-fn (:fns h)) "guide:index") ((:docs-page-fn (:fns h)) "guide:keys")))
      (let [landed (count @(:shown h))
            panels (count @(:panels h))]
        (run-with h (fn []
                      ((:on-link @(:handlers h)) "late")
                      (interrupt h)
                      (let [[_ answer] (first @pending)] (answer {:status :ok :text ok-text}))))
        (is (not-any? #(= "late" (:title %)) @(:shown h)) (str "after " label ": the late answer didn't land"))
        (when (#{"close" "quit"} label)
          (is (= landed (count @(:shown h))) (str "after " label ": nothing shown at all"))
          (is (not-any? #{:reader} (drop panels @(:panels h))) (str "after " label ": the panel stays closed")))))))

(deftest back-to-an-uncached-symbol-keeps-the-pages-ahead
  (let [h (harness {})
        on (:handlers h)]
    (run-with h (fn [] (doseq [s ["a" "b" "c"]] ((:on-link @on) s))
                  ((:on-back @on)) ((:on-back @on))))
    (reset! (:cache* h) {})
    (run-with h (fn [] ((:on-forward @on))))
    (let [page (last @(:shown h))]
      (is (= "b" (:title page)))
      (is (= "page 2 of 3" (dp/position-text (:history page))) "the page ahead is still there"))
    (run-with h (fn [] ((:on-forward @on))))
    (is (= "c" (:title (last @(:shown h)))))))

(deftest a-bookmark-list-that-cannot-be-read-whole-is-never-rewritten
  (let [h (harness {})
        on (:handlers h)
        path (:settings-path h)
        bad (pr-str {:theme :navy :doc-bookmarks [{:kind :symbol :id "keep"} {:kind :url :id "x"}]})]
    (spit path bad)
    (run-with h (fn [] ((:on-link @on) "map") ((:on-bookmark @on))))
    (is (= bad (slurp path)) "the file is untouched")
    (is (some #(re-find #"Bookmarks not changed: .*:doc-bookmarks" %) @(:said h)))
    (run-with h (fn [] ((:docs-bookmarks-fn (:fns h)))))
    (is (some #(re-find #"Bookmarks can't be listed" %) @(:said h)))
    (is (empty? @(:listed h)))
    (testing "a file that isn't EDN is refused too"
      (spit path "{:doc-bookmarks [")
      (run-with h (fn [] ((:on-bookmark @on))))
      (is (= "{:doc-bookmarks [" (slurp path))))
    (testing "a good file takes the toggle again"
      (spit path (pr-str {:doc-bookmarks [{:kind :symbol :id "keep"}]}))
      (run-with h (fn [] ((:on-bookmark @on))))
      (is (= [(dp/symbol-page "keep") (dp/symbol-page "map")]
             (:doc-bookmarks (settings/load-settings path)))))))

(deftest rendering-does-not-read-the-settings-file
  (let [h (harness {})
        on (:handlers h)
        reads (atom 0)
        real settings/read-doc-bookmarks]
    (run-with h (fn [] ((:on-link @on) "a") ((:on-bookmark @on))))
    (with-redefs [settings/read-doc-bookmarks (fn [p] (swap! reads inc) (real p))
                  settings/load-settings (fn [_] (swap! reads inc) {})]
      (run-with h (fn [] ((:on-link @on) "b") ((:on-back @on)) ((:on-forward @on)) ((:on-back @on))))
      (is (zero? @reads))
      (is (true? (:bookmarked? (last @(:shown h)))) "the in-memory set knows a is bookmarked"))))

(deftest an-odd-guide-id-says-so-instead-of-throwing
  (let [h (harness {})]
    (doseq [id ["guide:a\u0000b" "guide:../../etc/passwd" "guide:"]]
      (run-with h (fn [] ((:docs-page-fn (:fns h)) id))))
    (is (empty? @(:shown h)))
    (is (<= 2 (count (filter #(re-find #"guide|page" %) @(:said h)))))))
