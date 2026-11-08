(ns gtk-selftest-window.probe-part
  "The window self-test's probe part (goal 1 of the window parts): a command,
  a right-hand panel with its own key, a ui key and a quit hook, all added
  through jonquil.parts.extra with no change to jonquil.gtk.window or
  jonquil.command-registry.

  Load order is the point. jonquil.parts, jonquil.keymap, jonquil.palette and
  jonquil.command-registry compute their tables when they load, so this
  namespace is the first require of gtk-selftest-window and may require only
  what does not load them: jonquil.parts.extra and the GTK plumbing. A value
  those namespaces hold comes from wctx at install time."
  (:require [jonquil.gtk.ffi :as g]
            [jonquil.parts.extra :as extra]))

(def spec
  "The probe's data half: :probe/ping, the Probe panel's command and label,
  and the panel's key. Cmd/Ctrl+Shift+F2 is free: keymap.edn binds F2 only
  with :exact #{} (no modifier held), so the chord matches no :window or
  :editor binding, and no Cmd+digit tab binding or Alt chord is involved. F2
  is also one of the key names jonquil.keymap/key-value knows, which the
  reachability check needs (an unknown name is reported unreachable)."
  {:id :probe
   :commands
   {:probe/ping {:doc "Self-test probe: count a ping." :scope :window
                 :call [:probe-ping-fn]}
    :panel/probe {:doc "Focus the Probe panel, or open it." :scope :window
                  :call [:panel-focus-fn :probe]}}
   :panels [{:id :probe :label "Probe"}]
   :bindings {:window [{:key "F2" :mods #{:cmd :shift} :command :panel/probe}]}})

(defn- install
  "The probe for one window: a label panel, a ping counter, a ui key for the
  panel's root and its close, and a quit hook that does nothing."
  [{:keys [right show-right-panel! hide-right-panel!]}]
  (let [pings (atom 0)
        root (g/gtk-label-new "Probe panel")
        open! (fn [] (show-right-panel! right :probe) (g/gtk-widget-grab-focus root))]
    {:fns {:probe-ping-fn (fn [] (swap! pings inc))}
     :ui {:probe-pings* pings
          :probe-root root
          :probe-close! (fn [] (hide-right-panel! right :probe))}
     :panels {:probe {:root root
                      :focus! (fn [] (g/gtk-widget-grab-focus root))
                      :open! open!}}
     :hooks {:on-quit (fn [] nil)}}))

(def part
  "The probe's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :probe}))

;; Registered once: a reload leaves the first registration in place.
(when-not (some #(= :probe (:id %)) @extra/specs*)
  (swap! extra/specs* conj spec)
  (swap! extra/installs* conj part))
