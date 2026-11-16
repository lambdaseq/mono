(ns mono-test-runner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn read-test-namespace [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (let [form (read {:read-cond :allow :features #{:clj}} reader)]
      (when-not (= 'ns (first form))
        (throw (ex-info "Test file must begin with an ns form." {:file (str file)})))
      (second form))))

(defn -main [& _]
  (let [namespaces (->> (file-seq (io/file "test"))
                        (filter #(.isFile %))
                        (filter #(or (str/ends-with? (.getName %) ".clj")
                                     (str/ends-with? (.getName %) ".cljc")))
                        (sort-by str)
                        (mapv read-test-namespace))]
    (doseq [namespace namespaces] (require namespace))
    (let [result (apply test/run-tests namespaces)]
      (shutdown-agents)
      (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))))
