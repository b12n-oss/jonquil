;; A sample of the palette, so every theme shows some of its colours
;; side by side.
(defn greet [name]
  (let [info {:kind :greeting :count (+ 1 2)}]
    (println "Hi," name)
    info))

(greet "Jonquil")
