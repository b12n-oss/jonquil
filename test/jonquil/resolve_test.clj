(ns jonquil.resolve-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.resolve :as r]))

(def ^:private clj #{:clj})

(defn- res
  "The resolution of sym-text where it first appears inside the first
  occurrence of needle in text (needle defaults to sym-text), read for
  features."
  ([text sym-text] (res text sym-text sym-text clj))
  ([text needle sym-text] (res text needle sym-text clj))
  ([text needle sym-text features]
   (let [offset (+ (.indexOf ^String text ^String needle)
                   (.indexOf ^String needle ^String sym-text))]
     (r/resolve-at (r/file-context text features) offset sym-text))))

(deftest ns-info-test
  (testing "aliases, refers, renames and excludes"
    (is (= {:ns 'demo.a
            :aliases {'str 'clojure.string 'm 'my.mod}
            :refers {'join 'clojure.string/join 'w 'my.mod/write}
            :excludes #{'get} :core-renames {} :all? false}
           (r/ns-info (str "(ns demo.a \"doc\"\n"
                           "  (:refer-clojure :exclude [get])\n"
                           "  (:require [clojure.string :as str :refer [join]]\n"
                           "            [my.mod :as-alias m :refer [write] :rename {write w}]))\n")
                      clj))))
  (testing "no ns form: user"
    (is (= 'user (:ns (r/ns-info "(defn f [] 1)" clj)))))
  (testing "a reader conditional picks the branch for the features"
    (let [text "(ns a (:require #?(:clj [x.y :as z] :cljs [x.w :as z])))"]
      (is (= 'x.y (get-in (r/ns-info text #{:clj}) [:aliases 'z])))
      (is (= 'x.w (get-in (r/ns-info text #{:cljs}) [:aliases 'z])))))
  (testing "prefix lists"
    (is (= {'s 'a.b.c 'd 'a.b.d}
           (:aliases (r/ns-info "(ns x (:require (a.b [c :as s] [d :as d])))" clj)))))
  (testing ":refer :all and :use make unknown bare names unresolvable"
    (is (:all? (r/ns-info "(ns x (:require [a.b :refer :all]))" clj)))
    (is (:all? (r/ns-info "(ns x (:use a.b))" clj)))
    (is (= {'f 'a.b/f} (:refers (r/ns-info "(ns x (:use [a.b :only [f]]))" clj))))
    (is (not (:all? (r/ns-info "(ns x (:use [a.b :only [f]]))" clj)))))
  (testing "a refer-clojure rename"
    (is (= {'g 'clojure.core/get} (:core-renames (r/ns-info "(ns x (:refer-clojure :rename {get g}))" clj)))))
  (testing "an ns form in a comment block or discarded isn't the file's"
    (is (= 'b (:ns (r/ns-info "(comment (ns a))\n#_(ns c)\n(ns b)" clj)))))
  (testing "an ns form that doesn't read leaves the file unresolvable, not wrong"
    (is (= {:ns nil :aliases {} :refers {} :excludes #{} :core-renames {} :all? true}
           (r/ns-info "(ns a (:require [x :as y]) {:odd})" clj)))
    (is (= {:unresolved true} (res "(ns a (:require [x :as y]) {:odd})\n(defn f [])\n(f)" "(f)" "f"))
        "its own defs don't resolve to user")))

(def ^:private src
  (str "(ns demo.a\n"
       "  (:refer-clojure :exclude [get])\n"
       "  (:require [clojure.string :as str :refer [join]]\n"
       "            [my.mod :as m]))\n"
       "(defn get [k] k)\n"
       "(join [1]) (str/join [2]) (m/join 3) (clojure.set/join 4)\n"
       "(let [join inc] (join 5))\n"
       "(get 1) (map inc [1]) (frob 2)\n"))

(deftest resolve-at-test
  (testing "a referred bare name"
    (is (= {:var 'clojure.string/join} (res src "(join [1])" "join"))))
  (testing "the ns form's own refer resolves too"
    (is (= {:var 'clojure.string/join} (res src "[join]" "join"))))
  (testing "aliases, and a full namespace name"
    (is (= {:var 'clojure.string/join} (res src "str/join")))
    (is (= {:var 'my.mod/join} (res src "m/join")))
    (is (= {:var 'clojure.set/join} (res src "clojure.set/join"))))
  (testing "a let binding is a local, not the var"
    (is (= {:local true} (res src "(join 5)" "join"))))
  (testing "the file's own def wins over an excluded core name"
    (is (= {:var 'demo.a/get} (res src "(get 1)" "get")))
    (is (= {:var 'demo.a/get} (res src "(defn get" "get"))))
  (testing "clojure.core, and a name nothing defines"
    (is (= {:var 'clojure.core/map} (res src "map")))
    (is (= {:unresolved true} (res src "frob")))))

(deftest resolve-at-edge-test
  (testing "with :refer :all an unknown bare name is unresolved"
    (let [t "(ns x (:require [a.b :refer :all]))\n(frob 1) (map f xs)"]
      (is (= {:unresolved true} (res t "frob")))))
  (testing "an excluded core name with no own def is unresolved"
    (is (= {:unresolved true} (res "(ns x (:refer-clojure :exclude [map]))\n(map f xs)" "(map f" "map"))))
  (testing "a refer-clojure rename"
    (let [t "(ns x (:refer-clojure :rename {get g}))\n(g m :k) (get m :k)"]
      (is (= {:var 'clojure.core/get} (res t "(g m" "g")))
      (is (= {:unresolved true} (res t "(get m" "get")))))
  (testing "a script with no ns form: its own defs are user's"
    (is (= {:var 'user/f} (res "(defn f [] 1)\n(f)" "(f)" "f"))))
  (testing "cljc: the alias follows the features"
    (let [t "(ns a (:require #?(:clj [x.y :as z] :cljs [x.w :as z])))\n(z/f 1)"]
      (is (= {:var 'x.y/f} (res t "z/f" "z/f" #{:clj})))
      (is (= {:var 'x.w/f} (res t "z/f" "z/f" #{:cljs})))))
  (testing "ClojureScript's core is cljs.core"
    (is (= {:var 'cljs.core/map} (res "(ns a)\n(map f xs)" "map" "map" #{:cljs}))))
  (testing "a fn's parameter is a local"
    (is (= {:local true} (res "(defn f [join] (join 1))" "(join 1)" "join")))))

(deftest features-for-test
  (is (= #{:cljs} (r/features-for "/p/a.cljs" #{:jolt :clj})))
  (is (= #{:cljd} (r/features-for "/p/a.cljd" #{:jolt :clj})))
  (is (= #{:jolt :clj} (r/features-for "/p/a.cljc" #{:jolt :clj})))
  (is (= #{:cljs} (r/features-for "/p/a.cljc" #{:cljs})))
  (is (= #{:jolt :clj} (r/features-for "/p/a.clj" #{:jolt :clj})))
  (is (= #{:clj} (r/features-for "/p/a.clj" #{:cljs})) "a .clj file is never read as ClojureScript"))

(deftest select-reader-conditionals-test
  (let [sel r/select-reader-conditionals]
    (testing "the first branch for the features, else :default, else nothing"
      (is (= "(a [x.w :as z] b)" (sel "(a #?(:clj [x.y :as z] :cljs [x.w :as z]) b)" #{:cljs})))
      (is (= "(a (d) b)" (sel "(a #?(:clj [x] :default (d)) b)" #{:cljd})))
      (is (= "(a  b)" (sel "(a #?(:clj [x]) b)" #{:cljs}))))
    (testing "splicing drops the branch's own brackets"
      (is (= "(r [p :as q] [s])" (sel "(r #?@(:cljs [[p :as q] [s]] :clj []))" #{:cljs})))
      (is (= "(r )" (sel "(r #?@(:cljs [[p :as q]] :clj []))" #{:clj}))))
    (testing "nested conditionals, and a set's # stays with it"
      (is (= "(r #{y})" (sel "(r #?(:clj #?(:jolt #{y} :default x)))" #{:clj :jolt}))))
    (testing "no conditional: unchanged"
      (is (= "(ns a (:require [b]))" (sel "(ns a (:require [b]))" #{:clj}))))))

;; --- review round 1: places that must never get a wrong :var -------------

(deftest unknown-qualifier-test
  (testing "a qualifier that's no alias and no dotted ns name is unresolved"
    (is (= {:unresolved true} (res "(require '[clojure.string :as str])\n(str/join 1)" "str/join"))))
  (testing "a dotted full ns name still resolves"
    (is (= {:var 'clojure.set/join} (res "(ns a)\n(clojure.set/join 1)" "clojure.set/join")))))

(deftest own-defs-exact-test
  (testing "deref and default-something define nothing"
    (is (= {:var 'clojure.core/atom} (res "(ns a)\n(deref atom)\n(atom 1)" "(atom 1)" "atom"))))
  (testing "defmethod doesn't define its multimethod"
    (is (= {:var 'clojure.core/print-method}
           (res "(ns a)\n(defmethod print-method Foo [x w])\n(print-method 1 2)" "(print-method 1" "print-method"))))
  (testing "metadata before the name is skipped"
    (is (= {:var 'a/count} (res "(ns a (:refer-clojure :exclude [count]))\n(def ^:private count 1)\n(inc count)" "count)" "count")))
    (is (= {:var 'a/count} (res "(ns a (:refer-clojure :exclude [count]))\n(def ^{:private true} count 1)\n(inc count)" "count)" "count")))
    (is (= {:var 'a/foo} (res "(ns a)\n(defn ^String foo [] 1)\n(foo)" "(foo)" "foo")))
    (is (= {:var 'clojure.core/str} (res "(ns a)\n(defn ^String foo [] (str 1))" "str")) "the hint's class isn't defined")))

(deftest local-scope-test
  (testing "a def's own name isn't its parameter"
    (is (= {:var 'a/status} (res "(ns a)\n(defn status [status] status)" "status"))))
  (testing "a binding's init that names the outer var isn't called a local"
    (is (= {:unresolved true} (res "(ns a)\n(def cfg 1)\n(let [cfg (or cfg 2)] cfg)" "(or cfg" "cfg")))
    (is (= {:var 'a/x} (res "(ns a)\n(def x 1)\n(let [y x x 2] y)" "y x" "x"))
        "before the binding, x is still the outer var"))
  (testing "the binding site itself, and uses in the body, are locals"
    (is (= {:local true} (res "(ns a)\n(let [cfg 2] cfg)" "cfg 2" "cfg")))
    (is (= {:local true} (res "(ns a)\n(let [cfg 2] cfg)" "cfg)" "cfg")))))

(deftest refer-all-and-broken-ns-test
  (testing ":refer :all or :use: a core name may be shadowed, so it's unresolved"
    (is (= {:unresolved true} (res "(ns x (:require [clojure.string :refer :all]))\n(replace s m)" "replace")))
    (is (= {:unresolved true} (res "(ns x (:use a.b))\n(map f xs)" "map"))))
  (testing "an explicit refer still resolves next to :refer :all"
    (is (= {:var 'p.q/f} (res "(ns x (:require [a.b :refer :all] [p.q :refer [f]]))\n(f 1)" "(f 1)" "f"))))
  (testing "an unreadable ns form resolves no bare name, core included"
    (is (= {:unresolved true} (res "(ns a (:require [x :as y]) {:odd})\n(map f xs)" "map"))))
  (testing "an in-ns file isn't user"
    (is (= {:unresolved true} (res "(in-ns 'my.app)\n(defn f [])\n(f)" "(f)" "f")))))

(deftest malformed-clauses-test
  (testing "a malformed clause doesn't throw: the file is unresolvable"
    (is (= {:unresolved true} (res "(ns a (:refer-clojure :exclude map))\n(map f xs)" "(map f" "map")))
    (is (= {:unresolved true} (res "(ns a (:require [b :refer [f] :rename [f g]]))\n(g 1)" "(g 1)" "g")))))

(deftest ns-form-symbols-test
  (testing "an alias in the ns form isn't clojure.core's str"
    (is (= {:unresolved true} (res "(ns a (:require [clojure.string :as str]))" "str]" "str"))))
  (testing "a name in a :refer vector is the var it refers"
    (is (= {:var 'clojure.string/join} (res "(ns a (:require [clojure.string :refer [join]]))" "join"))))
  (testing "the ns name and lib names are unresolved"
    (is (= {:unresolved true} (res "(ns a (:require [clojure.string :refer [join]]))" "clojure.string")))))

(deftest reader-conditional-in-string-test
  (testing "a #?( in the ns docstring doesn't stop the real branches being picked"
    (let [t "(ns a \"uses #?( forms\" (:require #?(:clj [x.y :as z] :cljs [x.w :as z])))\n(z/f 1)"]
      (is (= {:var 'x.w/f} (res t "z/f" "z/f" #{:cljs})))))
  (testing "refer-clojure :only hides the rest of core"
    (is (= {:unresolved true} (res "(ns a (:refer-clojure :only [map]))\n(filter f xs)" "filter")))
    (is (= {:var 'clojure.core/map} (res "(ns a (:refer-clojure :only [map]))\n(map f xs)" "(map" "map")))))
