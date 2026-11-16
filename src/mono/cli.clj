(ns mono.cli
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [mono.config :as config]
            [mono.files :as files]
            [mono.process :as process]
            [mono.scaffold :as scaffold]
            [mono.session :as session]))

(def init-usage
  "mono init DIRECTORY --namespace PREFIX [--template mixed|libs] [--runtime-url URL --runtime-sha SHA]")

(def add-usage
  "mono add app|lib NAME [--platform clj|cljs] [--compiler shadow|cljs]")

(def help
  (str "mono: generate and operate Clojure monorepos

  mono init                 Interactive wizard (terminal only)
  " init-usage "
  mono add app|lib          Interactive module wizard (terminal only)
  " add-usage "
  mono list
  mono build|test|clean [--module PATH ...]
  mono deploy --module PATH
  mono repl start|stop --module PATH [--port PORT] [--timeout SECONDS]
  mono repl status [--module PATH ...]

Inside a workspace: bb mono COMMAND. Build/test/clean default to all modules.
Init defaults to mixed; libs generates a library-only workspace.
Module paths include libs/NAME or apps/NAME.
CLJS defaults to Shadow CLJS. REPLs require JDK 17+, Clojure CLI, and Babashka."))

(defn parse-options [arguments allowed]
  (loop [remaining arguments options {} positional []]
    (if-let [argument (first remaining)]
      (if (str/starts-with? argument "--")
        (let [key (keyword (subs argument 2)) value (second remaining)]
          (when-not (contains? allowed key) (config/fail "Unknown option." {:option argument}))
          (when (or (nil? value) (str/starts-with? value "--"))
            (config/fail "Option requires a value." {:option argument}))
          (when (and (not= key :module) (contains? options key))
            (config/fail "Option supplied more than once." {:option argument}))
          (recur (nnext remaining) (if (= key :module) (update options key (fnil conj []) value)
                                       (assoc options key value)) positional))
        (recur (next remaining) options (conj positional argument)))
      {:options options :positional positional})))

(defn require-arity [values expected]
  (when-not (= expected (count values))
    (config/fail "Wrong number of arguments; run mono --help." {:arguments values})))

(defn source-root []
  (let [resource (io/resource "mono/cli.clj")]
    (when (and resource (= "file" (.getProtocol resource)))
      (str (.getCanonicalFile (io/file (io/file (.toURI resource)) "../../.."))))))

(defn git-output [root & args]
  (try
    (let [process (-> (ProcessBuilder. ^java.util.List (into ["git" "-C" root] args))
                      (.redirectError (io/file "/dev/null")) (.start))
          output (str/trim (slurp (.getInputStream process)))]
      (when (zero? (.waitFor process)) output))
    (catch Exception _ nil)))

(defn runtime-coordinate [{:keys [runtime-url runtime-sha]}]
  (when (not= (boolean runtime-url) (boolean runtime-sha))
    (config/fail "Supply --runtime-url and --runtime-sha together." {}))
  (let [root (source-root)
        url (or runtime-url (when root (git-output root "remote" "get-url" "origin")))
        sha (or runtime-sha (when root (git-output root "rev-parse" "HEAD")))]
    (cond
      (and url sha (re-matches #"[a-f0-9]{40}" sha)) {:git/url url :git/sha sha}
      runtime-sha (config/fail "Runtime SHA must be a full lowercase 40-character Git commit." {})
      root {:local/root root}
      :else (config/fail "Cannot locate runtime source. Supply --runtime-url and --runtime-sha." {}))))

(defn delegate-workspace [root arguments]
  (when-not (= (str (.getCanonicalFile root)) (System/getenv "MONO_WORKSPACE_RUNTIME"))
    (let [builder (-> (ProcessBuilder. ^java.util.List
                       (into ["bb" "--config" (str (io/file root "bb.edn")) "mono"] arguments))
                      (.directory root) (.inheritIO))]
      (.put (.environment builder) "MONO_WORKSPACE_RUNTIME" (str (.getCanonicalFile root)))
      (let [exit (.waitFor (.start builder))]
        (when-not (zero? exit) (config/fail "Workspace command failed." {:exit exit}))
        true))))

(defn parse-number [value minimum maximum option]
  (let [number (parse-long value)]
    (when-not (and number (<= minimum number maximum))
      (config/fail "Option is outside its allowed integer range." {:option option :min minimum :max maximum}))
    number))

(defn has-terminal? []
  (some? (System/console)))

(defn prompt-answer [label parse-answer]
  (loop []
    (print (str label ": "))
    (flush)
    (let [answer (read-line)]
      (when (nil? answer)
        (config/fail "Generation cancelled: input closed." {}))
      (let [result (try {:value (parse-answer (str/trim answer))}
                        (catch Exception e {:error (.getMessage e)}))]
        (if-let [error (:error result)]
          (do (println error) (recur))
          (:value result))))))

(defn prompt-add [root manifest kind]
  (println (str "Create " (if (= kind :app) "an app" "a library") ". Press Ctrl-D or Ctrl-C to cancel."))
  (let [module-name (prompt-answer (if (= kind :app) "App name" "Library name")
                                   (fn [value]
                                     (scaffold/check-module-destination
                                      root manifest (scaffold/create-module (:namespace manifest) kind value :clj nil))
                                     value))
        platform (prompt-answer "Platform: clj or cljs [clj]"
                                #(config/validate-platform (keyword (if (str/blank? %) "clj" %))))
        compiler (when (= platform :cljs)
                   (prompt-answer "Use shadow-cljs? [Y/n]"
                                  #(case (str/lower-case %)
                                     ("" "y" "yes") :shadow
                                     ("n" "no") :cljs
                                     (config/fail "Answer yes or no." {}))))]
    {:module-name module-name :platform platform :compiler compiler}))

(defn run-add [root manifest positional options]
  (let [kind (keyword (first positional))
        wizard? (and (= 1 (count positional)) (empty? options))]
    (when (or (not (#{:app :lib} kind))
              (if wizard? (not (has-terminal?)) (not= 2 (count positional))))
      (config/fail (str "Usage: " add-usage "\nRun mono add app or mono add lib in a terminal to use the wizard.") {}))
    (let [{:keys [module-name platform compiler]}
          (if wizard? (prompt-add root manifest kind)
              {:module-name (second positional) :platform (keyword (or (:platform options) "clj"))
               :compiler (some-> (:compiler options) keyword)})]
      (files/with-workspace-lock
        root
        (fn []
          (let [fresh (:manifest (config/load-workspace root))
                module (scaffold/add-module root fresh kind module-name platform compiler)]
            (println "Created" (:path module))))))))

(defn run-workspace-command [command arguments]
  (let [allowed (case command
                  "add" #{:platform :compiler}
                  "list" #{}
                  "repl" #{:module :port :timeout}
                  #{:module})
        {:keys [options positional]} (parse-options arguments allowed)
        {:keys [root manifest]} (config/load-workspace ".")]
    (case command
      "list" (do (require-arity positional 0)
                 (doseq [module (:modules manifest)]
                   (println (:path module) (name (:platform module)) (name (:kind module)))))
      "add" (run-add root manifest positional options)
      "repl" (let [action (first positional)
                   modules (config/select-modules manifest (:module options))]
               (require-arity positional 1)
               (when-not (#{"start" "stop" "status"} action)
                 (config/fail "REPL action must be start, stop, or status." {}))
               (when (and (not= action "status")
                          (or (not= 1 (count (:module options))) (not= 1 (count modules))))
                 (config/fail "REPL start/stop requires exactly one --module." {}))
               (when (and (not= action "start") (or (:port options) (:timeout options)))
                 (config/fail "--port and --timeout apply only to repl start." {}))
               (pprint/pprint (session/run-repl root modules action
                                                {:port (parse-number (or (:port options) "0") 0 65535 :port)
                                                 :timeout (parse-number (or (:timeout options) "120") 1 600 :timeout)})))
      (do
        (require-arity positional 0)
        (when (and (= command "deploy") (not (seq (:module options))))
          (config/fail "Deploy requires explicit --module selection." {}))
        (let [modules (config/select-modules manifest (:module options))]
          (process/run-command root ["clojure" "-T:build" command
                                     ":module" (pr-str (mapv :path modules))]))))))

(defn prompt-init []
  (println "Create a workspace. Press Ctrl-D or Ctrl-C to cancel.")
  (let [directory (prompt-answer "Project directory" scaffold/check-destination)
        prefix (prompt-answer "Namespace prefix" config/validate-prefix)
        template (prompt-answer "Starter: mixed (app + library) or libs (libraries only) [mixed]"
                                #(config/validate-workspace-template
                                  (keyword (if (str/blank? %) "mixed" %))))]
    {:directory directory :namespace prefix :template template}))

(defn run-init [arguments]
  (let [{:keys [options positional]} (parse-options arguments #{:namespace :runtime-url :runtime-sha :template})
        wizard? (empty? arguments)]
    (when (if wizard? (not (has-terminal?))
              (or (not= 1 (count positional)) (str/blank? (:namespace options))))
      (config/fail (str "Usage: " init-usage "\nRun mono init in a terminal to use the wizard.") {}))
    (let [runtime (runtime-coordinate options)
          {:keys [directory namespace template]}
          (if wizard? (prompt-init)
              {:directory (first positional) :namespace (:namespace options)
               :template (keyword (or (:template options) "mixed"))})
          root (scaffold/init-workspace directory namespace runtime template)]
      (println "Created" (str root))
      (when (:local/root runtime)
        (println "Runtime uses this local checkout. Supply release Git coordinates before sharing.")))))

(defn run-cli [arguments]
  (let [[command & rest-args] arguments]
    (cond
      (or (nil? command) (#{"help" "--help" "-h"} command)) (println help)
      (= command "init") (run-init rest-args)
      (not (#{"add" "list" "build" "test" "clean" "deploy" "repl"} command))
      (config/fail "Unknown command; run mono --help." {:command command})
      :else
      (let [root (or (config/find-root ".") (config/fail "No monorepo.edn found above this directory." {}))]
        (when-not (delegate-workspace root arguments)
          (run-workspace-command command rest-args))))))

(defn -main [& arguments]
  (try
    (run-cli arguments)
    (catch Exception e
      (binding [*out* *err*]
        (println "mono:" (.getMessage e))
        (when (seq (ex-data e)) (pprint/pprint (ex-data e))))
      (System/exit (or (:exit (ex-data e)) 1)))))
