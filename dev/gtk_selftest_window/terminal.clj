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
            [jonquil.gtk.window.alerts :as win-alerts]
            [jonquil.keymap :as km]))

(defn row-terminal-in-the-bottom-area
  "Nothing is built until the terminal is first shown. Show the terminal
  then makes the two tabs. With VTE a shell starts and echoes what it is
  fed, Larger Text gives it fewer columns, Ctrl+R is passed on, F6 and Ctrl+` leave the terminal, and a
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
              ;; Larger Text reaches the terminal: the same widget then holds
              ;; fewer columns, and Reset Text Size brings them back
              cols (fn [] (:columns (anchan/size term)))
              cols-before (on-gtk (cols))
              _ (on-gtk (dotimes [_ 4] ((:zoom!* ui) :in)))
              larger? (await-until 8000 50 #(< (cols) cols-before))
              _ (on-gtk ((:zoom!* ui) :reset))
              reset? (await-until 8000 50 #(= (cols) cols-before))
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
                                      echoed? larger? reset? (= 0 ctrl-r)
                                      (= [1 false true] f6-out) (= [1 true] f6-back)
                                      (= [1 false false] left) (false? idle) job? stopped?)
                                 {:unbuilt unbuilt? :started started? :shown shown :focus focus :echoed echoed?
                                  :columns cols-before :larger larger? :reset reset?
                                  :ctrl-r ctrl-r :f6-out f6-out :f6-back f6-back :left left
                                  :idle-busy idle :job-busy job? :stopped stopped?})))))))

(defn- no-vte-pass
  "The tab rows' answer on a machine without VTE: a pass, unless the run
  says VTE must be there (jolt linux:selftest)."
  [row]
  (support/check row (nil? (System/getenv "JONQUIL_SELFTEST_NEEDS_VTE")) {:no-vte true}))

(defn row-terminal-tabs
  "More than one terminal: New Terminal adds a tab with its own shell, what
  it is fed stays out of the first one, Show the terminal leaves it and
  comes back to the one used last, and the tab's close button takes an
  idle terminal away."
  [ui]
  (go-row :terminal-tabs support/check
    (let [{:keys [state show! new! close! numbers focused? showing?]} (:terminal ui)]
      (if (on-gtk (:why @state))
        (on-gtk (no-vte-pass :terminal-tabs))
        (let [pages (fn [] (g/gtk-notebook-get-n-pages (:notebook @state)))
              before (on-gtk {:numbers (numbers) :pages (pages)})
              first-term (on-gtk (:term @state))
              _ (on-gtk (new!))
              n (on-gtk (last (numbers)))
              term (on-gtk (:term @state))
              started? (await-until 8000 50 #(anchan/running? term))
              added (on-gtk {:numbers (numbers) :pages (pages) :showing (showing?) :focused (focused?)})
              _ (on-gtk (anchan/feed! term "echo tab-$((7*8))\n"))
              echoed? (await-until 8000 50 #(str/includes? (anchan/text term) "tab-56"))
              apart? (on-gtk (not (str/includes? (anchan/text first-term) "tab-56")))
              away (on-gtk (show!) [(showing?) (focused?)])
              back (on-gtk (show!) [(showing?) (focused?) (:last @state)])
              _ (on-gtk (close! n))
              closed (on-gtk {:numbers (numbers) :pages (pages)})
              want {:added {:numbers (conj (:numbers before) n) :pages (inc (:pages before))
                            :showing true :focused true}
                    :away [false false] :back [true true n] :closed before}
              got {:added added :away away :back back :closed closed}]
          (on-gtk (support/check :terminal-tabs (and started? echoed? apart? (= want got))
                                 (assoc got :before before :n n :started started? :echoed echoed? :apart apart?))))))))

(defn row-terminal-tab-close-asks
  "A tab's close button asks first while a program is running in its
  terminal, and Cancel keeps the tab. With every terminal closed only
  Interactions is left, and Show the terminal opens Terminal 1 again. The
  row ends in the editor, so the rows after it have the window's keys."
  [ui]
  (go-row :terminal-tab-close-asks support/check
    (let [{:keys [state show! new! close! numbers busy? focused? showing?]} (:terminal ui)]
      (if (on-gtk (:why @state))
        (on-gtk (no-vte-pass :terminal-tab-close-asks))
        (let [pages (fn [] (g/gtk-notebook-get-n-pages (:notebook @state)))
              _ (on-gtk (new!))
              n (on-gtk (last (numbers)))
              open (on-gtk (numbers))
              term (on-gtk (:term @state))
              started? (await-until 8000 50 #(anchan/running? term))
              ;; the shell answers before the job is started: a shell that is
              ;; still starting looks busy, and would take the line too early
              _ (on-gtk (anchan/feed! term "echo ready-$((6*6))\n"))
              ready? (await-until 8000 50 #(str/includes? (anchan/text term) "ready-36"))
              ;; in the foreground and ended with Ctrl+C: what a shell prints
              ;; for a job started with & differs from one shell to the next
              _ (on-gtk (anchan/feed! term "sleep 20\n"))
              job? (await-until 8000 50 busy?)
              alerts-before (on-gtk @(:alert-dialog-count ui))
              _ (on-gtk (close! n))
              asked (on-gtk (- @(:alert-dialog-count ui) alerts-before))
              _ (on-gtk (win-alerts/dismiss-pending-alerts!))
              settled? (await-until 3000 20 #(zero? ((:pending-alerts* ui))))
              kept (on-gtk (numbers))
              _ (on-gtk (anchan/feed! term "\u0003"))
              idle? (await-until 8000 50 #(not (busy?)))
              _ (on-gtk (doseq [m (numbers)] (close! m)))
              none (on-gtk {:numbers (numbers) :pages (pages) :showing (showing?)})
              _ (on-gtk (show!))
              again (on-gtk {:numbers (numbers) :pages (pages) :focused (focused?)})
              last-term (on-gtk (:term @state))
              _ (on-gtk (anchan/stop! last-term))
              stopped? (await-until 8000 50 #(not (anchan/running? last-term)))
              left (on-gtk (show!) [(showing?) (focused?)])
              want {:asked 1 :kept open :none {:numbers [] :pages 1 :showing false}
                    :again {:numbers [1] :pages 2 :focused true} :left [false false]}
              got {:asked asked :kept kept :none none :again again :left left}]
          (on-gtk (support/check :terminal-tab-close-asks
                                 (and started? ready? job? settled? idle? stopped? (= want got))
                                 (assoc got :n n :started started? :ready ready? :job (boolean job?) :settled settled? :idle idle?
                                        :stopped stopped?))))))))
