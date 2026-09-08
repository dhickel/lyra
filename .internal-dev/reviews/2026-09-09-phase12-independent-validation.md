# Phase 12 Independent Validation

## Scope

Independent review and repair of run/compile REPL activation, listener bootstrap/readiness, launcher reconstruction, remote shutdown, and owned/external root lifecycle. Unrelated worktree changes were preserved and no commit was created by the validator.

## Findings

- Activation is disabled by default; `run --repl` owns the credential-free listener and `compile --repl` records capability without listening. Port and argument constraints are enforced.
- Bootstrap gates live work until initialization and actual root registration. Wait readiness is handshake-complete controller readiness, not an accepted socket.
- Launcher reconstruction validates embedded attachable context before root instantiation, supports bundled and explicit layouts, and preserves main arguments.
- Shutdown repairs cover registration races, closed-state admission, bounded terminal draining, queued cancellation, active completion, disconnect during wait, and primary failure preservation. Externally owned roots survive service close and reopen.
- Normal artifacts and the four-module dependency graph remain unchanged by activation.

## Validation Evidence

Focused REPL/CLI/remote tests passed. `mvn -q clean verify` passed on Java 25.4 and `git diff --check` passed. No commit was created by the validator.

## Risk Assessment

A client submitting during initialization receives truthful BUSY and must retry. Terminal delivery during close is bounded. Platform-native terminal behavior remains platform-specific and is covered primarily on Linux. Later conformance and release audit phases remain outstanding.

## Recommendations

Preserve bootstrap gating and shared-controller lease reuse through cross-surface conformance. Treat the full release audit as incomplete until Phase 14 executes its complete JMH and documentation gates.

## Follow-ups

- Phase 13 cross-surface conformance and repeated-lifetime evidence.
- Phase 14 release audit, documentation, and final independent review.
