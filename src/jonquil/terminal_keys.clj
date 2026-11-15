(ns jonquil.terminal-keys
  "Which keys stay Jonquil's while the terminal has the focus. Every other
  key is the terminal's, so a shell, vim or a REPL in it gets its own
  keys. No GTK here.

  Jonquil keeps: F6 (back to the editor), the key that shows and leaves
  the terminal (Ctrl+`), and the panel keys (Cmd/Ctrl+Alt+digit). On a Mac
  it also keeps every key held with Cmd, which no terminal program uses,
  so Cmd+W, Cmd+Shift+P and the rest work as anywhere else. On other
  systems Control is the shell's, so Jonquil keeps only the keys held with
  both Control and Shift: the palette, and copy and paste on C and V, as
  GNOME Terminal has them."
  (:require [jonquil.keymap :as km]))

(def ^:private M km/MODIFIER-MASKS)

(defn- held? [state m] (pos? (bit-and state (get M m))))

(defn jonquils?
  "True when the key stays Jonquil's with the terminal focused. key is
  {:keyval lowered :state modifier bits :physical the physical key's name
  or nil}; os is the os.name property."
  [{:keys [keyval state physical]} os]
  (let [mac? (= "Mac OS X" os)
        k (or physical (km/key-name keyval))
        ctrl? (held? state :ctrl) meta? (held? state :meta)
        alt? (held? state :alt) shift? (held? state :shift)]
    (boolean
     (or (= "F6" k)
         ;; show the terminal, or leave it
         (and (= "grave" k) ctrl? (not alt?) (not meta?))
         ;; the panel keys
         (and alt? (or ctrl? meta?) (string? k) (re-matches #"[0-9]" k))
         (if mac?
           meta?
           (and ctrl? shift? (not alt?)))))))

(defn clipboard-action
  "What a key means for the terminal's clipboard: :copy, :paste or nil. Cmd+C
  and Cmd+V on a Mac; Ctrl+Shift+C and Ctrl+Shift+V elsewhere."
  [{:keys [keyval state]} os]
  (let [mac? (= "Mac OS X" os)
        chord? (if mac?
                 (and (held? state :meta) (not (held? state :ctrl)) (not (held? state :alt)))
                 (and (held? state :ctrl) (held? state :shift) (not (held? state :alt)) (not (held? state :meta))))]
    (when chord?
      (case (km/key-name keyval)
        "c" :copy
        "v" :paste
        nil))))
