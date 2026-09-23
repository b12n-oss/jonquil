;; Names and output aren't limited to ASCII. 안녕 is a valid Clojure symbol,
;; and printing an emoji works exactly like printing anything else.
(defn 안녕 [이름]
  (str "안녕하세요, " 이름 "님! 👋"))

(println (안녕 "친구"))
(println "🎉 Jonquil supports Unicode end to end.")
