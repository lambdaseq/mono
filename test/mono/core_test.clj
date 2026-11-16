(ns mono.core-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [mono.cli :as cli]
            [mono.cljs :as cljs]
            [mono.config :as config]
            [mono.files :as files]
            [mono.plan :as plan]
            [mono.scaffold :as scaffold]
            [mono.session :as session]))

(defn delete-tree [file]
  (when (and (.isDirectory file) (not (java.nio.file.Files/isSymbolicLink (.toPath file))))
    (doseq [child (.listFiles file)] (delete-tree child)))
  (.delete file))

(defn with-temp-directory [action]
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory "mono-test-"
                                                                    (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (action directory) (finally (delete-tree directory)))))

(def runtime {:local/root (cli/source-root)})

(test/deftest interactive-init-defaults-to-mixed
  (with-temp-directory
    (fn [root]
      (let [destination (io/file root "mixed-workspace")
            output (with-redefs [cli/has-terminal? (constantly true)
                                 cli/runtime-coordinate (constantly runtime)]
                     (with-in-str (str destination "\nacme.tools\n\n")
                       (with-out-str (cli/run-cli ["init"]))))
            manifest (:manifest (config/load-workspace destination))]
        (test/is (= "acme.tools" (:namespace manifest)))
        (test/is (= [:app :lib] (mapv :kind (:modules manifest))))
        (test/is (str/includes? output "Project directory: "))
        (test/is (str/includes? output "[mixed]: "))
        (test/is (str/includes? output "Created"))))))

(test/deftest interactive-init-retries-invalid-answers
  (with-temp-directory
    (fn [root]
      (let [destination (io/file root "my libraries")
            occupied (io/file root "existing.txt")]
        (spit occupied "preserve")
        (let [output (with-redefs [cli/has-terminal? (constantly true)
                                   cli/runtime-coordinate (constantly runtime)]
                       (with-in-str (str "\n" occupied "\n" root "\n" destination
                                         "\nBad Prefix\nacme\nunknown\nlibs\n")
                         (with-out-str (cli/run-cli ["init"]))))]
          (test/is (str/includes? output "Project directory is required."))
          (test/is (str/includes? output "Init requires an empty or absent directory."))
          (test/is (str/includes? output "Namespace prefixes must contain"))
          (test/is (str/includes? output "Workspace template must be mixed or libs."))
          (test/is (= [:lib] (mapv :kind (:modules (:manifest (config/load-workspace destination))))))
          (test/is (not (.exists (io/file destination "apps"))))
          (test/is (= "preserve" (slurp occupied))))))))

(test/deftest interactive-init-cancels-before-writing
  (with-temp-directory
    (fn [root]
      (let [destination (io/file root "cancelled")]
        (doseq [input ["" (str destination "\n") (str destination "\nacme\n")]]
          (with-redefs [cli/has-terminal? (constantly true)
                        cli/runtime-coordinate (constantly runtime)]
            (with-in-str input
              (with-out-str
                (test/is (thrown-with-msg? Exception #"Generation cancelled"
                                           (cli/run-cli ["init"]))))))
          (test/is (not (.exists destination))))))))

(test/deftest noninteractive-init-requires-explicit-arguments
  (with-redefs [cli/has-terminal? (constantly false)]
    (doseq [arguments [["init"] ["init" "project"] ["init" "--namespace" "acme"]
                       ["init" "--template" "libs"]]]
      (with-in-str "unread input\n"
        (test/is (thrown-with-msg? Exception #"Usage: mono init DIRECTORY --namespace PREFIX"
                                   (cli/run-cli arguments)))
        (test/is (= "unread input" (read-line)))))))

(test/deftest explicit-init-skips-wizard-in-any-environment
  (doseq [terminal? [false true]]
    (with-temp-directory
      (fn [root]
        (with-redefs [cli/has-terminal? (constantly terminal?)
                      cli/prompt-init #(throw (ex-info "Unexpected prompt." {}))
                      cli/runtime-coordinate (constantly runtime)]
          (with-out-str
            (cli/run-cli ["init" (str root) "--namespace" "acme" "--template" "libs"])))
        (test/is (= [:lib] (mapv :kind (:modules (:manifest (config/load-workspace root))))))))))

(defn with-library-workspace [action]
  (with-temp-directory
    (fn [root]
      (scaffold/init-workspace root "acme" runtime :libs)
      (action root))))

(test/deftest interactive-add-generates-each-module-variant
  (doseq [kind [:app :lib]
          [answers platform compiler] [["\n" :clj nil] ["cljs\n\n" :cljs :shadow] ["cljs\nno\n" :cljs :cljs]]]
    (with-library-workspace
      (fn [root]
        (let [output (with-redefs [cli/has-terminal? (constantly true)]
                       (with-in-str (str "sample\n" answers)
                         (with-out-str (cli/run-add root (:manifest (config/load-workspace root)) [(name kind)] {}))))
              module (last (:modules (:manifest (config/load-workspace root))))
              directory (io/file root (:path module))
              deps (config/read-edn (io/file directory "deps.edn"))]
          (test/is (= kind (:kind module)))
          (test/is (= platform (:platform module)))
          (test/is (= compiler (:compiler module)))
          (test/is (.isFile (io/file directory "README.md")))
          (if (= platform :cljs)
            (do
              (test/is (str/includes? output "Use shadow-cljs?"))
              (test/is (.isFile (io/file directory (if (= compiler :shadow) "shadow-cljs.edn" "test.edn")))))
            (do
              (test/is (not (str/includes? output "Use shadow-cljs?")))
              (test/is (get-in deps [:aliases :test :main-opts])))))))))

(test/deftest interactive-add-retries-invalid-and-existing-names
  (with-library-workspace
    (fn [root]
      (let [output (with-redefs [cli/has-terminal? (constantly true)]
                     (with-in-str "Bad Name\nexample-lib\nshared\npython\ncljs\nmaybe\nN\n"
                       (with-out-str (cli/run-add root (:manifest (config/load-workspace root)) ["lib"] {}))))
            modules (:modules (:manifest (config/load-workspace root)))]
        (test/is (str/includes? output "Names must use lowercase"))
        (test/is (str/includes? output "Module already exists."))
        (test/is (str/includes? output "Platform must be clj or cljs."))
        (test/is (str/includes? output "Answer yes or no."))
        (test/is (= ["libs/example-lib" "libs/shared"] (mapv :path modules)))
        (test/is (= :cljs (:compiler (last modules))))))))

(test/deftest interactive-add-cancels-before-writing
  (doseq [kind ["app" "lib"] input ["" "cancelled\n" "cancelled\ncljs\n"]]
    (with-library-workspace
      (fn [root]
        (let [before (set (map str (file-seq root)))
              manifest (slurp (io/file root "monorepo.edn"))]
          (with-redefs [cli/has-terminal? (constantly true)]
            (with-in-str input
              (with-out-str
                (test/is (thrown-with-msg? Exception #"Generation cancelled"
                                           (cli/run-add root (:manifest (config/load-workspace root)) [kind] {}))))))
          (test/is (= manifest (slurp (io/file root "monorepo.edn"))))
          (test/is (= before (set (map str (file-seq root))))))))))

(test/deftest noninteractive-add-requires-name-without-consuming-input
  (with-library-workspace
    (fn [root]
      (let [manifest (:manifest (config/load-workspace root))]
        (with-redefs [cli/has-terminal? (constantly false)]
          (doseq [[positional options] [[["app"] {}] [["lib"] {}] [["lib"] {:platform "cljs"}] [[] {}]]]
            (with-in-str "unread input\n"
              (test/is (thrown-with-msg? Exception #"Usage: mono add app\|lib NAME"
                                         (cli/run-add root manifest positional options)))
              (test/is (= "unread input" (read-line)))))
          (test/is (not (.exists (io/file root ".mono")))))))))

(test/deftest explicit-add-skips-wizard-in-any-environment
  (doseq [terminal? [false true] kind ["app" "lib"]
          options [{} {:platform "cljs"} {:platform "cljs" :compiler "cljs"}]]
    (with-library-workspace
      (fn [root]
        (with-redefs [cli/has-terminal? (constantly terminal?)
                      cli/prompt-add (fn [& _] (throw (ex-info "Unexpected prompt." {})))]
          (with-out-str (cli/run-add root (:manifest (config/load-workspace root)) [kind "sample"] options)))
        (let [module (last (:modules (:manifest (config/load-workspace root))))]
          (test/is (= (keyword kind) (:kind module)))
          (test/is (= (keyword (get options :platform "clj")) (:platform module)))
          (test/is (= (when (= "cljs" (:platform options)) (keyword (get options :compiler "shadow")))
                      (:compiler module))))))))

(test/deftest explicit-add-validates-options-before-generating-module
  (with-library-workspace
    (fn [root]
      (let [manifest (:manifest (config/load-workspace root))]
        (with-redefs [cli/has-terminal? (constantly true)
                      cli/prompt-add (fn [& _] (throw (ex-info "Unexpected prompt." {})))]
          (doseq [options [{:platform "python"} {:platform "clj" :compiler "shadow"}
                           {:platform "cljs" :compiler "unknown"}]]
            (with-out-str (test/is (thrown? Exception (cli/run-add root manifest ["app" "invalid"] options)))))
          (test/is (not (.exists (io/file root "apps/invalid"))))
          (test/is (= manifest (:manifest (config/load-workspace root)))))))))

(test/deftest validates-inputs-at-edges
  (doseq [value ["my-app" "a" "app2"]]
    (test/is (= value (config/validate-name value))))
  (doseq [value [nil "" "../escape" "x/y" "UPPER" "two--words" "-bad" "a.b"]]
    (test/is (thrown? Exception (config/validate-name value))))
  (doseq [value [nil "" "acme." ".acme" "acme..app" "Acme"]]
    (test/is (thrown? Exception (config/validate-prefix value))))
  (test/is (= "acme.tools" (config/validate-prefix "acme.tools")))
  (test/is (thrown? Exception (cli/parse-options ["--bogus" "x"] #{:module})))
  (test/is (thrown? Exception (cli/parse-options ["--module"] #{:module})))
  (test/is (thrown? Exception (cli/parse-options ["--platform" "clj" "--platform" "cljs"] #{:platform})))
  (test/is (= {:options {:module ["apps/a" "libs/b"]} :positional []}
              (cli/parse-options ["--module" "apps/a" "--module" "libs/b"] #{:module})))
  (doseq [value ["x" "-1" "65536" "1.5"]]
    (test/is (thrown? Exception (cli/parse-number value 0 65535 :port))))
  (test/is (= 0 (cli/parse-number "0" 0 65535 :port))))

(test/deftest validates-modules
  (let [module (scaffold/create-module "acme" :app "api" :clj nil)]
    (doseq [change [{:platform :python} {:compiler :shadow} {:path "apps/a/b"}
                    {:path "libs/api"} {:version "../../oops"} {:deploy "sh deploy.sh"}
                    {:deploy []} {:lib 'unqualified} {:lib 'acme/..} {:deploy ["bash" ""]} {:main nil}]]
      (test/is (thrown? Exception (config/validate-module (merge module change)))))
    (test/is (= ["echo" "a b"] (:deploy (config/validate-module (assoc module :deploy ["echo" "a b"])))))))

(test/deftest selects-in-manifest-order
  (let [manifest {:modules [{:path "libs/b"} {:path "apps/a"}]}]
    (test/is (= (:modules manifest) (config/select-modules manifest nil)))
    (test/is (= (:modules manifest) (config/select-modules manifest ["apps/a" "libs/b" "apps/a"])))
    (test/is (= [{:path "apps/a"}] (config/select-modules manifest "apps/a")))
    (test/is (thrown? Exception (config/select-modules manifest ["apps/missing"])))
    (test/is (thrown? Exception (config/select-modules manifest :all)))))

(test/deftest contains-paths-including-symlinks
  (with-temp-directory
    (fn [root]
      (test/is (thrown? Exception (config/resolve-inside root "../outside")))
      (test/is (thrown? Exception (config/resolve-inside root "/tmp")))
      (test/is (thrown? Exception (config/resolve-inside root ".")))
      (test/is (= "app.clj" (.getName (config/resolve-inside root "src/app.clj"))))
      (java.nio.file.Files/createSymbolicLink (.toPath (io/file root "outside"))
                                              (.toPath (io/file "/tmp"))
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (test/is (thrown? Exception (config/resolve-inside root "outside/deleted"))))))

(test/deftest scaffolds-working-layout-and-preserves-files
  (with-temp-directory
    (fn [root]
      (scaffold/init-workspace root "acme" runtime)
      (let [{:keys [manifest]} (config/load-workspace root)]
        (test/is (= ["apps/example-app" "libs/example-lib"] (mapv :path (:modules manifest))))
        (test/is (= (.getCanonicalFile root) (config/find-root (io/file root "apps/example-app/src")))))
      (let [deps (config/read-edn (io/file root "apps/example-app/deps.edn"))]
        (test/is (= {:local/root "../../libs/example-lib"} (get-in deps [:deps 'acme/example-lib]))))
      (doseq [relative ["build.clj" "README.md" "bb.edn" "deps.edn"
                        "apps/example-app/resources" "libs/example-lib/test/acme/example_lib/core_test.clj"]]
        (test/is (.exists (io/file root relative))))
      (test/is (thrown? Exception (scaffold/init-workspace root "acme" runtime)))
      (spit (io/file root "keep.txt") "original")
      (test/is (thrown? Exception (scaffold/write-files root {"keep.txt" "replacement" "should-not-exist" "bad"})))
      (test/is (= "original" (slurp (io/file root "keep.txt"))))
      (test/is (not (.exists (io/file root "should-not-exist"))))
      (let [manifest (:manifest (config/load-workspace root))
            module (scaffold/add-module root manifest :app "web" :cljs :shadow)]
        (test/is (= :shadow (:compiler module)))
        (test/is (= 3 (count (:modules (:manifest (config/load-workspace root)))))))
      (test/is (thrown? Exception (scaffold/add-module root (:manifest (config/load-workspace root)) :app "web" :cljs :shadow))))))

(test/deftest scaffolds-library-only-workspace
  (with-temp-directory
    (fn [root]
      (scaffold/init-workspace root "acme" runtime :libs)
      (let [manifest (:manifest (config/load-workspace root))
            readme (slurp (io/file root "README.md"))]
        (test/is (= ["libs/example-lib"] (mapv :path (:modules manifest))))
        (test/is (every? #(= :lib (:kind %)) (:modules manifest)))
        (test/is (not (.exists (io/file root "apps"))))
        (test/is (str/includes? readme "bb mono build --module libs/example-lib"))
        (test/is (str/includes? readme "jar tf libs/example-lib/target/example-lib-0.1.0.jar"))
        (test/is (str/includes? readme "bb mono repl start --module libs/example-lib"))
        (test/is (not (str/includes? readme "apps/example-app")))
        (test/is (not (str/includes? readme "{{")))
        (let [added (scaffold/add-module root manifest :lib "shared" :cljs :shadow)]
          (test/is (= :lib (:kind added)))
          (test/is (= ["libs/example-lib" "libs/shared"]
                      (mapv :path (:modules (:manifest (config/load-workspace root))))))
          (test/is (not (.exists (io/file root "apps")))))
        (scaffold/add-module root (:manifest (config/load-workspace root)) :app "optional-app" :clj nil)
        (test/is (.isFile (io/file root "apps/optional-app/deps.edn")))))))

(test/deftest rejects-unknown-workspace-template-before-writing
  (with-temp-directory
    (fn [root]
      (let [destination (io/file root "untouched")]
        (test/is (thrown? Exception (scaffold/init-workspace destination "acme" runtime :unknown)))
        (test/is (not (.exists destination)))))))

(test/deftest generates-every-template
  (let [versions (scaffold/read-versions)]
    (doseq [kind [:app :lib] platform [:clj :cljs]
            compiler (if (= platform :clj) [nil] [:shadow :cljs])]
      (let [module (scaffold/create-module "acme" kind "sample" platform compiler)
            files (scaffold/module-files module runtime versions)
            base (:path module)
            deps (edn/read-string (get files (str base "/deps.edn")))]
        (test/is (seq files))
        (test/is (= ["src" "resources"] (:paths deps)))
        (test/is (every? #(not (str/includes? % "{{")) (vals files)))
        (when (= platform :cljs)
          (test/is (get-in deps [:aliases :cljs :extra-deps 'org.clojure/clojurescript]))
          (test/is (contains? files (str base (if (= :shadow compiler) "/shadow-cljs.edn" "/test.edn")))))
        (when (and (= platform :cljs) (= compiler :cljs))
          (test/is (false? (:browser-repl (edn/read-string (get files (str base "/build.edn")))))))
        (when (= platform :clj)
          (test/is (contains? files (str base "/dev/mono_test_runner.clj"))))))))

(test/deftest plans-builds-without-shell-interpolation
  (let [app (scaffold/create-module "acme" :app "api" :clj nil)
        lib (scaffold/create-module "acme" :lib "shared" :cljs :shadow)
        browser (scaffold/create-module "acme" :app "web" :cljs :shadow)]
    (test/is (= {:operation :uberjar} (plan/plan-task :build app)))
    (test/is (= {:operation :jar} (plan/plan-task :build lib)))
    (test/is (= "release" (nth (:command (plan/plan-task :build browser)) 4)))
    (test/is (= ["node" "target/tests.js"] (:after (plan/plan-task :test lib))))
    (test/is (= {:command ["echo" "$(touch oops)"]}
                (plan/plan-task :deploy (assoc app :deploy ["echo" "$(touch oops)"]))))
    (test/is (thrown? Exception (plan/plan-task :deploy app)))
    (test/is (thrown? Exception (plan/plan-task :unknown app)))))

(test/deftest discovers-cljs-tests
  (with-temp-directory
    (fn [root]
      (.mkdirs (io/file root "test/acme"))
      (spit (io/file root "test/acme/extra.cljs") "(ns acme.extra-test (:require [cljs.test :as t]))")
      (spit (io/file root "test/acme/common.cljc") "(ns acme.common-test #?(:cljs (:require [cljs.test :as t])))")
      (spit (io/file root "test/README.md") "not code")
      (test/is (= ['acme.common-test 'acme.extra-test] (cljs/find-test-namespaces root)))
      (cljs/write-test-runner root)
      (let [runner (slurp (io/file root "target/test-runner/mono_test_runner.cljs"))]
        (test/is (str/includes? runner "'acme.extra-test"))
        (test/is (str/includes? runner "exitCode"))))))

(test/deftest reads-one-edn-form
  (with-temp-directory
    (fn [root]
      (let [file (io/file root "bad.edn")]
        (spit file "{} {}")
        (test/is (thrown? Exception (config/read-edn file)))
        (spit file "#=(System/exit 0)")
        (test/is (thrown? Exception (config/read-edn file)))))))

(test/deftest workspace-lock-releases-after-exceptions
  (with-temp-directory
    (fn [root]
      (test/is (thrown? Exception (files/with-workspace-lock root #(throw (ex-info "expected" {})))))
      (test/is (= :released (files/with-workspace-lock root (constantly :released)))))))

(test/deftest stale-sessions-never-signal-pids
  (with-temp-directory
    (fn [root]
      (.mkdirs (io/file root "apps/api"))
      (let [module {:path "apps/api"}
            file (session/session-file root module ".edn")]
        (io/make-parents file)
        (spit file (pr-str {:pid 1 :token "old" :port 9999 :control-port 1}))
        (spit (io/file root "apps/api/.nrepl-port") "9999")
        (test/is (= :stale (:status (session/describe-session root module))))
        (test/is (= :stopped (:status (session/stop-session root module))))
        (test/is (not (.exists file)))
        (test/is (not (.exists (io/file root "apps/api/.nrepl-port"))))))))

(test/deftest explicit-git-runtime-coordinates
  (test/is (= {:git/url "https://example.com/mono.git" :git/sha (apply str (repeat 40 "a"))}
              (cli/runtime-coordinate {:runtime-url "https://example.com/mono.git"
                                       :runtime-sha (apply str (repeat 40 "a"))})))
  (test/is (thrown? Exception (cli/runtime-coordinate {:runtime-url "https://example.com/mono.git"})))
  (test/is (thrown? Exception (cli/runtime-coordinate {:runtime-url "https://example.com/mono.git" :runtime-sha "abc"}))))
