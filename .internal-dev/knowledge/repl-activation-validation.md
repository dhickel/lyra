# Topic: REPL run/host activation validation

## Source References

- `.internal-dev/specifications/repl.md`, `.internal-dev/specifications/decisions.md` (2026-09-09 section)
- `.internal-dev/plans/20260906-221525-complete-lyra-repl-with-trusted-module-linking-and-local/plan.md` (Phase 12, D17, V19)
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ReplActivation.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ReplLauncher.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteServer.java`
- `lyra-cli/src/test/java/io/mindspice/lyra/cli/ReplRunCliTest.java`, `ReplLauncherIT.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ReplActivationJavaHostTest.java`

## Key Takeaways

- A submit that races the bootstrap gate (listener bound before root load/instantiate/register) is truthfully rejected with BUSY and must be retried; the first successful submit may require several attempts in tests. Never assert first-attempt SUCCESS against an endpoint that was announced before registration completed.
- The transport composition must dispatch remote operations on the root's shared controller so generated safe points service them, but the adapter's synchronous submit would then always observe `hasLiveWork()` and return BUSY. `ApplicationAttachment.submitAdmitted`/`resetAdmitted` reuse the poll's admitted lease; the adapter picks the admitted path when `hasAdmittedLease()` is true and the synchronous path otherwise (separate pump-controller compositions).
- Terminal wire frames can be lost at shutdown: the connection writer previously decremented its byte counter before `writeFrame`, so a drain observed zero bytes while the frame was still in flight and closed the socket under it. Account bytes only after the write and give `RemoteServer.close()` a bounded drain before closing transports. The client models a dropped request as a terminal DISCONNECTED status without completing the result future (reconnect-without-replay), so tests must read `RemoteRequest.status()`, not only the future.
- The attachable `optionsRevision` must not include packaging mode or include-sources: packaged activation always reconstructs through the classes assembly, and the context must equal the live root metadata's recorded context. Deployment/presentation options are recorded elsewhere in metadata.
- The reactor must be used for sibling modules: `mvn -pl lyra-cli test` without `-am` resolves stale installed compiler snapshots and fails with `NoSuchMethodError` on new builder methods. Include a compiler test (`ReplPackagingCompatibilityTest`) when selecting REPL/CLI tests because the compiler POM sets failIfNoTests.
- `expr ::fn[]` adjacency inside a block parses as a receiver-qualified direct call and fails with LYC-TYPE-013; use `(fn)` S-expression calls or nested blocks for a final call form after another statement.
- In-process CLI runs with `--repl` are driven on the calling thread; the loopback endpoint is cross-process reachable, so parent tests can drive forked app JVMs with `RemoteClient` and parse `lyra: repl listener: HOST:PORT (no authentication ...)` from stderr.
- Packaged activation must reconstruct the attachable context from verified embedded sources before root instantiation; otherwise compatibility rejection can execute application initializers. `RemoteServer` admission must order closed-state checks with connection/controller installation, publish controller readiness only after ServerHello is written, and snapshot accepted connections under an admission lock before bounded shutdown drain.

## Project Relevance

Reusable validation patterns for every future phase touching run/compile activation, the remote transport composition, shutdown ordering, or packaged reconstruction; documents the deliberate race outcomes (BUSY retry, DISCONNECTED) that assertion-grade tests must encode.

## Open Questions

None within Phase 12 scope. Phase 13 may revisit whether a first-submit BUSY during initialization should carry a dedicated control status instead of the shared busy outcome.
