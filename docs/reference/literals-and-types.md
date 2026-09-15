# Literals and types

Lyra is statically typed. Every expression has a compile-time type. Local inference is available when the initializer and expected context determine one complete type. Public signatures must be explicit.

## Literals

| Kind | Syntax | Type or rule |
| --- | --- | --- |
| Boolean | `#T`, `#F` | `Bool` |
| Nil | `#NIL` | Requires an expected `@nil T` contract |
| Unit | `()`, `Array[]`, `Tuple[]` | `Unit`; the latter two are not empty collections |
| String | `"text"` | Immutable UTF-16 `String` |
| Character | `'x'`, `'\uD800'` | One UTF-16 code unit, `Char` |
| Integer | `42`, `42I32`, `255U8` | Decimal only |
| Float | `1.0`, `1.0F32`, `1.0e3` | Decimal point required; exponent is optional |
| Negative number | `-1`, `-1.5F32` | Adjacent minus normalized to unary negation |

Numeric suffixes are `I8`, `I16`, `I32`, `I64`, `U8`, `U16`, `U32`, `U64`, `F32`, and `F64`. Suffixes are uppercase. An exponent is accepted only on a decimal literal containing a point.

String and character escapes are `\\`, `\"`, `\'`, `\n`, `\r`, `\t`, `\b`, `\f`, `\0`, and exactly four hexadecimal digits in `\uXXXX`. A character must decode to exactly one UTF-16 code unit. Unpaired surrogate code units are representable.

## Types

| Family | Types and syntax |
| --- | --- |
| Signed integer | `I8`, `I16`, `I32`, `I64` |
| Unsigned integer | `U8`, `U16`, `U32`, `U64` |
| Floating point | `F32`, `F64` |
| Other primitive | `Bool`, `Char`, `String`, `Unit` |
| Array | `Array<T>` |
| Range | `Range<T>`, where `T` is `I8`, `I16`, `I32`, or `I64` |
| Tuple | `Tuple<T1,T2,...>` |
| Function | `Fn<P1,P2,...;R>`, including `Fn<;R>` |
| Nominal | A declared struct or class name, optionally namespace-qualified |

Composite types are invariant. Types and values share one namespace.

## Qualifiers

| Qualifier | Meaning |
| --- | --- |
| `@pub` | Exports a top-level binding/type, or selected imported names. Not a nested type qualifier. |
| `@mut` | Permits rebinding or mutation through that binding, parameter, field, or method slot. Not permitted on returns. |
| `@nil` | Admits `#NIL` in that exact value position. |

Placement matters:

```lyra
let @nil names :Array<String> = #NIL       // the array may be nil
let names :Array<@nil String> = Array[#NIL] // an element may be nil
let apply :Fn<@mut Array<I32>;Unit> = ...
```

`#NIL` cannot infer its base type alone. A non-nil `T` can be lifted to `@nil T`. A nilable value must be narrowed by a truthy conditional, compared with `#NIL`, or coalesced before ordinary `T` operations.

## Numeric inference and conversion

Unsuffixed integers default to `I64`; unsuffixed decimals default to `F64`. An exact literal adopts an expected numeric type when representable.

Implicit conversion is lossless only:

- same-signed integers widen by width;
- unsigned to signed is allowed only when the signed destination contains the complete unsigned domain, such as `U8` to `I16`;
- `I8`, `I16`, `U8`, and `U16` may widen to `F32`;
- `I32`, `U32`, and narrower integers may widen to `F64`;
- `F32` widens to `F64`;
- `I64` and `U64` never implicitly become floating point;
- signed to unsigned is never implicit.

Explicit primitive conversion uses bracket application:

```lyra
let small :I32 = I32[value]
let text :String = String[small]
```

Numeric conversion is range and value checked. It is not bit reinterpretation. A constant-invalid conversion is a compile error; a dynamic invalid conversion fails with `LYR-CONVERT`. See [operators](operators.md) for arithmetic result rules and [collections and strings](collections-and-strings.md) for text conversion.
