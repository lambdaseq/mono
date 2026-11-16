# example-lib

Run tasks from the workspace root:

```sh
clojure -T:build test :modules :example-lib
clojure -T:build build :modules :example-lib
clojure -T:build repl :modules :example-lib
```

This module owns its dependencies in `deps.edn`.
