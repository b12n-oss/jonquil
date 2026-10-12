(ns jonquil.tool-path-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.tool-path :as tp]))

(def ^:private home "/Users/t")

(deftest needs-lookup-test
  (testing "a Finder launch's PATH needs the lookup"
    (is (true? (tp/needs-lookup? "/usr/bin:/bin:/usr/sbin:/sbin" home))))
  (testing "a PATH holding a well-known dir does not"
    (is (false? (tp/needs-lookup? "/usr/bin:/opt/homebrew/bin" home)))
    (is (false? (tp/needs-lookup? "/Users/t/.local/bin:/usr/bin" home))))
  (testing "an empty PATH needs the lookup"
    (is (true? (tp/needs-lookup? "" home)))))

(deftest well-known-dirs-test
  (is (= ["/opt/homebrew/bin" "/usr/local/bin" "/Users/t/.local/bin" "/Users/t/bin"]
         (tp/well-known-dirs home))))

(deftest shell-command-test
  (is (= ["/bin/zsh" "-il" "-c" "printf '\\n__JONQUIL_PATH__%s__END__\\n' \"$PATH\""]
         (tp/shell-command "/bin/zsh")))
  (is (= ["/bin/zsh" "-il" "-c" "printf '\\n__JONQUIL_PATH__%s__END__\\n' \"$PATH\""]
         (tp/shell-command nil)))
  (is (= ["/bin/zsh" "-il" "-c" "printf '\\n__JONQUIL_PATH__%s__END__\\n' \"$PATH\""]
         (tp/shell-command "  "))))

(deftest parse-answer-test
  (testing "a clean answer"
    (is (= "/opt/homebrew/bin:/usr/bin"
           (tp/parse-answer "\n__JONQUIL_PATH__/opt/homebrew/bin:/usr/bin__END__\n"))))
  (testing "banner lines before and after the markers"
    (is (= "/a:/b"
           (tp/parse-answer "Welcome\n\n__JONQUIL_PATH__/a:/b__END__\nbye\n"))))
  (testing "the last marker pair wins"
    (is (= "/new"
           (tp/parse-answer "__JONQUIL_PATH__/old__END__\n__JONQUIL_PATH__/new__END__\n"))))
  (testing "no markers gives nil"
    (is (nil? (tp/parse-answer "/usr/bin:/bin\n"))))
  (testing "an empty answer gives nil"
    (is (nil? (tp/parse-answer "\n__JONQUIL_PATH____END__\n")))))

(deftest fallback-path-test
  (let [exists? #{"/opt/homebrew/bin" "/Users/t/.local/bin"}]
    (testing "adds only the existing well-known dirs, in order"
      (is (= "/usr/bin:/bin:/opt/homebrew/bin:/Users/t/.local/bin"
             (tp/fallback-path "/usr/bin:/bin" home exists?))))
    (testing "skips dirs already on the PATH"
      (is (= "/opt/homebrew/bin:/usr/bin:/Users/t/.local/bin"
             (tp/fallback-path "/opt/homebrew/bin:/usr/bin" home exists?))))
    (testing "nothing existing leaves the current PATH as is"
      (is (= "/usr/bin" (tp/fallback-path "/usr/bin" home (constantly false)))))))

(deftest resolve-program-test
  (let [files #{"/opt/homebrew/bin/jolt" "/usr/local/bin/jolt" "/usr/local/bin/jank"}
        exists? files]
    (testing "the first PATH entry holding the name wins"
      (is (= "/usr/local/bin/jolt"
             (tp/resolve-program "jolt" "/usr/bin:/usr/local/bin:/opt/homebrew/bin" exists?)))
      (is (= "/opt/homebrew/bin/jolt"
             (tp/resolve-program "jolt" "/opt/homebrew/bin:/usr/local/bin" exists?))))
    (testing "nil when no entry holds the name"
      (is (nil? (tp/resolve-program "clojure" "/usr/bin:/bin" exists?))))
    (testing "empty PATH entries are ignored"
      (is (= "/usr/local/bin/jank"
             (tp/resolve-program "jank" "::/usr/local/bin:" exists?))))
    (testing "an absolute name is returned as is when it exists"
      (is (= "/usr/local/bin/jank"
             (tp/resolve-program "/usr/local/bin/jank" "/usr/bin" exists?)))
      (is (nil? (tp/resolve-program "/nope/jank" "/usr/bin" exists?))))))
