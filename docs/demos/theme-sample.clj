;; A sample of the palette, so all four themes show some of their colours
;; side by side.
(defn greet [name]
  (let [info {:kind :greeting :count (+ 1 2)}]
    (println "Hi," name)
    info))

(greet "Jonquil")
