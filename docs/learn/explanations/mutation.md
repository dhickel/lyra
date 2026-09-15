# Binding-local mutation

Lyra bindings are immutable by default. `@mut` grants permission through one symbol:

```lyra
let values = Array<I32>[1 2]
let @mut editable = values
editable[0] := 9
```

Both names refer to the same array, so reading `values[0]` observes `9`. Only `editable` grants permission to perform the update. Mutation permission is therefore not a global property attached to the array.

The same rule applies to rebinding:

```lyra
let @mut count :I32 = 0
count := (++ count)
```

A function parameter that is used as a mutation root must carry `@mut`, and that qualifier appears in its `Fn` contract. A captured mutable binding is one shared cell seen by every closure that captures it.

Nominal objects add a separate member rule. A method may mutate its receiver's `@mut` fields through `self` even when the caller's binding is immutable. This does not let the method rebind the caller's variable, mutate an immutable field, or bypass module ownership.

An importing module can read an exported `@mut` binding but cannot assign it directly. The defining module retains mutation ownership and can expose functions that perform allowed updates.

This design keeps authority visible at each name and module boundary while allowing ordinary shared mutable state. See the [binding and member rules](../reference.md#language).
