(ns jonquil.plugin-protocol-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.plugin :as p]
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
