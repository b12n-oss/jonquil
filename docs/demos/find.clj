;; Cmd+F opens the find bar. Search for a word that appears more than
;; once, like "jonquil" below, and every match highlights at once.
(defn jonquil-greeting [name]
  (str "Hello from Jonquil, " name "!"))

(println (jonquil-greeting "jonquil"))
