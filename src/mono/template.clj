(ns mono.template
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def versions
  {:clojure "1.12.4" :build "0.10.11" :cljs "1.12.145" :shadow "3.4.11" :nrepl "1.3.0"})

(def test-runner
  {'io.github.cognitect-labs/test-runner
   {:git/tag "v0.5.1" :git/sha "dfb30dd6605cb6c0efc275e1df1736f6e90d4d73"}})

(defn format-edn [value]
  (str/trimr (with-out-str
               (binding [*print-namespace-maps* false]
                 (pprint/pprint value)))))

(defn validate-choice [value choices option]
  (when-not (contains? choices value)
    (throw (ex-info (str "Unsupported " (name option) "; choose " (str/join ", " (sort (map name choices))) ".")
                    {option value})))
  value)

(defn create-namespace [opts]
  (let [value (str (:top opts) "." (:main opts))]
    (when-not (re-matches #"[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)*" value)
      (throw (ex-info "Use lowercase Clojure project names, such as acme/my-project." {:namespace value})))
    value))

(defn create-module-deps [namespace kind platform compiler]
  (let [cljs-deps (cond-> {'org.clojure/clojurescript {:mvn/version (:cljs versions)}}
                    (= :shadow compiler) (assoc 'thheller/shadow-cljs {:mvn/version (:shadow versions)}))
        aliases (if (= :clj platform)
                  {:test {:extra-paths ["test"] :extra-deps test-runner
                          :main-opts ["-m" "cognitect.test-runner" "-d" "test"]}
                   :repl {:extra-paths ["test"] :extra-deps {'nrepl/nrepl {:mvn/version (:nrepl versions)}}
                          :main-opts ["-m" "nrepl.cmdline" "--bind" "127.0.0.1" "--port" "0"]}}
                  {:cljs {:extra-deps cljs-deps}
                   :test {:extra-paths ["test" "dev"] :extra-deps cljs-deps
                          :main-opts (if (= :shadow compiler)
                                       ["-m" "shadow.cljs.devtools.cli" "compile" "test"]
                                       ["-m" "cljs.main" "-co" "test.edn" "-c" (str namespace ".test-runner")])}
                   :repl {:extra-deps cljs-deps
                          :main-opts (if (= :shadow compiler)
                                       (into ["-m" "shadow.cljs.devtools.cli"]
                                             (if (= :app kind) ["watch" "app"] ["node-repl"]))
                                       ["-m" "cljs.main" "-re" (if (= :app kind) "browser" "node") "-r"])}})]
    {:paths ["src" "resources"] :deps {'org.clojure/clojure {:mvn/version (:clojure versions)}}
     :aliases aliases}))

(defn create-module-data [opts kind]
  (let [namespace (create-namespace opts)
        platform (validate-choice (get opts :platform :clj) #{:clj :cljs} :platform)
        compiler (when (= platform :cljs)
                   (validate-choice (get opts :compiler :shadow) #{:shadow :cljs} :compiler))]
    (when (and (= platform :clj) (contains? opts :compiler))
      (throw (ex-info ":compiler applies only to :platform :cljs." {})))
    {:namespace namespace :kind (name kind) :platform (name platform)
     :compiler (some-> compiler name)
     :init-script (if (= :cljs compiler) (str "<script>" (str/replace namespace "-" "_") ".core.init();</script>") "")
     :module-deps (format-edn (create-module-deps namespace kind platform compiler))}))

(defn configure-module-template [template {:keys [kind platform compiler]}]
  (let [extension (if (= "clj" platform) "clj" "cljs")]
    (assoc template :transform
           (cond-> [[(str "source-" platform) "src/{{namespace/file}}" {(str "core." extension) (str "core." extension)} :only]
                    [(str "source-" platform) "test/{{namespace/file}}" {(str "core_test." extension) (str "core_test." extension)} :only]]
             (= "cljs" platform) (conj [compiler "" {}])
             (and (= "cljs" platform) (= "cljs" compiler))
             (conj ["runner" "dev/{{namespace/file}}" {"test_runner.cljs" "test_runner.cljs"}])
             (and (= "cljs" platform) (= "app" kind))
             (conj ["public" "resources/public" {}])))))

(defn create-workspace-data [opts]
  (let [prefix (create-namespace opts)
        layout (validate-choice (get opts :layout :mixed) #{:mixed :libs} :layout)
        lib (symbol prefix "example-lib")
        library {:kind :lib :dir "libs/example-lib" :platform :clj :lib lib}
        application {:kind :app :dir "apps/example-app" :platform :clj
                     :lib (symbol prefix "example-app") :main (symbol (str prefix ".example-app.core"))}
        modules (cond-> (array-map :example-lib library) (= :mixed layout) (assoc :example-app application))
        build-deps {'io.github.clojure/tools.build {:mvn/version (:build versions)}
                    'slipset/deps-deploy {:mvn/version "0.2.2"}}
        deps {:paths [] :aliases {:build {:deps build-deps :ns-default 'build}
                                  :dev {:extra-deps (into {} (map (fn [[_ module]] [(:lib module) {:local/root (:dir module)}])) modules)}}}]
    {:kind "workspace" :prefix prefix :layout (name layout) :workspace-modules (str/replace (format-edn modules) "\n" "\n   ") :workspace-deps (format-edn deps)
     :lib-deps (format-edn (create-module-deps (str prefix ".example-lib") :lib :clj nil))
     :app-deps (format-edn (assoc-in (create-module-deps (str prefix ".example-app") :app :clj nil)
                                     [:deps lib] {:local/root "../../libs/example-lib"}))
     :example-module (if (= :mixed layout) "example-app" "example-lib")
     :artifact-command (if (= :mixed layout)
                         "java -jar apps/example-app/target/example-app-0.1.0.jar"
                         "jar tf libs/example-lib/target/example-lib-0.1.0.jar")}))

(defn create-template-data [opts]
  (case (validate-choice (get opts :kind :workspace) #{:workspace :app :lib} :kind)
    :workspace (create-workspace-data opts)
    :app (create-module-data opts :app)
    :lib (create-module-data opts :lib)))

(defn configure-workspace-template [template {:keys [layout]}]
  (assoc template :transform
         (cond-> [["lib" "libs/example-lib" {"deps.edn" "deps.edn" "README.md" "README.md"} :only]
                  ["lib" "libs/example-lib/src/{{prefix/file}}/example_lib" {"core.clj" "core.clj"} :only]
                  ["lib" "libs/example-lib/test/{{prefix/file}}/example_lib" {"core_test.clj" "core_test.clj"} :only]
                  ["lib/resources" "libs/example-lib/resources" {}]]
           (= "mixed" layout)
           (into [["app" "apps/example-app" {"deps.edn" "deps.edn" "README.md" "README.md"} :only]
                  ["app" "apps/example-app/src/{{prefix/file}}/example_app" {"core.clj" "core.clj"} :only]
                  ["app" "apps/example-app/test/{{prefix/file}}/example_app" {"core_test.clj" "core_test.clj"} :only]
                  ["app/resources" "apps/example-app/resources" {}]]))))

(defn configure-template [template {:keys [kind] :as data}]
  (let [configured (if (= "workspace" kind)
                     (configure-workspace-template template data)
                     (configure-module-template template data))]
    (-> configured
        (assoc :root (str kind "/root"))
        (update :transform
                (fn [transforms]
                  (mapv #(update % 0 (fn [source] (str kind "/" source))) transforms))))))
