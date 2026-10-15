(ns jonquil.save-path
  "Where a save really writes, and the temp file it writes through. No GTK:
  plain java.nio.file calls, so the unit tests can run them on temp dirs.")

(def ^:private max-hops 40)

(defn- link-step
  "The path a symlink at p points at, as an absolute normalized Path. A
  relative target is resolved against the link's canonical parent, so a
  .. in it climbs from the parent's real location, as the OS does, and
  not from the lexical path that reached the link."
  [p]
  (let [target (java.nio.file.Files/readSymbolicLink p)
        parent (.toPath (.getCanonicalFile (.toFile (.getParent (.toAbsolutePath p)))))]
    (.normalize (.resolve parent target))))

(defn resolve-target
  "The canonical path of the file a save to path must write, following
  symlinks by hand, each relative link resolved against its parent
  directory's real location. .getCanonicalPath alone does not follow a dangling
  link on this Jolt (probed: it returns the link's own path), and a rename
  onto the link's own path would replace the link. Walking the links first
  means a link to a missing file resolves to the file it would create.
  Throws ex-info after 40 hops, so a link loop fails instead of spinning."
  [path]
  (loop [p (.toPath (java.io.File. ^String path))
         hops 0]
    (cond
      (> hops max-hops)
      (throw (ex-info "too many levels of symbolic links" {:path path}))

      (java.nio.file.Files/isSymbolicLink p)
      (recur (link-step p) (inc hops))

      :else
      (.getCanonicalPath (.toFile p)))))

(defn create-temp!
  "When target exists, creates tmp empty with mode rw-------, so the new
  content never sits in a file wider than the owner. spit alone creates
  it with the umask default (644) and the later permission copy would
  come too late. Does nothing when target is missing; the temp file then
  gets the default mode, which is right for a new file."
  [tmp target]
  (when (.exists (java.io.File. ^String target))
    (java.nio.file.Files/createFile
     (.toPath (java.io.File. ^String tmp))
     (into-array java.nio.file.attribute.FileAttribute
                 [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                   (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))]))))
