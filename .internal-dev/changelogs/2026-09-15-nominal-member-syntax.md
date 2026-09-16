# Remove `let` from nominal member declarations

## Date

2026-09-15

## Git Commit

7f192fd34527948e32a979446b50e9de4f507ff1

## Change Summary

Struct and class members now use `Modifier* name :Type [= expression]` directly. Ordinary top-level, block-local, and lambda-local declarations continue to require `let`. A `let` token at the start of a nominal member is rejected with a structured parse diagnostic.

## Files

- `lyra-compiler/src/main/java/`
- `lyra-compiler/src/test/java/`
- `lyra-compiler/src/test/resources/language/corpus/nominal/`
- `lyra-repl/src/test/java/`
- `docs/`
- `.internal-dev/specifications/`
- `.internal-dev/knowledge/`
- `tools/phase24-conformance-coverage.tsv`

## Behavioral Impact

Nominal examples and source fixtures must omit `let` from fields and `Fn`-typed method slots. Existing `:Type[...]` construction compatibility and ordinary `let` bindings are unchanged.

## Specification Impact

Updated the language-core specification, formal language specification, grammar synchronization resource, reference/tutorial documentation, nominal knowledge record, and Phase 24 coverage evidence. Added positive syntax coverage for top-level, block-local, and lambda-local `let` bindings and negative coverage for obsolete nominal-member syntax.

## Validation

- `git diff --check`
- `mvn -q -pl lyra-compiler -am -DskipTests compile`
- targeted compiler and REPL syntax/nominal tests
- `mvn -q test` (1,805 tests; two graphical editor tests skipped without a display)

## Risks

The former `let`-prefixed nominal member form is intentionally incompatible and requires source migration. The Phase 24 release audit and graphical/native editor release validation remain separate release-gate work.

## Follow-up Items

- Run the Phase 24 release audit when its clean-build and evidence prerequisites are available.
- Exercise graphical and native editor packaging on supported target platforms.
