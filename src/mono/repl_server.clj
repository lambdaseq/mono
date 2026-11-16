(ns mono.repl-server
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [mono.config :as config]))

(defn delete-owned-file [file content]
  (when (and (.isFile (io/file file)) (= content (str/trim (slurp file))))
    (.delete (io/file file))))

(defn choose-browser-port []
  (with-open [socket (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort socket)))

(defn start-shadow [kind]
  (let [load-config (requiring-resolve 'shadow.cljs.devtools.config/load-cljs-edn)
        start (requiring-resolve 'shadow.cljs.devtools.server/start!)
        settings (cond-> (-> (load-config) (assoc :nrepl false)
                             (assoc-in [:http :host] "127.0.0.1")
                             (assoc-in [:http :port] 0))
                   (= kind "app") (assoc-in [:builds :app :devtools :http-port] (choose-browser-port))
                   (= kind "app") (assoc-in [:builds :app :devtools :http-host] "127.0.0.1"))]
    (start settings)
    (when (= kind "app")
      (doseq [file (file-seq (io/file "resources/public")) :when (.isFile file)]
        (let [relative (.relativize (.toPath (io/file "resources/public")) (.toPath file))
              target (config/resolve-inside "." (str "target/public/" relative))]
          (io/make-parents target)
          (io/copy file target)))
      ((requiring-resolve 'shadow.cljs.devtools.api/watch) :app))
    (let [instance ((requiring-resolve 'shadow.cljs.devtools.server.runtime/get-instance))
          servers (:servers @(:dev-http instance))
          browser-url (some :http-url servers)]
      (when (and (= kind "app") (nil? browser-url))
        (config/fail "Shadow browser server failed to bind; inspect the log." {}))
      {:middleware (requiring-resolve 'shadow.cljs.devtools.server.nrepl/middleware)
       :browser-url browser-url})))

(defn start-nrepl [mode port kind]
  (let [start (requiring-resolve 'nrepl.server/start-server)
        default-handler (requiring-resolve 'nrepl.server/default-handler)
        shadow (when (= mode "shadow") (start-shadow kind))
        middleware (case mode
                     "shadow" (:middleware shadow)
                     "cljs" (requiring-resolve 'cider.piggieback/wrap-cljs-repl)
                     "clj" nil)]
    (assoc (start :bind "127.0.0.1" :port port
                  :handler (if middleware (default-handler middleware) (default-handler)))
           :browser-url (:browser-url shadow))))

(defn serve-control [server token]
  (loop []
    (let [stop? (try
                  (with-open [socket (.accept ^java.net.ServerSocket server)]
                    (.setSoTimeout socket 1000)
                    (let [reader (java.io.BufferedReader. (io/reader (.getInputStream socket)))
                          supplied (.readLine reader)
                          action (.readLine reader)
                          valid? (= token supplied)
                          stop? (and valid? (= action "stop"))
                          writer (io/writer (.getOutputStream socket))]
                      (.write writer (if valid? (str token " " (if stop? "stopping" "ready") "\n") "invalid\n"))
                      (.flush writer)
                      stop?))
                  (catch java.io.IOException _ false))]
      (if stop? (System/exit 0) (recur)))))

(defn write-state [file state]
  (let [temp (io/file (str file ".tmp"))]
    (spit temp (pr-str state))
    (java.nio.file.Files/move (.toPath temp) (.toPath (io/file file))
                              (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                                    java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))

(defn -main [file token mode port module kind]
  (try
    (let [nrepl (start-nrepl mode (parse-long port) kind)
          actual-port (:port nrepl)
          server (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
          state {:module module :token token :port actual-port :host "127.0.0.1"
                 :browser-url (:browser-url nrepl)
                 :pid (.pid (java.lang.ProcessHandle/current)) :mode (keyword mode)
                 :control-port (.getLocalPort server)
                 :log (str/replace file #"\.edn$" ".log")
                 :connect (case mode
                            "shadow" (if (= kind "app")
                                       "Evaluate (shadow.cljs.devtools.api/repl :app); open :browser-url to attach the runtime."
                                       "Evaluate (shadow.cljs.devtools.api/node-repl) to attach a Node runtime.")
                            "cljs" "Evaluate (cider.piggieback/cljs-repl (cljs.repl.browser/repl-env)); open its browser URL."
                            "Connect your IDE to the module's .nrepl-port.")}]
      (when (= mode "cljs") (require 'cljs.repl.browser))
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. ^Runnable
        (fn []
          (delete-owned-file ".nrepl-port" (str actual-port))
          (when (= token (:token (try (config/read-edn file) (catch Exception _ nil))))
            (.delete (io/file file))))))
      (spit ".nrepl-port" (str actual-port))
      (write-state file state)
      (println "mono REPL ready:" module "127.0.0.1" actual-port)
      (serve-control server token))
    (catch Exception e
      (binding [*out* *err*] (println (.getMessage e)) (.printStackTrace e))
      (System/exit 1))))
