# Verification: 2026-11-16

The implementation was checked on macOS arm64 and Linux arm64.
Linux checks used a cached Eclipse Temurin 25 container with read-only source and dependency mounts.

| Check | Result |
| --- | --- |
| Babashka unit suite | 25 tests, 268 assertions passed |
| JVM unit suite | 25 tests, 268 assertions passed |
| Linux JVM unit suite | 12 tests, 116 assertions passed |
| clj-kondo | Zero errors or warnings |
| Clojure source formatting | Passed; unrendered templates excluded |
| Runtime jar packaging | Passed |
| bbin installation | Passed in temporary directories on macOS and Linux |
| Library-only starter | Seven assertions passed: generation without apps, testing, source jar, REPL lifecycle, adding a CLJS library, and cleaning |
| Interactive init | Terminal wizard retried invalid answers and generated a passing library workspace; Ctrl-D cancelled without creating files |
| Init automation | Nonterminal missing arguments returned usage without reading input; explicit flags bypassed prompts in unit tests |
| Module wizards | Terminal app wizard generated plain CLJS; library wizard retried an existing name and generated Shadow CLJS; both modules passed compiled tests |
| Module prompt validation | All app/library platform and compiler variants, input retries, cancellation without writes, and explicit argument bypass passed |
| Generated CLJ modules | Tests, source jar, uberjar, and executable output passed |
| Generated CLJS modules | Shadow and plain compiler app/library tests and builds passed |
| Browser rendering | Both production app templates rendered the greeting in headless Chrome |
| Failure propagation | CLJ, Shadow CLJS, and discovered plain CLJS test failures returned nonzero exits |
| Deployment hooks | Explicit selection, module working directory, and repeated invocation passed |
| JVM REPL lifecycle | Start, repeated start, status, stop, and occupied-port rejection passed |
| CLJS REPL lifecycle | Shadow browser, plain browser, and Shadow library sessions started and stopped |
| Shadow browser HTTP | Served HTML through the reported URL with browser request headers |
| Linux CLI lifecycle | Installed, generated modules, tested, built, executed, started/stopped REPL, and cleaned outputs |

Integration scenarios passed across targeted runs. Run the complete acceptance suite with `bb integration`.
Run one group with `bb integration jvm-workflow`, `bb integration cljs-workflow`, `bb integration cljs-repl-workflow`, or `bb integration library-workflow`.

Tests use local runtime coordinates because the source workspace has no Git origin or commit.
Publishing requires a reachable Git repository and a full commit SHA.
Linux checks cover JVM workflows; browser rendering and CLJS lifecycle checks ran on macOS.
