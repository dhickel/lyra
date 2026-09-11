# Implement the complete Lyra language, JVM backend, runtime, CLI, and Java APIs

## Feature

Implement every non-deferred requirement in `.internal-dev/specifications/language-core.md` and `.internal-dev/specifications/backend-runtime.md`, progressing from a corrected two-phase lexer/parser through immutable syntax and semantic artifacts, a closed typed IR, direct Java 25 bytecode, deterministic artifacts, runtime loading, generated facades, standalone CLI execution, and release evidence.

## Required Behavior

- Implement the complete normative lexical, syntactic, static-semantic, evaluation, module, diagnostic, and runtime-failure behavior in `.internal-dev/specifications/language-core.md`; no normative construct may remain a placeholder or unsupported path.
- Implement the complete compiler/backend/runtime, CLI, generated-artifact, Java-consumption, metadata, lifecycle, source-map, standard-I/O, and performance-evidence behavior in `.internal-dev/specifications/backend-runtime.md`.
- Keep every capability listed in `.internal-dev/specifications/deferred-features.md` out of the implementation, grammar, public API, and completion claims.
- Retain and repair the two-phase grammar-match then parser-replay architecture for future LSP use, with explicit consumed-range invariants preventing matcher/parser divergence.
- Publish deeply immutable outputs at every compiler phase: source snapshot, tokens, syntax AST, semantic graph, typed IR, emitted classes, and packaged artifact.
- Use a separate immutable syntax AST, separate resolved/typed semantic graph, and separate closed immutable typed IR; remove mutable type/resolution metadata from syntax nodes.
- Assign deterministic compilation-local scope/declaration/reference/lambda/capture IDs once and preserve them through resolution, typing, IR, diagnostics, and lowering; separately implement stable module/export/artifact identities.
- Implement whole-reachable-graph source resolution, import visibility/re-export rules, function-signature SCC linkage, conservative eager-initialization dependency analysis, and deterministic initialization order.
- Implement bidirectional type checking with expected types, exact literal preservation, local inference, complete lambda contracts, nil narrowing, invariant composite types, legal numeric widening/conversion, modifier legality, and exhaustive access/call checking.
- Emit direct Java 25 bytecode only through the Java Class-File API, with specialized primitive descriptors and no production interpreter, source-to-Java path, ASM path, or generic `Object...` hot path.
- Implement the specified JVM ABI for signed/unsigned primitives, floats, Bool, Char, String, Unit, nilable values, arrays, tuples, functions, captures, mutable cells, closures, and recursive initialization.
- Generate deterministic instantiable module facades with exact typed methods, getters, allowed setters, function-value getters, factories, metadata, thread checks, lifecycle checks, and authenticated Lyra function-value boundaries.
- Implement class-directory, thin-JAR, and bundled-JAR output with canonical metadata, source maps, normalized ZIP/JAR data, atomic publication, compatibility preflight, and byte-for-byte repeatability.
- Implement public Java compile/load/instantiate/export APIs using immutable records/sealed results and builders only for complex request/options objects.
- Implement the exact standalone command surface, launch scripts, `main :Fn<Array<String>;I32>` behavior, argument forwarding, exit mapping, close precedence, diagnostics, and runnable-JAR behavior.
- Implement the pinned intrinsic `std->io` module and exact UTF-8/default and configurable-stream behavior without introducing arbitrary Lyra-to-Java interop.
- Provide stable compiler diagnostic codes and all required `LYR-*` runtime categories, authoritative UTF-16 spans, rendered diagnostics, debug maps, Lyra source frames, and the specified JVM-error translation boundaries.
- Prove primitive descriptors and zero avoidable steady-state boxing/allocation structurally and empirically; gather JMH evidence and require owner ratification of numerical release thresholds before final performance acceptance.
- Replace print-only and vacuous tests with assertion-grade positive, negative, boundary, malformed-input, integration, subprocess, determinism, compatibility, lifecycle, and benchmark evidence.
- Do not claim full language/backend completion until every normative requirement and validation obligation passes; intermediate slices and the bounded bytecode spike are explicitly non-production evidence only.

## Targets

- `pom.xml`: Replace the single prototype artifact with a Java 25 Maven parent/aggregator for exactly three modules: `lyra-runtime`, `lyra-compiler`, and `lyra-cli`; centralize versions, preview policy, Surefire/Failsafe/JMH profiles, reproducible build settings, and dependency management.
- `lyra-runtime/pom.xml`: Create `io.mindspice:lyra-runtime:1.0-SNAPSHOT` as the minimal classpath runtime/API artifact with no JPMS descriptor and no unnecessary compiler/debug dependencies.
- `lyra-compiler/pom.xml`: Create `io.mindspice:lyra-compiler:1.0-SNAPSHOT`, depending on the runtime contract and owning source resolution, frontend, semantics, IR, Class-File emission, packaging, and compiler-side tests/benchmarks.
- `lyra-cli/pom.xml`: Create `io.mindspice:lyra-cli:1.0-SNAPSHOT`, depending on compiler/runtime and owning the command parser, launcher composition, CLI integration tests, and distribution scripts.
- `src/main/java/{parse,lang,util} and src/test/java/TestForms.java`: Move and replace prototype sources/tests under the normative `io.mindspice.lyra.compiler.*` namespace; remove broken mutable/provisional models and print-only validation rather than preserving prototype API compatibility.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source`: Add immutable source snapshots, stable/physical source identities, UTF-8/BOM validation, source resolvers, whole-graph discovery, canonical revisions, and UTF-16 span/line indexing.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/lex`: Implement the complete token/trivia/span/literal model and lexer, including spacing-sensitive annotations, scoped commas, nested comments, escapes, exact numeric lexemes/suffixes, malformed-input diagnostics, and EOF behavior.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/grammar`: Repair the separate grammar-matching phase so it recognizes only normative syntax, emits immutable replay descriptors, and records/checks expected token consumption without attempting semantic decisions.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/parse`: Repair parser replay to consume the grammar descriptors and token stream deterministically into a complete immutable syntax AST while preserving all accessor, call, modifier, annotation, import, literal, and aggregate distinctions.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ast`: Define the finalized deeply immutable syntax AST and exhaustive visitors with complete spans and no symbol-resolution or JVM concerns.
- `lyra-compiler/src/main/resources/grammar_spec.md`: Replace the stale prototype grammar with synchronized human-readable EBNF derived from `language-core.md`, clearly subordinate to the living specification.
- `src/resources/grammar_spec.md and obsolete prototype resources`: Remove duplicate/stale grammar evidence and obsolete Match/Iter/user-type/operator/modifier descriptions so deferred syntax is not accidentally accepted or documented as current.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic`: Add module/declaration collection, scope trees, symbol/reference/export resolution, capture analysis, mutation authorization, initialization dependency analysis, bidirectional type checking, immutable semantic nodes/side artifacts, and exhaustive invariants.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/types`: Implement the complete immutable Lyra type/signature system, qualifiers, canonical spelling, exact numeric literals, widening/common-type rules, and JVM-independent semantic type operations.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir`: Add the closed immutable typed IR, IDs, conversions, narrowing, explicit evaluation order/control flow, access/call distinctions, checks/failure sites, captures/cells, aggregates, module initialization, and validator.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm`: Add Java 25 Class-File API emission, ABI mapping, deterministic naming, generated tuples/interfaces/closures/cells/facades/module state, self-tail-call lowering, debug attributes, and class verification support.
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime`: Implement runtime values, failures/source frames, lifecycle/thread ownership, module handles, exact export handles, class loading/compatibility, runtime options, UTF-8 I/O environment, launcher, metadata types/codecs, and authenticated Lyra closures.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact`: Implement in-memory compiled artifacts, project-canonical JSON/debug-map generation, deterministic class/JAR packaging, thin/bundled modes, source inclusion, staged atomic publication, and artifact revisions.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api`: Implement `LyraCompiler`, immutable compile requests/results, diagnostics, source resolver contracts, compiled artifacts, write options, and associated builders/records in the public package.
- `lyra-cli/src/main/java/io/mindspice/lyra/cli`: Implement the fixed internal command parser and exact `run`, `compile`, `--help`, and `--version` workflows without adding a general CLI framework.
- `lyra-cli/src/main/scripts`: Add Unix and Windows `lyra` launchers for the CLI JAR with deterministic argument forwarding and documented Java 25/preview behavior.
- `lyra-{compiler,runtime,cli}/src/test`: Add focused unit, integration, subprocess, Java-consumer compilation, class verification, reproducibility, lifecycle, compatibility, CLI, and focused golden tests inside the three selected modules.

## Constraints

- Treat `.internal-dev/specifications/language-core.md` and `.internal-dev/specifications/backend-runtime.md` as the implementation contract; code may not silently revise their semantics or narrow their scope.
- Preserve the selected two-phase grammar/replay architecture. Strengthen it with immutable descriptors and consumption invariants, but do not replace it with recursive descent-only parsing.
- Do not implement an LSP in this plan; retain parser phase boundaries that can support one later.
- Use exactly three Maven child modules—runtime, compiler, and CLI—with classpath artifacts only and no `module-info.java`; test and JMH source sets/profiles remain within those modules.
- Migrate immediately to `io.mindspice.lyra.*`; no compatibility adapters for prototype `parse`, `lang`, or `util` packages are required.
- Use Maven/compiler version `1.0-SNAPSHOT`, language contract version `1`, runtime ABI `1.0`, artifact schema version `1`, and debug-map schema version `1`; derive the pinned `std->io` revision from its canonical contract.
- Use project-defined canonical JSON: fixed schema field order, deterministic sorting for unordered content, UTF-8, minimal escaping, and no insignificant whitespace; do not define determinism as incidental Jackson behavior.
- Use records/sealed result types for immutable public values and diagnostics; use builders only where complex compile/load/runtime options justify them.
- Use a small internal CLI parser and ship Unix/Windows launcher scripts; do not add Picocli or another CLI framework.
- Use structural assertions by default and reviewed goldens only for canonical metadata, rendered diagnostics, debug maps, and selected class disassembly.
- Expected source/configuration/resolution failures remain structured result data. Implementation bugs, environmental I/O, Java API misuse, runtime faults, and JVM integrity failures follow the exact boundaries in `backend-runtime.md`.
- Preserve strict eager left-to-right language semantics even when a different lowering would be easier on the JVM.
- No AST/semantic/IR placeholder, TODO path, hard-coded fake, skipped visitor branch, print-only test, or claimed future follow-up may substitute for a normative feature.
- No hostile-code sandbox, engine/Vulkan integration, Lyra-to-Java calls, arbitrary callbacks, reload/state migration, serialized IR, incremental cache, extra backend, optimizer levels, broader standard library, or other deferred feature enters scope.
- The bounded early bytecode spike is test-only and must not become a second pipeline or public incomplete execution mode.
- Preserve unrelated worktree state, including the pre-existing `CLAUDE.md` deletion and existing internal-development records.
- Numerical performance thresholds cannot be invented in advance; final performance acceptance pauses for owner ratification after reproducible evidence is collected.

## Assumptions

- The living specifications are authoritative over both stale grammar resources and current prototype behavior.
- The unmerged `environment` and `feature/two-pass-resolver` branches may be consulted for ideas only; no branch is merged or ported wholesale.
- The three-module dependency direction is runtime as the minimal base, compiler depending on runtime contracts needed by generated artifacts, and CLI depending on compiler/runtime.
- The runtime owns shared public artifact metadata/runtime API types and a small strict metadata reader; the compiler owns canonical metadata generation and artifact assembly.
- The existing prototype has no external compatibility obligations, so package moves and AST/parser API replacement are allowed.
- The root project remains Java 25; generated artifacts set `previewRequired` only when emitted/bundled classes actually require preview, independent of compiler implementation settings.
- A complete source graph can be held in memory and compiled as one immutable request; persistent/incremental compilation is not needed.
- A test-only bounded Class-File API spike may cover a scalar function and exact descriptor after the initial IR foundations, but broad backend work waits for complete semantic/IR coverage.
- No public evaluator/interpreter is needed. Any semantic oracle used in tests must be bounded, internal, and justified rather than becoming another execution product.
- Owner interaction is available at the benchmark ratification gate to set numeric release thresholds from recorded JMH evidence.

## Settled Decisions

- Repair and retain the current two-phase grammar matcher plus parser replay model because its separable grammar phase is intended to support a later LSP.
- Use three immutable layers: syntax AST, resolved/typed semantic graph, and closed typed IR.
- Create exactly three Maven child modules from the start: `lyra-runtime`, `lyra-compiler`, and `lyra-cli`; keep integration tests and JMH inside those modules.
- Migrate prototype packages immediately to the normative `io.mindspice.lyra.*` namespace.
- Use a bidirectional type checker rather than a general global constraint solver.
- Replace stale grammar documentation with synchronized EBNF while keeping living specifications authoritative.
- Run a bounded test-only Java Class-File API spike after core IR foundations, then return to complete frontend/IR coverage before expanding the production backend.
- Use an evidence-then-owner-ratification performance gate.
- Use `1.0-SNAPSHOT`, language contract `1`, runtime ABI `1.0`, and schema versions `1`.
- Use classpath artifacts without JPMS descriptors.
- Use a project-specific canonical JSON schema/codec rather than RFC 8785 or incidental library ordering.
- Use immutable records/sealed results plus builders for complex request/options APIs.
- Use a small internal CLI parser and Unix/Windows launchers.
- Use structural assertions plus narrowly reviewed golden fixtures.

## Implementation Approach

1. 1. Establish the three-module Maven foundation and contract constants. Convert the root POM to a parent/aggregator, create runtime/compiler/CLI POMs, centralize Java 25 and test/plugin policy, set selected versions, define dependency direction, migrate packages/sources immediately, and prove a clean reactor `mvn verify` before feature work.
2. 2. Define the shared immutable source and diagnostic foundation. Add stable `SourceId`/`ModuleId`, physical keys, UTF-16 `SourceSpan`, line indexing, source snapshots, phase/severity/related-span diagnostics, stable `LYC-*` code registries, immutable phase-result patterns, and tests for malformed UTF-8, BOMs, Unicode offsets, rendering, and no partial artifacts.
3. 3. Replace the token model and implement the complete lexer. Preserve exact lexemes/values and trivia needed for annotation-spacing and comma legality; implement identifiers, punctuation, nested comments, modifiers, bool/nil/Unit, strings/chars/escapes, exact integer/decimal/exponent/suffix forms, operator spellings, EOF, and fail-fast structured lexical diagnostics. Add exhaustive positive/negative and numeric/Unicode boundary tests.
4. 4. Rebuild the grammar-matching phase without abandoning replay. Remove deferred/stale productions, represent every normative statement/expression/type/import/accessor/list form, retain immutable minimal replay descriptors, record expected start/end token positions, and add invariants proving each production's matcher consumption. Replace the grammar resource with synchronized EBNF and conformance examples.
5. 5. Finalize the immutable syntax AST and repair parser replay. Parse every normative construct, preserve all source spelling distinctions required downstream, defensively copy all collections, remove mutable metadata/Symbol state and placeholder Match/Iter/Array/Tuple nodes, and verify parser consumption exactly equals the grammar phase range. Replace `TestForms` with structural parser tests and malformed-delimiter/replay-divergence diagnostics.
6. 6. Implement source resolvers and whole-graph module discovery. Support path and logical roots, all-root/all-resolver ambiguity checks, source-root-relative stable identity, symlink physical deduplication, one-time source snapshots, intrinsic `std->io` reservation, import-header parsing, reachable graph construction, canonical source/module revisions, and deterministic traversal tests.
7. 7. Implement complete immutable type/signature and identity models. Cover every primitive/composite/qualified/nilable type, canonical signatures, exact numeric literals, widening lattice/common-type operations, scope/declaration/reference/export/lambda/capture IDs, export hashing, deterministic Java naming, and collision tests independent of AST/JVM state.
8. 8. Implement declaration collection, scopes, and name/module resolution. Enforce header imports, aliases, selective imports/re-exports, visibility, one namespace, source-ordered private replacement, public uniqueness, ordinary no-forward-read rules, typed-lambda signature predeclaration, recursive function SCCs, capture selection, mutation roots, and complete related-span diagnostics. Treat remote resolver branches only as test ideas.
9. 9. Build the bidirectional semantic checker and initial semantic graph/IR foundation for literals, declarations, lambdas, direct/callable calls, blocks, and core scalar operations. Flow expected types inward, synthesize outward, record explicit legal conversions/narrowing, preserve strict order, freeze outputs, and add exhaustive unresolved/untyped/skipped-node invariants.
10. 10. Run the bounded Class-File API spike. Emit one deterministic test-only class from the initial final-form scalar/function IR, verify exact primitive descriptors and line metadata with the Java 25 API and `-Xverify:all`, load/invoke it in an isolated test, inspect selected bytecode, document discovered constraints, and then keep the spike private while completing semantics.
11. 11. Complete semantic analysis for every remaining normative language construct. Add all modifiers and nil rules, conditionals/predicate bindings/coalescing, truthiness, every operator/arity/numeric failure rule, strings/chars/conversion, arrays/tuples/indexing/mutation/aliases/equality/identity/length, access chains, assignment, captures/shared cells, recursive signatures, public explicit contracts, and conservative eager-initialization dependency/cycle analysis.
12. 12. Complete and seal the typed IR. Represent every source operation, conversion, narrowing, short-circuit/control-flow edge, left-to-right sequence, direct/callable/access/module call, runtime check/failure site, aggregate operation, capture/cell, recursive initialization, export, module state, and initialization schedule. Add exhaustive visitors and a validator rejecting unresolved IDs/types, absent spans, implicit conversions, placeholders, or unsupported nodes before emission.
13. 13. Implement runtime foundations and ABI contracts. Add `LyraUnit`, runtime ABI/profile constants, immutable metadata/signature/module/export types, all structured runtime exceptions/source frames, runtime options and authenticated closure ownership, thread/lifecycle primitives, source-frame rendering support, and strict canonical metadata reading without introducing host interop.
14. 14. Implement the production JVM ABI mapper and deterministic generated-type planner. Map every Lyra type/qualifier/nilability position to exact JVM descriptors, plan wrapper boundaries, arrays, Unit, tuple value classes, typed functional interfaces, closure/cell classes, module/facade/state classes, deterministic name mangling/collision resolution, export methods, and class-generation order. Test descriptor/signature parity before emitting bodies.
15. 15. Implement direct bytecode emission for scalar/control semantics. Emit constants, locals, declarations/rebinding, conversions, checked signed/unsigned arithmetic, trapping floats, comparisons/truthiness, strict sequencing, blocks, branching, coalescing, direct and callable calls, runtime failures, source line attributes, and direct self-tail-call loops through the Class-File API.
16. 16. Complete aggregate, function, and module bytecode. Emit strings/chars, deterministic scalar text conversion, primitive/reference/nilable arrays, bounds/mutation/identity/structural equality, immutable tuple classes/fields/equality, closures and dynamic identity, immutable captures/shared specialized cells, recursive closure linkage, ordinary recursion/stack translation, imports/module access, eager state shells, and deterministic initialization schedules.
17. 17. Implement generated facades and module lifecycle. Emit typed invocation methods, value/function getters, permitted mutable setters, factories, metadata access, exact Java names, authenticated Lyra function-argument checks, owner-thread checks, OPEN/FAILED/CLOSED behavior, idempotent owner close, invalidated closures/handles, dependency-instance isolation, and initialization-failure/effect semantics.
18. 18. Implement metadata, source maps, and deterministic artifact assembly. Define schema-1 canonical JSON field order/escaping/sorting, debug BCI mappings/synthetic frames, source hashes/optional sources, language/runtime/JVM/preview compatibility data, normalized class-directory and JAR output, canonical MANIFEST.MF, sorted stored entries/epoch timestamps/no extras, thin and bundled modes, collision/duplicate validation, and staged fsync/atomic replacement with `--force` behavior.
19. 19. Finalize the public Java compiler/runtime APIs. Freeze and compile-test records, sealed results, builders, `SourceResolver`, `LyraCompiler`, `CompileRequest`, `CompileResult`, `CompiledArtifact`, `ArtifactMetadata`, `LyraRuntime`, `LoadedArtifact`, `ModuleHandle`, `ExportHandle`, signatures/options, exact bound MethodHandles, parent-first shared-runtime loaders, profile preflight, definition-time `LYR-LINK`/`LYR-VERIFY`, lifecycle ordering, and path/in-memory loading.
20. 20. Implement intrinsic `std->io`. Pin its stable ID and contract-derived revision; participate in graph/type/manifest resolution; lower directly to runtime calls; implement exact UTF-8 print/newline/flush serialization, configurable immutable stream environment, blocking line input, CRLF stripping, EOF, malformed input, interruption preservation, failure mapping, and non-ownership of process streams.
21. 21. Implement standalone launch and CLI delivery. Add the exact command grammar/defaults/help/version, logical/path root handling, argument separation, in-memory `run`, compile formats/output refusal/atomic writes, exact `main` checking, invocation/close suppression precedence, exit-code behavior, thin/bundled launch requirements, `LyraLauncher`, and Unix/Windows scripts.
22. 22. Execute complete conformance and integration sealing. Add table-driven positive/negative source suites for every language rule, module graph/cycle/order suites, full source-to-IR and source-to-bytecode coverage, `-Xverify:all`, classes/thin/bundled execution, generated Java facade compilation, runtime compile/load/exact-handle invocation, compatibility corruption/profile tests, lifecycle/thread tests, raw-array escape tests, deterministic clean-build byte comparisons, and focused reviewed goldens.
23. 23. Establish and run the performance/allocation evidence gate. Add JMH within the compiler/runtime test profile against equivalent Java for all specified workloads; record JDK/flags/GC/platform/forks/warmup/iterations/confidence; inspect descriptors/instructions; use GC profiler/JFR allocation evidence; prove structural no-boxing paths; present results for owner threshold ratification; then encode and run the accepted numeric gates.
24. 24. Perform final scope and release review. Run the full Maven reactor and subprocess matrix from clean state, audit every sealed visitor and specification validation bullet, search for placeholders/deferred syntax/public API creep, inspect deterministic artifacts and dependency boundaries, update authoritative documentation/changelog records, and claim completion only when every non-deferred contract and the ratified performance gate passes.

## Validation Criteria

- Root `mvn clean verify` succeeds for the three-module Java 25 reactor, with unit/integration/JMH profiles configured inside those modules and no prototype source set still compiled.
- Every accepted and rejected lexical spelling in `language-core.md` has assertion-grade coverage, including comments, scoped commas, spacing-sensitive type annotations, exact literal ranges, suffixes, Unicode escapes, malformed input, and complete UTF-16 spans.
- For every grammar production, tests prove grammar matcher and parser replay consume the same token range; malformed input returns a structured syntax diagnostic rather than indexing exceptions or implementation exceptions.
- The syntax AST, semantic graph, and typed IR are deeply immutable, structurally separate, exhaustively visitable, span-complete, and contain no placeholder/deferred nodes or mutable resolution/type fields.
- Module tests prove physical deduplication, stable root-relative/URI identities, duplicate/missing resolution diagnostics, import aliases/selections/re-exports, visibility, private replacement, public uniqueness, deterministic revisions, legal function cycles, and rejected eager cycles.
- Resolution tests prove source-order visibility, typed-lambda predeclaration, self/forward/mutual recursion boundaries, capture identity, shared `@mut` cells, mutation authorization, and related-span diagnostics.
- Type tests prove every type/qualifier/nilability position, exact contextual literal typing, the complete widening lattice, explicit checked conversions, branch unification, nil narrowing/coalescing, complete lambda contracts, exact arity, invariant composites, and accessor/member legality.
- Semantic tests prove strict left-to-right effects, all short-circuit paths, truthiness, predicate binding, then-only Unit, every operator arity and checked/trapping edge, function/array identity, structural equality, aliases, bounds, UTF-16 indexing/length, and deterministic scalar string conversion.
- The typed-IR validator rejects any unresolved identity/type, missing span, unrecorded conversion/narrowing, skipped source construct, illegal evaluation ordering, placeholder, or incomplete module schedule before bytecode emission.
- The bounded spike and production emitter use the Java 25 Class-File API, emit exact primitive descriptors, pass `java -Xverify:all`, and do not expose an interpreter/source-Java/ASM/MethodHandle-graph execution mode.
- ABI tests cover signed/unsigned raw bits and operations, all nilable primitive/reference/Unit positions, wrapper boundaries, primitive/reference arrays, tuple descriptors/classes, function interfaces, closures/cells, and canonical Lyra signatures independent of JVM descriptors.
- Generated facade consumer fixtures compile as ordinary Java and prove exact typed calls/getters/setters/function getters, deterministic naming/collisions, same-module authenticated function values, rejected Java callbacks, live-array escape behavior, and metadata access.
- Runtime tests prove one dependency instance per root instance, independent instances, eager order/effects/failure, wrong-thread-before-state checks, OPEN/FAILED/CLOSED behavior, owner-thread idempotent close, post-close failures, invalidated closures/handles, and loader close ordering.
- Failure tests cover every required `LYR-*` category, `LYC-*` diagnostics, Lyra frame/span order, synthetic-frame hiding, `StackOverflowError` translation only at the specified boundary, custom loader link/verify translation, and unchanged escape of excluded JVM integrity failures.
- Repeated clean compilation of identical snapshots/options under the same compiler build produces byte-identical classes/directories/thin JARs/bundled JARs, canonical JSON/debug maps/MANIFEST.MF, stable names/revisions, normalized ZIP entries, and no absolute paths/timestamps.
- Artifact publication tests prove refusal without force, sibling staging, atomic replacement, preservation of previous output on failure, source-inclusion hash consistency, malformed/duplicate metadata rejection, ABI/profile/preview preflight, and direct-classpath JVM mismatch behavior.
- Class directories, thin JARs with external runtime, and bundled JARs all verify/load/execute; bundled output obeys `java -jar` versus `--enable-preview`, and thin output shares exactly one parent runtime.
- CLI subprocess tests prove exact help/version text, options/defaults, root path/logical resolution, `--` forwarding, exact-main diagnostics, compile formats, output paths, source/runtime/usage exit classes, returned-main exit values, and invocation/close failure precedence.
- Public Java API tests compile external consumer fixtures for ahead-of-time generated facades and runtime compile/load/instantiate/exact export handles, with no repeated name search or `Object...` conversion on invocation.
- `std->io` tests prove pinned identity/revision, non-shadowability, serialized contiguous UTF-8 output, exact `\n`, flushes, configurable streams/charset, line/CRLF behavior, EOF, malformed input, interruption preservation, `LYR-IO`, and non-closing process streams.
- Focused goldens for canonical metadata, diagnostics, debug maps, and selected disassembly are reviewed and stable; all other behavior is asserted structurally rather than accepted through broad snapshots or printed output.
- Class-file inspection plus JMH GC-profiler/JFR evidence proves no avoidable boxing/allocation in primitive steady-state paths without relying solely on escape analysis.
- JMH evidence records the required environment/methodology and compares all specified cold/warm/call/arithmetic/recursion/closure/aggregate/failure/loading/allocation/class-count/metaspace workloads against equivalent Java.
- The owner ratifies numerical performance thresholds from the evidence, those gates are encoded and pass before final backend completion is reported.
- A final requirement matrix maps every validation bullet in both living specifications to passing tests/evidence and confirms every deferred feature remains absent from current grammar/product/API claims.

## Out of Scope

- User-declared classes, records, variants, fields, construction, inheritance, or user-defined member types.
- Dedicated Match or Iter syntax, loops/ranges/iterator protocols, and pattern matching/exhaustiveness.
- User-defined generics, macros, quoting/hygiene, and compile-time metaprogramming.
- Bitwise and shift operators.
- Lyra-level throw/try/catch/finally and multi-error compiler recovery.
- Dynamic/Any values, dynamic member lookup, default/named/omitted/vararg arguments, currying, and partial application.
- Source edition directives or simultaneous multi-edition support.
- Lyra source calling arbitrary Java APIs, overload/coercion discovery, fields/constructors, callbacks, and host-object ownership.
- Game-engine or Vulkan bindings, affinity, scheduling, cancellation, capabilities, and native-resource lifecycle.
- Hostile-code sandboxing, process isolation, quotas, fuel, forced termination, and malicious-bytecode defenses.
- Transparent reload, state migration, latest-version handles, and callback rebinding.
- Serialized/public IR, incremental/persistent caches, cross-target translation, engine adapters, public backend SPIs, multiple production backends, optimization levels, adaptive tiering, and generalized profiling.
- Standard-library domains beyond intrinsic core behavior and `std->io`.
- An LSP implementation; only the retained two-phase grammar/replay boundary is preserved for later use.
- Prototype API/source compatibility for the current `parse`, `lang`, and `util` packages.

## Planning Record

- Interactive rounds completed: 5
- User questions answered: 15
- Remaining consequential open questions: none
