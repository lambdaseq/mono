# mono

Generate Clojure monorepos with independent apps and libraries. Build, test, deploy, and manage module REPLs from the workspace root.

Supports macOS and Linux.

## Quickstart

### Prerequisites

Install these tools and configure GitHub SSH access:

| Tool | Version |
| --- | --- |
| Babashka | 1.13.220+ for the bbin installer below |
| [bbin](https://github.com/babashka/bbin) | 0.2.5 |
| Clojure CLI | 1.12.6.1673 |
| JDK | 17+ for CLJ; 21+ for CLJS |
| Git | 2.x |
| Node.js | 24.x, for CLJS tests and Node REPLs |

### Setup

Install the published commit:

```sh
bbin install git@github.com:lambdaseq/mono.git \
  --git/sha 2ec9233af83e23ed35a8ed839f80a90d16fc53a2 --as mono
mono --help
```

Ensure the directory printed by `bbin bin` is on your `PATH`.

### Run

Create an app-and-library workspace, test it, and build the app:

```sh
mono init my-project --namespace acme
cd my-project
bb mono test
bb mono build --module apps/example-app
java -jar apps/example-app/target/example-app-0.1.0.jar
```

### Verify it worked

Tests report zero failures and errors. Running the jar prints `Hello, world!`.
Use `bb mono` inside the workspace to load its pinned runtime version.

### Troubleshooting

- **`mono: command not found`:** add the directory printed by `bbin bin` to your `PATH`.
- **GitHub access fails:** configure an SSH key with repository access, then retry installation.
- **Destination exists:** choose an empty or absent directory; generators refuse overwrites.

## Commands

| Command | Behavior |
| --- | --- |
| `mono init` | Open the initialization wizard in a terminal. |
| `mono init DIR --namespace PREFIX` | Generate a working CLJ app and library. |
| `mono init DIR --namespace PREFIX --template libs` | Generate a library-only workspace. |
| `mono add app` | Open the app wizard in a terminal. |
| `mono add lib` | Open the library wizard in a terminal. |
| `mono add app NAME --platform clj` | Add an executable JVM app. |
| `mono add lib NAME --platform cljs` | Add a CLJS source library. |
| `mono add app NAME --platform cljs` | Add a Shadow CLJS browser app. |
| `mono add app NAME --platform cljs --compiler cljs` | Add a plain CLJS browser app. |
| `mono list` | List registered modules. |
| `mono build [--module PATH]` | Build selected modules, or all modules. |
| `mono test [--module PATH]` | Test selected modules, or all modules. |
| `mono clean [--module PATH]` | Delete selected module output directories. |
| `mono deploy --module PATH` | Execute the module's deployment command. |
| `mono repl start --module PATH` | Start or return a managed module REPL. |
| `mono repl status [--module PATH]` | Report managed REPL connection details. |
| `mono repl stop --module PATH` | Stop a managed module REPL. |

Repeat `--module` to select several modules. Commands run sequentially in manifest order and stop on failure.
REPL start and stop accept exactly one module. Start accepts `--port` and `--timeout`; defaults are an automatic port and 120 seconds.
Commands work from nested workspace directories.

## Interactive generation

Run `mono init` without arguments in a terminal to open the wizard.
It asks for the project directory, namespace prefix, and starter: `mixed` or `libs`.
Press Enter at the starter prompt to choose `mixed`.
Invalid answers are retried. Ctrl-D or Ctrl-C cancels before generation.

Explicit arguments skip the wizard, including in terminals.
Without a terminal, missing arguments show usage and return a nonzero exit code.

The global `mono` command delegates to the workspace's pinned runtime when used inside a workspace.

Run `bb mono add app` or `bb mono add lib` in a terminal to open a module wizard.
It asks for a name and platform: CLJ or CLJS. CLJ is the default.
CLJS selections also ask whether to use Shadow CLJS, defaulting to yes.
Existing module names are rejected before generation. Cancellation leaves the workspace unchanged.

Supplying a name skips module prompts in every environment:

```sh
bb mono add app api --platform clj
bb mono add lib shared --platform cljs --compiler shadow
bb mono add app web --platform cljs --compiler cljs
```

With an explicit name, omitted flags retain their defaults: CLJ, or Shadow when the platform is CLJS.
Without a terminal, a missing name shows usage and returns a nonzero exit code.

## Library-only repositories

```sh
mono init my-libraries --namespace acme --template libs
cd my-libraries
bb mono test
bb mono build
bb mono repl start --module libs/example-lib
```

The `libs` template generates a CLJ library and omits `apps/`.
Add further CLJ or CLJS libraries with `mono add lib`.
The default `mixed` template generates the app-and-library example.

## Workspace layout

```text
apps/example-app/{src,test,resources,deps.edn,README.md}
libs/example-lib/{src,test,resources,deps.edn,README.md}
bb.edn
build.clj
deps.edn
monorepo.edn
README.md
```

The root manifest registers module paths and build settings. Each module owns its standard `deps.edn`.
Apps are optional. Library-only workspaces keep their modules under `libs/` and use the same build, test, deploy, and REPL commands.
CLJ modules include a standalone test runner under `dev/`.
CLJS modules include either `shadow-cljs.edn` or plain compiler configurations.

Root `build.clj` stays editable and exports `build`, `test`, `clean`, and `deploy`:

```sh
clojure -T:build test
clojure -T:build build :module '["apps/example-app"]'
```

These functions accept `:module` as a path string or sequence of paths.
Their default workspace root is the working directory; programmatic callers can supply `:root`.

### Local dependencies and artifacts

Reference sibling libraries using standard relative dependencies:

```clojure
{:deps {acme/shared {:local/root "../../libs/shared"}}}
```

CLJ apps produce executable uberjars. Libraries produce source jars, including CLJS libraries.
Browser apps produce static files under `target/public/`.
Artifacts remain under each module's `target/` directory.

The runtime does not sort dependencies, cache builds, or calculate affected modules.
Jars are local build artifacts. Remote library publication and translation of local dependencies into published coordinates require custom build logic.

### Deployment

Set a module's `:deploy` to an argument vector in `monorepo.edn`:

```clojure
:deploy ["./deploy.sh" "production"]
```

The command runs from that module's directory and inherits the environment.
Arguments are passed directly; shell expansion requires an explicit shell command.
Deployment requires module selection and has no built-in hosting provider.
Make deployment commands safe to rerun by replacing or merging shared state.

### REPLs

Sessions bind to loopback and write module-local `.nrepl-port` files for IDEs.
Connection details and logs appear in `bb mono repl status`.
Runtime state lives under ignored `.mono/` directories.

CLJ sessions expose nREPL. Shadow browser sessions also watch the app build.
Connect to nREPL, evaluate `(shadow.cljs.devtools.api/repl :app)`, and open the reported browser URL.
For Shadow libraries, evaluate `(shadow.cljs.devtools.api/node-repl)`.
For plain CLJS, evaluate `(cider.piggieback/cljs-repl (cljs.repl.browser/repl-env))` and open its browser URL.
Server readiness and browser attachment are separate states.

Starts are serialized and reuse healthy sessions. Authenticated control sockets identify managed processes.
Stops never signal an unverified PID. Stale session records are removed without killing unrelated processes.

## Runtime versioning

The remote installation above generates workspaces pinned to `lambdaseq/mono` and the installed commit.
From a Git checkout with an origin, scaffolding uses its URL and full HEAD commit.
For an explicit release, supply both coordinates:

```sh
mono init my-project --namespace acme \
  --runtime-url git@github.com:lambdaseq/mono.git \
  --runtime-sha 2ec9233af83e23ed35a8ed839f80a90d16fc53a2
```

Use a full commit SHA when selecting a different version.
Both generated runtime dependencies use the same coordinates.

Without Git coordinates, scaffolding uses this checkout through an absolute `:local/root` dependency.
This development mode requires the checkout to remain available.
Supply release coordinates when generating a workspace to share or clone elsewhere.
Template upgrades and third-party template plugins are deferred.

## Development

Clone the repository to run from source:

```sh
git clone git@github.com:lambdaseq/mono.git
cd mono
./bin/mono --help
```

Install that checkout with `bbin install . --as mono`, or use `./bin/mono` directly.

```sh
bb test
bb lint
clojure -M:test
bb integration
clojure -T:build jar
```

Unit tests cover validation, selection, templates, file collisions, path containment, and stale sessions.
`bb integration` builds temporary workspaces, checks failure exits and deploy hooks, and exercises JVM and CLJS REPLs.
It downloads compiler dependencies and opens loopback sockets.
Dependency pins and their publication dates are recorded in [docs/dependencies.md](docs/dependencies.md).
