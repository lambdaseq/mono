# mono

A deps-new template for self-contained Clojure workspaces with applications and libraries.
Generated projects own their `build.clj`, module declarations, and dependencies. Extend those functions directly.

## Quickstart

### Prerequisites

Use macOS or Linux with these tools:

| Tool | Version |
| --- | --- |
| Clojure CLI | 1.12.6.1673 (tested) |
| Git | 2.x |
| JDK | 17+ for CLJ; 21+ for CLJS |
| Node.js | 24.x for CLJS tests and Node REPLs |

### Setup

Clone this repository, or use an existing checkout:

```sh
git clone git@github.com:lambdaseq/mono.git
cd mono
clojure -X:new :template lambdaseq/mono :name acme/my-project
```

The `:new` alias loads a pinned deps-new release. It creates `my-project` with an example app that uses an example library.
For a library-only workspace, add `:layout :libs`.

### Run

```sh
cd my-project
clojure -T:build ci
java -jar apps/example-app/target/example-app-0.1.0.jar
```

### Verify it worked

Tests report zero failures and errors. The executable prints `Hello, world!`.
Builds load only dependencies declared in the generated workspace; mono and deps-new are generation tools.

### Troubleshooting

- **Destination exists:** choose another directory; deps-new refuses to overwrite it by default.
- **Unknown module:** register its keyword and settings in the generated `build.clj` modules map.
- **Publishing metadata missing:** configure the generated `project` URL and license before calling `deploy`.

## Templates

| Template | Result | Options |
| --- | --- | --- |
| `lambdaseq/mono` | Workspace with a library and optional example app | `:layout :mixed` (default) or `:libs` |
| `lambdaseq/app` | Application module | `:platform :clj` (default) or `:cljs` |
| `lambdaseq/lib` | Library module | `:platform :clj` (default) or `:cljs` |

CLJS modules use `:compiler :shadow` by default. Choose `:compiler :cljs` for the plain compiler.
The compiler option applies only to CLJS. Names follow ordinary qualified Clojure names, such as `acme.my-project/shared`.

Run these commands from the mono checkout:

```sh
clojure -X:new :template lambdaseq/lib :name acme.my-project/shared \
  :target-dir '"my-project/libs/shared"'

clojure -X:new :template lambdaseq/app :name acme.my-project/web \
  :target-dir '"my-project/apps/web"' :platform :cljs :compiler :shadow
```

Register each module in the generated workspace's quoted `modules` map:

```clojure
:shared {:kind :lib :dir "libs/shared" :platform :clj :lib acme.my-project/shared}
:web {:kind :app :dir "apps/web" :platform :cljs :compiler :shadow
      :lib acme.my-project/web :main acme.my-project.web.core}
```

Add local coordinates to root `:dev` for workspace REPL access.
Consumers reference sibling libraries through their own `deps.edn`:

```clojure
{:deps {acme.my-project/shared {:local/root "../../libs/shared"}}}
```

Module templates omit their own build files; the workspace build handles them.
The same templates can be loaded through a pinned Git dependency with an installed deps-new tool.
Use a full commit SHA of a revision containing these templates.

## Generated workspace

```text
apps/example-app/{src,test,resources,deps.edn}
libs/example-lib/{src,test,resources,deps.edn}
build.clj
deps.edn
README.md
```

The `libs` layout omits `apps/`. Each module owns its runtime and test dependencies.
The root build file contains ordinary functions and a Clojure map of modules, following the approach used by [fx](https://github.com/lambdaseq/fx/blob/main/build.clj).

## Build functions

Run functions from the generated workspace root with `clojure -T:build FUNCTION`.

| Function | Behavior |
| --- | --- |
| `test` | Run module test aliases, then execute CLJS tests in Node. |
| `jar` | Build library source jars. |
| `uber` | Build executable CLJ application jars. |
| `build` | Build libraries, CLJ applications, and CLJS browser releases. |
| `clean` | Delete selected module target directories. |
| `install` | Build and install libraries into the local Maven repository. |
| `deploy` | Build and publish explicitly selected libraries to Clojars. |
| `run` | Run one CLJ application from source. |
| `repl` | Run one module's foreground REPL alias. |
| `version` | Print the workspace version. |
| `ci` | Test and build selected modules. |

```sh
clojure -T:build test :modules '[:example-lib]'
clojure -T:build jar :version '"0.2.0"'
clojure -T:build repl :modules :example-lib
```

Tasks default to all supported modules. Explicit selections of unsupported modules fail.
Execution is sequential without dependency scheduling. Build artifacts stay in module-local `target/` directories.

The generated `project` map defines the default version and publication metadata.
Module versions can override that default; command-line `:version` overrides both. Snapshots are rejected.
Library POMs replace registered internal local dependencies with Maven coordinates and versions.
Unregistered local dependencies and direct Git dependencies fail library packaging instead of being omitted from POMs.
Dependency files stay unchanged. `install` supports `:local-repo` for a separate Maven destination.

Configure URL and license metadata before publishing. `deploy` requires explicit modules and uses your Clojars credentials.
Application deployment belongs in project-specific build functions.

## REPLs

Use `clojure -M:dev` for a JVM REPL spanning the workspace.
Use `clojure -T:build repl :modules :example-lib` for a module's foreground alias; stop it with Ctrl-C.
CLJ modules provide standard nREPL aliases for IDE connections.
Shadow browser apps use watch mode. Shadow libraries use Node REPLs.
Plain CLJS aliases use the browser REPL for apps and Node REPLs for libraries.

## Migration from the CLI

The standalone executable, bbin integration, prompts, runtime manifest, and managed background REPL sessions were removed.
Use deps-new templates for creation and `clojure -T:build` for project tasks.
Existing CLI-generated repositories retain their pinned runtime dependency until migrated explicitly.
Generated build files belong to their projects; template updates are adopted by copying the desired changes.

## Template development

```sh
clojure -M:test
clojure -M:integration
clojure -T:build ci
clj-kondo --lint src test integration build.clj
```

Tests generate workspaces through deps-new. Acceptance checks cover both layouts, executable jars, failure propagation, portable library POMs, local installation, and CLJS builds.
Remote publication is exercised only by an explicit `deploy` invocation in a generated project.
Dependency pins and publication dates are recorded in [docs/dependencies.md](docs/dependencies.md).
