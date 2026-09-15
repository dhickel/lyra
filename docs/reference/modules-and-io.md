# Modules and standard I/O

A source file is a module. It contains no module declaration. Canonical file or resolver identity determines module identity; import aliases do not.

## Import declarations

Imports form a header before executable declarations:

```lyra
import game->math->vector
import game->math->vector as vec
import game->math->vector->{length normalize as norm}
import @pub game->math->vector->{length}
```

| Form | Effect |
| --- | --- |
| Direct import | Binds an immutable namespace under the final path segment. |
| `as name` | Binds that namespace under `name`. |
| Selective import | Binds only listed public names, with optional aliases. |
| `import @pub ...->{...}` | Re-exports selected names under their local names. |

Namespace aliases are not first-class values. Use `vec->:.length` for value access and `vec->::normalize[value]` for a direct call. Wildcard imports do not exist. Imports are private by default, and `@pub` is valid only on a selective import.

The default resolver maps `game->math->vector` to `game/math/vector.lyra` below each configured source root. Exactly one configured root or resolver must supply a logical import. Missing and duplicate matches are diagnostics rather than precedence choices.

Modules initialize eagerly after function signatures are linked. Dependencies initialize first. Declarations within a module initialize in source order. Function-only cycles are permitted; eager value cycles are rejected. One root instance owns one instance of every reachable dependency.

## Exports and mutation

Top-level declarations are private unless `@pub`, and every public contract is explicit. A consuming module may read an exported mutable binding but cannot mutate its storage directly. The owner module can expose mutation through functions. Re-export is always explicit.

## `std->io`

`std->io` is a compiler-owned intrinsic module with reserved identity. Import it like any module:

```lyra
import std->io as io
io->::println["hello"]
```

| Export | Exact signature | Behavior |
| --- | --- | --- |
| `print` | `Fn<String;Unit>` | Write one contiguous string and flush stdout. |
| `println` | `Fn<String;Unit>` | Write the string plus exactly `\n`, then flush stdout. |
| `eprint` | `Fn<String;Unit>` | Write one contiguous string and flush stderr. |
| `eprintln` | `Fn<String;Unit>` | Write the string plus exactly `\n`, then flush stderr. |
| `readLine` | `Fn<;@nil String>` | Read one line; return `#NIL` only for EOF before any code unit. |

The CLI and default runtime use UTF-8. `readLine` removes `\n` and an immediately preceding `\r`. Malformed input, interruption, and stream failures produce `LYR-IO`; interruption preserves thread interrupt status. Modules never close process streams. A Java host can replace input, output, error, and charset together through `LoadOptions`.

Filesystem, environment, clocks, randomness, networking, process control, concurrency, and engine APIs are not current standard-library surfaces.
