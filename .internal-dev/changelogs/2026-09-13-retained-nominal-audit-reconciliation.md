# Retained nominal audit, contract reconciliation and release-gate inventory

## Date

2026-09-13

## Git Commit

b6e6ef19f28887a03414db429d92993bad5c014d

## Change Summary

Phase 5 of the retained nominal struct/class factory completion: the living specifications, durable decisions, documentation, knowledge, handoff status and the Phase 24 release audit are reconciled with the behavior delivered in phases 1-4, and an independent completion review is recorded.

- `language-core.md` now documents the retained construction contracts: the closed producer-certified initializer-transfer algebra and its inventory, ordinary-summary consumption with unpublished synthetic events, the structured `LYC-SESSION-001` safety net at the member span, Unit-returning retained constructors, exact argument/default/constructor ordering, nested construction contexts, incomplete-self restrictions, and the immutable-`self` mutation-authority rule with its bounded provenance lattice, structured `LYC-RESOLVE-021` rejection and its accepted precision cost.
- `backend-runtime.md` documents the runtime/proof boundary: producer-bound factory authority with no source or IR replay, `SESSION_EXECUTE`-only root binding initialization with exactly-once semantics, consumer-scoped fresh-allocation derivation with tagged disjoint identity domains and the finite-site property, route-exact and destination-sensitive certification, exact nil provenance, predecessor continuity, the route-blind `certifiesLinkedCallable` IR-link boundary, and the structured diagnostics involved.
- `repl.md` documents cross-generation session behavior for retained nominals: distinct instances per construction, fresh versus shared identity, producer-order effects, nontransactional failure and cancellation with no staged publication and a usable session, attachment behavior, and both open limitations with the corrected statement that issue #7 affects bare and member-qualified reads while issue #8 leaves the bare read working.
- `decisions.md` gains six durable entries with justification, rejected alternatives, caveats, affected specifications and review timing: immediate closed-algebra completion; exact summary-derived fresh provenance with the finite-site caveat; runtime keeping the authenticated producer factory; proof/runtime separation with `SESSION_EXECUTE`-only root initialization; the conservative flow-insensitive immutable-`self` provenance tradeoff; and the rejected `sourceLocal` relaxation with the session-root ownership direction for issue #7.
- `tools/phase24-release-audit.sh` adds the nominal syntax, type, semantics, bytecode, certificate, artifact-metadata and schema-contract suites plus the session, session-fuzz and language-fuzz suites to the mandatory expected-report inventory, so a missing or empty suite fails the report check, and adds them to the frontend, semantic, types, jvm, abi, runtime and repl-session evidence groups plus a new `nominal-campaigns` group. No existing check, inventory or group was removed or weakened, and active nominal forms remain classified as implemented rather than deferred.
- A completion review is recorded at `.internal-dev/reviews/2026-09-13-retained-nominal-finalization-review.md` with the required headings, claiming only what the recorded evidence supports and stating that final qualification is still pending.
- The handoff `.internal-dev/handoffs/nominal-struct-class-finalization.md` gains a reconciled status section citing the phase commits and the two open issues while preserving its historical "Work still required" list with per-item reconciliation notes.
- Documentation corrections from independent validation: the bare-read statement for the two open limitations is corrected in `repl.md`, `docs/repl.md` and the linkage knowledge file; the fuzz knowledge file no longer claims the nilable-index and direct-rebind crashes are open (the nilable-index crash was fixed in phase 4 and the direct-rebind crash never reproduced); and `nominal-types.md` no longer references a removed planning path.
- New open limitation logged and mirrored: `.internal-dev/bugs/compiler/lyratype-primitive-init-cycle.md` (GitHub issue #13) records the latent `LyraType`/`PrimitiveType` static-initialization cycle found during this phase's validation. It did not reproduce in a clean reactor run on a quiescent tree, so it is recorded as latent rather than active.

## Files

- `.internal-dev/specifications/language-core.md`, `backend-runtime.md`, `repl.md`, `decisions.md` (updated)
- `.internal-dev/reviews/2026-09-13-retained-nominal-finalization-review.md` (new)
- `.internal-dev/handoffs/nominal-struct-class-finalization.md` (reconciled)
- `.internal-dev/knowledge/nominal-session-linkage.md`, `nominal-types.md`, `language-conformance-and-fuzzing.md` (corrected)
- `.internal-dev/bugs/compiler/lyratype-primitive-init-cycle.md` (new)
- `tools/phase24-release-audit.sh` (inventories and evidence groups added)
- `docs/repl.md`, `docs/language-testing.md` (claims corrected)
- `.internal-dev/changelogs/2026-09-12-*.md`, `2026-09-13-*.md` (commit metadata aligned with the actual phase commits)

## Behavioral Impact

None: this phase changes specifications, records, documentation and the release-audit inventory only. The audit now fails when a nominal, session, session-fuzz or language-fuzz suite is missing or empty, which tightens the release gate rather than relaxing it.

## Specification Impact

This phase is the specification reconciliation for phases 1-4. It records the retained construction, certification, runtime-authority, failure, ordering and mutation-authority contracts as implemented, and records the two open behavioral limitations (#7 and #8) plus the latent initialization-cycle hazard (#13) instead of presenting them as closed.

## Risks

- The audit's workspace check compares porcelain status, so a content change to an already-modified tracked file during a run is not detected; qualification therefore requires a quiescent worktree, and two writers editing the same tree concurrently can invalidate an audit run.
- The specifications now describe behavior that depends on the compiler proof algebra; a future syntax extension must update the algebra, the inventory test and these contracts together.
- Issues #7, #8 and #13 remain open and are documented, not worked around.

## Follow-up Items

- Run the plan's final qualification command sequence on the final commit (install, nominal session suite, full reactor, extended campaign, Phase 24 audit) and re-run the audit on that commit.
- Fix issues #7, #8 and #13, then flip their pinned or documented cases to positive assertions.
