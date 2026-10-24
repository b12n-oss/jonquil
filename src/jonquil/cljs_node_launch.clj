(ns jonquil.cljs-node-launch
  "The launcher Jonquil's ClojureScript-on-node runtime starts in its child
  JVM: clojure -M -i <this file> -m jonquil.cljs-node-launch. JVM Clojure
  only, run inside the child. Jolt never loads it; Jonquil embeds its text
  (jonquil.nrepl.cljs-node-files) and writes it to a cache folder.

  It serves a prepl on a loopback port the OS picks, prints the ready line
  `jonquil-cljs-prepl <port>`, and exits when stdin ends, taking node with it.
  node picks its own port too: it binds port 0 and writes the port it got
  to a file the patched create-socket reads, so no other process can take
  the port between a probe and node's bind."
  (:require [clojure.core.server :as server]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net InetAddress ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private script "cljs/repl/node_repl.js")
(def ^:private unpatched ".listen(PORT);")

(defn- script-text [] (slurp (io/resource script)))

(def ^:private run-dir
  "This JVM's own folder for the patched script and node's port files."
  (delay (.toFile (Files/createTempDirectory "jonquil-cljs-" (make-array FileAttribute 0)))))

(defn- port-file
  "Where node writes the port it bound for the env whose :port is token."
  [token]
  (io/file @run-dir (str "port-" token)))

(defn- patched
  "The listen call that binds node to 127.0.0.1 on a port the OS picks, then
  writes that port to port-<PORT> in dir through a rename, so a reader never
  sees half the number. PORT is the env's token, not a port."
  [^java.io.File dir]
  (str ".listen(0, \"127.0.0.1\", function () {"
       " var fs = require(\"fs\");"
       " var f = " (pr-str (str (.getPath dir) "/port-")) " + PORT;"
       " fs.writeFileSync(f + \".tmp\", String(this.address().port));"
       " fs.renameSync(f + \".tmp\", f); });"))

(def ^:private patched-url
  "The patched script, written once per JVM. deleteOnExit runs in reverse
  registration order, so the folders are registered outermost first and the
  file last: the file goes first, then each folder is empty when its turn
  comes. The pid file is registered after the script, so it goes first."
  (delay
    (let [dir @run-dir
          f (io/file dir script)]
      (io/make-parents f)
      (doseq [d (reverse (take-while some? (iterate #(.getParentFile ^java.io.File %) (.getParentFile f))))
              :when (.startsWith (.getPath ^java.io.File d) (.getPath dir))]
        (.deleteOnExit ^java.io.File d))
      (spit f (str/replace (script-text) unpatched (patched dir)))
      (.deleteOnExit f)
      ;; Jonquil's leftovers prune skips a folder whose pid is running.
      (let [pid (io/file dir "pid")]
        (spit pid (str (.pid (java.lang.ProcessHandle/current))))
        (.deleteOnExit pid))
      (.toURL (.toURI f)))))

(defn- loopback-loader
  "A classloader answering node_repl.js with a copy that listens on
  127.0.0.1 only; every other resource comes from parent."
  [^ClassLoader parent]
  (let [url @patched-url]
    ;; Child-first for this one name: a plain URLClassLoader asks its
    ;; parent first, which finds the jar's unpatched script.
    (proxy [ClassLoader] [parent]
      (getResource [n]
        (if (= n script) url (proxy-super getResource n))))))

(def ^:private next-token (atom 0))

(defn accept
  "Per connection: a ClojureScript prepl on node, bound to loopback."
  []
  (let [t (Thread/currentThread)]
    (.setContextClassLoader t (loopback-loader (.getContextClassLoader t)))
    (require 'cljs.server.node)
    ((resolve 'cljs.server.node/prepl) {:env-opts {:host "127.0.0.1" :port (swap! next-token inc)}})))

(defn- port-file-socket!
  "Replaces cljs.repl.node/create-socket, which setup calls every 50 ms until
  node answers, with one that reads the port node wrote for this env's token
  and connects to it. A missing file throws, as a refused connect does, so
  setup tries again. The file goes once the connect succeeds. Exits 2 when
  the function isn't the one this was written against."
  []
  (require 'cljs.repl.node)
  (let [v (resolve 'cljs.repl.node/create-socket)
        orig @v]
    (when-not (= '([host port]) (:arglists (meta v)))
      (binding [*out* *err*]
        (println (str "jonquil: cljs.repl.node/create-socket has arglists " (:arglists (meta v))
                      ", not ([host port]); this ClojureScript version isn't supported")))
      (System/exit 2))
    (alter-var-root
     v
     (fn [_]
       (fn [host token]
         (let [f (port-file token)
               port (or (parse-long (str/trim (slurp f)))
                        (throw (ex-info "no port in node's port file" {:file (str f)})))
               s (orig host port)]
           (.delete ^java.io.File f)
           s))))))

(def ^:private http-port (atom nil))

(def ^:private page-token
  "The owning window's token (the `token:<uuid>` argument), carried in the
  page URL as /?t=<token>. nil when the caller sent none: then no gate
  installs and every URL stays bare, as before the token existed."
  (atom nil))

(def ^:private prebound
  "The HTTP ServerSocket bound in -main before the ready line, so a taken
  port is reported at start. The replaced cljs.repl.server/start takes it
  the first time it runs and clears the atom; later calls bind afresh."
  (atom nil))

(defn browser-accept
  "Per connection: a ClojureScript prepl on the browser REPL. The HTTP server
  comes up on first use (cljs.repl.server/start, replaced to bind loopback)
  and no browser is launched: Jonquil opens the page."
  []
  (require 'cljs.server.browser)
  ((resolve 'cljs.server.browser/prepl)
   {:env-opts {:host "127.0.0.1" :port @http-port :launch-browser false}}))

(defn- loopback-start!
  "Replaces cljs.repl.server/start with the same body bound to 127.0.0.1
  (ClojureScript's own listens on every interface). Exits 2 when the
  function isn't the one this was written against."
  []
  (require 'cljs.repl.server)
  (let [v (resolve 'cljs.repl.server/start)
        loop-var (resolve 'cljs.repl.server/server-loop)
        state-var (resolve 'cljs.repl.server/state)]
    (when-not (= '([opts]) (:arglists (meta v)))
      (binding [*out* *err*]
        (println (str "jonquil: cljs.repl.server/start has arglists " (:arglists (meta v))
                      ", not ([opts]); this ClojureScript version isn't supported")))
      (System/exit 2))
    (alter-var-root
     v
     (fn [_]
       (fn [opts]
         (let [[old _] (reset-vals! prebound nil)
               ss (or old (ServerSocket. (int (:port opts)) 50 (InetAddress/getByName "127.0.0.1")))]
           (.start (Thread. ((ns-resolve 'clojure.core 'binding-conveyor-fn)
                             (fn [] (@loop-var opts ss)))))
           (swap! @state-var (fn [old] (assoc old :socket ss :port (:port opts))))))))))

(defn- peer-gone?
  "True when the page's end of a held connection has closed: a read that
  ends (-1) or fails, rather than timing out, means nothing is listening
  there. The page sent its whole request already, so a live one has nothing
  more to read."
  [^java.net.Socket conn]
  (or (.isClosed conn)
      (try (.setSoTimeout conn 1)
           (neg? (.read (.getInputStream conn)))
           (catch java.net.SocketTimeoutException _ false)
           (catch java.io.IOException _ true)
           (finally (try (.setSoTimeout conn 0) (catch Throwable _ nil))))))

(def ^:private page-gone?
  "Set when the only queued page connections were closed, or the page said
  goodbye (page-bye!), until a live one turns up again (the page reloaded
  and posted)."
  (atom false))

(defn- gone-message []
  ;; Byte for byte the URL Jonquil opened (the fail-fast live tests pin
  ;; "reload <opened-url>" verbatim), so the token spelling matches
  ;; jonquil.cljs-node/page-url exactly.
  (str "the page is gone (its tab closed or left the page): reload http://127.0.0.1:"
       @http-port (when-let [t @page-token] (str "/?t=" t)) " and evaluate again"))

(def ^:private reload-grace-ms
  "How long an evaluation waits for a page once the last one has gone, so a
  Run pressed right after a reload finds the reloaded page."
  2000)

(defn- live-connection!
  "Replaces cljs.repl.server/connection, which hands an evaluation the
  connection a page holds open for it, or waits for one. Its own check is
  .isClosed, which a page closing its end doesn't change, so an evaluation
  sent after the tab closed was written into a dead socket and waited for
  an answer until Stop. This one drops connections whose page has gone and,
  when that leaves none, waits reload-grace-ms for a page, then throws. With
  none queued from the start (the setup, before any page connected) it waits
  as before. Exits 2 when the function isn't the one this was written
  against."
  []
  (let [v (resolve 'cljs.repl.server/connection)
        lock @(resolve 'cljs.repl.server/lock)
        ^java.util.LinkedList connq @(resolve 'cljs.repl.server/connq)
        ^java.util.LinkedList promiseq @(resolve 'cljs.repl.server/promiseq)]
    (when-not (= '([]) (:arglists (meta v)))
      (binding [*out* *err*]
        (println (str "jonquil: cljs.repl.server/connection has arglists " (:arglists (meta v))
                      ", not ([]); this ClojureScript version isn't supported")))
      (System/exit 2))
    (alter-var-root
     v
     (fn [_]
       (fn []
         (let [p (promise)
               gone? (locking lock
                       (let [[conn dropped?] (loop [dropped? false]
                                               (if-let [c (.poll connq)]
                                                 (if (peer-gone? c)
                                                   (do (try (.close ^java.net.Socket c) (catch Throwable _ nil))
                                                       (recur true))
                                                   [c dropped?])
                                                 [nil dropped?]))]
                         (if conn
                           (do (reset! page-gone? false) (deliver p conn) false)
                           (do (.offer promiseq p)
                               (when dropped? (reset! page-gone? true))
                               @page-gone?))))]
           (when (and gone?
                      (= ::none (deref p reload-grace-ms ::none))
                      ;; Still queued: take it back, unless a page delivered
                      ;; to it in between.
                      (locking lock (.remove promiseq p) (not (realized? p))))
             (throw (ex-info (gone-message)
                             {})))
           (when (and gone? (realized? p)) (reset! page-gone? false))
           p))))))

(def ^:private page-gen
  "Counts the pages that connected (each :ready). A goodbye names the page
  it came from, so a reload's late goodbye can't close the new page's
  connection."
  (atom 0))

(def ^:dynamic ^:private *bye-gen*
  "Bound while the :ready handler runs, to the connecting page's number:
  send-for-eval then appends the console forwarding and the pagehide
  listener to that page's setup."
  nil)

(defn- bye-js
  "Plain JS for a page: on pagehide (tab closed, reloaded or navigated away)
  it beacons {:type :jonquil/bye :srv server :gen gen} to the REPL server.
  Chrome holds the page's open connection for about a minute after it
  leaves (measured 2026-10-22: 56 s from about:blank to CLOSE_WAIT), so the
  socket alone can't tell the server in time. The goodbye names its server
  (review 2026-10-23): page generations restart at 1 in every process, so
  after an auto-reload the OLD page's late goodbye could match the NEW
  page's generation; the server ignores a goodbye for another server."
  [server gen]
  (str "\n;(function(){var u=location.origin+'/';"
       "window.addEventListener('pagehide',function(){"
       "try{navigator.sendBeacon(u,'{:type :jonquil/bye :srv " (pr-str server) " :gen " gen "}')}catch(e){}});})();"))

(def ^:private console-flush-ms
  "How long forwarded console text gathers before one repl-print sends it.
  estimate: long enough that the first send leaves well after the setup's
  own :result (see console-js), short enough to read as live."
  100)

(def ^:private console-max-chars
  "The most console text one flush forwards; the rest is counted and named
  in a note. estimate: a page logging in a loop shouldn't post megabytes."
  100000)

(def ^:private console-js
  "Plain JS for a page: console.log, info, warn, error and debug still log in
  the browser and also print to Jonquil's console, through the browser
  REPL's own clojure.browser.repl/repl-print, the path println takes. A
  string prints as it is, any other value as pr-str prints it, and the
  arguments are joined by spaces.

  The text gathers for console-flush-ms and goes out as one repl-print, at
  most console-max-chars of it. Each send is its own numbered post, and
  after :ready the server takes the first number that arrives as the start:
  a print that beat the setup's :result there would leave that result, and
  the page's connection, unhandled. The delay keeps the first console send
  well behind it, and gathering keeps a page that logs in a loop to a few
  posts a second. busy stops a console call made synchronously while
  forwarding (inside pr-str, say) from forwarding again, and the outer try
  leaves a page without the REPL client alone."
  (str "\n;(function(){try{var c=window.console,p=clojure.browser.repl.repl_print,busy=false,"
       "buf=[],n=0,timer=null,max=" console-max-chars ";"
       "if(!c||c.__jonquil)return;c.__jonquil=true;"
       "function flush(){timer=null;var s=buf.join(''),over=n-max;buf=[];n=0;"
       "if(over>0)s+='; ['+over+' more characters of console output dropped]\\n';"
       "busy=true;try{p(s)}catch(e){}finally{busy=false}}"
       "['log','info','warn','error','debug'].forEach(function(k){var o=c[k];"
       "if(typeof o!=='function')return;"
       "c[k]=function(){o.apply(c,arguments);if(busy)return;busy=true;"
       "try{var t=Array.prototype.map.call(arguments,function(a){"
       "if(typeof a==='string')return a;"
       "try{return cljs.core.pr_str(a)}catch(e){return String(a)}}).join(' ')+'\\n';"
       "if(n<max)buf.push(t.slice(0,max-n));n+=t.length;"
       "if(!timer)timer=setTimeout(flush," console-flush-ms ")}"
       "catch(e){}finally{busy=false}}});}catch(e){}})();"))

(def ^:private server-gen
  "This process's identity for the page's keep-fresh ping (prefixed so a
  foreign server's 200 is never mistaken for a restart): a restart answers
  a different one, and the page reloads itself."
  (str "jonquil-gen:" (java.util.UUID/randomUUID)))

(defn- ping-js
  "Plain JS for a page: polls the REPL server (the same POST path the bye
  beacon uses) every five seconds while healthy, so devtools' network
  panel stays quiet, and once a second for the first thirty after the
  server stops answering (a restart usually follows at once), then every
  five again. An answer naming another generation means a new process
  owns this port (a restart), so the page reloads itself; the fresh page
  carries the new generation, so there is no reload loop. A non-200 answer
  counts as a failure too (Jonquil's handler always answers 200, and a
  program that later owns the port may answer 404 or 501 to POST /), and a
  page whose server never answers right gives up after ~750 failed pings,
  about an hour in a foreground tab, so an orphaned tab stops POSTing
  eventually; a Run after the give-up needs the tab reloaded by hand, as
  reload-message says. One loop per
  window (__jonquil_ping), bfcache restores included. The answer is
  prefixed (gen-prefix), so a 200 from some other program that later owns
  the port ends the loop instead of reloading the page into it (review
  2026-10-23: SPA dev servers answer any POST / with 200 and a body)."
  [gen]
  (str "\n;(function(){try{if(window.__jonquil_ping)return;window.__jonquil_ping=true;"
       "var g=" (pr-str gen) ",p=\"jonquil-gen:\",d=5000,e=0;"
       "function next(){setTimeout(tick,d)}"
       "function fail(){e++;if(e>=750)return;d=e<30?1000:5000;next()}"
       "function tick(){var x=new XMLHttpRequest();"
       "x.open(\"POST\",location.origin+\"/\",true);x.timeout=4000;"
       "x.onload=function(){var r=(x.responseText||\"\");"
       "if(x.status!==200){fail()}"
       "else if(r.indexOf(p)!==0){}"
       "else if(r!==g){location.reload()}"
       "else{e=0;d=5000;next()}};"
       "x.onerror=x.ontimeout=fail;"
       "try{x.send(\"{:type :jonquil/ping}\")}catch(ex){fail()}}"
       "next();}catch(ex){}})();"))

(defn- page-bye!
  "Wires the pagehide goodbye, the keep-fresh ping and, with :console?
  (the default), the console forwarding: send-for-eval appends console-js,
  ping-js and bye-js to the setup the
  :ready handler sends each page that connects, and a :jonquil/bye post from
  the current page drops the queued connections, marks the page gone, and
  fails an evaluation still waiting for that page's answer. A goodbye from
  an earlier page is ignored. Exits 2 when send-for-eval or the :ready
  handler isn't the one this was written against."
  [{:keys [console?]}]
  (require 'cljs.repl.browser)
  (let [sfe (resolve 'cljs.repl.browser/send-for-eval)
        orig-sfe @sfe
        handle-post @(resolve 'cljs.repl.browser/handle-post)
        orig-ready (get-method handle-post :ready)
        return-value (resolve 'cljs.repl.browser/return-value)
        send-and-close @(resolve 'cljs.repl.server/send-and-close)
        lock @(resolve 'cljs.repl.server/lock)
        ^java.util.LinkedList connq @(resolve 'cljs.repl.server/connq)]
    (when-not (and (= '([form return-value-fn] [conn form return-value-fn]) (:arglists (meta sfe)))
                   orig-ready return-value)
      (binding [*out* *err*]
        (println (str "jonquil: cljs.repl.browser/send-for-eval has arglists " (:arglists (meta sfe))
                      " or no :ready handler; this ClojureScript version isn't supported")))
      (System/exit 2))
    (alter-var-root
     sfe
     (fn [_]
       (fn
         ([form f] (orig-sfe form f))
         ([conn form f] (orig-sfe conn
                                  (if *bye-gen*
                                    (str form (when console? console-js)
                                         (ping-js server-gen) (bye-js server-gen *bye-gen*))
                                    form)
                                  f)))))
    (.addMethod ^clojure.lang.MultiFn handle-post :ready
                (fn [m conn opts]
                  (binding [*bye-gen* (swap! page-gen inc)]
                    (orig-ready m conn opts))))
    (.addMethod ^clojure.lang.MultiFn handle-post :jonquil/ping
                (fn [_ conn _]
                  ;; the page's keep-fresh poll: answer who owns this port
                  (send-and-close conn 200 server-gen)))
    (.addMethod ^clojure.lang.MultiFn handle-post :jonquil/bye
                (fn [{:keys [srv gen]} conn _]
                  (when (and (= srv server-gen) (= gen @page-gen))
                    (locking lock
                      (doseq [c connq] (try (.close ^java.net.Socket c) (catch Throwable _ nil)))
                      (.clear connq)
                      (reset! page-gone? true))
                    (@return-value (pr-str {:status :error
                                            :value (gone-message)})))
                  (send-and-close conn 200 "ok")))))

(defn- query-token
  "The t parameter's value in a query string, or nil when none."
  [query-str]
  (some #(when (str/starts-with? % "t=") (subs % 2))
        (str/split (str query-str) #"&")))

(def ^:private stale-page
  "What a tab from another session gets instead of the REPL page: no
  script at all, so nothing pings and nothing reconnects."
  (str "<html><head><meta charset=\"utf-8\"><title>Jonquil</title></head><body>"
       "<h2>This tab belongs to an older Jonquil session.</h2>"
       "<p>Close this tab.</p>"
       "</body></html>"))

(defn- stale-tab-gate!
  "Turns away a page request carrying another window's token: the FIRST
  :get entry in cljs.repl.server/handlers (dispatch takes the first whose
  pred answers true) serves stale-page when / or /index.html is asked for
  with a t parameter that differs from this launcher's token. A request
  with no t at all (a hand-typed URL, a pre-token tab) falls through to
  the stock handlers. Exits 2 when the handler table isn't the shape this
  was written against."
  []
  (require 'cljs.repl.server)
  (let [handlers @(resolve 'cljs.repl.server/handlers)
        send-and-close @(resolve 'cljs.repl.server/send-and-close)
        entry? (fn [m] (and (map? m) (fn? (:pred m)) (fn? (:handler m))))
        token @page-token]
    (when-not (and (instance? clojure.lang.Atom handlers)
                   (map? @handlers)
                   (vector? (:get @handlers))
                   (seq (:get @handlers))
                   (every? entry? (:get @handlers)))
      (binding [*out* *err*]
        (println (str "jonquil: cljs.repl.server/handlers isn't a vector of :get {:pred :handler}"
                      " entries; this ClojureScript version isn't supported")))
      (System/exit 2))
    (swap! handlers update :get
           (fn [v]
             (into [{:pred (fn [{:keys [path query-str]} _ _]
                             (let [t (query-token query-str)]
                               (and (contains? #{"/" "/index.html"} path)
                                    (some? t)
                                    (not= token t))))
                     :handler (fn [_request conn _opts]
                                (send-and-close conn 200 stale-page "text/html" "UTF-8"))}]
                   v)))))

(defn- make-fifo!
  "The stdin FIFO, created as input in this JVM's own temp folder (never the
  working directory: that is the user's project) and opened \"rw\": a
  read-write open never blocks on a FIFO, where a write-only open would
  wait for read-line's first reader. {:path p :raf open-file}, or nil when
  mkfifo or the open failed, with one stderr line saying why. Opened here,
  before the ready line, so both failures take the one stdin:none path: an
  open that failed later, in the relay, used to exit the whole launcher
  (measured: before its ready line, so the session never started). read-line
  is then unavailable; the session is otherwise unchanged."
  []
  (let [f (io/file @run-dir "input")
        unavailable (fn [why]
                      (binding [*out* *err*]
                        (println (str "jonquil: " why "; read-line won't be available")))
                      nil)]
    (try
      (let [exit (.waitFor (.start (ProcessBuilder. ["mkfifo" (.getPath f)])))]
        (if (zero? exit)
          (do (.deleteOnExit f)
              {:path (.getPath f) :raf (java.io.RandomAccessFile. (.getPath f) "rw")})
          (unavailable (str "mkfifo " (.getPath f) " exited " exit))))
      (catch Exception e
        (unavailable (str "couldn't make the input pipe (" (.getMessage e) ")"))))))

(defn- relay-stdin!
  "Relays this process's stdin into the FIFO make-fifo! opened, a line at a
  time, so a blocked read-line in node gets the line Jonquil sends.
  Returns when stdin ends; the caller exits, and closing the FIFO's only
  writer makes a blocked read return 0 bytes, which read-line maps to nil."
  [^java.io.RandomAccessFile fifo-raf]
  (with-open [raf fifo-raf
              in (io/reader System/in :encoding "UTF-8")]
    (loop []
      (when-let [line (.readLine ^java.io.BufferedReader in)]
        (.write raf (.getBytes (str line "\n") java.nio.charset.StandardCharsets/UTF_8))
        (recur)))))

(defn- destroy-descendants []
  (doseq [p (iterator-seq (.iterator (.descendants (java.lang.ProcessHandle/current))))]
    (.destroy ^java.lang.ProcessHandle p)))

(defn -main
  "No args: ClojureScript on node. [\"browser\" http-port]: the browser
  REPL; a trailing \"no-console\" leaves the page's console unforwarded
  ({:cljs {:forward-console? false}})."
  [& args]
  (let [browser? (= "browser" (first args))]
    (if browser?
      (let [port (some-> (second args) parse-long)]
        (when-not port
          (binding [*out* *err*]
            (println "usage: jonquil.cljs-node-launch browser <http-port>"))
          (System/exit 2))
        (reset! http-port port)
        (try (reset! prebound (ServerSocket. (int port) 50 (InetAddress/getByName "127.0.0.1")))
             (catch java.io.IOException _
               (println (str "jonquil-cljs-http-port-taken " port))
               (flush)
               (System/exit 3)))
        (reset! page-token (some #(when (str/starts-with? % "token:") (subs % 6)) args))
        (loopback-start!)
        (live-connection!)
        ;; page-bye!'s require of cljs.repl.browser registers the stock
        ;; :get handlers; the gate prepends to them, so it goes after.
        (page-bye! {:console? (not (some #(= "no-console" %) args))})
        (when @page-token (stale-tab-gate!)))
      (do (when-not (str/includes? (script-text) unpatched)
            (binding [*out* *err*]
              (println (str "jonquil: " script " has no " unpatched
                            " to bind to loopback; this ClojureScript version isn't supported")))
            (System/exit 2))
          (port-file-socket!)))
    (let [fifo (when-not browser? (make-fifo!))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable destroy-descendants))
      ;; Jonquil holds our stdin for its life, so stdin ending means it is
      ;; gone. On node the watcher doubles as the read-line relay.
      (future (try (if fifo
                     (relay-stdin! (:raf fifo))
                     (let [in System/in] (loop [] (when-not (neg? (.read in)) (recur)))))
                   (catch Throwable _ nil))
              (System/exit 0))
      (let [ss (server/start-server {:name "jonquil-cljs" :address "127.0.0.1" :port 0
                                     :accept (if browser?
                                               'jonquil.cljs-node-launch/browser-accept
                                               'jonquil.cljs-node-launch/accept)})]
        (println (str "jonquil-cljs-prepl " (.getLocalPort ^ServerSocket ss)
                      (when browser? (str " http://127.0.0.1:" @http-port))
                      (when-not browser? (str " stdin:" (or (:path fifo) "none"))))))
      (flush)
      @(promise))))
