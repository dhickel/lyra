# REPL and application attachment

> **Security warning:** Attachment is an explicitly enabled, unauthenticated, loopback-only trusted development interface. Any local process that can reach it can execute Lyra source with the application's authority. There is no token, credential file, encryption, sandbox, or hostile-client isolation.

The REPL compiles each submission to Java 25 bytecode and executes only the new entry point against retained typed storage. It is not an interpreter and does not replay the transcript.

## Local console

```sh
lyra repl [DIR] [--source-root DIR]* [--history PATH] [--plain] [--keymap emacs|vi]
```

Startup is an empty scratch workspace. `DIR` and source roots configure module discovery only. They do not initialize a root or call `main`. Import a module in source or load a file explicitly.

## Commands

| Command | Behavior |
| --- | --- |
| `\help` | Show commands |
| `\bindings` | List committed names, canonical types, and mutability metadata |
| `\type SOURCE` | Analyze the final form without execution, pinning, publication, or history entry |
| `\load PATH` | Read one UTF-8 file on the execution host and submit it once |
| `\reload MODULE` | Rebuild one retained REPL-owned module or namespace alias from fresh source |
| `\reset` | Clear scratch declarations, aliases, owned resources, and source history after success |
| `\history` | Show source-only history; never replay it |
| `\quit` | Exit locally or detach remotely |

Commands are recognized only at a top-level source-unit boundary, not in multiline source, strings, comments, or program input. A colon-leading unit is Lyra source. `:Type[...]`, `::name[...]`, and `:.` are never console commands.

JLine mode adds multiline completeness, highlighting, delimiter matching, bracketed paste, indentation, Emacs/vi keymaps, search, and resize handling. Plain mode has no terminal decoration, uses the same completeness rules, and reports incomplete EOF. Source entry and local `readLine` coordinate one input owner. Program input is not source history.

## Persistence and publication

A finite submission may contain imports followed by declarations, assignments, and expressions. Unresolved names are not held for future submissions.

- Success publishes staged names and increments the committed revision.
- Compile/type/link failure executes nothing and publishes nothing.
- Runtime failure or cancellation publishes no staged names, but completed writes, output, and escaped initialized values remain.
- Private replacement creates new storage; older closures retain prior storage.
- Captured mutable bindings remain shared cells.
- Arrays, tuples, ranges, nominal objects, and compiler-certified callables retain identity and producer lifetime.
- A declaration-only submission has no separate value result.

Results contain immutable bounded snapshots rather than live Java objects. Defaults are depth 6, 100 aggregate elements, and 16 KiB rendered output. Snapshots preserve canonical type, nil/Unit distinctions, unsigned values, UTF-16 content, aliases/references, function descriptions, range components, and truncation. Formatting runs no user code. Public nominal members appear in declaration order; private class state is omitted.

## Imports and reload

Imported modules initialize once per pinned source revision and remain live across later submissions and transitive imports. Repeated identical imports are idempotent. Existing pins are not replaced when backing files change or disappear.

`\reload MODULE` reads fresh source and reconstructs only the REPL-owned reachable dependency closure. Publication is atomic after successful initialization and reports initializer progress. Old selective imports, captures, references, and saved methods keep their original producers. There is no watcher, automatic reload, state migration, or old-reference retargeting. Application-owned graphs cannot be reloaded.

## Java session API

The local API is synchronous and owned by its opening thread:

```java
import io.mindspice.lyra.repl.*;

try (LyraSession session = LyraSession.open()) {
    EvaluationResult first = session.submit(
            EvaluationSource.of("counter.lyra", "let @mut count :I32 = 1"));
    EvaluationResult second = session.submit(
            EvaluationSource.of("update.lyra", "count := 42"));
    EvaluationResult value = session.submit(
            EvaluationSource.of("read.lyra", "count"));
}
```

`EvaluationResult` distinguishes success, compilation failure, runtime failure, cancellation, busy, and closed outcomes. Submission/reset/close are owner-thread operations. `cancel(EvaluationId)` is the narrow cross-thread control and affects only the matching active evaluation. Cancellation is cooperative; blocking host I/O or code that does not reach generated boundaries can delay it.

The canonical runnable API example is [`examples/repl/HostExample.java`](../../examples/repl/HostExample.java). It also shows the actual attachment signature:

```java
ApplicationAttachment attachment = ApplicationAttachment.open(
        root, compiled.context(), SessionOptions.defaults());
attachment.submit(EvaluationSource.of("change.lyra", "count := 7"));
attachment.poll();
attachment.close(); // control surface closes; caller-owned root remains open
```

## Application attachment

```sh
lyra run app.lyra --repl --repl-port 0
lyra attach 127.0.0.1:PORT
```

`run --repl` starts the listener before root publication. Live work remains gated until initialization and exact root registration succeed. `--repl-wait` pauses after registration and before `main` until a protocol-v2 controller completes its handshake.

The attached workspace enters the root module's public top-level scope. Public `@mut` exports are real live bindings. Private names, stack locals, immutable exports, and imported mutation restrictions remain enforced. In attachable code, element mutation through a public mutable root aggregate is conservatively rejected because another evaluation may replace its contents at a safe point; scalar and whole-binding replacement remain available.

All live work runs on the application owner thread. Generated function/call boundaries, executable forms, loop backedges, and direct self-tail backedges provide safe points. At most one evaluation is active. Cancellation never terminates `main`. Normal `main` completion closes REPL resources and does not keep the process alive.

`compile ROOT --repl` emits capability but never listens. Enable a compiled debug bundle explicitly:

```sh
java -Dlyra.repl.enabled=true \
     -Dlyra.repl.port=0 \
     -Dlyra.repl.wait=true \
     -jar app.jar
```

Without `lyra.repl.enabled=true`, no listener starts. The port and wait properties do not alter program arguments or artifact bytes.

## Protocol v2

Protocol v2 is bounded length-prefixed UTF-8 JSON. It has explicit schemas for evaluation, load, reload, cancellation, reset, status, binding/type query, and bounded completion. It carries request identity, revision plus mutation sequence, sequence watermark, terminal status, and retained reconnect state.

Only one controlling connection is allowed. Socket threads validate and queue work but never execute Lyra. Disconnect requests cancellation while preserving committed state. Reconnect can recover retained terminal status without source resubmission. Duplicate request identities do not execute twice; changed reuse is rejected. Version 1 receives an explicit upgrade error and is never used as a fallback.

Attached `\load`, reload, and file completion use the application's filesystem. Program stdin/stdout/stderr remain at the host and are not forwarded over the protocol.

For detailed operating procedures see [the REPL guide](../repl.md) and [runnable examples](../../examples/repl/README.md).
