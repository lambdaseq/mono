# {{project}}

A workspace for independently built Clojure and ClojureScript modules.

Requires Babashka 1.12+, Clojure CLI, and JDK 17+. CLJS testing also requires Node.js.

```sh
bb mono list
bb mono test
bb mono build --module {{example-module}}
{{artifact-command}}
bb mono repl start --module {{example-module}}
bb mono repl stop --module {{example-module}}
```

Use `bb mono add lib shared --platform cljs` to add a library.
Apps are optional; add one with `bb mono add app api --platform clj`.
CLJS modules default to Shadow CLJS. Select `--compiler cljs` for the plain compiler.
Run `bb mono add app` or `bb mono add lib` without a name in a terminal for interactive choices.
The wizard asks for a name, CLJ or CLJS, and whether CLJS should use Shadow CLJS.
Supplying a name skips prompts and uses the explicit flags or their defaults.

Each module owns its `deps.edn`. Add local libraries with relative `:local/root` dependencies.
Commands run sequentially in manifest order, without dependency ordering or caching.

Root build functions provide the same build operations:

```sh
clojure -T:build test
clojure -T:build build :module '["{{example-module}}"]'
```

Set a module's `:deploy` to a command vector in `monorepo.edn`.
Run `bb mono deploy --module {{example-module}}` to invoke it from the module directory.
Deployment inherits your environment and never runs by default.

REPLs bind to loopback. Read connection details with `bb mono repl status`.
Connect your IDE using the module's `.nrepl-port`. Logs live under `.mono/`.
For Shadow CLJS libraries, evaluate `(shadow.cljs.devtools.api/node-repl)` after connecting.
For Shadow browser apps, evaluate `(shadow.cljs.devtools.api/repl :app)` and open the reported browser URL.

{{runtime-note}}
