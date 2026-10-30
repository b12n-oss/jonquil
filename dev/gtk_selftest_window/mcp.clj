(ns gtk-selftest-window.mcp
  "The notifying hooks the MCP server drives (:mcp-run!*, :mcp-eval!*,
  :mcp-run-selection!*): each calls notify exactly once and returns a reader
  of the output collected so far.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each.
  A hook runs on the GTK thread through on-gtk; the row thread then derefs
  the notify promise with a deadline, never inside on-gtk."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.mcp.json :as json]
            [jonquil.mcp.listen :as listen]
            [jonquil.mcp.window :as window]
            [jonquil.tabs :as tabs]
            [temp-dirs]))

(def ^:private DEADLINE-MS 5000)

(defn- fire!
  "Runs the hook (f notify) or (f a notify) ... on the GTK thread; returns
  [reader done-promise], where the row's own notify delivers the map."
  [f & args]
  (on-gtk (let [done (promise)
                reader (apply f (concat args [(fn [m] (deliver done m))]))]
            [reader done])))

(defn- await-done [done]
  (deref done DEADLINE-MS ::timeout))

(defn row-mcp-eval-done [ui]
  (go-row :mcp-eval-done support/check
    (let [mark (on-gtk (support/out-end-offset ui))
          [reader done] (fire! (:mcp-eval!* ui) "(println \"hi\") (+ 1 2)")
          m (await-done done)
          {:keys [result output]} (:done m)
          echoed? (await-until 3000 20 #(let [t (support/since ui mark)]
                                          (and (str/includes? t "(println \"hi\") (+ 1 2)")
                                               (str/includes? t "\n=> 3"))))
          read (on-gtk (reader))]
      (on-gtk (support/check :mcp-eval-done
                             (and (= :ok (:status result)) (= "hi\n" output)
                                  (= "hi\n" read) echoed?)
                             {:msg m :read read :echoed echoed?
                              :console (support/since ui mark)})))))

(defn row-mcp-eval-busy [ui]
  (go-row :mcp-eval-busy support/check
    (let [[_ done1] (fire! (:mcp-eval!* ui) "(Thread/sleep 1500)")
          mark (on-gtk (support/out-end-offset ui))
          [_ done2] (fire! (:mcp-eval!* ui) "(+ 1 1)")
          m2 (await-done done2)
          busy (:busy m2)
          shown? (await-until 3000 20 #(let [t (support/since ui mark)]
                                         (and (str/includes? t "(+ 1 1)")
                                              (str/includes? t "already in progress"))))
          m1 (await-done done1)
          idle? (await-until 3000 20 #(support/idle? ui))]
      (on-gtk (support/check :mcp-eval-busy
                             (and (string? busy) (not (str/blank? busy)) shown?
                                  (:done m1) idle?)
                             {:m2 m2 :m1 m1 :shown shown? :idle idle?
                              :console (support/since ui mark)})))))

(defn row-mcp-run-done [ui]
  (go-row :mcp-run-done support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(def a 6) (* a 7)")
                         m))
          [_ done] (fire! (:mcp-run!* ui))
          m (await-done done)
          {:keys [result output]} (:done m)
          shown? (await-until 3000 20 #(let [t (support/since ui mark)]
                                         (and (str/includes? t "; --- Running ")
                                              (str/includes? t "\n=> 42"))))]
      (on-gtk (support/check :mcp-run-done
                             (and (= :ok (:status result)) (= "" output) shown?)
                             {:msg m :shown shown? :console (support/since ui mark)})))))

(defn row-mcp-run-problem [ui]
  (go-row :mcp-run-problem support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(+ 1")
                         m))
          [_ done] (fire! (:mcp-run!* ui))
          m (await-done done)
          problem (:problem m)
          idle? (on-gtk (support/idle? ui))
          shown? (await-until 3000 20 #(let [t (support/since ui mark)
                                             i (str/index-of t "; --- Running ")]
                                         (and i (str/includes? (subs t i) "is never closed"))))]
      (on-gtk (support/check :mcp-run-problem
                             (and (string? problem) (str/includes? problem "is never closed")
                                  idle? shown?)
                             {:msg m :idle idle? :shown shown? :console (support/since ui mark)})))))

(defn- cljd-project!
  "A temp ClojureDart project (pubspec.yaml) with lib/x.cljd, like the tabs
  feature file's own fixture (private there); the file's path."
  []
  (let [dir (temp-dirs/temp-dir! "jonquil-selftest-mcp-")]
    (spit (io/file dir "pubspec.yaml") "name: other\n")
    (let [f (io/file dir "lib" "x.cljd")]
      (io/make-parents f)
      (spit f "(ns other.x)\n")
      (.getCanonicalPath f))))

(defn- close-extra-tabs!
  "Marks every tab but the first saved and closes it, then selects the
  first. GTK-thread only."
  [ui]
  (doseq [t (rest ((:tabs* ui)))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first ((:tabs* ui)))))
  (reset! (:alert-pending? ui) false))

(defn row-mcp-run-refused [ui]
  (go-row :mcp-run-refused support/check
    ;; Two tabs first: with one tab, opening a foreign file switches the
    ;; window's project instead of opening a refused third tab.
    (let [[mark expected] (on-gtk
                           (editor/set-text! (:ed (first ((:tabs* ui)))) ";; mcp\n")
                           ((:new-tab!* ui))
                           (editor/set-text! (:ed ((:active-tab* ui))) ";; two\n")
                           ((:open-file! ui) (cljd-project!))
                           (let [t ((:active-tab* ui))]
                             [(support/out-end-offset ui)
                              (tabs/refusal-message {:file "x.cljd" :tab-sel @(:project t)
                                                     :window-sel ((:selection* ui))})]))]
      (try
        (let [[_ done] (fire! (:mcp-run!* ui))
              m (await-done done)
              s (:refused m)
              shown? (await-until 3000 20 #(str/includes? (support/since ui mark) "so it can't run here."))
              [idle? banner?] (on-gtk [(support/idle? ui)
                                       (str/includes? (support/since ui mark) "; --- Running")])]
          (on-gtk (support/check :mcp-run-refused
                                 (and (= expected s) shown? idle? (not banner?))
                                 {:msg m :expected expected :shown shown? :idle idle?
                                  :console (support/since ui mark)})))
        (finally (on-gtk (close-extra-tabs! ui)))))))

(defn row-mcp-run-selection-done [ui]
  (go-row :mcp-run-selection-done support/check
    (let [text "(+ 1 1)\n(+ 2 2)"
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) text)
                         m))
          [_ done] (fire! (:mcp-run-selection!* ui) 8 15)
          m (await-done done)
          {:keys [result]} (:done m)
          shown? (await-until 3000 20 #(let [t (support/since ui mark)]
                                         (and (str/includes? t "(+ 2 2)")
                                              (str/includes? t "\n=> 4"))))
          sel (on-gtk (g/selection-offsets (:buf (:editor ui))))
          ;; A blank range: the empty line of "(+ 1 1)\n\n".
          _ (on-gtk (editor/set-text! (:editor ui) "(+ 1 1)\n\n"))
          [_ done2] (fire! (:mcp-run-selection!* ui) 8 8)
          m2 (await-done done2)]
      (on-gtk (support/check :mcp-run-selection-done
                             (and (= :ok (:status result)) (= "4" (:printed result))
                                  shown? (= [8 15] sel)
                                  (string? (:none m2)) (not (str/blank? (:none m2))))
                             {:msg m :shown shown? :sel sel :m2 m2
                              :console (support/since ui mark)})))))

;; --- the tool table over the live window -----------------------------
;; Tool calls block (promise derefs with deadlines): they run on the row
;; thread, never inside on-gtk. Reads of GTK state still go through on-gtk.

(defn- tools-for
  "A fresh window tool table over the live ui; its :call is (fn [name args])."
  [ui]
  (window/make-window-tools {:ui ui :dir (temp-dirs/temp-dir! "jonquil-selftest-mcp-dir-")}))

(defn- call
  "Calls tool tname with string-keyed args on the row thread."
  ([t tname] (call t tname {}))
  ([t tname args] ((:call t) tname args)))

(defn- file!
  "A file named name holding text in a fresh temp dir; its path."
  [name text]
  (let [f (io/file (temp-dirs/temp-dir! "jonquil-selftest-mcp-") name)]
    (io/make-parents f)
    (spit f text)
    (.getPath f)))

(defn- occupy-first!
  "Gives the first tab text so jonquil_open adds a tab. GTK-thread only."
  [ui]
  (editor/set-text! (:ed (first ((:tabs* ui)))) ";; mcp\n"))

(defn- tab-id [open-text]
  (some->> open-text (re-find #"Opened tab (\d+) ") second parse-long))

(defn- tab-line-of [t id]
  (->> (str/split-lines (:text (call t "jonquil_tabs")))
       (filter #(str/starts-with? % (str id " ")))
       first))

(defn- console-mark [t]
  (parse-long (second (re-find #"^mark (\d+)" (:text (call t "jonquil_console"))))))

(defn row-mcp-tools-open-read-select [ui]
  (go-row :mcp-tools-open-read-select support/check
    (let [t (tools-for ui)
          p1 (file! "one.clj" "(def one 1)\n")
          p2 (file! "two.clj" "(def two 2)\n")]
      (on-gtk (occupy-first! ui))
      (try
        (let [o1 (call t "jonquil_open" {"path" p1})
              id1 (tab-id (:text o1))
              read (call t "jonquil_read_tab")
              line1 (tab-line-of t id1)
              o2 (call t "jonquil_open" {"path" p2})
              id2 (tab-id (:text o2))
              line2 (tab-line-of t id2)
              sel (call t "jonquil_select_tab" {"id" id1})
              line1b (tab-line-of t id1)
              line2b (tab-line-of t id2)
              bad (call t "jonquil_select_tab" {"id" 9999})]
          (on-gtk (support/check :mcp-tools-open-read-select
                                 (and id1 id2 (not= id1 id2)
                                      (not (:error? o1)) (str/includes? (:text o1) p1)
                                      (= "(def one 1)\n" (:text read))
                                      (= (str id1 " * saved " p1) line1)
                                      (= (str id2 " * saved " p2) line2)
                                      (not (:error? sel))
                                      (= (str id1 " * saved " p1) line1b)
                                      (= (str id2 " - saved " p2) line2b)
                                      (:error? bad)
                                      (= "No tab with id 9999." (:text bad)))
                                 {:o1 o1 :o2 o2 :read read :line1 line1 :line2 line2
                                  :sel sel :line1b line1b :line2b line2b :bad bad})))
        (finally (on-gtk (close-extra-tabs! ui)))))))

(defn row-mcp-tools-replace-save [ui]
  (go-row :mcp-tools-replace-save support/check
    (let [t (tools-for ui)
          p (file! "rs.clj" "(def old 1)\n")
          new-text "(def fresh 2)\n"]
      (on-gtk (occupy-first! ui))
      (try
        (let [id (tab-id (:text (call t "jonquil_open" {"path" p})))
              r (call t "jonquil_replace_text" {"text" new-text})
              dirty-line (tab-line-of t id)
              ed-text (on-gtk (editor/editor-text (:ed ((:active-tab* ui)))))
              s (call t "jonquil_save")
              disk (slurp p)
              saved-line (tab-line-of t id)]
          (on-gtk (support/check :mcp-tools-replace-save
                                 (and id (not (:error? r))
                                      (= (str id " * dirty " p) dirty-line)
                                      (= new-text ed-text)
                                      (not (:error? s))
                                      (= new-text disk)
                                      (= (str id " * saved " p) saved-line))
                                 {:r r :dirty-line dirty-line :ed-text ed-text :s s
                                  :disk disk :saved-line saved-line})))
        (finally (on-gtk (close-extra-tabs! ui)))))))

(defn row-mcp-tools-replace-nul [ui]
  (go-row :mcp-tools-replace-nul support/check
    ;; Replacement text holding a NUL would land truncated at the
    ;; first NUL (GTK C strings). The tool refuses it
    ;; with an error naming the line, and the tab is untouched; without
    ;; the tool's own pre-check the funnel's nil would mislabel this
    ;; refusal as "read-only".
    (let [t (tools-for ui)
          p (file! "nul-replace.clj" "(def old 1)\n")]
      (on-gtk (occupy-first! ui))
      (try
        (let [id (tab-id (:text (call t "jonquil_open" {"path" p})))
              r (call t "jonquil_replace_text" {"text" "a\u0000b"})
              ed-text (on-gtk (editor/editor-text (:ed ((:active-tab* ui)))))]
          (on-gtk (support/check :mcp-tools-replace-nul
                                 (and id (:error? r)
                                      (str/includes? (:text r) "holds a NUL byte on line 1")
                                      (= "(def old 1)\n" ed-text))
                                 {:r r :ed-text ed-text})))
        (finally (on-gtk (close-extra-tabs! ui)))))))

(defn row-mcp-tools-run-eval-console [ui]
  (go-row :mcp-tools-run-eval-console support/check
    (let [t (tools-for ui)
          p (file! "run.clj" "(def y 7) y")
          c0 (call t "jonquil_console")
          mark (parse-long (second (re-find #"^mark (\d+)\n" (str (:text c0) "\n"))))]
      (on-gtk (occupy-first! ui))
      (try
        (let [ev (call t "jonquil_eval" {"code" "(+ 1 2)"})
              _ (call t "jonquil_open" {"path" p})
              run (call t "jonquil_run")
              c1 (call t "jonquil_console" {"since" mark})
              first-line (first (str/split-lines (:text c1)))
              body (:text c1)]
          (on-gtk (support/check :mcp-tools-run-eval-console
                                 (and (= (str "mark " mark) (first (str/split-lines (:text c0))))
                                      (not (:error? ev)) (str/ends-with? (:text ev) "=> 3")
                                      (not (:error? run)) (str/ends-with? (:text run) "=> 7")
                                      (str/starts-with? first-line "mark ")
                                      (str/includes? body "(+ 1 2)")
                                      (str/includes? body "; --- Running ")
                                      (str/includes? body "\n=> 3")
                                      (str/includes? body "\n=> 7"))
                                 {:mark mark :ev ev :run run :c1 c1})))
        (finally (on-gtk (close-extra-tabs! ui)))))))

(defn row-mcp-tools-run-selection [ui]
  (go-row :mcp-tools-run-selection support/check
    (let [t (tools-for ui)]
      (on-gtk (editor/set-text! (:editor ui) "(+ 1 1)\n(+ 2 2)"))
      (let [r (call t "jonquil_run_selection" {"start" 8 "end" 15})]
        (on-gtk (support/check :mcp-tools-run-selection
                               (and (not (:error? r)) (str/ends-with? (:text r) "=> 4"))
                               {:r r}))))))

(defn row-mcp-tools-timeout-stop [ui]
  (go-row :mcp-tools-timeout-stop support/check
    (let [t (tools-for ui)
          stop (call t "jonquil_stop")
          t0 (System/currentTimeMillis)
          to (call t "jonquil_eval" {"code" "(loop [] (recur))" "timeout_ms" 300})
          elapsed (- (System/currentTimeMillis) t0)
          after (call t "jonquil_eval" {"code" "(+ 2 2)"})]
      (on-gtk (support/check :mcp-tools-timeout-stop
                             (and (= "Nothing is running." (:text stop)) (not (:error? stop))
                                  (< elapsed 6000) (:error? to)
                                  (str/includes? (:text to) "Timed out after 300 ms")
                                  (not (:error? after)) (str/ends-with? (:text after) "=> 4"))
                             {:stop stop :elapsed elapsed :to to :after after})))))

(defn row-mcp-tools-waiting-input [ui]
  (go-row :mcp-tools-waiting-input support/check
    (let [t (tools-for ui)
          mark (console-mark t)
          t0 (System/currentTimeMillis)
          r (call t "jonquil_eval" {"code" "(read-line)" "timeout_ms" 10000})
          elapsed (- (System/currentTimeMillis) t0)
          _ (ev/send-input! ((:evaluator* ui)) "hi")
          idle? (await-until 5000 20 #(support/idle? ui))
          echoed? (await-until 3000 20 #(str/includes? (support/since ui mark) "\n=> \"hi\""))]
      (on-gtk (support/check :mcp-tools-waiting-input
                             (and (< elapsed 5000) (not (:error? r))
                                  (str/includes? (:text r) "waiting for console input")
                                  idle? echoed?)
                             {:r r :elapsed elapsed :idle idle? :echoed echoed?
                              :console (support/since ui mark)})))))

(defn row-mcp-tools-doc-complete-brackets-project [ui]
  (go-row :mcp-tools-doc-complete-brackets-project support/check
    (let [t (tools-for ui)
          doc (call t "jonquil_doc" {"symbol" "map"})
          comp (call t "jonquil_complete" {"prefix" "ma"})
          brackets (call t "jonquil_check_brackets" {"text" "(+ 1"})
          project (call t "jonquil_project")]
      (on-gtk (support/check :mcp-tools-doc-complete-brackets-project
                             (and (str/includes? (:text doc) "map") (str/includes? (:text doc) "[")
                                  (some #{"map"} (str/split-lines (:text comp)))
                                  (not (:error? brackets)) (not= "ok" (str/trim (:text brackets)))
                                  (str/includes? (:text brackets) "(")
                                  (str/includes? (:text project) "Dialect: "))
                             {:doc doc :comp comp :brackets brackets :project project})))))

;; --- the listener over the live window ---------------------------
;; Socket IO runs on the row thread, never inside on-gtk; every read has a
;; socket timeout. Each row owns its listener and stops it in finally.

(defn- connect [port]
  (let [s (java.net.Socket. (java.net.InetAddress/getByName "127.0.0.1") (int port))]
    (.setSoTimeout s DEADLINE-MS)
    {:socket s
     :in (io/reader (.getInputStream s) :encoding "UTF-8")
     :out (io/writer (.getOutputStream s) :encoding "UTF-8")}))

(defn- close-client! [c]
  (try (.close ^java.net.Socket (:socket c)) (catch Throwable _ nil)))

(defn- rpc!
  "Sends one JSON-RPC request and reads one response line (nil on timeout)."
  [c id method params]
  (.write ^java.io.Writer (:out c)
          (str (json/encode {"jsonrpc" "2.0" "id" id "method" method "params" params}) "\n"))
  (.flush ^java.io.Writer (:out c))
  (try (some-> (.readLine ^java.io.BufferedReader (:in c)) json/decode)
       (catch java.net.SocketTimeoutException _ nil)))

(defn- initialize! [c]
  (rpc! c 1 "initialize" {"protocolVersion" "2024-11-05" "capabilities" {}
                          "clientInfo" {"name" "selftest" "version" "0"}}))

(defn- tool-text [resp]
  (get-in resp ["result" "content" 0 "text"]))

(defn- call-tool! [c id tname args]
  (tool-text (rpc! c id "tools/call" {"name" tname "arguments" args})))

(defn- listener! [ui]
  (let [sp (support/fresh-settings-path)]
    [sp (listen/start-listener! {:ui ui :dir (temp-dirs/temp-dir! "jonquil-selftest-mcp-dir-")
                                 :settings-path sp})]))

(defn- registry-files [sp]
  (vec (.listFiles (io/file (listen/registry-dir sp)))))

(defn row-mcp-listen-serves-and-registers [ui]
  (go-row :mcp-listen-serves-and-registers support/check
    (let [[sp l] (listener! ui)
          mark (on-gtk (support/out-end-offset ui))
          c (atom nil)]
      (try
        (reset! c (connect (:port l)))
        (let [init (initialize! @c)
              ev (call-tool! @c 2 "jonquil_eval" {"code" "(+ 1 2)"})
              files (registry-files sp)
              entry (some-> (first files) slurp edn/read-string)
              echoed? (await-until 3000 20 #(let [t (support/since ui mark)]
                                              (and (str/includes? t "(+ 1 2)")
                                                   (str/includes? t "\n=> 3"))))]
          (on-gtk (support/check :mcp-listen-serves-and-registers
                                 (and (some? (get-in init ["result" "serverInfo"]))
                                      (string? ev) (str/ends-with? ev "=> 3")
                                      (= 1 (count files))
                                      (= (:port l) (:port entry))
                                      echoed?)
                                 {:init init :ev ev :files (map str files) :entry entry
                                  :echoed echoed? :console (support/since ui mark)})))
        (finally
          (when @c (close-client! @c))
          ((:stop! l)))))))

(defn row-mcp-listen-detach-leaves-window [ui]
  (go-row :mcp-listen-detach-leaves-window support/check
    (let [[_ l] (listener! ui)
          c1 (atom nil)
          c2 (atom nil)]
      (try
        (reset! c1 (connect (:port l)))
        (initialize! @c1)
        (close-client! @c1)
        (let [idle? (await-until 3000 20 #(support/idle? ui))
              read (on-gtk (some? (editor/editor-text (:ed ((:active-tab* ui))))))
              _ (reset! c2 (connect (:port l)))
              _ (initialize! @c2)
              tabs (call-tool! @c2 2 "jonquil_tabs" {})]
          (on-gtk (support/check :mcp-listen-detach-leaves-window
                                 (and idle? read (string? tabs) (not (str/blank? tabs)))
                                 {:idle idle? :read read :tabs tabs})))
        (finally
          (when @c1 (close-client! @c1))
          (when @c2 (close-client! @c2))
          ((:stop! l)))))))

(defn row-mcp-listen-stop-cleans [ui]
  (go-row :mcp-listen-stop-cleans support/check
    (let [[sp l] (listener! ui)]
      (try
        (let [before (count (registry-files sp))
              _ ((:stop! l))
              after (count (registry-files sp))
              refused? (try (close-client! (connect (:port l))) false
                            (catch java.io.IOException _ true))]
          (on-gtk (support/check :mcp-listen-stop-cleans
                                 (and (= 1 before) (zero? after) refused?)
                                 {:before before :after after :refused refused?})))
        (finally ((:stop! l)))))))
