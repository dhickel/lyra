# Lyra

Lyra is an experimental standalone functional JVM scripting language. The current product builds Lyra source into deterministic Java 25 class files, class directories, thin JARs, or bundled runnable JARs. It also provides a dependency-free runtime, typed generated Java facades, exact runtime export handles, and a small `run`/`compile` CLI.

## Requirements

- Java 25
- Maven 3.9 or newer for building

## Build

```sh
mvn clean verify
```

The reactor contains exactly three classpath modules:

- `lyra-runtime`: runtime values, metadata, lifecycle, I/O, loading, and launcher support;
- `lyra-compiler`: source resolution, lexer/parser, semantic analysis, validated typed IR, direct Class-File API emission, and artifact assembly;
- `lyra-cli`: command parsing, compilation/execution commands, and launch scripts.

## CLI

After packaging, the CLI can compile or run a source file:

```sh
java -jar lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar compile program.lyra
java -jar lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar run program.lyra -- arg1 arg2
```

A runnable bundled artifact requires a public `main :Fn<Array<String>;I32>` export. Use `--help` and `--version` for the exact command surface. Thin artifacts require a compatible `lyra-runtime` on the class path; bundled artifacts include the launcher and runtime. Java 25 preview support is explicit in artifact metadata and launch options.

## Java API

`io.mindspice.lyra.compiler.api.LyraCompiler` compiles immutable source requests to validated `CompiledArtifact` values. `io.mindspice.lyra.runtime.LyraRuntime` loads artifacts, and exact typed `ExportHandle` method handles are available from an instantiated `ModuleHandle`. Generated facades expose typed methods, getters, permitted setters, function-value getters, metadata, and lifecycle operations.

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
