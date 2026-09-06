# Callable Persistence Closeout Review

## Scope

Review the source-local persistent REPL callable path, compiler flow certificates, captured-cell linkage, remote cancellation admission, documentation synchronization, and remaining full-REPL blockers.

## Findings

- Source-local named callable persistence is implemented through compiler-issued `SessionFlowCertificate` records and callable summaries. The path preserves exact call targets, captures, shared mutable cells, writes, allocation provenance, operation-site spans and producer generation authority.
- Higher-order calls, returned closures, recursion, callable-bearing arrays/tuples, lexical replacement, failed-submission effects and cooperative cancellation are covered by compiler, runtime, REPL and forked Java-consumer tests.
- Remote cancellation had an admission race: a transport token could be admitted before `LyraSession` installed its active operation, allowing `session.cancel` to miss the request. Session admission now accepts a cancellation probe after installing the active operation, and the adapter uses it.
- Imported/pinned module linkage, configured roots, reload, application attachment, application safe points, coordinated program input and debug deployment remain intentionally blocked and are still represented as structured boundaries.

## Risk Assessment

The reviewed callable-flow path has no confirmed soundness defect. The remote cancellation race was a high-priority lifecycle correctness issue and is repaired with focused coverage. The full REPL specification remains incomplete outside the source-local session domain.

## Recommendations

- Keep callable and imported-module linkage separate in diagnostics and release evidence.
- Preserve the fail-closed `LYC-SESSION-001` boundary for uncertified or imported callable values.
- Add imported-module and attachment work only as separate phases with owner/lifecycle tests; do not weaken the source-local certificate checks.

## Follow-ups

- Implement pinned/imported module ownership and explicit reload.
- Implement configured/application roots, owner-thread safe points and embedding lifecycle.
- Re-run the Phase 24 audit after each completed full-REPL phase; it should remain BLOCKED until all declared requirements pass.
