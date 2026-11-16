# example-app

Run tasks from the workspace root:

```sh
clojure -T:build test :modules :example-app
clojure -T:build build :modules :example-app
clojure -T:build repl :modules :example-app
```

This module owns its dependencies in `deps.edn`.
