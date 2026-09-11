# Authenticated CLI Attachment Console

## Date

2026-09-05

## Git Commit

86696c7598b46185c56e00c421dca8ef2392eca1

## Change Summary

Added `lyra attach ENDPOINT --token-file PATH` over the existing bounded authenticated loopback protocol. Local and remote console sessions now share essential command/evaluation handling while preserving server-owned application standard I/O. Attachment validates loopback endpoints and restrictive token files, supports controller exclusivity, reconnect/status tracking, cancellation, reset, bindings queries, and orderly disconnect without resetting or closing the server session.

## Files

- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraCli.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/PlainConsole.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ConsoleSession.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/LocalConsoleSession.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/RemoteConsoleSession.java`
- related CLI/REPL/remote tests

## Behavioral Impact

The CLI can now explicitly attach to an authenticated loopback REPL server. Remote failures distinguish unavailable operations from disconnected transport. Disconnect requests cancellation and preserves remote workspace state. No remote stdout/stderr capture, stdin forwarding, PID injection, LAN listener, TLS server, serialization, or generic RPC was added.

## Specification Impact

Implements the client-side attachment and shared console-session portion of the REPL contract. Live compiler linkage remains unavailable and is reported structurally.

## Validation

- Attachment validator: PASS.
- `mvn -pl lyra-repl,lyra-cli -am test`: PASS, 628 tests.
- `mvn test`: PASS, 628 tests.
- `git diff --check`: PASS.

## Risks

Persistent direct-bytecode live values, functional `:type`/`:reload`, application-root registration, and launcher activation remain unfinished. Native macOS/Windows behavior was not executed.

## Follow-up Items

Implement authenticated session-to-artifact linkage before enabling attached live evaluation. Then add debug-capable artifact packaging and launcher activation.
