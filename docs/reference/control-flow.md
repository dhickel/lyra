# Control flow

Lyra evaluates strictly and eagerly from left to right, except where a form is documented as short-circuiting. Blocks and control forms are expressions.

## Blocks

```lyra
{
    let x :I32 = 40
    (+ x 2)
}
```

A block creates a lexical scope, evaluates forms in order, and returns its final expression. An empty block returns Unit.

## Truthiness

False values are `#F`, `#NIL`, numeric zero, floating `+0.0` and `-0.0`, the empty string, an empty array, and Unit. Non-nil nominal references are true, alongside `#T`, nonzero finite numbers, nonempty strings, nonempty arrays and tuples, functions, and characters. A nilable nominal is false only when it is `#NIL`. Boolean operators return `Bool` rather than an operand.

## Conditionals

```lyra
(predicate -> thenValue : elseValue)
(predicate value -> value : fallback)
(predicate -> effect)
```

The predicate evaluates once. The optional unannotated binding exists only in the true branch. For `@nil T`, it has type `T`; otherwise it has the predicate type. Only one branch evaluates. Full branch results must have an exact or permitted losslessly widened common type. A then-only conditional always has type Unit and discards the selected expression result.

Nil-only coalescing has a separate form:

```lyra
(nilableValue : fallback)
```

It accepts only `@nil T`. A non-nil value is returned even if otherwise falsey. The fallback evaluates only for `#NIL`.

## Match

```lyra
(match value
  10 -> "ten"
  _ when (> value 20) -> "large"
  _ -> "other")

match[value
  10 -> "ten"
  _ -> "other"
]
```

The subject evaluates once. Arms are tried in source order. A pattern may be any expression and is compared with typed value equality. It introduces no binding. A guard runs only after its pattern matches. Only the selected result runs.

Every match ends with an unguarded `_ -> fallback`; the fallback may be the only arm. No arm follows it. Results use the same contextual typing and unification rules as a full conditional. Match does not automatically narrow a nilable subject.

## Cond

`cond` is subjectless and supports equivalent parenthesized and direct-bracket forms:

```lyra
(cond
  (< value 0) -> 0
  (> value 100) -> 100
  _ -> value)

cond[
  (< value 0) -> 0
  (> value 100) -> 100
  _ -> value
]
```

Conditions evaluate once in order using ordinary truthiness. The first truthy result is selected. `when` is not valid in `cond`. A final `_` fallback is mandatory. Both spellings have identical lazy evaluation and result typing.

## Ranges and `iter`

```lyra
let up :Range<I32> = (0..5:1)       // 0, 1, 2, 3, 4
let down :Range<I32> = (5..=1:-2)   // 5, 3, 1
iter[up |value| ::consume[value]]
iter[up || ::tick[]]
```

`..` excludes the endpoint. `..=` includes it if the step reaches it. Start, end, and step evaluate once, left to right, at range construction. A range is immutable, reusable, and does not allocate its sequence of elements.

Bounds and step use one signed integer type: `I8`, `I16`, `I32`, or `I64`. Zero step is a compile error when constant and `LYR-ARITH` otherwise. A step directed away from the endpoint yields no elements. An inclusive equal-endpoint range yields one element; an exclusive one is empty.

`iter` takes exactly a range and either `Fn<T;Unit>` or `Fn<;Unit>`, then returns Unit. The range and callback expressions are selected once in that order. The callback runs synchronously once per element. Failure stops traversal. Empty traversal invokes no callback. Each one-argument callback invocation has a fresh immutable parameter binding.

## `while`

```lyra
let @mut count :I32 = 0
while[
  || (< count 10)
  || { count := (++ count) }
]
```

`while` takes exactly two callbacks: `Fn<;Bool>` followed by `Fn<;Unit>`. Its result is Unit. It evaluates both callback expressions once, left to right. It calls the predicate before every action, including the first. The final false predicate call is observable. General truthiness is not accepted for this predicate contract. Results from the second callback are not implicitly dropped.

`iter` and `while` use constant JVM stack for repetition and reach cooperative application/session safe points at backedges. There is no `return`, `break`, `continue`, `do while`, or dedicated loop statement.
