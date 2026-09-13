# Retained nominal phase 3 constructor/runtime integration

## Date

2026-09-13

## Git Commit

Baseline: `c6f4274f96d0193da2f9428eae436ade08473836`

## Change Summary

Checkpointed the phase-3 constructor/runtime integration slice after reproducing failing retained-constructor paths and repairing the initial compiler validation and provenance defects. Added focused coverage for constructor compositions and ordering, exactly-once factory emission, failure and cancellation cleanup, source mapping, attachment, recovery, and owner lifetime. No runtime production change was retained. Two independent review rounds then demonstrated remaining proof-closure defects recorded in `.internal-dev/bugs/retained-nominal/phase3-constructor-proof-closure.md`; this changelog is an explicit WIP checkpoint and not a completion claim.

Confirmed fixed by the second round: nested retained construction contexts (four-level `Outer -> Middle -> Leaf -> Deep` chains execute across generations under their exact derived context), exact certificate destination assertions, and exact producer source-map assertions.

Still open (recorded in the bug report with reproductions): a mutable alias of immutable `self` bypasses the constructor-only mutation narrowing; a valid rebind-then-tuple retained initializer fails consumer compilation; an overwritten intermediate rebind allocation is still certified; and a nominal object passed to a callable that ignores it is falsely accepted by derived-object certification.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionFlowCertificate.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedTopologyValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/RetainedNominalFlowCertificateTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/NominalBytecodeTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/NominalSessionTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ApplicationAttachmentTest.java`
- `docs/language-testing.md`
- `.internal-dev/bugs/retained-nominal/unit-initializer-later-observation-linkage.md`
- `.internal-dev/knowledge/nominal-session-linkage.md`

## Behavioral Impact

- Constructor `self` may now legally root member and nested aggregate mutations without being misclassified as an immutable declaration rebinding.
- Consumer-derived retained arrays keep exact producer scope, source span, and flow-site provenance in canonical allocation indexes.
- Retained construction certification now includes aggregate and nested-object allocations reachable through the retained constructor summary, in addition to member initializer transfers.
- Runtime behavior is pinned for observable argument/default/constructor ordering, nontransactional completed effects on failure or cancellation, no staged-name publication, producer recovery, exactly-once root/default initialization, attached source factories, and root-owned closure lifetime.

## Specification Impact

Specification Impact: none. The changes repair implementation and proof coverage to match the existing retained-construction, nontransactional failure, owner-lifetime, and attachment contracts; no public contract changed.

## Risks

- Independent review demonstrated three unresolved defects: the immutable-`self` mutation exception is broader than constructor scope; nested retained constructors do not recursively execute/certify under the exact nested object context; and derivation checks may accept discarded allocations by subtree membership. The current passing suite does not close those gaps.
- Retained Unit/intrinsic-initialized nominal observation issue #7 remains open. A nominal-only same-workspace runtime experiment fixed Unit reads but failed at the callable authority boundary; widening that boundary would violate existing closure-authority tests, so the experiment was reverted.

## Follow-up Items

- Resolve `.internal-dev/bugs/retained-nominal/phase3-constructor-proof-closure.md` before phase-3 completion or broader qualification.
- Design issue #7 as a route-scoped nominal-member/session-root authority capability rather than a broad `sourceLocal` or closure-authentication relaxation.
- Phase 4 remains responsible for the comprehensive independent retained-nominal compiler/session campaigns described by the active plan.

Validation evidence:

- Focused compiler, runtime, closure-authority, nominal-session, attachment, cancellation, lifetime, bytecode and failure suites: PASS.
- Fresh full-reactor `mvn -q test`: 1,656 tests, 0 failures, 0 errors, 2 editor UI skips.
- `tools/fuzz-language.sh -q`: PASS; four language fuzz tests at 1,800 cases each (7,200 cases total), plus the script's nominal compiler/runtime matrix.
- Source whitespace scan: no trailing whitespace in the changed source, documentation, or development records.
