;; With unsaved changes, Cmd+W (or Cmd+Q) doesn't just close the window --
;; it asks first. Save writes the file and closes, Don't Save closes
;; without writing, and Cancel leaves everything open and untouched.
(defn petal-count [flower]
  (get {:jonquil 6 :daisy 21} flower))

(println "A jonquil has" (petal-count :jonquil) "petals.")
