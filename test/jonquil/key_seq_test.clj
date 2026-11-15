(ns jonquil.key-seq-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.command-registry :as registry]
            [jonquil.key-seq :as ks]
            [jonquil.keymap :as km]
            [jonquil.palette :as palette]
            [jonquil.user-keys :as user-keys]))

(use-fixtures :each (fn [t] (ks/cancel!) (try (t) (finally (ks/cancel!)))))

(def ^:private M km/MODIFIER-MASKS)
(def ^:private ctrl-alt (bit-or (:ctrl M) (:alt M)))
(def ^:private mac "Mac OS X")

(def ^:private alt
  "How this system's notes name the Alt key: step! labels a note for the
  system the test runs on."
  (if (= mac (System/getProperty "os.name")) "Option" "Alt"))

(defn- kv [n] (km/key-value n))

(def ^:private rewrap-round
  {:key "r" :exact #{:ctrl :alt} :then [{:key "p" :exact #{:ctrl :alt}}] :command :rewrap/round})
(def ^:private rewrap-square
  {:key "r" :exact #{:ctrl :alt} :then [{:key "s" :exact #{:ctrl :alt}}] :command :rewrap/square})
(def ^:private three
  {:key "r" :exact #{:ctrl :alt} :then [{:key "x" :exact #{}} {:key "y" :exact #{}}] :command :three/steps})
(def ^:private plain {:key "j" :exact #{:ctrl :alt} :command :plain/one})
(def ^:private bindings [rewrap-round rewrap-square three plain])

(defn- press!
  "Sends a key through the editor's answer over bindings, as the editor's
  handler does; ran collects the commands run. Answers 1 or 0."
  [ran keyval state]
  (registry/editor-answer bindings keyval state
                          {:holds? (constantly false)
                           :run! (fn [b] (swap! ran conj (:command b)) :handled)
                           :popup-key! (constantly 0)
                           :typing-key! (constantly 0)}))

(deftest a-sequence-runs-after-its-last-chord-test
  (let [ran (atom [])]
    (is (= 1 (press! ran (kv "r") ctrl-alt)) "the prefix is used up")
    (is (= [] @ran) "and runs nothing")
    (is (= [{:key "r" :exact #{:ctrl :alt}}] (:prefix (ks/pending))))
    (is (= 3 (count (:candidates (ks/pending)))) "every sequence that starts on it")
    (is (= 1 (press! ran (kv "s") ctrl-alt)))
    (is (= [:rewrap/square] @ran))
    (is (nil? (ks/pending)))
    (is (nil? (:note @ks/live)))))

(deftest three-chords-test
  (let [ran (atom [])]
    (press! ran (kv "r") ctrl-alt)
    (is (= 1 (press! ran (kv "x") 0)) "a later chord may be a plain key")
    (is (= [] @ran))
    (is (= 2 (count (:prefix (ks/pending)))))
    (is (= 1 (count (:candidates (ks/pending)))))
    (press! ran (kv "y") 0)
    (is (= [:three/steps] @ran))))

(deftest a-key-that-leads-nowhere-ends-it-test
  (let [ran (atom [])]
    (press! ran (kv "r") ctrl-alt)
    (is (= 1 (press! ran (kv "j") ctrl-alt)) "the key is used up")
    (is (= [] @ran) "and its own binding doesn't run")
    (is (nil? (ks/pending)))
    (is (= (str "Ctrl+" alt "+R Ctrl+" alt "+J isn't a key sequence.") (:note @ks/live)))
    (testing "the next key is an ordinary one again"
      (press! ran (kv "j") ctrl-alt)
      (is (= [:plain/one] @ran)))))

(deftest escape-cancels-and-a-modifier-waits-test
  (let [ran (atom [])]
    (press! ran (kv "r") ctrl-alt)
    (is (= 1 (press! ran 0xffe3 (:ctrl M))) "Control's own key press")
    (is (some? (ks/pending)) "leaves the sequence waiting")
    (is (= 1 (press! ran (kv "Escape") 0)))
    (is (nil? (ks/pending)))
    (is (= (str "Ctrl+" alt "+R cancelled.") (:note @ks/live)))
    (is (= [] @ran))))

(deftest without-start-seq-a-sequence-never-matches-test
  ;; a caller of dispatch that knows nothing of sequences runs none by half
  (is (= :unmatched (km/dispatch [rewrap-round] (kv "r") ctrl-alt
                                 {:holds? (constantly false) :run! (constantly :handled)})))
  (is (nil? (ks/pending))))

(deftest the-window-takes-the-next-key-of-an-editor-sequence-test
  ;; the window's handler sees every key first, so it steps a sequence the
  ;; editor started, and the command runs as the editor would run it
  (let [ran (atom [])]
    (press! ran (kv "r") ctrl-alt)
    (is (= 1 (registry/window-answer [{:key "p" :mods #{:cmd :alt} :command :window/other}]
                                     (kv "p") ctrl-alt
                                     {:holds? (constantly false)
                                      :run! (fn [b] (swap! ran conj [:window (:command b)]) :handled)})))
    (is (= [:rewrap/round] @ran))))

(deftest follow-rows-test
  (let [ran (atom [])
        commands {:rewrap/round {:doc "Round."} :rewrap/square {:doc "Square."} :three/steps {:doc "Three."}}]
    (press! ran (kv "r") ctrl-alt)
    (is (= [{:keys "Ctrl+Option+P" :doc "Round."}
            {:keys "Ctrl+Option+S" :doc "Square."}
            {:keys "X" :doc "1 more keys…"}]
           (ks/follow-rows (ks/pending) commands mac)))))

(deftest labels-and-problems-test
  (is (= "Ctrl+Alt+R Ctrl+Alt+P" (palette/chord-label rewrap-round "Linux")))
  (is (= "Ctrl+Option+R X Y" (palette/chord-label three mac)))
  (let [ids #{:rewrap/round}
        problems #(km/problems [%] ids #{})]
    (is (= [] (problems rewrap-round)))
    (is (= ["binding 0 :then isn't a vector of at least one chord"]
           (problems (assoc rewrap-round :then []))))
    (is (= ["binding 0 :then chord 1 names an unknown key (\"nokey\")"]
           (problems (assoc rewrap-round :then [{:key "nokey"}]))))
    (is (= ["binding 0 :then chord 1 has unknown keys [:command]"]
           (problems (assoc rewrap-round :then [{:key "p" :command :rewrap/round}]))))
    (is (some #{"binding 0 has a :then and an :outcome; a key sequence runs a command"}
              (problems (-> rewrap-round (dissoc :command) (assoc :outcome :handled)))))))

(deftest status-and-list-test
  (let [ran (atom [])
        commands {:rewrap/round {:doc "Round."} :rewrap/square {:doc "Square."} :three/steps {:doc "Three."}}]
    (press! ran (kv "r") ctrl-alt)
    (is (= "Ctrl+Alt+R …" (ks/status-text (ks/pending) "Linux")))
    (is (= ["Ctrl+Alt+R, then:"
            "  Ctrl+Alt+P  Round."
            "  Ctrl+Alt+S  Square."
            "  X           1 more keys…"
            "  Esc         Cancel"]
           (ks/list-lines (ks/pending) commands "Linux")))))

(deftest keys-edn-takes-the-guides-sequence-test
  ;; the guide's keys.edn example of a key sequence loads without a problem
  (let [text "{:window [{:key \"g\" :exact #{:ctrl :alt} :then [{:key \"r\"}] :command :run/run}]}"
        r (user-keys/read-keys text {:commands registry/commands
                                     :conditions {:window (set (keys registry/window-conditions))
                                                  :editor (set (keys registry/editor-conditions))}})]
    (is (= [] (:problems r)))
    (is (= [{:key "r"}] (:then (first (:window r)))))
    (is (= "Ctrl+Alt+G R" (palette/chord-label (first (:window r)) "Linux")))))
