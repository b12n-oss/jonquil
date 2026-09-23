;; read-line pauses the program and waits for you to type something into
;; the Interactions console below, then press Return. Press Run, then type
;; your name and press Return.
(println "What is your name?")
(let [name (read-line)]
  (str "Hello, " name "!"))
