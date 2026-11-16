# {{name}}

{{platform}} {{kind}} in the workspace.

From the workspace root:

```sh
bb mono test --module {{path}}
bb mono build --module {{path}}
bb mono repl start --module {{path}}
bb mono repl stop --module {{path}}
```

Dependencies and aliases live in `deps.edn`. Build outputs live in `target/`.
Configure deployment in the root `monorepo.edn`.
