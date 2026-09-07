# Phase05 Attachable Profile and Root Registration

## Date

2026-09-07

## Git Commit

`1f18cffa339c7456bba7dc70967f426604dd5d1d` (dirty-worktree baseline; no commit created).

## Change Summary

Completed Phase 05 of the trusted REPL plan: opt-in REPL capability on the ordinary compiler request, explicit normal/session/attachable emission modes, versioned attachable profile metadata, actual public-root registration, and the attachable safe-point effect boundary in canonical semantic flow. The prior implementer's partial edits were reviewed and completed rather than replaced; the semantic/flow boundary (plan step 3) was missing entirely and was implemented from scratch.

- `CompileProfile`/`EmissionMode` carry explicit NORMAL/SESSION/ATTACHABLE modes through planning, emission and packaging. Normal AOT artifacts emit no hooks, dependencies or profile extensions and remain byte-identical in shape; schema-1 metadata and include-sources behavior are preserved.
- Attachable artifacts declare exact hook/dependency requirements, a source-independent attachment context (root/graph/source-inventory/options revisions), the canonical logical/import topology with exact spans, and reproducible semantic options. No AST, typed IR, semantic graph or resolver objects are serialized.
- Attachable roots load with a per-artifact structural class base and per-instance `RootLifetime` extensions. `LyraRuntime.registerRoot` binds exact typed getters/setters/function-value getters and Class identities from one OPEN root, validates hook shapes, owners and lifetimes, and installs the narrow owner-controller surface. Hooks are inert without a registered service; service close leaves the root open and reopening reuses the retained structural domain; root close invalidates; independent roots stay independent.
- Attachable semantic flow now models dispatch safe points as explicit effect boundaries: reads of public `@mut` root bindings carry conservative `AttachableBoundary` aggregate identities, so element mutation through them (direct, captured, or via called functions) is an ordinary `LYC-RESOLVE-022` diagnostic while scalar writes, whole-binding replacement, private-state mutation, callable replacement and higher-order transfers remain allowed. Normal compilation is unchanged. A directly closed application controller is also treated as an absent service by the lifecycle hook, preserving inert-hook behavior.
- Added 14 attachable profile/registration/boundary tests beyond the supplied 790-test baseline, covering normal no-hook emission, malformed/missing profile contexts, exact descriptors/metadata/source/import/options, live registration for scalars, mutable functions, arrays, tuples and nested callables, private/immutable/missing/re-export rules, duplicate registration, two roots, service reset/close/reopen, root invalidation, inert hooks, wrong-thread rejection and byte-identical repeat attachable builds.

## Files

- `.internal-dev/specifications/decisions.md`
- `.internal-dev/specifications/repl.md`
- `.internal-dev/knowledge/attachable-semantic-boundary.md`
- `.internal-dev/changelogs/2026-09-07-phase05-attachable-profile-root-registration.md`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/CompileRequest.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/CompileProfile.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/LyraCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/ArtifactAssembly.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/EmissionMode.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedClassPlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedMemberKind.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedMemberPlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedTypePlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedTypePlanner.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeArtifact.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/ArrayIdentity.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/ValueFormula.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/ReplProfileEmissionTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/RootTypeRegistrationTest.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactMetadata.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactMetadataReader.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactRevision.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ExportMetadata.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraClosure.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraOwnershipToken.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntime.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ModuleLifecycle.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionTypeLoader.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactDependency.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactHook.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactImport.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ArtifactProfile.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/AttachmentContext.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/RootTypeRegistration.java`

## Behavioral Impact

Ordinary compilation, normal artifact metadata/revisions and all existing session/AOT behavior are unchanged. Attachable compilation now rejects element mutation through public `@mut` root aggregate bindings with `LYC-RESOLVE-022` (conservative safe-point ownership) while preserving all other mutable and higher-order behavior. Attachable artifacts carry profile extension metadata and roots can be explicitly registered with exact typed accessors; registration surfaces are inert without an active service, including after direct controller closure.

## Specification Impact

`repl.md` now states the attachable safe-point effect boundary; `decisions.md` records the durable boundary decision and its alternatives. No ordinary language or backend contract changed.

## Risks

The per-binding conservative boundary is intentionally coarser than Phase 07's per-call safe-point granularity; revisit only if finer resets are needed. Attachable export declaration identities are compiler-local ordinals; reconstruction (Phase 11) must reproduce identity allocation deterministically from embedded sources and options.

## Follow-up Items

Phase 06 live public-root workspace binding, Phase 07 generated dispatch activation, Phase 11 debug packaging/reconstruction, and Phase 24 audit coverage rows for these tests.
