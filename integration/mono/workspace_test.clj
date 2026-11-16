(ns mono.workspace-test
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :as test]
            [mono.template-test :as support]))

(defn capture-command [directory args]
  (println "Check:" (str/join " " args))
  (let [process (-> (ProcessBuilder. ^java.util.List args)
                    (.directory (io/file directory)) (.redirectErrorStream true) (.start))
        output (slurp (.getInputStream process))]
    {:exit (.waitFor process) :output output}))

(defn check-command [directory args]
  (let [{:keys [exit output] :as result} (capture-command directory args)]
    (when-not (zero? exit)
      (throw (ex-info (str "Generated project command failed:\n" output) {:args args :exit exit})))
    result))

(defn save-edn [file value]
  (spit file (with-out-str (pprint/pprint value))))

(defn register-module [workspace id module]
  (spit (io/file workspace "build.clj")
        (str "\n(def modules (assoc modules " (pr-str id) " '" (pr-str module) "))\n")
        :append true))

(test/deftest mixed-workspace-builds-without-a-template-runtime
  (support/with-directory
    (fn [root]
      (let [workspace (support/generate root "workspace" {:template 'io.github.lambdaseq/mono :name 'acme/workspace})]
        (check-command workspace ["clojure" "-T:build" "ci"])
        (test/is (str/includes? (:output (check-command workspace ["java" "-jar" "apps/example-app/target/example-app-0.1.0.jar"]))
                                "Hello, world!"))
        (test/is (str/includes? (:output (check-command workspace ["clojure" "-T:build" "run"])) "Hello, world!"))
        (test/is (= "0.2.0\n" (:output (check-command workspace ["clojure" "-T:build" "version" ":version" "\"0.2.0\""]))))
        (test/is (pos? (:exit (capture-command workspace ["clojure" "-T:build" "jar" ":modules" ":missing"]))))
        (test/is (pos? (:exit (capture-command workspace ["clojure" "-T:build" "deploy"]))))
        (test/is (pos? (:exit (capture-command workspace ["clojure" "-T:build" "deploy" ":modules" ":example-lib"]))))
        (let [file (io/file workspace "libs/example-lib/test/acme/workspace/example_lib/core_test.clj")
              original (slurp file)]
          (spit file (str/replace original "Hello, world!" "wrong"))
          (test/is (pos? (:exit (capture-command workspace ["clojure" "-T:build" "test" ":modules" ":example-lib"]))))
          (spit file original))
        (check-command workspace ["clojure" "-T:build" "clean" ":modules" ":example-app"])
        (test/is (not (.exists (io/file workspace "apps/example-app/target"))))
        (test/is (.isFile (io/file workspace "libs/example-lib/target/example-lib-0.1.0.jar")))))))

(test/deftest library-workspace-installs-portable-dependent-jars
  (support/with-directory
    (fn [root]
      (let [workspace (support/generate root "workspace" {:template 'io.github.lambdaseq/mono :name 'acme/libraries :layout :libs})
            consumer (support/generate workspace "libs/consumer" {:template 'lambdaseq/lib :name 'acme/consumer})
            repo (io/file root "maven")
            consumer-deps (io/file consumer "deps.edn")]
        (save-edn consumer-deps (assoc-in (support/read-edn consumer-deps)
                                          [:deps 'acme.libraries/example-lib] {:local/root "../example-lib"}))
        (spit (io/file consumer "src/acme/consumer/core.clj")
              "(ns acme.consumer.core (:require [acme.libraries.example-lib.core :as shared]))\n(defn greet [person] (shared/greet person))\n")
        (register-module workspace :consumer {:kind :lib :dir "libs/consumer" :platform :clj :lib 'acme/consumer})
        ;; Packaging must respect module paths instead of assuming every module uses src/.
        (.renameTo (io/file workspace "libs/example-lib/src") (io/file workspace "libs/example-lib/source"))
        (let [file (io/file workspace "libs/example-lib/deps.edn")]
          (save-edn file (assoc (support/read-edn file) :paths ["source" "resources"])))
        (check-command workspace ["clojure" "-T:build" "test"])
        (check-command workspace ["clojure" "-T:build" "install" ":version" "\"0.2.0\"" ":local-repo" (pr-str (str repo))])
        (test/is (not (.exists (io/file workspace "apps"))))
        (let [pom (slurp (io/file repo "acme/consumer/0.2.0/consumer-0.2.0.pom"))]
          (test/is (str/includes? pom "<groupId>acme.libraries</groupId>"))
          (test/is (str/includes? pom "<artifactId>example-lib</artifactId>"))
          (test/is (str/includes? pom "<version>0.2.0</version>"))
          (test/is (not (str/includes? pom ":local/root"))))
        (let [external (io/file root "external")]
          (.mkdirs external)
          (save-edn (io/file external "deps.edn")
                    {:deps {'acme/consumer {:mvn/version "0.2.0"}}
                     :mvn/repos {"central" {:url (str (.toURI repo))}
                                 "clojars" {:url (str (.toURI repo))}}})
          (test/is (str/includes? (:output (check-command external
                                                          ["clojure" "-M" "-e" "(require '[acme.consumer.core :as c]) (println (c/greet \"world\"))"]))
                                  "Hello, world!")))))))

(test/deftest cljs-modules-use-both-compiler-backends
  (support/with-directory
    (fn [root]
      (let [workspace (support/generate root "workspace" {:template 'io.github.lambdaseq/mono :name 'acme/js-workspace :layout :libs})
            ids [:web-shadow :web-plain :shared-shadow :shared-plain]]
        (doseq [[id kind compiler] [[:web-shadow :app :shadow] [:web-plain :app :cljs]
                                    [:shared-shadow :lib :shadow] [:shared-plain :lib :cljs]]]
          (let [directory (str (if (= kind :app) "apps/" "libs/") (name id))
                module-name (symbol "acme" (name id))]
            (support/generate workspace directory {:template (symbol "lambdaseq" (name kind)) :name module-name
                                                   :platform :cljs :compiler compiler})
            (register-module workspace id (cond-> {:kind kind :dir directory :platform :cljs
                                                   :compiler compiler :lib module-name}
                                            (= kind :app) (assoc :main (symbol (str "acme." (name id) ".core")))))))
        (check-command workspace ["clojure" "-T:build" "ci" ":modules" (pr-str ids)])
        (doseq [id [:web-shadow :web-plain]]
          (test/is (.isFile (io/file workspace "apps" (name id) "target/public/index.html"))))
        (doseq [id [:shared-shadow :shared-plain]]
          (test/is (.isFile (io/file workspace "libs" (name id) "target" (str (name id) "-0.1.0.jar")))))
        (doseq [id [:web-shadow :web-plain]]
          (let [file (io/file workspace "apps" (name id) "test/acme" (str/replace (name id) "-" "_") "core_test.cljs")]
            (spit file (str/replace (slurp file) "Hello, world!" "wrong"))
            (test/is (pos? (:exit (capture-command workspace ["clojure" "-T:build" "test" ":modules" (pr-str id)]))))))))))
