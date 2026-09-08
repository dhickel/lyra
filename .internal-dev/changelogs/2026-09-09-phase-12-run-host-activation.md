# Changelog: Phase 12 — Run/compile/host activation and shutdown

## Date

2026-09-09

## Git Commit

736e29e

## Change Summary

- `lyra-cli` now accepts `run ROOT --repl` with run-only `--repl-port PORT` (0..65535, default 0) and `--repl-wait`, and `compile ROOT --repl` which records the capability only. `--repl-port`/`--repl-wait` require `--repl`; ports are validated at parse time including the ephemeral port 0. Ordinary options and literal arguments after `--` are unchanged, and no token/credential controls exist or were added.
- `run --repl` compiles through the attachable debug-capable pipeline, bootstraps the bounded loopback v2 listener before the root is published, gates all live work until initialization and actual root registration succeed, waits after registration and before main when requested, and shuts down in order: retire queued control requests, close listener/service, close the owned root (retiring root-lifetime generations), close the loading context. The primary failure or exit request is preserved and cleanup failures are suppressed. Bind/port/control failures are configuration exits; init failures keep their ordinary exit class; main's return value remains the exact exit request.
- New `io.mindspice.lyra.repl.ReplActivation` owns the composition: bootstrap listener with a rejecting dispatcher and gated adapter, registration switching to the root's shared controller, controller-readiness wait, and close ordering. `ReplLauncher` activates through the documented runtime properties `lyra.repl.enabled` (default disabled), `lyra.repl.port`, and `lyra.repl.wait`, reconstructs the attachable root context from the embedded source inventory, and treats activation-path compatibility failures as configuration errors (exit 2).
- `ApplicationAttachment` gained the admitted-lease transport seams (`hasAdmittedLease`, `submitAdmitted`, `resetAdmitted`) so remote operations dispatched on the shared root controller reuse the poll's lease instead of being rejected busy or beginning a second evaluation. `ApplicationAttachmentAdapter` uses the admitted path when the owner holds a lease and the synchronous path otherwise.
- `RemoteServer.close()` retires a queued active request with an explicit CANCELLED terminal frame and performs a bounded writer drain before closing transports; the connection writer now accounts bytes only after a frame is actually written, so terminal frames are never dropped by the shutdown drain while a slow or broken peer can never keep main alive.
- The attachable `optionsRevision` no longer includes packaging mode or include-sources, both deployment/presentation choices recorded elsewhere in metadata, so packaged activation reconstruction reproduces the context across classes/thin/bundled layouts. `DebugArtifactContext` gained `attachableContext()` and its rebuild verification now always compares the options revision.

## Files

- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraCli.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ReplActivation.java` (new)
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ReplLauncher.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ApplicationAttachment.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/ApplicationAttachmentAdapter.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteServer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/DebugArtifactContext.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/ArtifactAssembly.java`
- `lyra-cli/src/test/java/io/mindspice/lyra/cli/ReplRunCliTest.java` (new)
- `lyra-cli/src/test/java/io/mindspice/lyra/cli/ReplLauncherIT.java` (new)
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ReplActivationJavaHostTest.java` (new)
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ReplActivationHostDriver.java` (new)
- `.internal-dev/specifications/repl.md`, `.internal-dev/specifications/decisions.md`, `AGENTS.md`

## Behavioral Impact

- Shipped users can `run ROOT --repl [--repl-port PORT] [--repl-wait]`, compile the capability with `compile ROOT --repl`, and activate compiled attachable artifacts through the runtime properties; everything remains disabled by default. The endpoint announcement includes the unauthenticated authority warning.
- During initialization a remote submit is truthfully rejected (BUSY) and never touches a partial root; after registration it executes on the original owner at generated safe points with exactly one lease. A submit racing the bootstrap gate must retry; a wire write racing the service close terminates as DISCONNECTED without replay.
- Service close never closes an externally owned root; the root may reopen attachment in its retained domain, and closed hooks are inert for continued application code.

## Specification Impact

`repl.md` now documents the delivered run/host activation surface and the runtime properties; `decisions.md` records the Phase 12 composition, shutdown-order, and options-revision decisions. Ordinary language/AOT contracts and `backend-runtime.md` are unchanged.

## Risks

- The bootstrap/registration race surfaces as a truthful BUSY that a client must retry; automated clients need that retry. No silent admission or fake readiness is substituted.
- The bounded close drain (500 ms) is the only shutdown wait; a peer that stops reading can delay terminal delivery by that bound, never keep the process alive.

## Follow-up Items

- Phase 13 cross-surface conformance and repeated lifetime; Phase 14 release audit, docs, independent review, and coverage matrix.
