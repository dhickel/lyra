# Lyra

Lyra is an experimental standalone functional JVM scripting language. The current product builds Lyra source into deterministic Java 25 class files, class directories, thin JARs, or bundled runnable JARs. It also provides a dependency-free runtime, typed generated Java facades, exact runtime export handles, a small `run`/`compile` CLI, and an optional REPL foundation.

## Requirements

- Java 25
- Maven 3.9 or newer for building

## Build

```sh
mvn clean verify
```

The reactor contains four classpath modules:

- `lyra-runtime`: runtime values, metadata, lifecycle, I/O, loading, and launcher support;
- `lyra-compiler`: source resolution, lexer/parser, semantic analysis, validated typed IR, direct Class-File API emission, and artifact assembly;
- `lyra-repl`: owner-confined session contracts, bounded credential-free loopback v2 transport, and plain-console session behavior;
- `lyra-cli`: command parsing, compilation/execution commands, REPL/attachment adapters, and launch scripts.

## CLI

After packaging, the CLI can compile or run a source file:

```sh
java -jar lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar compile program.lyra
java -jar lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar run program.lyra -- arg1 arg2
```

A runnable bundled artifact requires a public `main :Fn<Array<String>;I32>` export. Use `--help` and `--version` for the exact command surface. Thin artifacts require a compatible `lyra-runtime` on the class path; bundled artifacts include the launcher and runtime. Java 25 preview support is explicit in artifact metadata and launch options.

The interactive and attachment surfaces are:

```sh
java -jar lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar repl [DIR] [--source-root DIR]...
java -jar lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar attach HOST:PORT
java -jar lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar run ROOT --repl [--repl-port PORT] [--repl-wait] [-- ARGS...]
java -jar lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar compile ROOT --repl --format bundled-jar --output app.jar
```

`run --repl` starts an unauthenticated loopback-only listener before `main` and gates live work until the root initializes and registers. `compile --repl` records the same capability without ever listening; compiled artifacts activate only through `-Dlyra.repl.enabled=true` (plus optional `-Dlyra.repl.port` and `-Dlyra.repl.wait`). The full REPL workflow, security warning, and limits are documented in [docs/repl.md](docs/repl.md) with runnable sources under [examples/repl](examples/repl).

## Java API

`io.mindspice.lyra.compiler.api.LyraCompiler` compiles immutable source requests to validated `CompiledArtifact` values. `io.mindspice.lyra.runtime.LyraRuntime` loads artifacts, and exact typed `ExportHandle` method handles are available from an instantiated `ModuleHandle`. Generated facades expose typed methods, getters, permitted setters, function-value getters, metadata, and lifecycle operations. The optional `io.mindspice.lyra.repl` module currently exposes owner-confined session contracts and credential-free loopback v2 transport boundaries.

## REPL status

The plain/JLine consoles, non-executing `:type`, credential-free loopback protocol v2, persistent imported modules, explicit `:reload`, actual application attachment, and deterministic debug-capable packaging are implemented and tested end to end.

Sessions retain exact typed scalar, aggregate and compiler-certified callable storage across submissions. For example, three inputs `let @mut count :I32 = 1`, `count := 2`, and `count` return `I32` value `2` without rerunning earlier source. Arrays and tuples containing scalars or further data aggregates persist through exact typed storage and shared structural JVM classes; rebinding selects a new value without changing earlier aliases, and completed data mutations survive a later failure or cancellation without publishing failed declarations.

Imported modules initialize exactly once per pinned source revision, execute real `std->io` through the session, and stay live across later submissions, diamonds, aliases and re-exports. Explicit `:reload MODULE` rebuilds only the REPL-owned reachable closure from fresh source and publishes new defaults atomically, while old captured values, selective imports and compiled references keep their original producers. Local startup is an empty scratch workspace: `repl [DIR]` and `--source-root` configure module discovery only and never run `main`.

`run ROOT --repl` and explicit `-Dlyra.repl.enabled=true` attach a genuinely running REPL-capable application over an unauthenticated loopback-only listener (ephemeral port by default). Public `@mut` root exports are live bindings, owner-thread safe points service at most one request, cancellation targets only its evaluation, and root-held values/type domains survive reset, disconnect and service reopening until root close. Debug-capable classes/thin/bundled artifacts embed original sources, the canonical resolution topology and reproducible options, and exclude CLI/JLine/tests and credential material.

This is a trusted development interface, not a sandbox: any process able to reach an enabled listener can execute with the application's authority, and no token, credential file or authentication exists. Authentication/hostile-client hardening and automatic local-root initialization are explicitly deferred; details, warnings, migration from the removed v1 transport, and operational limits are documented in [docs/repl.md](docs/repl.md).

## Scope

The implementation preserves strict source order, typed primitive JVM descriptors, deterministic metadata/debug maps, source-mapped failures, owner-thread lifecycle checks, live trusted-Java array escape behavior, and the pinned `std->io` intrinsic. User classes/member types, pattern matching, loops/ranges, generics/macros, dynamic values, bitwise operators, Lyra-to-Java/engine interop, sandboxing, automatic local-root initialization, REPL authentication/hostile-client hardening, serialized IR, additional backends, and optimization levels remain explicitly deferred.

## Validation

The normal release check is `mvn clean verify`. The Phase 23 performance/allocation evidence gate is explicit and separate from ordinary tests:

```sh
./tools/phase23-evidence.sh
```

It records JMH JSON/CSV/text evidence, structural Class-File API checks, class-count/Metaspace observations, and the owner-ratified gate result under `target/`. The final Phase 24 audit command is:

```sh
./tools/phase24-release-audit.sh
```

Both commands are deterministic for a fixed Java/build environment. The current validation environment is Linux, so native Windows launcher execution is unavailable and is recorded as N/A by the Phase 24 audit, never as a false PASS. The audit also restores every pre-existing reactor `target/` tree outside its fresh `target/phase24-audit/` output.
