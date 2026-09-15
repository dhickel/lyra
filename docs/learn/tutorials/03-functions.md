# Tutorial 3: Write and call functions

**Outcome:** define full lambdas, use an inline signature, pass a compact lambda, and call functions in both current call forms.

## Prerequisites

Complete [Tutorial 2](02-bindings-and-mutation.md).

## 1. Create `functions.lyra`

```lyra
import std->io as io

let addOne :Fn<I32;I32> = (=> |value| (+ value 1))
let double = (=> :I32 |value :I32| (* value 2))
let apply :Fn<Fn<I32;I32>,I32;I32> = (=> |function value| (function value))

let @pub main :Fn<Array<String>;I32> = (=> |args| {
    let first :I32 = (addOne 20)
    let second :I32 = ::double[first]
    let result :I32 = ::apply[|value| (+ value 1) second]
    io->::println[String[result]]
    0
})
```

Run it:

```sh
lyra run functions.lyra
```

Expected output:

```text
43
```

## Read the forms

- `addOne` gets its complete lambda contract from `Fn<I32;I32>`.
- `double` supplies complete parameter and return annotations inline.
- `|value| (+ value 1)` is a compact anonymous lambda. It is valid because `apply` supplies the expected `Fn<I32;I32>` contract.
- `(addOne 20)` calls a callable value.
- `::double[first]` directly calls a resolved name.

Both calls use exact positional arity and static argument types. There are no omitted, named, default, or variable arguments, and no automatic currying.

Try changing `::double[first]` to `::double[]` or passing a string. Compilation fails before execution.

Next: [Work with values and collections](04-values-and-collections.md). See [Calls and accessors](../explanations/calls-and-accessors.md).
