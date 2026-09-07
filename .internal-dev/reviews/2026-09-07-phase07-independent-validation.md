# Phase 07 Independent Validation

## Scope

Independent review and repair of generated attachable safe-point polling, admission, cancellation, shared-controller lease reuse, and owner dispatch in the dirty Lyra worktree. Unrelated worktree changes were preserved and no commit was created by the validator.

## Findings

- Generated attachable boundaries activate polling for callable entry, direct and callable calls, executable forms, sequence/block boundaries, and direct self-tail backedges while normal/session/dependency emission remains unchanged and initialization remains gated.
- Contained polling preserves the ordinary rethrowing controller API while allowing expected cancellation/failure at application boundaries to complete the dispatched handle and resume the application.
- Attachment storage and generated root lifecycle share one controller and one admitted lease. Owner-dispatched work reuses the poll admission, preventing nested evaluation and double lease acquisition.
- Admission, cancellation, terminal publication, controller close, and request-id reuse are bound to exact dispatch handles and controller generations. Cleanup is performed on terminal paths.
- Java 25 clean verification passed with 848 tests and zero failures, errors, or skips; `git diff --check` passed.

## Repairs

The validator repaired terminal cancellation/close cleanup, exact handle binding for stale cancellation after request identity reuse, and added regression coverage for controller-close and cancellation races.

## Risk Assessment

Cooperative cancellation can be delayed by blocking host I/O or root code that does not reach a generated boundary. No forced interruption or cancellation of main is used. The existing postfix `::direct[...]` grammar ambiguity remains documented and out of scope.

## Recommendations

Continue with Phase 08 managed local owner/API and console work using the dispatched evaluation handle and contained polling contract. Preserve exact controller-generation correlation for later protocol disconnect cancellation.

## Follow-ups

- Phase 08 managed local owner adapter and console presentation.
- Phase 09 protocol correlation and disconnect cancellation.

Validation evidence: focused Phase 07, Phase 05/06 regression suites passed; `mvn -q test` passed; `mvn -q clean verify` passed with 848 tests, zero failures/errors/skips; `git diff --check` passed. Git commit baseline: `419aa29`.
