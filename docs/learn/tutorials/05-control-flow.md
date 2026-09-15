# Tutorial 5: Control evaluation

**Outcome:** combine blocks, source order, lazy conditionals, nil coalescing, value matching, and `cond`.

## Prerequisites

Complete [Tutorial 4](04-values-and-collections.md).

## 1. Create `control.lyra`

```lyra
import std->io as io

let describe :Fn<I32;String> = (=> |value| {
    let category :String = (match value
        0 -> "zero"
        _ when (> value 10) -> "large"
        _ -> "small")
    category
})

let clamp :Fn<I32;I32> = (=> |value| (cond
    (< value 0) -> 0
    (> value 100) -> 100
    _ -> value))

let @pub main :Fn<Array<String>;I32> = (=> |args| {
    let @nil label :String = #NIL
    let shown :String = (label : "unset")
    io->::println[shown]
    io->::println[::describe[12]]
    let result :I32 = ::clamp[120]
    (#T -> io->::println[String[result]])
    0
})
```

Run it:

```sh
lyra run control.lyra
```

Expected output:

```text
unset
large
100
```

## Evaluation rules used here

A block evaluates forms in source order and returns its final expression. A declaration cannot read an ordinary value before that value is initialized.

`(label : "unset")` is nil-only coalescing. Its fallback runs only when `label` is `#NIL`. It does not replace other falsey values such as `""` or `0`.

`match` always has a real subject, evaluates it once, tests arms in order, and requires a final unguarded `_ ->` fallback. `when` guards run only after their pattern matches.

`cond` has no subject. It tests conditions in order and also requires a final `_ ->` fallback. `cond[...]` is not valid.

The final then-only conditional executes its body because `#T` is truthy and has `Unit` type.

Next: [Split a program into modules](06-modules.md). Read [Evaluation order and laziness](../explanations/evaluation-order.md).
