(ns ^{:clj-kondo/config '{:lint-as {jonquil.live.cljs-node-live-test/with-cljs clojure.core/let}}}
 jonquil.live.cljs-node-live-test
  "Drives jonquil.nrepl.cljs-node against REAL ClojureScript on node: each
  evaluator launches its own clojure + node prepl in a private run dir, over
  one temp project. Run with `bb test:cljs-node`, not `bb test`: it needs the
  clojure CLI and node (ClojureScript comes from ~/.m2 or the network). The
  runner skips with exit 0 when either is missing.

  Every test stops its evaluator in a finally, so no child outlives it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jolt.process :as p]
            [jonquil.cljs-node :as cljs-node-msg]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.cljs-child :as cljs-child]
            [jonquil.nrepl.cljs-node :as cljs-node]
            [jonquil.nrepl.cljs-node-files :as files]
            [temp-dirs]))

(def deps-text "{:paths [\"src\"]\n :deps {org.clojure/clojurescript {:mvn/version \"1.12.42\"}}}\n")

(def util-text "(ns demo.util)\n(defn greet [x] (str \"hi \" x))\n")

(def ^:dynamic *dir* nil)

(defn- project-fixture [f]
  (let [dir (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-cljs-node-live-")))]
    (.mkdirs (io/file dir "src/demo"))
    (spit (io/file dir "deps.edn") deps-text)
    (spit (io/file dir "src/demo/util.cljs") util-text)
    (try (binding [*dir* dir] (f))
         (finally (temp-dirs/delete-made!)))))

(use-fixtures :once project-fixture)

(defn- make []
  (cljs-node/make-cljs-node {:profile dialect/cljs :runtime :launch :project-dir *dir*}))

(defmacro ^:private with-cljs
  "Binds sym to a fresh evaluator and stops it afterwards. Written as
  (with-cljs [e (make)] ...), which clj-kondo reads as a let (see the ns config)."
  [[sym init] & body]
  `(let [~sym ~init]
     (try ~@body (finally (ev/stop! ~sym)))))

(defn- run
  "Evaluates and waits. {:result r :out [[kind text]...]}."
  [e code]
  (let [done (promise) out (atom [])]
    (ev/eval! e code {:run? false
                      :on-output (fn [k s] (swap! out conj [k s]))
                      :on-complete (fn [r] (deliver done r))})
    {:result (deref done 60000 :timeout) :out @out}))

(defn- sh-out [& args]
  (let [r @(apply p/process {:out :string} args)]
    (str (:out r))))

(defn- process-tree
  "pid and every descendant pid, as strings (pgrep -P, recursively)."
  [pid]
  (let [kids (str/split-lines (str/trim (sh-out "pgrep" "-P" (str pid))))
        kids (remove str/blank? kids)]
    (into [(str pid)] (mapcat process-tree) kids)))

(defn- pid-alive? [pid]
  (let [r (deref (p/process "ps" "-p" (str pid)) 5000 :timeout)]
    (or (= :timeout r) (zero? (:exit r)))))

(deftest defs-persist-within-a-run-live
  (with-cljs [e (make)]
    (let [{:keys [result out]} (run e "(def a 1) (def b 2) (+ a b)")]
      (is (= :ok (:status result)) (pr-str [result out]))
      (is (= "3" (:printed result)) (pr-str result)))))

(deftest println-streams-live
  (with-cljs [e (make)]
    (let [{:keys [result out]} (run e "(println \"hello\")")]
      (is (= :ok (:status result)) (pr-str [result out]))
      (is (some #(= [:out "hello\n"] %) out) (pr-str out)))))

(deftest output-after-the-evaluation-live
  ;; A setTimeout prints after the evaluation answered: the console still
  ;; gets it, through the last evaluation's on-output.
  (with-cljs [e (make)]
    (let [out (atom []) done (promise)]
      (ev/eval! e "(js/setTimeout (fn [] (println \"late\")) 300)"
                {:run? false
                 :on-output (fn [k s] (swap! out conj [k s]))
                 :on-complete (fn [r] (deliver done r))})
      (is (= :ok (:status (deref done 60000 :timeout))))
      (is (not-any? #(= [:out "late\n"] %) @out) "not yet: the timer hasn't fired")
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop [] (when (and (not-any? #(= [:out "late\n"] %) @out)
                            (< (System/currentTimeMillis) deadline))
                   (Thread/sleep 50) (recur))))
      (is (some #(= [:out "late\n"] %) @out) (pr-str @out)))))

(deftest quick-doc-ignores-the-programs-output-live
  ;; A timer printing while quick docs are fetched goes to the console, and
  ;; the doc is cljs.repl/doc's own text.
  (with-cljs [e (make)]
    (let [out (atom [])]
      (ev/eval! e "(js/setTimeout (fn [] (println \"stray\")) 150)"
                {:run? false :on-output (fn [k s] (swap! out conj [k s])) :on-complete (fn [_])})
      (let [deadline (+ (System/currentTimeMillis) 60000)]
        (loop [] (when (and (ev/evaluating? e) (< (System/currentTimeMillis) deadline))
                   (Thread/sleep 20) (recur))))
      (let [doc (ev/lookup e "map" "(map inc [1])" 1)
            _ (Thread/sleep 400)]
        (is (re-find #"cljs.core/map" (str (:doc doc))) (pr-str doc))
        (is (not (re-find #"stray" (str (:doc doc)))) "the program's output isn't the doc")
        (is (some #(= [:out "stray\n"] %) @out) (pr-str @out))))))

(deftest error-then-recover-live
  (with-cljs [e (make)]
    (let [{:keys [result out]} (run e "(throw (js/Error. \"boom\"))")]
      (is (= :error (:status result)) (pr-str [result out]))
      (is (str/includes? (str (:error-text result) (apply str (map second out))) "boom")
          (pr-str [result out])))
    (let [{:keys [result]} (run e "(+ 2 3)")]
      (is (= "5" (:printed result)) (pr-str result)))))

(deftest require-project-namespace-live
  (with-cljs [e (make)]
    (let [{:keys [result out]} (run e "(require 'demo.util)")]
      (is (= :ok (:status result)) (pr-str [result out])))
    (let [{:keys [result out]} (run e "(demo.util/greet \"x\")")]
      (is (= :ok (:status result)) (pr-str [result out]))
      (is (= "\"hi x\"" (:printed result)) (pr-str result)))))

(defn- node-pids
  "The pids among pids whose command's first word ends with node."
  [pids]
  (filter (fn [pid]
            (let [cmd (str/trim (sh-out "ps" "-o" "command=" "-p" pid))
                  word (first (str/split cmd #"\s+"))]
              (str/ends-with? (str word) "node")))
          pids))

(defn- listen-rows [pid]
  (rest (str/split-lines (sh-out "lsof" "-nP" "-a" "-p" pid "-iTCP" "-sTCP:LISTEN"))))

(deftest stop-kills-the-tree-live
  (with-cljs [e (make)]
    (is (= "1" (:printed (:result (run e "1")))) "the first eval starts the child")
    (let [child (cljs-node/child-pid e)
          _ (is (some? child))
          pids (process-tree child)
          nodes (node-pids pids)
          done (promise)]
      (is (seq nodes) (str "node is among the child's tree: " (pr-str pids)))
      (ev/eval! e "(loop [] (recur))" {:run? false :on-complete (fn [r] (deliver done r))})
      (Thread/sleep 1000)
      (let [t0 (System/currentTimeMillis)]
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 5000 :timeout))))
        (is (< (- (System/currentTimeMillis) t0) 5000)))
      (let [{:keys [result]} (run e "(+ 4 5)")]
        (is (= "9" (:printed result)) (pr-str result)))
      (is (not-any? pid-alive? pids) (str "alive: " (pr-str (filter pid-alive? pids)))))))

(deftest listeners-are-loopback-only-live
  (with-cljs [e (make)]
    (is (= "2" (:printed (:result (run e "(+ 1 1)")))) "the first eval starts the child")
    (let [child (cljs-node/child-pid e)
          _ (is (some? child))
          pids (process-tree child)
          nodes (node-pids pids)]
      (is (seq nodes) (str "node is among the child's tree: " (pr-str pids)))
      (is (some #(seq (listen-rows %)) nodes) "node owns a listening socket")
      (doseq [pid pids
              row (listen-rows pid)]
        (let [name-col (nth (reverse (str/split (str/trim row) #"\s+")) 1)]
          (is (str/starts-with? name-col "127.0.0.1:") row))))))

(defn- browser-selection []
  {:profile dialect/cljs :runtime :launch :project-dir *dir*
   :cljs {:target :browser} :cljs-target :browser})

(defn- http-get
  "[status body] of a GET to url, through curl (Jolt's URL has no http handler)."
  [url]
  (let [out (sh-out "curl" "-s" "-m" "5" "-w" "\n%{http_code}" url)
        i (str/last-index-of out "\n")]
    [(parse-long (str/trim (subs out (inc i)))) (subs out 0 i)]))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort s)))

(defn- listener-names
  "The local address column of every listening socket in the child's tree."
  [child]
  (map #(nth (reverse (str/split (str/trim %) #"\s+")) 1)
       (mapcat listen-rows (process-tree (:pid child)))))

(deftest browser-listeners-are-loopback-only-live
  ;; No browser opens and no evaluator is involved: the browser launcher is
  ;; started through cljs-child/start! (the evaluator's own first Run can't
  ;; get past the setup form without a page, see the task 5 report). The
  ;; prepl and the HTTP server must listen on 127.0.0.1 only, and the page
  ;; URL must answer HTML.
  (let [port (free-port)
        child (cljs-child/start! {:clojure (cljs-child/find-clojure)
                                  :launcher (files/ensure-launcher!)
                                  :project-dir *dir* :target :browser :http-port port})]
    (try
      (is (nil? (:error child)) (pr-str child))
      (is (= (str "http://127.0.0.1:" port) (:url child)) (pr-str (:url child)))
      (let [names (listener-names child)]
        (is (some #(= (str "127.0.0.1:" (:port child)) %) names) (pr-str [(:port child) names]))
        (is (some #(= (str "127.0.0.1:" port) %) names) (pr-str [port names]))
        (doseq [n names] (is (str/starts-with? n "127.0.0.1:") n)))
      ;; The HTTP server starts when the first form reaches the prepl.
      (with-open [sock (java.net.Socket. "127.0.0.1" (int (:port child)))]
        (doto (io/writer (.getOutputStream sock))
          (.write "(require 'cljs.repl)\n")
          (.flush))
        (let [deadline (+ (System/currentTimeMillis) 20000)
              [status body] (loop []
                              (let [r (try (http-get (:url child)) (catch Throwable _ [0 ""]))]
                                (if (or (= 200 (first r)) (> (System/currentTimeMillis) deadline))
                                  r
                                  (do (Thread/sleep 250) (recur)))))]
          (is (= 200 status))
          (is (re-find #"(?i)<html" body) (subs body 0 (min 200 (count body)))))
        ;; Listeners the HTTP server's first connection created count too.
        (let [names (listener-names child)]
          (is (some #(= (str "127.0.0.1:" port) %) names) (pr-str [port names]))
          (doseq [n names] (is (str/starts-with? n "127.0.0.1:") n))))
      (finally (cljs-child/kill! child)))))

(deftest browser-no-page-keeps-the-child-live
  ;; The real browser setup blocks until a page connects. With :open-url
  ;; stubbed nobody connects: the first Run opens the page (stub) before
  ;; the setup, returns the no-page notice at the page bound, and leaves
  ;; the child alive. No browser opens.
  (let [opened (atom [])
        e (cljs-node/make-cljs-node (browser-selection)
                                    {:timings {:page-timeout-ms 4000}
                                     :open-url (fn [url] (swap! opened conj url) true)})]
    (try
      (let [{:keys [result]} (run e "(+ 1 2)")
            url (first @opened)]
        (is (some? url))
        (is (re-find #"^http://127\.0\.0\.1:\d+$" (str url)) (pr-str url))
        (is (= {:status :notice :text (cljs-node-msg/no-page-message url)} result) (pr-str result))
        (let [pid (cljs-node/child-pid e)]
          (is (some? pid))
          (is (pid-alive? pid) "the child is still alive")))
      (finally (ev/stop! e)))))

(deftest browser-stop-then-run-keeps-the-url-live
  ;; Stop kills in the background, and the next Run launches while the old
  ;; JVM may still hold the pre-bound HTTP port. The Run waits for the kill,
  ;; so the second launch gets the same port (the open tab keeps working) and
  ;; nothing says the port changed. :open-url is stubbed: no browser opens.
  (let [opened (atom [])
        e (cljs-node/make-cljs-node (browser-selection)
                                    {:timings {:page-timeout-ms 4000}
                                     :open-url (fn [url] (swap! opened conj url) true)})
        done (promise)]
    (try
      (ev/eval! e "(+ 1 2)" {:run? false :on-output (fn [_ _]) :on-complete (fn [r] (deliver done r))})
      (is (loop [n 0] (cond (seq @opened) true
                            (> n 3000) false
                            :else (do (Thread/sleep 20) (recur (inc n)))))
          "the page was opened by the first launch")
      (Thread/sleep 1000)
      (ev/interrupt! e)
      (is (= :interrupted (:status (deref done 30000 :timeout))))
      (let [{:keys [result out]} (run e "(+ 1 2)")
            url (first @opened)
            text (apply str (map second out))]
        (is (= {:status :notice :text (cljs-node-msg/no-page-message url)} result) (pr-str result))
        (is (= url (:url (:child @(:state e)))) "the second launch served the same address")
        (is (= 1 (count @opened)) "no second tab")
        (is (not (str/includes? text "port changed")) text)
        (is (str/includes? text (cljs-node-msg/reload-message url)) text))
      (finally (ev/stop! e)))))

(deftest launcher-without-a-port-prints-usage-live
  (let [r @(p/process {:out :string :err :string :dir *dir*}
                      (cljs-child/find-clojure) "-M" "-i" (files/ensure-launcher!)
                      "-m" "jonquil.cljs-node-launch" "browser")]
    (is (= 2 (:exit r)) (pr-str r))
    (is (str/includes? (str (:err r)) "usage: jonquil.cljs-node-launch browser <http-port>") (pr-str (:err r)))))

(deftest real-browser-live
  ;; Opens a REAL browser tab, so it only runs with JONQUIL_BROWSER_LIVE=1.
  (let [live? (= "1" (System/getenv "JONQUIL_BROWSER_LIVE"))]
    (if live?
      (let [e (cljs-node/make-cljs-node (browser-selection) {:timings {:page-timeout-ms 60000}})]
        (try
          (let [{:keys [result]} (run e "(+ 1 2)")]
            (is (= :ok (:status result)) (pr-str result))
            (is (= "3" (:printed result)) (pr-str result)))
          (let [{:keys [result out]} (run e "(println \"from-browser\")")]
            (is (= :ok (:status result)) (pr-str result))
            ;; The browser REPL sends println's text and its newline as two
            ;; :out chunks; the console appends them, so compare the text.
            (is (str/includes? (apply str (map second (filter #(= :out (first %)) out)))
                               "from-browser\n")
                (pr-str out)))
          (let [{:keys [result]} (run e "(throw (js/Error. \"boom\"))")]
            (is (= :error (:status result)) (pr-str result)))
          (let [{:keys [result]} (run e "(+ 2 3)")]
            (is (= "5" (:printed result)) (pr-str result)))
          (let [out (atom []) done (promise)]
            (ev/eval! e "(js/setTimeout (fn [] (println \"late\")) 300)"
                      {:run? false
                       :on-output (fn [k s] (swap! out conj [k s]))
                       :on-complete (fn [r] (deliver done r))})
            ;; Output after the answer reaches the console, as on node.
            (is (= :ok (:status (deref done 60000 :timeout))))
            (let [late? #(str/includes? (apply str (map second @out)) "late\n")
                  deadline (+ (System/currentTimeMillis) 5000)]
              (loop [] (when (and (not (late?)) (< (System/currentTimeMillis) deadline))
                         (Thread/sleep 50) (recur)))
              (is (late?) (pr-str @out))))
          ;; js/console.log stays in the browser's own console.
          (let [out (atom []) done (promise)]
            (ev/eval! e "(js/console.log \"via-console\")"
                      {:run? false
                       :on-output (fn [k s] (swap! out conj [k s]))
                       :on-complete (fn [r] (deliver done r))})
            (is (= :ok (:status (deref done 60000 :timeout))))
            (Thread/sleep 1000)
            (is (not (str/includes? (apply str (map second @out)) "via-console")) (pr-str @out)))
          (finally (ev/stop! e))))
      (println "skip: set JONQUIL_BROWSER_LIVE=1 to run the real-browser test (it opens a browser tab)"))))
