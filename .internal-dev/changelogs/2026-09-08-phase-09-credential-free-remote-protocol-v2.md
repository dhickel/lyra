# Phase 09 Credential-Free Remote Protocol v2 and Execution-Host Operations

## Date
2026-09-08

## Git Commit
544f75cd44139c7b17dfe399a05306ebf02e2210

## Change Summary
Replaced the optional authenticated v1 REPL transport with the credential-free version-two protocol and added bounded execution-host LOAD/RELOAD/completion operations. `RemoteProtocol.VERSION` is 2; `TokenCredential` and every token/challenge/credential-file surface was deleted, including the server option, the endpoint field, the client handshake step and the CLI `--token-file` argument. The handshake is now a single `ClientHello`/`ServerHello` exchange whose ready message carries the session identity, revision, mutation sequence, request-sequence watermark and active request; version-one peers receive an explicit `UNSUPPORTED_VERSION` upgrade error. Freshness is revision plus mutation sequence: reset keeps the local revision contract and bumps the mutation sequence, stale evaluates/loads/reloads terminate `REVISION_CONFLICT`, stale resets get `REVISION_CONFLICT`, and stale queries/completions get `STALE` without owner work. Duplicate request identities replay retained outcomes without re-execution, mismatched payload reuse is rejected, and expired retained results are reported explicitly.

New wire operations: `LoadRequest`/`ReloadRequest` (sequence-bearing request identities answered by `Result` with real initializer progress) and `CompletionRequest`/`CompletionResult` (bounded metadata-only). `RemoteFileRead` captures one server UTF-8 file exactly once into a file-URI `EvaluationSource` and preflights source/label bounds before any Lyra effect; oversized LOAD payloads terminate `REJECTED` before submission. `RemoteFileCompletion` performs a bounded read-only listing under configured source roots; binding-member completion uses a fixed core-type metadata table. No compilation, pinning, expression execution or generic reflection/file RPC exists in completion.

Adapters route every live operation through real owners: `LyraSessionAdapter` gains load/reload/completion over `LyraSession` (with new `sourceRoots()` and identity/probe-carrying `reload` overloads), `ApplicationAttachmentAdapter` executes against a live registered root through the synchronous owner path (reload of application-owned modules is an explicit structured outcome), and `ManagedConsoleSessionAdapter` routes through the managed owner loop via new full-fidelity `submit`/`submitReloadResult` seams. Socket threads only parse, validate and enqueue; wire results bound dynamic snapshots/diagnostics/query lists with explicit truncation while preserving terminal status, and program I/O never enters wire payloads.

## Files
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteProtocol.java` — version 2 constants and bounds.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/ProtocolMessage.java`, `ProtocolCodec.java`, `ProtocolException.java` — v2 wire schema, strict codec, upgrade rejection.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteEndpoint.java` — address plus optional session identity only, with an explicit no-auth warning.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteServerOptions.java` — credential-free bounded configuration.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/TokenCredential.java` — deleted.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteServer.java` — v2 hello, mutation-sequence freshness, load/reload/completion dispatch, bounded result fitting.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteClient.java`, `RemoteRequest.java` — v2 handshake, mutation tracking, load/reload/completion client operations, sequence reconciliation.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteConsoleSession.java` — client-side load/reload/completion surfaces.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteSessionAdapter.java`, `RemoteCompletion.java`, `RemoteFileRead.java`, `RemoteFileCompletion.java`, `RemoteLoadException.java` — owner seams and execution-host file/completion helpers.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/LyraSessionAdapter.java`, `ApplicationAttachmentAdapter.java`, `ManagedConsoleSessionAdapter.java` — real session/root/managed owner routing.
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/LyraSession.java`, `ApplicationAttachment.java`, `ManagedConsoleSession.java` — narrow additive seams (source roots accessors, identity/probe-carrying reload/submit).
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraCli.java`, `lyra-cli/src/test/java/io/mindspice/lyra/cli/AttachCliTest.java` — token-free attach command and tests.
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/remote/RemoteProtocolV2Test.java`, `RemoteWireRobustnessTest.java`, `NoAuthAttachmentTest.java`, `RemoteFileModuleTest.java` — new assertion-grade suites; `RemoteServerTest.java`, `RemoteConsoleSessionTest.java`, `RemoteSessionExecutionTest.java` updated; `RemoteProtocolTest.java`, `RemoteWireSecurityTest.java` deleted.
- `tools/phase24-release-audit.sh`, `tools/phase24-repl-coverage.tsv` — remote suite names and R08/R15 evidence updated.

## Behavioral Impact
No credential path, flag or file operation exists in attach/start/close. The listener binds loopback only and validates ports (0 for bound endpoints, invalid ports, non-loopback IPv4/IPv6). One controlling connection, finite frames/queues/outbound/retention, explicit reconnect without replay, reset-safe freshness and identity-specific cancellation are exercised by tests including real attachable-root execution, Unicode/escaped JSON boundary bytes, cancel races and exactly-once LOAD/RELOAD.

## Specification Impact
`repl.md` remote-protocol and console sections now describe implemented behavior (it already specified the v2 credential-free target); the removed authentication surface matches the previously recorded supersession decisions. No normative contract changed.

## Risks
The v2 wire is schema-versioned strictly; future changes need a new version or additive optional fields with coordinated codec updates. Conservative result fitting keeps terminal status at the cost of dropping large dynamic payload data. Attachment adapter evaluations use the synchronous owner path; the safe-point-dispatched composition belongs to the later launcher/bootstrap phase.

## Follow-up Items
Phase 10 console-command wiring (client-side `:load`/`:reload`/completion routing to the server), Phase 12 run/compile activation and bootstrap, Phase 14 full audit and review.
