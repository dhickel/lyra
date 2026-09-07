# REPL Module Linkage

## Topic

Compiler-side retained producer identity, semantic evidence and new-only session execution projection.

## Source References

- `.internal-dev/specifications/repl.md` and `backend-runtime.md`
- `.internal-dev/plans/20260906-221525-complete-lyra-repl-with-trusted-module-linking-and-local/plan.md`, Phase 02
- `.internal-dev/changelogs/2026-09-06-repl-phase02-compiler-repair.md`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/session/SessionModuleEnvironment.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrSessionExecution.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/SessionPinnedModuleCompilerTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/SessionImportedFlowTest.java`

## Key Takeaways

- Pinning source bytes does not preserve an initialized producer. Re-resolving or checking a captured source creates new declaration/lambda/capture/flow identities even if its logical name and revision match. Reuse the original sealed records, syntax links and solved summaries, and analyze only new bodies.
- GenerationId denotes a graph generation; ProducerId denotes a module/storage owner. New modules in one graph share the former and must have distinct latter identities. Reuse preserves both.
- A source revision includes the original request's reproducible options. Current request options cannot validate an old pin. SessionCompileRequest adds Java/profile options, so SourceConfiguration.empty() is not equivalent to its sourceConfiguration().
- Immutable import/re-export contracts identify the actual origin producer and its original boundary facts. Current mutable alternatives belong to the evolving SessionFlowCertificate. Never infer a callable target by JVM/Lyra signature alone.
- Overlay success boundary states in dependency order with the submission last, not Map.copyOf iteration order. Attempted prefixes require joins instead of last-writer map iteration. Retained module frames must not evaluate original initializers again.
- Persisted caller effect sites can belong to an older scratch source outside the current graph. Validate their complete exact witness shape and producer-certified source-site suffix. A mutable call target must be the certified declaration's actual boundary lambda identity; source-site evidence is not a license to guess a target.
- Multiple valid effect witnesses can collapse to one initialization dependency. Require one distinct complete source-site path, not exactly one witness row; different paths must still fail sealing.
- Full semantic evidence is not executable IR. Filtering an execution-plan list is insufficient if retained modules, lambdas, cells, import fields or facade state reads are still generated. Project bodies, initialization metadata and backend layouts together, and emit external exact-typed accessors instead of retained state links. Preserve the intrinsic helper exception explicitly.
- Mutable re-export facade setters need exact external writer contracts as well as readers. This does not permit Lyra source to rebind imported names.
- A callable's aggregate-mutation summary must be checked against the callable's producer module, not only the importing caller. A captured aggregate owned by the imported callable's producer is a legal producer-authorized mutation; an imported caller-owned aggregate passed through a mutable parameter remains rejected.
- Attempted snapshots may reserve identities and retain conservative flow facts without publishing newly discovered modules as initialized residents. Retry must allocate fresh producers and still contain new initializer work.
- Environment validation needs every graph dependency's producer record and every retained producer graph's captured source context, not just the latest topology. Compare actual optional graphs/facts, not whether they are present.
- The Phase 02 classfile test loads/verifies emitted classes separately while asserting that ordinary LyraRuntime loading remains guarded. Removing the runtime guard to make a compiler projection test pass would incorrectly claim Phase 03 execution support.

## Project Relevance

The compiler now distinguishes original retained authority from new execution, including diamonds, selected/re-export contracts, borrowed metadata, mutable callable consumers and failed-attempt retries. Runtime/REPL execution remains independently gated. Use Java 25 focused semantic/IR tests and the complete reactor; artifact inventory assertions alone cannot prove absence of retained executable bodies.

## Open Questions

Phase 03 must bind these exact producer contracts to initialized runtime instances, preflight the whole prepared graph, and publish only after successful execution. Reload, application-root attachment and remote/CLI integration remain later accepted work, not claims of this compiler repair.
