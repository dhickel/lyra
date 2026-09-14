# Remote Protocol v2 Freshness and Owner Routing

## Topic
Credential-free REPL remote protocol v2: revision plus mutation-sequence freshness, duplicate/reconnect semantics, execution-host LOAD/RELOAD/completion, and owner-routed adapters.

## Source References
- `.internal-dev/specifications/repl.md` (Remote protocol section)
- `.internal-dev/plans/20260906-221525-complete-lyra-repl-with-trusted-module-linking-and-local/plan.md` (Phase 09)
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteServer.java`, `RemoteClient.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/remote/RemoteServerTest.java` (stale/reset coordination)

## Key Takeaways
- Wire freshness is revision **plus** a mutation sequence: successful evaluations advance the revision; reset keeps the local revision contract and bumps the mutation sequence. Any request whose (revision, mutationSequence) pair does not match the server is rejected before owner work. This is the only way an unchanged revision after reset remains detectable.
- The v2 `ServerHello` also carries `lastSequence` even though the phase text lists only session/revision/mutation/active request: client-side reconnect reconciliation (fail requests the server never accepted, resume the sequence counter) depends on that watermark. Dropping it silently broke the no-replay reconnect contract.
- Owner-dispatched operations (reset, BINDINGS/TYPE queries, completions) do **not** answer until the owner pump runs. Raw-socket tests that send such an operation and block on the response must pump the server between send and read, or they deadlock. Immediate answers (BUSY/STALE/REVISION_CONFLICT/REQUEST_EXPIRED) never reach the owner queue; asserting `owner.hasPending()` for those paths is wrong.
- The attachment adapter must use the synchronous `ApplicationAttachment.submit` owner path when the remote server's dispatcher polls on the attachment owner. The `submitDispatch` handle path deadlocks (the waiting runnable occupies the pump while the attachment's controller needs the same owner to poll), and reset/query on the attachment check the attachment controller's `hasLiveWork()`. A separate `LyraOwnerController` for the remote server keeps the two lease domains independent.
- Load capture happens exactly once: `RemoteFileRead` reads a regular non-symlink file, strictly decodes UTF-8, bounds the source/label, and attaches a file URI. Bounds violations are `RemoteLoadException` -> wire `REJECTED` **before** any session submission; graph-wide source capacity is rejected inside the session before initializer effects.
- Reload-qualified producer identities are session URIs (`lyra-session:module/<uuid>`), so wire initializer-progress module ids must be asserted structurally (states present) rather than by file-path spelling.
- Strict schema records cannot construct v1 messages; upgrade-diagnostic tests must send raw JSON bytes with `version:1`.

## Project Relevance
Applies to any future transport work (Phase 12 launcher/bootstrap), the Phase 10 console wiring that routes `\load`/`\reload`/completion to the execution host, and raw-wire tests in Phase 13/14.

## Open Questions
Phase 12 must decide how the application's own safe points pump the remote server in a live `run --repl` host (bootstrap polling versus the separate-controller composition used by the Phase 09 attachment adapter).
