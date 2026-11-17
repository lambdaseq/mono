# Dependency pins

Pins were checked on 2026-11-16, with rewrite-clj checked on 2026-11-17. All selected releases were at least seven days old.

| Dependency | Version or revision | Publication evidence (UTC) |
| --- | --- | --- |
| org.clojure/clojure | 1.12.4 | POM Last-Modified: 2025-12-10 18:27:30 |
| io.github.clojure/tools.build | 0.10.11 | POM Last-Modified: 2025-10-28 13:27:18 |
| io.github.seancorfield/deps-new | v0.10.3 / 2c012c42185f9f14448f561481e6b39088e0fc82 | Tagged commit: 2025-12-11 15:47:41 |
| io.github.cognitect-labs/test-runner | v0.5.1 / dfb30dd6605cb6c0efc275e1df1736f6e90d4d73 | Tagged commit: 2022-05-06 13:51:07 |
| slipset/deps-deploy | 0.2.2 | POM Last-Modified: 2023-10-28 18:35:53 |
| org.clojure/clojurescript | 1.12.145 | POM Last-Modified: 2026-05-06 18:27:40 |
| thheller/shadow-cljs | 3.4.11 | POM Last-Modified: 2026-05-18 05:31:26 |
| nrepl/nrepl | 1.3.0 | POM Last-Modified: 2024-08-13 13:05:10 |
| rewrite-clj/rewrite-clj | 1.1.47 | POM Last-Modified: 2023-03-25 19:18:57; checked 2026-11-17 |

The deps-new dependency belongs to template generation and testing. Generated builds depend on tools.build and deps-deploy, with their own module dependencies.
rewrite-clj preserves workspace comments and formatting during generation. Generated workspaces do not depend on it.
The CLJS version matches Shadow CLJS 3.4.11. Its Closure compiler requires JDK 21+.

Before adding or changing a pin, verify its POM Last-Modified header or tagged commit date and reject releases under seven days old.
Keep the template hooks, root aliases, and this table consistent.
