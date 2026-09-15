# Direct bytecode and Java facades

Lyra compiles source through semantic analysis and a closed internal typed representation to Java 25 class files. Generated programs execute direct JVM bytecode. There is no public interpreter mode or second production backend.

The compiler's typed intermediate representation is private implementation data. Java consumers use one of two public boundaries instead:

1. A generated, typed module facade in an ahead-of-time artifact.
2. `LyraRuntime`, `LoadedArtifact`, `ModuleHandle`, and an exact `ExportHandle` after runtime compilation or loading.

Each generated facade is instantiable and owns one module graph. Its methods use concrete JVM types for known Lyra types. Public function exports have typed invocation methods and function-value getters. Public values use getters, and public mutable exports also have setters. The artifact metadata records exact generated-name mappings.

An `ExportHandle` validates an export name and complete canonical Lyra signature, then exposes a bound exact `MethodHandle`. Repeated invocation does not perform name lookup or `Object...` conversion.

Module instances and exported closures are confined to their creating thread. Their lifecycle remains checked at every facade or closure call. Raw Java arrays already returned are a deliberate trusted-Java escape: Java can mutate or retain them without Lyra's binding-level `@mut` checks.

Classes, thin JARs, and bundled JARs contain the same generated module behavior but different deployment contents. See [Build classes and JARs](../how-to/package-artifacts.md) and [Use the Java API](../how-to/use-java-api.md).
