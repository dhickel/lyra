# AGENTS.md

This file provides repository-specific guidance for coding agents working on Lyra.

## Project Mission

Lyra is an experimental standalone functional JVM scripting language. Its first product boundary is a complete source-to-Java-25-bytecode compiler, standalone CLI/runtime, reusable class/JAR output, and direct typed use from Java. Later, Lyra is intended to embed in a Java game engine, including planned Vulkan integration, so current boundaries must preserve explicit module instances, lifecycle, threading, and future interop without making the engine the first implementation target.

The repository contains a verified compiler/runtime/CLI backend foundation plus an optional REPL module. Plain/JLine consoles, owner-confined lifecycle, non-executing `:type`, credential-free loopback protocol, exact cross-submission scalar/data-aggregate storage, compiler-certified source-local callable persistence, shared structural JVM types, executed bounded snapshots and generated session cancellation boundaries are tested. Data aggregates may recursively contain scalars, arrays, tuples and certified callable values; imported-module persistence, reload, application-root attachment, asynchronous owner execution, cooperative application safe points, deterministic debug packaging and the run/compile host activation surface (`run --repl`, `--repl-port`, `--repl-wait`, `lyra.repl.enabled/port/wait`) are implemented and tested. The REPL attachment is a trusted explicitly enabled localhost development interface, not an authenticated or hostile-code boundary. Local REPL startup is empty and source-root options configure discovery only; explicit application-root registration is separate.

## Current Baseline and Direction

The planning baseline for this REPL completion is commit `ae793e7d6776f6611fa78ad80ea357ac827ed131`; the current worktree contains substantial uncommitted backend work that must be preserved. The verified implemented pipeline is:

```text
source -> lex -> parse -> module discovery -> resolve -> type-check -> typed IR -> Java 25 bytecode -> artifact/runtime/CLI
```

The current product foundation includes `lyra-runtime`, `lyra-compiler`, `lyra-repl`, and `lyra-cli`, including direct bytecode emission, class/JAR output, loading, lifecycle, typed facades, `run`/`compile`, owner-confined session execution, plain/JLine consoles, and credential-free loopback protocol v2 transport. Scalar, source-local data-aggregate and compiler-certified source-local callable live-storage linkage, shared structural type/loading domains and executed result snapshots are implemented. Imported-module persistence, reload, configured-root/application attachment, cooperative application safe points, deterministic debug packaging and run/compile host activation are proven by code and tests; the next slice is cross-surface conformance and repeated lifetime evidence.

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

## Testing

The tests cover compiler semantics/IR/JVM artifacts, runtime lifecycle, CLI launchers, REPL sessions/consoles, JLine PTY behavior, and remote protocol/security. Add focused JUnit assertions for every new language or compiler behavior, including malformed input and failure paths.

At minimum, run `mvn test` after changes. Before claiming an executable, persistent session, or embedding feature, add an integration test that starts from Lyra source and proves the host JVM can load/invoke the resulting artifact or session behavior.
