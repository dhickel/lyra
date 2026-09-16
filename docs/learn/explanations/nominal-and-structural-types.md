# Nominal and structural data

Tuples are structural values. Their type is determined by the ordered component types, and their fields are fixed zero-based positions:

```lyra
let pair :Tuple<I32,String> = Tuple[1 "one"]
pair:.0
```

Structs and classes are nominal. Their identity comes from the declaration and originating module revision, not from matching field shapes. Two declarations with identical members still define different types.

Structs are data-only nominal types. Uninitialized fields become positional construction parameters in declaration order, and fields are public by default. Struct equality compares current field values structurally within the same nominal type.

Classes may have private state, function-valued methods, and one explicit constructor. Class fields and methods are private unless `@pub`. Class equality is instance identity.

Construction uses the type name followed by bracket arguments:

```lyra
let point :Point = Point[1 2]
let counter :model->Counter = model->Counter[0]
```

Resolution distinguishes construction from indexing and conversion; capitalization alone does not. The older `:Point[...]` and `:model->Counter[...]` forms remain accepted for compatibility. Struct arguments initialize required fields before defaults. Class field defaults run in declaration order before the constructor body. Definite initialization rejects reads, duplicate immutable writes, method calls, or escape of incomplete `self`.

There is no inheritance, interface implementation, overriding, overloading, custom struct constructor, static member, or user-defined operator surface.

See the [nominal type rules](../reference.md#language).
