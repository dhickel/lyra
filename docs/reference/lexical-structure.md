# Lexical structure

Lyra source is UTF-8. The compiler ignores one initial UTF-8 BOM and rejects malformed input. Source spans use zero-based, end-exclusive UTF-16 code-unit offsets. Rendered line and column numbers are one-based and also count UTF-16 code units.

## Identifiers and reserved words

Identifiers are case-sensitive ASCII names matching `[A-Za-z_][A-Za-z0-9_]*`. Punctuation is never part of a name.

Reserved words are:

```text
let import as match cond when iter while struct class
and or xor not
I8 I16 I32 I64 U8 U16 U32 U64 F32 F64
Bool Char String Unit Array Range Tuple Fn
```

`_` is an ordinary identifier except in the wildcard position of `match` and `cond` arms. Nominal declaration names must begin with an uppercase ASCII letter.

## Comments and whitespace

```lyra
// line comment
/* block comment /* nested block */ still in comment */
```

Trivia normally separates tokens and otherwise has no effect. Exact spacing is required at these boundaries:

| Construct | Required spelling | Invalid examples |
| --- | --- | --- |
| Named annotation | `name :Type` | `name:Type`, `name : Type` |
| Return annotation | `:Type` | `: Type` |
| Construction | `Type[...]` (canonical); `:Type[...]` (legacy) | `: Type[...]` |
| Bare negative literal | `-1` | `- 1`, `-/*comment*/1` |

A trivia-separated minus remains an operator and therefore needs operator syntax such as `(- 1)` or `-[1]`.

## Delimiters and punctuation

| Tokens | Use |
| --- | --- |
| `( )` | callable application, operators, lambdas, conditionals, ranges, and special forms; not general grouping |
| `[ ]` | bracket application, indexing, literals, conversions, and direct calls |
| `{ }` | blocks, class/struct bodies, and selective imports |
| `| |` | lambda parameters |
| `->` | namespace access, conditional arrows, and match/cond arrows |
| `:.` | value, field, bound-method, tuple-position, and `length` access |
| `::` | direct source function or method invocation |
| `:=` | assignment |
| `..`, `..=` | exclusive and inclusive ranges |

Parentheses are not a grouping operator. The exact `(::f[])` form preserves a direct call, but `(::f[] x)` calls the value returned by `f` with `x`.

## Comma separators

A comma is optional inside parameter, argument, type-argument, array, and tuple lists. They are not globally ignored.

A special comma is needed when adjacent sibling expressions begin with `::`, because postfix parsing is greedy:

```lyra
(+ ::left[], ::right[])
+[::left[], ::right[]]
{ ::left[], ::right[] }
```

Without that comma, the second call is parsed as a receiver call on the first result. In modules, blocks, and `match` or `cond` arm sequences, a comma is accepted only before a sibling beginning with `::`. Leading, repeated, trailing, and other statement-level commas are invalid.

See [literals and types](literals-and-types.md) and [bindings and functions](bindings-and-functions.md).
