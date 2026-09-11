# Lyra JVM Backend and Standalone Runtime Specification

## Status

Living normative contract for Lyra's first complete compiler backend, standalone tools, generated JVM artifacts, and Java consumption API. No backend, runtime, CLI, or embedding behavior described here is implemented until code and tests prove it.

## Purpose

Define how `language-core.md` becomes deterministic Java 25 class files and JARs, how standalone programs run, and how Java consumes compiled Lyra modules. Lyra is a standalone language first. Its boundaries must preserve later engine embedding without making Java interop, Vulkan integration, reload, sandboxing, or engine lifecycle first-release requirements.

## Intended Contract

### Product boundary

The completed backend implements the entire normative scope of `language-core.md`. Implementation may proceed through smaller vertical slices, but unsupported normative features block completion. Features in `deferred-features.md` remain out of scope.

Required workflows are:

1. compile and run Lyra source from the CLI;
2. compile source to deterministic Java 25 class directories or JARs;
3. execute a compiled root module through a runnable JAR;
4. place a thin compiled Lyra JAR and `lyra-runtime` on a Java class path, import its generated module facade, instantiate it, and call typed exports directly;
5. compile and load Lyra source from Java through a public compiler API.

Lyra-to-Java calls from Lyra source, arbitrary Java class/member access, callbacks, engine bindings, and Vulkan operations are not first-release behavior.

### Java profile and production execution

- The initial compiler, runtime, and generated class-file target are Java 25.
- The emitter is the Java 25 Class-File API. ASM, source-to-Java, MethodHandle graphs, Truffle, and a second emitter are not parallel production backends.
- Preview Java features are permitted in compiler/runtime implementation when materially useful. Artifact metadata records whether `--enable-preview` is required. Generated code is not marked preview merely because preview is allowed.
- Generated programs execute direct bytecode. An evaluator may exist only as an internal test oracle, never as a public mode or second production runtime.
- One predictable compilation mode always emits source/debug metadata and straightforward typed bytecode. There are no public optimization levels initially; the JVM JIT performs general optimization.

### Complete compiler phases

The pipeline is:

```text
source snapshot
  -> lex
  -> parse
  -> module/declaration collection
  -> resolve
  -> type-check
  -> closed typed IR
  -> JVM emission
  -> package
```

Each phase consumes a complete immutable output and either publishes a complete immutable artifact or structured diagnostics. Expected source errors never publish partial loadable artifacts and never escape as implementation exceptions. Mutable builders are allowed only within a phase and freeze before publication.

One compilation graph owns scope, declaration, reference, and capture identities. IDs are allocated once and reused through resolution, typing, lowering, and diagnostics. Exhaustive sealed visitors and post-phase invariants prove that no supported source node is skipped.

### Identity

Two identity layers are required:

- compilation-local deterministic IDs for scopes, declarations, references, lambdas, and captures; these remain stable throughout one artifact build but are not a cross-build API;
- stable external IDs for logical modules, public exports, runtime ABI, and generated Java names.

The default file resolver distinguishes a compile-local physical key from an emitted stable identity. The physical key is the real normalized path used to deduplicate symlinks. The stable `SourceId`/`ModuleId` is the source-root-relative POSIX path (or a resolver-supplied stable URI), never an absolute checkout path. A resolver must return both keys when they differ. Import aliases are not identities.

A module revision is `SHA-256` over a domain tag, length-prefixed UTF-8 source bytes, language-contract version, and canonical semantics-affecting options. An artifact revision additionally covers the compiler build, sorted module revisions, Java package mapping, target, and packaging options. Canonical encoding is versioned and length-prefixed rather than delimiter-ambiguous. An export ID consists of module ID, export name, and canonical complete Lyra signature. Canonical signatures use the no-whitespace source type spelling, qualifiers ordered `@mut` then `@nil`, and separately record export binding mutability; they never infer distinctions from a JVM descriptor. Lines and source offsets are never identities.

### Typed IR

A separate closed immutable typed IR isolates semantics from parser records and JVM details. Every value and operation carries an exact Lyra type and source span. The IR explicitly represents:

- resolved symbol, export, and module IDs;
- declared and inferred types;
- legal inserted conversions and nil narrowing;
- strict left-to-right evaluation and short-circuit control flow, including subject-once value matching and ordered conditional-match arms;
- direct calls, callable-value calls, namespace/value/direct-call access distinctions, and module accesses;
- immutable captures, specialized shared cells for captured `@mut` bindings, and recursive closure initialization;
- checked integer operations, non-finite float checks, bounds checks, invalid conversions, and all runtime-failure sites;
- arrays, tuples, equality, identity, module state, and eager initialization order.

The IR is internal. It is not a serialized public format, backend plug-in API, generalized effect system, or dynamic execution protocol.

### Module resolution and compilation unit

- Source is UTF-8; an initial UTF-8 BOM is ignored and malformed input is a source diagnostic. The default extension is `.lyra`.
- `game->math->vector` maps to `game/math/vector.lyra` beneath a configured source root.
- A Java `SourceResolver` may supply the same logical module from memory with physical and stable URIs.
- All resolvers and source roots are queried for a logical import. Exactly one source may satisfy it; missing or duplicate matches are diagnostics, never precedence decisions.
- A request snapshots each source's bytes once when first discovered and compiles the complete reachable graph. No persistent/incremental cache is required initially.

The compiler builds both an import graph and an eager-initialization dependency graph. Function signatures are linked for every import strongly connected component before values initialize. A top-level initializer creates an initialization edge for every cross-module value read or call it may transitively execute. The initialization graph must be acyclic. Imported SCCs with no cyclic initialization dependency initialize in topological dependency order, then stable `ModuleId` order when unconstrained; declarations within a module remain source ordered. This conservative analysis rejects uncertain cyclic effects rather than exposing partially initialized values.

Under the same compiler build, identical snapshots, mappings, and options produce byte-identical output. IDs/classes are emitted in sorted stable-ID/source order. JAR entries are lexicographically ordered, UTF-8 named, stored without compression, timestamped at the ZIP epoch, and contain no comments or platform extras. Lyra metadata documents use canonical UTF-8 JSON. Standard `MANIFEST.MF` uses UTF-8, CRLF, JAR-spec 72-byte wrapping, `Manifest-Version` first, remaining attributes lexicographically ordered, and one trailing blank line. Class directories and JARs are written to a sibling staging path, fsynced where supported, and atomically replaced; failure leaves the previous output untouched. Absolute paths and wall-clock values never enter emitted output.

### JVM value ABI

Known paths use primitives and concrete references. Universal `Object`, tagged universal values, and `Object...` invocation are not the normal ABI.

| Lyra | JVM |
|---|---|
| `I8`, `I16`, `I32`, `I64` | `byte`, `short`, `int`, `long` |
| `U8`, `U16`, `U32`, `U64` | same-width primitive raw bits with unsigned Lyra operations |
| `F32`, `F64` | `float`, `double` |
| `Bool` | `boolean` |
| `Char` | `char` |
| `String` | `java.lang.String` |
| `Unit` | `void` only for language-function returns; `io.mindspice.lyra.runtime.LyraUnit` elsewhere |
| `Array<T>` | exact primitive/reference JVM array under the element mapping below |
| `Tuple<...>` | deterministic generated immutable final value class |
| `Fn<...;...>` | deterministic generated typed functional interface and closure class |

The manifest uses a canonical Lyra signature encoding because JVM descriptors cannot distinguish signed/unsigned, qualifiers, or every nilable contract. Exact dynamic lookup validates both that signature and the JVM `MethodType`.

Unsigned Java-facing values use raw same-width primitives; metadata identifies unsigned positions. Non-nilable primitives stay unboxed. Internal nilable primitive locals, captures, and fields use a presence bit plus payload where this does not escape. A Java-facing `@nil` primitive uses its nullable Java wrapper; nilable references use `null`. Tuple fields and function parameters use the same rule. `Fn<...;Unit>` returns JVM `void`; a Unit parameter, exported Unit value/getter, tuple field, capture, or array element uses the non-null `LyraUnit.INSTANCE`. `@nil Unit` is nullable `LyraUnit`.

JVM arrays preserve fixed length and identity. Non-nil primitive arrays are primitive arrays; reference and Unit arrays are reference arrays; arrays of nilable primitives use wrapper arrays whenever they cross a Java-visible boundary. Internal specialized nilable arrays may not cross that boundary without preserving the same live identity, so the first backend uses wrapper arrays for any potentially exported/passed array of nilable primitives.

Java receives live arrays, not defensive copies. It can therefore mutate a returned array regardless of the Lyra binding's source-level `@mut` permission and can retain it after module close. This is an explicit trusted-Java ABI escape, not authority granted to another Lyra module. Facade and closure calls remain owner-thread checked, but raw array operations cannot be checked. Future untrusted/engine boundaries must wrap or copy arrays in their own specification. Other boxing is limited to unavoidable nilable, generic, reflection, or tooling boundaries.

### Functions and closures

- Every function value is identity-bearing under `eq?`. Each dynamic lambda evaluation creates a distinct identity. A closure may be cached only when language evaluation creates that value once, such as one top-level initializer; the compiler never coalesces separately evaluated lambdas.
- Every `@pub` `Fn` binding has a typed invocation method and a typed function-value getter. Invocation loads the current binding, so an `@pub @mut` function observes later Java setter replacement.
- Public function-valued signatures receive deterministic generated `@FunctionalInterface` types with exact descriptors.
- Capturing lambdas become deterministic final closure classes with typed capture fields. Immutable captures store their selected value/reference; captured `@mut` bindings share one specialized cell.
- Every exported/returned closure retains its module instance. Invocation checks OPEN state and owner thread; closing the module invalidates later closure invocation.
- Direct self-tail calls, including calls in match-arm result positions, lower to loops and use constant JVM stack. Mutual/non-tail recursion uses JVM calls. `StackOverflowError` crossing a generated Lyra invocation boundary is the sole `VirtualMachineError` translated to `LYR-STACK`; all other `VirtualMachineError` instances escape unchanged.
- MethodHandles support exact dynamic export lookup, not whole-program control flow.

### Generated classes and Java facade

Each module receives a deterministic facade class. The default base package is `lyra.generated`; a CLI/API option may replace it. ASCII alphanumerics/underscore are preserved. Java keywords gain a `lyra$` prefix, illegal JVM-name characters are encoded as `$uXXXX`, and any remaining collision receives `$` plus the first eight hexadecimal digits of its export ID. Metadata records every exact mapping.

Infrastructure names begin `$lyra$`, which source identifiers cannot contain. `close` and inherited `Object` method names are reserved for infrastructure; a colliding function invocation uses `invoke$<sourceName>`. The factories are `$lyra$create()` and `$lyra$create(RuntimeOptions)`, where the no-argument form uses immutable default runtime options and both bind ownership to the calling thread. For an export `name`, the normal Java members are:

- function invocation: `name(...)` unless reserved/colliding;
- value getter: `get$name()`;
- function-value getter: `value$name()`;
- setter for `@pub @mut`: `set$name(value)`;
- factory/metadata: `$lyra$create(...)` and `$lyra$metadata()`.

A facade is instantiable, owns one module-state instance, implements `AutoCloseable`, and exposes only those typed members. Representation fields are private. Public Java fields and Java overload resolution are not part of the ABI. The Java mapping is deterministic but the manifest, not inference from spelling, is authoritative.

### Module instance, initialization, and threading

One root instance owns exactly one instance of every reachable dependency. Construction on the creating/owning thread allocates shells for the complete import SCC, links all typed function slots, executes the proven acyclic initialization schedule above, and publishes only after success.

Top-level initializers may perform effects through the standard library. If initialization fails, the internal graph enters terminal `FAILED`, no facade is returned, and completed external effects are not rolled back. `FAILED` is observable through the thrown `LYR-INIT` failure, not a usable facade.

A module instance is confined to its creation thread. Export methods/getters/setters, function values/closures, and close check the owner. Wrong-thread access fails before state access with `LYR-THREAD`. Independent instances may run on different threads. Lyra creates no threads, serializes no calls, and performs no hidden hop.

Lifecycle is `INITIALIZING -> OPEN -> CLOSED`, with terminal `FAILED` during construction. `close()` first performs the owner-thread check, then is idempotent on that owner thread, invalidates facade-bound handles/closures, and releases runtime references. Post-close access fails with `LYR-CLOSED`. Process streams and raw Java values already returned to a caller are not module-owned.

Reload means compile/load a new instance. Existing instances and values are not retargeted; no state migration, latest-version handle, or transparent hot reload is promised.

### Failures and source maps

Generated code returns values normally and throws structured unchecked `LyraRuntimeException` subtypes for runtime failures. Exact typed Java methods do not return result wrappers. Any Java value supplied for a Lyra `Fn` parameter or setter must be a runtime-authenticated Lyra closure from the same OPEN loaded artifact; arbitrary Java SAM implementations are rejected with `LYR-LINK`. General Java-to-Lyra callbacks remain deferred.

A failure contains a stable code/category, summary, ordered Lyra module/function/source frames with spans, and an original Java cause where appropriate. Required runtime categories are `LYR-ARITH`, `LYR-BOUNDS`, `LYR-CONVERT`, `LYR-STACK`, `LYR-IO`, `LYR-INIT`, `LYR-THREAD`, `LYR-CLOSED`, `LYR-LIFECYCLE`, `LYR-LINK`, `LYR-VERIFY`, `LYR-COMPAT`, and `LYR-INTERNAL`. Compiler codes use stable `LYC-<PHASE>-<NUMBER>` identifiers. Expected source/configuration/resolution diagnostics are result data; Java API misuse uses `IllegalArgumentException`, environmental artifact I/O uses `IOException`, and violated compiler invariants use unchecked `LyraCompilerBugException` with no artifact.

Source is decoded UTF-8, while authoritative spans are zero-based, end-exclusive UTF-16 code-unit offsets in the decoded text. Rendered line/column values are one-based and count UTF-16 code units. Generated classes include JVM source/line attributes; `META-INF/lyra/debug-map.json` maps class/method/BCI ranges to source IDs/spans and marks synthetic frames. Synthetic frames are hidden by default and point to the nearest originating span. Missing source text never invalidates a frame. Optional sources live under `META-INF/lyra/sources/` and their hashes must match metadata.

The CLI renders stable code, summary, source label, line/column, excerpt when source is available, then Lyra frames. Direct Java callers receive the unchecked exception. `StackOverflowError` has the explicit conversion above. During custom `LyraRuntime.load`, the controlled definition boundary translates `VerifyError` to `LYR-VERIFY` and other class-definition/resolution `LinkageError` to `LYR-LINK`, before any module initializes. Direct class-path loading may surface native JVM linkage errors first. Every other `VirtualMachineError`, `ThreadDeath`, and linkage/integrity failure outside that boundary escapes host control unchanged.

### Standalone entry point and CLI

An executable root declares exactly:

```lyra
let @pub main :Fn<Array<String>;I32> = (=> |args| ...)
```

The launcher maps `String[]` directly to the fixed-size Lyra argument array and passes a normally returned `I32` unchanged to `System.exit`; the operating system may narrow it platform-specifically. Zero conventionally means success. A runnable build without the exact signature is a package diagnostic; library builds need no `main`.

Normative commands are:

```text
lyra run ROOT [--source-root DIR]* [-- ARGS...]
lyra compile ROOT [--source-root DIR]* [--output PATH]
  [--format classes|thin-jar|bundled-jar]
  [--java-package PACKAGE] [--include-sources] [--force]
lyra --help
lyra --version
```

`ROOT` is an existing `.lyra` path or a logical chain resolved through source roots. A path root implicitly adds its parent as the first root only when no explicit root is supplied. `run` compiles/loads in memory, always closes in `finally`, and forwards only tokens after `--`. `compile` defaults to `bundled-jar`, base package `lyra.generated`, and `build/lyra/<root-name>.jar`. Existing output is refused unless `--force`; writes remain staged/atomic.

Help/version and successful library compilation exit 0. Source/package/runtime diagnostics exit 1; invalid CLI usage, configuration, or compiler infrastructure failure exits 2. A successfully invoked `main` controls the `run` exit request exactly; stderr diagnostics distinguish a returned 1/2 from tool failure. If invocation and close both fail, invocation remains primary and close is reported as suppressed; a sole close failure exits 1.

Bundled JARs contain `META-INF/MANIFEST.MF` with `Main-Class: io.mindspice.lyra.runtime.LyraLauncher`, artifact metadata, generated classes, launcher, and runtime, and run as `java -jar app.jar` when preview is not required. Preview-requiring output must be started as `java --enable-preview -jar app.jar`; a JAR cannot enable preview itself. Thin JARs contain generated classes/metadata and run/use via an explicit class path with compatible `lyra-runtime`; they do not promise standalone `java -jar`. Both modes use identical module bytecode.

### Public Java API

Maven artifacts are `io.mindspice:lyra-compiler`, `io.mindspice:lyra-runtime`, and `io.mindspice:lyra-cli`. Public packages begin `io.mindspice.lyra.compiler`, `.runtime`, and `.cli`.

The minimum Java API shape is:

```java
CompileResult LyraCompiler.compile(CompileRequest request);

sealed interface CompileResult {
  record Success(CompiledArtifact artifact, List<Diagnostic> diagnostics) implements CompileResult {}
  record Failure(List<Diagnostic> diagnostics) implements CompileResult {}
}

interface CompiledArtifact {
  ArtifactMetadata metadata();
  void writeClasses(Path output, WriteOptions options) throws IOException;
  void writeJar(Path output, JarMode mode, WriteOptions options) throws IOException;
}

interface LoadedArtifact extends AutoCloseable {
  ModuleHandle instantiate(ModuleId rootModule);
  void close(); // unchecked LyraLifecycleException if instances remain open
}

interface ModuleHandle extends AutoCloseable {
  ExportHandle export(String name, LyraSignature signature);
  void close(); // owner-thread checked and idempotent
}

LoadedArtifact LyraRuntime.load(CompiledArtifact artifact, LoadOptions options);
LoadedArtifact LyraRuntime.load(Path classesOrJar, LoadOptions options) throws IOException;
```

`CompileRequest` is immutable and contains root source/module, resolvers/source roots, Java base package, target/preview and semantic options. Expected source/configuration/resolution failures return `Failure`; no artifact exists. `CompiledArtifact` contains no live instance.

For ahead-of-time use, Java imports the generated facade, calls `$lyra$create`, then typed methods/getters/setters. For runtime compilation, `LoadedArtifact` owns one parent-first child loader that shares JDK and exactly one parent `lyra-runtime`; bundled duplicate runtime classes are never defined in the child. An instance must close before its `LoadedArtifact`; closing a context with OPEN instances fails `LYR-LIFECYCLE` without partially closing it. After all instances close, context close invalidates export handles, drops caches, and closes the loader. Unloading is not guaranteed.

`ExportHandle` contains the canonical Lyra signature, JVM `MethodType`, metadata, and a handle already bound to one module instance. Lookup validates name plus complete signature once. Repeated invocation performs no lookup, overload search, or `Object...` conversion. An optional adapter may implement the generated functional interface without changing conversions.

Custom loading preflights metadata before defining generated classes and reports `LYR-COMPAT` for profile mismatches. Direct class-path use can be rejected by the JVM first with `UnsupportedClassVersionError` or preview errors; generated facade initialization can check runtime ABI but cannot replace JVM class-file checks.

### Artifact metadata and compatibility

Every class directory/JAR stores canonical UTF-8 JSON at `META-INF/lyra/artifact.json` and the source map at `META-INF/lyra/debug-map.json`. Duplicate, missing, malformed, unknown-required-field, or hash-inconsistent metadata is `LYR-COMPAT` before class definition. `schemaVersion` controls parsing; unknown optional fields are ignored.

A versioned debug capability declaration (`replCapability`, schema 1) may accompany the NORMAL or ATTACHABLE execution profile without changing the generated ABI. It requires every reachable source snapshot to be embedded under `META-INF/lyra/sources/` with hash-consistent metadata, the canonical import resolution topology and reproducible scalar options to be recorded, and the exact fixed compiler/REPL/runtime dependency closure to be declared. Ordinary schema-1 encodings omit the field entirely and remain byte-identical, including the legacy fixture revisions. Debug bundled JARs use `Main-Class: io.mindspice.lyra.repl.ReplLauncher`; ordinary bundles keep `io.mindspice.lyra.runtime.LyraLauncher`. The runtime loader accepts the declared closure class entries only for debug-capable bundles and never defines them in the generated child loader.

Required artifact fields are language-contract version, compiler version/build, runtime ABI major/minor, Java class-file target, `previewRequired`, artifact/root module ID/revision, sorted module IDs/revisions/source labels, canonical export IDs/signatures/JVM descriptors, logical-to-Java name map, debug-map version/hash, and packaging mode. `previewRequired` is true when any class that will be loaded from that artifact—including a bundled launcher/runtime class—uses the preview class-file minor version or depends on a preview runtime API. Preview use by the separate compiler process alone does not set it. Thin metadata also records the required external runtime's own preview/profile requirement, exact Maven coordinates, and minimum compatible ABI. Standard JAR `META-INF/MANIFEST.MF` is separately canonicalized and contains launcher data only for bundled output.

Metadata is not serialized IR or a security signature. Custom loading preflights target/preview/language/runtime ABI. Compatibility means exact language-contract version and runtime ABI major, with minor compatibility explicitly declared by the runtime; there is no silent adaptation. Classes/JARs are reusable across JVM starts only within that recorded Java/runtime profile.

### Minimal standalone library

The runtime provides intrinsic support for checked numbers, strings, arrays, tuples, closures, explicit primitive-to-string conversion, and source-mapped failures. `std->io` is a compiler-known intrinsic module, not initializer-less Lyra syntax or arbitrary Java interop. Its stable module ID, revision, and interface are pinned to the language/runtime ABI, participate in normal import/type resolution and manifests, and lower to direct runtime calls. Users may import its exports but cannot shadow its module identity with a source resolver.

Its exported signatures are:

| Export | Signature |
|---|---|
| `print` | `Fn<String;Unit>` |
| `println` | `Fn<String;Unit>` |
| `eprint` | `Fn<String;Unit>` |
| `eprintln` | `Fn<String;Unit>` |
| `readLine` | `Fn<;@nil String>` |

The CLI and default Java runtime use UTF-8. Each output call is serialized against its process stream, writes one contiguous UTF-8 sequence, and flushes before return; `println`/`eprintln` append exactly `\n`. Cross-thread call order is lock-acquisition order, not deterministic. `readLine` serializes input, blocks, strips `\n` plus an immediately preceding `\r`, returns `#NIL` only at EOF before any code unit, and reports malformed UTF-8, interruption, or I/O errors as `LYR-IO` while preserving interrupt status. Modules never close process streams. Java `LoadOptions` may replace all three streams/charset as one immutable per-loaded-artifact I/O environment.

Core syntax supplies arithmetic, comparisons, collections, and indexing. `String[value]` is explicit deterministic text conversion for primitive scalars and Unit; nothing implicitly stringifies. `String` and `Array<T>` expose read-only `:.length :I32`, measured in UTF-16 code units and elements. These source-visible completions are also normative in `language-core.md`.

Filesystem, paths, environment, clocks, randomness, process control, networking, concurrency, and engine APIs require later library contracts.

### Trust boundary

The first release runs trusted application code controlled by the owner. It provides no hostile-code sandbox, capability security system, process isolation, forced termination, deterministic fuel, heap quota, or malicious-bytecode defense. Class loaders are lifecycle/organization tools, not a security claim.

Compiler progress, malformed-input handling, and reliable diagnostics remain correctness requirements, not adversarial isolation promises.

### Performance and allocation

Primitive hot paths remain unboxed, direct Java exports are ordinary typed JVM calls, closure/cell allocation occurs only when semantics require it, and dynamic names resolve once.

Match lowers to direct typed control flow, not a runtime matcher or generic dispatch ABI. Value mode stores the subject once; conditional mode emits ordered truth tests without a subject value. Specialized dispatch is permitted only when it preserves first-match behavior, exact typed equality, guard/pattern effects, lazy evaluation, source-mapped failures, and tail positions. No public optimization setting is introduced.

Absolute thresholds come from an evidence spike rather than guesses. Before broad backend expansion, JMH benchmarks compare generated Lyra with equivalent direct Java for typed calls, arithmetic/branches, self-tail recursion, closures, arrays/tuples/strings, failures, construction/loading, cold/warm calls, facade versus exact handles, allocations, class count, and metaspace. Results record JDK build, JVM flags, GC, OS/CPU, forks, warmup, measurement iterations, and confidence intervals. The owner ratifies numerical release gates from those results.

Zero avoidable boxing/allocation in primitive steady-state paths is proven by Class-File API descriptor/instruction inspection plus JMH GC-profiler/JFR allocation evidence. Escape-analysis-dependent elimination alone does not satisfy the structural gate.

## Constraints

- The backend must implement `language-core.md` exactly and must not change source behavior to suit JVM convenience.
- Direct JVM bytecode through the Java 25 Class-File API is the sole production execution path.
- Published phase outputs and artifacts are immutable and complete; expected failures produce structured diagnostics.
- Generated Java APIs preserve primitive descriptors and avoid generic hot paths.
- Output is deterministic and carries enough readable metadata for compatibility and Java use.
- Standalone language behavior is primary. Future embedding shapes boundaries but cannot introduce current engine/Vulkan scope.
- Trust is assumed; no implementation or documentation may claim hostile-code isolation.
- No placeholder IR nodes, unimplemented normative feature paths, or print-only validation satisfy completion.

## Decisions

The owner selected Java 25 with preview permitted, the Class-File API, direct bytecode, specialized primitives, closed immutable typed IR, whole-graph compilation, deterministic reusable class/JAR output, thin and bundled packaging, source maps, a typed `main`, an instantiable thread-confined module facade, unchecked source-mapped runtime failures, direct Java calls, public Java runtime compilation, getter/setter export mapping, direct self-tail-call optimization, one compiler mode, minimal console/core support, and measurement-derived performance gates.

Best-faith technical completions are: two-layer identity, one immutable semantic graph, deterministic generated closure/tuple/function classes, exact MethodHandles only for dynamic export lookup, simple checked artifact metadata, no first-release cache/optimizer/reload/interpreter product, and natural JVM value mappings with boxing only at unavoidable nullable/generic boundaries.

Durable rationale and alternatives are also recorded in `decisions.md`.

## Validation

A conforming implementation proves:

- every normative source feature reaches typed IR/bytecode or its specified diagnostic;
- stable identities and byte-identical repeated clean output;
- module resolution, eager order, legal function cycles, and rejected value cycles;
- class verification under `-Xverify:all` and loading from classes, thin JARs, and bundled JARs;
- exact descriptors and canonical signatures, unsigned/mixed numeric boundaries, Unit/nilable positions, no avoidable primitive boxing, raw-array Java escape behavior, and aggregate semantics;
- captures, shared cells, constant-stack self-tail calls, recursion failures, and source frames;
- CLI run/compile, exact `main`, arguments, exit values, and diagnostics;
- direct Java facade compilation/calls, Lyra-owned function-value validation, and runtime Java compile/load/exact-handle calls;
- instance isolation, owner-thread checks, eager failure/effects, idempotent close, and post-close failure;
- canonical JSON/JAR manifests, atomic publication, reproducible bytes, custom-loader preflight/link/verify translation, and direct-classpath JVM mismatch behavior;
- standard I/O ordering, EOF/failures, text conversion, and string/array length;
- benchmark evidence against direct Java.

Tests assert results, types, descriptors, diagnostics, exceptions, metadata, and lifecycle states. Debug prints or absence of crashes are not evidence.

The core `mvn test` suite includes bounded reproducible compiler/runtime fuzzing and a persistent-session state model, as documented in `docs/language-testing.md`. Runtime/ABI changes must update independent value/state expectations and applicable corrupted-artifact, authentication, owner-thread, lifetime, failure-recovery and source-frame assertions. Saved failing inputs must be replayable and retained as regression tests; extended seeded campaigns supplement the default gate. Fuzz worker process deadlines and memory limits contain test failures and do not alter the production trust boundary or establish hostile-code isolation.

## Deferred Backend and Integration Work

Deferred until separately specified:

- Lyra calling Java or engine capabilities;
- Java overload/coercion discovery, fields/constructors, callbacks, and host-object ownership;
- Vulkan/render affinity, scheduling, cancellation, and native-resource disposal;
- hostile-script isolation, process deployment, quotas, and forced termination;
- transparent reload, state migration, latest-version handles, and callback rebinding;
- serialized IR, cross-JVM-target translation, engine adapters, and incremental caches;
- multiple production backends, public backend SPIs, optimization levels, adaptive tiering, and generalized profiling;
- standard-library domains beyond the minimal boundary.

Later engine embedding should reuse compile artifacts, typed facades, explicit instances, exact dynamic handles, thread ownership, metadata, and closeable loading contexts. It adds a separate interop/authority/lifecycle contract rather than changing these implicitly.

## REPL extension boundary

The optional `repl.md` specification extends this backend with persistent submission compilation, typed session linkage, explicitly enabled trusted localhost application attachment, cooperative safe points, and debug-capable artifact/source-context inventories. It does not alter ordinary whole-graph compilation, AOT artifacts, imported-module mutation ownership, lifecycle checks, or the direct-bytecode production path. Normal artifacts remain uninstrumented and dependency-free with respect to the REPL distribution. REPL authentication, hostile-client isolation and automatic local-root initialization are not current requirements.

The implemented session linkage profile shares only structural tuple/function-interface classes through a session-owned parent loader. It retains generation-local state/cell/closure/facade classes and distinct artifact keys. Tuple component getters cross that loader boundary through explicit session-only public methods; ordinary AOT visibility is unchanged. Structural inventory, definition equality, exact accessor MethodTypes and opaque storage capabilities are checked before source execution. Arrays/tuples containing callables are admitted when their callable elements carry compiler-issued summaries and exact generation authority. Imported and explicitly reloaded REPL-owned producers retain exact module contracts, original dependency edges and initialized storage rather than re-emitting old bodies.

Compiler-issued session flow certificates and callable summaries retain exact callable targets, captures, shared-cell snapshots, writes, allocation provenance and operation-site spans across source-local generations. The trusted REPL extension carries those facts through imported and explicitly registered root generations using exact typed links and conservative mutable-state boundaries. Normal AOT artifact/closure compatibility remains unchanged; optional attachment does not add a security authority model. Producers remain OPEN while values are used, and enclosing session/root lifetime retires them. Application-root links and unsupported escaped-generation cases remain structured failures until their concrete producer/lifetime evidence exists.

Sealed semantic/IR aggregate provenance distinguishes source allocations from initialized session origins. A session array origin identifies an exact external declaration and source contract route, with no invented allocation FlowSiteId; `IrAggregateProvenance.originSite` is empty only for that explicit origin kind. Ordinary local/imported allocation provenance still requires its producer-issued site, and exact canonical producer certification remains mandatory. Compatible distinct external origins are may-alias facts, not distinct-allocation proofs.

## Open Questions

No unresolved decision blocks backend implementation planning. Numerical performance thresholds remain a required measurement-and-ratification gate. Future standard-library domains, Lyra-to-Java interop, and engine APIs require dedicated specifications; they do not block the standalone backend.
