(ns {{namespace}}.test-runner
  (:require [cljs.test :as test :include-macros true]
            [{{namespace}}.core-test]))

(defmethod test/report [::test/default :end-run-tests] [result]
  (set! (.-exitCode js/process) (if (test/successful? result) 0 1)))

(defn -main [& _]
  (test/run-tests '{{namespace}}.core-test))

(set! *main-cli-fn* -main)
