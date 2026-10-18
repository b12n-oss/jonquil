(ns jonquil.nrepl.stdin-middleware-test
  "The stdin middleware's reader and routing, run in this process. In a Jolt
  child the same code feeds read-line (jonquil.nrepl.evaluator)."
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.nrepl.stdin-middleware :as mw])
  (:import (clojure.lang LineNumberingPushbackReader)))

(defn- reading
  "Runs f against a fresh session entry's *in* on its own thread; [entry
  result-future replies-atom]. replies collects what the reader sends on
  the session's current reply."
  [f]
  (let [e (mw/entry)
        replies (atom [])
        _ (reset! (:reply e) (fn [m] (swap! replies conj m)))
        rdr (LineNumberingPushbackReader. (mw/reader e))]
    [e (future (binding [*in* rdr] (f))) replies]))

(defn- wait-for [pred] (loop [n 0] (cond (pred) true (> n 250) false :else (do (Thread/sleep 20) (recur (inc n))))))

(deftest a-read-asks-for-input-then-returns-the-line
  (let [[e f replies] (reading read-line)]
    (is (wait-for #(= [{"status" ["need-input"]}] @replies)))
    (is (mw/waiting? e))
    (mw/offer! e "hello\n")
    (is (= "hello" (deref f 2000 :timeout)))
    (is (not (mw/waiting? e)))))

(deftest one-message-answers-two-reads
  (let [[e f replies] (reading (fn [] [(read-line) (read-line)]))]
    (is (wait-for #(= 1 (count @replies))))
    (mw/offer! e "a\nb\n")
    (is (= ["a" "b"] (deref f 2000 :timeout)))
    (is (= 1 (count @replies)) "asked once")))

(deftest an-empty-message-is-eof-and-it-doesnt-stick
  (let [[e f replies] (reading (fn [] [(read-line) (read-line)]))]
    (is (wait-for #(= 1 (count @replies))))
    (mw/offer! e "")
    (is (wait-for #(= 2 (count @replies))) "the next read asks again")
    (mw/offer! e "after\n")
    (is (= [nil "after"] (deref f 2000 :timeout)))))

(deftest read-reads-a-form
  (let [[e f replies] (reading read)]
    (is (wait-for #(seq @replies)))
    (mw/offer! e "{:k [1 2]}\n")
    (is (= {:k [1 2]} (deref f 2000 :timeout)))))

(deftest an-interrupt-frees-a-waiting-read
  (let [[e f replies] (reading (fn [] (try (read-line) (catch Throwable t (ex-data t)))))]
    (is (wait-for #(seq @replies)))
    (mw/interrupt! e)
    (is (= {:jolt/interrupted true} (deref f 2000 :timeout)))))

(deftest an-interrupt-with-no-read-waiting-leaves-nothing-behind
  (let [e (mw/entry)]
    (mw/interrupt! e)
    (let [replies (atom [])
          _ (reset! (:reply e) (fn [m] (swap! replies conj m)))
          f (future (binding [*in* (LineNumberingPushbackReader. (mw/reader e))] (read-line)))]
      (is (wait-for #(seq @replies)) "the read waits, rather than seeing a stale interrupt")
      (mw/offer! e "fine\n")
      (is (= "fine" (deref f 2000 :timeout))))))

(deftest output-is-flushed-before-asking
  (let [e (mw/entry)
        out (java.io.StringWriter.)
        order (atom [])
        _ (reset! (:reply e) (fn [_] (swap! order conj [:asked (str out)])))
        w (proxy [java.io.Writer] []
            (write ([x] (.write out (str x))) ([x off len] (.write out (subs (str x) off (+ off len)))))
            (flush [] (swap! order conj [:flushed (str out)])))
        f (future (binding [*out* w *in* (LineNumberingPushbackReader. (mw/reader e))]
                    (print "Name: ") (read-line)))]
    (is (wait-for #(some (fn [[k]] (= :asked k)) @order)))
    (is (= [:flushed "Name: "] (first @order)) "the prompt is flushed before need-input goes out")
    (mw/offer! e "Ann\n")
    (is (= "Ann" (deref f 2000 :timeout)))))

(deftest text-with-no-read-waiting-is-dropped
  (let [e (mw/entry)]
    (is (false? (mw/offer! e "stale\n")))
    (reset! (:reply e) (fn [_]))
    (let [f (future (binding [*in* (LineNumberingPushbackReader. (mw/reader e))] (read-line)))]
      (is (wait-for #(mw/waiting? e)) "the read waits instead of taking the stale line")
      (mw/offer! e "fresh\n")
      (is (= "fresh" (deref f 2000 :timeout))))))

(deftest an-interrupt-drops-what-was-queued
  (let [[e f replies] (reading (fn [] (try (read-line) (catch Throwable t (ex-data t)))))]
    (is (wait-for #(seq @replies)))
    (.offer ^java.util.concurrent.LinkedBlockingQueue (:queue e) "racing line\n")
    (mw/interrupt! e)
    (is (= {:jolt/interrupted true} (deref f 2000 :timeout)) "the interrupt wins over a line already queued")
    (is (zero? (.size ^java.util.concurrent.LinkedBlockingQueue (:queue e))))))

(deftest a-new-eval-drops-leftover-text
  (let [[e f replies] (reading read-line)]
    (is (wait-for #(seq @replies)))
    (mw/offer! e "one\ntwo\n")
    (is (= "one" (deref f 2000 :timeout)))
    (mw/new-eval! e (fn [_]))
    (let [g (future (binding [*in* (LineNumberingPushbackReader. (mw/reader e))] (read-line)))]
      (is (wait-for #(mw/waiting? e)) "\"two\" from the last eval doesn't answer this one")
      (mw/offer! e "three\n")
      (is (= "three" (deref g 2000 :timeout))))))

(deftest close-forgets-the-session
  (let [sessions (atom {"s1" (mw/entry)})
        h (mw/handler sessions (fn [_] nil))]
    (h {"op" "close" "session" "s1" :reply (fn [_])})
    (is (= {} @sessions))))

(deftest the-handler-routes-by-op
  (let [sessions (atom {})
        seen (atom [])
        replies (atom [])
        h (mw/handler sessions (fn [req] (swap! seen conj (get req "op"))
                                 (when (= "clone" (get req "op"))
                                   ((:reply req) {"new-session" "s1" "status" ["done"]}))))]
    (testing "clone registers the new session's entry and binds *in* while it runs"
      (let [bound (atom nil)
            h2 (mw/handler sessions (fn [req] (reset! bound *in*) ((:reply req) {"new-session" "s2"})))]
        (h2 {"op" "clone" :reply (fn [_])})
        (is (instance? LineNumberingPushbackReader @bound))
        (is (contains? @sessions "s2"))))
    (h {"op" "clone" :reply (fn [_])})
    (is (contains? @sessions "s1"))
    (testing "eval records the reply the reader asks on, then hands on"
      (let [r (fn [_])]
        (h {"op" "eval" "session" "s1" :reply r})
        (is (identical? r @(:reply (get @sessions "s1"))))))
    (testing "stdin answers done, and an unknown session says so"
      (h {"op" "stdin" "session" "s1" "stdin" "x\n" :reply (fn [m] (swap! replies conj m))})
      (h {"op" "stdin" "session" "nope" "stdin" "x\n" :reply (fn [m] (swap! replies conj m))})
      (is (= [{"status" ["done" "not-waiting"]} {"status" ["done" "error"]}] @replies)
          "no read waits in s1, so the text is dropped and the reply says so"))
    (h {"op" "interrupt" "session" "s1" :reply (fn [_])})
    (is (= ["clone" "eval" "interrupt"] @seen) "stdin is ours; interrupt is also handed on")))
