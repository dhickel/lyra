# Remote Protocol Validation (Historical Phase 08 Record)

## Topic

Loopback REPL protocol framing, the superseded authentication design, owner dispatch, and reconnect retention. Phase 09 replaces the authentication design with credential-free protocol v2; current invariants are recorded in `remote-protocol-v2.md`.

## Source References

- `.internal-dev/specifications/repl.md`
- `.internal-dev/plans/20260905-134255-persistent-lyra-repl-with-jline-console-evaluation-api-a/plan.md`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/FrameCodec.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteServer.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteProtocol.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/remote/RemoteProtocolV2Test.java`

## Key Takeaways

- Frame length must be checked before payload allocation; JSON decoding must use a strict UTF-8 decoder and reject duplicate/unknown schema fields.
- Historical Phase 08 behavior used two bounded authenticated handshake frames. This is superseded; protocol v2 performs one credential-free hello exchange and rejects legacy v1 peers explicitly.
- A remote server should retain request identity, sequence, revision, and terminal result separately from a connection. Disconnect releases only the controller lease, requests cooperative cancellation, and leaves status queryable for an explicit reconnect.
- Owner dispatch must have one pending work item and a cancellation path that distinguishes pending cancellation from a running evaluation. Socket threads may request cancellation but never call evaluation/reset/query adapter methods.
- `LyraSessionAdapter` is intentionally limited by the current synchronous one-shot session API. Binding/type queries use structured unavailable results, not an interpreter or fake live-value store.
- Handshake deadlines must be applied to every low-level read, not only each frame call, so byte-drip peers cannot renew a per-read socket timeout. The client uses one monotonic deadline for both handshake frames through a socket-aware frame read.
- Terminal evaluation results and cancellation status publication share the server state lock; cancellation re-checks the request after its adapter callback before enqueueing a nonterminal status.
- A query can report an expired request without a request snapshot. The client waiter must retain the queried request identity so the retained `RemoteRequest` can complete with a terminal data-free `EXPIRED` result rather than remaining pending.
- The client treats the server sequence as authoritative after reconnect. Locally outstanding records beyond that prefix are failed and removed without source replay.
- Historical credential-path security used `SecureDirectoryStream` directory-relative no-follow traversal and exclusive file opens. That code and obligation are no longer part of protocol v2.

## Project Relevance

These invariants are reusable when the future compiler/session linker and application attachment lifecycle are wired into the remote seam. Protocol tests can remain transport-focused with fake owner/session adapters while integration tests prove owner-thread execution separately.

## Open Questions

Persistent live binding linkage, application safe-point servicing, and attached-root lifecycle remain later compiler/runtime/CLI integration work; this protocol layer must not silently emulate them.
