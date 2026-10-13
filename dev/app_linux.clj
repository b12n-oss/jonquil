(ns app-linux
  "The pure parts of the Linux desktop install: where files go, the .desktop
  launcher text, the shared-mime-info package and the replace check. The
  install itself (copying files, running the database updaters) lives in
  install!/uninstall!, which app-bundle/-main dispatches to on Linux.
  Everything lands under ~/.local/share and no default app is ever set."
  (:require [clojure.string :as str]))

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
