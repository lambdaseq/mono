(ns mono.plan
  (:require [mono.config :as config]))

(defn plan-task [task module]
  (case task
    :build (cond
             (= :lib (:kind module)) {:operation :jar}
             (= :clj (:platform module)) {:operation :uberjar}
             (= :shadow (:compiler module))
             {:command ["clojure" "-M:cljs" "-m" "shadow.cljs.devtools.cli" "release" "app"]}
             :else {:command ["clojure" "-M:cljs" "-m" "cljs.main" "-co" "build.edn"
                              "-c" (str (:main module))]})
    :test {:command (cond
                      (= :clj (:platform module)) ["clojure" "-M:test"]
                      (= :shadow (:compiler module))
                      ["clojure" "-M:cljs:test" "-m" "shadow.cljs.devtools.cli" "compile" "test"]
                      :else ["clojure" "-M:cljs:test" "-m" "cljs.main" "-co" "test.edn"
                             "-c" "mono-test-runner"])
           :after (when (= :cljs (:platform module)) ["node" "target/tests.js"])}
    :clean {:operation :clean}
    :deploy (if-let [command (:deploy module)] {:command command}
                    (config/fail "Configure :deploy for this module before deploying." {:module (:path module)}))
    (config/fail "Unknown build task." {:task task})))
