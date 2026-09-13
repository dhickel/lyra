# Retained nominal phase 3 constructor/runtime integration

## Date

2026-09-13

## Git Commit

`df6e1d5` is the phase-3 WIP checkpoint and `2c3602afa8299e3d2a89ce03cf6c4f0ff458ce37` completes it; this changelog describes the completed phase.

## Change Summary

Completed the phase-3 constructor/runtime integration slice. Added focused coverage for constructor compositions and ordering, exactly-once factory emission, failure and cancellation cleanup, source mapping, attachment, recovery, owner lifetime, nested retained construction contexts, destination-sensitive aggregate and object certification, explicit nominal function-array literals, and immutable-`self` alias provenance. No runtime production change was retained.

Four independent review rounds drove the proof closure. The initial round repaired constructor scope, nested construction contexts, destination reachability and exact assertions; two follow-up rounds closed the immutable-`self` provenance family (branch joins, multi-level forwarding, identity-returning calls, closures returning captured `self` including tuple/alias/higher-order variants, fail-closed bound exhaustion, and member-slot taint); and one escalated senior round replaced the route-by-route engine with a declaration-keyed value-position provenance lattice that preserves member identity through aggregates, closes the remaining escape routes, and fixes `TypedSemanticProvenance.syntaxType` for explicit `Array<Fn<;Box>>[...]` literals. Final independent validation passed and confirmed no tested escape remains. The full history is recorded in `.internal-dev/bugs/retained-nominal/phase3-constructor-proof-closure.md`, now closed.

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

- Immutable-`self` provenance is a deliberately conservative, flow-insensitive over-approximation: aggregate sibling positions collapse, member taint is declaration-wide and instance-insensitive, rebinds never clear prior may-alias facts, opaque calls may keep self-derived taint, and alias chains deeper than the eight-pass bound fail closed rather than resolving. Legal code in those shapes can therefore be rejected; no soundness escape was demonstrated after the lattice rewrite.
- Retained opaque callable bodies are not walked for body-result provenance; no executing escape was found, but the analysis does not cover them.
- Retained Unit/intrinsic-initialized nominal observation issue #7 remains open. A nominal-only same-workspace runtime experiment fixed Unit reads but failed at the callable authority boundary; widening that boundary would violate existing closure-authority tests, so the experiment was reverted.

## Follow-up Items

- Design issue #7 as a route-scoped nominal-member/session-root authority capability rather than a broad `sourceLocal` or closure-authentication relaxation; the four pinned `LYR-LINK` inventory cases stay as the acceptance signal.
- Phase 4 remains responsible for the comprehensive independent retained-nominal compiler/session campaigns described by the active plan.

Validation evidence:

- Focused compiler, runtime, closure-authority, nominal-session, attachment, cancellation, lifetime, bytecode and failure suites: PASS.
- Fresh full-reactor `mvn -q test` after the final repair round: runtime 42, compiler 1,217, repl 333, cli 66, editor 24 with 2 expected graphical skips; 0 failures and 0 errors (1,682 tests).
- `tools/fuzz-language.sh -q`: PASS; four language fuzz tests at 1,800 cases each (7,200 cases total), plus the script's nominal compiler/runtime matrix.
- Source whitespace scan: no trailing whitespace in the changed source, documentation, or development records.
