(ns jonquil.cljs-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.cljs :as cljs]))

(deftest sentences-test
  (is (= "; ClojureScript support is experimental. Jonquil connects to the shadow-cljs nREPL server on the first Run." cljs/experimental-message))
  (is (= "; Detached from the shadow-cljs server. The watch and the app weren't touched." cljs/detached-message))
  (is (= "; No shadow-cljs server here. Start one in /p with npx shadow-cljs watch <build>." (cljs/not-running-message "/p")))
  (is (re-find #"shadow-cljs\.edn" cljs/no-project-message))
  (is (re-find #"\.shadow-cljs/nrepl\.port" cljs/bad-port-file-message))
  (is (re-find #"port 7" (cljs/refused-message 7)))
  (is (re-find #"Port 7" (cljs/not-server-message 7)))
  (is (re-find #"port 7" (cljs/connecting-message 7)))
  (is (= "; The build isn't loaded in any JS runtime. Open the app (or run the node script) and Run again." cljs/no-runtime-message))
  (is (= ":repl/quit" cljs/quit-value)))

(deftest select-test
  (is (= "(shadow.cljs.devtools.api/nrepl-select :app)" (cljs/select-code :app)))
  (is (cljs/selected-value? :app "[:selected :app]"))
  (is (not (cljs/selected-value? :app "[:selected :dev]")))
  (is (not (cljs/selected-value? :app nil)))
  (is (= "; watch for build not running. Start it with npx shadow-cljs watch :dev."
         (cljs/select-failed-message "Execution error (ExceptionInfo) at shadow.cljs.devtools.server.nrepl-impl/repl-init (nrepl_impl.clj:30).\nwatch for build not running\n" :dev)))
  (is (= "; plain failure. Start it with npx shadow-cljs watch :dev."
         (cljs/select-failed-message "\nplain failure\nmore\n" :dev))))

(deftest error-line-test
  (is (cljs/exception-value? ":repl/exception!"))
  (is (not (cljs/exception-value? "3")))
  (is (= "Error: boom" (cljs/error-line "\nExecution error (Error) at (<cljs repl>:1).\nboom\n")))
  (is (= "Error: second" (cljs/error-line "Execution error (Error) at a.\nfirst\nExecution error (Error) at b.\nsecond\n")))
  (is (= "just some text" (cljs/error-line "  just some text\n")))
  (is (cljs/no-runtime? "No available JS runtime.\nSee https://x"))
  (is (not (cljs/no-runtime? "boom"))))

(deftest run-summary-test
  (is (= "; Ran demo.core (3 forms)" (cljs/run-summary "demo.core" ["1" "2" "3"])))
  (is (= "; Ran demo.core (1 form)" (cljs/run-summary "demo.core" ["1"])))
  (is (= "; Ran the buffer (3 forms)" (cljs/run-summary nil ["1" "2" "3"])))
  (is (= "; Ran demo.core (3 forms, 1 error; an error doesn't stop the rest of the file)"
         (cljs/run-summary "demo.core" ["1" ":repl/exception!" "3"])))
  (is (= "; Ran demo.core (2 forms, 2 errors; an error doesn't stop the rest of the file)"
         (cljs/run-summary "demo.core" [":repl/exception!" ":repl/exception!"]))))

(deftest shadow-build-test
  (is (= {:build :web} (cljs/shadow-build {:shadow-build :web} "{:builds {:app {}}}")))
  (is (= {:build :web} (cljs/shadow-build {:shadow-build :web} nil)))
  (is (= {:build :app} (cljs/shadow-build nil "{:builds {:app {}}}")))
  (is (= {:build :app} (cljs/shadow-build {} "{:deps true #_ {:x 1} :builds {:app {}}}")))
  (is (= {:notice "; Several shadow-cljs builds: :app :web. Pick one with {:cljs {:shadow-build :app}} in .jonquil.edn."}
         (cljs/shadow-build nil "{:builds {:web {} :app {}}}")))
  (is (= {:notice "; No build found in shadow-cljs.edn. Name one with {:cljs {:shadow-build :app}} in .jonquil.edn."}
         (cljs/shadow-build nil "{:builds {}}")))
  (is (= {:notice "; No build found in shadow-cljs.edn. Name one with {:cljs {:shadow-build :app}} in .jonquil.edn."}
         (cljs/shadow-build nil "{:builds {:app")))
  (is (= {:notice "; No build found in shadow-cljs.edn. Name one with {:cljs {:shadow-build :app}} in .jonquil.edn."}
         (cljs/shadow-build nil nil)))
  (is (= {:notice "; .jonquil.edn :cljs :shadow-build should be a keyword, like :app."}
         (cljs/shadow-build {:shadow-build "app"} "{:builds {:app {}}}"))))
