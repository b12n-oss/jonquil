(ns jonquil.docs-panel-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.docs-panel :as dp]))

(def ^:private map-page (dp/symbol-page "map"))
(def ^:private keys-page (dp/guide-page "keys"))

(deftest pages-test
  (is (= {:kind :symbol :id "map"} map-page))
  (is (= {:kind :guide :id "keys"} keys-page))
  (is (dp/valid-page? map-page))
  (is (dp/valid-page? keys-page))
  (doseq [bad [nil "map" {} {:kind :symbol} {:kind :symbol :id ""} {:kind :symbol :id "  "}
               {:kind :url :id "x"} {:kind :symbol :id 3}]]
    (is (not (dp/valid-page? bad)) (pr-str bad)))
  (is (= "map" (dp/page-label map-page)))
  (is (= "Guide: keys" (dp/page-label keys-page))))

(deftest page-key-round-trip-test
  (is (= "symbol:map" (dp/page-key map-page)))
  (is (= "guide:architecture/index" (dp/page-key (dp/guide-page "architecture/index"))))
  (is (= map-page (dp/key->page "symbol:map")))
  (is (= keys-page (dp/key->page "guide:keys")))
  (is (= (dp/symbol-page "a:b") (dp/key->page (dp/page-key (dp/symbol-page "a:b"))))
      "the first colon splits, so a symbol may hold one")
  (is (nil? (dp/key->page "nonsense")))
  (is (nil? (dp/key->page "url:x"))))

(deftest history-starts-empty-test
  (is (nil? (dp/current dp/empty-history)))
  (is (nil? (dp/back dp/empty-history)))
  (is (nil? (dp/forward dp/empty-history)))
  (is (= "" (dp/position-text dp/empty-history))))

(deftest push-back-forward-test
  (let [h (-> dp/empty-history (dp/push map-page) (dp/push keys-page) (dp/push (dp/symbol-page "filter")))]
    (is (= (dp/symbol-page "filter") (dp/current h)))
    (is (= "page 3 of 3" (dp/position-text h)))
    (is (nil? (dp/forward h)) "at the newest page there is no forward")
    (let [b (dp/back h)]
      (is (= keys-page (dp/current b)))
      (is (= (dp/symbol-page "filter") (dp/current (dp/forward b))))
      (is (= map-page (dp/current (dp/back b))))
      (is (nil? (dp/back (dp/back b))) "back stops at the oldest"))))

(deftest push-drops-the-forward-pages-test
  (let [h (-> dp/empty-history (dp/push map-page) (dp/push keys-page) (dp/push (dp/symbol-page "filter")))
        b (dp/back (dp/back h))
        h2 (dp/push b (dp/symbol-page "reduce"))]
    (is (= [map-page (dp/symbol-page "reduce")] (:items h2)))
    (is (nil? (dp/forward h2)))))

(deftest push-the-same-page-twice-adds-nothing-test
  (let [h (dp/push dp/empty-history map-page)]
    (is (= h (dp/push h map-page)))
    (is (= 2 (count (:items (dp/push h keys-page)))))
    (testing "the same page after another one is a new entry"
      (is (= 3 (count (:items (dp/push (dp/push h keys-page) map-page))))))))

(deftest history-is-capped-test
  (let [pages (map #(dp/symbol-page (str "s" %)) (range 60))
        h (reduce dp/push dp/empty-history pages)]
    (is (= 50 dp/max-history))
    (is (= 50 (count (:items h))))
    (is (= "s59" (:id (dp/current h))))
    (is (= "s10" (:id (first (:items h)))) "the oldest ten are gone")
    (is (= "page 50 of 50" (dp/position-text h)))
    (is (= "s10" (:id (dp/current (nth (iterate dp/back h) 49)))))
    (is (nil? (dp/back (nth (iterate dp/back h) 49))))))

(deftest bookmarks-test
  (let [b1 (dp/add-bookmark [] map-page)
        b2 (dp/add-bookmark b1 keys-page)]
    (is (= [map-page] b1))
    (is (= [map-page keys-page] b2))
    (is (= b2 (dp/add-bookmark b2 map-page)) "no duplicates")
    (is (dp/bookmarked? b2 keys-page))
    (is (not (dp/bookmarked? b1 keys-page)))
    (is (= [keys-page] (dp/remove-bookmark b2 map-page)))
    (is (= b2 (dp/remove-bookmark b2 (dp/symbol-page "nope"))))
    (is (= [map-page] (dp/add-bookmark nil map-page)) "nil reads as none")
    (is (= [] (dp/add-bookmark [] {:kind :bad})) "an invalid page is not added")))

(deftest toggle-bookmark-test
  (let [b (dp/toggle-bookmark [] map-page)]
    (is (= {:added? true :bookmarks [map-page]} b))
    (is (= {:added? false :bookmarks []} (dp/toggle-bookmark (:bookmarks b) map-page)))))

(deftest bookmark-entries-test
  (is (= [{:id [:docs/page "symbol:map"] :doc "Doc bookmark: map" :chords [] :source "doc"}
          {:id [:docs/page "guide:keys"] :doc "Doc bookmark: Guide: keys" :chords [] :source "doc"}]
         (dp/bookmark-entries [map-page keys-page])))
  (is (= [] (dp/bookmark-entries nil))))

(deftest symbol-at-test
  (let [text "See also: filter, remove.\n(map f xs) clojure.string/join :kw 12 ..."]
    (is (= "filter" (dp/symbol-at text 10)))
    (is (= "filter" (dp/symbol-at text 15)))
    (is (= "remove" (dp/symbol-at text 19)) "the sentence's period is not part of it")
    (is (nil? (dp/symbol-at text 16)) "on the comma")
    (is (= "map" (dp/symbol-at text 27)))
    (is (nil? (dp/symbol-at text 26)) "on the paren")
    (is (= "clojure.string/join" (dp/symbol-at text 40)))
    (is (nil? (dp/symbol-at text (str/index-of text ":kw"))) "a keyword has no docs")
    (is (nil? (dp/symbol-at text (str/index-of text "12"))) "a number has none either")
    (is (nil? (dp/symbol-at text (str/index-of text "..."))))
    (is (nil? (dp/symbol-at text 9)) "on a space")
    (is (nil? (dp/symbol-at text (count text))) "past the end")
    (is (nil? (dp/symbol-at text -1)))
    (is (nil? (dp/symbol-at "" 0)))
    (is (nil? (dp/symbol-at nil 0))))
  (testing "symbols with the usual punctuation"
    (is (= "swap!" (dp/symbol-at "use swap! here" 5)))
    (is (= "nil?" (dp/symbol-at "(nil? x)" 2)))
    (is (= "->>" (dp/symbol-at "the ->> macro" 5)))
    (is (= "*ns*" (dp/symbol-at "bind *ns* first" 6)))))

(deftest symbol-at-stays-fast-on-long-input-test
  (let [text (apply str (repeat 200000 "a"))
        t0 (System/nanoTime)
        r (dp/symbol-at text 100000)
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (is (= 200000 (count r)))
    (is (< ms 2000) (str ms " ms"))))

(deftest canonical-id-test
  (is (= "clojure.core/map" (dp/canonical-id "map" "clojure.core/map")))
  (is (= "map" (dp/canonical-id "map" nil)))
  (is (= "map" (dp/canonical-id "map" "")))
  (is (= "map" (dp/canonical-id "map" "two words")))
  (is (= "str/join" (dp/canonical-id "str/join" "clojure.string/join"))
      "a symbol that is already qualified stays as it was written"))

(deftest guide-text-headings-test
  (is (= "Keys\n====\n\nKeys by purpose.\n\nRun\n---\n\nText.\n\nDeeper\n\nMore."
         (dp/guide-text "# Keys\n\nKeys by purpose.\n\n## Run\n\nText.\n\n### Deeper\n\nMore.\n"))))

(deftest guide-text-inline-test
  (is (= "See the keys page and run it, bold."
         (dp/guide-text "See the [keys page](keys.md) and `run` it, **bold**.")))
  (is (= "Image: a logo" (dp/guide-text "Image: ![a logo](logo.png)")))
  (is (= "a < b and <name> stay" (dp/guide-text "a < b and <name> stay"))))

(deftest guide-text-html-test
  (is (= "Edge cases\n----------\n\nBody."
         (dp/guide-text "<details>\n<summary><strong>Edge cases</strong></summary>\n\nBody.\n\n</details>\n")))
  (is (= "Before\n\nAfter" (dp/guide-text "Before\n\n<!-- hidden -->\n\nAfter"))))

(deftest guide-text-code-and-tables-test
  (let [md "Run:\n\n```clojure\n# not a heading\n(+ 1 2) ; **kept**\n```\n\n| Key | Does |\n|---|---|\n| F5 | Run |\n"]
    (is (= "Run:\n\n    # not a heading\n    (+ 1 2) ; **kept**\n\n| Key | Does |\n| F5 | Run |"
           (dp/guide-text md)))))

(deftest guide-text-collapses-blank-runs-test
  (is (= "a\n\nb" (dp/guide-text "a\n\n\n\n\nb\n\n\n")))
  (is (= "" (dp/guide-text "")))
  (is (= "" (dp/guide-text nil))))

(deftest guide-text-lines-with-windows-endings-test
  (is (= "A\n=\n\nb" (dp/guide-text "# A\r\n\r\nb\r\n"))))

(deftest guide-text-long-line-stays-fast-test
  (let [md (str "[" (apply str (repeat 50000 "[")) " no close")
        t0 (System/nanoTime)
        _ (dp/guide-text md)
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (is (< ms 2000) (str ms " ms"))))

(defn- temp-dir []
  (let [d (java.io.File. (str (System/getProperty "java.io.tmpdir") "/docs-panel-test-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- put! [dir rel text]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f text)))

(deftest guide-pages-test
  (let [d (temp-dir)]
    (put! d "keys.md" "# Keys\n\nbody\n")
    (put! d "index.md" "# Jonquil guide\n")
    (put! d "architecture/index.md" "# How it fits together\n")
    (put! d "notes.txt" "not markdown")
    (put! d "plain.md" "no heading here\n")
    (is (= [{:id "architecture/index" :title "How it fits together"}
            {:id "index" :title "Jonquil guide"}
            {:id "keys" :title "Keys"}
            {:id "plain" :title "plain"}]
           (map #(select-keys % [:id :title]) (dp/guide-pages (str d)))))
    (is (every? #(.isFile (io/file (:path %))) (dp/guide-pages (str d))))
    (is (= "# Keys\n\nbody\n" (slurp (:path (some #(when (= "keys" (:id %)) %) (dp/guide-pages (str d)))))))))

(deftest guide-file-test
  (let [d (temp-dir)]
    (put! d "guide/keys.md" "# Keys")
    (put! d "guide/architecture/index.md" "# A")
    (put! d "secret.md" "# not in the guide")
    (let [g (str d "/guide")]
      (is (= (str (.getCanonicalPath (io/file g)) "/keys.md") (dp/guide-file g "keys")))
      (is (some? (dp/guide-file g "architecture/index")))
      (is (nil? (dp/guide-file g "../secret")) "an id that climbs out finds nothing")
      (is (nil? (dp/guide-file g "architecture/../../secret")))
      (is (nil? (dp/guide-file g "missing")))
      (is (nil? (dp/guide-file g "")))
      (is (nil? (dp/guide-file g nil)))
      (is (nil? (dp/guide-file nil "keys"))))))

(deftest guide-pages-without-a-directory-test
  (is (= [] (dp/guide-pages nil)))
  (is (= [] (dp/guide-pages "/no/such/dir/anywhere"))))

(deftest guide-entries-test
  (is (= [{:id [:docs/page "guide:keys"] :doc "Guide page: Keys (keys)" :chords [] :source "doc"}
          {:id [:docs/page "guide:plain"] :doc "Guide page: plain" :chords [] :source "doc"}]
         (dp/guide-entries [{:id "keys" :title "Keys" :path "/x/keys.md"}
                            {:id "plain" :title "plain" :path "/x/plain.md"}]))))

(deftest guide-dir-test
  (let [d (temp-dir)]
    (put! d "src/jonquil/keymap.edn" "[]")
    (put! d "docs/guide/index.md" "# g")
    (is (= (str (.getCanonicalPath d) "/docs/guide")
           (dp/guide-dir (str d "/src/jonquil/keymap.edn"))))
    (testing "a tree without the guide, or a missing path, has none"
      (let [e (temp-dir)]
        (put! e "src/jonquil/keymap.edn" "[]")
        (is (nil? (dp/guide-dir (str e "/src/jonquil/keymap.edn")))))
      (is (nil? (dp/guide-dir nil)))
      (is (nil? (dp/guide-dir "/no/such/file"))))))

(deftest the-real-guide-is-found-from-the-checkout-test
  ;; the checkout's own docs/guide, reached from a source file beside it
  (let [root (.getCanonicalPath (io/file "."))]
    (when (.isFile (io/file root "src/jonquil/keymap.edn"))
      (is (= (str root "/docs/guide") (dp/guide-dir (str root "/src/jonquil/keymap.edn"))))
      (is (<= 20 (count (dp/guide-pages (str root "/docs/guide")))))
      (doseq [{:keys [path]} (dp/guide-pages (str root "/docs/guide"))]
        (is (string? (dp/guide-text (slurp path))) path)))))
