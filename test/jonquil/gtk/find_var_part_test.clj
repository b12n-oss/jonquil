(ns jonquil.gtk.find-var-part-test
  "Find a Var by Name (jonquil.gtk.parts.find-var) with the evaluator, the
  palette and the window's jump and doc fns stubbed: what each answer of
  the query does, and where Return and Shift+Return go."
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.gtk.callbacks]
            [jonquil.gtk.parts.find-var :as part]))

(def ^:private find-var! @#'part/find-var!)
(def ^:private open-var! @#'part/open-var!)
(def ^:private show-var-doc! @#'part/show-var-doc!)

(def ^:private value
  "{:vars [{:ns \"demo.found\" :name \"needle-fn\" :arglists [\"[x]\"] :file \"src/demo/found.clj\" :line 3 :ns-file \"src/demo/found.clj\"}] :dropped 0}")

(defn- ctx
  "A find-var ctx over log: the query answers with notify-map (or nothing
  when nil), the palette and status calls are logged, tab* holds the active
  tab."
  [log {:keys [dialect notify-map tab*] :or {dialect :jolt tab* (atom {:id 1})}}]
  {:sel* (atom {:profile {:id dialect :name "Babashka"} :project-dir "/p"})
   :set-status! (fn [s] (swap! log conj [:status s]))
   :query! (fn [code notify] (swap! log conj [:query (string? code)]) (when notify-map (notify notify-map)))
   :active-tab (fn [] @tab*)
   :open-palette! (fn [tab entries] (swap! log conj [:palette (:id tab) (mapv :name entries)]))
   :files (fn [_] ["/p/src/demo/found.clj"])})

(defn- run [opts]
  (with-redefs [jonquil.gtk.callbacks/on-gui! (fn [f] (f))]
    (let [log (atom [])]
      (find-var! (ctx log opts))
      @log)))

(defn- ok [printed] {:done {:result {:status :ok :printed (pr-str printed)} :output ""}})

(deftest refuses-unsupported-dialect
  (is (= [[:status "; Find a Var by Name works with Jolt and JVM Clojure. This project is Babashka."]]
         (run {:dialect :bb}))))

(deftest busy-runtime-says-so
  (is (= [[:query true] [:status "; Find a Var by Name: the runtime is busy. Try again when it's done."]]
         (run {:notify-map {:busy "; Busy."}}))))

(deftest query-error-says-so
  (testing "an error ends the query"
    (is (= [[:query true] [:status "; Find a Var by Name: Error [ex]: boom"]]
           (run {:notify-map {:done {:result {:status :error :error-text "Error [ex]: boom\nmore"}}}}))))
  (testing "an answer that doesn't read back"
    (is (= [[:query true] [:status "; Find a Var by Name: the runtime's answer couldn't be read."]]
           (run {:notify-map (ok "{:vars")})))))

(deftest empty-list-says-so
  (is (= [[:query true] [:status "No vars are loaded."]]
         (run {:notify-map (ok "{:vars [] :dropped 0}")}))))

(deftest lists-the-vars
  (is (= [[:query true] [:palette 1 ["needle-fn"]] [:status "Ready"]]
         (run {:notify-map (ok value)}))))

(deftest late-answer-uses-the-active-tab
  (with-redefs [jonquil.gtk.callbacks/on-gui! (fn [f] (f))]
    (testing "a tab switch while the query ran: the list opens in the tab active now"
      (let [log (atom []) tab* (atom {:id 1}) notify* (atom nil)
            c (assoc (ctx log {:tab* tab*}) :query! (fn [_ notify] (reset! notify* notify)))]
        (find-var! c)
        (reset! tab* {:id 2})
        (@notify* (ok value))
        (is (= [[:palette 2 ["needle-fn"]] [:status "Ready"]] @log))))
    (testing "no tab left: nothing opens and nothing throws"
      (let [log (atom []) tab* (atom {:id 1}) notify* (atom nil)
            c (assoc (ctx log {:tab* tab*}) :query! (fn [_ notify] (reset! notify* notify)))]
        (find-var! c)
        (reset! tab* nil)
        (@notify* (ok value))
        (is (= [] @log))))))

(defn- open-ctx [log doc]
  {:sel* (atom {:profile {:id :jolt} :project-dir "/p"})
   :set-status! (fn [s] (swap! log conj [:status s]))
   :active-tab (fn [] {:id 1})
   :source-ctx (fn [t] {:tab (:id t)})
   :files (fn [_] ["/p/src/demo/found.clj"])
   :open-file-line! (fn [sc label path line] (swap! log conj [:open sc label path line]))
   :ask-lookup! (fn [_ {:keys [sym on-doc]}] (swap! log conj [:lookup sym]) (on-doc doc))
   :jump-to! (fn [sc sym target] (swap! log conj [:jump sc sym target]))
   :show-doc-for! (fn [sc sym] (swap! log conj [:doc sc sym]))})

(deftest open-in-project-file
  (let [log (atom [])]
    (open-var! (open-ctx log nil) {:ns "demo.found" :name "needle-fn" :file "src/demo/found.clj" :line 3})
    (is (= [[:open {:tab 1} "demo.found/needle-fn" "/p/src/demo/found.clj" 3]] @log))))

(deftest open-falls-back-to-lookup-then-doc
  (testing "a library var: the lookup's answer goes to jump-to!, which opens its place or shows its doc"
    (let [log (atom []) doc {:status :found :file "clojure/core.clj"}]
      (open-var! (open-ctx log doc) {:ns "clojure.core" :name "map" :file "clojure/core.clj" :line 2})
      (is (= [[:lookup "clojure.core/map"]
              [:jump {:tab 1} "clojure.core/map" {:kind :external :doc doc}]]
             @log))))
  (testing "a var defined at the prompt: no file, so the lookup too"
    (let [log (atom [])]
      (open-var! (open-ctx log {:status :found}) {:ns "user" :name "x" :file nil :line nil})
      (is (= [:lookup "user/x"] (first @log)))))
  (testing "no answer from the lookup"
    (let [log (atom [])]
      (open-var! (open-ctx log nil) {:ns "user" :name "x" :file nil :line nil})
      (is (= [[:lookup "user/x"] [:status "No definition found for user/x."]] @log)))))

(deftest doc-shows-the-lookup
  (let [log (atom [])]
    (show-var-doc! (open-ctx log nil) "demo.found/needle-fn")
    (is (= [[:doc {:tab 1} "demo.found/needle-fn"]] @log))))

(deftest asks-for-a-larger-list-and-says-when-cut
  (with-redefs [jonquil.gtk.callbacks/on-gui! (fn [f] (f))]
    (let [log (atom []) code* (atom nil)
          c (assoc (ctx log {}) :query! (fn [code notify] (reset! code* code)
                                          (notify (ok "{:vars [{:ns \"a\" :name \"b\" :arglists []}] :dropped 7}"))))]
      (find-var! c)
      (is (clojure.string/includes? @code* "50000") "the command's own cap, not the MCP tools' 5000")
      (is (= [:status "Listing the first 50000 vars: 7 more are loaded. Own vars past the cut may be missing."]
             (last @log))))))
