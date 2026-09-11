# Namespace Direct Call Argument Collection Repair

## Date

2026-09-02

## Git Commit

`c02851d4a3a56ac652079724664e7de2224c6295`

## Change Summary

Separated namespace member access from namespace direct call during semantic declaration collection. Namespace direct calls now recursively collect every argument expression exactly once in source order before resolution.

Added regression coverage for compact and general lambda, block, tuple/array aggregate, and mutation arguments, including exact identity/scope/reference/capture counts, argument order and spans, imported-ownership diagnostics, unchanged sibling call kinds, structured failures, and absence of partial artifacts.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticResolver.java`
- `lyra-compiler/src/test/java/SemanticResolverTest.java`
- `.internal-dev/knowledge/semantic-aggregate-provenance.md`

## Behavioral Impact

Valid namespace direct calls no longer reach resolver/type-checker traversal with uncollected nested lambda identities, block scopes, local declarations, aggregate sites, or mutations. Invalid nested arguments now return their existing structured resolve/type diagnostics with exact source spans instead of exposing collection-related `IllegalStateException` failures.

Validation completed with the focused 203-test resolver/parser/type/Phase-11 semantic matrix, `mvn -pl lyra-compiler clean test` (263 tests), `mvn clean verify` (all four reactor projects), and `git diff --check`.

## Specification Impact

Specification Impact: none. The repair restores the existing complete immutable phase-artifact and structured-diagnostic contracts without changing source-language semantics or Phase-11 architecture.

## Risks

The production change is confined to the existing declaration collector branch and reuses its established recursive traversal. No public API, evaluator, flow model, backend, runtime, or CLI behavior was added.

## Follow-up Items

- Independent read-only validation of this defect remains with the parent workflow.
- This record does not declare Gate 11E or Phase 11 passed.
