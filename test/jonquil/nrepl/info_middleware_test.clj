(ns jonquil.nrepl.info-middleware-test
  "jonquil-info's handler, run in this process: in a Jolt child the same
  code answers jump to definition's lookups (jonquil.nrepl.evaluator)."
  (:require [clojure.data.json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.help :as help]
            [jonquil.nrepl.info-middleware :as mw]))

(deftest a-var-in-a-loaded-file-test
  (let [r (mw/info "jonquil.help" "prefix-at")]
    (is (= {"name" "prefix-at" "ns" "jonquil.help"} (select-keys r ["name" "ns"])))
    (is (str/includes? (get r "doc") "word prefix"))
    (is (str/starts-with? (get r "file") "/") "an absolute path, not the ./src/... Jolt records")
    (is (str/ends-with? (get r "file") "src/jonquil/help.clj"))
    (is (.isFile (io/file (get r "file"))))
    (is (pos-int? (get r "line")))
    (is (= (:line (meta #'help/prefix-at)) (get r "line")))))

(deftest a-var-from-a-jar-test
  (let [r (mw/info "user" "clojure.data.json/read-str")]
    (is (str/starts-with? (get r "file") "jar:file:"))
    (is (str/ends-with? (get r "file") "!/clojure/data/json.clj"))
    (is (pos-int? (get r "line")))))

(deftest built-into-jolt-test
  (let [r (mw/info "user" "map")]
    (is (= {"name" "map" "ns" "clojure.core"} (select-keys r ["name" "ns"])))
    (is (contains? r "arglists-str"))
    (is (not (contains? r "file")) "no file, so no line either")
    (is (not (contains? r "line")))))

(deftest resolution-test
  (testing "an alias in the namespace"
    (is (= "join" (get (mw/info "jonquil.nrepl.info-middleware" "str/join") "name"))))
  (testing "nothing there"
    (is (nil? (mw/info "user" "no-such-thing")))
    (is (nil? (mw/info "user" "")))
    (is (nil? (mw/info "user" "if")) "a special form isn't a var"))
  (testing "a namespace that isn't loaded resolves in user"
    (is (= "clojure.core" (get (mw/info "not.loaded.anywhere" "map") "ns")))))

(deftest handle-test
  (is (nil? (mw/handle {"op" "eval" "code" "1"})) "other ops pass on")
  (is (= ["done"] (get (mw/handle {"op" "jonquil-info" "sym" "nope" "ns" "user"}) "status")))
  (is (= #{"status"} (set (keys (mw/handle {"op" "jonquil-info" "sym" "nope" "ns" "user"})))) "a bare done")
  (is (= "map" (get (mw/handle {"op" "jonquil-info" "sym" "map" "ns" "user"}) "name"))))

(def odd-meta 1)

(deftest metadata-it-can-meet-test
  (testing "a bare classpath name isn't resolved against the directory (review finding 1)"
    ;; README.md exists in this directory, so resolving the bare name here
    ;; would name an unrelated file
    (is (.isFile (io/file "README.md")))
    (alter-meta! #'odd-meta assoc :file "README.md" :line 1)
    (is (not (contains? (mw/info "jonquil.nrepl.info-middleware-test" "odd-meta") "file"))))
  (testing "./ and absolute paths still resolve"
    (alter-meta! #'odd-meta assoc :file "./README.md")
    (is (= (.getCanonicalPath (io/file "README.md"))
           (get (mw/info "jonquil.nrepl.info-middleware-test" "odd-meta") "file"))))
  ;; No test for a var without :name or :ns: Jolt supplies both on every
  ;; meta read, even after an alter-meta! dissoc (measured, v0.8.23).
  )
