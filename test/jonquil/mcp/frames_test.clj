(ns jonquil.mcp.frames-test
  "The answers of jonquil_frames and of jonquil_eval in a frame
  (jonquil.mcp.frames), over fixture maps: no runtime."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.debugger :as debugger]
            [jonquil.mcp.frames :as frames]))

(def ^:private data
  {:title "ArithmeticException: Divide by zero"
   :error-id "rt:42"
   :frames [{:fn "demo.core/inner" :file "/p/src/demo/core.clj" :line 12
             :locals [{:name "x" :text "1"} {:name "y" :text "0"}]}
            {:fn "demo.core/outer" :file nil :line 3 :locals []}]})

(def ^:private opts
  {:root "/p" :locate (fn [f] (when (str/starts-with? (str f) "/p/") f))})

(def ^:private whole-value-line
  "Evaluate a local's name in its frame (jonquil_eval with frame) to see its whole value.")

(def ^:private debug-hint
  "Run the code again with debug mode on (Debug Mode in the Run menu) to keep every local.")

(deftest frames-answer-ok
  (let [r (frames/frames-answer data opts)]
    (is (false? (:error? r)))
    (is (= {"status" "ok" "title" "ArithmeticException: Divide by zero" "error_id" "rt:42"
            "frames" [{"index" 0 "fn" "demo.core/inner" "file" "src/demo/core.clj" "line" 12
                       "locals" [{"name" "x" "value" "1"} {"name" "y" "value" "0"}]}
                      {"index" 1 "fn" "demo.core/outer" "file" nil "line" 3 "locals" []}]}
           (:data r)))
    (is (= ["ArithmeticException: Divide by zero"
            "error_id: rt:42"
            "0  demo.core/inner  src/demo/core.clj:12"
            "     x = 1"
            "     y = 0"
            "1  demo.core/outer  line 3 (no file)"]
           (take 6 (str/split-lines (:text r)))))
    (is (str/ends-with? (:text r) whole-value-line))))

(deftest frames-answer-notes
  (testing "no error yet"
    (let [r (frames/frames-answer {:note "No error yet."} opts)]
      (is (= {"status" "no-error"} (:data r)))
      (is (= "No error yet." (:text r)))
      (is (false? (:error? r)))))
  (testing "any other note: no-frames, its text kept"
    (let [note "No frames: Jolt ran out of stack or memory and kept none."
          r (frames/frames-answer {:title "StackOverflowError: x" :frames [] :note note :error-id "rt:1"} opts)]
      (is (= "no-frames" (get-in r [:data "status"])))
      (is (= "rt:1" (get-in r [:data "error_id"])))
      (is (str/includes? (:text r) note))))
  (testing "no frames and no note"
    (is (= "no-frames" (get-in (frames/frames-answer {:title "t" :frames [] :error-id "i"} opts) [:data "status"]))))
  (testing "the runtime's error"
    (let [r (frames/frames-answer {:error "boom"} opts)]
      (is (:error? r))
      (is (= {"status" "error" "error" {"message" "boom"}} (:data r)))
      (is (= "boom" (:text r))))))

(deftest frames-answer-debug-hint
  (testing "a Jolt frame with no locals"
    (is (str/includes? (:text (frames/frames-answer data opts)) debug-hint)))
  (testing "every frame has locals: no hint"
    (is (not (str/includes? (:text (frames/frames-answer (update data :frames (comp vec butlast)) opts))
                            debug-hint))))
  (testing "JVM Clojure: its own line instead"
    (let [t (:text (frames/frames-answer data (assoc opts :jvm? true)))]
      (is (not (str/includes? t debug-hint)))
      (is (str/includes? t "JVM Clojure keeps no locals.")))))

(deftest frame-arg-checks-its-parts
  (is (nil? (frames/frame-arg {"code" "x"})))
  (is (= {:frame-i 1 :error-id "a"} (frames/frame-arg {"frame" {"index" 1 "error_id" "a"}})))
  (doseq [bad [{"index" 1} {"error_id" "a"} {"index" "1" "error_id" "a"} {"index" -1 "error_id" "a"}
               {"index" 1.5 "error_id" "a"} {"index" 1 "error_id" " "} "frame 1"]]
    (is (thrown-with-msg? Exception #"frame needs index \(a number\) and error_id \(from jonquil_frames\)\."
                          (frames/frame-arg {"frame" bad}))
        (pr-str bad))))

(deftest frame-eval-answer-shapes
  (testing "a value"
    (let [r (frames/frame-eval-answer {:out "hi\n" :text "7"} {:max-output 100 :ms 5})]
      (is (= {:text "hi\n=> 7" :error? false} (dissoc r :data)))
      (is (= {"status" "ok" "output" "hi\n" "value" "7" "ms" 5} (:data r)))))
  (testing "no error to evaluate in"
    (is (= {:text "No error yet." :error? true :data {"status" "no-error"}}
           (frames/frame-eval-answer {:error "No error yet."} {:max-output 100 :ms 5}))))
  (testing "an older error"
    (let [r (frames/frame-eval-answer {:error debugger/STALE} {:max-output 100 :ms 5})]
      (is (:error? r))
      (is (= "stale" (get-in r [:data "status"])))
      (is (= "That error_id names an older error. Call jonquil_frames again." (:text r)))))
  (testing "the code's own error"
    (let [r (frames/frame-eval-answer {:error "Error [ArithmeticException]: Divide by zero"} {:max-output 100 :ms 5})]
      (is (:error? r))
      (is (= "error" (get-in r [:data "status"])))
      (is (= {"message" "Divide by zero" "type" "ArithmeticException"} (get-in r [:data "error"])))))
  (testing "output cut to max-output, the last characters kept"
    (let [r (frames/frame-eval-answer {:out "abcdef" :text "nil"} {:max-output 4 :ms 1})]
      (is (= "cdef" (get-in r [:data "output"])))
      (is (= 2 (get-in r [:data "dropped_chars"]))))))

(deftest refusals
  (is (= {:text "An evaluation is running. Call again when it finishes, or stop it with jonquil_stop."
          :error? true :data {"status" "busy"}}
         frames/busy-result))
  (is (= {:text "An evaluation is paused in the Frames panel; the person at the window can Continue or Stop it."
          :error? true :data {"status" "paused"}}
         frames/paused-result))
  (is (= {:text "JVM Clojure keeps no locals, so code can't run in a frame."
          :error? true :data {"status" "unsupported"}}
         frames/jvm-frame-result))
  (is (= {:text "jonquil_frames answers for Jolt and JVM Clojure. This project is Babashka."
          :error? true :data {"status" "unsupported"}}
         (frames/unsupported-result {:profile {:name "Babashka"}}))))

(deftest await-callback-answers-or-times-out
  (is (= 3 (frames/await-callback (fn [deliver!] (deliver! 3)) 100)))
  (is (= ::frames/timeout (frames/await-callback (fn [_]) 10))))
