(ns mono.integration-test
  (:require [babashka.http-client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [mono.cli :as cli]
            [mono.config :as config]
            [mono.core-test :as support]
            [mono.session :as session]))

(defn capture-command [directory arguments]
  (let [process (-> (ProcessBuilder. ^java.util.List (mapv str arguments))
                    (.directory (io/file directory)) (.redirectErrorStream true) (.start))
        output (slurp (.getInputStream process))]
    {:exit (.waitFor process) :output output}))

(defn check-command [directory arguments]
  (println "Check:" (str/join " " arguments))
  (let [{:keys [exit output] :as result} (capture-command directory arguments)]
    (when-not (zero? exit)
      (throw (ex-info (str "Integration command failed:\n" output) {:arguments arguments :exit exit})))
    result))

(defn cleanup-sessions [root]
  (when (.exists (io/file root "monorepo.edn"))
    (doseq [module (:modules (:manifest (config/load-workspace root)))]
      (session/stop-session root module))))

(defn with-workspace
  ([action] (with-workspace [] action))
  ([init-options action]
   (support/with-temp-directory
     (fn [root]
       (check-command (cli/source-root)
                      (into ["bb" "bin/mono" "init" (str root) "--namespace" "acceptance"] init-options))
       (try (action root) (finally (cleanup-sessions root)))))))

(defn replace-text [file old replacement]
  (spit file (str/replace (slurp file) old replacement)))

(test/deftest jvm-workflow
  (with-workspace
    (fn [root]
      (test/is (str/includes? (:output (check-command root ["bb" "mono" "list"])) "libs/example-lib"))
      (check-command root ["bb" "mono" "test"])
      (check-command root ["bb" "mono" "build"])
      (test/is (= "Hello, world!\n"
                  (:output (check-command root ["java" "-jar" "apps/example-app/target/example-app-0.1.0.jar"]))))
      (test/is (.isFile (io/file root "libs/example-lib/target/example-lib-0.1.0.jar")))
      (let [nested (io/file root "apps/example-app/src")
            mono (str (io/file (cli/source-root) "bin/mono"))]
        (test/is (str/includes? (:output (check-command nested ["bb" mono "list"])) "apps/example-app")))
      (let [module {:path "apps/example-app"}]
        (check-command root ["bb" "mono" "repl" "start" "--module" (:path module)])
        (let [first-state (session/read-session (session/session-file root module ".edn"))]
          (check-command root ["bb" "mono" "repl" "start" "--module" (:path module)])
          (test/is (= first-state (session/read-session (session/session-file root module ".edn"))))
          (test/is (= :running (:status (session/describe-session root module)))))
        (check-command root ["bb" "mono" "repl" "stop" "--module" (:path module)])
        (test/is (= :stopped (:status (session/describe-session root module))))
        (with-open [occupied (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
          (test/is (pos? (:exit (capture-command root ["bb" "mono" "repl" "start" "--module" (:path module)
                                                       "--port" (str (.getLocalPort occupied)) "--timeout" "20"])))))
        (test/is (not (.exists (io/file root "apps/example-app/.nrepl-port")))))
      (let [file (io/file root "apps/example-app/test/acceptance/example_app/core_test.clj")]
        (replace-text file "Hello, world!" "wrong")
        (test/is (pos? (:exit (capture-command root ["bb" "mono" "test" "--module" "apps/example-app"]))))
        (replace-text file "wrong" "Hello, world!"))
      (let [file (io/file root "monorepo.edn")
            manifest (config/read-edn file)]
        (spit file (pr-str (assoc-in manifest [:modules 0 :deploy]
                                     ["clojure" "-M" "-e" "(spit \"deployed.txt\" \"ok\")"])))
        (check-command root ["bb" "mono" "deploy" "--module" "apps/example-app"])
        (check-command root ["bb" "mono" "deploy" "--module" "apps/example-app"])
        (test/is (= "ok" (slurp (io/file root "apps/example-app/deployed.txt"))))
        (test/is (pos? (:exit (capture-command root ["bb" "mono" "deploy"])))))
      (check-command root ["clojure" "-T:build" "test"])
      (check-command root ["bb" "mono" "clean" "--module" "apps/example-app"])
      (test/is (not (.exists (io/file root "apps/example-app/target"))))
      (test/is (.exists (io/file root "libs/example-lib/target"))))))

(test/deftest library-workflow
  (with-workspace
    ["--template" "libs"]
    (fn [root]
      (test/is (not (.exists (io/file root "apps"))))
      (test/is (= "libs/example-lib clj lib\n"
                  (:output (check-command root ["bb" "mono" "list"]))))
      (check-command root ["bb" "mono" "test"])
      (check-command root ["clojure" "-T:build" "build"])
      (let [contents (:output (check-command root ["jar" "tf" "libs/example-lib/target/example-lib-0.1.0.jar"]))]
        (test/is (str/includes? contents "acceptance/example_lib/core.clj")))
      (try
        (check-command root ["bb" "mono" "repl" "start" "--module" "libs/example-lib"])
        (test/is (= :running (:status (session/describe-session root {:path "libs/example-lib"}))))
        (finally (check-command root ["bb" "mono" "repl" "stop" "--module" "libs/example-lib"])))
      (check-command root ["bb" "mono" "add" "lib" "shared" "--platform" "cljs"])
      (test/is (every? #(= :lib (:kind %)) (:modules (:manifest (config/load-workspace root)))))
      (check-command root ["bb" "mono" "clean"])
      (test/is (not (.exists (io/file root "libs/example-lib/target"))))
      (test/is (not (.exists (io/file root "apps")))))))

(test/deftest cljs-workflow
  (with-workspace
    (fn [root]
      (doseq [[kind module compiler] [["app" "web" "shadow"] ["app" "plain-web" "cljs"]
                                      ["lib" "shared" "shadow"] ["lib" "plain-shared" "cljs"]]]
        (check-command root ["bb" "mono" "add" kind module "--platform" "cljs" "--compiler" compiler]))
      (let [selected ["--module" "apps/web" "--module" "apps/plain-web"
                      "--module" "libs/shared" "--module" "libs/plain-shared"]]
        (check-command root (into ["bb" "mono" "test"] selected))
        (check-command root (into ["bb" "mono" "build"] selected)))
      (doseq [module ["web" "plain-web"]]
        (test/is (.isFile (io/file root "apps" module "target/public/index.html"))))
      (doseq [module ["shared" "plain-shared"]]
        (test/is (.isFile (io/file root "libs" module "target" (str module "-0.1.0.jar")))))
     ;; Add a second namespace to prove plain-compiler discovery is not template-specific.
      (spit (io/file root "apps/plain-web/test/acceptance/plain_web/extra_test.cljs")
            "(ns acceptance.plain-web.extra-test (:require [cljs.test :as t :include-macros true]))\n(t/deftest fails (t/is false))\n")
      (test/is (pos? (:exit (capture-command root ["bb" "mono" "test" "--module" "apps/plain-web"]))))
      (let [file (io/file root "apps/web/test/acceptance/web/core_test.cljs")]
        (replace-text file "Hello, world!" "wrong")
        (test/is (pos? (:exit (capture-command root ["bb" "mono" "test" "--module" "apps/web"]))))
        (replace-text file "wrong" "Hello, world!")))))

(test/deftest cljs-repl-workflow
  (with-workspace
    (fn [root]
      (doseq [[kind name compiler] [["app" "web" "shadow"] ["app" "plain-web" "cljs"] ["lib" "shared" "shadow"]]]
        (check-command root ["bb" "mono" "add" kind name "--platform" "cljs" "--compiler" compiler]))
      (doseq [path ["apps/web" "apps/plain-web" "libs/shared"]]
        (let [module {:path path}]
          (try
            (check-command root ["bb" "mono" "repl" "start" "--module" path])
            (let [state (session/read-session (session/session-file root module ".edn"))]
              (test/is (= :running (:status (session/describe-session root module))))
              (when (= path "apps/web")
                (test/is (str/includes? (:body (http/get (:browser-url state) {:headers {"accept" "text/html"}})) "<main id=\"app\">"))))
            (finally (check-command root ["bb" "mono" "repl" "stop" "--module" path]))))))))

(defn -main [& names]
  (let [result (if (seq names)
                 (binding [test/*report-counters* (ref test/*initial-report-counters*)]
                   (test/test-vars (mapv #(or (ns-resolve 'mono.integration-test (symbol %))
                                              (throw (ex-info "Unknown integration test." {:name %}))) names))
                   (let [result @test/*report-counters*]
                     (test/do-report (assoc result :type :summary))
                     result))
                 (test/run-tests 'mono.integration-test))]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
