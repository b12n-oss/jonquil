(ns jonquil.menus-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.keymap :as km]
            [jonquil.menus :as menus]))

(def ^:private commands (merge registry/window-commands registry/editor-commands))

(deftest shipped-menus-are-well-formed-test
  (is (= [] (menus/problems menus/shipped commands)))
  (is (= ["File" "Edit" "View" "Search" "Run" "Debug" "Tools" "Help"] (mapv :label menus/shipped))))

(deftest problems-test
  (let [ok [{:label "File" :sections [[{:command :file/open :label "Open…"}]]}]]
    (is (= [] (menus/problems ok commands)))
    (testing "an unknown command, a missing label, an empty section, a command listed twice"
      (is (some #(str/includes? % ":no/such") (menus/problems [{:label "X" :sections [[{:command :no/such :label "N"}]]}] commands)))
      (is (some #(str/includes? % "has no label") (menus/problems [{:label "X" :sections [[{:command :file/open}]]}] commands)))
      (is (some #(str/includes? % "empty section") (menus/problems [{:label "X" :sections [[]]}] commands)))
      (is (some #(str/includes? % "twice") (menus/problems [{:label "X" :sections [[{:command :file/open :label "A"}
                                                                                    {:command :file/open :label "B"}]]}]
                                                           commands))))
    (testing "a submenu is checked too, and named by its path"
      (is (some #(str/includes? % "X > Sub") (menus/problems [{:label "X" :sections [[{:label "Sub" :sections [[{:command :no/such :label "N"}]]}]]}]
                                                             commands))))))

(deftest action-name-test
  (is (= "file.save-as" (menus/action-name :file/save-as)))
  (is (= "compare-form.last-run" (menus/action-name :compare-form/last-run)))
  (is (= :file/save-as (menus/command-of "file.save-as")))
  (testing "every shipped command's name round-trips and is a valid action name"
    (doseq [id (menus/command-ids menus/shipped)]
      (is (= id (menus/command-of (menus/action-name id))) id)
      (is (re-matches #"[A-Za-z0-9.\-]+" (menus/action-name id)) id))))

(deftest accel-test
  (testing "a GTK accelerator for a binding: :cmd is Meta on a Mac and Control elsewhere"
    (is (= "<Meta>s" (menus/accel {:key "s" :mods #{:cmd}} "Mac OS X")))
    (is (= "<Control>s" (menus/accel {:key "s" :mods #{:cmd}} "Linux")))
    (is (= "<Shift><Meta>s" (menus/accel {:key "s" :mods #{:cmd :shift}} "Mac OS X")))
    (is (= "<Shift><Alt><Control>Left" (menus/accel {:key "Left" :exact #{:ctrl :alt :shift}} "Mac OS X")))
    (is (= "F5" (menus/accel {:key "F5"} "Linux")))
    (is (= "<Control>Return" (menus/accel {:keys #{"Return" "KP_Enter"} :mods #{:cmd}} "Linux"))
        "a :keys binding shows its first key by name")))

(deftest tree-test
  (let [keymap {:window [{:key "Escape" :when [:evaluating] :command :run/stop}
                         {:key "F5" :command :run/run}
                         {:key "r" :mods #{:cmd} :command :run/run}]
                :editor [{:keys #{"Return" "KP_Enter"} :mods #{:cmd} :command :run/form}]}
        ms [{:label "Run" :sections [[{:command :run/run :label "Run"}
                                      {:command :run/form :label "Run Form"}
                                      {:command :run/stop :label "Stop"}
                                      {:label "More" :sections [[{:command :run/changed :label "Changed"}]]}]]}]
        [run] (menus/tree {:menus ms :keymap keymap :os "Linux"})
        [[r f s more]] (:sections run)]
    (is (= "Run" (:label run)))
    (testing "an item carries its action name and the first key that runs it"
      (is (= {:command :run/run :label "Run" :action "run.run" :accel "F5"} r))
      (is (= "<Control>Return" (:accel f)) "an editor key shows too"))
    (testing "a key that only works under a condition isn't shown: the menu would take it at other times"
      (is (nil? (:accel s))))
    (testing "a submenu keeps its shape"
      (is (= "More" (:label more)))
      (is (= [[{:command :run/changed :label "Changed" :action "run.changed" :accel nil}]] (:sections more))))))

(deftest every-shipped-key-is-one-gtk-can-show-test
  (doseq [menu (menus/tree {:menus menus/shipped :keymap km/shipped :os "Mac OS X"})
          item (menus/items menu)
          :when (:accel item)]
    (is (re-matches #"(<(Shift|Alt|Control|Meta|Super)>)*[A-Za-z0-9_]+" (:accel item)) (:command item))))
