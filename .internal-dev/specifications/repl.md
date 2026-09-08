# Lyra REPL and Application Attachment

## Status

Living normative contract for the optional `lyra-repl` session/evaluation distribution, local console, persistent module workspace, and explicitly enabled trusted localhost application attachment. It extends `backend-runtime.md` without changing ordinary AOT source semantics, imported-module mutation ownership, or the direct-bytecode production path.

The delivered foundation includes persistent scalar, aggregate and compiler-certified callable values; pinned imported-module reuse; explicit REPL-owned reload; actual generated `std->io` session execution; immutable bounded snapshots; staged publication; cooperative session cancellation; plain/JLine consoles; an owner-dispatched loopback transport; deterministic debug-capable classes/thin/bundled artifact packaging with embedded source/resolution context; the fixed REPL launcher composition; and the run/host activation surface (`run --repl`, `--repl-port`, `--repl-wait`, and the `lyra.repl.enabled/port/wait` runtime properties). Activation is disabled by default; the bootstrap listener starts before root publication and gates live work until initialization and actual root registration succeed, the optional wait pauses after registration and before main until the v2 controller handshake, and shutdown retires queued control requests before closing the listener, service, owned root and generations in order. Reload retains distinct compiler source/producer identities and original source origins; successful defaults are separate from historical producer contracts.

This interface is intentionally trusted rather than hardened. An explicitly enabled loopback listener has no authentication or credential file; any process that can reach it may execute with the application's authority. Type compatibility, initialization, owner-thread, producer-lifetime, ordinary import-mutation and direct-bytecode checks remain because they are required for correct execution, not because they provide hostile-client security. The attached root is explicitly registered and must be compiled with the REPL-capable profile; arbitrary JVM/PID attachment is not supported.

Local `repl` startup is an empty scratch workspace. A positional directory and repeatable source-root options configure module discovery only; they do not initialize a root or invoke `main`. User modules are explicitly imported or loaded, retained by source revision, and reloadable only through the explicit `:reload MODULE` command. Application roots are a separate host-owned surface. `run ROOT --repl` compiles an attachable debug-capable root, starts the unauthenticated loopback listener before the root is published, and gates all live work until initialization and actual root registration succeed; `--repl-port` (default 0, ephemeral) and `--repl-wait` are run-only controls. `compile ROOT --repl` records the same capability in the artifact without ever listening. Compiled attachable artifacts activate only with explicit runtime properties: `-Dlyra.repl.enabled=true`, optional `-Dlyra.repl.port=PORT` and `-Dlyra.repl.wait=true`; absent enablement starts no listener and changes no program arguments.

## Purpose

Define persistent source evaluation, attached root authority, console behavior, public API, remote transport, security, I/O, lifecycle, artifact profiles, and validation for Lyra's REPL capability.

## Intended Contract

### Scope and layering

The feature has four cooperating surfaces: a persistent local console, a small synchronous Java evaluation API, a persistent module workspace, and an explicitly enabled trusted localhost attachment for REPL-capable applications. Compiler session support lives in `lyra-compiler`; dependency-free owner-thread control and cancellation hooks live in `lyra-runtime`; transport-independent session execution, module ownership and the credential-free protocol live in `lyra-repl`; JLine and CLI adapters live in `lyra-cli`. No component is an interpreter or source-replay engine.

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
- A separate typed session link domain validates exact scalar contracts, declaration/storage identity, assignment permission, initialization, revision and producer lifetime before any new source form executes. Matching names, types or numeric identity ordinals alone identify no storage. Ordinary AOT artifact compatibility, imported-module mutation rules, generated closure behavior and foreign-SAM rejection remain unchanged; optional trusted attachment does not alter those ordinary contracts.
- Compile/type/link failure publishes no names and executes no source effects. Successful execution publishes staged names only afterward. Runtime failure publishes no staged names but preserves completed mutations to existing storage; it does not promise rollback.
- Every live read, write, snapshot extraction and cleanup checks the owner thread. Reset/close retire owned storage without exposing uninitialized or closed locations. Returned immutable snapshots remain usable without live access.

These gates now cover source-local cross-generation closures, callable-bearing arrays/tuples, captured cells and certified callable summaries. They do not claim imported modules, reload or attachment. Those broader contracts above and below remain required for full REPL completion.

### Attached root authority

Only an explicitly registered REPL-capable application root is attachable. The attached workspace enters the root module's public top-level scope. Public `@mut` exports are current-root bindings and accept ordinary `name := value`; no separate debug setter exists. Immutable, private, nonexistent and other-module imported names remain protected by normal static rules. Attachment never exposes stack locals, private names or a cloned module state.

Attachable compilation models dispatch safe points as explicit effect boundaries: the live contents of a public `@mut` root binding may be replaced by evaluation writes between safe points, so compiled consumers treat aggregate contents read through those bindings as potentially foreign-owned. Element mutation through a public `@mut` root aggregate binding is rejected in attachable mode by the ordinary imported-mutation diagnostic; scalar writes, whole-binding replacement, callable replacement, private-state mutation and higher-order transfers remain allowed, and normal-profile compilation is unchanged.

All live reads, writes, evaluation, formatting and lifecycle operations run on the module owner thread. The standalone Java API remains synchronous and opening-thread-owned; managed local consoles may use one internal owner loop. An attached application keeps its existing owner thread and services work through compiler safe points or explicit host polling. Requests are non-reentrant and one evaluation is active at a time. Safe points occur at function/call boundaries, direct self-tail-loop backedges and executable-form boundaries. Cancellation is cooperative and never terminates main or the host.

The listener starts before main but never exposes partially initialized modules. Optional wait-for-client occurs after successful initialization and before main. Normal main completion closes REPL resources without changing the application's exit request or keeping the process alive.

### Public API

The public Java API is limited to opening a session, submitting source, receiving immutable structured results/diagnostics, cancelling by evaluation identity, resetting, and closing. Requests carry a source label/URI, optional document version, and origin offsets as passive mapping metadata. Results distinguish success, compilation failure, runtime failure, cancellation, busy, and closed states. Values are immutable bounded typed snapshots, never Java objects, executable handles, completion APIs, managed documents, generic value handles, or LSP contracts.

Snapshots retain canonical Lyra types, bounded scalar/aggregate display data, ordering, unsigned mathematical values, UTF-16 content, nil/Unit distinctions, function descriptions, aliases, references, and explicit truncation markers. Formatting never executes user code. Default budgets are depth 6, 100 aggregate elements, and 16 KiB rendered output.

### Console

`lyra repl [ROOT]` supports source search directories, optional `--source-root DIR`, `--history PATH`, `--keymap emacs|vi` and `--plain`; it opens an empty workspace and never invokes `main`. `lyra attach HOST:PORT` connects to an explicitly enabled localhost service without a token file. `run ROOT --repl` enables the attachable instrumentation and the gated listener for that run, with run-only `--repl-port PORT` (0..65535, default 0) and `--repl-wait`. `compile ROOT --repl` builds the same capability but never listens; compiled artifacts activate only through the explicit `lyra.repl.enabled` (plus optional `lyra.repl.port` and `lyra.repl.wait`) runtime properties, default disabled.

The only developer commands are `:help`, `:bindings`, `:type`, `:load`, `:reload MODULE`, `:reset`, `:history` and `:quit`. `:type` never evaluates. History is in memory by default and optional bounded source-only files are client-controlled; source history never contains program input or protocol traffic. Source entry and local `readLine` share one coordinated input owner; attached evaluation retains host stdin/stdout/stderr and never captures process output into the socket result. Completion is bounded read-only metadata/filesystem lookup and is not a public editor API.

Interactive TTY mode uses authoritative lexer/grammar completeness, nested comments, literals, delimiter diagnostics, multiline continuation, bracketed-paste atomicity, indentation, highlighting, delimiter matching, resize handling, Emacs/vi keymaps, searchable history, and cleanup. Plain/non-TTY mode has no ANSI decoration, uses the same completeness rules, diagnoses incomplete EOF, and returns status 1 after recoverable evaluation failures. Structural Lisp editing is out of scope.

### Remote protocol

The socket protocol is version 2, length-prefixed UTF-8 JSON with explicit operation schemas, finite frame/result/queue bounds, request/correlation identity, session revision plus mutation sequence, terminal statuses, cancellation, reset, reload/load and metadata-query operations. Socket threads never execute live work. There is at most one controlling connection; a busy controller is rejected. Disconnect requests cancellation, preserves committed state and permits explicit reconnect with status/retained terminal results without source resubmission. Version 1 clients receive an explicit upgrade diagnostic.

Listeners bind loopback only with an ephemeral default port and are enabled explicitly. No authentication, token, credential file, encryption or hostile-client isolation is provided or promised. The endpoint display warns that reachable local clients have application execution authority. Normal framing, schema, type, owner, initialization and lifecycle validation remains required for reliable correct operation. Program stdin/stdout/stderr remain at the execution host; they are not forwarded as generic protocol data.

### Artifacts and compatibility

Normal artifacts remain free of compiler, REPL-server, JLine and generated safe-point dependencies. Debug-enabled classes, thin JARs and bundled runnable JARs record explicit profile/dependency/source-context inventories and session linkage capability. Bundled debug artifacts include the actual compiler/REPL dependency closure required for in-process source compilation but exclude JLine, CLI, tests and credential material. Thin/classes outputs document and preflight required external dependencies. Runtime endpoint, port, wait state and wall-clock values never enter deterministic artifacts. Existing strict artifact metadata and compatibility checks remain fail-closed; the optional debug profile is a declared deployment layout, not a security mechanism.

Debug capability is a versioned canonical metadata declaration (`replCapability`, schema 1) orthogonal to the generated-code execution profile. A debug-capable publication always embeds every reachable original source snapshot (path and resolver-produced URI identities, exact UTF-8 bytes, SHA-256 verified), the canonical import resolution topology with exact source spans, and the reproducible scalar compilation options of the original build, and declares the exact fixed production closure requirement: `io.mindspice:lyra-compiler`, `io.mindspice:lyra-repl`, and `io.mindspice:lyra-runtime` at the artifact's own execution profile. Missing, extra, or different closure entries and incomplete embedded source contexts are structured compatibility/configuration errors. Ordinary schema-1 normal metadata, revisions, and runtime-only bundles are byte-identical to their non-debug encodings; the capability input enters the artifact revision only when declared. Packaged reconstruction rebuilds the recorded module graph and revisions purely from embedded sources through the ordinary compiler pipeline: it never re-queries resolver objects, never reads edited or deleted original files, never executes initializers, and never deserializes IR. The fixed `io.mindspice.lyra.repl.ReplLauncher` is the profile-aware Main-Class of debug bundled JARs; it locates its artifact from its own code source, preflights the declared closure with a fixed source compile that executes no Lyra code, and runs the ordinary exact `main` contract. External classes/thin layouts invoke the same launcher with an explicit documented artifact-location argument and the declared closure on the class path. Ordinary bundles keep the dependency-free `io.mindspice.lyra.runtime.LyraLauncher`. Bundled closure collection reads only fixed production code sources (runtime, compiler, and the REPL anchor class) and never scans arbitrary class-loader or test resources.

### Diagnostics and validation

Compiler diagnostic codes remain stable. REPL/configuration/cancellation statuses are narrowly scoped additions. Source text and exact zero-based end-exclusive UTF-16 spans are retained by submission/source identity, including origin offsets and file labels; delayed closure/runtime failures point to their originating source rather than generated wrappers. Validation must cover API/compiler/protocol on all platforms and Linux PTY/subprocess behavior in this environment. Native macOS/Windows behavior is N/A unless executed.

## Constraints

The REPL is a trusted owner-controlled code execution interface, not a sandbox or authenticated service. It may omit credentials, hostile-client isolation and security-only filesystem policy. It cannot weaken `@mut`, static typing, imported-module ownership, owner-thread/lifecycle correctness, exact typed links, initialization guards or normal AOT behavior. Blocking host I/O may delay cancellation. Completed nontransactional effects survive failed submissions and reloads. No editor, document service, LSP, persistent debugger mode, replay, profiling, AST/IR browser, automatic restart, persistent live-value serialization or speculative plugin framework is included.

## Decisions

- One optional `lyra-repl` module owns reusable session/API/socket behavior; JLine remains in CLI.
- Ordinary Lyra assignment is the only attached-root mutation surface; no debug setter exists.
- Sessions use staged namespace publication with nontransactional effects and persistent typed identities.
- Standalone public work remains synchronous and opening-thread-owned; managed consoles may provide one internal owner loop, while attached work remains on the application owner thread.
- The protocol is explicitly enabled, loopback-only, credential-free, length-prefixed JSON with one controller; reachable local clients are trusted.
- The public API exposes immutable typed snapshots rather than live handles or editor contracts.

## Validation

Conforming validation must prove persistent multi-submission evaluation, lexical replacement, mutable-cell/aggregate/callable identity, typed closures, failure/cancellation publication rules, imports/reload/reset, actual root-public assignment, owner-thread execution, trusted attachment lifecycle, bounded snapshots/source maps, all essential console commands, plain/Linux PTY behavior, JLine input/history behavior, credential-free protocol framing/controller/reconnect behavior, debug artifact inventories and `-Xverify:all`, deterministic normal/debug packaging, external Java API use, and full `mvn test`, `mvn clean verify`, Phase 23 evidence and Phase 24 audit results. Authentication/security-hardening and automatic local-root initialization are explicit deferred/superseded scope, not validation blockers. Native macOS/Windows results are N/A unless run.

## Open Questions

No consequential current-scope questions remain. Future editor/document/LSP integration, general debugger features, hostile-code isolation, and broader Java/engine interop require separate specifications.
