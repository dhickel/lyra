# Tutorial 6: Split a program into modules

**Outcome:** create a logical module, import it by alias or selected export, and run a root module.

## Prerequisites

Complete [Tutorial 5](05-control-flow.md).

## 1. Create the source tree

```text
modules/
├── app/
│   └── main.lyra
└── math/
    └── basic.lyra
```

Create `modules/math/basic.lyra`:

```lyra
let @pub double :Fn<I32;I32> = (=> |value| (* value 2))
let @pub answer :I32 = 21
```

Create `modules/app/main.lyra`:

```lyra
import std->io as io
import math->basic as math

let @pub main :Fn<Array<String>;I32> = (=> |args| {
    let result :I32 = math->::double[math->:.answer]
    io->::println[String[result]]
    0
})
```

## 2. Run the logical root

```sh
lyra run 'app->main' --source-root modules
```

Expected output:

```text
42
```

A logical import `math->basic` maps to `math/basic.lyra` beneath a configured source root. Imports form a header before executable declarations.

`as math` creates a namespace alias. Use `math->:.answer` for a value and `math->::double[...]` for a direct function call.

## 3. Try a selective import

Replace the alias import with:

```lyra
import math->basic->{double answer}
```

Then change the calculation to:

```lyra
let result :I32 = ::double[answer]
```

The output remains `42`. A selective import binds only the named public exports.

Module values initialize eagerly. Public exports require explicit complete types. A runnable root still requires exactly `main :Fn<Array<String>;I32>`.

Next: [Define structs and classes](07-structs-and-classes.md). See [Configure source roots and imports](../how-to/modules-and-imports.md).
