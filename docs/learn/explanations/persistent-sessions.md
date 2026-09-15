# Persistent sessions and bounded snapshots

A Lyra session compiles each submission against committed metadata and links it to real initialized storage from earlier successful work. It executes only the newly generated entry point. Previous source is not replayed.

Successful execution commits staged names and imports. Compilation and linkage failure run no source and publish nothing. Runtime failure or cooperative cancellation publishes no staged names, but completed mutations, output, and escaped valid values remain. Effects are nontransactional.

This distinction matters when retrying:

```lyra
let @mut count :I32 = 0
```

A later failing submission can increment `count` before it fails. The new declarations from that submission disappear, but the increment remains.

The REPL does not return live Java objects. It returns immutable bounded snapshots with canonical Lyra types and display data. Snapshots preserve scalar values, aggregate ordering, alias or cycle references, function descriptions, nil and Unit distinctions, and explicit truncation. Formatting does not run constructors, methods, equality, or user stringification.

Default snapshot budgets are depth 6, 100 aggregate elements, and 16 KiB rendered output. A dynamic result that exceeds a budget is represented with truncation after execution. Admission checks that cannot represent even the minimum result reject the operation before effects.

Reset removes scratch names and resources but does not mutate snapshots already returned. In an attached application, reset also preserves root-held values and completed root mutations.

See [Use the local REPL](../how-to/use-local-repl.md) and the [operational limits](../../repl.md#operational-limits-summary).
