# Bindings and functions

All named values, including functions, are introduced with `let`. Bindings are immutable unless marked `@mut`.

## State changes

```lyra
let answer :I32 = 42
let inferred = 42
let @mut count :I32 = 0
count := (+ count 1)
(:= count 2)
```

Assignment has infix and parenthesized prefix forms. It has no bracket form. Array element and mutable field assignment also use `:=`. A mutation root must carry the required `@mut` permission. Permission is local to the binding, not deep immutability: aliases can refer to the same mutable array or object.

An importing module may read an exported `@mut` binding but cannot mutate it directly. The declaring module can expose mutation through a function.

## Scope, replacement, and capture

Modules, blocks, and lambdas create lexical scopes. Declarations are source ordered.

A later private declaration may reuse a same-scope name. It creates a new symbol from that point onward. Earlier references and closures keep the prior binding, even if the replacement has another type. Public top-level names and re-exports cannot be replaced.

A captured immutable binding retains its selected value or reference. A captured `@mut` binding is one shared mutable cell:

```lyra
let @mut count :I32 = 0
let next :Fn<;I32> = (=> || {
    count := (++ count)
    count
})
```

## Lambdas

General form:

```text
(=> ReturnModifier* [:ReturnType] |Parameter*| body)
```

A lambda gets one complete `Fn` signature from context or from complete inline annotations:

```lyra
let addOne :Fn<I32;I32> = (=> |value| (+ value 1))
let addOne = (=> :I32 |value :I32| (+ value 1))
let find = (=> @nil :String |key :String| #NIL)
```

Redundant annotations must agree. Types are not inferred from recursive call sites or an unconstrained body.

The compact form omits `=>` and lambda parentheses:

```lyra
iter[(0..3:1) |value| ::consume[value]]
```

It is accepted only where a complete expected function type exists and cannot directly initialize a named declaration without such context.

Completely typed lambda-valued `let` declarations are signature-predeclared. This permits self recursion, forward function references, and mutual recursion. Ordinary eager values cannot be read before initialization, and eager cycles are errors.

## Calls and accessors

| Form | Meaning |
| --- | --- |
| `(callee arg1 arg2)` | Call a callable value. `(f)` is a zero-argument call. |
| `::name[arg1 arg2]` | Directly call a resolved local or imported source function. |
| `receiver::method[args]` | Select and call a method with implicit `self`. |
| `receiver:.member` | Read a field/value or obtain a bound method value. |
| `module->::name[args]` | Direct namespace call. |
| `module->:.name` | Namespace value access. |

Calls have exact positional arity. There is no grouping syntax, partial application, currying, named arguments, defaults, omitted arguments, or varargs.

The target or receiver is evaluated and selected once before arguments. Arguments evaluate left to right. Replacing a mutable function or method slot during argument evaluation does not retarget the in-progress call.

A method read such as `counter:.increment` captures the current method selection and receiver. Later slot replacement affects new reads, not saved values. Assigning an existing callable preserves its existing captures and receiver. A directly assigned method lambda receives contextual `self`, but only the lexical access privileges of its definition site.

After exact resolution, parenthesized and bracket direct spellings have the same call semantics and tail behavior. Computed callable targets still use runtime authentication. `::` is not valid without bracket arguments.

Compiler-recognized built-ins are not callable values and do not use `::`. Write `+[a b]`, `I32[value]`, `match[...]`, `iter[...]`, or `while[...]`, not `::+[...]`, `::I32[...]`, `::match[...]`, `::iter[...]`, or `::while[...]`.
