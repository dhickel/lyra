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

## Java API

`io.mindspice.lyra.compiler.api.LyraCompiler` compiles immutable source requests to validated `CompiledArtifact` values. `io.mindspice.lyra.runtime.LyraRuntime` loads artifacts, and exact typed `ExportHandle` method handles are available from an instantiated `ModuleHandle`. Generated facades expose typed methods, getters, permitted setters, function-value getters, metadata, and lifecycle operations. The optional `io.mindspice.lyra.repl` module currently exposes owner-confined session contracts and credential-free loopback v2 transport boundaries.

## REPL status

The plain/JLine consoles, non-executing `:type`, and credential-free loopback protocol v2 are tested. Sessions now retain exact typed scalar storage across submissions, including private bindings and captured scalar cells, and return bounded snapshots of executed final expressions. Generated session form/function/tail-loop boundaries support cooperative API cancellation. For example, three inputs `let @mut count :I32 = 1`, `count := 2`, and `count` return `I32` value `2` without rerunning earlier source.

Arrays and tuples containing scalars or further data aggregates also persist through exact typed storage and shared structural JVM classes. For example, submit `let @mut items :Array<I32> = Array<I32>[1]`, then `let alias = items`, then `items[0] := 42`; reading `alias[0]` returns `I32 42`. Rebinding selects a new value without changing earlier aliases. Completed data mutations survive a later failure or cancellation without publishing failed declarations.

Compiler-certified named callable persistence now works for source-local session generations. Callable summaries retain call targets, captured cells, writes, allocation provenance and source operation sites across submissions, so higher-order calls, returned closures, recursion, callable-bearing arrays/tuples, lexical replacement, failure recovery and cooperative cancellation use the original generated code and storage. Exact signatures, producer certificates and generation authority are checked before a retained callable is admitted. Imported callable/module linkage remains a structured `LYC-SESSION-001` boundary.

A separate runtime bridge permits exact callable values between successfully initialized source-local generations in one authenticated session domain. It preserves original closures/cells and rejects foreign sessions/SAMs and retired generations. This is tested through generated Java export signatures and complements, rather than replaces, compiler-side named persistence.

This is partial REPL implementation. Imported-module persistence, reload, configured roots, coordinated program input, asynchronous owner execution and application/debug attachment remain incomplete. Aggregate and callable linkage currently requires source-local generations in the same authenticated session domain. Unsupported linkage returns diagnostics rather than replaying source or simulating values. Local execution remains confined to the thread that opens the session.

## Scope

The implementation preserves strict source order, typed primitive JVM descriptors, deterministic metadata/debug maps, source-mapped failures, owner-thread lifecycle checks, live trusted-Java array escape behavior, and the pinned `std->io` intrinsic. User classes/member types, pattern matching, loops/ranges, generics/macros, dynamic values, bitwise operators, Lyra-to-Java/engine interop, sandboxing, reload, serialized IR, additional backends, and optimization levels remain explicitly deferred.

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
