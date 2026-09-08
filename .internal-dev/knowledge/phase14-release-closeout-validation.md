# Phase 14 Release Closeout Validation

## Topic

Final release audit, documentation and independent-review closeout for the complete Lyra REPL/attachment scope (plan `20260906-221525` phases 00-14).

## Source References

- `tools/phase24-repl-coverage.tsv`
- `tools/phase24-requirement-matrix.tsv`
- `tools/phase24-release-audit.sh`
- `target/phase24-audit/audit-summary.json` (audit output)
- `.internal-dev/reviews/2026-09-08-phase14-independent-validation.md`
- `.internal-dev/specifications/repl.md`, `.internal-dev/specifications/deferred-features.md`, `.internal-dev/specifications/decisions.md`

## Key Takeaways

- Status declarations alone cannot close a release. The repl-coverage inventory splits every mixed requirement into retained PASS rows (exact annotated test methods re-verified against clean-reactor Surefire/Failsafe reports) versus explicitly SUPERSEDED/DEFERRED rows (verified against living-specification headings). Blanket deletion of superseded checks is replaced by classification.
- The requirement matrix must not keep BLOCKED rows for delivered behavior. Mixed rows were split into per-surface PASS rows with exact evidence plus `P24-DEF-009`/`P24-DEF-010` deferred rows; the audit's expected ID set was updated to match instead of erasing checks.
- The audit runs in one long-lived shell with a start-of-run workspace snapshot. Any file created mid-run (even an intended internal-dev artifact) fails the workspace preservation check. Finalize every file before launching the audit, then make no edits until it finishes.
- Failsafe integration suites (`ReplArtifactIT`, `ReplLauncherIT`, `JLineDistributionIT`) only exist under `verify`; method-level evidence checks must read both surefire and failsafe report directories.
- Focused reactor validation through the compiler requires a real compiler test in the `-Dtest` list (compiler POM has `failIfNoTests=true`); stale XML reports are not removed by filtered Surefire runs, so fresh timestamps/clean-verify reports are the only reliable executed-method evidence.
- The `-pl <module> -am` reactor pattern is mandatory for focused REPL/CLI runs; sibling snapshots installed elsewhere are stale.
- Packaged attachable examples must use a direct `::spin[]` call for the main loop's generated safe-point boundary. A parenthesized `(spin)` form can compile and may appear to work when `run --repl` wins a startup race, but packaged activation can remain BUSY; verify both `run --repl` and `-Dlyra.repl.enabled=true` by attaching and observing the expected main exit.

## Project Relevance

Phase 14 is the final gate after the 24-phase backend sequence and the 15-phase REPL completion. The audit gates on the matrix, conformance coverage, the exact-method REPL coverage inventory, a fresh unchanged Phase 23 gate, deterministic packaging smokes, dependency/jdeps closure, and workspace/target preservation. It does not authorize deferred features, alternate execution products, or future optimization work.

## Open Questions

- Re-run the audit after any material production change; it is the mandatory release gate.
- Native Windows launcher validation remains N/A until a Windows environment exists.
- Future hostile-code isolation or editor/LSP work needs separate accepted specifications.
