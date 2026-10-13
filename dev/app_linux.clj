(ns app-linux
  "The pure parts of the Linux desktop install: where files go, the .desktop
  launcher text, the shared-mime-info package and the replace check. The
  install itself (copying files, running the database updaters) lives in
  install!/uninstall!, which app-bundle/-main dispatches to on Linux.
  Everything lands under ~/.local/share and no default app is ever set."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]))

(def app-id "net.b12n.jonquil")

(def ^:private marker "X-Jonquil-Install=1")

(def mime-types
  "The types Jonquil registers and offers to open: [type comment globs]."
  [["text/x-clojure" "Clojure source" ["*.clj" "*.cljc" "*.cljd" "*.bb"]]
   ["application/edn" "EDN data" ["*.edn"]]
   ["text/x-jank" "jank source" ["*.jank"]]])

(defn install-paths
  "Every path the install writes, for the given home directory."
  [home]
  (let [share (str home "/.local/share")]
    {:dir (str share "/jonquil")
     :binary (str share "/jonquil/jonquil")
     :desktop (str share "/applications/" app-id ".desktop")
     :icon (str share "/icons/hicolor/256x256/apps/" app-id ".png")
     :mime (str share "/mime/packages/" app-id ".xml")
     :mime-db (str share "/mime")
     :apps-db (str share "/applications")}))

(defn- exec-arg
  "path as one Exec argument. The Desktop Entry spec quotes an argument that
  holds a reserved character with double quotes and backslash-escapes
  \", `, $ and \\ inside them; a backslash is then doubled again for the
  string layer of the file, and a literal % is written %%."
  [path]
  (let [p (str/replace path "%" "%%")]
    (if (re-find #"[\s\"'\\<>~|&;$*?#()`]" p)
      (str "\""
           (-> p
               (str/replace "\\" "\\\\")
               (str/replace "\"" "\\\"")
               (str/replace "`" "\\`")
               (str/replace "$" "\\$")
               (str/replace "\\" "\\\\"))
           "\"")
      p)))

(defn desktop-entry
  "The launcher text. opts: :binary (absolute path of the installed binary)
  and :version (recorded as X-Jonquil-Version)."
  [{:keys [binary version]}]
  (str "[Desktop Entry]\n"
       "Type=Application\n"
       "Name=Jonquil\n"
       "GenericName=Clojure IDE\n"
       "Comment=A DrRacket-style Clojure IDE written in Jolt\n"
       "Exec=" (exec-arg binary) " %F\n"
       "Icon=" app-id "\n"
       "Terminal=false\n"
       "StartupWMClass=" app-id "\n"
       "Categories=Development;IDE;\n"
       "MimeType=" (apply str (map (fn [[t _ _]] (str t ";")) mime-types)) "\n"
       marker "\n"
       "X-Jonquil-Version=" version "\n"))

(defn mime-xml
  "The shared-mime-info package: the three types, their globs, and a
  comment each, all sub-classes of text/plain."
  []
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<mime-info xmlns=\"http://www.freedesktop.org/standards/shared-mime-info\">\n"
       (apply str
              (for [[t c globs] mime-types]
                (str "  <mime-type type=\"" t "\">\n"
                     "    <comment>" c "</comment>\n"
                     "    <sub-class-of type=\"text/plain\"/>\n"
                     (apply str (map (fn [g] (str "    <glob pattern=\"" g "\"/>\n")) globs))
                     "  </mime-type>\n")))
       "</mime-info>\n"))

(defn replaceable?
  "True when text is nil (nothing installed) or a launcher that carries the
  line X-Jonquil-Install=1, so install may replace it. Any other launcher
  is somebody else's and is refused."
  [text]
  (or (nil? text)
      (boolean (some #{marker} (str/split-lines text)))))

(defn- slurp-or-nil [path]
  (let [f (io/file path)]
    (when (.isFile f) (slurp f))))

(defn- on-path?
  "True when tool is an executable on PATH."
  [tool]
  (zero? (:exit (p/sh "sh" "-c" (str "command -v " tool)))))

(defn- refresh-databases!
  "Runs update-mime-database and update-desktop-database for paths, each
  only when `which` (default: PATH lookup) finds it; otherwise prints a note.
  A tool that fails prints a note too and does not fail the install."
  [paths which]
  (doseq [[tool dir] [["update-mime-database" (:mime-db paths)]
                      ["update-desktop-database" (:apps-db paths)]]]
    (if (which tool)
      (let [r (p/sh tool dir)]
        (when-not (zero? (:exit r))
          (println (str "note: " tool " failed, skipped: " (str/trim (str (:err r)))))))
      (println (str "note: " tool " not found, skipped")))))

(defn- err!
  "Prints msg to stderr, returns 1."
  [msg]
  (.write *err* (str "app-linux: " msg "\n"))
  (.flush *err*)
  1)

(defn- refuse
  "Prints the refusal for a launcher that isn't ours to stderr; returns 1."
  [desktop verb]
  (err! (str desktop " is not a Jonquil launcher (no X-Jonquil-Install=1); refusing to " verb " it")))

(defn- write-file! [path content-fn]
  (io/make-parents path)
  (content-fn path))

(defn- move!
  "Renames from onto to, replacing a file. Returns nil, or the error text.
  Not `mv`: that would move from INTO an existing directory at to."
  [from to]
  (try (java.nio.file.Files/move (.toPath (io/file from)) (.toPath (io/file to))
                                 (into-array java.nio.file.CopyOption
                                             [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
       nil
       (catch Exception e (or (.getMessage e) (str e)))))

(defn install!
  "Installs the binary at :source under :home/.local/share. opts: :home,
  :source (the built ./jonquil), :version, and optionally :which (a fn from
  tool name to boolean, default a PATH lookup). Returns the exit status: 0,
  or 1 when the launcher at the target path is somebody else's (nothing is
  written then)."
  [{:keys [home source version which] :or {which on-path?}}]
  (let [paths (install-paths home)
        desktop (:desktop paths)]
    (cond
      (not (replaceable? (slurp-or-nil desktop))) (refuse desktop "replace")
      (not (.isFile (io/file source)))
      (err! (str source " is missing; run `bb app:build` first"))
      :else
      (let [tmp (str (:binary paths) ".new")
            written (atom [])
            note! (fn [path] (swap! written conj path))
            fail (fn [msg]
                   (java.nio.file.Files/deleteIfExists (.toPath (io/file tmp)))
                   (err! (str msg (when (seq @written)
                                    (str "; already written: " (str/join ", " @written)
                                         " (re-run bb app:install, or run bb app:uninstall)")))))]
        (try
          (write-file! (:mime paths) #(spit % (mime-xml)))
          (note! (:mime paths))
          (write-file! (:icon paths) #(io/copy (io/file "resources/icon/jonquil-256.png") (io/file %)))
          (note! (:icon paths))
          (write-file! desktop #(spit % (desktop-entry {:binary (:binary paths) :version version})))
          (note! desktop)
          ;; The binary goes last, through a temp name and a rename, so a
          ;; running copy is replaced rather than overwritten in place.
          (write-file! tmp #(io/copy (io/file source) (io/file %)))
          (if-not (.setExecutable (io/file tmp) true)
            (fail (str "install failed: chmod +x " tmp " failed"))
            (if-let [msg (move! tmp (:binary paths))]
              (fail (str "install failed: move " tmp " to " (:binary paths) ": " msg))
              (do (refresh-databases! paths which)
                  (println (str "installed " (:binary paths)))
                  0)))
          (catch Exception e
            (fail (str "install failed: " (.getMessage e)))))))))

(defn uninstall!
  "Removes what install! wrote: the four files, then :dir (the only
  recursive delete; everything inside ~/.local/share/jonquil goes with it). opts: :home and optionally :which. Returns 0, or 1 on
  a launcher that isn't ours (nothing is deleted then). With nothing
  installed it prints `nothing to uninstall` and returns 0."
  [{:keys [home which] :or {which on-path?}}]
  (let [paths (install-paths home)
        files (map paths [:binary :desktop :icon :mime])
        present? (fn [path] (.exists (io/file path)))]
    (cond
      (not (replaceable? (slurp-or-nil (:desktop paths)))) (refuse (:desktop paths) "remove")
      (not-any? present? (cons (:dir paths) files))
      (do (println "nothing to uninstall") 0)
      :else
      (do (doseq [f files]
            (java.nio.file.Files/deleteIfExists (.toPath (io/file f))))
          (let [r (p/sh "rm" "-rf" (:dir paths))]
            (if-not (zero? (:exit r))
              (err! (str "could not remove " (:dir paths) ": " (str/trim (str (:err r)))))
              (do (refresh-databases! paths which)
                  (println "uninstalled")
                  0)))))))
