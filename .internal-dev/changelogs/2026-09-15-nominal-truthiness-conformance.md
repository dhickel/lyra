## Date

2026-09-15

## Git Commit

2305182c5e2b8f3bda994f16b9b9ce2d0bf92cb7

## Change Summary

Reconciled nominal truthiness between the language contract and compiler. Non-nil `struct` and `class` references are now admitted in truth-test contexts throughout static typing, semantic provenance, retained-session flow certification, and typed IR validation.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticProvenance.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionFlowCertificate.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/NominalBytecodeTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/RetainedNominalFlowCertificateTest.java`
- `lyra-compiler/src/test/resources/language/corpus/truthiness/nominal-references.lyra`
- `docs/specification/language.md`
- `docs/reference/control-flow.md`

## Behavioral Impact

Valid non-nil nominal references now work as truthy values in conditional expressions, boolean operators, `cond`, and match guards. Nilable nominal references remain false only when they are `#NIL`.

## Specification Impact

The formal and reference documentation now describe the implemented nominal truthiness rule without the former discrepancy disclosure.

## Validation

- Focused nominal bytecode, retained-flow, and language-conformance tests passed.
- Full `mvn test` passed: 1804 tests with 2 expected editor UI skips.

## Risks

The Phase 24 release audit remains independently blocked by its existing clean-build/evidence issues; this change does not claim release readiness.

## Follow-up Items

Rerun the Phase 24 release audit in an isolated clean environment before claiming release completion.
