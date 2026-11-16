(ns mono.cljs
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [mono.config :as config]))

(defn read-test-namespace [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (let [form (read {:read-cond :allow :features #{:cljs}} reader)]
      (when-not (= 'ns (first form))
        (config/fail "Test file must begin with an ns form." {:file (str file)}))
      (second form))))

(defn find-test-namespaces [directory]
  (->> (file-seq (io/file directory "test"))
       (filter #(.isFile %))
       (filter #(or (str/ends-with? (.getName %) ".cljs")
                    (str/ends-with? (.getName %) ".cljc")))
       (sort-by str)
       (mapv read-test-namespace)))

(defn render-test-runner [namespaces]
  (str "(ns mono-test-runner\n  (:require [cljs.test :as test :include-macros true]\n"
       (str/join "\n" (map #(str "            [" % "]") namespaces)) "))\n\n"
       "(defmethod test/report [::test/default :end-run-tests] [result]\n"
       "  (set! (.-exitCode js/process) (if (test/successful? result) 0 1)))\n\n"
       "(defn -main [& _]\n  (test/run-tests "
       (str/join " " (map #(str "'" %) namespaces)) "))\n\n"
       "(set! *main-cli-fn* -main)\n"))

(defn write-test-runner [directory]
  (let [file (config/resolve-inside directory "target/test-runner/mono_test_runner.cljs")]
    (io/make-parents file)
    (spit file (render-test-runner (find-test-namespaces directory)))))
