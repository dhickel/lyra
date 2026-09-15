# Collections and strings

## Array values

Arrays are homogeneous, fixed-size, and identity-bearing.

```lyra
let values :Array<I32> = Array[1 2 3]
let explicit = Array<I32>[1 2 3]
let empty :Array<I32> = Array<I32>[]
```

`Array[...]` needs a complete expected `Array<T>`. `Array<T>[...]` supplies the element type. `Array<T>[]` is an empty array, while bare `Array[]` is Unit.

```lyra
let first :I32 = values[0]
let length :I32 = values:.length
values[0] := 9
```

An index must be an integer value representing a valid nonnegative position. Invalid bounds fail with `LYR-BOUNDS`. Element assignment requires an `@mut` mutation root. Arrays compare structurally with `==` and by shared identity with `eq?`.

## Tuples

```lyra
let pair = Tuple[42 "answer"]
let explicit = Tuple<I32,String>[42 "answer"]
let number :I32 = pair:.0
```

Tuple fields are heterogeneous, immutable, and selected by a zero-based compile-time numeric member. An invalid position is a compile error. Tuples compare structurally and have no reference identity. Bare `Tuple[]` is Unit.

## Ranges

A `Range<T>` stores signed start, end, step, and endpoint inclusion. It is lazy in the sense that construction does not enumerate elements, but its three components evaluate immediately. Traversal is repeatable. See [control flow](control-flow.md#ranges-and-iter).

## Strings and characters

Strings are immutable UTF-16 sequences. Indexing selects one UTF-16 code unit:

```lyra
let text :String = "A😀"
let units :I32 = text:.length
let first :Char = text[0]
```

The example has length 3 because the supplementary character occupies two UTF-16 code units. Invalid indexes fail with `LYR-BOUNDS`.

`+` concatenates two or more strings. It never implicitly converts another value. `String[value]` explicitly converts primitive scalar values and Unit:

| Input | Output rule |
| --- | --- |
| Signed/unsigned integer | Mathematical base-10 value, no leading zeroes |
| `F32`, `F64` | Shortest round-tripping finite decimal; lowercase `e`; preserves `-0.0` |
| `Bool` | `#T` or `#F` |
| `Char` | One UTF-16 code-unit string |
| `String` | Same content |
| `Unit` | `()` |

Strings compare by content with `==` and `!=`. Relational ordering is not defined.

## Mutation, aliases, and Java

Lyra mutation permission belongs to a binding. Copying an array reference does not copy the array, and an immutable alias can observe mutations performed through an authorized mutable alias.

At the Java ABI, arrays are live JVM arrays rather than defensive copies. Trusted Java code can mutate them regardless of Lyra source-level `@mut` and can retain them after module close. This is a documented host escape, not permission granted to another Lyra module. See [artifacts and Java](artifacts-and-java.md).
