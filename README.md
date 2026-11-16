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

Install the pinned deps-new tool once:

```sh
clojure -Ttools install io.github.seancorfield/deps-new \
  '{:git/tag "v0.10.3" :git/sha "2c012c42185f9f14448f561481e6b39088e0fc82"}' \
  :as new
```

From the directory that should contain your project, generate a workspace:

```sh
clojure -Tnew create \
  :template io.github.lambdaseq/mono \
  :name acme/my-project
```

This creates `my-project` with an example app that uses an example library.
deps-new is pinned to a full commit SHA. The template command follows mono's default GitHub branch.
The workspace template name matches its GitHub repository, so no `%` path is needed.
deps-new fetches it over HTTPS into Clojure's Git cache. No shell variables or manual checkout are needed.
Generated projects own their build code and dependencies.

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
- **GitHub access fails:** check `git ls-remote https://github.com/lambdaseq/mono.git` and your Git HTTPS configuration.

## Template options

Use `io.github.lambdaseq/mono` for all generation. Select the output with `:kind`:

| Kind | Result | Options |
| --- | --- | --- |
| `:workspace` (default) | Workspace with a library and optional example app | `:layout :mixed` (default) or `:libs` |
| `:app` | Application module | `:platform :clj` (default) or `:cljs` |
| `:lib` | Library module | `:platform :clj` (default) or `:cljs` |

CLJS modules use `:compiler :shadow` by default. Choose `:compiler :cljs` for the plain compiler.
The compiler option applies only to CLJS. Names follow ordinary qualified Clojure names, such as `acme.my-project/shared`.

To create a separate library-only workspace, run from its intended parent directory:

```sh
clojure -Tnew create \
  :template io.github.lambdaseq/mono \
  :name acme/my-libraries :layout :libs
```

From a generated workspace root, add modules:

```sh
# CLJ library
clojure -Tnew create \
  :template io.github.lambdaseq/mono :kind :lib \
  :name acme.my-project/shared :target-dir '"libs/shared"'

# CLJ application
clojure -Tnew create \
  :template io.github.lambdaseq/mono :kind :app \
  :name acme.my-project/api :target-dir '"apps/api"'

# CLJS browser application with Shadow CLJS
clojure -Tnew create \
  :template io.github.lambdaseq/mono :kind :app \
  :name acme.my-project/web :target-dir '"apps/web"' \
  :platform :cljs :compiler :shadow

# CLJS library with the plain compiler
clojure -Tnew create \
  :template io.github.lambdaseq/mono :kind :lib \
  :name acme.my-project/shared-ui :target-dir '"libs/shared-ui"' \
  :platform :cljs :compiler :cljs
```

These commands use the installed `new` tool and work across shells without project-specific aliases.

Register each module in the generated workspace's quoted `modules` map:

```clojure
:shared {:kind :lib :dir "libs/shared" :platform :clj :lib acme.my-project/shared}
:api {:kind :app :dir "apps/api" :platform :clj
      :lib acme.my-project/api :main acme.my-project.api.core}
:shared-ui {:kind :lib :dir "libs/shared-ui" :platform :cljs :compiler :cljs
            :lib acme.my-project/shared-ui}
:web {:kind :app :dir "apps/web" :platform :cljs :compiler :shadow
      :lib acme.my-project/web :main acme.my-project.web.core}
```

Add local coordinates to root `:dev` for workspace REPL access.
Consumers reference sibling libraries through their own `deps.edn`:

```clojure
{:deps {acme.my-project/shared {:local/root "../../libs/shared"}}}
```

Generated modules omit their own build files; the workspace build handles them.
The separate `lambdaseq/app` and `lambdaseq/lib` templates are replaced by `:kind :app` and `:kind :lib`.
For repeatable generation, append `#` and a full 40-character commit SHA to the template argument.
The template is distributed through GitHub; no Maven or Clojars publication is required.

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
Use mono's deps-new template for creation and `clojure -T:build` for project tasks.
Existing CLI-generated repositories retain their pinned runtime dependency until migrated explicitly.
Generated build files belong to their projects; template updates are adopted by copying the desired changes.

## Template development

For local changes, clone the repository and use its `:new` alias:

```sh
git clone git@github.com:lambdaseq/mono.git
cd mono
clojure -X:new :template local%io.github.lambdaseq/mono :name acme/local-example
```

The `local%` prefix selects the template on the local classpath without fetching GitHub.

```sh
clojure -M:test
clojure -M:integration
clojure -T:build ci
clj-kondo --lint src test integration build.clj
```

Tests generate workspaces through deps-new. Acceptance checks cover both layouts, executable jars, failure propagation, portable library POMs, local installation, and CLJS builds.
Remote publication is exercised only by an explicit `deploy` invocation in a generated project.
Dependency pins and publication dates are recorded in [docs/dependencies.md](docs/dependencies.md).
