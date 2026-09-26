(ns gtk-selftest-window.files
  "Open and Save: the dirty-open guard, invalid UTF-8, atomic/symlink/mode
  save behaviour, and dialog-dismissed handling."
  (:require [clojure.string :as str]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(defn row-open-refuses-dirty
  "I4: Open silently discarded unsaved edits. Dirties the editor without
  going through set-text! (which marks clean), then drives (:open-path!
  ui) -- the SAME guarded-open! gate do-open! uses -- twice: the first
  attempt must refuse and leave the text untouched, the second (still
  dirty) must proceed. The refusal text arrives through cview/append!,
  an on-gui!-scheduled drain (same I1 lesson: poll for it, don't read
  the console immediately after the synchronous call that queued it)."
  [ui next]
  (let [ed (:editor ui)
        mark (support/out-end-offset ui)
        tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-dirty-" (System/currentTimeMillis) ".clj")]
    (spit tmp "(+ 9 9)")
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (let [dirty-before (editor/dirty? ed)]
      ((:open-path! ui) tmp)
      (support/poll-until 1000 20
                  (fn [] (str/includes? (support/since ui mark)
                                        "Unsaved changes. Save first, or Open again to discard them."))
                  (fn [refused?]
                    (let [text-after-first (editor/editor-text ed)]
                      ((:open-path! ui) tmp)
                      (let [text-after-second (editor/editor-text ed)]
                        (support/check :open-refuses-dirty
                               (and dirty-before refused?
                                    (= "(+ 1 1)z" text-after-first)
                                    (= "(+ 9 9)" text-after-second))
                               {:dirty-before dirty-before :refused refused?
                                :text-after-first text-after-first
                                :text-after-second text-after-second
                                :console (support/since ui mark)})
                        (.delete (java.io.File. ^String tmp))
                        (next))))))))

(defn row-open-invalid-utf8
  "I6: a file with a stray non-UTF-8 byte (0xE9, the brief's own example)
  loads as U+FFFD without throwing; Open must append a :system warning
  rather than silently accept the loss. Poll for the warning -- same
  on-gui! drain lesson as row-open-refuses-dirty."
  [ui next]
  (let [ed (:editor ui)
        mark (support/out-end-offset ui)
        tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-badutf8-" (System/currentTimeMillis) ".clj")]
    (with-open [os (java.io.FileOutputStream. ^String tmp)]
      (.write os (byte-array [(byte 0x28) (unchecked-byte 0xE9) (byte 0x29)])))
    ((:open-path! ui) tmp)
    (support/poll-until 1000 20
                (fn [] (str/includes? (support/since ui mark) "isn't valid UTF-8"))
                (fn [warned?]
                  (let [text (editor/editor-text ed)]
                    (support/check :open-invalid-utf8
                           (and (str/includes? text "�") warned?)
                           {:text text :warned warned? :console (support/since ui mark)})
                    (.delete (java.io.File. ^String tmp))
                    (next))))))

(defn row-save-atomic
  "M12: save-to! writes a temp file beside the target and renames over it,
  never truncating the target in place. Saving into a missing directory
  must still show :error (and never create the bad path); saving to a
  real path round-trips. Poll for the :error line -- same on-gui! drain
  lesson; the file writes themselves are synchronous, no poll needed for
  those."
  [ui next]
  (let [ed (:editor ui)
        mark (support/out-end-offset ui)
        good-tmp (str (System/getProperty "java.io.tmpdir")
                      "/jonquil-selftest-atomic-" (System/currentTimeMillis) ".clj")
        bad-path (str (System/getProperty "java.io.tmpdir")
                      "/jonquil-selftest-missing-dir-" (System/currentTimeMillis) "/nested/file.clj")]
    (editor/set-text! ed "(+ 3 3)")
    ((:save-to! ui) bad-path)
    (support/poll-until 1000 20
                (fn [] (str/includes? (support/since ui mark) "; save:"))
                (fn [error-shown?]
                  (let [bad-path-exists? (.exists (java.io.File. ^String bad-path))]
                    (editor/set-text! ed "(+ 4 4)")
                    ((:save-to! ui) good-tmp)
                    (let [round-tripped? (= "(+ 4 4)" (slurp good-tmp))]
                      (support/check :save-atomic
                             (and error-shown? (not bad-path-exists?) round-tripped?)
                             {:error-shown error-shown? :bad-path-exists bad-path-exists?
                              :round-tripped round-tripped? :console (support/since ui mark)})
                      (.delete (java.io.File. ^String good-tmp))
                      (next)))))))

(defn row-save-follows-symlink
  "M12 follow-up (a): the atomic rename used to target the SAVE PATH
  directly, so if that path is a symlink, java.nio.file.Files/move
  replaces the link itself with a regular file -- the link stops being a
  link, and whatever it used to point at is never touched (probed:
  reproduced exactly this against the old, unfixed code before writing
  this row -- see the fix-wave report's Follow-up section). save-to! now
  resolves the path first (.getCanonicalPath) and renames onto THAT, so
  saving through a symlink updates the real file and the link stays a
  link pointing at the same place."
  [ui next]
  (let [ed (:editor ui)
        dir (System/getProperty "java.io.tmpdir")
        real (str dir "/jonquil-selftest-symlink-real-" (System/currentTimeMillis) ".clj")
        link (str dir "/jonquil-selftest-symlink-link-" (System/currentTimeMillis) ".clj")]
    (spit real "(+ 0 0)")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (java.io.File. ^String link))
     (.toPath (java.io.File. ^String real)))
    (editor/set-text! ed "(+ 9 9)")
    ((:save-to! ui) link)
    (let [still-symlink? (java.nio.file.Files/isSymbolicLink (.toPath (java.io.File. ^String link)))
          real-content (slurp real)
          via-link-content (slurp link)]
      (support/check :save-follows-symlink
             (and still-symlink? (= "(+ 9 9)" real-content) (= "(+ 9 9)" via-link-content))
             {:still-symlink still-symlink? :real-content real-content :via-link-content via-link-content})
      (.delete (java.io.File. ^String link))
      (.delete (java.io.File. ^String real))
      (next))))

(defn row-save-executable-keeps-mode
  "M12 follow-up (b): a freshly spit temp file gets the process umask's
  default mode (probed: 644), so the old save-to! silently turned a 755
  file into 644 on every save. copy-permissions! now copies the target's
  real POSIX permissions onto the temp file before the rename."
  [ui next]
  (let [ed (:editor ui)
        path (str (System/getProperty "java.io.tmpdir")
                  "/jonquil-selftest-exec-" (System/currentTimeMillis) ".clj")]
    (spit path "(+ 0 0)")
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (java.io.File. ^String path))
     (java.nio.file.attribute.PosixFilePermissions/fromString "rwxr-xr-x"))
    (editor/set-text! ed "(+ 1 1)")
    ((:save-to! ui) path)
    (let [mode (java.nio.file.attribute.PosixFilePermissions/toString
                (java.nio.file.Files/getPosixFilePermissions (.toPath (java.io.File. ^String path))))
          content (slurp path)]
      (support/check :save-executable-keeps-mode
             (and (= "rwxr-xr-x" mode) (= "(+ 1 1)" content))
             {:mode mode :content content})
      (.delete (java.io.File. ^String path))
      (next))))

(defn row-save-failure-leaves-original-intact
  "M12 follow-up: a target directory made read-only AFTER the file
  already exists there -- spit can't create the temp file inside it, so
  the rename never runs at all. Must leave the original content intact,
  leave no stray .jonquil-tmp-* file in the directory, and show :error
  naming path (dir/file.clj, what the user typed) rather than the hidden
  temp filename or path's resolved-through-symlinks form (here the
  process tmpdir itself, /var/folders/... on macOS, which name-user-path
  must also rewrite since it resolves to /private/var/folders/...)."
  [ui next]
  (let [ed (:editor ui)
        dir (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-readonly-" (System/currentTimeMillis))
        path (str dir "/file.clj")
        mark (support/out-end-offset ui)]
    (.mkdir (java.io.File. ^String dir))
    (spit path "(+ 0 0)")
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (java.io.File. ^String dir))
     (java.nio.file.attribute.PosixFilePermissions/fromString "r-xr-xr-x"))
    (editor/set-text! ed "(+ 1 1)")
    ((:save-to! ui) path)
    (support/poll-until 1000 20
                (fn [] (str/includes? (support/since ui mark) "; save:"))
                (fn [error-shown?]
                  (let [error-text (support/since ui mark)
                        content-intact? (= "(+ 0 0)" (slurp path))
                        stray-tmp? (boolean (some (fn [n] (str/includes? n ".jonquil-tmp-"))
                                                  (seq (.list (java.io.File. ^String dir)))))
                        names-user-path? (str/includes? error-text path)
                        names-tmp? (str/includes? error-text ".jonquil-tmp-")]
                    ;; restore write access before deleting, or .delete may
                    ;; itself fail against the still-read-only directory.
                    (java.nio.file.Files/setPosixFilePermissions
                     (.toPath (java.io.File. ^String dir))
                     (java.nio.file.attribute.PosixFilePermissions/fromString "rwxr-xr-x"))
                    (support/check :save-failure-leaves-original-intact
                           (and error-shown? content-intact? (not stray-tmp?)
                                names-user-path? (not names-tmp?))
                           {:error-shown error-shown? :content-intact content-intact?
                            :stray-tmp stray-tmp? :names-user-path names-user-path?
                            :names-tmp names-tmp? :error-text error-text})
                    (.delete (java.io.File. ^String path))
                    (.delete (java.io.File. ^String dir))
                    (next))))))

(defn row-dialog-dismissed-silent
  "M2 follow-up: cancelling/dismissing a file dialog
  (GTK_DIALOG_ERROR_DISMISSED, code 2, IN GTK's OWN error domain) must not
  print an error line -- but code 2 is ALSO G_IO_ERROR_EXISTS in a
  different domain, so dialog-dismissed? must check the domain too, not
  just the code. file-dialog! itself can't be driven without a live
  dialog, so this drives the exact result-handling fns with result maps
  shaped like g/finish-file's.

  Every append! (real or, if the dismissed guard were ever broken, errant)
  goes through on-gui!'s async drain. The FIRST version of this row read
  (since ui mark) synchronously right after the simulate calls, with
  nothing real ever enqueued -- so it could never fail no matter what
  dialog-dismissed? actually did. Fixed by adding a wrong-domain code-2
  case and a genuine different-code error, both real appends the row can
  poll for; once the last one lands, the SAME drain call has already
  flushed everything queued before it (drain! processes the whole queue
  in one pass), including any errant dismissed-case append, so the scoped
  text at that point is a real assertion, not a foregone conclusion."
  [ui next]
  (let [mark (support/out-end-offset ui)
        real-domain (g/gtk-dialog-error-quark)
        wrong-domain (inc real-domain)] ; any value != real-domain
    ;; the real dismissed case: code 2, the real GTK-dialog-error domain.
    ((:simulate-open-result! ui) {:error "Dismissed by user" :code 2 :domain real-domain})
    ((:simulate-save-result! ui) {:error "Dismissed by user" :code 2 :domain real-domain})
    ;; code 2, wrong domain -- stands in for G_IO_ERROR_EXISTS -- must NOT
    ;; be swallowed by a code-only check.
    ((:simulate-open-result! ui) {:error "exists" :code 2 :domain wrong-domain})
    ;; a genuine, different-code error: something real to poll for.
    ((:simulate-open-result! ui) {:error "boom" :code 1 :domain real-domain})
    (support/poll-until 1000 20
                (fn [] (str/includes? (support/since ui mark) "; open: boom"))
                (fn [boom-shown?]
                  (let [text (support/since ui mark)]
                    (support/check :dialog-dismissed-silent
                           (and boom-shown?
                                (str/includes? text "; open: exists")
                                (not (str/includes? text "; open: Dismissed by user"))
                                (not (str/includes? text "; save: Dismissed by user")))
                           {:console text})
                    (next))))))

(defn row-save-and-open [ui next]
  (let [tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-" (System/currentTimeMillis) ".clj")]
    (editor/set-text! (:editor ui) "(+ 1 1)")
    ((:save-to! ui) tmp)
    (let [saved-content (slurp tmp)]
      (editor/set-text! (:editor ui) "(+ 2 2)")
      ((:open-path! ui) tmp)
      (let [text (editor/editor-text (:editor ui))
            title (g/window-title (:window ui))]
        (support/check :save-and-open
               (and (= "(+ 1 1)" saved-content) (= "(+ 1 1)" text)
                    (some? title) (not (str/includes? title "* ")))
               {:saved-content saved-content :text text :title title})
        (.delete (java.io.File. ^String tmp))
        (next)))))
