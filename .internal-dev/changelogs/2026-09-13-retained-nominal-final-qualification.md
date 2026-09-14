# Retained nominal final qualification evidence

## Date

2026-09-13

## Git Commit

051a5494af369f4b4be1dc380df3645e0bec695c

## Change Summary

Final qualification evidence for the retained nominal struct/class factory job (plan `.internal-dev/plans/20260912-180414-complete-retained-nominal-struct-class-factory-semantics/plan.md`). This record captures the mandated command sequence, the inspected evidence and the exact state that was qualified. It adds no behavior.

Phases delivered: phase 1 closed initializer transfer and direct calls (`432121b`), phase 2 complete expression algebra and consumer-scoped fresh provenance (`c6f4274`), phase 3 constructor/runtime integration with its proof-closure completion (`df6e1d5`, `2c3602a`), phase 4 independent compiler and session campaigns with the combined extended run (`2b49f7c`), phase 5 audit and contract reconciliation (`051a549`). One unrelated documentation commit by another writer (`b6e6ef1`, adjacent direct-call commas) landed during phase 5 and is preserved.

## Files

Record-only change: this changelog is the only file added. No source, test, tool, specification or documentation file changed in this qualification step.

## Behavioral Impact

None. This record captures evidence for an already committed state; it changes no runtime, compiler, tooling or specification behavior.

## Qualification Evidence

Command sequence run on the frozen final tree, in order:

1. `mvn -q -DskipTests install` — exit 0.
2. `mvn -q -pl lyra-repl -Dtest=NominalSessionTest test` — 67 tests, 0 failures, 0 errors, 0 skips.
3. `mvn test` — BUILD SUCCESS: runtime 42, compiler 1223, repl 334, cli 66, editor 24 with 2 expected headless skips; 0 failures, 0 errors (1,685 tests). A separate `mvn clean test` on the same tree also passed, so the reactor is not order-sensitive in this state.
4. `tools/fuzz-language.sh -q` — exit 0: compiler campaign 4 seeds x 1,800 cases with `retained=150` per seed and all 47 retained operation lines nonzero; session model 3 seeds x 240 steps with `SESSION FUZZ PASS` for seeds 7, 83 and 137.
5. `tools/phase24-release-audit.sh` — **PASS**: 167 matrix rows, 157 non-deferred, PASS=156, BLOCKED=0, DEFERRED=10, N/A=1; fresh Phase 23 gate PASS; exact REPL coverage gate PASS; the strengthened report inventory passed with 116 suites and no skips; the `nominal-campaigns` evidence group passed. No blocked requirements; the deferrals are the documented out-of-scope language and platform features.

Inspected evidence rather than exit codes: per-seed fuzz `summary.txt` files (category balance, numeric distribution, `retained.ops`, per-operation and per-profile lines), session-fuzz transcripts under `lyra-repl/target/session-fuzz/seed-*/worker.log`, the audit matrix `target/phase24-audit/requirement-matrix.tsv` with `target/phase24-audit/audit-summary.txt`, and fresh Surefire report totals under each module's `target/surefire-reports`.

Diff and history review: `git diff --check` clean; `git status --short` empty; history linear from the job baseline `c20984f` through `432121b`, `c6f4274`, `df6e1d5`, `2c3602a`, `2b49f7c`, `b6e6ef1`, `051a549`; 54 files changed, 13,946 insertions and 869 deletions, all owned by this job or the one preserved concurrent documentation commit. No placeholders, stubs or deferred TODOs were introduced; the two intentional WIP checkpoints (`432121b`, `df6e1d5`) were each completed by a later commit that closes their recorded gaps.

## Open Limitations

- Issue #7 (`.internal-dev/bugs/retained-nominal/unit-initializer-later-observation-linkage.md`): observing a retained nominal whose member initializer is Unit-typed or imports the intrinsic module one generation after construction fails with `LYR-LINK`. Four inventory cases pin the structured failure. The rejected fix direction is documented: do not relax `SessionStorageDomain.Linkage.sourceLocal`; anchor generated nominal-instance ownership to the session root instead.
- Issue #8 (`.internal-dev/bugs/retained-nominal/nilable-member-read-contract.md`): annotated retained nilable-member reads and nil-contract-consuming forms over member reads are rejected at the IR boundary with `LYC-IR-003`; the bare read works.
- Issue #13 (`.internal-dev/bugs/compiler/lyratype-primitive-init-cycle.md`): latent `LyraType`/`PrimitiveType` static-initialization cycle, not reproducible in the qualified state.

## Specification Impact

Recorded in `2026-09-13-retained-nominal-audit-reconciliation.md`: the retained construction, certification, runtime-authority, ordering, failure and mutation-authority contracts are now stated in `language-core.md`, `backend-runtime.md`, `repl.md` and `decisions.md`, and the audit gate now requires the nominal, session and campaign suites.

## Risks

- Qualification applies to the exact commit recorded above; any later change invalidates it and requires re-running the sequence.
- The audit's workspace check compares tracked status rather than file content, so qualification assumes a quiescent worktree (a second writer committed during phase 5 and was preserved rather than merged).

## Follow-up Items

- Fix issues #7, #8 and #13, then flip their pinned or documented cases to positive assertions.
- Keep the closed transfer algebra, its inventory test and the specification contracts in step when syntax is extended.
