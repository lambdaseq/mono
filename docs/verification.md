# Verification: 2026-11-16

The deps-new template architecture was checked on macOS arm64 with JDK 25 and Node.js 24.22.1.
Template tests also ran in a Linux arm64 JDK container using an already resolved classpath.

| Check | Result |
| --- | --- |
| Template tests | Five tests, 102 assertions passed |
| Generated workspace acceptance | Three tests, 21 assertions passed |
| Root `clojure -T:build ci` | Passed template and acceptance suites |
| Linux template tests | Five tests, 102 assertions passed |
| clj-kondo | Zero errors and warnings in generator hooks, tests, and root build |
| Source formatting | Passed; template source was formatted with placeholders preserved |
| Both workspace layouts | Mixed and library-only projects generated without a mono runtime dependency |
| Module variants | CLJ apps/libs and both CLJS compiler variants generated and compiled |
| Executable app | Built uberjar and source execution printed `Hello, world!` |
| Library artifacts | Custom source paths were packaged correctly |
| Publication POMs | Registered local dependencies became Maven coordinates and retained dependency metadata |
| Maven installation | Two dependent libraries installed into a temporary repository |
| External consumer | An independent project loaded the installed jars and executed their code |
| Failure propagation | Failing CLJ and CLJS tests and unknown module selections returned nonzero exits |
| Publishing preconditions | Missing module selection and missing publication metadata failed locally |
| Foreground REPL | Generated nREPL alias started through root `repl`; Ctrl-C terminated it |
| Browser starters | Plain and Shadow release builds rendered `Hello, world!` in headless Chrome |
| Subtraction | CLI, bbin tasks, manifest loader, background session manager, runtime code, and their tests removed |

Clojars publication was not invoked. The acceptance suite verifies local installation and publishing preconditions without contacting a remote artifact repository.
Generated-project execution checks ran on macOS. Linux checks cover generation and build-function unit tests, not the full acceptance suite.

Defaults: mixed CLJ workspace, CLJ modules, and Shadow CLJS when choosing CLJS.
Library-only generation uses `:layout :libs`. Project owners choose license metadata before publication.

Generated projects own their build code. Existing CLI-generated repositories retain their pinned runtime until migrated explicitly.
