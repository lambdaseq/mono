(ns mono.test-runner
  (:require [clojure.test :as test]
            [mono.core-test]))

(defn run [_]
  (let [result (test/run-tests 'mono.core-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "Tests failed." result)))))

(defn -main [& _]
  (run {})
  (shutdown-agents))
