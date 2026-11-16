# {{name}}

A self-contained workspace. Module declarations and task implementations live in `build.clj`.
Requires Clojure CLI and JDK 17+. CLJS modules need JDK 21+ and Node.js.

```sh
clojure -T:build test
clojure -T:build build
{{artifact-command}}
clojure -T:build repl :modules :{{example-module}}
```

`repl` runs the selected module's foreground alias. Stop it with Ctrl-C.
Use `clojure -M:dev` for a JVM REPL spanning the workspace, or connect your IDE to a module's standard nREPL alias.

## Build functions

| Function | Behavior |
| --- | --- |
| `test` | Run module test aliases, then execute CLJS tests in Node. |
| `jar` | Build source jars for libraries. |
| `uber` | Build executable CLJ app jars. |
| `build` | Build source jars, CLJ uberjars, and CLJS browser releases. |
| `clean` | Delete module target directories. |
| `install` | Build and install libraries into the local Maven repository. |
| `deploy` | Build and publish explicitly selected libraries to Clojars. |
| `run` | Run one CLJ app from source. |
| `repl` | Run one module's foreground REPL alias. |
| `version` | Print the workspace version. |
| `ci` | Test and build selected modules. |

Select modules with `:modules :example-lib` or `:modules '[:example-lib]'`.
Without selection, tasks use all supported modules. `run` and `repl` need exactly one module; `deploy` requires explicit selection.
Execution is sequential without dependency scheduling. Source dependencies resolve through module `deps.edn` files.
Libraries remain valid in a workspace without `apps/`.

The workspace version lives in `project`. A module's `:version` overrides it; a command's `:version` overrides both.
Snapshots are rejected. Set `project` URL and license metadata before publishing:

```clojure
:license {:name "Your license name" :url "https://example.org/your-license"}
```

In library POMs, internal local dependencies are converted to registered Maven coordinates and versions in POMs.
Unregistered local dependencies and direct Git dependencies fail library packaging instead of disappearing from the POM.
Development dependency files stay unchanged. App uberjars include their local dependencies.
Use `:local-repo "/tmp/maven"` with `install` to choose a different Maven destination.
App deployment is project-specific: add an ordinary build function for your hosting provider.

## Add modules

Generate a module with mono's `lambdaseq/app` or `lambdaseq/lib` deps-new template, targeting `apps/NAME` or `libs/NAME`.
Register it in the `modules` map, for example:

```clojure
:shared {:kind :lib :dir "libs/shared" :platform :clj :lib {{prefix}}/shared}
```

The whole `modules` map is quoted, so its symbol values need no inner quote.
Use `:platform :cljs` and `:compiler :shadow` or `:compiler :cljs` for CLJS modules.
CLJ apps also set `:main` to the application's namespace symbol.
Add its local coordinates to root `:dev` for workspace REPL access. Consumers reference sibling libraries with relative `:local/root` dependencies.
For plain CLJS, add test namespaces to the module's `dev/.../test_runner.cljs`. Shadow discovers namespaces ending in `-test`.

These files belong to this project. Change or extend the functions directly; builds do not load mono or deps-new.
