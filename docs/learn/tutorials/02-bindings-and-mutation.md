# Tutorial 2: Bind and update values

**Outcome:** use inferred and explicit types, then update a binding that grants mutation permission.

## Prerequisites

Complete [Tutorial 1](01-first-program.md).

## 1. Create `counter.lyra`

```lyra
import std->io as io

let @pub main :Fn<Array<String>;I32> = (=> |args| {
    let label = "count"
    let start :I32 = 2
    let @mut count :I32 = start
    count := (++ count)
    io->::println[(+ label ": " String[count])]
    count
})
```

Run it:

```sh
lyra run counter.lyra
```

Expected output:

```text
count: 3
```

The process requests exit status `3` because the block returns its final expression, `count`.

## 2. Observe immutable defaults

Remove `@mut` from `count` and run the file again. Compilation fails at `count := ...`. A `let` binding is immutable unless that symbol has `@mut`.

`label` is inferred as `String`. `start` and `count` use explicit named annotations. The syntax requires this spacing:

```lyra
let label :String = "count"
```

`label:String` and `label : String` are invalid.

## What `@mut` grants

`@mut` permits mutation through that binding. It does not make every alias globally mutable. Arrays and objects can be shared while each symbol retains its own mutation permission.

Next: [Write and call functions](03-functions.md). Read [Binding-local mutation](../explanations/mutation.md) for the model.
