# Lyra REPL and Application Attachment

## Status

Living normative contract for the optional `lyra-repl` session/evaluation distribution, local console, and opt-in authenticated application attachment. It extends `backend-runtime.md` without changing ordinary AOT source semantics, imported-module mutation ownership, or runtime isolation.

### Current implementation boundary

The minimum scalar vertical slice below is implemented and tested. Private/public scalar bindings retain exact accessors to original initialized fields or captured cells. The three-submission `I32` counter returns 42, lexical replacement allocates a new identity, and new-submission closures can read/write retained scalar storage. Resolved external contracts, declaration/storage identities and mutation permissions survive semantic/flow validation and sealed IR lowering. The runtime's separate owner-confined storage domain validates opaque capabilities, exact logical/physical scalar contracts, identity, initialization, revision and lifetime before source execution. Metadata alone never authorizes access.

Module initialization remains Unit-typed. A separate sealed final-form contract produces an exact typed session entry point/result field; only new source executes. Tooling extraction returns bounded immutable scalar, nullable, array, tuple, function and alias snapshots. Session-generated form/function/self-tail-loop boundaries observe cooperative API cancellation. Failed/cancelled submissions publish no names and preserve completed scalar mutations. Reset/close retire retained owned generations; returned snapshots remain usable.

The data-aggregate extension is also implemented and tested. Arrays and tuples containing only scalars or further data aggregates retain their original typed storage and live identities across submissions. Tuple classes and function interfaces have a session-owned structural parent loader; their bytes are source-independent, their structural inventory is checked before admission, and repeat definitions must be byte-identical. State, cell, closure and facade classes remain generation-local. Session tuple component getters are public solely to cross this loader boundary; ordinary AOT tuple getters remain package-private. Exact generated accessor MethodTypes are checked before any source form runs.

External array provenance is an explicit `SessionOrigin` tied to the resolved external declaration and its contract route. It is not a fabricated allocation site. Different compatible session origins may alias and receive conservative joined writes. Semantic and IR sealing retain exact producer certification and validate these origins against external contracts; ordinary allocation origins still require producer-issued flow sites. Aggregate storage registration requires a source-local generation loaded in the same authenticated session domain. Imported aggregate origins and partial aggregate authority remain unsupported. Multi-module generations cannot access retained aggregate storage until imported ownership is carried across the boundary. This guard prevents completed effects from contaminating the certified source-local data domain.

Compiler-certified named callable persistence is now implemented for source-local session generations. Callable summaries retain call targets, captured values, shared mutable-cell snapshots, writes, allocation provenance and operation-site source spans across submissions. Exact higher-order calls, returned closures, callable-bearing arrays/tuples, recursive functions, lexical replacement, failed-submission effects and cooperative cancellation use the original generated code and cells rather than copied values or source replay. A retained callable is admitted only when its producer certificate, exact signature, capture/cell contracts and generation authority match. Imported callable/module linkage remains unsupported and continues to use structured `LYC-SESSION-001` diagnostics.

Compile/link failure performs no source effects. Data aggregates and callable values assigned into retained storage before runtime failure or cancellation remain usable after the failed generation is closed when their producer contracts are valid. Reset retires binding capabilities, pending link tables and the structural loader epoch; returned snapshots remain usable. `:type` preserves literal quoting, escapes and internal whitespace instead of treating source as shell-like arguments.

A separate runtime bridge is also implemented and tested for successfully initialized source-local generations loaded through the same authenticated session domain and epoch. Linkage pins the complete immutable artifact metadata and source-local classification; a reusable artifact ID alone cannot substitute another inventory or upgrade authority. Existing exact generated function parameters accept these Lyra closures without copying or merging artifact keys. Captures/cells stay in the original generation. Cross-generation producers must be OPEN; INITIALIZING/FAILED producers, other sessions, ordinary unregistered artifacts, imported graphs, foreign SAMs and wrong signatures remain rejected. Reset/close retire invocation authority.

This is not completion of the intended contract. Imports/pinned reuse/reload, configured or attached roots, asynchronous standalone owner execution, coordinated console/program input, escaped callable-generation ownership beyond the certified source-local domain, delayed cross-generation runtime source maps, application safe-point attachment and debug deployment remain incomplete. Current synchronous local sessions are owned by their opening thread. `PersistentScalarTest`, `PersistentAggregateTest`, `PersistentCallableTest`, `SessionStorageLinkTest`, `SessionAggregateLinkTest`, `SessionCallableRuntimeTest`, `SessionCapturedInstanceFlowTest`, `SessionDeclarationWriteFlowTest`, `ExecutedSnapshotTest`, `PlainConsoleTest` and the forked `SessionJavaConsumerTest` provide evidence for the delivered slices, not for those remaining requirements.

## Purpose

Define persistent source evaluation, attached root authority, console behavior, public API, remote transport, security, I/O, lifecycle, artifact profiles, and validation for Lyra's REPL capability.

## Intended Contract

### Scope and layering

The feature has three surfaces: a persistent local console, a small headless Java evaluation API, and an authenticated loopback socket attachment for explicitly debug-enabled applications. Compiler session support lives in `lyra-compiler`; dependency-free owner-thread control and cancellation hooks live in `lyra-runtime`; transport-independent session execution and the bounded socket implementation live in `lyra-repl`; JLine and CLI adapters live in `lyra-cli`.

The production execution path remains direct Java 25 bytecode. A submission is compiled and linked against real initialized typed bindings and executes only its newly generated entry point. No interpreter, transcript replay, fake initializer, serialized IR, or automatic result binding is permitted.

### Session and submission semantics

A session owns immutable source records, a revisioned namespace, typed storage identities, mutable cells, aggregate/function identities, imported module instances, and generation/resource ownership. Each finite submission may contain a leading import header followed by expressions, assignments, and declarations. It uses ordinary lexical resolution, static typing, source order, modifiers, complete signatures, and mutual recursion within that submission. Unresolved references are diagnostics; definitions are never held pending for a later submission.

Compilation/type/link failures execute nothing and publish no names. Execution stages namespace publication and runs only the new unit. Successful execution commits new names/import aliases after execution. Runtime failure or cooperative cancellation publishes no staged names while retaining completed mutations, output, and other effects. Escaped valid values remain linked; uninitialized storage is never exposed. A later private declaration creates a new binding identity and earlier references/closures retain the old identity. Assignment through an existing `@mut` binding updates its original cell.

Initialized imports are reused. Later imports are allowed and repeated identical imports may be idempotent. `:load` submits UTF-8 file contents once with file source mapping. Explicit `:reload` reconstructs only a REPL-owned reachable dependency graph, reports rerun initializers, and publishes it only after successful construction. Application-owned graphs cannot be reloaded or retargeted.

Reset clears scratch declarations, aliases, history view, and owned scratch resources while retaining a configured or attached root and all completed root mutations. Detach never closes an externally owned root. Close retires session resources and, for local roots, closes owned generations and roots after retained values are accounted for.

### Minimum scalar linkage acceptance gate

A scalar vertical slice is partial implementation of this specification, not completion of the broader session or attachment feature. Before enabling cross-submission scalar access it must prove:

- The three-submission private `I32` counter sequence above returns 42 from the original retained typed storage, not a copied value, replayed initializer, or source rewrite. Private replacement creates a distinct declaration/storage identity; ordinary assignment retains the selected identity.
- Sealed resolution, typing, flow and IR carry explicit external binding/storage linkage and an explicit typed submission result contract. Ordinary module initialization remains Unit-typed. A generated entry point executes only new source forms, and a successful expression produces an immutable typed snapshot without an automatic result binding.
- A separate authenticated session-owned storage domain validates exact scalar contracts, declaration/storage identity, assignment permission, initialization, revision and lifetime before any new source form executes. Matching names, types or numeric identity ordinals alone grant no authority. Ordinary AOT artifact keys, imported-module restrictions, generated closure authentication and foreign-SAM rejection remain unchanged.
- Compile/type/link failure publishes no names and executes no source effects. Successful execution publishes staged names only afterward. Runtime failure publishes no staged names but preserves completed mutations to existing storage; it does not promise rollback.
- Every live read, write, snapshot extraction and cleanup checks the owner thread. Reset/close retire owned storage without exposing uninitialized or closed locations. Returned immutable snapshots remain usable without live access.

These gates now cover source-local cross-generation closures, callable-bearing arrays/tuples, captured cells and certified callable summaries. They do not claim imported modules, reload or attachment. Those broader contracts above and below remain required for full REPL completion.

### Attached root authority

A configured local root or explicitly attached application enters only the root module's public top-level scope. Public `@mut` exports are current-module bindings and accept ordinary `name := value`; no separate debug setter exists. Immutable, private, nonexistent, and other-module imported names remain protected by normal static rules. Attachment never exposes stack locals or private names and never clones module state.

All live reads, writes, evaluation, formatting, and lifecycle operations run on the module owner thread. A standalone session uses one owner executor. An attached application uses its existing owner thread and services work through compiler-inserted cooperative safe points or explicit host polling. Requests are non-reentrant and one evaluation is active at a time. Safe points occur at function/call boundaries, direct self-tail-loop backedges, and appropriate executable-form boundaries. Cancellation is cooperative and never terminates main or the host.

The listener starts before main but never exposes partially initialized modules. Optional wait-for-client occurs after successful initialization and before main. Normal main completion closes REPL resources without changing the application's exit request or keeping the process alive.

### Public API

The public Java API is limited to opening a session, submitting source, receiving immutable structured results/diagnostics, cancelling by evaluation identity, resetting, and closing. Requests carry a source label/URI, optional document version, and origin offsets as passive mapping metadata. Results distinguish success, compilation failure, runtime failure, cancellation, busy, and closed states. Values are immutable bounded typed snapshots, never Java objects, executable handles, completion APIs, managed documents, generic value handles, or LSP contracts.

Snapshots retain canonical Lyra types, bounded scalar/aggregate display data, ordering, unsigned mathematical values, UTF-16 content, nil/Unit distinctions, function descriptions, aliases, references, and explicit truncation markers. Formatting never executes user code. Default budgets are depth 6, 100 aggregate elements, and 16 KiB rendered output.

### Console

`lyra repl [ROOT]` supports configured source roots, optional `--history PATH`, `--keymap emacs|vi`, and `--plain`; it initializes a root without invoking main. `lyra attach ENDPOINT --token-file PATH` uses the same essential console behavior. `run ROOT --repl` enables instrumentation/listening for that run. `compile ROOT --repl` builds debug capability but does not listen by itself; compiled applications require explicit runtime properties to activate listening.

The only developer commands are `:help`, `:bindings`, `:type`, `:load`, `:reload`, `:reset`, `:history`, and `:quit`. `:type` never evaluates. History is in memory by default and optional bounded restrictive files are client-controlled; tokens, protocol traffic, program input, and authentication messages are never stored or replayed. Source entry and local `readLine` share one coordinated input owner; attached evaluation retains host process stdin/stdout/stderr and never captures process output into the socket result.

Interactive TTY mode uses authoritative lexer/grammar completeness, nested comments, literals, delimiter diagnostics, multiline continuation, bracketed-paste atomicity, indentation, highlighting, delimiter matching, resize handling, Emacs/vi keymaps, searchable history, and cleanup. Plain/non-TTY mode has no ANSI decoration, uses the same completeness rules, diagnoses incomplete EOF, and returns status 1 after recoverable evaluation failures. Structural Lisp editing is out of scope.

### Remote protocol and security

The socket protocol is versioned, length-prefixed UTF-8 JSON with explicit operation schemas, a 1 MiB frame bound, request/correlation identity, session revision, authentication/handshake, terminal statuses, cancellation, reset, and internal console-query operations. Socket threads never execute live work. There is at most one authenticated controlling connection; a busy controller is rejected. Disconnect requests cancellation, preserves committed state, and permits explicit reconnect with status/retained terminal results without source resubmission.

Listeners bind loopback only with an ephemeral default port and a cryptographically random token of at least 256 bits. Credential files are exclusively created with restrictive permissions; endpoints print the credential-file path, never the token. Authentication uses constant-time comparison and bounded time/work. LAN listeners, built-in TLS/SSH servers, PID injection, Java serialization, reflection RPC, generic host access, and remote stdin/output forwarding are forbidden. SSH tunneling is the supported remote-machine mechanism.

### Artifacts and compatibility

Normal artifacts remain free of compiler, REPL-server, JLine, and generated safe-point dependencies. Debug-enabled classes, thin JARs, and bundled runnable JARs record explicit profile/dependency inventories and session linkage capability. Bundled debug artifacts include the actual compiler/REPL dependency closure required for in-process source compilation but exclude JLine, CLI, tests, and credential material. Thin/classes outputs document and preflight required external dependencies. Runtime endpoint, token, port, and wall-clock values never enter deterministic artifacts. Existing strict artifact metadata and compatibility checks remain fail-closed.

### Diagnostics and validation

Compiler diagnostic codes remain stable. REPL/configuration/cancellation statuses are narrowly scoped additions. Source text and exact zero-based end-exclusive UTF-16 spans are retained by submission/source identity, including origin offsets and file labels; delayed closure/runtime failures point to their originating source rather than generated wrappers. Validation must cover API/compiler/protocol on all platforms and Linux PTY/subprocess behavior in this environment. Native macOS/Windows behavior is N/A unless executed.

## Constraints

The REPL is a trusted owner-controlled code execution interface, not a sandbox. It cannot weaken `@mut`, static typing, imported-module ownership, owner-thread/lifecycle checks, closure authentication, foreign artifact/SAM rejection, initialization guards, or normal AOT behavior. Blocking host I/O may delay cancellation. Completed nontransactional effects survive failed submissions and reloads. No editor, document service, LSP, debugger mode, replay, profiling, AST/IR browser, automatic restart, persistent live-value serialization, or speculative plugin framework is included.

## Decisions

- One optional `lyra-repl` module owns reusable session/API/socket behavior; JLine remains in CLI.
- Ordinary Lyra assignment is the only attached-root mutation surface; no debug setter exists.
- Sessions use staged namespace publication with nontransactional effects and persistent typed identities.
- Standalone work uses one owner executor; attached work remains on the application owner thread.
- The protocol is loopback-only, token-authenticated, length-prefixed JSON with one controller.
- The public API exposes immutable typed snapshots rather than live handles or editor contracts.

## Validation

Conforming validation must prove persistent multi-submission evaluation, lexical replacement, mutable-cell and aggregate identity, typed closures, failure/cancellation publication rules, imports/reload/reset, root-public assignment, owner-thread execution, attachment lifecycle, bounded snapshots and source maps, all essential console commands, plain and Linux PTY behavior, JLine resource/history/privacy rules, protocol authentication/framing/controller/reconnect behavior, debug artifact inventories and `-Xverify:all`, deterministic normal/debug packaging, external Java API use, and full `mvn test`, `mvn clean verify`, Phase 23 evidence, and Phase 24 audit results. Native macOS/Windows results are N/A unless run.

## Open Questions

No consequential current-scope questions remain. Future editor/document/LSP integration, general debugger features, hostile-code isolation, and broader Java/engine interop require separate specifications.
