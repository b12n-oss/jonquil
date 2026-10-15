(ns jonquil.gtk.appearance-test
  "Under `bb test` GTK isn't initialised, so only the contract is checked
  here: a boolean or nil, never a throw. The window self-test asserts a
  real boolean."
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.appearance :as appearance]))

(deftest system-dark-is-boolean-or-nil-test
  (let [r (appearance/system-dark?)]
    (is (or (nil? r) (true? r) (false? r)))))
