# Phase 10 Remote Protocol and Security

## Date

2026-09-05

## Git Commit

a16a46b71780206c6cec27ee87adef29e5333069

## Change Summary

Added the independent `lyra-repl` remote protocol/security domain. The transport is a loopback-only, version-one, length-prefixed UTF-8 JSON protocol with explicit handshake challenge/authentication, bounded schema/framing/work/result retention, request sequencing and session revisions, terminal evaluation statuses, cancellation, reset, and bounded internal query operations. Socket threads publish work only through an owner dispatcher.

Credential files are exclusively created with owner-only permissions, reject existing and symlink targets, use 256-bit random tokens, and compare fixed-size decoded credentials with constant-time byte comparison. The server enforces one authenticated controller, bounded connections and outbound queues, duplicate/expired request protection, disconnect cancellation, and retained status for explicit reconnect without resubmission.

## Files

- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/remote/`

## Behavioral Impact

Remote evaluation results and diagnostics are structured data only. The server never forwards process stdin/stdout/stderr, exposes Java serialization/reflection RPC, binds LAN addresses, or executes live session work on socket threads. `LyraSessionAdapter` uses the existing public synchronous session API; live binding/type queries and full persistent attached-session linkage remain explicitly unavailable until compiler/session linkage exists.

## Specification Impact

None. This implementation follows `.internal-dev/specifications/repl.md` and the phase-10 plan without changing compiler/runtime/CLI/POM/specification files.

## Risks

The current compiler-backed `LyraSession` is still a one-shot session boundary: remote submissions through `LyraSessionAdapter` do not provide persistent live values or attached application-root linkage. Hosts requiring those capabilities must provide a real `RemoteSessionAdapter` and owner polling seam; the protocol returns structured unavailable statuses rather than using an alternate evaluator.

## Follow-up Items

Wire a compiler/session-linker-backed adapter and application lifecycle integration in the later planned domains. Do not treat the current adapter as proof of persistent multi-submission evaluation or application attachment.
