# Initialized-generation REPL Callable Runtime Bridge

## Date

2026-09-06

## Git Commit

86696c7598b46185c56e00c421dca8ef2392eca1

Baseline HEAD only. No commit was made. Existing unrelated dirty/deleted/untracked state was preserved; initial hashes and copies are retained at `/tmp/lyra-callable-baseline-7ryhmork` for comparison.

## Change Summary

Implemented a separate authenticated runtime bridge for exact callable values between successfully initialized source-local generations in one session domain/epoch. Original closures, captures and mutable cells remain generation-owned; artifact keys remain distinct. Linkage now pins complete immutable artifact metadata and source-local classification, rejecting same-ID metadata substitution. Session epoch retirement invalidates closure creation/invocation/validity and module-owned I/O authority while preserving owner and terminal lifecycle precedence.

This is the strongest completed safe runtime slice from the xhigh senior pass, not named REPL callable persistence. No compiler flow/sealing guard, callable storage requirement, or failed-initializer lifecycle guard was removed. No interpreter, replay, copying, Object execution ABI, public callable-handle API, unsafe cancellation, imported authority or AOT closure relaxation was introduced.

## Files

Production runtime (`lyra-runtime/src/main/java/io/mindspice/lyra/runtime/`):
- `SessionStorageDomain.java`: immutable full artifact admission and source-local same-domain/epoch callable authority; callable storage remains rejected.
- `LyraOwnershipToken.java`: separate session authentication, OPEN producer requirement, epoch-aware validity/invocation/creation/I/O checks.
- `LyraClosureAuthority.java`: uses separate session authority without changing `sameArtifact`.
- `LyraClosureSupport.java`: existing exact-signature/SAM boundaries retained; diagnostics/docs distinguish explicit session authority.
- `LyraRuntime.java`: loading-key documentation only.

New tests:
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/SessionCallableRuntimeTest.java` (7 tests).
- `lyra-compiler/src/test/java/io/mindspice/lyra/runtime/SessionClosureAuthorityTest.java` (4 tests).

Records:
- `README.md`, `AGENTS.md`, living `repl.md`, `backend-runtime.md`, `decisions.md`.
- Phase-4 data progress cross-reference and new `phase-04-callable-runtime-progress.md`.
- `repl-aggregate-linkage.md` cross-reference and new `repl-callable-linkage.md` knowledge.
- `.internal-dev/reviews/2026-09-06-callable-runtime-bridge-review.md`.
- `.internal-dev/bugs/20260906-recursive-captured-cell-summary-limit/report.md`, mirrored as https://github.com/dhickel/lyra/issues/2 (OPEN).

## Behavioral Impact

Existing exact generated Java function signatures can interchange initialized same-session Lyra functions, including higher-order returned closures, function-bearing arrays/tuples and aggregate signatures. Tests prove both chronological directions, original capture/cell identity, ordinary later-source scalar assignment, lexical replacement, failed new initializer effects on old cells, ordinary call failure and exact producer spans, cooperative cancellation/recovery, owner checks, reset/close invalidation, and foreign SAM/artifact/session/imported-graph/signature rejection. Object adaptation in tests is confined to tooling; generated calls remain exact typed bytecode.

Compiler callable names and runtime callable storage remain unsupported. These tests deliberately supply values through existing generated Java export signatures; they are not a claim that later REPL source can reference prior functions. Cross-artifact INITIALIZING/FAILED producers are rejected, and failed new closures cannot yet escape safely.

Validation, after review repairs:

```text
mvn test -pl lyra-compiler -am -Dtest=SessionCallableRuntimeTest,SessionClosureAuthorityTest,SessionAggregateLinkTest,SessionStorageLinkTest,Phase16SmokeTest,Phase17SmokeTest -Dsurefire.failIfNoSpecifiedTests=false -Dlyra.preview.jvm.args=-Xverify:all
  PASS: 81 tests, zero failures/errors/skips (11 new tests).
mvn test
  PASS: 689 tests, zero failures/errors/skips.
mvn clean verify
  PASS: 689 unit tests + 3 distribution integration tests, zero failures/errors/skips.
git diff --check
  PASS.
```

Logs: `/tmp/lyra-callable-verify.log`, `/tmp/lyra-callable-mvn-test.log`, `/tmp/lyra-callable-clean-verify.log`. Includes existing normal AOT/runtime/authentication/structural bytecode regressions, forked public session consumer and Linux JLine PTY coverage. Standard deprecation/manifest-overlap warnings remain non-failing. Final full runs completed at 10:24 and 10:25 local time on 2026-09-06.

The initial recursive cancellation fixture exposed a pre-existing semantic solver defect before reaching this runtime boundary: recursive captured-cell writes grow to 257 entries against a 256-entry domain, escaping as LyraCompilerBugException. Issue #2 records the reproducer. A nonrecursive completed write followed by write-free recursion proves runtime cancellation without claiming that compiler defect fixed. An initial arithmetic fixture also incorrectly expected `/` to return I32; it was corrected to checked addition overflow.

## Specification Impact

Updated current implementation boundaries and recorded the distinct initialized-generation runtime authority in existing specifications/decisions. The intended full persistent REPL contract is unchanged. No phase/release BLOCKED gate was promoted to complete.

## Risks

Full callable persistence remains blocked on transported producer-certified summaries/identities/captures/flow sites, current mutable-cell flow, exact callable storage admission/setters, per-binding initialization, escaped failed/cancelled-generation retention, failed-generation identity allocation, and producer source-origin presentation through session results/protocol. Ordinary runtime producer frames are tested, but LyraSession still discards those frames from its RuntimeFailure presentation.

The recursive captured-cell summary defect is open. Imports/pinned reuse/reload, configured/application roots, owner executor/input coordination, real application attachment/safe points, debug packaging/launcher and remaining console parity are unfinished. No Phase 23 measurements, full Phase 24 audit or native macOS/Windows validation was run. No class-unloading or bounded total retention guarantee is claimed.

## Follow-up Items

The parent must run the next required configured senior escalation at max after this xhigh pass, with the precise flow/lifetime/solver blockers above. This session was already the first-generation `openai-codex/gpt-6-astra:xhigh` senior (confirmed through PI environment), so the harness rejected a further control-enabled senior launch. Read-only luna helpers completed; no substitute senior model was used. Complete the certified compiler flow and failed-generation lifetime path together before removing callable guards, then continue the remaining accepted REPL plan.
