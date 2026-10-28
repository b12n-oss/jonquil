(ns jonquil.tabs
  "Pure helpers for a window's tabs (tabs spec): the tab strip's labels,
  finding a file that's already open, whether a tab may run in the
  window's evaluator, and the text of the refusal, the unsaved-changes
  alert and the Run banner. A tab here is a plain map with :path (a string
  or nil for Untitled) and :dirty?."
  (:require [clojure.string :as str]
            [jonquil.dialect :as dialect]
            [jonquil.source :as source]))

(defn- base-name [path] (last (str/split path #"/")))

(defn- parent-name [path]
  (let [parts (str/split path #"/")]
    (when (> (count parts) 1) (nth parts (- (count parts) 2)))))

(defn labels
  "One label per tab, in order: the file's basename, plus \" · <parent
  folder>\" when another tab has the same basename, or Untitled numbered
  in tab order (Untitled, Untitled 2, ...); then \" ●\" while dirty."
  [tabs]
  (let [names (keep (fn [{:keys [path]}] (when path (base-name path))) tabs)
        clashes (set (keep (fn [[n c]] (when (> c 1) n)) (frequencies names)))]
    (loop [[t & more] tabs untitled 0 out []]
      (if-not t
        out
        (let [{:keys [path dirty?]} t
              untitled' (if path untitled (inc untitled))
              base (cond
                     (nil? path) (if (= 1 untitled') "Untitled" (str "Untitled " untitled'))
                     (clashes (base-name path)) (str (base-name path) " · " (parent-name path))
                     :else (base-name path))]
          (recur more untitled' (conj out (if dirty? (str base " ●") base))))))))

(defn find-open
  "The index of the first tab whose :path names the same file as path
  (compared canonically, so a symlink finds its target), or nil."
  [tabs path]
  (when path
    (first (keep-indexed (fn [i {p :path}] (when (and p (source/same-path? p path)) i)) tabs))))

(defn untouched-untitled?
  "An Untitled tab nobody has typed in: Open fills it instead of adding a
  tab."
  [{:keys [path text]}]
  (and (nil? path) (= "" text)))

(defn loose-jolt?
  "A Jolt file outside any project: it has no project folder of its own to
  run in, so any Jolt the window runs will do."
  [sel]
  (and (= :jolt (:id (:profile sel))) (nil? (:project-dir sel))))

(defn keeps-window-jolt?
  "True when a window whose selection is window-sel keeps its own Jolt for
  a lone tab whose selection becomes tab-sel (Save As, a rename, Open into
  an untouched Untitled tab) instead of following it: a loose Jolt file in
  a Jolt window, whose evaluator it can run in already (runnable-here?)."
  [window-sel tab-sel]
  (boolean (and (loose-jolt? tab-sel) (= :jolt (:id (:profile window-sel))))))

(defn runnable-here?
  "Whether a tab whose own selection is tab-sel may run in a window whose
  selection is window-sel: an Untitled tab (nil) always may, and so may a
  loose Jolt file in any Jolt window (a scratch file next to a deps.edn
  project's tabs runs in that project's Jolt child). Otherwise the two need
  the same evaluator (dialect/selection-key). Closing tabs down to one asks
  this too, so a loose file left alone doesn't switch the window away from
  its project's Jolt."
  [window-sel tab-sel]
  (or (nil? tab-sel)
      (and (loose-jolt? tab-sel) (= :jolt (:id (:profile window-sel))))
      (= (dialect/selection-key window-sel) (dialect/selection-key tab-sel))))

(defn- home-relative [path]
  (let [home (System/getProperty "user.home")]
    (if (and home (str/starts-with? path (str home "/")))
      (str "~" (subs path (count home)))
      path)))

(defn- dir-text [sel]
  (if-let [d (:project-dir sel)] (home-relative d) "no project"))

(defn refusal-message
  "The console line for Run in a tab that belongs to another project or
  dialect than the window."
  [{:keys [file tab-sel window-sel]}]
  (str "; " file " belongs to " (dir-text tab-sel) " (" (dialect/label tab-sel) "). This window runs "
       (dir-text window-sel) " (" (dialect/label window-sel) "), so it can't run here.\n"))

(defn close-alert
  "The unsaved-changes alert for the dirty tabs' names: one name keeps the
  single-file wording; several get one alert with Save All."
  [names]
  (if (= 1 (count names))
    {:message (str "Save changes to " (first names) " before closing?")
     :buttons ["Save" "Don't Save" "Cancel"]}
    {:message (str (count names) " files have unsaved changes: " (str/join ", " names))
     :buttons ["Save All" "Don't Save" "Cancel"]}))

(defn run-banner
  "Run's console banner: an Untitled tab keeps the Definitions wording."
  [path]
  (str "; --- Running " (if path (base-name path) "Definitions") " ---\n"))

(defn cycle-index
  "i moved by delta among n tabs, wrapping at both ends."
  [n i delta]
  (mod (+ i delta) n))

(defn index-for-digit
  "Cmd/Ctrl+digit: 1 to 8 pick that tab when it exists; 9 picks the last."
  [n digit]
  (if (= 9 digit)
    (dec n)
    (let [i (dec digit)] (when (< i n) i))))

(defn insert-after
  "v with x inserted right after index i."
  [v i x]
  (into (conj (subvec v 0 (inc i)) x) (subvec v (inc i))))

(defn disk-sync
  "What a tab on a file a plugin step may have rewritten should do (plugins
  F2): :reload when its file holds other text than the tab last saved and
  the tab has no unsaved edits, :warn when it has some, :rebase when its
  edits already equal the new text (nothing to lose: final review I2), nil
  when the file is unchanged or missing (disk nil)."
  [{:keys [saved disk dirty? buffer]}]
  (when (and disk (not= disk saved))
    (cond (not dirty?) :reload
          (= buffer disk) :rebase
          :else :warn)))
