# REPL Phase 00 scope and baseline

## Date

2026-09-06

## Git Commit

ae793e7d6776f6611fa78ad80ea357ac827ed131

## Change Summary

Reconciled the living REPL and backend specifications with the approved trusted-development scope. Recorded the explicit removal of authentication/security-hardening and automatic local-root initialization from the current completion contract, while retaining loopback-only activation, exact typed correctness checks, owner/lifetime rules, empty local startup, imported-module persistence, reload, application attachment, and debug deployment as implementation requirements. Added the initial method-level REPL coverage inventory.

## Files

- `.internal-dev/specifications/repl.md`
- `.internal-dev/specifications/backend-runtime.md`
- `.internal-dev/specifications/deferred-features.md`
- `.internal-dev/specifications/decisions.md`
- `AGENTS.md`
- `tools/phase24-repl-coverage.tsv`

## Behavioral Impact

No runtime behavior changed. The baseline inventory records existing source-local evidence and current blockers without promoting them to completion.

## Specification Impact

The REPL contract now describes trusted credential-free localhost attachment, explicit source-root-only local startup, v2 migration direction, root-lifetime retention, debug source context and graph-wide capacity obligations. Deferred-features and decisions record the superseded security/local-root requirements.

## Risks

The implementation remains blocked on imported-module linking, real session `std->io`, reload, application-root attachment/safe points, remote v2, and debug packaging. Existing historical authentication documentation may remain until later implementation/documentation phases update all user-facing surfaces.

## Follow-up Items

Execute phases 01 onward from the published plan. Keep this coverage inventory method-specific and mark retained requirements only after fresh executed evidence.
