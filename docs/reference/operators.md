# Operators

Built-in operators have equivalent parenthesized and bracket forms:

```lyra
(+ a b c)
+[a b c]
```

Assignment is the exception: use `target := value` or `(:= target value)`, never `:=[...]`.

## Operator table

| Operator | Arity | Operands and result | Evaluation |
| --- | --- | --- | --- |
| `+` | 2 or more | Numeric common type, or all `String` to `String` | Left fold |
| `-` | 1 or more | Numeric; unary negation or left fold | Strict |
| `*` | 2 or more | Numeric common type | Left fold |
| `/` | 1 or more | Numeric; reciprocal or left fold | Strict |
| `%` | exactly 2 | One common integer type | Strict |
| `^` | exactly 2 | Numeric exponentiation | Strict |
| `<`, `<=`, `>`, `>=` | 2 or more | Numeric operands, `Bool` result | Chained adjacent comparisons |
| `==`, `!=` | 2 or more | Compatible typed values, `Bool` | Value or structural equality |
| `eq?`, `!eq?` | 2 or more | Same identity-bearing type, `Bool` | Reference/function identity |
| `and`, `or` | 2 or more | Truth-testable operands, `Bool` | Short-circuit left to right |
| `xor` | 2 or more | Truth-testable operands, `Bool` | Evaluates all; odd truthy parity |
| `not` | exactly 1 | Truth-testable operand, `Bool` | Strict |
| `++`, `--` | exactly 1 | Any numeric type, same type | Pure checked add/subtract one |

`++` and `--` never mutate:

```lyra
count := (++ count)
count := ++[count]
```

## Arithmetic

Operands choose the least common type that losslessly contains all operand domains. If none exists, use explicit conversion. Integer arithmetic is checked. Signed overflow, unsigned overflow or underflow, division by zero, and remainder by zero fail with `LYR-ARITH`. Constant failures are compile errors.

Integer `/` returns `F64` by default or `F32` under a complete expected `F32` context. Integer `%` stays integer. Integer power requires a nonnegative integer exponent. Floating operations reject non-finite results instead of exposing NaN or infinity.

## Equality and identity

`==` and `!=` compare typed values. Arrays, tuples, and structs compare structurally. Strings compare UTF-16 content. Classes compare by instance identity under value equality. When at least one operand is `Bool`, value equality compares the operands' truthiness.

`eq?` and `!eq?` are available only for identity-bearing types. Arrays, classes, and functions have identity. Tuples and strings do not. Structs do not gain a source identity operator. Repeated reads of one function binding or unchanged method slot retain identity; separate lambda evaluations produce distinct identities.

`eqt?`, `eqv?`, `nor`, `nand`, and `xnor` do not exist.
