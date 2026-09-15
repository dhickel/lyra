# Evaluation order and laziness

Lyra evaluates strict expressions left to right. This includes a call target or receiver, arguments, operator operands, collection elements, declarations, and block forms.

For a call, target selection happens before argument evaluation. If an argument replaces the function or method slot being called, the current call still invokes the value selected before that replacement. A later call sees the new slot.

Most forms are eager, but several forms select work lazily:

- `and` stops at the first falsey operand.
- `or` stops at the first truthy operand.
- A conditional evaluates only its selected branch.
- Nil coalescing evaluates its fallback only for `#NIL`.
- `match` evaluates its subject once, then reached patterns and guards in source order, and only the selected result.
- `cond` evaluates conditions in source order and only the selected result.

`xor` is not short-circuiting. It evaluates all operands before computing odd truthy parity.

A block makes order explicit:

```lyra
{
    count := (++ count)
    io->::println[String[count]]
    count
}
```

Its final expression is the block value. An empty block returns Unit. Completed effects are observable if a later form fails because Lyra does not imply transactional rollback.

See the [formal evaluation rules](../reference.md#language).
