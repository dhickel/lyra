# Retained nominal closed initializer transfer

## Date

2026-09-12

## Git Commit

e3236223e7570ac22554d50537a9bf249949baef

## Change Summary

Phase 1 of the retained nominal struct/class factory completion. The compiler proof for a retained nominal member initializer is now a closed, immutable transfer algebra instead of parallel template/call inventories plus retained producer expression trees, and a producer-certified call is reconstructed through the ordinary callable-summary machinery.

- `SessionFlowCertificate` issues exactly one `RetainedInitializerTransfer` per nominal member, present exactly when the schema member has an initializer. Variants are `Lambda`, `Value`, `Reference` and `Call`. `Call` carries the exact target declaration, its function contract, the closed argument transfers and one bounded `WriteTarget` per argument. The previous `RetainedInitializerCall` record (which retained arbitrary producer `TypedExpression` argument trees) and the parallel `memberInitializerLambdas`, `memberInitializerTemplates` and `memberInitializerCalls` inventories are gone.
- The duplicated write-target derivation is unified in `io.mindspice.lyra.compiler.semantic.flow.WriteTarget.of(...)`, shared by certificate issuance and `SemanticFlowAnalyzer`. `SemanticFlowAnalyzer.invokeCandidate` takes bounded argument write targets plus an explicit result type instead of producer expressions.
- `SemanticFlowAnalyzer.retainedNominalInitializer` returns a full `Eval` and consumes the transfer algebra through the ordinary call path: target selection before arguments, left-to-right argument effects, shared-cell refresh, candidate joins, transferred writes and failure prefixes. Only the synthetic internal call event is withheld from the typed root boundary; transferred state and effects are applied.
- Retained nominal continuity: a later generation re-registers a retained nominal as a declaration with no initializer evidence. The certificate carries the predecessor's exact certified transfers forward after verifying declaration, self, schema, member, name, visibility and optional constructor identity.
- Fabricated placeholders are gone. A member initializer that the closed algebra cannot yet represent is rejected before issuance with the structured session diagnostic `LYC-SESSION-001` at the member initializer span, so valid source never escapes as a compiler-bug exception.
- `AllocationProvenance` carries the exact producer allocation identity. Certificates certify the aggregate identity and ownership witness of every fresh allocation reachable from their own callable summaries, and retained constructions are certified through bounded `RetainedConstruction` evidence keyed by `SummaryCallId`.
- Retained aggregate ownership is no longer laundered as local: producer arrays become certified cross-module views and consumer mutation follows nominal fields, so the established imported-mutation diagnostic fires.
- Retained callable effects are propagated with consumer-call attribution and producer terminal provenance instead of being dropped, while dynamic targets still require current or retained lambda ownership.
- Certificate proof predicates are exact: object facts require an exact `ObjectProofKey` (identity plus the full ownership witness) or exact retained-construction evidence, and certified aggregate use sites require the exact origin span or an exactly collected use span. Nil provenance is certified exactly by source site and span.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionFlowCertificate.java` (modified)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java` (modified)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowFactValidator.java` (modified)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/WriteTarget.java` (new)
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/RetainedNominalFlowCertificateTest.java` (new)
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/NominalSessionTest.java` (extended)
- `.internal-dev/knowledge/nominal-session-linkage.md` (updated)

## Behavioral Impact

- `retainedClassFactoriesPreserveCallableReturningDefaultCalls` no longer fails with a compiler invariant error; the whole nominal session suite passes (15 tests).
- A retained class field initialized by a direct call is reconstructed through the producer's callable summary, so the field receives the real callable or aggregate fact instead of a placeholder.
- Retained transfers survive more than one later generation; previously the second generation after a class declaration silently replaced certified transfers with empty evidence.
- Nested producer constructions, namespace intrinsic defaults, nil defaults, constructor field writes and retained eager effects now behave correctly across generations.
- Mutating an aggregate allocated by a retained factory now raises the established imported-mutation diagnostic instead of silently succeeding.

## Specification Impact

Living specifications are reconciled in the plan's final audit phase. Contracts that must be updated there: the retained initializer transfer algebra and its coverage inventory, exact allocation provenance and use-site proof evidence, predecessor transfer continuity, the structured unsupported-form diagnostic, the finite fresh-provenance caveat, and the separation between compiler proof state and runtime producer authority.

## Risks

- The closed algebra currently covers lambda, literal, array and tuple literal composition, exact declaration projection, and direct or namespace direct call. Operators, blocks and rebindings, conditionals, coalesce, match, short-circuit, conversions, indexing and legal loop or callback forms are not yet representable and are rejected with `LYC-SESSION-001`. Completing that coverage is the next phase's scope.
- Fresh allocation identity is still producer-site scoped: two constructions from the same retained factory share one certified allocation identity. That is a sound over-approximation of aliasing, not the exact per-construction provenance the plan requires.
- Compiler-level nominal fuzz generator and oracle coverage is added in the campaign phase; this phase's behavior is covered by the new certificate suite and the nominal session suite.

## Follow-up Items

- Complete the closed expression algebra for every legal initializer composition.
- Instantiate summary and default allocations with distinct consumer construction and nested invocation provenance while preserving genuine aliases and finite repeated-site soundness.
- Extend the compiler and session fuzz models with retained nominal construction coverage.
- Reconcile specifications, decisions, docs and the Phase 24 audit inventory.
