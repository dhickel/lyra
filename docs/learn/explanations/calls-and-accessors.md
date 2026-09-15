# Calls and the three accessors

Lyra uses separate syntax for namespace qualification, value access, and direct invocation.

`->` traverses a module namespace:

```lyra
math->:.pi
math->::double[21]
```

`:.` reads a value or member. It is used for module values, fields, tuple positions, built-in `length`, and receiver-bound method values:

```lyra
array:.length
point:.x
pair:.0
counter:.increment
```

`::` directly invokes a resolved source function or method name:

```lyra
::double[21]
counter::increment[]
```

A callable value uses parenthesized application:

```lyra
let saved :Fn<;Unit> = counter:.increment
(saved)
```

Parentheses are not general grouping. `(function argument)` is a call. The exact wrapper `(::function[])` preserves that direct call, but it does not add general grouping syntax.

Compiler-recognized built-ins use bare heads, never `::`. Examples include `+[a b]`, `I32[value]`, `Array<I32>[1 2]`, `match[...]`, `cond[...]`, `iter[...]`, and `while[...]`. `cond` supports both its parenthesized and direct-bracket special forms. `std->io` exports are ordinary resolved module functions, so `io->::println["text"]` does use `::`.

These distinctions preserve static name resolution and call-target selection. A matching name, JVM descriptor, or generated function-interface shape does not by itself grant callable authority.

See the [grammar and call rules](../reference.md#language).
