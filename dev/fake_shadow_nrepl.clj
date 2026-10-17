(ns fake-shadow-nrepl
  "A fake shadow-cljs nREPL server for the cljs evaluator's tests and the
  cljs self-test window. It imitates the replies measured against shadow-cljs
  3.2.0:

  - describe advertises eval, load-file, clone, complete, info and eldoc
    (:bare-ops? gives only eval, load-file, clone and describe).
  - A session starts as CLJ (ns user). Evaluating
    (shadow.cljs.devtools.api/nrepl-select :<build>) selects the configured
    :build (the banner as :out, then {:ns cljs.user :value [:selected ...]}),
    or fails with \"watch for build not running\" when the build differs or
    :watch-running? is false.
  - A selected session evaluates its text form by form, one {:value v :ns n}
    per form, preceded by the stale {:status [\"state\"]} message that carries
    the previous request's id (none on a session's first eval). A throw form
    answers {:err ...} then {:value \":repl/exception!\"} and the later forms
    still run. :repl/quit leaves the session in CLJ (ns shadow.user).
  - :no-runtime? answers shadow's \"No available JS runtime\" for every eval.
  - complete and eldoc answer from canned data; info answers cider's keys.

  It never evaluates anything: a form's value comes from :value (default
  \"42\"), \"#'ns/name\" for def and defn, and \"nil\" for ns. Every request is
  kept (ops-seen, requests), so a test can assert what a client sent."
  (:require [clojure.string :as str]
            [fake-nrepl-core :as core :refer [head read-forms]]))

(def shadow-ops
  ;; The ops the evaluator and the doc and completion gates read.
  ["clone" "close" "describe" "eval" "load-file" "complete" "info" "eldoc"])

(def bare-ops
  ["eval" "load-file" "clone" "describe"])

(def quit-err
  (str "Exited CLJS session. You are now in CLJ again.\n"))

(def no-runtime-err
  "No available JS runtime.\nSee https://shadow-cljs.github.io/docs/UsersGuide.html#repl-troubleshooting")

(def throw-err
  "\nExecution error (Error) at (<cljs repl>:1).\nboom\n")

(def watch-err
  "Execution error (ExceptionInfo) at shadow.cljs.devtools.server.nrepl-impl/repl-init (nrepl_impl.clj:30).\nwatch for build not running\n")

(def candidates
  [{"candidate" "map" "ns" "cljs.core" "type" "function"}
   {"candidate" "mapv" "ns" "cljs.core" "type" "function"}
   {"candidate" "map?" "ns" "cljs.core" "type" "function"}])

(def map-doc
  "Returns a lazy sequence consisting of the result of applying f to the set of first items of each coll.")

(defn- canned-value [opts cur-ns form]
  (cond
    (#{'def 'defn 'defn- 'defonce} (head form)) (str "#'" cur-ns "/" (second form))
    (= 'ns (head form)) "nil"
    :else (str (:value opts "42"))))

(defn- select-form? [form]
  (and (= 'shadow.cljs.devtools.api/nrepl-select (head form)) (keyword? (second form))))

(defn- select! [{:keys [opts sessions]} reply sid form]
  (if (and (:watch-running? opts true) (= (name (second form)) (name (:build opts :app))))
    (do (swap! sessions update sid assoc :cljs? true :ns "cljs.user")
        (reply {"out" "To quit, type: :cljs/quit\n"})
        (reply {"ns" "cljs.user" "value" (str "[:selected " (second form) "]")})
        (reply {"status" ["done"]}))
    (do (reply {"err" watch-err})
        (reply {"ex" "class clojure.lang.ExceptionInfo" "root-ex" "class clojure.lang.ExceptionInfo"
                "status" ["eval-error"]})
        (reply {"status" ["done"]}))))

(defn- evaluate!
  "Answers one eval or load-file request. reply sends one message map
  (string keys) carrying the request's id and session."
  [{:keys [opts sessions] :as srv} reply raw-reply m]
  (let [sid (get m "session")
        text (or (get m "file") (get m "code") "")
        forms (try (read-forms text) (catch Exception _ ::unreadable))
        {:keys [cljs? last-id]} (get @sessions sid)
        cur (fn [] (get-in @sessions [sid :ns] "shadow.user"))]
    (cond
      (= ::unreadable forms)
      (do (reply {"err" "Syntax error reading source"}) (reply {"status" ["done"]}))

      (and (= 1 (count forms)) (select-form? (first forms)))
      (select! srv reply sid (first forms))

      (and cljs? (:no-runtime? opts))
      (do (reply {"err" no-runtime-err})
          (reply {"ns" "cljs.user" "printed-value" 1 "value" "nil"})
          (reply {"status" ["done"]}))

      cljs?
      (do (when last-id
            (raw-reply {"id" last-id "session" sid "repl-type" "cljs" "status" ["state"] "changed-namespaces" {}}))
          (loop [fs (seq forms)]
            (let [f (first fs)]
              (cond
                (nil? fs) (reply {"status" ["done"]})

                (= :repl/quit f)
                (do (swap! sessions update sid assoc :cljs? false :ns "shadow.user")
                    (reply {"err" quit-err})
                    (reply {"ns" "shadow.user" "printed-value" 1 "value" ":repl/quit"})
                    (reply {"status" ["done"]}))

                (= 'throw (head f))
                (do (reply {"err" throw-err})
                    (reply {"ns" (cur) "value" ":repl/exception!"})
                    (recur (next fs)))

                :else
                (do (when (= 'ns (head f)) (swap! sessions assoc-in [sid :ns] (str (second f))))
                    (reply {"ns" (cur) "value" (canned-value opts (cur) f)})
                    (recur (next fs)))))))

      :else
      (do (doseq [f forms]
            (when (= 'ns (head f)) (swap! sessions assoc-in [sid :ns] (str (second f))))
            (reply {"ns" (cur) "value" (canned-value opts (cur) f)}))
          (reply {"status" ["done"]})))))

(defn- handle!
  "Answers one request, incrementing srv's :busy count for its duration so
  stop! can wait for every in-flight reply."
  [{:keys [opts sessions log counter busy] :as srv} _sock _stop-latch _closed raw-reply m]
  (swap! log conj (dissoc m "id"))
  (swap! busy inc)
  (try
    (let [sid (get m "session")
          reply (fn [kv] (raw-reply (cond-> kv sid (assoc "session" sid))))
          op (get m "op")]
      (case op
        "describe" (reply {"ops" (zipmap (if (:bare-ops? opts) bare-ops shadow-ops) (repeat {}))
                           "status" ["done"]})
        "clone" (let [new-sid (str "fake-shadow-session-" (swap! counter inc))]
                  (swap! sessions assoc new-sid {:ns "shadow.user" :cljs? false})
                  (raw-reply {"new-session" new-sid "session" new-sid "status" ["done"]}))
        ("eval" "load-file") (do (evaluate! srv reply raw-reply m)
                                 (when sid (swap! sessions assoc-in [sid :last-id] (get m "id"))))
        "complete" (let [prefix (or (get m "prefix") (get m "symbol") "")]
                     (reply {"completions" (filterv (fn [c] (str/starts-with? (get c "candidate") prefix)) candidates)
                             "status" ["done"]}))
        "eldoc" (if (= "map" (get m "sym"))
                  (reply {"ns" "cljs.core" "name" "map" "type" "function"
                          "eldoc" [["f"] ["f" "coll"]] "docstring" map-doc "status" ["done"]})
                  (reply {"status" ["done" "no-eldoc"]}))
        "info" (if (= "map" (get m "sym"))
                 (reply {"ns" "cljs.core" "name" "map" "arglists-str" "([f] [f coll])"
                         "doc" map-doc "status" ["done"]})
                 (reply {"status" ["done" "no-info"]}))
        "close" (reply {"status" ["done"]})
        (reply {"err" (str "Unknown op: " op) "status" ["done" "unknown-op"]})))
    (finally (swap! busy dec))))

(defn start!
  "Starts a fake server on a free loopback port and returns it. opts:
  :build          the build nrepl-select accepts (default :app)
  :watch-running? false: selecting any build fails (default true)
  :no-runtime?    a selected session's evals answer shadow's no-runtime error
  :value          the printed value of an ordinary form (default \"42\")
  :bare-ops?      describe advertises only eval, load-file, clone, describe"
  ([] (start! {}))
  ([opts] (core/start-server! opts handle! {})))

(def ops-seen "Every op the server has received, in order." core/ops-seen)

(def requests "Every request the server has received (string keys, no :id), in order." core/requests)

(def stop!
  "Stops the server and waits for its connection threads (fake-nrepl-core)."
  core/stop!)

(comment
  (def srv (start!))
  (:port srv)
  (stop! srv))
