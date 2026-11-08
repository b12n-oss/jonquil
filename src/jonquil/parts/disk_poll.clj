(ns jonquil.parts.disk-poll
  "Disk polling as a window part's data half. It has no commands: open tabs
  follow their files on a timer. The install half is
  jonquil.gtk.parts.disk-poll.")

(def spec
  "The disk-poll part: no commands, no panels."
  {:id :disk-poll :commands {}})
