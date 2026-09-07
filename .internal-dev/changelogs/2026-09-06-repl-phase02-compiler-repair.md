# Phase 02 retained-module compiler repair

## Date

2026-09-06

## Git Commit

`34ae9c399e18fea7eb24b5701a31919895f6d24d` (implementation baseline before the Phase 02 commit).

## Change Summary

- Preserve original producer syntax, resolution/type identities, flow sites, captures and solved summaries across pinned reuse. Resolve retained sources before filesystem/custom resolver access and parse new captures once.
- Preserve the distinction between unaliased namespaces and explicit aliases when reconstructing retained imports, including qualified nested-module and intrinsic `std->io` access.
- Allocate one graph generation per compilation and distinct module/storage producer identities. Pins validate captured source plus original revision options.
- Publish exact producer-qualified namespace, selected-export and re-export contracts. Validate environment graph/fact identity, source/input/dependency coverage, allocator dominance and retained producer context.
- Lower and emit a real new-only execution projection with exact external storage access contracts. Retained user initializers, closures, cells and state classes are not re-emitted; intrinsic helpers remain compiler-generated.
- Preserve imported mutable callable state in deterministic dependency/submission order, including persisted callers. Retained effect provenance requires exact certified source paths and targets, not a signature-based lookup. Mutable Java facade re-exports use typed external writers; Lyra import rebinding remains rejected.
- Keep new modules out of attempted reusable inventories. Validate duplicate-free canonical execution plans and preserve the sealed plan through result compatibility constructors.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/LyraCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionCompileResult.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionFlowCertificate.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/ArtifactAssembly.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedTypePlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/GeneratedTypePlanner.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmAbiParity.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrImportBinding.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrInitializationPlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrProgramMetadata.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrSessionExecution.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/TypedIr.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/TypedIrBuilder.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedImportBinding.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedSemanticGraph.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowFactValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticResolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticCore.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/PinnedModule.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/SessionExecutionPlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/SessionImport.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/SessionModuleContract.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/SessionModuleEnvironment.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/SessionSnapshot.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source/ModuleGraphDiscovery.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/SessionImportedFlowTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/SessionPinnedModuleCompilerTest.java`
- `.internal-dev/knowledge/repl-module-linkage.md`
- This changelog.

Pre-existing identity allocator/topology edits, Phase 01 runtime behavior and unrelated dirty/deleted/untracked files were preserved.

## Behavioral Impact

Compiler-only pinned/reused/application-borrowed projections now carry original producer authority and actual new work. Staged module metadata becomes usable only after the later runtime owner commits successful execution. The incoming environment remains the attempted snapshot's reusable inventory.

Focused Java 25 regression/sealing validation selects SessionCompilerTest, SessionPinnedModuleCompilerTest, SessionImportedFlowTest, Domain11SealingTest and TypedIrTest. The two module suites now contain 14 actual tests covering stable identities, new-only emitted classes, borrowed producers, retries, revisions, exact/effect-only order, deep coverage, diamonds, aliases/re-exports/conflicts, callable aggregate ownership, mutable callable replacement and pin-first transitive resolution.

Validation commands:

```sh
mvn -pl lyra-compiler -am -Dtest='SessionCompilerTest,SessionPinnedModuleCompilerTest,SessionImportedFlowTest,Domain11SealingTest,TypedIrTest' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl lyra-compiler -am test
mvn test
git diff --check
```

Final reruns passed: 75 focused tests; 570 compiler tests plus 15 runtime tests in the compiler reactor; and 743 full-reactor tests (15 runtime, 570 compiler, 114 REPL, 44 CLI). All had zero failures, errors and skips. `git diff --check` passed. Transient logs are `/tmp/lyra-phase02-focused-final.log`, `/tmp/lyra-phase02-full-compiler-final.log` and `/tmp/lyra-phase02-full-reactor.log`. Toolchain: OpenJDK 25.0.4, Maven 3.9.11 on Linux.

## Specification Impact

Specification Impact: none. This repairs the accepted Phase 02 compiler contracts without expanding the runtime/REPL delivery boundary or changing the living language semantics.

## Risks

- Multi-module prepared execution remains guarded. A projected artifact intentionally omits retained producer facades and is not an ordinary standalone loadable replacement for its producer graph.
- Retained compiler facts are metadata, not proof that runtime initialization succeeded. Runtime publication, residency and exact-instance registration remain Phase 03 responsibilities.
- This is a scoped repair, not final plan/release completion. Reload, attachment, remote/CLI changes and later gates remain outside this task.

## Follow-up Items

Continue Phase 03 against the sealed execution projection and producer contracts without relaxing initialization/lifecycle guards. The final alias-repair gate passed 77 focused tests, 572 compiler-reactor tests, and 743 full-reactor tests with zero failures, errors, or skips; `git diff --check` passed.
