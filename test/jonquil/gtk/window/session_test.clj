(ns jonquil.gtk.window.session-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.last-session :as last-session]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- settings-path
  "A settings.edn path in a fresh temp dir, holding text (none when nil)."
  [text]
  (let [p (str (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-session-test-"))) "/settings.edn")]
    (when text (spit p text))
    p))

(defn- ctx
  "A tab-ctx with three tabs (the middle one Untitled) under root /r; the
  active one is index active, or none."
  [settings-path* active]
  (let [ts [{:path (atom "/r/a.clj")} {:path (atom nil)} {:path (atom "/r/b.clj")}]]
    {:tabs (atom ts) :tree-pane {:state (atom {:root "/r"})} :settings-path* settings-path*
     :active-tab (fn [] (when active (nth ts active)))}))

(deftest reopen-tabs-test
  (is (true? (#'win-session/reopen-tabs? (settings-path nil))))
  (is (true? (#'win-session/reopen-tabs? (settings-path "{:reopen-tabs? true}"))))
  (is (false? (#'win-session/reopen-tabs? (settings-path "{:reopen-tabs? false}")))))

(deftest session-snapshot-test
  (is (= {:root "/r" :tabs ["/r/a.clj" "/r/b.clj"] :active "/r/b.clj"} (#'win-session/session-snapshot (ctx nil 2))))
  (is (= {:root "/r" :tabs ["/r/a.clj" "/r/b.clj"] :active nil} (#'win-session/session-snapshot (ctx nil nil)))))

(deftest save-session-remembers-the-tabs-test
  (let [p (settings-path nil)]
    (is (nil? (#'win-session/save-session! (ctx (atom p) 0))))
    (is (= {:root "/r" :tabs ["/r/a.clj" "/r/b.clj"] :active "/r/a.clj"}
           (last-session/entry-for (:sessions (last-session/load-sessions (last-session/sessions-path p))) "/r"))))
  (let [off (settings-path "{:reopen-tabs? false}")]
    (is (nil? (#'win-session/save-session! (ctx (atom off) 0))))
    (is (not (.exists (io/file (last-session/sessions-path off)))))))

(deftest session-changed-saves-only-what-changed-test
  (let [saved (atom []) said (atom []) answer (atom nil)
        live (atom false) last-snap (atom nil) err (atom nil)
        c (assoc (ctx (atom (settings-path nil)) 0)
                 :session-live? live :session-last last-snap :session-error err :cv ::cv)]
    (with-redefs [last-session/save-sessions! (fn [_ sessions] (swap! saved conj (first sessions)) @answer)
                  cview/append! (fn [cv k t] (swap! said conj [cv k t]))]
      (#'win-session/session-changed! (dissoc c :session-live?))
      (#'win-session/session-changed! c)
      (is (= [] @saved))
      (reset! live true)
      (#'win-session/session-changed! c)
      (#'win-session/session-changed! c)
      (is (= 1 (count @saved)))
      (is (= (#'win-session/session-snapshot c) @last-snap))
      (reset! (:path (second @(:tabs c))) "/r/c.clj")
      (reset! answer "disk full")
      (#'win-session/session-changed! c)
      (#'win-session/session-changed! c)
      (reset! answer nil)
      (#'win-session/session-changed! c))
    (is (= 4 (count @saved)))
    (is (= [[::cv :system "; sessions: disk full\n"]] @said))
    (is (nil? @err))
    (is (= ["/r/a.clj" "/r/c.clj" "/r/b.clj"] (:tabs @last-snap)))))
