# Lexer Numeric and Unicode Validation

## Topic
Exact numeric range classification and UTF-16 handling in the Lyra lexer.

## Source References
- `.internal-dev/specifications/language-core.md`
- `.internal-dev/specifications/backend-runtime.md`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/lex/Lexer.java`
- `lyra-compiler/src/test/java/LexerTest.java`

## Key Takeaways
- Once decimal grammar has been validated, `BigDecimal` construction failure indicates an exponent/scale representation limit and must be reported as `LYC-LEX-011`, not malformed syntax.
- Floating range checks must reject both infinity and rounding of an exact nonzero `BigDecimal` to primitive zero. Exact decimal zero remains valid.
- The signed-minimum magnitude exception belongs only to an integer immediately governed by a unary minus. A preceding binary minus must not widen the accepted literal magnitude. `Lexer.add()` normally consumes that one-token permission, but it must preserve it through the `LEFT_BRACKET` in unary F-expression spelling such as `-[128I8]`; clearing it for every emitted token rejects the legal minimum before parsing.
- An adjacent bare negative literal (`-1`, `-128I8`, `-0.0`) is a *parser* form, not a lexer form: the lexer still emits `MINUS` plus a nonnegative magnitude, and the matcher records a `NEGATIVE_LITERAL` production that replays to the ordinary unary-minus `OperatorBracket` AST node. Adjacency is decided by `Token.isAdjacentToPrevious()` on the literal token, so whitespace, a comment, or a newline between `-` and the number keeps the operator meaning and its bracket-required diagnostic. Never make the sign part of the literal magnitude, or the existing exactness/suffix/range checks and the signed-minimum rule stop applying uniformly.
- `--1` must stay invalid: the lexer consumes `--` as the decrement operator before any adjacency rule applies.
- Invalid source characters are diagnosed by Unicode code point while source spans remain UTF-16 code-unit based; supplementary code points therefore consume a two-unit span.

## Project Relevance
These checks preserve exact lexer artifacts and stable structured diagnostics at numeric and Unicode boundaries without moving parser or backend behavior into the lexical phase.

## Open Questions
None for the repaired lexer scope.
