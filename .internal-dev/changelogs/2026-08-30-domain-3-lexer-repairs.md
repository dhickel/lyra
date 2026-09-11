# Domain 3 Lexer Repairs

## Date
2026-08-30

## Git Commit
c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary
Repaired unary signed-minimum context detection, floating underflow and extreme-exponent range classification, and supplementary Unicode invalid-character diagnostics. Added focused regression coverage.

## Files
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/lex/Lexer.java`
- `lyra-compiler/src/test/java/LexerTest.java`
- `.internal-dev/knowledge/lexer-numeric-and-unicode-validation.md`
- `.internal-dev/changelogs/2026-08-30-domain-3-lexer-repairs.md`

## Behavioral Impact
Lexer failures now consistently publish `LYC-LEX-011` for the repaired numeric range cases and span unsupported supplementary code points completely. Failed lexing continues to publish no partial token artifact.

## Specification Impact
Specification Impact: none. The changes bring lexer behavior into conformance with the existing language-core and backend/runtime diagnostic contracts.

## Risks
Unary-minus classification is intentionally lexical and based on whether the preceding token can terminate an expression; parser validation remains responsible for complete expression legality.

## Follow-up Items
None.
