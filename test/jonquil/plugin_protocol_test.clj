(ns jonquil.plugin-protocol-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as registry]
            [jonquil.keymap :as km]
            [jonquil.plugin :as p]
            [jonquil.plugin-check :as pc]
            [jonquil.plugin-protocol :as protocol]))

(defn- source
  "A plugin file for id, the spec's wrap-when with the J chord, whose :edit
  body is edit-body (a form over the snapshot s)."
  ([id] (source id "(p/wrap-form s \"(when \" \")\")"))
  ([id edit-body]
   (str "(ns jonquil.plugins." id "\n  (:require [jonquil.plugin :as p]))\n\n"
        "(p/defplugin\n"
        "  {:id :" id "\n"
        "   :doc \"Wrap the form at the caret in (when ...).\"\n"
        "   :effects #{}\n"
        "   :commands [{:id :" id "/wrap :doc \"Wrap the form at the caret in (when ...).\"\n"
        "               :scope :editor :edit (fn [s] " edit-body ")}]\n"
        "   :bindings [{:key \"j\" :mods #{:cmd :alt} :command :" id "/wrap}]})\n")))

(defn- load! [src file-id] (protocol/load-candidate {:source src :file-id file-id}))

(deftest load-good-test
  (let [r (load! (source "p2-good") "p2-good")]
    (is (= [] (:problems r)))
    (is (= [:p2-good] (map :id (:definitions r))))))

(deftest load-errors-test
  (let [r (load! "(ns jonquil.plugins.p2-eof\n  (:require [jonquil.plugin :as p]))\n\n(p/defplugin {:id :p2-eof\n" "p2-eof")]
    (is (= 1 (count (:problems r))) (pr-str r))
    ;; the reader reports EOF where it ran out: the line after the open form
    (is (str/starts-with? (first (:problems r)) "line 5: ") (pr-str r)))
  (let [r (load! "(ns jonquil.plugins.p2-unres\n  (:require [jonquil.plugin :as p]))\n\n\n(p/defplugin {:id (nope-fn)})\n" "p2-unres")]
    (is (= 1 (count (:problems r))) (pr-str r))
    (is (str/starts-with? (first (:problems r)) "line 5: ") (pr-str r)))
  (let [r (load! "(ns jonquil.plugins.p2-throw)\n(throw (ex-info \"boom\" {}))\n" "p2-throw")]
    (is (= ["loading the file threw: boom"] (:problems r)) (pr-str r))))

(deftest wrong-namespace-test
  (let [r (load! (str/replace (source "p2-ns") "ns jonquil.plugins.p2-ns" "ns jonquil.plugins.p2-other") "p2-ns")]
    (is (= 1 (count (:problems r))) (pr-str r))
    (is (str/includes? (first (:problems r)) "jonquil.plugins.p2-other")))
  (let [r (load! "(+ 1 2)\n" "p2-nons")]
    (is (= 1 (count (:problems r))) (pr-str r))
    (is (str/includes? (first (:problems r)) "jonquil.plugins.p2-nons"))))

(deftest load-leaves-caller-alone-test
  (let [before *ns*]
    (load! (source "p2-alone") "p2-alone")
    (load! "(ns jonquil.plugins.p2-alone2)\n(throw (ex-info \"x\" {}))" "p2-alone2")
    (is (= before *ns*))
    (is (nil? p/*definitions*))))

;; --- the soak and run-protocol ---------------------------------------------

(def ^:private request
  (pc/request-from-registry {:commands registry/commands
                             :conditions (into (set (keys registry/window-conditions))
                                               (keys registry/editor-conditions))
                             :keymap km/shipped :os "Linux"}))

(defn- definition [edit]
  {:id :p2-soak :doc "d" :effects #{}
   :commands [{:id :p2-soak/e :doc "d" :scope :editor :edit edit}]})

(def ^:private text "(defn f [x] (inc x))")

(deftest edit-result-shapes-test
  (let [s {:text text :caret 0 :sel-start 0 :sel-end 0}]
    (is (nil? (protocol/edit-result-problem s nil)))
    (is (nil? (protocol/edit-result-problem s {:edits [[0 0 "x"]] :caret 1})))
    (doseq [r [5
               {:edits [[0 99 "x"]] :caret 0}
               {:edits [[3 4 "a"] [1 2 "b"]] :caret 0}
               {:edits [[0 2 "a"] [1 3 "b"]] :caret 0}
               {:edits [[0 0 :x]] :caret 0}
               {:edits [] :caret -1}
               {:edits [] :caret 0 :sel [0 99]}]]
      (is (string? (protocol/edit-result-problem s r)) (pr-str r)))))

(deftest soak-good-test
  (let [r (protocol/soak {:definition (definition (fn [s] (p/wrap-form s "(when " ")")))})]
    (is (= [] (:problems r)))
    (is (number? (get (:timings r) :p2-soak/e)))
    (is (<= 0 (get (:timings r) :p2-soak/e)))))

(deftest soak-throw-test
  (let [r (protocol/soak {:definition (definition (fn [_] (throw (ex-info "kaboom" {}))))})]
    (is (= (count protocol/FIXTURES) (count (:problems r))))
    (is (every? #(and (str/includes? % ":p2-soak/e") (str/includes? % "kaboom")) (:problems r)))
    (is (str/includes? (first (:problems r)) (ffirst protocol/FIXTURES)))))

(deftest soak-timeout-test
  (let [stop (atom false)
        t0 (System/currentTimeMillis)
        r (try (protocol/soak {:definition (definition (fn [_] (loop [] (when-not @stop (recur)))))
                               :soak-ms 100})
               (finally (reset! stop true)))
        ms (- (System/currentTimeMillis) t0)]
    ;; one problem: after its first timeout a command's other fixtures are
    ;; skipped, so at most one runaway thread per command
    (is (= 1 (count (:problems r))) (pr-str r))
    (is (str/includes? (first (:problems r)) "still running"))
    (is (str/includes? (first (:problems r)) "weren't tried"))
    (is (< ms 1000) (str ms " ms"))))

(deftest soak-lazy-result-test
  ;; a lazy result is checked and printed inside the timed call, with
  ;; printing bounded, so an infinite seq can't hang the caller
  (let [guard (future (protocol/soak {:definition (definition (fn [_] (range))) :soak-ms 200}))
        r (deref guard 10000 ::hung)]
    (is (not= ::hung r) "the soak returned")
    (when (map? r)
      (is (= (count protocol/FIXTURES) (count (:problems r))))
      (is (every? #(< (count %) 300) (:problems r)) "each message is short"))))

(deftest run-commands-not-invoked-test
  (let [ran (atom 0)
        d {:id :p2-run-cmd :doc "d" :effects #{:console}
           :commands [{:id :p2-run-cmd/say :doc "d" :scope :window
                       :run (fn [_ _] (swap! ran inc) (throw (ex-info "no" {})))}]}
        r (protocol/soak {:definition d})]
    (is (= [] (:problems r)))
    (is (zero? @ran))))

(defn- run [src file-id & {:as more}]
  (protocol/run-protocol (merge {:where :in-process :source src :file-id file-id :request request} more)))

(deftest run-protocol-test
  (let [r (run (source "p2-run") "p2-run")]
    (is (true? (:ok? r)) (pr-str r))
    (is (= :p2-run (:id (:definition r))))
    (is (contains? (:timings r) :p2-run/wrap)))
  (let [r (run (str/replace (source "p2-noeff") "   :effects #{}\n" "") "p2-noeff")]
    (is (false? (:ok? r)))
    (is (some #(str/includes? % ":effects") (:problems r)) (pr-str r))
    (is (= {} (:timings r)) "the soak didn't run")
    (is (nil? (:definition r))))
  (let [r (run "(ns jonquil.plugins.p2-bad\n" "p2-bad")]
    (is (false? (:ok? r)))
    (is (str/starts-with? (first (:problems r)) "line ")))
  (let [r (run (source "p2-iso") "p2-iso" :where :isolated)]
    (is (false? (:ok? r)))
    (is (= 1 (count (:problems r))))))

(def stop-spin (atom false))

(deftest load-deadline-test
  ;; a file that loops at top level is a problem after :load-ms, so the
  ;; files after it still load (review I1)
  (reset! stop-spin false)
  (let [t0 (System/currentTimeMillis)
        r (try (protocol/load-candidate
                {:source "(ns jonquil.plugins.p2-spin)\n(loop [] (when-not @jonquil.plugin-protocol-test/stop-spin (recur)))\n"
                 :file-id "p2-spin" :load-ms 200})
               (finally (reset! stop-spin true)))
        ms (- (System/currentTimeMillis) t0)]
    (is (= 1 (count (:problems r))) (pr-str r))
    (is (str/includes? (first (:problems r)) "didn't finish"))
    (is (< ms 2000) (str ms " ms"))))
