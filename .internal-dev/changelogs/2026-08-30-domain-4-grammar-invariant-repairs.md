# Domain 4 Grammar Invariant Repairs

## Date
2026-08-30

## Git Commit
c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary
Repaired grammar replay validation so reassignment enclosure, exact primary-token positions, aggregate/member roles, and modifier/operator metadata are derived from immutable descriptor structure rather than broad token categories.

## Files
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/grammar/GrammarProgram.java`
- `lyra-compiler/src/test/java/GrammarMatcherTest.java`
- `.internal-dev/knowledge/grammar-lexer-boundary-validation.md`

## Behavioral Impact
Valid top-level `(x) := 1` matching is accepted. Structurally mutated descriptors with nested primary pointers, false aggregate prefixes, suffixed numeric members, roleless metadata, or mismatched import/namespace arrows are rejected at lexical replay validation.

## Specification Impact
Specification Impact: none. The repair enforces existing `language-core.md` syntax and `backend-runtime.md` immutable phase-boundary requirements without changing the language contract.

## Risks
Validation is intentionally stricter for manually constructed descriptors. Matcher-produced descriptors remain covered by the complete compiler test suite.

## Follow-up Items
None.
