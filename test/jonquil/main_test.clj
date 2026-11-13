(ns jonquil.main-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.window :as window]
            [jonquil.main :as main]))

(deftest gtk-argv-test
  (is (= ["jonquil" "/w/a.clj" "/w/sub"] (main/gtk-argv ["a.clj" "sub"] "/w")))
  (is (= ["jonquil" "/x/b.clj"] (main/gtk-argv ["/x/b.clj"] "/w")))
  (is (= ["jonquil" "/w/-x.clj" "/w/--help"] (main/gtk-argv ["-x.clj" "--help"] "/w")))
  (is (= ["jonquil"] (main/gtk-argv [] "/w"))))

(deftest launch-split-test
  (let [dir? (fn [p] (= "/d" p))]
    (is (= {:launch {} :rest []} (window/launch-split [] dir?)))
    (is (= {:launch {:dir "/d"} :rest ["/x.clj" "/y.clj"]} (window/launch-split ["/d" "/x.clj" "/y.clj"] dir?)))
    (is (= {:launch {:file "/f.clj"} :rest ["/d"]} (window/launch-split ["/f.clj" "/d"] dir?)))
    (is (= {:launch {:file "/missing.clj"} :rest []} (window/launch-split ["/missing.clj"] dir?)))))

(deftest version-arg-test
  (is (true? (main/version-arg? ["--version"])))
  (is (true? (main/version-arg? ["--version" "x.clj"])))
  (is (false? (main/version-arg? [])))
  (is (false? (main/version-arg? ["a.clj" "--version"])) "only the first arg counts")
  (is (false? (main/version-arg? ["--mcp"]))))

(deftest mcp-args-test
  (let [cwd (System/getProperty "user.dir")]
    (is (= {:dir cwd :window? false} (main/mcp-args ["--mcp"])))
    (is (= {:dir (str cwd "/proj") :window? false} (main/mcp-args ["--mcp" "proj"])))
    (is (= {:dir "/abs/p" :window? false} (main/mcp-args ["--mcp" "/abs/p"])))
    (is (= {:dir cwd :window? true} (main/mcp-args ["--mcp" "--window"])))
    (is (= {:dir (str cwd "/proj") :window? true} (main/mcp-args ["--mcp" "--window" "proj"])))
    (is (nil? (main/mcp-args ["--window"])))
    (is (nil? (main/mcp-args ["a.clj"])))
    (is (nil? (main/mcp-args [])))))

(deftest attach-args-test
  (let [cwd (System/getProperty "user.dir")]
    (is (= {:dir nil} (main/attach-args ["--mcp-attach"])))
    (is (= {:dir (str cwd "/proj")} (main/attach-args ["--mcp-attach" "proj"])))
    (is (= {:dir "/abs/p"} (main/attach-args ["--mcp-attach" "/abs/p"])))
    (is (nil? (main/attach-args ["proj" "--mcp-attach"])))
    (is (nil? (main/attach-args ["--mcp"])))
    (is (nil? (main/attach-args [])))))
