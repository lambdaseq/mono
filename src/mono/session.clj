(ns mono.session
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [mono.config :as config]
            [mono.files :as files]))

(defn session-file [root module suffix]
  (config/resolve-inside root (str ".mono/sessions/" (str/replace (:path module) "/" "__") suffix)))

(defn read-session [file]
  (when (.isFile (io/file file))
    (try (config/read-edn file) (catch Exception _ nil))))

(defn contact-session [state action]
  (when (and (integer? (:control-port state)) (string? (:token state)))
    (try
      (with-open [socket (java.net.Socket.)]
        (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int (:control-port state))) 500)
        (.setSoTimeout socket 1000)
        (let [writer (io/writer (.getOutputStream socket))
              reader (java.io.BufferedReader. (io/reader (.getInputStream socket)))]
          (.write writer (str (:token state) "\n" (name action) "\n"))
          (.flush writer)
          (= (.readLine reader) (str (:token state) " " (if (= :stop action) "stopping" "ready")))))
      (catch Exception _ false))))

(defn describe-session [root module]
  (let [state (read-session (session-file root module ".edn"))]
    (if (contact-session state :status)
      (assoc (dissoc state :token :control-port) :status :running)
      {:module (:path module) :status (if state :stale :stopped)})))

(defn remove-port-file [root module state]
  (let [port-file (config/resolve-inside (config/resolve-inside root (:path module)) ".nrepl-port")]
    (when (and (.isFile port-file) (= (str (:port state)) (str/trim (slurp port-file))))
      (.delete port-file))))

(defn stop-session [root module]
  (let [file (session-file root module ".edn")
        state (read-session file)]
    (if (contact-session state :stop)
      (let [deadline (+ (System/currentTimeMillis) 10000)]
        (loop []
          (when (contact-session state :status)
            (when (> (System/currentTimeMillis) deadline)
              (config/fail "REPL did not stop; inspect its log." {:module (:path module)}))
            (Thread/sleep 100)
            (recur))))
      (println "No healthy managed REPL:" (:path module)))
    (remove-port-file root module state)
    (.delete file)
    {:module (:path module) :status :stopped}))

(defn launch-session [root module port timeout]
  (let [directory (config/resolve-inside root (:path module))
        file (session-file root module ".edn")
        previous (read-session file)]
    (if (contact-session previous :status)
      (describe-session root module)
      (do
        (when previous (remove-port-file root module previous))
        (when (.exists (config/resolve-inside directory ".nrepl-port"))
          (config/fail "An unmanaged .nrepl-port exists; stop that REPL before starting mono." {:module (:path module)}))
        (.delete file)
        (io/make-parents file)
        (let [token (str (java.util.UUID/randomUUID))
              log (session-file root module ".log")
              mode (if (= :cljs (:platform module)) (:compiler module) :clj)
              arguments ["clojure" (if (= mode :clj) "-M:repl" "-M:cljs:repl")
                         "-m" "mono.repl-server" (str file) token (name mode) (str port)
                         (:path module) (name (:kind module))]
              process (-> (ProcessBuilder. ^java.util.List arguments)
                          (.directory directory)
                          (.redirectInput (io/file "/dev/null"))
                          (.redirectErrorStream true)
                          (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log))
                          (.start))
              deadline (+ (System/currentTimeMillis) (* 1000 timeout))]
          (try
            (loop []
              (let [state (read-session file)]
                (cond
                  (and (= token (:token state)) (contact-session state :status))
                  (describe-session root module)
                  (not (.isAlive process))
                  (config/fail "REPL exited during startup; inspect its log." {:log (str log)})
                  (> (System/currentTimeMillis) deadline)
                  (config/fail "REPL startup timed out; inspect its log or increase --timeout." {:log (str log)})
                  :else (do (Thread/sleep 100) (recur)))))
            (catch Exception e
              (.destroy process)
              (when-not (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
                (.destroyForcibly process))
              (let [state (read-session file)]
                (when (= token (:token state))
                  (remove-port-file root module state)
                  (.delete file)))
              (throw e))))))))

(defn run-repl [root modules action {:keys [port timeout] :or {port 0 timeout 120}}]
  (files/with-workspace-lock
    root
    (fn []
      (case action
        "start" (launch-session root (first modules) port timeout)
        "stop" (stop-session root (first modules))
        "status" (mapv #(describe-session root %) modules)
        (config/fail "REPL action must be start, stop, or status." {})))))
