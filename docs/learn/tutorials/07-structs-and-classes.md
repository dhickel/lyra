# Tutorial 7: Define structs and classes

**Outcome:** construct a struct and class, use fields and methods, and keep a saved bound method.

## Prerequisites

Complete [Tutorial 6](06-modules.md).

## 1. Create `objects.lyra`

```lyra
import std->io as io

struct Point {
    let x :I32
    let y :I32
}

class Counter {
    let @mut value :I32

    Counter = (=> |start :I32| {
        self:.value := start
    })

    let @pub increment :Fn<;Unit> = (=> || {
        self:.value := (++ self:.value)
    })

    let @pub current :Fn<;I32> = (=> || self:.value)
}

let @pub main :Fn<Array<String>;I32> = (=> |args| {
    let point :Point = :Point[20 22]
    let counter :Counter = :Counter[(+ point:.x point:.y)]
    let saved :Fn<;Unit> = counter:.increment
    (saved)
    io->::println[String[counter::current[]]]
    0
})
```

Run it:

```sh
lyra run objects.lyra
```

Expected output:

```text
43
```

## Read the object model

- `Point` is a nominal struct. Its uninitialized fields become constructor parameters in declaration order.
- `Counter` has one explicit constructor. Its body initializes `value` through `self`.
- Class fields and methods are private unless marked `@pub`. Struct fields are public by default.
- `:Point[...]` and `:Counter[...]` are explicit nominal construction. The leading colon is required.
- `counter::current[]` calls a method with an implicit receiver.
- `counter:.increment` reads the current receiver-bound function value. `saved` retains that selected method and receiver.

Class equality is instance identity. Struct equality is structural within the same nominal type. There is no inheritance, interface syntax, method overloading, static member syntax, or custom struct constructor.

Next: [Traverse ranges and repeat work](08-ranges-and-loops.md). Read [Nominal and structural data](../explanations/nominal-and-structural-types.md).
