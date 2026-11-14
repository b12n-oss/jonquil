(ns gtk-selftest-window.menus
  "The window's menus: an item runs its command, the menu button shows where
  there is no system menu bar, and a key the window passes on takes the
  menu's actions away until the next idle."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.menus :as gm]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.menus :as menus]
            [jonquil.user-keys :as user-keys]))

(defn row-menu-item-runs-and-keys-yield
  "Activating Help > Jonquil's Version prints the version line. Yielding
  takes the action off the window and an idle puts it back. A chord nothing
  binds, sent through the window's key handler, is passed on (0) and yields
  on its way."
  [ui]
  (go-row :menu-item-runs-and-keys-yield support/check
    (let [{:keys [group button yield! yielded?]} (:menus ui)
          win (:window ui)
          mac? (= "Mac OS X" (System/getProperty "os.name"))
          version-lines #(count (re-seq #"; Jonquil " (support/console-text ui)))
          before (on-gtk (version-lines))
          _ (on-gtk (g/g-action-group-activate-action group (menus/action-name :help/version) ffi/null))
          ran? (await-until 3000 20 #(= (inc before) (version-lines)))
          ;; yield: gone at once, back on idle
          [gone? flagged?] (on-gtk (yield!) [(not (gm/offered? win :help/version)) @yielded?])
          back? (await-until 3000 20 #(not @yielded?))
          offered-again? (on-gtk (gm/offered? win :help/version))
          ;; a chord nothing binds: Cmd/Ctrl+Alt+Shift+Y
          [answer flagged-by-key?]
          (on-gtk [((:handle-window-key! ui) ffi/null (int \y) 0
                                             (bit-or g/META-MASK g/ALT-MASK g/SHIFT-MASK) ffi/null)
                   @yielded?])
          settled? (await-until 3000 20 #(not @yielded?))
          ;; plain typing never yields
          typed (on-gtk [((:handle-window-key! ui) ffi/null (int \y) 0 0 ffi/null) @yielded?])]
      (on-gtk (support/check :menu-item-runs-and-keys-yield
                             (and ran? gone? flagged? back? offered-again?
                                  (= 0 answer) flagged-by-key? settled?
                                  (= [0 false] typed)
                                  (if mac? (nil? button) (some? button))
                                  (str/includes? (support/console-text ui) "; Jonquil "))
                             {:ran ran? :gone gone? :flagged flagged? :back back? :offered-again offered-again?
                              :answer answer :flagged-by-key flagged-by-key? :settled settled?
                              :typed typed :button? (some? button) :mac mac?})))))

(defn- shown-accel
  "The key the menus show for command id, from the tree they were built from."
  [ui id]
  (some (fn [menu] (some #(when (= id (:command %)) (:accel %)) (menus/items menu)))
        @(:shown (:menus ui))))

(defn row-menus-follow-your-keys
  "Help > Jonquil's Version has no key. A key of yours for it (keys.edn's
  live value) shows in the menus a moment later, and goes again when your
  keys are cleared."
  [ui]
  (go-row :menus-follow-your-keys support/check
    (let [before (on-gtk (shown-accel ui :help/version))
          _ (on-gtk (reset! user-keys/live
                            (assoc user-keys/no-keys :window [{:key "j" :mods #{:cmd :shift} :command :help/version}])))
          with-key? (await-until 3000 20 #(some? (shown-accel ui :help/version)))
          shown (on-gtk (shown-accel ui :help/version))
          _ (on-gtk (reset! user-keys/live user-keys/no-keys))
          gone? (await-until 3000 20 #(nil? (shown-accel ui :help/version)))]
      (on-gtk (try
                (support/check :menus-follow-your-keys
                               (and (nil? before) with-key? (str/ends-with? (or shown "") "j")
                                    (str/includes? (or shown "") "<Shift>") gone?)
                               {:before before :shown shown :gone gone?})
                (finally (reset! user-keys/live user-keys/no-keys)))))))

(defn row-reset-on-run-from-the-menu
  "Run > Reset on Run toggles the window's Reset on Run check: off shows the
  note in the status bar and unticks the item, on hides it again."
  [ui]
  (go-row :reset-on-run-from-the-menu support/check
    (let [{:keys [group]} (:menus ui)
          check (:reset-check ui)
          on? #(pos? (g/gtk-check-button-get-active check))
          toggle! #(g/g-action-group-activate-action group (menus/action-name :run/reset-on-run) ffi/null)
          before (on-gtk (on?))
          _ (on-gtk (toggle!))
          off? (await-until 3000 20 #(not (on?)))
          said-off (on-gtk (str/includes? (g/label-text (:status-label ui)) "Reset on Run off"))
          _ (on-gtk (toggle!))
          back? (await-until 3000 20 on?)]
      (on-gtk (try
                (support/check :reset-on-run-from-the-menu
                               (and before off? said-off back?)
                               {:before before :off off? :said-off said-off :back back?})
                (finally (g/gtk-check-button-set-active check 1)))))))

(defn row-header-title-follows-the-file
  "The header bar shows the open file's name over its folder, and only the
  name for a buffer with no file."
  [ui]
  (go-row :header-title-follows-the-file support/check
    (on-gtk (let [{:keys [title subtitle]} (get @win-files/title-labels (:window ui))
                  name-now (g/label-text title)
                  folder-shown? (pos? (g/gtk-widget-get-visible subtitle))
                  folder (g/label-text subtitle)
                  window-title (g/window-title (:window ui))]
              (support/check :header-title-follows-the-file
                             (and (some? title) (some? subtitle)
                                  (= (str name-now " - Jonquil") window-title)
                                  (= folder-shown? (not= "" folder)))
                             {:name name-now :folder folder :folder-shown folder-shown? :window-title window-title})))))
