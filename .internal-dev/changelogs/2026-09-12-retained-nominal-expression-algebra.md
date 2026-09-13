# Retained nominal expression algebra and fresh allocation provenance

## Date

2026-09-12

## Git Commit

c6f4274f96d0193da2f9428eae436ade08473836 (phase 2 commit containing the described changes)

## Change Summary

Phase 2 of the retained nominal struct/class factory completion: the closed initializer transfer algebra now covers every currently legal member-initializer composition, each accepted transfer is deeply validated at issuance, and allocations minted while executing a retained factory inside a consumer generation receive exact consumer-scoped fresh provenance.

- `RetainedInitializerTransfer` gained closed variants for the remaining legal forms: `Composite` (array/tuple literals with dynamic children), `Apply` (operators, conversions, narrowing, ranges, short-circuit), `Alternative` (conditional, coalesce, match, with explicit prefix steps and independent branches), `Sequence` (blocks), `Declare`/`Rebind` (block-local declarations and rebinding), `Project` (indexing, tuple and nominal projections, array/string length), `Construct` (nested nominal construction with certified construction evidence), `CallableCall` (callable-value calls) and `Loop` (iter/while callback loops under the existing finite fixed-point budget).
- `SemanticFlowAnalyzer` consumes each variant with ordered evaluation, ordinary target-before-arguments selection and shared-cell refresh, transferred writes/binds/effects and failure prefixes; only synthetic internal events stay unpublished. `Alternative` follows the ordinary conditional/coalesce/match discipline: prefix evaluated once, then each branch independently from the post-prefix state, joined with the same join the ordinary path uses.
- Per-construction fresh provenance: the new `io.mindspice.lyra.compiler.semantic.flow.RetainedAllocationDerivation` derives a consumer-context-keyed identity for every retained summary/default allocation, with tagged ranges kept disjoint from ordinary source and summary identities. Distinct constructions, generations and nested invocation nodes receive distinct identities; intra-value aliases are preserved, genuinely shared returned storage is not freshened, and repeated execution at one finite analysis site stays finite. Producer-allocated storage remains a certified cross-module view, so the established imported-mutation diagnostic still fires.
- Deep issuance validation: transfer kinds, operator legitimacy, child-versus-declared types, route well-formedness, nominal schema agreement, index contracts and allocation provenance are validated, and the new negative tests reject forged child types, mismatched schemas and underivable routes.
- Exact route evidence: `RouteDerivation`/`RouteProof` make object, aggregate and callable certification route-exact, so a fact with a real identity and witness but a forged route is rejected. Exact object proofs (`ObjectProofKey`) now include the fact route.
- The legality inventory covers every source-admissible initializer kind with a per-form probe, including later-generation construction and value assertions.
- `IrValidator` link validation is aligned with the semantic gate: a foreign callable is accepted when the consumer-aware route verifier or the certificate's identity/capture evidence accepts it, while the semantic flow validator remains the authoritative route-exactness gate.
- Projection index contracts now mirror `TypeChecker.checkIndexAccess` (non-nil integer types), which fixed a crash on legal indexed defaults such as `Array<I32>[1 2][0]` and string indexing with an unsuffixed literal index.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionFlowCertificate.java` (modified)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java` (modified)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowFactValidator.java` (modified)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/InitializationAnalyzer.java` (narrow retained effect-dependency change)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java` (link validation alignment)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/RetainedAllocationDerivation.java` (new)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/ValueFormula.java`, `CallableFlow.java`, `CallableSummarySet.java`, `CallableSummarySolver.java`, `OwnershipRequirement.java`, `SummaryObjectResolver.java` (additive retained-context plumbing)
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/RetainedNominalFlowCertificateTest.java` (extended)
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/NominalSessionTest.java` (extended)
- `.internal-dev/knowledge/nominal-session-linkage.md` (updated)
- `.internal-dev/bugs/retained-nominal/unit-initializer-later-observation-linkage.md` (new; GitHub issue #7)

## Behavioral Impact

- Operators, blocks with declarations and rebinding, conditionals, coalesce, match, conversions, ranges, indexing, projections, nested construction, callable-value calls and iter/while loops are now first-class retained member initializers across generations; previously they were rejected with the structured session diagnostic.
- Two constructions of the same retained factory no longer share one abstract allocation identity.
- Legal indexed defaults and string indexing no longer crash the compiler.
- A forged route can no longer be certified for an object, aggregate or callable fact.

## Specification Impact

Living specifications, decisions and the coverage documentation are reconciled in the plan's final audit phase. Contracts that must be updated there: the complete transfer algebra inventory and its legality classification, the consumer-scoped fresh-provenance derivation and its tagged identity domains, route-exact certification evidence, the IR/semantic validation boundary, and the known runtime linkage gap tracked as issue #7.

## Risks

- The known pre-existing defect in `.internal-dev/bugs/retained-nominal/unit-initializer-later-observation-linkage.md` (GitHub issue #7) remains open: observing a retained nominal whose member initializer is Unit-typed or imports an intrinsic module, one generation after construction, fails with `LYR-LINK`. Four inventory cases pin that structured failure until it is fixed; it predates this phase and is not caused by the transfer algebra.
- The retained algebra is now closed for currently source-admissible forms, so a future syntax change must extend the inventory test and the algebra together.
- `certifiesLinkedCallable` is intentionally route-blind for IR link validation; it must not be used as semantic route authorization.

## Follow-up Items

- Fix issue #7 and flip the four pinned inventory cases to value assertions.
- Reconcile specifications, decisions, docs, the fuzz generator/oracle and the Phase 24 audit inventory in the remaining phases.
