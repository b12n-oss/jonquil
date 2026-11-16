(ns jonquil.gtk.window.place-move-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.place-move :as win-place-move]
            [jonquil.gtk.window.places :as win-places]))

(defn- settings-file [m]
  (let [f (doto (java.io.File/createTempFile "settings" ".edn") (.deleteOnExit))]
    (spit f (if (string? m) m (pr-str m)))
    (.getPath f)))

(defn- fake-right
  "A right map over keyword places: the terminal (two pages) at the
  bottom, the tree on the left, the inspector and the trace on the right."
  []
  {:places {:left ::left :right ::right :bottom ::bottom}
   :place-of* (atom {:file-tree :left :repl :bottom :terminal :bottom :inspector :right :trace :bottom})
   :pages-of* (atom {:terminal [::t1 ::t2] :file-tree [::tree] :inspector [::i] :trace [::tr]})
   :inspector-root ::i :source-root ::s
   :part-panels* (atom {:trace {:root ::tr}})})

(defn- run-move [right path id place & {:keys [vis position]}]
  (let [log (atom []) vis (atom (or vis {}))]
    (with-redefs [win-places/move-page! (fn [from to w] (swap! log conj [:page from to w]))
                  win-places/move-action! (fn [from to] (swap! log conj [:action from to]))
                  g/gtk-widget-get-visible (fn [w] (get @vis w 0))
                  g/gtk-widget-set-visible (fn [w v] (swap! vis assoc w v) (swap! log conj [:visible w v]))
                  g/gtk-widget-get-parent (fn [w] (when (= ::left w) ::tree-paned))
                  g/gtk-paned-get-position (fn [_] (or position 300))
                  g/gtk-paned-set-position (fn [p v] (swap! log conj [:position p v]))]
      {:result (win-place-move/move! {:right right :settings-path* (atom path)} id place)
       :log @log :vis @vis})))

(deftest moving-the-terminal-takes-its-pages-and-its-plus-test
  (let [right (fake-right)
        path (settings-file {:theme :dark :layout {:bottom [:terminal :repl]}})
        {:keys [result log]} (run-move right path :terminal :right)]
    (is (nil? result))
    (is (= [[:page ::bottom ::right ::t1] [:page ::bottom ::right ::t2] [:action ::bottom ::right]] log))
    (is (= :right (:terminal @(:place-of* right))))
    (is (= {:theme :dark :layout {:left [] :right [:terminal] :bottom [:repl]}}
           (edn/read-string (slurp path)))
        "the move is written to :layout and the other settings stay")))

(deftest moving-to-the-same-place-does-nothing-test
  (let [right (fake-right)
        path (settings-file {:theme :dark})
        {:keys [result log]} (run-move right path :repl :bottom)]
    (is (nil? result))
    (is (= [] log))
    (is (= {:theme :dark} (edn/read-string (slurp path))))))

(deftest the-tree-keeps-its-width-across-moves-test
  (let [right (fake-right)
        path (settings-file {:file-tree {:visible? true :width 240}})]
    (testing "leaving the left saves the split's position as its width"
      (run-move right path :file-tree :right :position 312)
      (is (= {:visible? true :width 312} (:file-tree (edn/read-string (slurp path))))))
    (testing "coming back sets the split from it"
      (let [{:keys [log]} (run-move right path :file-tree :left)]
        (is (some #{[:position ::tree-paned 312]} log))
        (is (= {:left [:file-tree] :right [] :bottom []} (:layout (edn/read-string (slurp path)))))))))

(deftest a-showing-panel-takes-the-place-of-the-panel-there-test
  (let [right (fake-right)
        path (settings-file {})
        {:keys [vis]} (run-move right path :inspector :bottom :vis {::i 1 ::tr 1})]
    (is (= 0 (get vis ::tr)) "one panel per place: the trace hides")
    (is (= 1 (get vis ::i))))
  (testing "a hidden panel hides nothing"
    (let [right (fake-right)
          {:keys [vis]} (run-move right (settings-file {}) :inspector :bottom :vis {::tr 1})]
      (is (= 1 (get vis ::tr))))))

(deftest a-failed-save-is-answered-test
  (let [right (fake-right)
        path (settings-file "{:theme")
        {:keys [result log]} (run-move right path :repl :right)]
    (is (string? result))
    (is (re-find #"isn't valid EDN" result))
    (is (= :right (:repl @(:place-of* right))) "the part moved all the same")
    (is (= [] log) "the REPL has no recorded pages in this fake")
    (is (= "{:theme" (slurp path)) "a file it can't read is left alone")))

(deftest panel-or-part-test
  (with-redefs [win-places/current? (fn [nb w] (and (= ::right nb) (= ::i w)))
                g/gtk-widget-get-visible (fn [_] 1)]
    (is (= :inspector (win-place-move/panel-or-part (assoc (fake-right) :recent-panels (atom [])) :panel)))
    (is (= :repl (win-place-move/panel-or-part (fake-right) :repl)))))
