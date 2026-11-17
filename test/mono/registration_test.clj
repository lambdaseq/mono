(ns mono.registration-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [mono.registration :as registration]
            [mono.template-test :as support]
            [rewrite-clj.zip :as z]))

(defn read-modules [workspace]
  (-> (io/file workspace "build.clj") slurp registration/find-modules-form registration/read-modules-map z/sexpr))

(defn save-modules! [workspace modules]
  (let [file (io/file workspace "build.clj")]
    (spit file (-> (slurp file) registration/find-modules-form registration/read-modules-map
                   (z/replace modules) z/root-string))))

(test/deftest registers-all-module-types-in-both-workspace-layouts
  (support/with-directory
    (fn [root]
      (doseq [layout [:mixed :libs]]
        (let [workspace (support/generate root (name layout) {:template 'io.github.lambdaseq/mono :name 'acme/demo :layout layout})
              build-file (io/file workspace "build.clj")
              deps-file (io/file workspace "deps.edn")
              tail "\n;; Keep this helper.\n(defn custom-task [opts] opts)\n"]
          (spit build-file (str ";; Keep build header.\n"
                               (str/replace-first (slurp build-file) ":example-lib" ";; Keep module comment.\n   :example-lib") tail))
          (spit deps-file (str ";; Keep dependency header.\n"
                              (str/replace-first (slurp deps-file) ":dev" ";; Keep alias comment.\n   :dev")
                              "\n;; Keep dependency footer.\n"))
          (doseq [kind [:app :lib]
                  [platform compiler] [[:clj nil] [:cljs :shadow] [:cljs :cljs]]]
            (let [id (keyword (str (name kind) "-" (name platform) "-" (or (some-> compiler name) "default")))
                  directory (str (name kind) "s/" (name id))
                  lib (symbol "acme.demo" (name id))
                  namespace (str "acme.demo." (name id))
                  module (cond-> {:kind kind :dir directory :platform platform :lib lib}
                           (= :app kind) (assoc :main (symbol (str namespace ".core")))
                           compiler (assoc :compiler compiler))]
              (support/generate workspace directory (cond-> {:template 'io.github.lambdaseq/mono :kind kind :name lib :platform platform}
                                                       compiler (assoc :compiler compiler)))
              (test/is (= module (get (read-modules workspace) id)))
              (test/is (= {:local/root directory}
                          (get-in (support/read-edn deps-file) [:aliases :dev :extra-deps lib])))))
          (test/is (= (+ (if (= :mixed layout) 2 1) 6) (count (read-modules workspace))))
          (test/is (str/starts-with? (slurp build-file) ";; Keep build header.\n"))
          (test/is (str/includes? (slurp build-file) ";; Keep module comment.\n   :example-lib"))
          (test/is (str/ends-with? (slurp build-file) tail))
          (test/is (str/starts-with? (slurp deps-file) ";; Keep dependency header.\n"))
          (test/is (str/includes? (slurp deps-file) ";; Keep alias comment.\n   :dev"))
          (test/is (str/ends-with? (slurp deps-file) "\n;; Keep dependency footer.\n")))))))

(test/deftest reruns-preserve-custom-module-settings-and-file-content
  (support/with-directory
    (fn [root]
      (let [workspace (support/generate root "workspace" {:template 'io.github.lambdaseq/mono :name 'acme/demo})
            opts {:template 'io.github.lambdaseq/mono :kind :lib :name 'acme/shared}
            build-file (io/file workspace "build.clj")
            deps-file (io/file workspace "deps.edn")]
        (support/generate workspace "libs/shared" opts)
        (save-modules! workspace (-> (read-modules workspace)
                                    (assoc-in [:shared :version] "0.2.0")
                                    (assoc-in [:shared :dir] "./libs/shared")))
        (spit deps-file (pr-str (assoc-in (support/read-edn deps-file) [:aliases :dev :extra-deps 'acme/shared]
                                        {:local/root "./libs/shared" :exclusions ['unused/lib]})))
        (let [build (slurp build-file)
              deps (slurp deps-file)]
          (support/generate workspace "libs/shared" (assoc opts :overwrite true))
          (test/is (= build (slurp build-file)))
          (test/is (= deps (slurp deps-file))))))))

(test/deftest detects-registration-conflicts-before-generating-files
  (support/with-directory
    (fn [root]
      (doseq [conflict [:id :coordinates :directory :dependency :dynamic-build]]
        (let [workspace (support/generate root (name conflict) {:template 'io.github.lambdaseq/mono :name 'acme/demo})
              build-file (io/file workspace "build.clj")
              deps-file (io/file workspace "deps.edn")
              target "libs/conflicting"
              module {:kind :lib :dir "libs/other" :platform :clj :lib 'other/shared}]
          (case conflict
            :id (save-modules! workspace (assoc (read-modules workspace) :shared module))
            :coordinates (save-modules! workspace (assoc (read-modules workspace) :other (assoc module :lib 'acme/shared)))
            :directory (save-modules! workspace (assoc (read-modules workspace) :other (assoc module :dir target)))
            :dependency (spit deps-file (pr-str (assoc-in (support/read-edn deps-file)
                                                        [:aliases :dev :extra-deps 'acme/shared] {:mvn/version "1.0.0"})))
            :dynamic-build (spit build-file "(def modules (merge {} {}))\n"))
          (let [build (slurp build-file)
                deps (slurp deps-file)]
            (test/is (thrown? Exception (support/generate workspace target {:template 'io.github.lambdaseq/mono :kind :lib :name 'acme/shared})))
            (test/is (not (.exists (io/file workspace target))))
            (test/is (= build (slurp build-file)))
            (test/is (= deps (slurp deps-file)))))))))

(test/deftest registration-can-be-disabled
  (support/with-directory
    (fn [root]
      (let [workspace (support/generate root "workspace" {:template 'io.github.lambdaseq/mono :name 'acme/demo})
            build-file (io/file workspace "build.clj")
            deps-file (io/file workspace "deps.edn")
            build "(def modules (merge {} {}))\n"
            _ (spit build-file build)
            deps (slurp deps-file)
            module (support/generate workspace "custom/shared" {:template 'io.github.lambdaseq/mono :kind :lib :name 'acme/shared :register false})]
        (test/is (.isFile (io/file module "deps.edn")))
        (test/is (= build (slurp build-file)))
        (test/is (= deps (slurp deps-file)))))))

(test/deftest adds-missing-dev-alias-without-changing-other-aliases
  (support/with-directory
    (fn [root]
      (let [workspace (support/generate root "workspace" {:template 'io.github.lambdaseq/mono :name 'acme/demo})
            deps-file (io/file workspace "deps.edn")
            deps (-> (support/read-edn deps-file) (update :aliases dissoc :dev)
                     (assoc-in [:aliases :custom] {:jvm-opts ["-Dcustom=true"]}))]
        (spit deps-file (pr-str deps))
        (support/generate workspace "services/shared" {:template 'io.github.lambdaseq/mono :kind :lib :name 'acme/shared})
        (test/is (= {:local/root "services/shared"}
                    (get-in (support/read-edn deps-file) [:aliases :dev :extra-deps 'acme/shared])))
        (test/is (= (:aliases deps) (dissoc (:aliases (support/read-edn deps-file)) :dev)))))))

(test/deftest stale-plans-do-not-overwrite-workspace-edits
  (support/with-directory
    (fn [root]
      (let [workspace (support/generate root "workspace" {:template 'io.github.lambdaseq/mono :name 'acme/demo})
            build-file (io/file workspace "build.clj")
            deps-file (io/file workspace "deps.edn")
            plan (registration/prepare-registration! {:kind "lib" :platform "clj" :top "acme" :main "shared"
                                                       :namespace "acme.shared" :target-dir (str (io/file workspace "libs/shared"))})
            build (slurp build-file)
            deps (str (slurp deps-file) "\n;; Edited while generating.\n")]
        (spit deps-file deps)
        (test/is (thrown? Exception (registration/register-module! nil {:registration plan})))
        (test/is (= build (slurp build-file)))
        (test/is (= deps (slurp deps-file)))))))
