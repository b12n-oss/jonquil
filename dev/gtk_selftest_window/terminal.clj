(ns gtk-selftest-window.terminal
  "The terminal in the window's bottom area: Show the terminal turns the
  console's place into two tabs and starts a shell, the shell runs what it
  is fed, a focused terminal takes the keys the window would otherwise run,
  and F6 and Ctrl+` go back to the editor. On a machine without VTE the
  Terminal tab says why instead."
  (:require [anchan.core :as anchan]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]
            [jonquil.keymap :as km]))

(defn row-terminal-in-the-bottom-area
  "Nothing is built until the terminal is first shown. Show the terminal
  then makes the two tabs. With VTE a shell starts and echoes what it is
  fed, Ctrl+R is passed on, F6 and Ctrl+` leave the terminal, and a
  background job makes it busy. Without
  VTE the Terminal tab holds the sentence saying why, which passes unless
  JONQUIL_SELFTEST_NEEDS_VTE is set: jolt linux:selftest sets it, since its
  image has VTE, so a terminal that stops loading there fails the row."
  [ui]
  (go-row :terminal-in-the-bottom-area support/check
    (let [{:keys [state show! busy? focused? showing?]} (:terminal ui)
          key! (fn [n mods] ((:handle-window-key! ui) ffi/null (km/key-value n) 0 mods ffi/null))
          ed (:editor ui)
          unbuilt? (on-gtk (nil? @state))
          _ (on-gtk (show!))
          term (on-gtk (:term @state))
          shown (on-gtk {:showing (showing?) :pages (g/gtk-notebook-get-n-pages (:notebook @state))})]
      (if-not term
        ;; no VTE here: the tab is there and says why
        (let [why (on-gtk (:why @state))
              _ (on-gtk (g/gtk-notebook-set-current-page (:notebook @state) 0))]
          (on-gtk (support/check :terminal-in-the-bottom-area
                                 (and unbuilt? (= {:showing true :pages 2} shown) (string? why)
                                      (nil? (System/getenv "JONQUIL_SELFTEST_NEEDS_VTE")))
                                 {:no-vte true :unbuilt unbuilt? :shown shown :why why
                                  :vte-needed (some? (System/getenv "JONQUIL_SELFTEST_NEEDS_VTE"))})))
        (let [started? (await-until 8000 50 #(anchan/running? term))
              focus (on-gtk (focused?))
              _ (on-gtk (anchan/feed! term "echo jonquil-$((6*7))\n"))
              echoed? (await-until 8000 50 #(str/includes? (anchan/text term) "jonquil-42"))
              ;; Ctrl+R is Run in the window; with the terminal focused it is the shell's
              ctrl-r (on-gtk (key! "r" g/CONTROL-MASK))
              ;; F6 goes back to the editor, and from there to the terminal again
              f6-out (on-gtk [(key! "F6" 0) (focused?) (= (g/gtk-root-get-focus (:window ui)) (:view ed))])
              f6-back (on-gtk [(key! "F6" 0) (focused?)])
              ;; Ctrl+` from the terminal: the Interactions tab and the editor
              left (on-gtk [(key! "grave" g/CONTROL-MASK) (showing?) (focused?)])
              ;; an idle shell isn't busy; one with a background job is, which
              ;; is what the close question asks about
              idle (on-gtk (busy?))
              _ (on-gtk (anchan/feed! term "sleep 20 &\n"))
              job? (await-until 8000 50 busy?)
              _ (on-gtk (anchan/stop! term))
              stopped? (await-until 8000 50 #(not (anchan/running? term)))]
          (on-gtk (support/check :terminal-in-the-bottom-area
                                 (and unbuilt? started? (= {:showing true :pages 2} shown) focus
                                      echoed? (= 0 ctrl-r)
                                      (= [1 false true] f6-out) (= [1 true] f6-back)
                                      (= [1 false false] left) (false? idle) job? stopped?)
                                 {:unbuilt unbuilt? :started started? :shown shown :focus focus :echoed echoed?
                                  :ctrl-r ctrl-r :f6-out f6-out :f6-back f6-back :left left
                                  :idle-busy idle :job-busy job? :stopped stopped?})))))))
