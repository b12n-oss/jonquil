(ns jonquil.gtk.window.files
  "Open and save for the window's tabs: the file dialogs (one callable per mode,
  results routed per dialog), open-path!, the atomic save-to! and its on-saved
  hook, the Open and Save result handling, and the window title. Internal to the
  window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window
  unchanged; the lock caller rule in its docstring applies here."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.save-path :as save-path]
            [jonquil.tabs :as tabs]))

;; --- title -------------------------------------------------------------

(defn file-name [path]
  (let [i (str/last-index-of path "/")]
    (if i (subs path (inc i)) path)))

(defn update-title!
  "\"<name> - Jonquil\", name the file's basename or Untitled, prefixed with
  \"* \" while dirty. GTK-thread only -- every caller already is one:
  button/key handlers, the dialog callbacks, the dirty-changed callback, and
  self-test rows (all invoked from g_timeout_add ticks)."
  [ed win path-atom]
  (g/gtk-window-set-title
   win
   (str (when (editor/dirty? ed) "* ")
        (if-let [p @path-atom] (file-name p) "Untitled")
        " - Jonquil")))

;; --- file dialogs ---------------------------------------------------------

;; One foreign callable per dialog MODE, reused for every call: a callable is
;; retained forever (jonquil.gtk.callbacks's contract), so building one per
;; Open/Save would leak a native trampoline per dialog.
;;
;; on-result is tracked per DIALOG OBJECT, keyed by the completion callback's
;; own source_object (GAsyncReadyCallback's first argument), never by a
;; single "current" atom: with two dialogs of one mode pending, a shared atom
;; would finish/unref the WRONG object and drop the first's result.
(defonce ^:private open-on-result-by-dialog (atom {}))
(defonce ^:private save-on-result-by-dialog (atom {}))

(defn- make-file-dialog-cb
  "The completion callback one dialog mode shares across every window.
  on-result is the window's own and contains its own throws (the callers
  of file-dialog! below report them on their console); anything else that
  throws here still unrefs the dialog and stops at callable's backstop."
  [finish on-result-by-dialog]
  (callable (fn [src res _data]
              (when-let [on-result (get @on-result-by-dialog src)]
                (swap! on-result-by-dialog dissoc src)
                (try (on-result (g/finish-file finish src res))
                     (finally (g/g-object-unref src))))
              nil)
            [:pointer :pointer :pointer] :void))

(defonce ^:private open-cb (make-file-dialog-cb g/gtk-file-dialog-open-finish open-on-result-by-dialog))
(defonce ^:private save-cb (make-file-dialog-cb g/gtk-file-dialog-save-finish save-on-result-by-dialog))
;; Open Folder..., same per-dialog routing.
(defonce ^:private folder-on-result-by-dialog (atom {}))
(defonce ^:private folder-cb (make-file-dialog-cb g/gtk-file-dialog-select-folder-finish folder-on-result-by-dialog))

(defn file-dialog!
  "mode is :open, :save or :folder (Open Folder...).
  on-result gets {:path s} or {:error msg :code n}. initial-folder, when
  given, is where the dialog starts."
  ([mode window on-result] (file-dialog! mode window on-result nil))
  ([mode window on-result initial-folder]
   (let [d (g/gtk-file-dialog-new)
         [on-result-by-dialog cb start]
         (case mode
           :open [open-on-result-by-dialog open-cb g/gtk-file-dialog-open]
           :save [save-on-result-by-dialog save-cb g/gtk-file-dialog-save]
           :folder [folder-on-result-by-dialog folder-cb g/gtk-file-dialog-select-folder])]
     (when initial-folder
       (let [gf (g/g-file-new-for-path initial-folder)]
         (g/gtk-file-dialog-set-initial-folder d gf)
         ;; The dialog keeps its own reference, so ours is released.
         (g/g-object-unref gf)))
     (swap! on-result-by-dialog assoc d on-result)
     (start d window ffi/null cb ffi/null))))

;; --- open / save: the no-dialog forms the rows (and the dialog callbacks
;; below) call -------------------------------------------------------------

(defn open-path!
  "Loads path into the editor: slurp, set-text!, remember the path. An
  error is appended as :error, never swallowed, and returned as {:error
  msg} too (nil on success) -- source.clj's own read-text/load! shape --
  so a caller that doesn't hold a console in view (do-source-open!) can
  still tell whether the open actually landed. Doesn't touch the title --
  callers hold the window, this doesn't, so update-title! is theirs to
  call afterward (do-open!, and the ui map's :open-path! in
  jonquil.gtk.window.ui).

  Jolt's slurp maps bad bytes to U+FFFD without throwing, so a
  non-UTF-8 file loads silently and Save would then write EF BF BD in
  place of whatever the original bytes were. When the loaded text
  contains U+FFFD, appends a :system warning naming the risk. A file
  holding a NUL byte is refused outright: GTK text is C
  strings, so the buffer would keep only the text before it and Save
  would write that shortened text back; the console names the line."
  [ed cv path-atom path]
  (try
    (let [text (slurp path)]
      (if-let [n (tabs/nul-line text)]
        (let [msg (str path " holds a NUL byte on line " n
                       ", which Jonquil can't edit, so it wasn't opened.")]
          (cview/append! cv :error (str "; open: " msg "\n"))
          {:error msg})
        (do
          (editor/set-text! ed text)
          (reset! path-atom path)
          (when (str/includes? text "�")
            (cview/append! cv :system
                           "This file isn't valid UTF-8; unreadable bytes show as � and saving will replace them.\n"))
          nil)))
    (catch :default e
      (cview/append! cv :error (str "; open: " (ex-message e) "\n"))
      {:error (ex-message e)})))

(defn- temp-path-for
  "A hidden temp file in the same directory as path: the write-then-
  rename below needs both on the same filesystem for the rename to be
  possible at all, atomic or not."
  [path]
  (let [i (str/last-index-of path "/")
        dir (if i (subs path 0 (inc i)) "")
        name (if i (subs path (inc i)) path)]
    (str dir "." name ".jonquil-tmp-" (System/nanoTime))))

(defn- copy-permissions!
  "Copies target's POSIX permissions onto tmp when target already exists:
  a freshly spit temp file gets the process umask's
  default mode (probed: rw-r--r--, i.e. 644), so saving over an
  executable (755) file silently dropped its +x. Files/setPosixFile-
  Permissions exists and works on this Jolt; java.io.File's
  setExecutable/setReadable/setWritable do NOT -- probed, and both the
  1-arg and 2-arg forms raise 'No matching method ... found' -- so there
  is no fallback to reach for here."
  [target tmp]
  (when (.exists (java.io.File. ^String target))
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (java.io.File. ^String tmp))
     (java.nio.file.Files/getPosixFilePermissions (.toPath (java.io.File. ^String target))))))

(defn- name-user-path
  "Rewrites any occurrence of tmp (the hidden temp filename) or target
  (path resolved through symlinks, which can differ from what the user
  typed) inside an exception message back to path -- an exact literal
  replacement, not a guessed pattern, since both strings are ones this
  namespace itself built or resolved a moment earlier. Used so a save
  error never names a file the user never typed."
  [msg tmp target path]
  (-> msg (str/replace tmp path) (str/replace target path)))

(defonce on-saved
  ;; (fn [path ed]) run after a save succeeds; the plugins part's
  ;; :plugins-start! sets it to reload a saved plugin file and to fire
  ;; :after-save hooks
  (atom nil))

(defn save-to!
  "Writes the editor text to path without ever truncating it in place,
  and without breaking a symlink or dropping the target's
  permissions: resolves path to its real underlying file
  (save-path/resolve-target, which follows links by hand because
  .getCanonicalPath does not follow a dangling one on Jolt), creates the
  temp file in THAT file's directory with mode rw------- when the target
  exists (save-path/create-temp!, so the new content is never in a wider
  file), spits into it, copies the target's POSIX permissions onto it
  (copy-permissions!), then renames the temp file over the RESOLVED
  target with java.nio.file.Files/move + ATOMIC_MOVE (probed against this
  Jolt) so a failure mid-write can't leave a
  half-written target -- and so a symlink target gets updated in place
  rather than replaced by the rename. mark-saved! and remembering path
  (the user's own path, not the resolved target) only happen once the
  rename has actually succeeded. An error is appended as :error, naming
  path rather than the hidden temp file or a resolved target that
  differs from it, and never swallowed -- losing the mark that a save
  failed would risk the user believing unsaved work is safe when it
  isn't -- and the temp file is cleaned up on any failure (a failure to
  resolve path, such as a symlink loop, is reported the same way, before
  any temp file exists); the rename
  never runs at all when the write itself fails, so the target is never
  touched. Doesn't touch the title; see open-path!'s docstring."
  [ed cv path-atom path]
  (let [target (try (save-path/resolve-target path)
                    (catch :default e
                      (cview/append! cv :error (str "; save: " (ex-message e) "\n"))
                      nil))]
    (when target
      (let [tmp (temp-path-for target)]
        (try
          (save-path/create-temp! tmp target)
          (spit tmp (editor/editor-text ed))
          (copy-permissions! target tmp)
          (java.nio.file.Files/move
           (.toPath (java.io.File. ^String tmp))
           (.toPath (java.io.File. ^String target))
           (into-array java.nio.file.CopyOption
                       [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                        java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
          (editor/mark-saved! ed)
          (reset! path-atom path)
          (when-let [f @on-saved]
            (try (f path ed)
                 (catch :default e
                   (cview/append! cv :error (str "; reloading the saved plugin failed: " (ex-message e) "\n")))))
          (catch :default e
            (let [f (java.io.File. ^String tmp)]
              (when (.exists f) (.delete f)))
            (cview/append! cv :error
                           (str "; save: " (name-user-path (ex-message e) tmp target path) "\n"))))))))

(defn guarded-open!
  "The dirty-discard gate for loading a file into the active tab: when ed
  is dirty and the previous attempt hasn't been warned about it yet,
  refuses and appends a :system warning instead of calling proceed!. The
  next attempt while STILL dirty (discard-warned? already true) calls
  proceed! and clears the flag; a dirty->clean transition elsewhere (a
  successful Save) also clears it, via wire-tab!'s on-dirty-changed!
  registration, so a later, unrelated dirty streak earns its own warning.
  Open and Open in window open a tab instead of replacing a buffer, so
  the ui's :open-path! is this gate's only caller."
  [ed cv discard-warned? proceed!]
  (if (and (editor/dirty? ed) (not @discard-warned?))
    (do (reset! discard-warned? true)
        (cview/append! cv :system "Unsaved changes. Save first, or Open again to discard them.\n"))
    (do (reset! discard-warned? false)
        (proceed!))))

(defn dialog-dismissed?
  "True only for GTK's own GTK_DIALOG_ERROR_DISMISSED, checking BOTH
  the numeric code AND the GError domain quark: code 2 is also
  G_IO_ERROR_EXISTS in a different domain, and code alone can't tell them
  apart -- see ffi.clj's gtk-dialog-error-quark citation."
  [{:keys [code domain]}]
  (and (= code g/GTK-DIALOG-ERROR-DISMISSED)
       (= domain (g/gtk-dialog-error-quark))))

(defn- handle-open-result!
  "The file-dialog! :open callback's decision, factored out so a self-test
  row can drive it with a synthetic result map shaped like g/finish-file's
  (the dismissed-dialog case has no other way to reach it without a live
  dialog)."
  [open! cv {:keys [path error] :as result}]
  (cond
    path (open! path)
    ;; A cancelled/dismissed dialog is not an error.
    (dialog-dismissed? result) nil
    :else (cview/append! cv :error (str "; open: " error "\n"))))

(defn contained-open-result!
  "handle-open-result!, with a throw reported on the console as \"; open:
  ...\" instead of reaching the shared dialog callback. The dialog's
  on-result, and the self-test's :simulate-open-result!. ctx is {:ed :cv
  :open!}: open! (fn [path]) is how a chosen file opens -- the real Open
  goes through open-file!; the ui's simulated result loads into the
  active tab, as :open-path! does."
  [{:keys [cv open!]} result]
  (try (handle-open-result! open! cv result)
       (catch :default e
         (cview/append! cv :error (str "; open: " (ex-message e) "\n")))))

(defn do-open!
  "Open: the file dialog, then open! with the chosen path. Open never
  replaces a buffer with unsaved changes -- it opens a tab or fills an
  untouched Untitled one -- so there's no discard gate."
  [cv win open!]
  (file-dialog! :open win (fn [result] (contained-open-result! {:cv cv :open! open!} result))))

(defn- handle-save-result!
  "handle-open-result!'s counterpart for file-dialog! :save."
  [ed cv win path-atom {:keys [path error] :as result}]
  (cond
    path (do (save-to! ed cv path-atom path)
             (update-title! ed win path-atom))
    (dialog-dismissed? result) nil
    :else (cview/append! cv :error (str "; save: " error "\n"))))

(defn contained-save-result!
  "contained-open-result!'s counterpart for :save."
  [{:keys [ed cv win path-atom]} result]
  (try (handle-save-result! ed cv win path-atom result)
       (catch :default e
         (cview/append! cv :error (str "; save: " (ex-message e) "\n")))))

(defn do-save-as! [ed cv win path-atom]
  (file-dialog! :save win (fn [result] (contained-save-result! {:ed ed :cv cv :win win :path-atom path-atom} result))))

(defn do-save! [ed cv win path-atom]
  (if-let [path @path-atom]
    (do (save-to! ed cv path-atom path)
        (update-title! ed win path-atom))
    (do-save-as! ed cv win path-atom)))
