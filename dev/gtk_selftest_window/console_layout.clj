(ns gtk-selftest-window.console-layout
  "I5 fix wave item 3: the console wraps a line wider than the view instead
  of scrolling sideways to show it. Before the fix (jonquil.gtk.console-
  view/make-console-view leaving the view at the default GTK_WRAP_NONE),
  appending a line wider than the view opened a horizontal scroll range,
  and scroll-to-end!'s own xalign 0.0 then pushed the view's horizontal
  adjustment off 0 on every later drain -- clipping every subsequent line
  by a couple of characters (phase-review-report.md's I5, reproduced
  exactly in target/smoke/nrepl-run.png)."
  (:require [clojure.string :as str]
            [gtk-async :refer [on-gtk await-until go-row]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]))

(defn- hadjustment-value
  "The console view's own horizontal adjustment value (gtk_scrollable_get_
  hadjustment, gtk_adjustment_get_value -- both jonquil.gtk.ffi bindings
  added for this row): 0 means the viewport's left edge sits at the
  buffer's left edge, i.e. nothing is scrolled off to the left."
  [view]
  (g/gtk-adjustment-get-value (g/gtk-scrollable-get-hadjustment view)))

(defn row-console-wraps-long-lines [ui]
  (go-row :console-wraps-long-lines support/check
    (let [cv (:console ui)
          view (:view cv)
          ;; One unbroken run of 500 characters, wider than the 900px-default
          ;; window (I5's own citation of the trap: a single unbreakable
          ;; "word" this long still forces GTK_WRAP_WORD to fall back to
          ;; GTK_WRAP_CHAR-style breaking under WORD_CHAR -- plain WORD would
          ;; leave it unbroken and reopen the same horizontal-scroll bug).
          long-line (apply str (repeat 500 \x))
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (cview/append! cv :out (str long-line "\n"))
                         m))
          long-landed? (await-until 2000 20 #(str/includes? (support/since ui mark) long-line))
          _ (on-gtk (cview/append! cv :out "short-line\n"))
          short-landed? (await-until 2000 20 #(str/includes? (support/since ui mark) "short-line"))]
      (on-gtk (let [hadj (hadjustment-value view)]
                (support/check :console-wraps-long-lines
                               (and long-landed? short-landed? (zero? hadj))
                               {:long-landed long-landed? :short-landed short-landed?
                                :hadjustment hadj}))))))
