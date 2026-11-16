(ns mono.build
  (:require [clojure.tools.build.api :as b]
            [mono.config :as config]
            [mono.cljs :as cljs]
            [mono.plan :as plan]
            [mono.process :as process]))

(defn build-jar [module]
  (let [basis (b/create-basis {:project "deps.edn"})
        class-dir "target/classes"
        artifact (str "target/" (name (:lib module)) "-" (:version module) ".jar")]
    (b/delete {:path class-dir})
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/write-pom {:class-dir class-dir :lib (:lib module) :version (:version module)
                  :basis basis :src-dirs ["src"]})
    (if (= :app (:kind module))
      (do
        (b/compile-clj {:basis basis :class-dir class-dir :ns-compile [(:main module)]})
        (b/uber {:class-dir class-dir :uber-file artifact :basis basis :main (:main module)}))
      (b/jar {:class-dir class-dir :jar-file artifact}))
    (println artifact)))

(defn execute-module [root module task task-plan]
  (let [directory (config/resolve-inside root (:path module))]
    ;; Check the output path separately: a target symlink must never escape its module.
    (config/resolve-inside directory "target")
    (b/with-project-root (str directory)
      (when (and (= :cljs (:platform module)) (not= :clean task))
        (when (and (= :test task) (= :cljs (:compiler module)))
          (cljs/write-test-runner directory))
        (when (and (= :build task) (= :app (:kind module)))
          (b/copy-dir {:src-dirs ["resources/public"] :target-dir "target/public"})))
      (case (:operation task-plan)
        :clean (b/delete {:path "target"})
        :jar (build-jar module)
        :uberjar (build-jar module)
        nil (process/run-command directory (:command task-plan))))
    (when-let [command (:after task-plan)] (process/run-command directory command))))

(defn run-task [task options]
  (let [{:keys [root manifest]} (config/load-workspace (or (:root options) "."))
        modules (config/select-modules manifest (:module options))]
    (when (and (= task :deploy) (not (seq (:module options))))
      (config/fail "Deploy requires explicit :module selection." {}))
    (let [plans (mapv #(plan/plan-task task %) modules)]
      (doseq [[module task-plan] (map vector modules plans)]
        (println (name task) (:path module))
        (execute-module root module task task-plan)))))
