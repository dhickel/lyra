## Date

2026-09-15

## Git Commit

fc363491d3d4af08cc0a701ffef67b51e71919af

## Change Summary

Added `cond[...]` as the direct-bracket spelling of the existing subjectless conditional special form, matching the two surface forms already provided by `match`.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/grammar/GrammarMatcher.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/grammar/GrammarProgram.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/parse/Parser.java`
- `lyra-compiler/src/main/resources/grammar_spec.md`
- `.internal-dev/specifications/language-core.md`
- `.internal-dev/specifications/decisions.md`
- `docs/specification/language.md`
- `docs/reference/control-flow.md`
- `docs/reference/deferred-features.md`
- `docs/learn/tutorials/05-control-flow.md`
- `docs/language-testing.md`
- `lyra-compiler/src/test/java/GrammarMatcherTest.java`
- `lyra-compiler/src/test/java/ParserTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/MatchBoundaryIntegrationTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/conformance/FuzzInfrastructureTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/conformance/TypedProgramGenerator.java`
- `lyra-compiler/src/test/resources/language/corpus/match/condition-bracket.lyra`

## Behavioral Impact

`cond[...]` now parses, replays, validates, type-checks, lowers, and executes with the same lazy arm semantics, truthiness, fallback requirements, and result typing as `(cond ...)`. The obsolete `::cond[...]` spelling remains rejected.

## Specification Impact

The language-core specification, grammar resource, formal specification, reference, tutorial, and testing matrix now describe both accepted `cond` spellings. The prior parenthesized-only decision is amended.

## Validation

- Focused grammar, parser, JVM integration, fuzz infrastructure, and conformance tests passed.
- Full `mvn test` passed: 1805 tests, with 2 expected editor UI skips.

## Risks

No known implementation risk remains for the new surface form. The Phase 24 release audit remains independently blocked by its existing clean-build/evidence issues.

## Follow-up Items

Rerun the Phase 24 release audit in an isolated clean environment before claiming release completion.
