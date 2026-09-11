# Remote Protocol Validator Repairs

## Date

2026-09-05

## Git Commit

a16a46b71780206c6cec27ee87adef29e5333069

## Change Summary

Repaired the bounded remote REPL protocol after validator review. Handshake reads now use an absolute byte-level deadline on both server and client, accepted responses are queued before owner statuses, client sequence state reconciles to the server without source replay, cancellation admission and terminal publication are atomic, disconnect cleanup precedes controller release, expired query results settle retained client requests, oversized query results drop nested terminal payloads, authenticated session identities are checked, terminal status messages are rejected, and sub-millisecond client timeouts are rounded to finite socket timeouts. Credential creation and reads now use secure directory-relative no-follow operations with exclusive creation and ownership/permission verification.

## Files

- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/FrameCodec.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteClient.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteRequest.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteServer.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/remote/RemoteServerTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/remote/RemoteWireSecurityTest.java`

## Behavioral Impact

Remote transport remains loopback-only, authenticated, bounded, owner-dispatched, and non-interpreter based. Reconnect never resubmits source. Client handshake framing now applies one monotonic deadline across every header and payload read. Terminal evaluation publication is serialized with cancellation-status publication, and an expired query completes the retained request future with a data-free terminal `EXPIRED` result. Providers without secure directory, POSIX ownership, or permission operations fail closed for credential access.

## Specification Impact

None. This repair follows `.internal-dev/specifications/repl.md` and changes no compiler, runtime, CLI, POM, or specification files.

## Risks

Secure credential access intentionally requires provider capabilities that expose directory-relative no-follow operations and verifiable POSIX ownership/permissions. Such providers are rejected rather than reopening the portable path-based TOCTOU gap.

## Follow-up Items

None for this focused validator repair. Persistent live session linkage and interpreter-like behavior remain out of scope.
