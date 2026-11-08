(ns jonquil.parts-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.parts :as parts]))

(defn- call [k & args]
  (fn [ctx _args] (apply (get ctx k) args) :handled))

(def ^:private go-spec
  {:id :p :commands {:p/go {:doc "Go." :scope :window :call [:go-fn 1]}}})

(deftest merge-commands-adds-a-spec-command
  (let [seen (atom nil)
        merged (parts/merge-commands {} [go-spec] :window call)
        cmd (:p/go merged)]
    (is (= "Go." (:doc cmd)))
    (is (not (contains? cmd :call)))
    (is (= :handled ((:run cmd) {:go-fn (fn [n] (reset! seen n))} nil)))
    (is (= 1 @seen))))

(deftest merge-commands-filters-by-scope
  (let [spec {:id :p :commands {:p/ed {:doc "Ed." :scope :editor :call [:ed-fn]}}}]
    (is (not (contains? (parts/merge-commands {} [spec] :window call) :p/ed)))
    (is (contains? (parts/merge-commands {} [spec] :editor call) :p/ed))))

(defn- message [f]
  (try (f) nil (catch Exception e (ex-message e))))

(deftest merge-commands-refuses-a-duplicate
  (testing "a spec id already in core"
    (is (= "duplicate command :p/go: :core and :p"
           (message #(parts/merge-commands {:p/go {:doc "x" :scope :window}} [go-spec] :window call)))))
  (testing "two specs"
    (let [other {:id :q :commands {:p/go {:doc "Again." :scope :window :call [:go-fn 2]}}}]
      (is (= "duplicate command :p/go: :p and :q"
             (message #(parts/merge-commands {} [go-spec other] :window call)))))))

(deftest check-clashes-refuses-a-cross-scope-duplicate
  (let [cores {:window {:run/run {:doc "x" :scope :window}}
               :editor {:edit/redo {:doc "y" :scope :editor}}}
        ed-spec (fn [id owner] {:id owner :commands {id {:doc "z" :scope :editor :call [:f]}}})]
    (testing "a spec :editor command reusing a core :window id"
      (is (= "duplicate command :run/run: :core and :p"
             (message #(parts/check-clashes cores [(ed-spec :run/run :p)])))))
    (testing "two specs in different scopes"
      (let [win {:id :q :commands {:x/y {:doc "w" :scope :window :call [:f]}}}
            ed {:id :p :commands {:x/y {:doc "z" :scope :editor :call [:f]}}}]
        (is (= "duplicate command :x/y: :q and :p"
               (message #(parts/check-clashes cores [win ed]))))))
    (testing "no clash passes"
      (is (nil? (parts/check-clashes cores [(ed-spec :p/new :p)]))))))

(deftest registry-tables-hold-the-specs
  (is (= [:replay] (mapv :id parts/specs)))
  (is (= #{:session/open-replay :session/forget-last}
         (set (filter (set (mapcat (comp keys :commands) parts/specs)) (keys registry/window-commands)))))
  (is (= (count registry/commands)
         (+ (count registry/window-commands)
            (count registry/editor-commands)
            (count registry/console-commands)))))

(defn- install-fn [id f] (with-meta f {:part/id id}))

(deftest install-merges-in-order
  (let [seen (atom nil)
        a (install-fn :a (fn [_] {:fns {:a-fn inc} :ui {:a-ui 1} :hooks {:on-quit :a-quit}
                                  :exports {:a-ex 10}}))
        b (install-fn :b (fn [wctx] (reset! seen (:a-ex wctx))
                           {:fns {:b-fn dec} :hooks {:on-quit :b-quit :on-start :b-start}}))
        c (parts/install [a b] {:win :w} (fn [& _] (throw (ex-info "no error expected" {}))))]
    (is (= 10 @seen))
    (is (= #{:a-fn :b-fn} (set (keys (:fns c)))))
    (is (= {:a-ui 1} (:ui c)))
    (is (= {:a-ex 10} (:exports c)))
    (is (= {:on-quit [:a-quit :b-quit] :on-start [:b-start]} (:hooks c)))))

(deftest install-refuses-duplicate-keys
  (let [a (install-fn :a (fn [_] {:ui {:shared 1}}))
        b (install-fn :b (fn [_] {:ui {:shared 2}}))]
    (is (= "duplicate ui key :shared: :a and :b"
           (message #(parts/install [a b] {} (fn [& _] nil)))))))

(deftest install-contains-a-throwing-part
  (let [errors (atom [])
        a (install-fn :a (fn [_] (throw (ex-info "boom" {}))))
        b (install-fn :b (fn [_] {:fns {:b-fn inc}}))
        c (parts/install [a b] {} (fn [id ex] (swap! errors conj [id (ex-message ex)])))]
    (is (= [[:a "boom"]] @errors))
    (is (= #{:b-fn} (set (keys (:fns c)))))))

(deftest install-names-an-unlabelled-part-by-index
  (let [errors (atom [])]
    (parts/install [(fn [_] (throw (ex-info "boom" {})))] {} (fn [id _] (swap! errors conj id)))
    (is (= [0] @errors))))

(deftest merge-with-core-refuses-a-core-key
  (let [c {:fns {:x 1} :owners {:fns {:x :p}}}]
    (is (= {:y 2 :x 1} (parts/merge-with-core :fns {:y 2} c)))
    (is (= "duplicate fns key :x: :core and :p"
           (message #(parts/merge-with-core :fns {:x 0} c))))))

(deftest run-hooks-isolates-a-throwing-hook
  (let [ran (atom []) errors (atom 0)
        errored (atom [])
        contribution {:hooks {:on-quit [(fn [x] (swap! ran conj [1 x]))
                                        (fn [_] (throw (ex-info "bad" {})))
                                        (fn [x] (swap! ran conj [3 x]))]}
                      :owners {:hooks {:on-quit [:a :b :c]}}}]
    (is (nil? (parts/run-hooks! contribution :on-quit [:arg] (fn [id _] (swap! errors inc) (swap! errored conj id)))))
    (is (= [[1 :arg] [3 :arg]] @ran))
    (is (= 1 @errors))
    (is (= [:b] @errored))
    (is (nil? (parts/run-hooks! contribution :on-start [] (fn [_ _] (swap! errors inc)))))))

(deftest missing-calls-names-unreachable-keys
  (let [cmds {:p/a {:doc "a" :scope :window :call [:here 1]}
              :p/b {:doc "b" :scope :window :call [:nope-fn]}
              :p/c {:doc "c" :scope :window}}]
    (is (= #{:nope-fn} (parts/missing-calls cmds #{:here})))
    (is (= #{} (parts/missing-calls cmds #{:here :nope-fn})))))
