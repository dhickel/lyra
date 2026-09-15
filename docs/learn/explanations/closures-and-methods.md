# Functions, closures, mutable cells, and saved methods

A Lyra function is a lambda value. A named function is a `let` binding whose initializer is a lambda. Each lambda evaluation creates a function identity.

Closures capture bindings. An immutable capture keeps its selected value or reference. A captured `@mut` binding becomes one shared mutable cell:

```lyra
let @mut count :I32 = 0
let next :Fn<;I32> = (=> || {
    count := (++ count)
    count
})
```

Repeated calls to `next` observe the same cell. Replacing a private declaration later creates a new symbol; existing closures retain the earlier one.

A method is a function-valued class member with an implicit receiver named `self`. Reading `counter:.increment` selects the current method slot and produces a receiver-bound callable. Saving it retains both the selected implementation and receiver:

```lyra
let saved :Fn<;Unit> = counter:.increment
```

If a mutable method slot is replaced later, `saved` still invokes the older selection. A fresh `counter::increment[]` or `counter:.increment` selects the current slot. Object state itself is not snapshotted, so the saved method continues to observe the receiver's current fields.

Assigning an existing bound callable does not rewrite its receiver. A forwarding lambda explicitly requests a new lookup each time:

```lyra
let forward :Fn<;Unit> = (=> || counter::increment[])
```

Closures and module instances are owner-thread and lifecycle checked. A retained closure stops being callable when its owning producer is closed.

See the [function and nominal member rules](../reference.md#language).
