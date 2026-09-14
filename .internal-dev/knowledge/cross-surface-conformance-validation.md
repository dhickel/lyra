# Cross-Surface Conformance Validation

## Topic

Phase 13 validation patterns for one persistent Lyra corpus across local, managed, plain, JLine, remote-v2, attached-root, Java-host and activation surfaces.

## Source References

- `.internal-dev/plans/20260906-221525-complete-lyra-repl-with-trusted-module-linking-and-local/plan.md`, Phase 13 and R01-R18
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/CrossSurfaceCorpus.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/CrossSurfaceLocalApiTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/CrossSurfaceManagedConsoleTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/CrossSurfacePlainConsoleTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/remote/CrossSurfaceRemoteStandaloneTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/CrossSurfaceAttachedAppTest.java`
- `lyra-cli/src/test/java/io/mindspice/lyra/cli/JLineConsoleTest.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySet.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source/ModuleGraphDiscovery.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ApplicationAttachment.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/remote/RemoteServer.java`

## Key Takeaways

- A solved callable SCC may contain current-graph and retained certificate-only lambdas. Selecting only current summaries must still publish complete SCC membership. Preserve wholly selected components and represent selected members of mixed components as deterministic singleton publication components; later solvers recompute their own fixed-point topology.
- Reusing a pinned module must reuse its exact retained `SourceSnapshot`. Recapturing identical bytes under a session-qualified compiler identity changes origin/header evidence and breaks later reload topology validation and delayed-source fidelity.
- Attached workspaces must seed their `SourceRegistry` with the complete original application source inventory and share the retained source archive through the root lifetime across reset, detach and reopen. Otherwise runtime frames retain spans but lose excerpts, root-held scratch closures outlive their source text, and root-context capacity is never preflighted. Clearing history records must not clear live producer snapshots or passive caller origins.
- Snapshot function display identities are local aliases allocated while rendering one snapshot. Cross-submission equality of strings such as `fn1` does not prove runtime identity equality; execute Lyra `eq?` against old/new values instead.
- Root initializer I/O belongs to the `LoadOptions` used to instantiate the application, not the later attachment's session options. Tests that assert initializer output must bind the same explicit `RuntimeIoEnvironment` at load time.
- `RemoteEndpoint.display()` owns the no-auth authority warning; `toString()` is a diagnostic record rendering and intentionally does not promise the warning.
- During graceful server shutdown, the connection handler must not run ordinary disconnect cleanup after observing the server-wide closed flag. That cleanup clears the writer queue and can race away the queued `CANCELLED` terminal frame. Server shutdown owns bounded drain, transport close and slot release for its connection snapshot; ordinary peer disconnect still owns cancellation cleanup.
- Plain history files encode exact source entries with escaped line terminators. Numbering is added only by `\history` presentation; reopen the console to prove decoding rather than asserting numbered disk text.
- JVM `CONSTANT_Utf8` uses modified UTF-8 over UTF-16 code units: NUL takes two bytes, ASCII non-NUL one, U+0080-U+07FF two, and every other code unit three. Astral characters therefore take six bytes. Preflight literals before class emission and return a structured emit diagnostic; never split literals or let the Class-File API exception escape.

## Project Relevance

These rules prevent conformance tests from confusing presentation aliases with value identity, client-side formatting with endpoint contracts, or session configuration with application initialization. They also preserve original source evidence, pre-effect rejection and complete callable-summary publication across local, remote and attached generation reuse.

## Open Questions

None for Phase 13. Phase 14 still owns the release matrix, documentation audit, fresh Phase 23 invocation and independent release review.
