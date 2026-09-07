# Phase04 Reload Review Repairs

## Date

2026-09-07

## Git Commit

`7245d1402c14b9094d5187b80cbc2f0a04656684` (dirty-worktree baseline; no commit created).

## Change Summary

Completed the pending standalone reload implementation and repaired the identity, historical-source/producer coverage, dependency-edge, effect-event, cancellation/progress, source-capacity and external-callable planning defects found during direct review. Added 13 assertion tests beyond the supplied 767-test baseline and strengthened existing application-boundary and generation-identity assertions.

Preserved unrelated modified/deleted/untracked content, including std-io knowledge edits, legacy deletions, existing untracked records and external directories. The previous Phase04 validation changelog remains untouched.

## Files

- `.internal-dev/changelogs/2026-09-07-phase04-reload-review-repairs.md`
- `.internal-dev/knowledge/repl-module-linkage.md`
- `.internal-dev/reviews/2026-09-07-phase04-reload-review.md`
- `.internal-dev/specifications/backend-runtime.md`
- `.internal-dev/specifications/repl.md`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/LyraCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionCompileRequest.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedTypePlanner.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrImportBinding.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrSessionExecution.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/InitializationAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedSemanticGraph.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedTopologyValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowFactValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticResolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticGraph.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticProvenance.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/SessionExecutionPlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/SessionImport.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/SessionModuleEnvironment.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/SessionSnapshot.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source/ModuleGraphDiscovery.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source/SourceSnapshot.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/SessionPinnedModuleCompilerTest.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/EvaluationResult.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/InitializerProgress.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/LyraSession.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/SourceRegistry.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/LyraSessionTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ModuleReloadTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/SessionGenerationLifecycleTest.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraArtifactKey.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntime.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/ModuleLifecycle.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionInitializationProgress.java`

`SessionWorkspace.java` retains its committed revision-conflict guard, and `ModuleGraph.java` has no net change after removal of the earlier unused API.

## Behavioral Impact

Reload stages a fresh REPL-owned closure under owner admission, preserves old selective/captured/aggregate/callable identities, and publishes defaults only after successful initialization. Historical producer/source records stay complete and distinct. Failure/cancellation reports actual progress, keeps completed effects and old values, and hides staged names. Reset keeps revision/allocator monotonicity while retiring owned generations and their source context. Ordinary AOT remains free of newly added session cancellation/progress calls.

## Specification Impact

Implementation-status updates only in repl.md and backend-runtime.md. The repair implements the existing accepted Phase04/D06/D10 contracts; it adds no source-language behavior or later attachment/deployment scope.

## Risks

Conservative generation retention and cooperative cancellation retain their documented limits. Application-root lifecycle/attachment gates remain later work and are not claimed by this pass. Root review verdict and evidence: `../reviews/2026-09-07-phase04-reload-review.md`.

## Follow-up Items

No known remaining blocker in the requested Phase04 standalone path. Continue later accepted phases independently. Validation: 51 focused tests; full mvn test and clean verify PASS; clean verify 780 tests, zero failures/errors/skips; git diff --check PASS. Full fresh evidence is retained outside restored build-output trees at `/tmp/lyra-phase04-review.2LE1eD/`.
