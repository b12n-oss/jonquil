(ns jonquil.tool-path
  "Decisions for finding tools (jolt, jank) when Jonquil is launched outside a
  shell, from Finder or the Dock. Such a launch inherits a bare PATH
  (/usr/bin:/bin:/usr/sbin:/sbin), so Homebrew and per-user tools are missing.
  This namespace only decides: it reads no files and runs no processes. Anything
  that touches the disk comes in as an argument (exists? is a function of one
  path string).

  The shell probe runs the user's login and interactive zsh and prints PATH
  between two markers, so a banner or an rc file's own output can't be
  mistaken for the answer. parse-answer reads the last marker pair."
  (:require [clojure.string :as str]))

(def marker-start
  "Opens the PATH that the shell probe prints."
  "__JONQUIL_PATH__")

(def marker-end
  "Closes the PATH that the shell probe prints."
  "__END__")

(defn- entries
  "The non-empty entries of a colon-separated PATH string."
  [path-string]
  (into [] (remove str/blank?) (str/split (or path-string "") #":")))

(defn well-known-dirs
  "The directories a GUI launch misses, for a user whose home is home."
  [home]
  ["/opt/homebrew/bin" "/usr/local/bin" (str home "/.local/bin") (str home "/bin")])

(defn needs-lookup?
  "True when none of well-known-dirs is an entry of path-string, which is what
  a Finder or Dock launch looks like. False when the PATH already has one, so
  the shell probe can be skipped."
  [path-string home]
  (let [have (set (entries path-string))]
    (not-any? have (well-known-dirs home))))

(defn shell-command
  "The argv that prints the login and interactive PATH of shell (default
  /bin/zsh when nil or blank). The markers are the constants above, so the
  answer stays findable."
  [shell]
  (let [sh (if (str/blank? shell) "/bin/zsh" shell)]
    [sh "-il" "-c"
     (str "printf '\\n" marker-start "%s" marker-end "\\n' \"$PATH\"")]))

(defn parse-answer
  "The PATH between the markers in the probe's stdout. When several marker
  pairs are present the last one wins, since a shell rc file may print one
  too. nil when there are no markers or the pair encloses nothing."
  [stdout]
  (let [pattern (re-pattern (str (java.util.regex.Pattern/quote marker-start)
                                 "(.*?)"
                                 (java.util.regex.Pattern/quote marker-end)))
        found (re-seq pattern (or stdout ""))]
    (when-let [[_ path] (last found)]
      (not-empty path))))

(defn fallback-path
  "current PATH followed by each well-known dir that exists? and is not already
  an entry, colon-joined and in the order well-known-dirs gives."
  [current home exists?]
  (let [have (entries current)
        extra (filter (fn [dir]
                        (and (exists? dir) (not (some #{dir} have))))
                      (well-known-dirs home))]
    (str/join ":" (concat have extra))))

(defn resolve-program
  "The first <dir>/<name> over path-string's entries for which exists? holds, or
  nil. An absolute name is returned as is when exists?, and nil otherwise."
  [name path-string exists?]
  (if (str/starts-with? name "/")
    (when (exists? name) name)
    (some (fn [dir]
            (let [candidate (str dir "/" name)]
              (when (exists? candidate) candidate)))
          (entries path-string))))
