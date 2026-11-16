(ns mono.template-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [mono.template :as template]
            [org.corfield.new :as new]))

(defn delete-tree [file]
  (when (and (.isDirectory file) (not (java.nio.file.Files/isSymbolicLink (.toPath file))))
    (doseq [child (.listFiles file)] (delete-tree child)))
  (.delete file))

(defn with-directory [action]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "mono-template-"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (action root) (finally (delete-tree root)))))

(defn generate [root directory opts]
  (let [target (io/file root directory)]
    ;; A non-Git prefix keeps template tests on the local classpath.
    (new/create (-> opts
                    (assoc :target-dir (str target))
                    (update :template #(symbol (str "local%" %)))))
    target))

(defn read-edn [file]
  (edn/read-string (slurp file)))

(test/deftest validates-template-options-before-copying
  (with-directory
    (fn [root]
      (doseq [opts [{:template 'io.github.lambdaseq/mono :name 'acme/demo :layout :unknown}
                    {:template 'lambdaseq/app :name 'acme/demo :platform :python}
                    {:template 'lambdaseq/lib :name 'acme/demo :platform :clj :compiler :shadow}
                    {:template 'lambdaseq/lib :name 'acme/demo :platform :cljs :compiler :unknown}
                    {:template 'lambdaseq/lib :name 'Acme/demo}]]
        (test/is (thrown? Exception (generate root "invalid" opts)))
        (test/is (not (.exists (io/file root "invalid"))))))))

(test/deftest generates-self-contained-workspaces
  (with-directory
    (fn [root]
      (doseq [layout [:mixed :libs]]
        (let [target (generate root (name layout) {:template 'io.github.lambdaseq/mono :name 'my-org/my-project :layout layout})
              deps (read-edn (io/file target "deps.edn"))
              build (slurp (io/file target "build.clj"))]
          (test/is (.isFile (io/file target "libs/example-lib/src/my_org/my_project/example_lib/core.clj")))
          (test/is (= (= :mixed layout) (.exists (io/file target "apps"))))
          (test/is (= (if (= :mixed layout) 2 1) (count (get-in deps [:aliases :dev :extra-deps]))))
          (test/is (= #{'io.github.clojure/tools.build 'slipset/deps-deploy}
                      (set (keys (get-in deps [:aliases :build :deps])))))
          (test/is (re-find #"\(def modules\s+'" build))
          (test/is (not (str/includes? build "mono.")))
          (test/is (not (.exists (io/file target "monorepo.edn"))))
          (test/is (not (.exists (io/file target "bb.edn"))))
          (test/is (not (.exists (io/file target "src/mono"))))
          (test/is (every? #(not (str/includes? (slurp %) "{{"))
                           (filter #(.isFile %) (file-seq target))))
          (test/is (thrown? Exception (generate root (name layout)
                                                {:template 'io.github.lambdaseq/mono :name 'acme/other}))))))))

(test/deftest generates-clj-and-cljs-modules
  (with-directory
    (fn [root]
      (doseq [kind ['lambdaseq/app 'lambdaseq/lib]
              [platform compiler] [[:clj nil] [:cljs :shadow] [:cljs :cljs]]]
        (let [directory (str (name kind) "-" (name platform) "-" (or (some-> compiler name) "default"))
              target (generate root directory (cond-> {:template kind :name 'acme/some-module :platform platform}
                                                compiler (assoc :compiler compiler)))
              extension (if (= platform :clj) "clj" "cljs")
              deps (read-edn (io/file target "deps.edn"))]
          (test/is (.isFile (io/file target (str "src/acme/some_module/core." extension))))
          (test/is (.isFile (io/file target (str "test/acme/some_module/core_test." extension))))
          (test/is (not (.exists (io/file target "build.clj"))))
          (test/is (not (.exists (io/file target (str "core." extension)))))
          (test/is (not (.exists (io/file target (str "src/acme/some_module/core_test." extension)))))
          (test/is (= ["src" "resources"] (:paths deps)))
          (test/is (get-in deps [:aliases :test :main-opts]))
          (test/is (get-in deps [:aliases :repl :main-opts]))
          (when (= platform :cljs)
            (test/is (.isFile (io/file target (if (= compiler :shadow) "shadow-cljs.edn" "test.edn")))))
          (test/is (every? #(not (str/includes? (slurp %) "{{"))
                           (filter #(.isFile %) (file-seq target)))))))))

(test/deftest defaults-to-clj-and-shadow-for-cljs
  (test/is (= "clj" (:platform (template/create-app-data {:top "acme" :main "demo"}))))
  (test/is (nil? (:compiler (template/create-lib-data {:top "acme" :main "demo"}))))
  (test/is (= "shadow" (:compiler (template/create-lib-data {:top "acme" :main "demo" :platform :cljs})))))

(test/deftest generated-build-preserves-publication-dependency-semantics
  (with-directory
    (fn [root]
      (let [target (generate root "workspace" {:template 'io.github.lambdaseq/mono :name 'acme/demo :layout :libs})
            namespace (symbol (str "generated-build-" (random-uuid)))
            source (str/replace-first (slurp (io/file target "build.clj")) "(ns build" (str "(ns " namespace))]
        (try
          (binding [*ns* *ns*] (load-string source))
          (let [publish (ns-resolve namespace 'create-publication-basis)
                select (ns-resolve namespace 'select-modules)
                resolve-version (ns-resolve namespace 'resolve-version)
                directory (str (.getCanonicalFile (io/file "libs/example-lib")))
                basis {:libs {'development/alias {:local/root directory :dependents ['acme/parent]
                                                  :exclusions ['excluded/lib] :optional true}}}
                result (publish basis {directory {:lib 'acme.demo/example-lib}} {:version "0.2.0"})]
            (test/is (= {:mvn/version "0.2.0" :dependents ['acme/parent]
                         :exclusions ['excluded/lib] :optional true}
                        (get-in result [:libs 'acme.demo/example-lib])))
            (test/is (nil? (get-in result [:libs 'development/alias])))
            (test/is (thrown-with-msg? Exception #"Register local dependencies" (publish {:libs {'unknown/lib {:local/root "/outside"}}} {} {})))
            (test/is (thrown-with-msg? Exception #"Published dependencies need Maven coordinates" (publish {:libs {'git/lib {:git/sha "abc"}}} {} {})))
            (test/is (= "0.3.0" (resolve-version {:version "0.2.0"} {:version "0.3.0"})))
            (test/is (= "0.2.0" (resolve-version {:version "0.2.0"} {})))
            (test/is (thrown? Exception (resolve-version {} {:version "0.1.0-SNAPSHOT"})))
            (test/is (thrown? Exception (select {:modules :missing} (constantly true))))
            (test/is (= [:example-lib] (mapv first (select {} #(= :lib (:kind %)))))))
          (finally (remove-ns namespace)))))))
