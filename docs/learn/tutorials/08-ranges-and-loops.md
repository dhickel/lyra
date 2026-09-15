# Tutorial 8: Traverse ranges and repeat work

**Outcome:** use signed ranges, `iter`, and `while` with exact callback contracts.

## Prerequisites

Complete [Tutorial 7](07-structs-and-classes.md).

## 1. Create `loops.lyra`

```lyra
import std->io as io

let @pub main :Fn<Array<String>;I32> = (=> |args| {
    let @mut total :I32 = 0
    let values :Range<I32> = (1..=3:1)

    iter[values |value| {
        total := (+ total value)
    }]

    let @mut countdown :I32 = 2
    while[
        || (> countdown 0)
        || {
            io->::println[String[countdown]]
            countdown := (-- countdown)
        }
    ]

    io->::println[(+ "total=" String[total])]
    0
})
```

Run it:

```sh
lyra run loops.lyra
```

Expected output:

```text
2
1
total=6
```

## Range and callback rules

`(1..=3:1)` is inclusive because it uses `..=`. `(1..3:1)` would visit `1` and `2`. A range stores signed integer start, end, and nonzero step values. A negative step supports descending traversal.

`iter` accepts either `Fn<I32;Unit>` for this range or `Fn<;Unit>`. `while` requires exactly a `Fn<;Bool>` predicate and a `Fn<;Unit>` action. It tests the predicate before every action, including the first.

The range and callback expressions are selected once, left to right, before traversal. Captured mutable bindings remain live during repeated calls. Runtime failure stops traversal immediately. Cancellation checks are cooperative at loop backedges.

`iter` and `while` are compiler-recognized built-ins. Write `iter[...]` and `while[...]`, never `::iter[...]` or `::while[...]`.

Next: [Use a persistent REPL](09-persistent-repl.md). See the [range and loop rules](../reference.md).
