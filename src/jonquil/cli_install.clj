(ns jonquil.cli-install
  "Installs and removes the `jonquil` terminal command for a Jonquil.app:
  a symlink to the script the bundle ships at Contents/Resources/bin/jonquil
  (resources/cli/jonquil). The link goes in /usr/local/bin when Jonquil may
  write there, else in ~/.local/bin, and Jonquil never asks for a password.
  It only ever replaces or removes a link that points into a Jonquil.app.
  The decisions are pure (plan, removal); install! and remove! do the IO."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]))

(def script-suffix "/Contents/Resources/bin/jonquil")

(defn bundle-of
  "The .app folder an executable path sits in (…/X.app/Contents/MacOS/y),
  or nil when it isn't inside one."
  [exe]
  (second (re-matches #"(.+\.app)/Contents/MacOS/[^/]+" (str exe))))

(defn script-of
  "The bundle's jonquil script."
  [bundle]
  (str bundle script-suffix))

(defn ours?
  "True when target, a symlink's target, is a Jonquil.app's script."
  [target]
  (boolean (and target (str/ends-with? target (str ".app" script-suffix)))))

(defn link-dirs
  "The folders a link may go in, first choice first."
  [home]
  ["/usr/local/bin" (str home "/.local/bin")])

(defn on-path?
  "True when dir is one of path-env's entries."
  [dir path-env]
  (boolean (some #{dir} (str/split (str path-env) #":"))))

(defn- path-note [dir path-env]
  (when-not (on-path? dir path-env)
    (str "; " dir " isn't on your PATH yet. Add this line to your shell's startup file: export PATH=\"" dir ":$PATH\"")))

(defn plan
  "What Install does. exe is Jonquil's own executable, existing maps each of
  dirs' links to what is there now (:none, [:link target] or :file), and
  writable? says whether Jonquil may write a folder (creating it when it's
  under home). dirs defaults to link-dirs. {:link :target :lines} or
  {:refuse lines}."
  [{:keys [exe home path-env existing writable? dirs]}]
  (let [bundle (bundle-of exe)
        target (some-> bundle script-of)
        dir (first (filter writable? (or dirs (link-dirs home))))
        link (str dir "/jonquil")
        there (get existing link :none)]
    (cond
      (nil? bundle)
      {:refuse ["; This Jonquil isn't running from a Jonquil.app, so there is no command to install. From a checkout, ./jonquil <path> is the command."]}
      (nil? dir)
      {:refuse ["; Neither /usr/local/bin nor ~/.local/bin can be written, so the jonquil command wasn't installed."]}
      (= :file there)
      {:refuse [(str "; " link " is a file Jonquil didn't put there, so it was left alone.")]}
      (and (vector? there) (not (ours? (second there))))
      {:refuse [(str "; " link " is a link to " (second there) ", which isn't a Jonquil.app, so it was left alone.")]}
      :else
      {:link link :target target
       :lines (remove nil? [(str "; Installed the jonquil command: " link " -> " target)
                            (path-note dir path-env)
                            "; In a terminal, jonquil . opens the current folder."])})))

(defn removal
  "What Remove does: {:remove [links] :lines}, the links among dirs'
  (default link-dirs) that point into a Jonquil.app."
  [{:keys [home existing dirs]}]
  (let [links (for [dir (or dirs (link-dirs home))
                    :let [link (str dir "/jonquil")
                          there (get existing link :none)]
                    :when (and (vector? there) (ours? (second there)))]
                link)]
    {:remove (vec links)
     :lines (if (seq links)
              (mapv (fn [l] (str "; Removed the jonquil command at " l ".")) links)
              ["; No jonquil command that points at a Jonquil.app was found, so nothing was removed."])}))

;; --- IO ---------------------------------------------------------------

(defn own-executable
  "The path of the program this process runs, asked of ps through a child
  shell's parent pid. nil when that fails."
  []
  (try
    (let [r (p/sh "sh" "-c" "ps -o comm= -p $PPID")
          out (str/trim (str (:out r)))]
      (when (and (zero? (:exit r)) (str/starts-with? out "/")) out))
    (catch Throwable _ nil)))

(defn- what-is-there [link]
  (let [path (.toPath (io/file link))]
    (cond
      (java.nio.file.Files/isSymbolicLink path) [:link (str (java.nio.file.Files/readSymbolicLink path))]
      (.exists (io/file link)) :file
      :else :none)))

(defn- existing [dirs]
  (into {} (map (fn [dir] (let [link (str dir "/jonquil")] [link (what-is-there link)])))
        dirs))

(defn- writable-fn
  "Whether Jonquil may write dir: an existing folder it can write, or a
  missing one under home, which install! then creates."
  [home]
  (fn [dir]
    (let [f (io/file dir)]
      (if (.isDirectory f)
        (.canWrite f)
        (and (not (.exists f)) (str/starts-with? dir (str home "/")))))))

(defn install!
  "Installs the command for the running Jonquil. Returns the lines to show."
  ([] (install! {:exe (own-executable) :home (System/getProperty "user.home")
                 :path-env (System/getenv "PATH")}))
  ([{:keys [home dirs] :as opts}]
   (let [dirs (or dirs (link-dirs home))
         {:keys [refuse link target lines]}
         (plan (assoc opts :dirs dirs :existing (existing dirs) :writable? (writable-fn home)))]
     (cond
       refuse refuse
       (not (.isFile (io/file target)))
       [(str "; " target " is missing from this Jonquil.app, so the jonquil command wasn't installed.")]
       :else
       (do (io/make-parents link)
           (let [r (p/sh "ln" "-sfn" target link)]
             (if (zero? (:exit r))
               (vec lines)
               [(str "; Installing the jonquil command at " link " failed: " (str/trim (str (:err r))))])))))))

(defn remove!
  "Removes the command's links. Returns the lines to show."
  ([] (remove! {:home (System/getProperty "user.home")}))
  ([{:keys [home dirs]}]
   (let [dirs (or dirs (link-dirs home))
         {links :remove lines :lines} (removal {:home home :dirs dirs :existing (existing dirs)})]
     (doseq [l links] (io/delete-file l true))
     lines)))
