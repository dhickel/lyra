# Domain 4 Grammar/Lexer Boundary Repairs

## Date
2026-08-30

## Git Commit
c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary
Repaired adjacent composite-type closure and assignment tokenization without changing ordinary `>=` comparisons, and strengthened grammar descriptor replay validation for production-specific metadata roles.

## Files
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/lex/Lexer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/grammar/GrammarProgram.java`
- `lyra-compiler/src/test/java/LexerTest.java`
- `lyra-compiler/src/test/java/GrammarMatcherTest.java`
- `.internal-dev/knowledge/grammar-lexer-boundary-validation.md`

## Behavioral Impact
Composite type closures followed immediately by `=` now publish adjacent `GREATER` and `EQUAL` tokens with exact source spans, including nested types. Ordinary `a>=b` remains one `GREATER_EQUAL` token. Constructed grammar programs with incorrect production primary tokens, delimiter roles, modifier lists, or comma correspondence are rejected during lexical-artifact validation.

## Specification Impact
None. The changes enforce the existing no-whitespace assignment and immutable phase-boundary contracts without changing language syntax or backend behavior.

## Risks
The lexical context is deliberately limited to built-in composite type names (`Array`, `Tuple`, and `Fn`); user generics remain outside the living language contract.

## Follow-up Items
None.
