# Adjacent Direct-Call Comma Documentation

## Date

2026-09-13

## Git Commit

2b49f7ca9c6382ce1591121e2900f0a38ef2115f

## Change Summary

Documented the current parser rule that adjacent sibling expressions beginning with ordinary `::` require a comma in comma-capable lists because postfix direct-call parsing is greedy and whitespace is not an expression boundary.

## Files

- `.internal-dev/specifications/language-core.md`
- `lyra-compiler/src/main/resources/grammar_spec.md`
- `docs/language-testing.md`
- `.internal-dev/changelogs/2026-09-13-adjacent-direct-call-comma-documentation.md`

## Behavioral Impact

Documentation only. Compiler and runtime behavior are unchanged.

## Specification Impact

Clarifies the normative call/accessor grammar, including the required disambiguating comma, the resulting parse without it, and why callable-application parentheses are not a grouping workaround.

## Risks

The documented rule is a current grammar limitation and remains in tension with the general statement that commas are optional list separators. A future syntax decision may replace this disambiguation rule.

## Follow-up Items

Consider filing a parser/language-design issue if adjacent ordinary direct calls should become unambiguous without commas.
