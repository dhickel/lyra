# REPL Session API Validation

## Topic

Owner-confined standalone session admission and the evolution from one-shot artifacts to typed data linkage. The older findings below document historical failed attempts, not the current implementation boundary; see `repl-aggregate-linkage.md` and the current `repl.md` status for delivered behavior.

## Source References

- `.internal-dev/specifications/repl.md`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/LyraCompiler.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntime.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/LyraSession.java`
- `.internal-dev/changelogs/2026-09-05-repl-session-api.md`
- `.internal-dev/changelogs/2026-09-05-repl-source-identity-regression.md`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/SessionRepairCompatibilityTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/LyraSessionTest.java`

## Key Takeaways

- The current public compiler emits a complete artifact and the runtime can instantiate it, but it has no session entry point or typed external-binding linker. A REPL layer must not turn that gap into an interpreter or generic value ABI.
- A truthful intermediate session can compile and instantiate only a single-source artifact, stage public artifact metadata, and return a structured unsupported-linkage diagnostic for multi-module submissions.
- Synchronous owner-thread admission is a valid bounded operation model for this slice. A guarded active operation still exposes deterministic busy behavior for reentrant or concurrent admission, while cancellation remains a cross-thread flag that is observed before execution, after execution, and before namespace commit.
- Source-origin diagnostics need explicit remapping because the REPL and compiler currently have separate immutable source-origin types. Root spans can map to the caller URI and UTF-16 origin range without changing imported-module spans.
- Source-identity regressions must distinguish internal compiler IDs from caller origins. `SourceRegistry` allocates synthetic `repl/submission-<evaluationId>.lyra` paths even for the first URI-backed submission, so both committed workspace module keys use `path:`, not `uri:<caller URI>`. Keep separate assertions for retained caller origins and exact URI/UTF-16 diagnostic spans; `compileSession` maps compiler diagnostics before the REPL receives them.
- Focused REPL reactor tests must also select a compiler test, for example `-Dtest='SessionCompilerTest,LyraSessionTest#repeatedUrisCommitDistinctModulesAndMapLaterDiagnosticsExactlyOnce' -Dsurefire.failIfNoSpecifiedTests=false`. The compiler POM explicitly sets `failIfNoTests=true`; selecting only a REPL test stops in the compiler module, even with `-DfailIfNoTests=false`. Do not relax the POM to work around test selection.
- Retained source data should be rejected at a fixed bound rather than silently evicted while source mapping and future closure diagnostics may still need the original record.
- The failed partial scalar-linkage path was removed, not completed. It emitted calls to nonexistent `ModuleLifecycle.sessionBindings()` and supplied neither a storage implementation/authentication domain nor REPL result retrieval. `SessionSnapshot` is metadata, not proof that a live initialized storage location exists. Compiling a declaration without instantiating its artifact missed this linkage failure.
- `TypedIrBuilder` deliberately gives the module body `Unit` type. It is not the final expression's value type: using it as a session result contract tried to convert the `I64` value of `42` to `LyraUnit`. A real result entry point needs explicit semantic and IR support, not reinterpretation of ordinary module initialization.
- The restored boundary rejects references to any retained binding or alias with `LYC-SESSION-001`, while unrelated submissions remain compilable even with unused function/aggregate/nilable metadata. This diagnostic has `Phase.SESSION` even when produced by the resolver. Tests cover reads, assignments, captures, exact origin spans, and unchanged namespace publication after rejection.
- Byte-for-byte ordinary/session emission comparisons must align source identity and Java package explicitly: their default packages are `lyra.generated` and `lyra.generated.session`. Exercise artifact loading/instantiation as well as compiler success. After removing source files, use a clean build to eliminate stale linkage classes from `target/`.
- The exact private counter sequence has a different current failure than the public-counter fixture: `stagedDeclarations` includes private lets, but `stageNamespace` and `SessionWorkspace.stage` retain only public exports. Later private reads/assignments therefore report unresolved names, not `LYC-SESSION-001`. Do not substitute `@pub` and describe that test as private persistence.
- Uncaptured root `@mut I32` currently uses a primitive module-state field; it does not automatically allocate an `IrCell`. Existing generated cells are artifact-local capture storage. Keeping a `LoadedArtifact` open neither exposes that private field through `ModuleHandle` nor authenticates another artifact's access. `ModuleHandle.export` is callable-only.
- External assignment enums, revision counters and unique storage ordinals are metadata constraints, not live authority. A compile-success snapshot can advertise a mutable public binding whose initializer has never run and would fail if instantiated. Tests exercise every advertised assignment authority and require external uses to remain rejected.
- `DeclarationKind.EXTERNAL` and a type-level `SemanticFlowAnalyzer` seed already exist, but no resolver path creates them. That tag does not solve live linkage: resolver mutation checks only local/imported ownership and binding mutability, while `IrDeclaration`/`IrModuleState` carry neither external assignment authority nor `StorageIdentity`. Simply feeding mutable external declarations into those paths would lose advertised permissions and plan fresh ordinary fields.
- Compiler storage ordinals are derived from `DeclarationId`; REPL `SessionWorkspace` allocates a separate public-export storage counter. They must not be treated as a shared runtime identity domain.
- `ResolvedReferenceTopology` is bound to the exact source-authoritative graph; `TypeChecker.typeReference` requires graph membership. `IrValidator` requires exact semantic projections and a Unit module body. A session linker needs coherent new external/result representations across those boundaries, not local emitter exceptions or fabricated source declarations. A negative fixture now verifies that retagging a module body with a scalar result type cannot reach emission.
- A read through a local mutable binding retains `@mut` in its semantic/IR expression type (`@mutI32` for `count`), although its storage payload is primitive `I32`. A new result contract must explicitly drop binding authority while retaining value qualifiers such as `@nil`; `ValueSnapshot` forbids an outer `@mut`. The initial scalar-boundary test incorrectly expected an unqualified reference type and was corrected to assert the actual producer contract, not relax typing.
- Failure-before-execution evidence needs a positive control: a deliberately missing superclass causes `LYR-LINK` during artifact definition with no output, while the intact source artifact prints its initializer marker only on instantiation. This proves the existing artifact-loading boundary, not a nonexistent session linker. A session test likewise distinguishes a rejected submission from the same source prefix's real runtime initializer failure.

## Project Relevance

The scalar/result linker and source-local data-aggregate extension now execute against original storage. Historical failures remain useful regression cases: keep result typing separate from Unit initialization, retain exact external identities, validate actual loading/execution, and never mistake metadata for authority. Callable and application attachment work must preserve these constraints.

## Phase 04 Validation Notes

- Reload closure membership is semantic work even when a module's own source hash is unchanged. If a retained dependency is reloaded, every REPL-owned module traversed in that closure needs a fresh generation/producer identity; comparing only the module's source revision incorrectly reuses a producer whose dependency graph changed.
- Logical/module IDs are not generation IDs. After reload, retained selective imports can still point at an older producer with the same module ID. Their boundary values and callable summaries must be resolved from the exact stored producer contract, and old callable effects must not be reinterpreted as effects of the current-generation topology.
- A standalone reset closes retained owned generations before clearing source history and starts a fresh storage epoch while preserving the monotonic session revision. The source-record view is therefore empty after reset; it is not the producer/source-retention mechanism.
- Reload discovery must retain every committed import root, not only the selected module closure, so unrelated defaults remain reachable. Drain the fresh selected closure before seeding old retained roots; otherwise an old pin can occupy a logical slot that the reload is replacing. Fresh traversal must also stop at application-owned modules rather than treating every dependency of a fresh session module as reload-owned.
- Candidate resolution is cached per discovery operation after a fresh source is selected. This is required for deterministic diamond traversal and prevents one generation from observing multiple resolver answers for the same logical module.

## Open Questions

Cross-generation callable flow outside exact retained import contracts, escaped failed-generation lifetime and initialization guards, and attachment remain unresolved. Data arrays/tuples do not establish those contracts.
