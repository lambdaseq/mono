# Dependency pins

Versions are exact. These POM `Last-Modified` headers were checked on 2026-11-16.
Every version was at least seven days old.

| Dependency | Version | POM Last-Modified (UTC) |
| --- | --- | --- |
| org.clojure/clojure | 1.12.4 | 2025-12-10 18:27:30 |
| io.github.clojure/tools.build | 0.10.6 | 2024-11-22 18:47:04 |
| org.clojure/clojurescript | 1.12.145 | 2026-05-06 18:27:40 |
| thheller/shadow-cljs | 3.4.11 | 2026-05-18 05:31:26 |
| nrepl/nrepl | 1.3.0 | 2024-08-13 13:05:10 |
| cider/piggieback | 0.6.0 | 2025-01-01 14:26:01 |

Clojure and CLJS versions match the requirements of Shadow CLJS 3.4.11.
Maven Central hosts the Clojure artifacts. Clojars hosts Shadow CLJS, nREPL, and Piggieback.

Before changing a pin, fetch its POM's `Last-Modified` header and reject releases under seven days old.
Update `deps.edn`, `resources/mono/versions.edn`, and this table where applicable.
