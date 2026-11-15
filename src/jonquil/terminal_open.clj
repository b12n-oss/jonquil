(ns jonquil.terminal-open
  "Opening the system's terminal in a folder: which folder a file belongs
  to, the command that opens a terminal there, and running it. No GTK here.

  On macOS the terminal is an app, opened with `open -a <app> <folder>`;
  Terminal unless settings.edn's :terminal names another. Elsewhere it is a
  program started with the folder as its working directory, which every
  terminal honours: the :terminal setting, or the first of linux-terminals
  on the PATH. Env JONQUIL_OPEN_TERMINAL names a program to run instead,
  with the folder as its only argument, for the tests."
  (:require [clojure.string :as str]
            [jolt.process :as p]
            [jonquil.dialect :as dialect]
            [jonquil.login-path :as login-path]
            [jonquil.tool-path :as tool-path]))

(def mac-default
  "The app a Mac opens when settings.edn names none."
  "Terminal")

(def linux-terminals
  "The programs tried, in order, when settings.edn names none: the
  desktop's own chooser first, then the common terminals."
  ["xdg-terminal-exec" "x-terminal-emulator" "gnome-terminal" "konsole" "xfce4-terminal"
   "kitty" "alacritty" "foot" "wezterm" "xterm"])

(defn folder
  "The folder a terminal opens in for the file at path: its project's
  folder when it is in one (jonquil.dialect/find-project), else the file's
  own folder, else home for a tab with no file."
  [path home]
  (cond
    (str/blank? path) home
    :else (or (:dir (dialect/find-project path))
              (some-> (java.io.File. ^String path) .getAbsoluteFile .getParent)
              home)))

(defn command
  "{:argv [program arg ...] :dir folder-or-nil} for opening a terminal in
  dir, or {:problem sentence}. opts is {:os os.name :setting the :terminal
  setting or nil :override env JONQUIL_OPEN_TERMINAL or nil :on-path? (fn
  [program]) true when the program can be run}."
  [dir {:keys [os setting override on-path?]}]
  (let [mac? (= "Mac OS X" os)]
    (cond
      (not (str/blank? override)) {:argv [override dir] :dir dir}
      mac? {:argv ["open" "-a" (if (str/blank? setting) mac-default setting) dir] :dir nil}
      (not (str/blank? setting)) {:argv [setting] :dir dir}
      :else (if-let [found (first (filter on-path? linux-terminals))]
              {:argv [found] :dir dir}
              {:problem (str "no terminal found on the PATH (tried " (str/join ", " linux-terminals)
                             "); name one as :terminal in settings.edn")}))))

(defn- on-path? [program]
  (some? (tool-path/resolve-program program (login-path/path) login-path/executable-file?)))

(defn- start!
  "Starts argv in dir. True when it started and did not exit non-zero
  within about a second; a process still running then is reaped in the
  background. Never throws."
  [argv dir]
  (try
    (let [proc (apply p/process (cond-> {:out :discard :err :discard} dir (assoc :dir dir)) argv)
          jp ^java.lang.Process (:proc proc)]
      (loop [n 0]
        (cond (not (p/alive? proc)) (zero? (.exitValue jp))
              (>= n 50) (do (future (try (deref proc) (catch Throwable _ nil))) true)
              :else (do (Thread/sleep 20) (recur (inc n))))))
    (catch Throwable _ false)))

(defn open!
  "Opens a terminal in the folder of the file at path (nil for a tab with
  no file), with setting as settings.edn's :terminal. Answers the console
  line that says where it opened, or why it couldn't."
  [path setting]
  (let [dir (folder path (System/getProperty "user.home"))
        {:keys [argv problem] run-dir :dir}
        (command dir {:os (System/getProperty "os.name") :setting setting
                      :override (System/getenv "JONQUIL_OPEN_TERMINAL") :on-path? on-path?})]
    (cond
      problem (str "; Couldn't open a terminal: " problem ".")
      (start! argv run-dir) (str "; Opened a terminal in " dir)
      :else (str "; Couldn't open a terminal in " dir ": " (str/join " " argv) " didn't start."))))
