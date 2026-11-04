# Lisp Machine ideas, and Emacs

Several of Jonquil's features come from the Lisp Machines, the MIT CADR and
Symbolics Genera. This page says what those machines were, how Emacs relates
to them, and where Jonquil sits next to both.

## What a Lisp Machine was

A Lisp Machine was a whole computer where everything was Lisp: the operating
system, the file system, the network, the window system and your own
programs, all live objects in one running image. Any of it could be
inspected, changed or debugged while it ran, and the environment explained
itself. You could ask a key what it does, click a result to open the object,
and when an error happened the debugger opened with the stack still there.
The [Lisp Machine museum](https://github.com/htayj/lisp-machine-container-museum)
documents both systems in depth.

## How Emacs relates

The Lisp Machines had their own Emacs-style editor, Zwei (Zmacs), written in
Lisp and running inside that live system. GNU Emacs came out of the same MIT
culture and keeps the central idea at a smaller size: its core is a Lisp
interpreter, so the editor is a running Lisp program you can change from
inside itself. It runs as an application on an ordinary operating system,
though, so your files, processes and network aren't Lisp objects. You can
think of Emacs as the Lisp Machine's live, self-describing environment
without the rest of the computer.

## Where Jonquil sits

Jonquil heads the same way, and a lot of what it takes from the Lisp Machines
Emacs took first:

| Jonquil | Emacs |
|---|---|
| Describe Key, Key Bindings, Recent Keys | `C-h k`, `C-h b`, `C-h l` |
| The command palette (Cmd/Ctrl+Shift+P) | `M-x` |
| The locations list and F8 / Shift+F8 | `next-error`, xref |
| Find Usages in Project, by how each place uses the name | `xref-find-references` (`M-?`); Genera's who-calls, by name rather than from a callers database |
| Switch to a recent tab, Go back to the tab used before | `switch-to-buffer`, most recent first |
| Switch to a recent panel, Cmd/Ctrl+Alt+1 to 6 for each panel | none built in: `C-x o` cycles windows; Genera's Select key, where Select L brings up the Lisp Listener, is where this comes from |
| Time the form at the caret | `benchmark-run` |
| Live console results: click to inspect, right-click verbs | none built in: CIDER inspects the last result; Genera's presentations are where this comes from |
| Trace the function at the caret, the Trace panel | `trace-function` and the `*trace-output*` buffer; CIDER's `cider-toggle-trace-var` |
| The changed-form marks, Run the forms changed since they last ran | none built in: `diff-hl` marks lines changed since the last commit, not since the last eval |
| Show the last error's frames and locals | the `*Backtrace*` buffer (`debug-on-error`) |
| Break on error, Continue | `debug-on-error` while the error is live: `e` evaluates in a frame, `c` continues |
| Retry, Edit and retry, Define and retry | none close: the Elisp debugger resumes a live stack, which an unwound one can't |
| Save World, Restore World: your session's data, one world per project | none close: `desktop-save` keeps buffers, not values; Genera's Save World wrote the whole machine, this writes data vars only |
| Plugins written in Clojure, in the settings folder | Emacs Lisp packages |

Three things set it apart:

- **Your code runs in the editor's own runtime.** Emacs is extended in
  Emacs Lisp, a dialect separate from the language you're working in.
  Jonquil is written in Clojure on Jolt, and your Jolt code runs on that
  same runtime: inline in Jonquil's process for a loose file, or in a Jolt
  child process for a project (see [Dialects and runtimes](dialects/index.md)).
  The inspector, its history and Time the form look at your program's real
  values, not the editor's.
- **The debugger reads that runtime.** Jolt's `jolt.debug` keeps the frames
  and locals of a throw, and the Frames panel (Show the last error's frames
  and locals, see [Help and the inspector](help-and-inspector.md)) lists them
  for your own code: a frame opens at its line, a local opens in the
  inspector, and a form typed in the panel evaluates with that frame's
  locals bound. With Break on error the panel opens before the stack
  unwinds, and a form runs on the thread that threw, inside its bindings. It
  needs a Jolt release with `jolt.debug`. Emacs debugs Emacs Lisp
  well, but debugging Clojure there goes through CIDER and nREPL, a separate
  layer.
- **It stays a Clojure IDE.** Emacs turns everything into a buffer: mail, a
  shell, a file manager. Jonquil takes Lisp Machine ideas only where they
  help you write and understand Clojure code, and leaves the rest.

The `ROADMAP.md` "Lisp Machine (LM)" section lists the ideas taken so far and
the ones still to come.
