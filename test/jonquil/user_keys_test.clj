(ns jonquil.user-keys-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.user-keys :as uk]))

(def ^:private opts
  {:commands {:run/run {:scope :window} :run/selection {:scope :window}
              :edit/wrap {:scope :editor}}
   :conditions {:window #{:evaluating} :editor #{}}})

(deftest keys-path-test
  (is (= "/c/jonquil/keys.edn" (uk/keys-path "/c/jonquil/settings.edn"))))

(deftest read-keys-test
  (testing "a good file"
    (let [r (uk/read-keys "{:window [{:key \"e\" :mods #{:cmd} :command :run/selection}]
                            :editor [{:key \"j\" :mods #{:cmd :alt} :command :edit/wrap}]
                            :remove [{:key \"r\" :mods #{:cmd} :command :run/run}]}" opts)]
      (is (= [] (:problems r)))
      (is (= [{:key "e" :mods #{:cmd} :command :run/selection}] (:window r)))
      (is (= [{:key "j" :mods #{:cmd :alt} :command :edit/wrap}] (:editor r)))
      (is (= [{:key "r" :mods #{:cmd} :command :run/run}] (:remove r)))))
  (testing "a binding needs no Cmd or Ctrl"
    (is (= [] (:problems (uk/read-keys "{:window [{:key \"F9\" :command :run/run}]}" opts)))))
  (testing "a key that types, alone or with only Shift, would replace typing, so it is refused"
    (doseq [b ["{:key \"e\" :command :run/run}"
               "{:key \"e\" :mods #{:shift} :command :run/run}"
               "{:key \"7\" :command :run/run}"
               "{:key \"e\" :exact #{} :command :run/run}"
               "{:keys #{\"F9\" \"e\"} :command :run/run}"
               "{:key \"Return\" :command :run/run}"
               "{:key \"space\" :mods #{:shift} :command :run/run}"
               "{:key \"period\" :command :run/run}"]]
      (let [r (uk/read-keys (str "{:window [" b "]}") opts)]
        (is (= [] (:window r)) b)
        (is (str/includes? (str (first (:problems r))) "would replace typing") b)))
    (doseq [b ["{:key \"e\" :mods #{:cmd} :command :run/run}"
               "{:key \"e\" :mods #{:alt :shift} :command :run/run}"
               "{:key \"e\" :exact #{:ctrl} :command :run/run}"
               "{:key \"F9\" :mods #{:shift} :command :run/run}"
               "{:key \"Escape\" :command :run/run}"]]
      (is (= [] (:problems (uk/read-keys (str "{:window [" b "]}") opts))) b)))
  (testing "an empty file and an empty map load nothing, quietly"
    (is (= (assoc uk/no-keys :problems []) (uk/read-keys "" opts)))
    (is (= (assoc uk/no-keys :problems []) (uk/read-keys "{}" opts))))
  (testing "a bad entry is skipped and named, the rest load"
    (let [r (uk/read-keys "{:window [{:key \"e\" :mods #{:cmd} :command :no/such}
                                     {:key \"F9\" :command :run/run}]}" opts)]
      (is (= [{:key "F9" :command :run/run}] (:window r)))
      (is (= 1 (count (:problems r))))
      (is (str/includes? (first (:problems r)) ":window entry 1"))
      (is (str/includes? (first (:problems r)) ":no/such"))))
  (testing "a command of the other scope is refused"
    (let [r (uk/read-keys "{:window [{:key \"j\" :mods #{:cmd} :command :edit/wrap}]}" opts)]
      (is (= [] (:window r)))
      (is (str/includes? (first (:problems r)) "an editor command"))))
  (testing "a scope that isn't a vector, an entry that isn't a map, an unknown key"
    (is (= 1 (count (:problems (uk/read-keys "{:window {:key \"e\"}}" opts)))))
    (is (= 1 (count (:problems (uk/read-keys "{:window [42]}" opts)))))
    (is (str/includes? (first (:problems (uk/read-keys "{:windows []}" opts))) ":windows")))
  (testing "a :remove entry needs a key and a command"
    (let [r (uk/read-keys "{:remove [{:key \"r\"} {:key \"r\" :mods #{:cmd} :command :run/run}]}" opts)]
      (is (= [{:key "r" :mods #{:cmd} :command :run/run}] (:remove r)))
      (is (str/includes? (first (:problems r)) ":remove entry 1"))))
  (testing "text that doesn't read loads nothing and says so"
    (let [r (uk/read-keys "{:window [" opts)]
      (is (= uk/no-keys (dissoc r :problems)))
      (is (= 1 (count (:problems r))))
      (is (str/includes? (first (:problems r)) "couldn't be read"))))
  (testing "a file that isn't a map"
    (is (str/includes? (first (:problems (uk/read-keys "[1 2]" opts))) "a map"))))

(deftest layer-test
  (let [base [{:key "r" :mods #{:cmd} :command :run/run}
              {:key "F5" :command :run/run}
              {:keys #{"Return" "KP_Enter"} :mods #{:cmd} :when [:evaluating] :command :run/selection}]]
    (testing "no user keys leaves the base as it is"
      (is (= base (uk/layer uk/no-keys :window base))))
    (testing "user bindings come first"
      (let [u (assoc uk/no-keys :window [{:key "e" :mods #{:cmd} :command :run/selection}])]
        (is (= (into (:window u) base) (uk/layer u :window base)))
        (is (= base (uk/layer u :editor base)) "another scope's bindings stay out")))
    (testing ":remove drops the base binding with that chord and command"
      (let [u (assoc uk/no-keys :remove [{:key "r" :mods #{:cmd} :command :run/run}])]
        (is (= (vec (rest base)) (uk/layer u :window base)))))
    (testing ":remove leaves a binding with other modifiers, another key or another command"
      (doseq [r [{:key "r" :mods #{:cmd :shift} :command :run/run}
                 {:key "x" :mods #{:cmd} :command :run/run}
                 {:key "r" :mods #{:cmd} :command :run/selection}]]
        (is (= base (uk/layer (assoc uk/no-keys :remove [r]) :window base)) (pr-str r))))
    (testing ":remove matches a :keys binding by its whole key set, whatever its :when"
      (let [u (assoc uk/no-keys :remove [{:keys #{"KP_Enter" "Return"} :mods #{:cmd} :command :run/selection}])]
        (is (= (vec (take 2 base)) (uk/layer u :window base)))))
    (testing ":remove never drops the user's own bindings"
      (let [b {:key "r" :mods #{:cmd} :command :run/run}
            u (assoc uk/no-keys :window [b] :remove [b])]
        (is (= (into [b] (rest base)) (uk/layer u :window base)))))))

(deftest load-keys-test
  (let [dir (java.nio.file.Files/createTempDirectory "jonquil-user-keys" (make-array java.nio.file.attribute.FileAttribute 0))
        path (str dir "/keys.edn")]
    (try
      (testing "no file is not a problem"
        (is (= {:status :absent :keys uk/no-keys :problems []} (uk/load-keys path opts))))
      (testing "a file loads"
        (spit path "{:window [{:key \"F9\" :command :run/run}]}")
        (let [r (uk/load-keys path opts)]
          (is (= :loaded (:status r)))
          (is (= [{:key "F9" :command :run/run}] (:window (:keys r))))
          (is (= [] (:problems r)))))
      (testing "the template reads clean and binds nothing"
        (spit path uk/template)
        (is (= {:status :loaded :keys uk/no-keys :problems []} (uk/load-keys path opts))))
      (finally
        (.delete (java.io.File. path))
        (.delete (.toFile dir))))))

(deftest report-lines-test
  (is (= [] (uk/report-lines "/c/keys.edn" {:status :absent :keys uk/no-keys :problems []})))
  (is (= ["; /c/keys.edn: 2 key bindings of yours loaded."]
         (uk/report-lines "/c/keys.edn" {:status :loaded :problems []
                                         :keys (assoc uk/no-keys :window [{} {}])})))
  (is (= ["; /c/keys.edn: 1 key binding of yours loaded, 1 removed."]
         (uk/report-lines "/c/keys.edn" {:status :loaded :problems []
                                         :keys (assoc uk/no-keys :editor [{}] :remove [{}])})))
  (is (= ["; /c/keys.edn: no key bindings of yours loaded."
          "; /c/keys.edn: :window entry 1 names :no/such, which isn't a command; skipped"]
         (uk/report-lines "/c/keys.edn"
                          {:status :loaded :keys uk/no-keys
                           :problems [":window entry 1 names :no/such, which isn't a command; skipped"]}))))
