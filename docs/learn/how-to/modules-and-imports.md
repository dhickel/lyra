# Configure source roots and imports

## Prerequisites

Use `.lyra` UTF-8 source files and a built CLI.

## Map logical names to files

Given:

```text
src/
├── app/main.lyra
└── game/math/vector.lyra
```

configure `src` as a source root:

```sh
lyra run 'app->main' --source-root src
```

The logical name `game->math->vector` maps to `game/math/vector.lyra` beneath each configured root. Every root and resolver is queried. Missing and duplicate matches are diagnostics; root order is not a precedence rule.

When `ROOT` is an existing `.lyra` path and no explicit root is supplied, its parent is used as the first source root. Once you provide `--source-root`, configure every root the graph needs explicitly.

## Choose an import form

Imports must form the source header.

Namespace import:

```lyra
import game->math->vector
let value = vector->:.origin
```

Namespace alias:

```lyra
import game->math->vector as vec
let value = vec->:.origin
let scaled = vec->::scale[value 2]
```

Selective import and alias:

```lyra
import game->math->vector->{origin scale as resize}
let scaled = ::resize[origin 2]
```

Public re-export:

```lyra
import @pub game->math->vector->{origin scale as resize}
```

Only selective imports can use `import @pub`. It exports the selected local names, including aliases. Imports are otherwise private, and there are no wildcard imports or implicit transitive exports.

## Failure boundaries

- Imported names must be public in their defining module.
- Two imports cannot bind the same local name.
- A public export or re-export cannot be replaced later in the module.
- Module initializers run eagerly. Cycles are legal only through function signatures that do not create a cyclic eager value dependency.
- An importing module may read an exported `@mut` binding but cannot mutate it directly. Expose a function in the declaring module when external code must request mutation.

For REPL pinning and reload, see [Use the local REPL](use-local-repl.md). Exact import grammar is in the [reference map](../reference.md).
