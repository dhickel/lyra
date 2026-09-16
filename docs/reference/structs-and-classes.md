# Structs and classes

Structs and classes are concrete nominal reference types. Identity belongs to the declaration, originating module revision, and occurrence, not to field shape or import alias.

## Declaration syntax

```lyra
struct @pub Vec2 {
    @mut x :F64
    @mut y :F64
}

class @pub Counter {
    @pub @mut value :I32

    Counter = (=> |start :I32| {
        self:.value := start
    })

    @pub @mut increment :Fn<;Unit> = (=> || {
        self:.value := (++ self:.value)
    })

    @pub current :Fn<;I32> = (=> || self:.value)
}
```

Declarations are module-level and names begin with an uppercase ASCII letter. Type visibility is private unless `@pub`. Members omit `let`, have an explicit complete type, and are unique within the type. This omission applies only inside struct/class bodies. Top-level, block-local, and lambda-local bindings remain ordinary `let` bindings.

| Rule | Struct | Class |
| --- | --- | --- |
| Default member visibility | Public fields | Private fields and methods |
| Methods | Not allowed | `Fn`-typed members |
| Callable data, including nested | Not allowed | Allowed |
| Constructor | Positional required fields | Optional one same-name constructor |
| Equality | Nominally typed structural equality | Instance identity |

Structs may contain class references as data. They cannot contain function values directly or nested in their data contracts.

## Creating values

Construction uses the type name followed by bracket arguments:

```lyra
let point :Vec2 = Vec2[10.0 20.0]
let remote :model->Vec2 = model->Vec2[1.0 2.0]
let counter :Counter = Counter[0]
```

Resolution distinguishes nominal construction from value indexing; capitalization alone does not. The older `:Type[...]` and `:module->Type[...]` spellings remain accepted for compatibility. Primitive conversions and array/tuple literals remain unprefixed. `module->:.Type[...]` is invalid; use `module->Type[...]`.

For a struct, fields without initializers are positional constructor arguments in declaration order. Initialized fields are not optional arguments. A class constructor is written `Name = (=> |typed parameters| body)` without `let`; it has Unit return semantics, while the construction expression returns the new instance. Without an explicit constructor, zero-argument class construction is legal only when every field has an initializer.

Arguments evaluate once from left to right. Required struct fields are installed first, then defaults run in declaration order. Class defaults run before the constructor body. Constructor failure publishes no instance and does not roll back effects on older initialized state.

Every field must be definitely initialized on all completing paths. Immutable fields initialize exactly once. Reads before initialization, method calls on incomplete `self`, duplicate immutable initialization, and escape of incomplete `self` are compile errors. A loop alone cannot prove an assignment occurs.

## Fields and methods

Member declarations are not ordinary bindings. For example, the following
`let` forms remain required outside a nominal body:

```lyra
let topLevel :I32 = 1
let blockResult :I32 = { let local :I32 = 2 local }
let make :Fn<;I32> = (=> || { let lambdaLocal :I32 = 3 lambdaLocal })
```

```lyra
let current :I32 = counter:.value
counter::increment[]
let saved :Fn<;Unit> = counter:.increment
let @mut counterForReplacement :Counter = counter
counterForReplacement:.increment := (=> || {
    counterForReplacement:.value := 0
})
```

`:.` reads a field or bound method value. `::` selects and invokes a method. `@mut` on a field or method slot permits replacement; it does not label a method as effectful. A method can mutate its receiver's mutable fields through immutable `self`, even when the caller's receiver binding is immutable. Direct external field assignment still requires ordinary mutation authority.

Saved bound methods preserve the selected implementation and receiver while observing later receiver state. Replacing a slot affects later reads only. A directly assigned lambda receives the selected target as contextual `self`, but does not gain private access outside its lexical declaration context.

## Equality and cycles

Struct equality compares current fields structurally and terminates on cyclic graphs by tracking visited object pairs. Nested class references compare by identity. Class equality is identity. Copying either reference does not clone storage. Mutable structural values do not have stable content hashes.

There is no inheritance, interface declaration, overriding, overloading, static member, user-defined operator, or custom struct constructor.
