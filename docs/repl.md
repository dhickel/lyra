# Lyra REPL and Application Attachment

This document covers the optional `lyra-repl` distribution: the local console, the synchronous Java evaluation API, persistent imported modules and reload, remote attachment through the credential-free loopback protocol v2, `run`/`compile` activation, and deterministic debug-capable artifacts. The normative contract lives in `.internal-dev/specifications/repl.md`; this page is the user-facing workflow reference.

## Security model (read first)

The attachment interface is **trusted, not hardened**:

- `run ROOT --repl` and `-Dlyra.repl.enabled=true` start an **unauthenticated** loopback-only listener. There is no token, password, credential file, challenge, or encryption.
- Any process that can reach the enabled listener can execute Lyra source **with the application's authority**. Only run it on a machine and account you trust.
- The listener never binds non-loopback addresses, validates its port (0..65535, `0` = ephemeral), prints the actual bound endpoint, and the endpoint display warns that reachable local clients have execution authority.
- Type compatibility, initialization, owner-thread, producer-lifetime, import-mutation and direct-bytecode checks all remain. They exist because correct execution requires them, not as a hostile-client security boundary.

Authentication, credential files, hostile-client isolation, and security-specific filesystem policy are explicitly deferred scope. Automatic initialization of a local project root is also deferred.

## Local console

```sh
java -jar lyra-cli/target/lyra-cli-0.1.0.jar repl [DIR] [--source-root DIR]... \
    [--history PATH] [--keymap emacs|vi] [--plain]
```

- Startup is an **empty scratch workspace**. `DIR` and repeatable `--source-root DIR` configure module search directories only; nothing is initialized and `main` is never invoked.
- User modules enter scope only through explicit `import` headers in a submission or explicit `\load`.
- The only commands are `\help`, `\bindings`, `\type`, `\load`, `\reload MODULE`, `\reset`, `\history`, `\quit`. Commands use a single leading backslash at a source-unit boundary; `::function[args]` is evaluated as ordinary Lyra source.
- `\type SOURCE` analyzes supplied source against the committed context **without executing, pinning, or publishing** anything.
- `\load FILE` reads a local UTF-8 file once, maps diagnostics to that file, and submits its contents. `\reload MODULE` accepts a committed namespace alias or logical module ID and rebuilds only the REPL-owned reachable dependency closure; `\reload` without a target is a usage error.
- Interactive TTY mode adds multiline completeness, bracketed-paste atomicity, indentation, highlighting, delimiter matching, Emacs/vi keymaps, history search, resize handling, and cleanup. `--plain` is the undecorated fallback.
- History is in memory by default; `--history PATH` enables a bounded source-only history file. A successful `\load` records exact loaded source only when the adapter returns it, never the command/path; attached protocol-v2 loads intentionally return no source text. Successful reset clears source history, while failed/busy reset preserves it. Program input (`std->io` `readLine`) never enters source history.
- Semantic completion reads committed binding/type/member metadata only; file and module completion is bounded read-only lookup of the execution host's filesystem and never compiles, initializes, or pins candidates.

## Modules, imports, pinned reuse and reload

A submission may begin with an import header followed by expressions, assignments and declarations:

```lyra
import counter
import shapes->{maker}

counter->::next[]          // initialized module state is reused
```

- Each successfully initialized module instance is pinned to its exact source revision and reused across later submissions and transitive imports. Later imports of the same revision never reread the backing file or rerun an initializer.
- Repeated identical imports are idempotent; conflicts, duplicate identities, missing modules, ambiguity and eager cycles are ordinary diagnostics.
- Editing or deleting a backing file never replaces an existing pin. Use `\reload` to move forward.
- `\reload MODULE` resolves the target's **fresh** source, reconstructs its REPL-owned dependency closure using the new import topology (stopping at borrowed application-owned dependencies), reports scheduled/attempted/completed initializers distinctly, and publishes affected defaults atomically only on success.
- Old captured values, selective imports, compiled lexical references and existing dependency edges keep their original producers. Unaffected defaults are unchanged. There is no watcher, automatic reload, state migration, or old-reference retargeting.
- `std->io` is an intrinsic module (`import std->io`) and executes through the session's real I/O environment.

## Effects, publication and limits

- Compile/type/link failure executes **no** Lyra source and publishes no names.
- Runtime failure or cooperative cancellation preserves completed mutations, output and escaped values but publishes no staged names. Effects are nontransactional: there is no rollback or reexecution.
- The entire newly discovered graph's source retention capacity and known minimum result/envelope representability are preflighted **before** any initializer effect. A rejection means nothing ran.
- Runtime-dependent display size is handled by explicit bounded truncation of snapshots, diagnostics and query lists; a large result is truncated, never rolled back. Default snapshot budgets are depth 6, 100 aggregate elements, and 16 KiB rendered output.
- UTF-16 character limits (source/snapshots) are distinct from encoded UTF-8/JSON frame bytes (transport).
- Live producer source is never evicted. Standalone sessions conservatively retain successful/attempted generations and old reload graphs until reset/close; attached roots pin producers, link tables, the structural type domain, semantic summaries and source data until root close.
- Struct/class declarations and instances persist with their exact nominal identity. Later submissions may access or mutate public members, invoke or replace mutable methods, and construct additional instances through the original initialized factory. Each construction returns a distinct instance; fresh values built inside a retained factory are new per construction, while shared returned storage and aliases stay shared. Argument/default/constructor effects run in producer order, and a failed or cancelled construction publishes no staged names or partial instance while keeping completed effects on older state and leaving the session usable. Snapshots label structs/classes, expand public members in declaration order, preserve aliases/cycles as references, and omit private class state without invoking user code.
- Retained nominal reads authenticate the exact object, schema, producer and generated field route. Callable fields that cross an importing-generation boundary carry opaque occurrence-scoped evidence for the selected value; raw imported closures remain rejected, saved selections survive field replacement, and reset/root/producer retirement invalidates old evidence. Unit/intrinsic member initializers remain observable in later generations without replaying construction effects. Nilable member reads derive their exact schema contract for annotations, coalescing, predicate narrowing and value match.

## Attach: run and compile

```sh
# run: activate an attachable root for one run
java -jar lyra-cli/target/lyra-cli-0.1.0.jar run ROOT.lyra --repl \
    [--repl-port PORT] [--repl-wait] [-- ARGS...]

# in another terminal
java -jar lyra-cli/target/lyra-cli-0.1.0.jar attach 127.0.0.1:PORT
```

- `run --repl` compiles an attachable, debug-capable root and starts the loopback listener **before** the root is published. Live work is gated until initialization and actual root registration succeed; a client during initialization receives a truthful busy/unavailable result and never touches a partial root.
- `--repl-port` (0..65535, default 0 = ephemeral) and `--repl-wait` are run-only and require `--repl`. `--repl-wait` pauses after initialization and before `main` until a handshake-complete v2 controller connects (or orderly shutdown/interrupt).
- `compile ROOT --repl` records the same capability and **never listens**. Compiled artifacts activate only through runtime properties:

```sh
java -Dlyra.repl.enabled=true [-Dlyra.repl.port=PORT] [-Dlyra.repl.wait=true] \
    -jar app.jar -- ARGS...
```

- Absent enablement starts no listener and changes no program arguments. Normal exit, main failures and initialization failures close control resources and preserve the application's exit/arguments; there is no post-main keepalive.

The attached workspace enters the root module's public top-level scope. Public `@mut` exports are live bindings; `count := value` writes the real root storage, public functions are real instances, and private names, stack locals and immutable/imported names keep their ordinary rules. Safe points (function/call boundaries, direct self-tail-loop backedges, executable forms) service at most one pending request; cancellation targets only its evaluation and never terminates `main`.

## Remote protocol v2 and v1 migration

The wire protocol is version 2: length-prefixed UTF-8 JSON, explicit operation schemas, session/request identity, revision plus mutation sequence, terminal statuses, cancellation, reset, LOAD/RELOAD, bounded metadata completion, finite frames/queues/result retention, and explicit reconnect without replay.

- Version 1 peers receive an explicit unsupported-version/upgrade diagnostic. There is no legacy authenticated mode, no token adapters, and no silent downgrade.
- Disconnect requests cancellation, preserves committed state, and reconnects to retained terminal results without source resubmission.
- Duplicate request identities are recognized without reexecution; a changed payload under a reused identity is rejected.
- Program stdin/stdout/stderr stay on the execution host; they are never forwarded into wire payloads. `\load`/`\reload`/completion on an attached console read the **application's** filesystem, never the client's.

## Debug artifacts

- `compile ROOT --repl --format classes|thin-jar|bundled-jar` produces debug-capable output. Debug capability is a versioned canonical metadata declaration (`replCapability`, schema 1) orthogonal to the generated-code profile.
- Every debug publication embeds all reachable original source snapshots (path and resolver-produced URI identities, exact UTF-8 bytes, SHA-256), the canonical import resolution topology with spans, and the reproducible scalar compilation options of the original build. It also declares the exact fixed closure: `io.mindspice:lyra-compiler`, `io.mindspice:lyra-repl`, `io.mindspice:lyra-runtime` at the artifact's own execution profile.
- Packaged reconstruction rebuilds the recorded graph purely from embedded sources through the ordinary compiler pipeline. It never re-queries resolver objects, never reads edited/deleted original files, never executes initializers, and never deserializes IR. Missing, extra, or different closure entries and incomplete source contexts are structured configuration errors.
- Bundled debug JARs are runnable with `java -jar` and include the real compiler/REPL closure; they exclude JLine, the CLI, tests and credential material. Classes/thin layouts invoke `io.mindspice.lyra.repl.ReplLauncher` with an explicit artifact-location argument and the declared closure on the class path.
- Normal artifacts remain byte-identical to their non-debug encodings: no compiler/REPL/JLine dependency, no generated polling calls, schema-1 compatibility unchanged. Activation endpoint, port, wait state and wall-clock values never enter artifact bytes.

## Java host API

The local evaluation API is synchronous and opening-thread-owned:

```java
try (LyraSession session = LyraSession.open(SessionOptions.defaults())) {
    EvaluationResult result = session.submit(
            EvaluationSource.of("counter.lyra", "let @mut count :I32 = 1"));
    // ... read immutable result/snapshot metadata, then
    session.submit(EvaluationSource.of("update.lyra", "count := 42"));
}
```

An attachable application registers its own root explicitly on its owner thread. A synchronous submission executes immediately on that thread:

```java
ApplicationAttachment attachment = ApplicationAttachment.open(
        root, compiled.context(), SessionOptions.defaults()); // owner thread
try {
    EvaluationResult result = attachment.submit(
            EvaluationSource.of("change.lyra", "count := 7"));
    // Cross-thread callers use submitDispatch(...). Generated safe points or
    // explicit attachment.poll() calls then service one queued request.
} finally {
    attachment.close(); // closes control resources; the root survives and may reopen
}
```

Requests are non-reentrant, one evaluation is active at a time, and results are immutable bounded typed snapshots. See `lyra-repl`'s `SessionJavaConsumerTest` and `ApplicationAttachmentJavaConsumerTest` for forked consumer fixtures and `examples/repl` for a complete host.

## Lifetime and reopen rules

- Namespace visibility and producer lifetime are separate. Standalone reset clears scratch names/aliases/history and closes owned scratch resources while preserving a configured root's state.
- Attached roots pin producers, original link tables, the structural type domain, summaries and source data to the root lifetime. Values stored before failure/cancellation survive reset, disconnect, detach and service close, and interoperate with new values through one root-lifetime structural domain.
- Reopening a service on the same live root reuses the retained domain and starts a fresh scratch workspace; it never revives removed scratch names. A second simultaneous service on the same root is rejected; independent roots stay independent.
- Root close retires all root-lifetime producers and invalidates retained closures. There is no heap/metaspace collection guarantee; retention is deliberately conservative.

## Operational limits summary

| Limit | Behavior |
| --- | --- |
| New-graph source retention | Rejected before any initializer effect |
| Minimum result/envelope representability | Rejected before any effect |
| LOAD source + encoded envelope | Preflighted before effects (exact UTF-8/JSON bytes) |
| Dynamic result/diagnostic/query size | Explicit bounded truncation after effects; terminal status preserved |
| Frame/queue/result retention | Finite protocol bounds; no reexecution after truncation |
| Listeners | Loopback-only, one controlling connection, one active operation |
| Cancellation | Cooperative, identity-specific; blocking host I/O can delay it |

## Validation

```sh
mvn clean verify                       # full reactor incl. integration tests
./tools/phase24-release-audit.sh       # final release/scope audit (fresh Phase 23 gate included)
```

The release audit requires exact annotated executed test methods for every retained requirement, keeps the four-module dependency graph and normal AOT behavior unchanged, and records native Windows launcher evidence as N/A on Linux.
