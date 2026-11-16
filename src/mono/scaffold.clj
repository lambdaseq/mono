(ns mono.scaffold
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [mono.config :as config]))

(def runtime-lib 'mono/runtime)

(defn format-edn [value]
  (with-out-str (pprint/pprint value)))

(defn render [template values]
  (let [source (slurp (io/resource (str "mono/templates/" template)))]
    (reduce-kv (fn [text key value]
                 (str/replace text (str "{{" (name key) "}}") (str value)))
               source values)))

(defn read-versions []
  (config/read-edn (io/resource "mono/versions.edn")))

(defn create-module [prefix kind module-name platform compiler]
  (config/validate-name module-name)
  (when-not (#{:app :lib} kind) (config/fail "Kind must be app or lib." {}))
  (config/validate-module
   (cond-> {:path (str (if (= :app kind) "apps/" "libs/") module-name)
            :kind kind :platform platform :namespace (str prefix "." module-name)
            :main (symbol (str prefix "." module-name ".core"))
            :lib (symbol prefix module-name) :version "0.1.0"}
     (= :cljs platform) (assoc :compiler (or compiler :shadow))
     (and (= :clj platform) compiler) (assoc :compiler compiler))))

(defn module-deps [module runtime versions]
  (let [cljs? (= :cljs (:platform module))
        shadow? (= :shadow (:compiler module))]
    {:paths ["src" "resources"]
     :deps {'org.clojure/clojure {:mvn/version (:clojure versions)}}
     :aliases
     (cond->
      {:test (if cljs?
               {:extra-paths ["test" "target/test-runner"]}
               {:extra-paths ["test" "dev"] :main-opts ["-m" "mono-test-runner"]})
       :repl {:extra-paths ["test"] :extra-deps {runtime-lib runtime
                                                 'nrepl/nrepl {:mvn/version (:nrepl versions)}}}}
       (and cljs? (not shadow?)) (assoc-in [:repl :extra-deps 'cider/piggieback]
                                           {:mvn/version (:piggieback versions)})
       cljs? (assoc :cljs {:extra-deps
                           (cond-> {'org.clojure/clojurescript {:mvn/version (:clojurescript versions)}}
                             shadow? (assoc 'thheller/shadow-cljs {:mvn/version (:shadow-cljs versions)}))}))}))

(defn module-files [module runtime versions]
  (let [{:keys [path kind platform compiler namespace]} module
        module-name (last (str/split path #"/"))
        cljs? (= :cljs platform)
        shadow? (= :shadow compiler)
        values {:name module-name :path path :kind (name kind) :platform (name platform)
                :namespace namespace
                :init-script (if shadow? "" (str "<script>" (str/replace namespace "-" "_") ".core.init();</script>"))}
        extension (if cljs? "cljs" "clj")
        ns-path (-> namespace (str/replace "." "/") (str/replace "-" "_"))
        base {"deps.edn" (format-edn (module-deps module runtime versions))
              "README.md" (render "module-readme.md" values)
              (str "src/" ns-path "/core." extension) (render (str extension "-" (name kind) "." extension) values)
              (str "test/" ns-path "/core_test." extension) (render (str extension "-test." extension) values)
              "resources/.gitkeep" ""}
        files (cond-> base
                (not cljs?) (assoc "dev/mono_test_runner.clj" (render "test-runner.clj" values))
                (and cljs? (= :app kind))
                (assoc "resources/public/index.html" (render "index.html" values))
                (and cljs? shadow?)
                (assoc "shadow-cljs.edn"
                       (format-edn
                        {:deps {:aliases [:cljs :test]}
                         :http {:host "127.0.0.1" :port 0}
                         :builds
                         (cond-> {:test {:target :node-test :output-to "target/tests.js"
                                         :ns-regexp "-test$" :autorun false}}
                           (= :app kind)
                           (assoc :app {:target :browser :output-dir "target/public/js"
                                        :asset-path "/js"
                                        :modules {:main {:init-fn (symbol (str namespace ".core/init"))}}
                                        :devtools {:http-root "target/public" :http-port 8080
                                                   :http-host "127.0.0.1"}}))}))
                (and cljs? (not shadow?))
                (assoc "build.edn" (format-edn {:output-to "target/public/js/main.js"
                                                :output-dir "target/cljs" :optimizations :simple
                                                :preloads nil :browser-repl false})
                       "test.edn" (format-edn {:target :nodejs :output-to "target/tests.js"
                                               :output-dir "target/test-out" :optimizations :none
                                               :main 'mono-test-runner})))]
    (into {} (map (fn [[relative content]] [(str path "/" relative) content])) files)))

(defn workspace-files [project runtime versions example-module]
  {"deps.edn" (format-edn {:paths [] :deps {}
                           :aliases {:build {:extra-paths ["."]
                                             :extra-deps {runtime-lib runtime
                                                          'io.github.clojure/tools.build
                                                          {:mvn/version (:tools-build versions)}}
                                             :ns-default 'build}}})
   "bb.edn" (format-edn {:min-bb-version "1.12.0" :deps {runtime-lib runtime}
                         :tasks {'mono {:task '(apply (requiring-resolve 'mono.cli/-main) *command-line-args*)}}})
   "build.clj" (render "build.clj" {})
   ".gitignore" ".cpcache/\n.mono/\n.nrepl-port\n.shadow-cljs/\ntarget/\nnode_modules/\n"
   "README.md" (render "workspace-readme.md"
                       {:project project
                        :example-module (:path example-module)
                        :artifact-command (str (if (= :app (:kind example-module)) "java -jar " "jar tf ")
                                               (:path example-module) "/target/" (name (:lib example-module))
                                               "-" (:version example-module) ".jar")
                        :runtime-note (if (:local/root runtime)
                                        "Runtime uses a local development checkout. Regenerate with --runtime-url and --runtime-sha before sharing."
                                        "Runtime dependencies are pinned to the Git commit recorded in monorepo.edn, deps.edn, and bb.edn.")})})

(defn write-files [root files]
  ;; Preflight every destination so a collision cannot leave a half-generated module.
  (doseq [relative (keys files)]
    (when (.exists (config/resolve-inside root relative))
      (config/fail "Refusing to overwrite an existing file." {:path relative})))
  (doseq [[relative content] (sort-by key files)]
    (let [file (config/resolve-inside root relative)]
      (io/make-parents file)
      (java.nio.file.Files/write (.toPath file) (.getBytes ^String content java.nio.charset.StandardCharsets/UTF_8)
                                 (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE_NEW
                                                                       java.nio.file.StandardOpenOption/WRITE])))))

(defn save-manifest [root manifest]
  (let [file (io/file root "monorepo.edn")
        temp (java.nio.file.Files/createTempFile (.toPath (io/file root)) ".monorepo-" ".edn"
                                                 (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (.toFile temp) (format-edn manifest))
      (java.nio.file.Files/move temp (.toPath file)
                                (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                                      java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (finally (java.nio.file.Files/deleteIfExists temp)))))

(defn check-module-destination [root manifest module]
  (let [directory (config/resolve-inside root (:path module))]
    (when (or (.exists directory) (some #(= (:path module) (:path %)) (:modules manifest)))
      (config/fail "Module already exists." {:module (:path module)}))
    module))

(defn add-module [root manifest kind module-name platform compiler]
  (let [module (check-module-destination root manifest
                                         (create-module (:namespace manifest) kind module-name platform compiler))]
    (write-files root (module-files module (:runtime manifest) (read-versions)))
    (save-manifest root (update manifest :modules conj module))
    module))

(defn create-starter [prefix runtime versions template]
  (config/validate-workspace-template template)
  (let [lib (create-module prefix :lib "example-lib" :clj nil)
        library-files (module-files lib runtime versions)]
    (if (= :libs template)
      {:modules [lib] :files library-files}
      (let [app (create-module prefix :app "example-app" :clj nil)
            app-deps (assoc-in (module-deps app runtime versions) [:deps (:lib lib)]
                               {:local/root "../../libs/example-lib"})
            app-source (str (:path app) "/src/"
                            (-> (:namespace app) (str/replace "." "/") (str/replace "-" "_")) "/core.clj")]
        {:modules [app lib]
         :files (merge library-files (module-files app runtime versions)
                       {(str (:path app) "/deps.edn") (format-edn app-deps)
                        app-source (render "clj-example-app.clj"
                                           {:namespace (:namespace app) :library-namespace (:namespace lib)})})}))))

(defn check-destination [destination]
  (when (str/blank? (str destination))
    (config/fail "Project directory is required." {}))
  (let [root (.getCanonicalFile (io/file destination))]
    (when (and (.exists root) (or (not (.isDirectory root)) (seq (.list root))))
      (config/fail "Init requires an empty or absent directory." {:path (str root)}))
    root))

(defn init-workspace
  ([destination prefix runtime]
   (init-workspace destination prefix runtime :mixed))
  ([destination prefix runtime template]
   (config/validate-prefix prefix)
   (let [root (check-destination destination)
         versions (read-versions)
         {:keys [modules files]} (create-starter prefix runtime versions template)
         workspace (merge (workspace-files (.getName root) runtime versions (first modules))
                          files
                          {"monorepo.edn" (format-edn {:schema-version 1 :namespace prefix :runtime runtime
                                                       :modules modules})})]
     (.mkdirs root)
     (write-files root workspace)
     root)))
