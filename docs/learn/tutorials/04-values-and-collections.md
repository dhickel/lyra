# Tutorial 4: Work with values and collections

**Outcome:** use numbers, strings, characters, arrays, tuples, indexing, length, conversion, equality, and truthiness.

## Prerequisites

Complete [Tutorial 3](03-functions.md).

## 1. Create `values.lyra`

```lyra
import std->io as io

let @pub main :Fn<Array<String>;I32> = (=> |args| {
    let small :I16 = 20
    let widened :I32 = small
    let explicit :I32 = I32[22I64]
    let values :Array<I32> = Array[I32[small] explicit]
    let pair :Tuple<I32,String> = Tuple[values[0] "answer"]
    let first :Char = pair:.1[0]

    io->::println[(+ pair:.1 ": " String[(+ pair:.0 values[1])])]
    io->::println[(+ "length=" String[values:.length])]
    io->::println[(+ "first=" String[first])]
    io->::println[String[(== values Array<I32>[20 22])]]
    (values -> 0 : 1)
})
```

Run it:

```sh
lyra run values.lyra
```

Expected output:

```text
answer: 42
length=2
first=a
#T
```

## What happened

- `I16` widens losslessly to `I32`.
- `I32[22I64]` is an explicit checked conversion.
- Arrays are homogeneous and fixed-size. `values[0]` indexes an element.
- Tuples are structural fixed-shape values. `pair:.0` and `pair:.1` select compile-time positions.
- String indexing returns one UTF-16 `Char` code unit.
- `:.length` returns `I32` for arrays and strings.
- `==` compares arrays structurally.
- A non-empty array is truthy, so the final conditional returns `0`.

Indexing outside a string or array aborts the invocation with a bounds failure. An invalid explicit conversion fails at compile time when constant and aborts at runtime otherwise.

Next: [Control evaluation](05-control-flow.md). Use the [reference map](../reference.md) for the complete type and operator rules.
