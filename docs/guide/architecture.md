# Jonquil Architecture

## Design

Jonquil is a port of DrClojure's idea to Jolt: a Clojure IDE with a Definitions editor, an Interactions console, Run and Stop that actually stops. The design splits into layers.

```
            +---------------------------------------------+
            |              jonquil.gtk.*  (front-end)      |
            |  window, two TextViews, toolbar, status bar, |
            |  key routing, tag painting, file dialogs      |
            +------------------+--------------------------+
                               | snapshots in, edits out
            +------------------v--------------------------+
            |         jonquil.session  (per-window state)  |
            |  file, dirty flag, eval context, font size   |
            +--+-----------+-------------+-------------+---+
               |           |             |             |
      +--------v--+  +-----v-----+  +----v-----+  +----v-------+
      | jonquil.  |  | jonquil.  |  | jonquil. |  | jonquil.   |
      | syntax +  |  | edit      |  | eval     |  | docs       |
      | paint     |  | pure edit |  | run, in, |  | (docs and  |
      | tokenize, |  | ops       |  | out,stop |  | completion |
      | format    |  |           |  | history  |  | in eval ns)|
      +-----------+  +-----------+  +----------+  +------------+
            pure         pure        threads       pure + runtime
```

Phase 3 and the Themes phase added three more pure namespaces alongside
these:

- `jonquil.commands`: the run-selection text, the pre-Run bracket check,
  next/prev match, replace-current and replace-all, the format
  operations, and the zoom sizes.
- `jonquil.theme`: the four palettes as data, plus `theme`, `next-theme`
  and `code-css`.
- `jonquil.settings`: `settings-path`, `load-settings` (never throws) and
  `save-settings!` (atomic, never throws).

P4 added a fourth: `jonquil.help`, the decisions behind completion, quick
doc, jump to definition and rename, covered in its own section below.

The front-end and the core use plain data to communicate. When the user edits, the GTK TextBuffer sends a snapshot to the core:

```clojure
{:text "..." :caret 42 :sel-start 40 :sel-end 45}
```

An edit operation returns data for the front-end to apply:

```clojure
{:edits [[start end "replacement"] ...]
 :caret 44
 :sel   [44 44]}
```

The front-end applies each edit in reverse order within one `gtk_text_buffer_begin/end_user_action` call, so undo sees one step.

## GTK is not everywhere

GTK4 is required only from `jonquil.main`. All of the pure core (tokenization, parsing, syntax highlighting, edit operations, the eval engine) runs without GTK or any GUI dependencies. This design lets the core be tested independently and potentially reused by a second front-end later.

Nothing enforces this automatically, which is why it is a rule. Jolt's build scanner follows `require` forms statically, so a `jonquil.gtk` namespace required from anywhere else would silently compile GTK into every binary built from that namespace, headless tools included. Phase 2 added the GTK libraries to `:jolt/native` in `deps.edn`.

## The GTK layer

`jonquil.gtk.*` is five namespaces, each with one job:

- `jonquil.gtk.ffi`: the raw GTK4/GLib/GObject/pango bindings, plus the low-level GtkTextIter/mark helpers everything else builds on.
- `jonquil.gtk.callbacks`: the `callable` macro that turns a Clojure function into a retained, `:collect-safe` foreign-callable (GTK invokes callbacks from inside its own blocking main loop, so a collected one would crash it), and `on-gui!`, the one function safe to call from any thread to hand a thunk to the GTK main thread.
- `jonquil.gtk.editor`: the Definitions editor, a GtkTextView wired to `jonquil.paint` for live syntax highlighting and `jonquil.edit` for smart Enter. Its `handle-key!` dispatches the rest of the editor's key chords, sending edit ops through `apply-op!` so each one lands as a single user action, and every handler yields during IME preedit.
- `jonquil.gtk.find-bar`: the find and replace bar, with its own `handle-key!` and match highlighting through a `search-match` tag.
- `jonquil.gtk.console-view`: the Interactions console, one GtkTextView whose buffer holds read-only output history, an editable prompt and input region, and the key handling for Return, Up/Down and Ctrl/Cmd+D.
- `jonquil.gtk.window`: the toolbar, the paned layout, the window-level key chords (F5, Esc, Cmd/Ctrl+R/O/S), the file dialogs, and the wiring between `jonquil.session` and the two views.

P4 added three more, all built on a shared popover base:

- `jonquil.gtk.popover`: a caret-anchored `GtkPopover`, shared by completion, quick doc and rename.
- `jonquil.gtk.help-popover`: the quick-doc popover and the rename popover, both built on `jonquil.gtk.popover`.
- `jonquil.gtk.completion`: the completion popover, one instance per view (the editor and the console's input line), also built on `jonquil.gtk.popover`.

### `on-gui!` and the worker-to-GTK-thread path

The eval engine's callbacks (`on-output`, `on-wait-input`, `on-status-change`, `on-complete`) run on the eval worker thread, never the GTK thread. GTK itself may only be touched from its own main thread, so every one of those callbacks follows the same rule: enqueue and return, never call GTK directly, never block. `jonquil.gtk.console-view/append!` queues a `[kind text]` chunk and schedules a drain; `jonquil.gtk.callbacks/on-gui!` queues an arbitrary thunk. Both are safe from any thread for exactly that reason, and both coalesce: several worker-thread calls landing between GTK ticks turn into one drain, so a flood of output doesn't starve the main loop.

### The console buffer layout

The Interactions console is one GtkTextBuffer laid out as

```
[ output history ......... ][ "> " ][ input ... ]
^0                          ^out-end ^input-start       ^end
```

Output history and the prompt carry a read-only tag, which is what stops typing, pasting or Backspace from reaching into old output; GtkTextView's own edit path already refuses edits under that tag. `out-end` is a mark with right gravity: `append!` always inserts at it, so new output lands before the prompt and the mark tracks past it. `input-start` is a mark with left gravity: text typed at it stays after it, and output inserted earlier in the buffer pushes it forward automatically. Committing an entry (Return, when it evaluates or sends stdin) moves both marks explicitly to open a fresh `"> "` prompt after the old input, rather than relying on gravity for that step.

The console reads its busy/waiting state live from the engine (`:evaluating?`/`:waiting?`, functions supplied at construction) rather than mirroring it into a console-owned atom. A mirror set right after starting an eval could lose a race against a fast eval's own completion clearing it first, stranding the console in a busy state with nothing left to un-strand it.

### `jonquil.session` and the O1 reset rule

`jonquil.session` holds one window's evaluation state: the eval context, the current mode (`:reset` or `:accumulate`), the prompt namespace, and the set of namespaces created since the last reset. Run and the REPL both call through the same `evaluate!` path into `jonquil.eval/eval-async`, so the engine itself never distinguishes them.

The O1 spec decision is that Run resets by default: in `:reset` mode, `run-definitions!` first removes every namespace that an earlier Run or REPL entry created, except loaded libraries and `user`, then recreates a fresh `user` and evaluates into it. Unchecking the toolbar's Reset on Run checkbox flips the session to `:accumulate`, and Run stops clearing first, building on whatever earlier evaluations already defined, the same way the REPL prompt always does.

### Self-test harnesses

Two `bb` tasks drive the real GTK front end headlessly, without a human at the keyboard:

- `bb gtk:selftest` opens two real GTK windows in turn and runs the P2 front-end self-test rows against them, exercising the same button/key handlers a person would use rather than calling internal functions directly.
- `bb docs:shots` self-captures `docs/images/*.png` and one GIF for this guide and the user guide: one fresh `jolt -M:shots <scene>` process per scene, each driving `jonquil.main/start!` through exactly one demo, screenshotting it with `cgevent`, then exiting. A fresh process per scene keeps each screenshot to its own scene's console history.

Both poll a condition against a deadline rather than sleeping, and both hand any blocking subprocess call (screenshots, GIF recording) to a `future` so the GTK thread itself never blocks. See [Development](development.md) for how to run these tasks, the split layout of `dev/gtk_selftest_window/`, the binary smoke, and the traps that catch a first run.

### Themes

Applying a theme doesn't rebuild anything: each component's `apply-theme!` retints its existing tags in place, so text, undo history and highlights all survive the switch. The window then reloads its one CSS provider, the same one zoom uses, from `theme/code-css`, and sets `gtk-interface-color-scheme` on GtkSettings to `DARK` or `LIGHT` so GTK's own chrome follows along.

Isolation matters here too: self-tests and `bb docs:shots` pass a temp `:settings-path`, and `bb smoke:binary` sets `XDG_CONFIG_HOME` to a temp directory, so none of the harnesses ever touches the user's real settings file.

### Help: completion, quick doc, jump and rename (P4)

`jonquil.help` holds every pure decision behind the four P4 features, the same split `jonquil.commands` and `jonquil.edit` already draw between deciding and doing. It never touches GTK or the running program; it works on the editor's own snapshot shape (`{:text :caret :sel-start :sel-end}`) and on plain data the GTK layer passes in.

Completion's state moves through a small set of functions: `prefix-at` reads the word before the caret (or nil, when there's a selection or nothing to complete), `open-state` turns a prefix plus a candidate list into the popup's state map, `move` steps the selection with clamping, `commit-op` is the edit that replaces the prefix with the chosen candidate, and `still-valid?`/`refine` decide whether an open popup survives the next keystroke or needs to close. `target` is shared by quick doc, jump and rename: the selection when it's exactly one symbol, otherwise the symbol at or around the caret.

Jump to definition is the one place `jonquil.help` overrides upstream's own logic rather than reusing it outright. `jonquil.syntax/find-definition` (lifted unchanged from DrClojure) checks top-level `def*` forms first and only then falls back to a scope-less "last earlier token with this name", which gets a shadowed local wrong: a function parameter or a `let` binding that reuses a top-level name should win, and an unbound name should report nothing rather than landing on an earlier use of it. `jonquil.help/jump-target` fixes this with a real scope search first: it walks the parenthesized forms enclosing the target, innermost first, collecting the names each one binds (`let`/`loop`/`binding`/`when-let`/`when-some`/`when-first`/`if-let`/`if-some`'s own bindings vector, `for`/`doseq`'s bindings vector, `fn`/`defn`/`defn-`/`defmacro` params for every arity, and `letfn`'s names), unpacking vector and map destructuring recursively along the way. Only when that search comes up empty does it call `find-definition` with no position at all, which runs just its top-level half; an external var's documentation is the last fallback. `rename-problem` mirrors `jonquil.edit/rename`'s own nil cases (no symbol at the caret, a blank or invalid new name, the same name, nothing to rename) as a sentence for the rename popover to show instead of applying anything.

`jonquil.docs` resolves lookups in the namespace the user's program actually runs in, not the IDE's own. When that namespace no longer exists, because Reset on Run just removed it, `resolve-ns` falls back to `user` rather than letting `the-ns` throw. Both `symbol-doc` and `candidates` wrap their real lookup in `try`/`catch` and return nil (or `[]` for candidates) on any exception, so a bad lookup never escapes into a key handler. `symbol-doc` also memoises on `[namespace symbol text]`: looking up a name defined only in the buffer costs around 144 ms on a 2,000-line file, too slow for a preview that has to keep up with every arrow key. The cache is cleared after every completed evaluation, Run or REPL alike, from the same `on-complete` callback in `jonquil.gtk.window` that already reports the result; without that, a lookup made before a name existed would go on returning its stale answer forever after, even once the same evaluation had defined it.

The three popover namespaces share `jonquil.gtk.popover` as their base. Its most important fact is where a popover's `GtkWidget` parent actually is: never the `GtkTextView` it's anchored to, always that view's own container (its `GtkScrolledWindow`, in both the editor and the console). Parenting a popover directly to the text view, which is what the P4 spike probes did, hangs a real window close: `GtkTextView`'s own dispose walks its structural children to detach them, doesn't recognise a popover as one of its own, and spins forever logging that the popover isn't a child of the text view, pinning the main thread at 100% CPU. The probes themselves never hit this because every probe process exits without ever destroying its window. A `GtkScrolledWindow` has no such override and tears down cleanly. `jonquil.gtk.window` keeps a registry of every P4 popover a window creates (`register-popover!`), and `close-popovers!` walks it to hide whichever one is open, whether that's Escape's own job or one popover making room for another. At window close, `close-request-fn`'s real-quit branch calls `unparent-registered-popovers!`, which hides an open popover before unparenting it (unparenting first would leave its `:open?` atom reading true against a widget that no longer exists, and a later focus-lost listener would then call `hide!` on it and hit a GTK-CRITICAL) and skips an entry that's already unparented, so the session's popovers detach cleanly instead of leaving `GtkScrolledWindow` still holding a child when it finalizes.

`jonquil.gtk.help-popover` builds the quick-doc popover (autohide off, so it never steals keys from the view, per `jonquil.gtk.popover`'s own autohide note) and the rename popover (autohide on, since its entry needs to receive typing; GTK itself closes it on Escape or a click outside, and a `"closed"` signal handler keeps the popover's `:open?` bit in sync when that happens with no call into this namespace at all). `jonquil.gtk.completion` is one popover per view, a candidate list over a doc preview, built once per view and reused for the rest of the session; `open!`, `refine!` and `commit!` all read `(popover/open? c)` rather than trusting the state atom directly, because a close driven from outside this namespace (the window's registry-wide `close-popovers!`) only ever calls the generic `popover/hide!` and has no idea this namespace's own state atom exists.

The editor's and the console's context menus (Shift+F10, or a right click) come from `gtk_text_view_set_extra_menu`, which appends to GTK's own Cut/Copy/Paste menu rather than replacing it. `install-context-menu!` builds a `GMenu` alongside a matching `GSimpleActionGroup`, inserted on the view under the `"p4"` action-group prefix, with one action per item wired to call the exact same function its keyboard chord calls. Every action's own `"activate"` callback runs inside `try`/`catch`: an uncaught throw from a `GSimpleAction` callback kills the process outright, so this is the one place every menu item, current and future, is guarded at once.

The key-dispatch wrappers carry the same guarantee down at the keyboard level. `jonquil.gtk.editor/dispatch-key!` wraps `handle-key!` in `try`/`catch`, and `jonquil.gtk.window`'s window-level key handler wraps its own `cond` the same way: either throw is reported through the status bar rather than escaping into GTK's signal emission, and the key still counts as consumed (returns 1) instead of falling through into a second, unrelated action or killing the process.

## The eval engine

Each window has an eval context. Run and REPL both use the same `eval-async` function with an `:ns-name` option. The engine does not distinguish between them. Results carry a `:printed` field: the value printed with `pr-str` under `*print-length*` 1000, inside the interruptible region, so realizing a lazy value can be stopped and its side effects reach the console. The GUI shows `:printed` and never re-prints `:value`.

Stop uses `jolt.host/run-interruptible` with a token. Each Run creates a token and evaluates inside the interruptible region on a worker thread. Stop calls `interrupt!` on the token, which aborts busy loops at back-edges. The engine tracks whether the worker has actually exited before returning `:idle`.

Callbacks (like `on-output` for print statements) run on the worker thread, and `on-status-change` runs while the context's lock is held for its `:running` and `:idle` transitions. The caller rule is simple: a callback enqueues its work and returns. It must never wait on another thread that may itself call `eval-async` or `stop-eval!` on the same context, because that deadlocks. The front-end drains output from a queue on the main thread.

Stop really stops. If a user program is in an infinite loop, Stop interrupts it. If a program is blocked on `read-line` waiting for input, Stop sends an EOF sentinel to the input queue and the `read-line` returns. The engine guarantees that once Stop is pressed, the result will be `:interrupted` no matter what the program then does.
