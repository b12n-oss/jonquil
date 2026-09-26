(ns hooks.jolt-ffi
  "clj-kondo hook for jolt.ffi/defcfn.

  Adapted from a-private-sibling's `.clj-kondo/hooks/jolt_ffi.clj` (same
  `jolt.ffi/defcfn` macro, same false-positive problem; that file in turn
  adapted it from b12n-rljlt). Jonquil's ffi.clj binds GTK4/GLib/GObject/
  pango, not raylib or AppKit, so the comments below are rewritten for what
  this project's bindings actually declare.

  `defcfn` binds a C symbol to a Clojure var:

      (ffi/defcfn gtk-application-new \"gtk_application_new\" [:string :uint] :pointer)

  clj-kondo cannot see through the macro, so without this hook every bound
  name in jonquil.gtk.ffi is an `Unresolved symbol`, and every call site
  through the `g` alias (jonquil.gtk.window, .editor, .console-view,
  .popover, .find-bar, .callbacks, .help-popover, plus dev/*) is an
  `Unresolved var: g/…` — measured at 188 Unresolved-symbol errors in
  ffi.clj and 281 Unresolved-var warnings at call sites before this hook
  existed, enough noise to make the linter useless as a gate.

  The hook rewrites the form into a `defn` of the same name whose parameter
  count matches the C argument-type vector, and whose body is a literal of
  the declared C return type. That buys three things clj-kondo could not
  otherwise know:

    * the var exists (kills the false positives),
    * its arity — passing the wrong number of arguments to a binding is
      exactly the FFI mistake that otherwise surfaces only as a native
      crash,
    * its return type, so e.g. `(neg? (u/some-int-returning-call …))`
      type-checks instead of tripping on a nil stub.

  Return-type mapping is deliberately conservative: numeric C types
  (including `:pointer`, a plain machine address here per ffi.clj's own ns
  docstring) become a number, `:string` a string, and everything else
  (`:void`) nil.

  `numeric-ret` below lists only the return types jonquil.gtk.ffi's defcfn
  forms actually declare, measured by scanning every `(ffi/defcfn ...)` form
  in src/ and dev/: :void (102), :pointer (46), :int (29), :uint (6),
  :size_t (4), :ulong (1). No defcfn in this project returns :float,
  :double, :char, :int64, :byte, :short or their unsigned variants, so this
  hook does not carry a-private-sibling's wider speculative set.

  Also omitted, on purpose: a-private-sibling's by-value aggregate-return handling
  (a `[:by-value [:struct ...]]` return type takes an implicit extra first
  argument at the call site). jonquil.gtk.ffi's own ns docstring states GTK
  structs like GtkTextIter are always passed by pointer, allocated by the
  caller and filled by GTK — \"no by-value passing anywhere, so no C
  shim\" — so there is nothing in this project for that branch to match."
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private numeric-ret
  #{:int :uint :ulong :size_t :size-t :pointer})

(defn- ret-node
  "A literal whose inferred type matches the declared C return type."
  [ret]
  (let [k (when ret (api/sexpr ret))]
    (cond
      (contains? numeric-ret k) (api/token-node 0)
      (= :string k)             (api/string-node "")
      :else                     (api/token-node nil))))

(defn defcfn
  [{:keys [node]}]
  (let [[_defcfn name-node _c-symbol arg-types ret] (:children node)]
    ;; Only rewrite the shape we understand; anything else falls through to
    ;; the default analysis rather than silently interning a wrong var.
    (if (and name-node arg-types (api/vector-node? arg-types))
      (let [params (map-indexed (fn [i _] (api/token-node (symbol (str "_arg" i))))
                                 (:children arg-types))
            expanded (api/list-node
                      [(api/token-node 'clojure.core/defn)
                       name-node
                       (api/vector-node (vec params))
                       (ret-node ret)])]
        {:node (with-meta expanded (meta node))})
      {:node node})))
