;; Every syntax kind this highlighter colours, so all four themes show
;; their full palette side by side.
(defn greet [name]
  (let [info {:kind :greeting :count (+ 1 2)}]
    (println "Hi," name)
    info))

(greet "Jonquil")
