# AGENTS.md

This file provides repository-specific guidance for coding agents working on Lyra.

## Project Mission

Lyra is an experimental standalone functional JVM scripting language. Its first product boundary is a complete source-to-Java-25-bytecode compiler, standalone CLI/runtime, reusable class/JAR output, and direct typed use from Java. Later, Lyra is intended to embed in a Java game engine, including planned Vulkan integration, so current boundaries must preserve explicit module instances, lifecycle, threading, and future interop without making the engine the first implementation target.

The repository contains a verified compiler/runtime/CLI backend foundation plus an optional REPL module. Plain/JLine consoles, owner-confined lifecycle, non-executing `:type`, credential-free loopback protocol v2, exact cross-submission scalar/data-aggregate storage, compiler-certified callable persistence, shared structural JVM types, executed bounded snapshots and generated session cancellation boundaries are tested. Data aggregates may recursively contain scalars, arrays, tuples and certified callable values; imported-module persistence and pinned reuse, explicit REPL-owned reload, application-root attachment, cooperative application safe points, deterministic debug-capable classes/thin/bundled packaging, cross-surface conformance and the run/compile host activation surface (`run --repl`, `--repl-port`, `--repl-wait`, `lyra.repl.enabled/port/wait`) are implemented and tested. The REPL attachment is a trusted explicitly enabled loopback-only development interface with no authentication, not a hostile-code boundary. Local REPL startup is empty and source-root options configure discovery only; explicit application-root registration is separate. Phase 14 release audit, documentation and independent review are complete; `tools/phase24-release-audit.sh` remains the mandatory release gate.

## Current Baseline and Direction

The planning baseline for this REPL completion is commit `ae793e7d6776f6611fa78ad80ea357ac827ed131`; the current worktree contains substantial uncommitted backend work that must be preserved. The verified implemented pipeline is:

```text
source -> lex -> parse -> module discovery -> resolve -> type-check -> typed IR -> Java 25 bytecode -> artifact/runtime/CLI
```

The current product foundation includes `lyra-runtime`, `lyra-compiler`, `lyra-repl`, and `lyra-cli`, including direct bytecode emission, class/JAR output, loading, lifecycle, typed facades, `run`/`compile`, owner-confined session execution, plain/JLine consoles, and credential-free loopback protocol v2 transport. Scalar, data-aggregate and compiler-certified callable live-storage linkage, shared structural type/loading domains and executed result snapshots are implemented. Imported-module persistence, pinned reuse, reload, configured-root/application attachment, cooperative application safe points, deterministic debug packaging, run/compile host activation and cross-surface conformance are proven by code and tests; the Phase 14 release audit, documentation, and independent review mark the REPL completion closeout. The baseline for the Phase 13 work is commit `7aa5356`; later Phase 14 audit tooling/docs work builds on that commit in the same worktree.

Implementation should progress in small, tested slices:

1. Reconcile the grammar specification, lexer, parser, AST, and type model; fix correctness defects and add real positive and negative tests.
2. Establish module/namespace environments, lexical scopes, declaration binding, and symbol resolution, taking useful ideas from the unmerged resolver work only after validating them against `master`.
3. Add type checking/inference and the closed immutable typed IR specified in `.internal-dev/specifications/backend-runtime.md`, with exact Lyra-to-JVM mappings.
4. Build a narrow source-to-direct-bytecode vertical slice through the Java 25 Class-File API, while keeping completion gated on the entire normative language core.
5. Add deterministic class/JAR packaging, CLI `run`/`compile`, instantiable generated Java facades, Java runtime compilation, source-mapped failures, and assertion-grade integration/benchmark evidence.
6. Design Lyra-to-Java calls and engine/Vulkan integration only in later dedicated specifications; do not pull those concerns into the standalone backend implicitly.

Do not invent behavior outside the living language and backend/runtime specifications merely to unblock implementation. Record or escalate consequential conflicts instead of silently changing a public contract.

## Project Overview

This is a Java-based compiler/runtime for a standalone functional JVM language, with an optional owner-confined REPL and credential-free loopback protocol v2 transport. The language uses LISP-like forms, unique accessor operators, and a grammar-driven parsing system. The normative target is direct Java 25 class/JAR output and standalone/Java use first, with Java game-engine interop later.

## Build Commands

- **Compile:** `mvn compile`
- **Test:** `mvn test`
- **Clean:** `mvn clean`
- **Package:** `mvn package`
- **Run specific test:** `mvn test -Dtest=TestForms`

### Java 25 EA Commands

- **JDK 25 EA Path:** `/home/hickelpickle/.jdks/openjdk-ea-25+36-3489`
- **Compile with Java 25 EA:** `JAVA_HOME=/home/hickelpickle/.jdks/openjdk-ea-25+36-3489 mvn compile`
- **Test with Java 25 EA:** `JAVA_HOME=/home/hickelpickle/.jdks/openjdk-ea-25+36-3489 mvn test`
- **Test with preview features:** `JAVA_HOME=/home/hickelpickle/.jdks/openjdk-ea-25+36-3489 mvn test -Dmaven.compiler.args="--enable-preview"`

## Project Architecture

### Core Components

**Compiler** (`lyra-compiler/src/main/java/io/mindspice/lyra/compiler/`)
- Lexes, matches grammar, parses immutable syntax, discovers modules, resolves semantics, checks types, lowers validated typed IR, and emits Java 25 bytecode.
- Produces deterministic class/JAR artifacts with source maps, typed generated facades, and structured diagnostics.

**Runtime** (`lyra-runtime/src/main/java/io/mindspice/lyra/runtime/`)
- Loads and verifies generated artifacts, enforces owner-thread/lifecycle/closure boundaries, provides typed export handles, standard I/O, and launcher support.

**REPL** (`lyra-repl/src/main/java/io/mindspice/lyra/repl/`)
- Provides owner-confined sessions, staged metadata publication, immutable request/results, bounded snapshots/contracts, plain-console behavior, and credential-free loopback protocol v2 adapters.
- Session compilation links prior scalar, data-aggregate and compiler-certified callable bindings to their original initialized storage through exact typed accessors and a separate authenticated domain. Structural tuple/function-interface types are shared, but state/closures/cells remain generation-local and are retained by producer authority. Imported module persistence and application-root attachment work through exact typed links to real initialized instances; no source replay is used.
- `ReplActivation`/`ReplLauncher` own the run/host activation composition: a gated bootstrap listener before root publication, registration on the shared owner controller, controller-handshake waits, and shutdown that retires queued control requests before closing the listener, service, owned root and generations.

**CLI** (`lyra-cli/src/main/java/io/mindspice/lyra/cli/`)
- Provides the `run`/`compile` commands, dependency-free plain fallback, JLine editing, and credential-free loopback `attach` transport.

**Editor** (`lyra-editor/src/main/java/io/mindspice/lyra/editor/`)
- Provides the optional JavaFX workspace, compiler-backed syntax/import/type checks, definition tracking, persistent REPL client, and JDI debugger. JavaFX/RichTextFX dependencies stay in this module.
- Editor-owned execution lives in a disposable Java 25 child process. File opening and analysis never execute source. Reuse the existing loopback REPL protocol and preserve owner confinement; no live runtime values cross into JavaFX.
- Run saves buffers and starts a fresh session; explicit REPL evaluations retain initialized state. Configured editor functions do not change the standalone executable `main` contract. See `.internal-dev/specifications/editor.md` and `docs/editor.md`.
- Run graphical integration checks with `mvn -pl lyra-editor -am verify -Dlyra.editor.uiTests=true` on a graphical display. The Phase 24 release gate still applies to the backend and now preserves the editor target tree too.

**Error Handling**
- Compiler phase boundaries and session APIs use immutable structured diagnostics; expected unsupported/live-linkage behavior is represented by stable compiler/session codes rather than unchecked placeholders.

### Language Features

**Accessor Operators:**
- `:.` - Field access (with implicit self for methods)
- `::` - Function access/calls (required for all function calls)
- `->` - Namespace access

**Syntax Patterns:**
- S-expressions: `(func arg1 arg2)`
- Lambda expressions: `(=> |params| body)` or `(=> : RetType |params| body)`
- Let statements: `let var : Type = expr`
- Block expressions: `{ stmt1 stmt2 expr }`
- Conditionals: `(predicate -> then_expr : else_expr)`

## Development Notes

- Java 25 is the supported compiler/runtime/class-file profile; preview features are permitted and must be declared in generated artifact metadata when required
- Preserve immutable structured diagnostic/result propagation at compiler phase boundaries; avoid introducing unchecked errors for expected source errors
- Grammar matching happens before AST construction (two-phase approach)
- Semantic resolution, typing, flow certification and typed IR validation are implemented after parsing; keep those phase boundaries explicit
- Keep JSON/debug tooling out of the execution ABI and stable typed IR contract
- Keep host integration explicit and testable. Do not couple the language core directly to Vulkan or a particular engine subsystem

## Git Commit Policy

- Every completed repository change, implementation phase, or coherent unit of work must end with a Git commit before proceeding to the next unit or reporting completion. This includes code, tests, documentation, and development records; do not wait for the user to request a commit.
- Run the required validation, review the diff, and include the relevant specification, knowledge, and changelog updates in the commit. Use a descriptive commit message that accurately states the work and its validation status.
- When unfinished work is checkpointed, commit it with an explicit WIP/checkpoint message and record the remaining scope or failing checks. A checkpoint is not a claim of completion.
- Commit the files belonging to the current unit of work. Preserve unrelated changes unless the user explicitly requests committing them too. Do not commit secrets or generated build output, and do not push unless requested.

## Testing

The tests cover compiler semantics/IR/JVM artifacts, runtime lifecycle, CLI launchers, REPL sessions/consoles, JLine PTY behavior, and remote protocol/security. Add focused JUnit assertions for every new language or compiler behavior, including malformed input and failure paths.

The language conformance corpus, primitive/operator/numeric/ABI matrices, compiler/runtime fuzz campaigns, and persistent-session state model are mandatory core tests selected by ordinary `mvn test`. See `docs/language-testing.md` for the coverage matrix, source fixture format, replay/reduction workflow, worker budgets, and extended campaigns (`tools/fuzz-language.sh`).

Every new or changed syntax form, primitive, operator, built-in, typed IR/JVM operation, runtime authority boundary, or persistent-session behavior must update its positive, negative, boundary and applicable runtime-failure assertions, fuzz generator and independent oracle/model, and developer coverage documentation in the same change. Preserve discovered counterexamples as permanent regression tests. Keep primitive/operator/built-in/IR inventory guards current; never remove assertions, suppress compiler invariants, accept unsupported-emission errors for valid source, or disable default fuzz execution to make a feature pass. Expected values must not be calculated by production compiler/runtime helpers.

Run the bounded fuzz baseline with every core suite and an extended campaign when changing language/runtime behavior. Save failing replay files/transcripts and seeds before cleaning target output. Fuzz subprocess resource limits protect the test run; they do not turn the production runtime or unauthenticated development REPL into a hostile-code sandbox. The Phase 24 release audit remains mandatory for release.

At minimum, run `mvn test` after changes. Before claiming an executable, persistent session, or embedding feature, add an integration test that starts from Lyra source and proves the host JVM can load/invoke the resulting artifact or session behavior.
