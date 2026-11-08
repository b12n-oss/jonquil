(ns jonquil.plugin-checker-files
  "The plugin checker (jonquil.plugin-checker) and every namespace it
  loads, as source files carried inside Jonquil and written where an
  isolated check's jolt child loads them as a :local/root dep. The same files Jonquil
  runs in process, so the child checks with the same validator. Read when
  this namespace is compiled, as jonquil.nrepl.middleware-files does, and
  written through its ensure-root!."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.nrepl.middleware-files :as middleware-files]))

(defmacro ^:private embedded-text
  "The text of classpath file path, read when this form is compiled."
  [path]
  (slurp (io/resource path)))

(def sources
  "Classpath-relative path -> text: jonquil.plugin-protocol's requires,
  followed through (measured 2026-10-26; plus jonquil.parts and its spec
  namespaces, which keymap and palette now require), keymap.edn (which keymap reads
  when it compiles), and the checker."
  {"jonquil/commands.clj" (embedded-text "jonquil/commands.clj")
   "jonquil/console.clj" (embedded-text "jonquil/console.clj")
   "jonquil/dialect.clj" (embedded-text "jonquil/dialect.clj")
   "jonquil/eval.clj" (embedded-text "jonquil/eval.clj")
   "jonquil/keymap.clj" (embedded-text "jonquil/keymap.clj")
   "jonquil/keymap.edn" (embedded-text "jonquil/keymap.edn")
   "jonquil/palette.clj" (embedded-text "jonquil/palette.clj")
   "jonquil/parts.clj" (embedded-text "jonquil/parts.clj")
   "jonquil/parts/extra.clj" (embedded-text "jonquil/parts/extra.clj")
   "jonquil/parts/layouts.clj" (embedded-text "jonquil/parts/layouts.clj")
   "jonquil/parts/messages.clj" (embedded-text "jonquil/parts/messages.clj")
   "jonquil/parts/processes.clj" (embedded-text "jonquil/parts/processes.clj")
   "jonquil/parts/replay.clj" (embedded-text "jonquil/parts/replay.clj")
   "jonquil/parts/time_form.clj" (embedded-text "jonquil/parts/time_form.clj")
   "jonquil/parts/worlds.clj" (embedded-text "jonquil/parts/worlds.clj")
   "jonquil/plugin.clj" (embedded-text "jonquil/plugin.clj")
   "jonquil/plugin_check.clj" (embedded-text "jonquil/plugin_check.clj")
   "jonquil/plugin_protocol.clj" (embedded-text "jonquil/plugin_protocol.clj")
   "jonquil/syntax.clj" (embedded-text "jonquil/syntax.clj")
   "jonquil/theme.clj" (embedded-text "jonquil/theme.clj")
   "jonquil/plugin_checker.clj" (embedded-text "jonquil/plugin_checker.clj")})

(def lib
  "The dep name the root is added under in the child's -Sdeps."
  'jonquil/plugin-checker)

(defn cache-base
  "$XDG_CACHE_HOME/jonquil/plugin-checker, or ~/.cache/jonquil/plugin-checker
  when that is unset, blank or relative (middleware-files' rule)."
  [env home]
  (let [xdg (get env "XDG_CACHE_HOME")]
    (str (if (and xdg (str/starts-with? xdg "/")) xdg (str home "/.cache"))
         "/jonquil/plugin-checker")))

(defn ensure-root!
  "The folder under base (cache-base by default) holding the checker's
  files, written first when it isn't there, by
  middleware-files/ensure-root!'s rules. Its canonical path; throws when
  it can't be written."
  ([] (ensure-root! (cache-base (System/getenv) (System/getProperty "user.home"))))
  ([base] (middleware-files/ensure-root! base sources)))
