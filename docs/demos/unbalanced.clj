;; Jonquil turns a missing paren into a friendly message instead of a raw
;; exception. This file has one on purpose -- press Run and read what it
;; says instead of guessing from a stack trace.
(println "Two plus three is:")

(defn add [a b]
  (+ a b)

(println (add 2 3))
