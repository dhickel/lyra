# Static typing and explicit conversion

Lyra assigns every expression a compile-time type. Local inference removes annotations when the initializer and expected context determine one complete contract. It does not introduce a dynamic fallback.

```lyra
let count = 42
let label :String = "items"
let transform :Fn<I32;I32> = (=> |value| (+ value 1))
```

The function annotation supplies the lambda's parameter and return types. Public declarations require explicit complete types because module consumers cannot depend on local inference details.

Numeric compatibility is based on representable domains. Lossless widening can be implicit, such as `I16` to `I32`. Narrowing and potentially lossy signed, unsigned, integer, or floating changes require an explicit type-bracket conversion:

```lyra
let wide :I64 = 42I32
let narrow :I32 = I32[wide]
```

An invalid constant conversion is a compile error. A runtime value outside the target range aborts the invocation with a conversion failure. Composite types are invariant, so compatible element conversions do not make `Array<I16>` an `Array<I32>`.

`@nil T` is a static contract, not a dynamic union. It must be narrowed by a truthy predicate binding, compared with `#NIL`, passed under a matching nilable contract, or consumed by nil-only coalescing before ordinary `T` operations.

There is no `Any`, implicit member lookup, or implicit stringification. `String[value]` is an explicit conversion for supported scalar values and Unit.

See the [formal type and numeric rules](../reference.md#language).
