(ns gtk-async-test
  "run-rows' row-timeout cancellation, without GTK: the parking helpers a
  row uses (here ga/sh) refuse to act for a row the driver has timed out,
  the throw fires once so the row's own finally still cleans up, and
  nothing outside a row is ever checked."
  (:require [clojure.core.async :as a :refer [<! go timeout]]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [gtk-async :as ga]
            [temp-dirs]))

(clojure.test/use-fixtures :once temp-dirs/fixture)

(defn- wait-for
  "True once (pred) holds, polling off the go thread; false at deadline-ms."
  [pred deadline-ms]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond (pred) true
            (> (- (System/currentTimeMillis) t0) deadline-ms) false
            :else (do (Thread/sleep 20) (recur))))))

(deftest an-overdue-row-is-abandoned-but-still-cleans-up-test
  (let [dir (temp-dirs/temp-dir! "gtk-async-test-")
        acted-file (str dir "/acted")
        cleaned-file (str dir "/cleaned")
        recs (atom []) acted (atom [])
        record! (fn [id ok detail] (swap! recs conj [id ok detail]))
        row1 (fn [] (ga/go-row :row1 record!
                      (try
                        (swap! acted conj :row1-start)
                        (<! (timeout 300))
                        (swap! acted conj :woke)
                        ;; past the 50 ms deadline: this first helper call
                        ;; must throw instead of touching the file
                        (ga/sh "touch" acted-file)
                        (swap! acted conj :row1-late)
                        (finally
                          ;; the throw fired once, so cleanup still runs
                          (ga/sh "touch" cleaned-file)
                          (swap! acted conj :cleaned)))))
        row2 (fn [] (ga/go-row :row2 record!
                      (swap! acted conj :row2)))
        done (promise)]
    (go (<! (ga/run-rows [row1 row2] 50 record!))
        (deliver done true))
    (is (true? (deref done 5000 false)) "the run finishes")
    (is (= [[:row-timeout false {:index 0 :timeout-ms 50}]]
           (filterv (fn [[id]] (= :row-timeout id)) @recs)))
    (is (wait-for #(some #{:woke} @acted) 3000) "the stale row woke up")
    (is (wait-for #(some #{:cleaned} @acted) 3000) "and its finally ran")
    (is (not-any? #(= :row1-late %) @acted) "but stopped at its first helper")
    (is (not (.exists (io/file acted-file))) "which never acted")
    (is (.exists (io/file cleaned-file)) "while cleanup's own helper did")
    (is (not-any? (fn [[id]] (= :row1 id)) @recs)
        "an abandoned row records nothing; the :row-timeout FAIL already told the story")))

(deftest a-row-stale-after-a-helper-started-is-stopped-on-its-way-out-test
  ;; the deadline fires while the row is INSIDE ga/sh: the check after the
  ;; hop throws, so what follows the helper never runs
  (let [recs (atom []) acted (atom [])
        record! (fn [id ok detail] (swap! recs conj [id ok detail]))
        row (fn [] (ga/go-row :in-flight record!
                     (ga/sh "sleep" "0.4")
                     (swap! acted conj :after-sh)))
        done (promise)]
    (go (<! (ga/run-rows [row] 100 record!))
        (deliver done true))
    (is (true? (deref done 5000 false)))
    (is (false? (wait-for #(seq @acted) 1500)) "nothing after the in-flight helper")))

(deftest an-overdue-last-row-is-abandoned-too-test
  ;; no next row bumps the counter, so the timeout itself must: the
  ;; driver's finish phase runs after the last row
  (let [recs (atom []) acted (atom [])
        record! (fn [id ok detail] (swap! recs conj [id ok detail]))
        row (fn [] (ga/go-row :last record!
                     (<! (timeout 300))
                     (swap! acted conj :woke)
                     (ga/sh "true")
                     (swap! acted conj :last-late)))
        done (promise)]
    (go (<! (ga/run-rows [row] 50 record!))
        (deliver done true))
    (is (true? (deref done 5000 false)))
    (is (wait-for #(some #{:woke} @acted) 3000) "the stale last row woke up")
    (Thread/sleep 100)
    (is (not-any? #(= :last-late %) @acted) "and stopped at its first helper")))

(deftest a-throw-that-is-not-abandonment-still-records-test
  (let [recs (atom [])
        record! (fn [id ok detail] (swap! recs conj [id ok detail]))
        row (fn [] (ga/go-row :boom record! (throw (ex-info "boom" {}))))
        done (promise)]
    (go (<! (ga/run-rows [row] 1000 record!))
        (deliver done true))
    (is (true? (deref done 5000 false)))
    (is (= [[:boom false {:threw "boom"}]] @recs))))

(deftest helpers-outside-a-row-are-untouched-test
  (let [done (promise)]
    (go (deliver done (:exit (ga/sh "true"))))
    (is (= 0 (deref done 5000 :timeout)))))

(deftest parking-helpers-refuse-the-gtk-thread-test
  ;; call-gtk always refused to run on the GTK thread (a take there freezes
  ;; the main loop); the other parking helpers refuse by construction too
  (with-redefs [ga/gtk-thread? (constantly true)]
    (doseq [[who f] [["sh" #(ga/sh "true")]
                     ["settle" #(ga/settle 1)]
                     ["call-gtk" #(ga/call-gtk (fn [] nil))]]]
      (let [done (promise)]
        (go (deliver done (try (f) ::no-throw
                               (catch :default e (ex-message e)))))
        (let [r (deref done 5000 :timeout)]
          (is (string? r) (str who ": " (pr-str r)))
          (when (string? r)
            (is (re-find #"GTK thread" r) (str who ": " r))))))))
