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
            [jonquil.menus :as menus]))

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
