(ns gtk-selftest-window.themes
  "apply-theme!, the theme chord/cycle, and settings isolation/save/load."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.appearance :as appearance]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.settings :as settings]
            [jonquil.theme :as theme]))

;; --- TH.T4: apply-theme! (retint tags, reload the CSS, flip prefer-dark) --
;; Every row here ends by restoring :light and zoom :reset, per the shared
;; rule (02-gtk.md) that later rows see the colours/size they expect.

(defn row-theme-retints-tags
  "Reads each tag's colour back via rgba-property-hex/get-bool-property
  (probe rgba.clj), before and after switching theme, against the exact
  hex values measured from jonquil.theme/themes: navy's :keyword
  (#c792ea), [:rainbow 0] (#f78c6c), :unmatched (:red, #f07178), the
  console's :result (#c3e88d) and the find bar's :find-highlight
  (#4d4520); then light's same five (#cf222e, #953800, #cf222e, #0a3069,
  #fff2a8). Also asserts the find bar tag's own foreground-rgba, which
  must equal the theme's :text (navy #c8cad8, light #24292f) so matched
  text stays readable on top of the highlight."
  [ui]
  (go-row :theme-retints-tags support/check
    (on-gtk (let [ed (:editor ui) cv (:console ui) fb (:find-bar ui)
                  kw-tag (get (:tags ed) :keyword)
                  rb0-tag (get (:tags ed) [:rainbow 0])
                  um-tag (get (:tags ed) :unmatched)
                  result-tag (get (:tags cv) :result)
                  fb-tag (:tag fb)]
              ((:set-theme!* ui) :navy)
              (let [kw-navy (g/rgba-property-hex kw-tag "foreground-rgba")
                    rb0-navy (g/rgba-property-hex rb0-tag "foreground-rgba")
                    um-navy (g/rgba-property-hex um-tag "foreground-rgba")
                    result-navy (g/rgba-property-hex result-tag "foreground-rgba")
                    fb-navy (g/rgba-property-hex fb-tag "background-rgba")
                    fbfg-navy (g/rgba-property-hex fb-tag "foreground-rgba")]
                ((:set-theme!* ui) :light)
                (let [kw-light (g/rgba-property-hex kw-tag "foreground-rgba")
                      rb0-light (g/rgba-property-hex rb0-tag "foreground-rgba")
                      um-light (g/rgba-property-hex um-tag "foreground-rgba")
                      result-light (g/rgba-property-hex result-tag "foreground-rgba")
                      fb-light (g/rgba-property-hex fb-tag "background-rgba")
                      fbfg-light (g/rgba-property-hex fb-tag "foreground-rgba")]
                  (support/check :theme-retints-tags
                                 (and (= "#c792ea" kw-navy) (= "#f78c6c" rb0-navy) (= "#f07178" um-navy)
                                      (= "#c3e88d" result-navy) (= "#4d4520" fb-navy) (= "#c8cad8" fbfg-navy)
                                      (= "#cf222e" kw-light) (= "#953800" rb0-light) (= "#cf222e" um-light)
                                      (= "#0a3069" result-light) (= "#fff2a8" fb-light) (= "#24292f" fbfg-light))
                                 {:kw-navy kw-navy :rb0-navy rb0-navy :um-navy um-navy
                                  :result-navy result-navy :fb-navy fb-navy :fbfg-navy fbfg-navy
                                  :kw-light kw-light :rb0-light rb0-light :um-light um-light
                                  :result-light result-light :fb-light fb-light :fbfg-light fbfg-light})
                  ((:zoom!* ui) :reset)))))))

(defn row-theme-css
  "dark's code-css (jonquil.theme/code-css) at the default font size, read
  back from the shared GtkCssProvider's OWN serialisation (:code-css*
  calls gtk_css_provider_to_string, not the code-css atom mirror).
  Measured (2026-09-26): GTK normalises hex
  colours to rgb(r,g,b) and re-orders/re-indents the declarations, but
  keeps \"font-size: <n>px;\" verbatim -- background-color rgb(0,0,0),
  color rgb(224,224,224), font-size 13px."
  [ui]
  (go-row :theme-css support/check
    (on-gtk ((:zoom!* ui) :reset)
            ((:set-theme!* ui) :dark)
            (let [css ((:code-css* ui))]
              (support/check :theme-css
                             (and (str/includes? css "background-color: rgb(0,0,0);")
                                  (str/includes? css "color: rgb(224,224,224);")
                                  (str/includes? css "font-size: 13px;"))
                             {:css css})
              ((:set-theme!* ui) :light)
              ((:zoom!* ui) :reset)))))

(defn row-theme-prefer-dark
  "GtkSettings' colour scheme, read back via color-scheme-dark?: true for
  dark's :dark? true, false for solarized's :dark? false.
  gtk-application-prefer-dark-theme is deprecated (GTK 4.20+, a
  g_warning from 4.23.1); apply-window-theme! now sets the replacement,
  gtk-interface-color-scheme, so this row reads that property instead
  (measured to darken the chrome exactly as the old boolean did -- see
  ffi.clj's own citation)."
  [ui]
  (go-row :theme-prefer-dark support/check
    (on-gtk ((:set-theme!* ui) :dark)
            (let [dark? (g/color-scheme-dark? (g/gtk-settings-get-default))]
              ((:set-theme!* ui) :solarized)
              (let [solarized-dark? (g/color-scheme-dark? (g/gtk-settings-get-default))]
                (support/check :theme-prefer-dark (and dark? (not solarized-dark?))
                               {:dark dark? :solarized-dark solarized-dark?})
                ((:set-theme!* ui) :light)
                ((:zoom!* ui) :reset))))))

(defn row-theme-keeps-text-and-undo
  "A theme switch never touches buffer text, the selection or undo
  history: inserts \"x\" interactively (an undoable step) after set-text!'s
  own irreversible \"(def a 1)\", selects [0 4], switches theme, then
  checks the text, the selection, get-can-undo and one undo's result are
  all exactly what they'd be without the switch."
  [ui]
  (go-row :theme-keeps-text-and-undo support/check
    (on-gtk (let [ed (:editor ui) buf (:buf ed)]
              (editor/set-text! ed "(def a 1)")
              (support/place-cursor! buf (count "(def a 1)"))
              (g/gtk-text-buffer-insert-interactive-at-cursor buf "x" -1 1)
              (support/select-range! buf 0 4)
              ((:set-theme!* ui) :navy)
              (let [text (editor/editor-text ed)
                    sel (g/selection-offsets buf)
                    can-undo (not (zero? (g/gtk-text-buffer-get-can-undo buf)))]
                (g/gtk-text-buffer-undo buf)
                (let [undone (editor/editor-text ed)]
                  (support/check :theme-keeps-text-and-undo
                                 (and (= "(def a 1)x" text) (= [0 4] sel) can-undo (= "(def a 1)" undone))
                                 {:text text :sel sel :can-undo can-undo :undone undone})
                  ((:set-theme!* ui) :light)
                  ((:zoom!* ui) :reset)))))))

(defn row-theme-keeps-find-highlight
  "A theme switch never touches the find bar's :matches: with the bar open
  on \"foo bar foo\" (matches at [0 3] and [8 11]), switching theme must
  leave both tagged, the count label unchanged, the tag's background
  retinted (dark's #5c4b00) and its foreground equal to the theme's
  :text (dark #e0e0e0) so matched text stays readable. Also
  checks the search-match tag's tag-table priority against a syntax tag's
  (:special-form, present in the buffer as \"foo\" isn't a token but the
  tag object exists once the editor's paint plan has run) -- GTK's
  priority is add order and a higher priority wins a conflicting property
  on an overlapping range, so search-match (added after every editor tag
  in make-find-bar) must read a strictly higher priority."
  [ui]
  (go-row :theme-keeps-find-highlight support/check
    (on-gtk (let [ed (:editor ui) fb (:find-bar ui) buf (:buf ed)
                  special-form-tag (get (:tags ed) :special-form)]
              (editor/set-text! ed "foo bar foo")
              (find-bar/open! fb :find)
              (g/gtk-editable-set-text (:query fb) "foo")
              (let [label-before (g/label-text (:count-label fb))]
                ((:set-theme!* ui) :dark)
                (let [tag0 (g/has-tag-at? buf 0 (:tag fb))
                      tag8 (g/has-tag-at? buf 8 (:tag fb))
                      label-after (g/label-text (:count-label fb))
                      bg (g/rgba-property-hex (:tag fb) "background-rgba")
                      fg (g/rgba-property-hex (:tag fb) "foreground-rgba")
                      fb-priority (g/gtk-text-tag-get-priority (:tag fb))
                      syntax-priority (g/gtk-text-tag-get-priority special-form-tag)]
                  (find-bar/close! fb)
                  (support/check :theme-keeps-find-highlight
                                 (and tag0 tag8 (= label-before label-after) (= "#5c4b00" bg) (= "#e0e0e0" fg)
                                      (> fb-priority syntax-priority))
                                 {:tag0 tag0 :tag8 tag8 :label-before label-before :label-after label-after
                                  :bg bg :fg fg :fb-priority fb-priority :syntax-priority syntax-priority})
                  ((:set-theme!* ui) :light)
                  ((:zoom!* ui) :reset)))))))

(defn row-theme-and-zoom-share-css
  "Zoom and theme reload the SAME shared provider and must not overwrite
  each other's part of the CSS: zoom :in (13->14px), switch to navy (css1:
  14px + navy's #1a1b2e background), zoom :in again (14->16px; css2: 16px
  and STILL navy's #1a1b2e, not the default theme's). Also reads from
  the provider's own serialisation (:code-css*), where navy's #1a1b2e
  prints as rgb(26,27,46) (measured 2026-09-26)."
  [ui]
  (go-row :theme-and-zoom-share-css support/check
    (on-gtk ((:zoom!* ui) :reset)
            ((:zoom!* ui) :in)
            ((:set-theme!* ui) :navy)
            (let [css1 ((:code-css* ui))]
              ((:zoom!* ui) :in)
              (let [css2 ((:code-css* ui))]
                (support/check :theme-and-zoom-share-css
                               (and (str/includes? css1 "font-size: 14px;") (str/includes? css1 "rgb(26,27,46)")
                                    (str/includes? css2 "font-size: 16px;") (str/includes? css2 "rgb(26,27,46)"))
                               {:css1 css1 :css2 css2})
                ((:set-theme!* ui) :light)
                ((:zoom!* ui) :reset))))))

(defn row-theme-applies-to-new-text
  "Tags that repaint! applies later are the same tag objects apply-theme!
  already retinted, so new/pasted text needs no repaint of its own: sets
  navy, THEN types new text, waits for the debounce repaint (a tag over
  offset 1, \"defn\"), and checks that tag is :special-form at navy's
  #c792ea."
  [ui]
  (go-row :theme-applies-to-new-text support/check
    (let [[buf tag] (on-gtk (let [ed (:editor ui) buf (:buf ed)
                                  tag (get (:tags ed) :special-form)]
                              ((:set-theme!* ui) :navy)
                              (editor/set-text! ed "(defn f [x] :k)")
                              [buf tag]))
          tagged? (await-until 500 20 #(g/has-tag-at? buf 1 tag))]
      (on-gtk (let [fg (g/rgba-property-hex tag "foreground-rgba")]
                (support/check :theme-applies-to-new-text
                               (and tagged? (= "#c792ea" fg))
                               {:tagged tagged? :fg fg})
                ((:set-theme!* ui) :light)
                ((:zoom!* ui) :reset))))))

(defn row-theme-cycle
  "Five :cycle-theme!* calls with no program running walk theme/choice-names
  in order, starting from :light (this harness's own isolated settings
  path has no file yet, so activate!'s startup load defaults to :light).
  :theme-choice* reads :dark :solarized :navy :auto :light. :theme-name*
  reads :dark :solarized :navy for the first three; the fake system is
  light, so the :auto step resolves to :light whatever the Mac is showing.
  After the FIRST call the status label reads \"Theme: dark\" and the
  settings file at (:settings-path* ui) reads back {:theme :dark} through
  jonquil.settings/load-settings itself, not a raw slurp.

  set-status! (window.clj) is on-gui!-scheduled, the same async-drain
  lesson (I1) every console-text assertion in this file already respects
  -- a synchronous read right after :cycle-theme!* can race the queued
  update and see a PRIOR row's leftover status text, so status1 is polled
  for, not read once."
  [ui]
  (go-row :theme-cycle support/check
    (on-gtk ((:system-dark-fn!* ui) (constantly false))
            ((:cycle-theme!* ui)))
    (let [status1-ok? (await-until 500 20 #(= "Theme: dark" (g/label-text (:status-label ui))))]
      (on-gtk (let [choice1 ((:theme-choice* ui))
                    name1 ((:theme-name* ui))
                    status1 (g/label-text (:status-label ui))
                    settings1 (settings/load-settings ((:settings-path* ui)))
                    _ ((:cycle-theme!* ui))
                    choice2 ((:theme-choice* ui))
                    name2 ((:theme-name* ui))
                    _ ((:cycle-theme!* ui))
                    choice3 ((:theme-choice* ui))
                    name3 ((:theme-name* ui))
                    _ ((:cycle-theme!* ui))
                    choice4 ((:theme-choice* ui))
                    _ ((:cycle-theme!* ui))
                    choice5 ((:theme-choice* ui))
                    name5 ((:theme-name* ui))]
                (support/check :theme-cycle
                               (and (= [:dark :solarized :navy :auto :light] [choice1 choice2 choice3 choice4 choice5])
                                    (= [:dark :solarized :navy] [name1 name2 name3])
                                    (= :light name5)
                                    status1-ok? (= :dark (:theme settings1)))
                               {:choices [choice1 choice2 choice3 choice4 choice5]
                                :names [name1 name2 name3 name5]
                                :status1 status1 :settings1 settings1})
                ((:set-theme!* ui) :light)
                ((:system-dark-fn!* ui) appearance/system-dark?)
                ((:zoom!* ui) :reset))))))

(defn row-theme-chord
  "The window's own key dispatch, driven directly (never a synthesized
  keystroke): 0x04d (uppercase M, as Shift+m arrives) + CONTROL-MASK +
  SHIFT-MASK, then 0x06d (lowercase m) + META-MASK + SHIFT-MASK -- both
  Control and Meta count as the command chord, so this
  covers Linux and macOS. Each call must return 1 and advance the theme
  one step from wherever the previous call (or an earlier row) left it."
  [ui]
  (go-row :theme-chord support/check
    (on-gtk (let [before ((:theme-name* ui))
                  h1 ((:handle-window-key! ui) nil 0x04d 0 (bit-or g/CONTROL-MASK g/SHIFT-MASK) nil)
                  after1 ((:theme-name* ui))
                  h2 ((:handle-window-key! ui) nil 0x06d 0 (bit-or g/META-MASK g/SHIFT-MASK) nil)
                  after2 ((:theme-name* ui))]
              (support/check :theme-chord
                             (and (= 1 h1) (not= before after1) (= 1 h2) (not= after1 after2))
                             {:before before :h1 h1 :after1 after1 :h2 h2 :after2 after2})
              ((:set-theme!* ui) :light)
              ((:zoom!* ui) :reset)))))

(defn row-theme-chord-needs-shift
  "The same lower-cased 0x06d with CONTROL-MASK alone (no Shift) must not
  cycle the theme: the window handler returns 0 and the theme is
  unchanged."
  [ui]
  (go-row :theme-chord-needs-shift support/check
    (on-gtk (let [before ((:theme-name* ui))
                  handled ((:handle-window-key! ui) nil 0x06d 0 g/CONTROL-MASK nil)
                  after ((:theme-name* ui))]
              (support/check :theme-chord-needs-shift (and (= 0 handled) (= before after))
                             {:handled handled :before before :after after})
              ((:set-theme!* ui) :light)
              ((:zoom!* ui) :reset)))))

(defn row-settings-isolated
  "This window's own settings path (:settings-path* ui) lives under
  java.io.tmpdir and differs from the real one (jonquil.settings/
  settings-path, no env/home override -- the actual production path);
  cycling the theme, which writes to the WINDOW's path, must never touch
  the real file. Checked via .exists/.lastModified/.length only --
  never a slurp of the real file's content, which would read (and print)
  whatever theme the user's own real settings.edn happens to hold."
  [ui]
  (go-row :settings-isolated support/check
    (on-gtk (let [window-path ((:settings-path* ui))
                  real-path (settings/settings-path)
                  real-file (java.io.File. ^String real-path)
                  existed-before (.exists real-file)
                  mtime-before (.lastModified real-file)
                  length-before (.length real-file)]
              ((:cycle-theme!* ui))
              (let [existed-after (.exists real-file)
                    mtime-after (.lastModified real-file)
                    length-after (.length real-file)]
                ((:set-theme!* ui) :light)
                ((:zoom!* ui) :reset)
                (support/check :settings-isolated
                               (and (str/starts-with? window-path (System/getProperty "java.io.tmpdir"))
                                    (not= window-path real-path)
                                    (= existed-before existed-after)
                                    (= mtime-before mtime-after)
                                    (= length-before length-after))
                               {:window-path window-path :real-path real-path
                                :existed-before existed-before :existed-after existed-after
                                :mtime-before mtime-before :mtime-after mtime-after
                                :length-before length-before :length-after length-after}))))))

(defn row-settings-unreadable-warns
  "A settings file whose contents aren't readable EDN: (:load-settings!* ui)
  (activate!'s own startup-load fn, exposed so this row drives the exact
  same path) against it must still leave the theme at :light and append a
  warning to the console -- jonquil.settings/load-settings' own message,
  'couldn't read <path>, using light', prefixed with '; settings: '."
  [ui]
  (go-row :settings-unreadable-warns support/check
    (let [[mark tmp] (on-gtk (let [m (support/out-end-offset ui)
                                   tmp (str (System/getProperty "java.io.tmpdir")
                                            "/jonquil-selftest-bad-settings-" (System/currentTimeMillis) ".edn")]
                               (spit tmp "{:theme")
                               ((:load-settings!* ui) tmp)
                               [m tmp]))
          warned? (await-until 1000 20 #(str/includes? (support/since ui mark) "; settings: couldn't read"))]
      (on-gtk (let [theme-name ((:theme-name* ui))]
                (support/check :settings-unreadable-warns
                               (and (= :light theme-name) warned?)
                               {:theme-name theme-name :console (support/since ui mark)})
                (.delete (java.io.File. ^String tmp))
                ((:set-theme!* ui) :light)
                ((:zoom!* ui) :reset))))))

(defn row-theme-save-failure-reported
  "Test hook: :settings-path!* (TH.T5's own self-test-only addition,
  window.clj) repoints the window's own settings-path atom -- here at an
  IMPOSSIBLE path (a regular file used as the would-be parent directory,
  so io/make-parents can't create it and the temp-file spit inside it
  throws). :cycle-theme!* must still advance the theme and report the
  save failure on the console (jonquil.settings/save-settings!'s own
  'couldn't save <path>: ...' message, prefixed with '; settings: ')."
  [ui]
  (go-row :theme-save-failure-reported support/check
    (let [{:keys [mark original-path blocking-file before after]}
          (on-gtk (let [mark (support/out-end-offset ui)
                        original-path ((:settings-path* ui))
                        blocking-file (str (System/getProperty "java.io.tmpdir")
                                           "/jonquil-selftest-impossible-parent-" (System/currentTimeMillis))
                        impossible-path (str blocking-file "/settings.edn")]
                    (spit blocking-file "not a directory")
                    (let [before ((:theme-name* ui))]
                      ((:settings-path!* ui) impossible-path)
                      ((:cycle-theme!* ui))
                      (let [after ((:theme-name* ui))]
                        {:mark mark :original-path original-path :blocking-file blocking-file
                         :before before :after after}))))
          reported? (await-until 1000 20 #(str/includes? (support/since ui mark) "; settings: couldn't save"))]
      (on-gtk ((:settings-path!* ui) original-path)
              (support/check :theme-save-failure-reported
                             (and (not= before after) reported?)
                             {:before before :after after :reported reported? :console (support/since ui mark)})
              (.delete (java.io.File. ^String blocking-file))
              ((:set-theme!* ui) :light)
              ((:zoom!* ui) :reset)))))

(defn row-theme-switch-while-running
  "Cycling the theme while a program is running must not overwrite a
  live Running…/Waiting for input with 'Theme: <name>'. Runs (loop []
  (recur)), waits for the status to actually read Running…, cycles the
  theme, and requires the status to STILL read Running… right after, the
  theme to have advanced, and Escape (via :handle-window-key!, still the
  same real Stop path) to still stop the program.

  Waits for the settled terminal status 'Stopped', never 'Ready': measured
  against make-eval-opts' :on-complete (window.clj), an INTERRUPTED run's
  terminal status is 'Stopped', never 'Ready' -- :on-status-change sets
  'Ready' first (the :idle notification), then :on-complete immediately
  overwrites it with 'Stopped' since (:status result) is :interrupted here.
  'Ready' is only ever the terminal text for a run that finishes on its
  own (row-run-output's own case), which a poll for 'Ready' would never
  see after an Escape-stop."
  [ui]
  (go-row :theme-switch-while-running support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(loop [] (recur))")
                         ((:run!* ui))
                         m))
          running? (await-until 2000 20 #(= "Running…" (g/label-text (:status-label ui))))
          {:keys [before after status-right-after handled]}
          (on-gtk (let [before ((:theme-name* ui))
                        _ ((:cycle-theme!* ui))
                        after ((:theme-name* ui))
                        status-right-after (g/label-text (:status-label ui))
                        handled ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)]
                    {:before before :after after
                     :status-right-after status-right-after :handled handled}))
          stopped? (await-until 3000 20 #(str/includes? (support/since ui mark) "; interrupted"))
          settled? (await-until 2000 20 #(= "Stopped" (g/label-text (:status-label ui))))]
      (on-gtk (support/check :theme-switch-while-running
                             (and running? (not= before after)
                                  (= "Running…" status-right-after)
                                  (= 1 handled) stopped? settled?)
                             {:running running? :before before :after after
                              :status status-right-after :handled handled
                              :stopped stopped? :settled settled?
                              :console (support/since ui mark)})
              ((:set-theme!* ui) :light)
              ((:zoom!* ui) :reset)))))

;; --- auto theme: follows the (faked) system appearance -------------------
;; Each row starts from :light and a fake system that says dark, and ends by
;; restoring :light, the real appearance/system-dark? and zoom.

(defn- start-fake-system!
  "Sets :light and points the window at a fake system. Returns the atom the
  row flips: true means the fake system is dark."
  [ui]
  (let [dark? (atom true)]
    ((:set-theme!* ui) :light)
    ((:system-dark-fn!* ui) (fn [] @dark?))
    dark?))

(defn- restore-real-system! [ui]
  ((:set-theme!* ui) :light)
  ((:system-dark-fn!* ui) appearance/system-dark?)
  ((:zoom!* ui) :reset))

(defn- css-background [k]
  (let [h (:background (theme/theme k))
        [r g b] (map #(Integer/parseInt (subs h % (+ % 2)) 16) [1 3 5])]
    (str "background-color: rgb(" r "," g "," b ");")))

(defn row-auto-follows-system
  "From :navy, one :cycle-theme!* reaches :auto. With the fake system dark
  that shows the dark palette (a :keyword tag's foreground, as
  row-theme-retints-tags reads it), says \"Theme: auto (dark)\" and saves
  {:theme :auto}. Flipping the fake to light and running one poll tick
  shows light (tag foreground and the provider's background) and leaves the
  saved file at :auto."
  [ui]
  (go-row :auto-follows-system support/check
    (let [dark? (on-gtk (let [d (start-fake-system! ui)]
                          ((:set-theme!* ui) :navy)
                          ((:cycle-theme!* ui))
                          d))
          status-ok? (await-until 500 20 #(= "Theme: auto (dark)" (g/label-text (:status-label ui))))]
      (on-gtk (let [kw-tag (get (:tags (:editor ui)) :keyword)
                    choice1 ((:theme-choice* ui))
                    name1 ((:theme-name* ui))
                    fg1 (g/rgba-property-hex kw-tag "foreground-rgba")
                    saved1 (:theme (settings/load-settings ((:settings-path* ui))))
                    _ (reset! dark? false)
                    _ ((:poll-appearance!* ui))
                    name2 ((:theme-name* ui))
                    fg2 (g/rgba-property-hex kw-tag "foreground-rgba")
                    css ((:code-css* ui))
                    saved2 (:theme (settings/load-settings ((:settings-path* ui))))
                    want-dark (theme/token-color (theme/theme :dark) :keyword)
                    want-light (theme/token-color (theme/theme :light) :keyword)]
                (support/check :auto-follows-system
                               (and (= :auto choice1) (= :dark name1) (= want-dark fg1) status-ok?
                                    (= :auto saved1)
                                    (= :light name2) (= want-light fg2)
                                    (str/includes? css (css-background :light))
                                    (= :auto saved2))
                               {:choice1 choice1 :name1 name1 :fg1 fg1 :want-dark want-dark
                                :status-ok? status-ok? :saved1 saved1 :name2 name2 :fg2 fg2
                                :want-light want-light :css css :saved2 saved2})
                (restore-real-system! ui))))))

(defn row-auto-flip-keeps-text
  "A system flip under :auto never touches buffer text or undo history:
  after one interactive edit, choosing :auto (fake dark), flipping to light
  and ticking leaves the text as it was and one undo restores the pre-edit
  text."
  [ui]
  (go-row :auto-flip-keeps-text support/check
    (on-gtk (let [dark? (start-fake-system! ui)
                  ed (:editor ui) buf (:buf ed)]
              (editor/set-text! ed "(def a 1)")
              (support/place-cursor! buf (count "(def a 1)"))
              (g/gtk-text-buffer-insert-interactive-at-cursor buf "x" -1 1)
              ((:set-theme!* ui) :auto)
              (reset! dark? false)
              ((:poll-appearance!* ui))
              (let [text (editor/editor-text ed)
                    name1 ((:theme-name* ui))
                    can-undo (not (zero? (g/gtk-text-buffer-get-can-undo buf)))]
                (g/gtk-text-buffer-undo buf)
                (let [undone (editor/editor-text ed)]
                  (support/check :auto-flip-keeps-text
                                 (and (= "(def a 1)x" text) (= :light name1) can-undo
                                      (= "(def a 1)" undone))
                                 {:text text :name1 name1 :can-undo can-undo :undone undone})
                  (restore-real-system! ui)))))))

(defn row-auto-poll-stops
  "Once a concrete theme is chosen the poll no longer follows the system:
  :auto then :solarized, flip the fake to light, tick. :theme-name* stays
  :solarized and the tick returns 0 (GLib drops the timer)."
  [ui]
  (go-row :auto-poll-stops support/check
    (on-gtk (let [dark? (start-fake-system! ui)]
              ((:set-theme!* ui) :auto)
              ((:set-theme!* ui) :solarized)
              (reset! dark? false)
              (let [ret ((:poll-appearance!* ui))
                    name1 ((:theme-name* ui))]
                (support/check :auto-poll-stops
                               (and (= :solarized name1) (= 0 ret))
                               {:name1 name1 :ret ret})
                (restore-real-system! ui))))))

(def ^:private mac? (= "Mac OS X" (System/getProperty "os.name")))

(defn row-auto-poll-single-timer
  "Choosing :auto five times in a row arms one poll timer, not five, and
  none off macOS, where there's no system appearance to poll. Every
  step runs inside one on-gtk block so no timer tick can fire between them.
  The window counts each real arming (:poll-arm-count*); the count must rise
  by exactly 1 from :light with the fake dark, then cycle through
  light..navy back to :auto. An earlier :auto row may have left a real
  timer alive, so the row first sets :light and waits (a real tick within
  about 1 s drops it) until :poll-armed?* reads false."
  [ui]
  (go-row :auto-poll-single-timer support/check
    (on-gtk (start-fake-system! ui))
    (let [idle? (await-until 2500 50 #(not ((:poll-armed?* ui))))]
      (on-gtk (let [before ((:poll-arm-count* ui))]
                ((:set-theme!* ui) :auto)
                (dotimes [_ 5] ((:cycle-theme!* ui)))
                (let [after ((:poll-arm-count* ui))
                      choice ((:theme-choice* ui))]
                  (support/check :auto-poll-single-timer
                                 (and idle? (= :auto choice) (= (if mac? 1 0) (- after before)))
                                 {:idle? idle? :before before :after after :choice choice})
                  (restore-real-system! ui)))))))

(defn row-auto-poll-nil-keeps-palette
  "A failed system read (nil) during a poll tick leaves the palette alone:
  :auto with the fake dark shows :dark, then the fake returns nil and one
  tick leaves :theme-name* at :dark and returns 1."
  [ui]
  (go-row :auto-poll-nil-keeps-palette support/check
    (on-gtk (let [_ (start-fake-system! ui)]
              ((:set-theme!* ui) :auto)
              (let [before ((:theme-name* ui))]
                ((:system-dark-fn!* ui) (fn [] nil))
                (let [ret ((:poll-appearance!* ui))
                      after ((:theme-name* ui))]
                  (support/check :auto-poll-nil-keeps-palette
                                 (and (= :dark before) (= :dark after) (= 1 ret))
                                 {:before before :after after :ret ret})
                  (restore-real-system! ui)))))))

(defn row-auto-startup-load
  "A settings file holding {:theme :auto} loads through the startup path
  (:load-settings!*): the choice is :auto, the shown theme follows the fake
  dark system, and the console gets no \"; settings:\" line."
  [ui]
  (go-row :auto-startup-load support/check
    (let [[mark tmp] (on-gtk (let [_ (start-fake-system! ui)
                                   m (support/out-end-offset ui)
                                   tmp (str (System/getProperty "java.io.tmpdir")
                                            "/jonquil-selftest-auto-settings-" (System/currentTimeMillis) ".edn")]
                               (spit tmp (pr-str {:theme :auto}))
                               ((:load-settings!* ui) tmp)
                               [m tmp]))]
      ;; Give any (wrongly) queued console line time to land first.
      (await-until 300 20 #(str/includes? (support/since ui mark) "; settings:"))
      (on-gtk (let [choice ((:theme-choice* ui))
                    name1 ((:theme-name* ui))
                    console (support/since ui mark)]
                (support/check :auto-startup-load
                               (and (= :auto choice) (= :dark name1)
                                    (not (str/includes? console "; settings:")))
                               {:choice choice :name1 name1 :console console})
                (.delete (java.io.File. ^String tmp))
                (restore-real-system! ui))))))

(defn row-system-dark-real
  "appearance/system-dark? on the GTK thread is a real boolean on macOS,
  and nil elsewhere (auto then resolves to light)."
  [_ui]
  (go-row :system-dark-real support/check
    (on-gtk (let [r (appearance/system-dark?)]
              (support/check :system-dark-real (if mac? (boolean? r) (nil? r)) {:result r :mac? mac?})))))
