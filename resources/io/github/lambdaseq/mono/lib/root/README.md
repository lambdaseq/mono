# {{name}}

A {{platform}} {{kind}}.

Generation inside a mono workspace registers the module in root `build.clj` and `:dev` dependencies.
Use `:register false` to manage registration manually. Outside a workspace, parent files are unchanged.

Run `clojure -M:test` from this directory, or select the module through the root build functions.
Run `clojure -M:repl` for a foreground REPL.
