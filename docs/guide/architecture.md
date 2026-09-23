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

Nothing enforces this automatically, which is why it is a rule. Jolt's build scanner follows `require` forms statically, so a `jonquil.gtk` namespace required from anywhere else would silently compile GTK into every binary built from that namespace, headless tools included. Phase 2 adds the GTK libraries to `:jolt/native` in `deps.edn`.

## The eval engine

Each window has an eval context. Run and REPL both use the same `eval-async` function with an `:ns-name` option. The engine does not distinguish between them. Results carry a `:printed` field: the value printed with `pr-str` under `*print-length*` 1000, inside the interruptible region, so realizing a lazy value can be stopped and its side effects reach the console. The GUI shows `:printed` and never re-prints `:value`.

Stop uses `jolt.host/run-interruptible` with a token. Each Run creates a token and evaluates inside the interruptible region on a worker thread. Stop calls `interrupt!` on the token, which aborts busy loops at back-edges. The engine tracks whether the worker has actually exited before returning `:idle`.

Callbacks (like `on-output` for print statements) run on the worker thread, and `on-status-change` runs while the context's lock is held for its `:running` and `:idle` transitions. The caller rule is simple: a callback enqueues its work and returns. It must never wait on another thread that may itself call `eval-async` or `stop-eval!` on the same context, because that deadlocks. The front-end drains output from a queue on the main thread.

Stop really stops. If a user program is in an infinite loop, Stop interrupts it. If a program is blocked on `read-line` waiting for input, Stop sends an EOF sentinel to the input queue and the `read-line` returns. The engine guarantees that once Stop is pressed, the result will be `:interrupted` no matter what the program then does.
