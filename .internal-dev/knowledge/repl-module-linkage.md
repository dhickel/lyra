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
- Reload freshness is the root-reachable closure of the synthetic reload graph, not every session-owned node seeded as an unrelated retained import root. Each fresh reload/retry receives a distinct compiler SourceId/ModuleId while SourceSnapshot retains the original resolver identity separately. Reused modules are discovery boundaries: their original dependency graphs stay in their producer records, never retargeted through current defaults. Fresh traversal stops at application/intrinsic boundaries.
- A single logical-default map is not producer history. SessionModuleEnvironment retains historical producer records and indexes their distinct ModuleIds, while modules()/modulesByLogical() describe current defaults. Validate every historical source/dependency record, and validate old selected-import contracts against that exact history. Skipping coverage for old graphs hides identity collisions instead of fixing them.
- SemanticFlowEvent.moduleId identifies an effect's source site, not necessarily its executing initializer. Restore EFFECT events by witness.fromModule together with the matching eager-effect index. Copying only eventsAt(module) can omit a dependency-site EFFECT event and fail nondeterministically as fresh source IDs change module ordering. Semantic/IR validation must recognize the complete exact event and site/span paths in its sealed producer graph.
- SourceRegistry must retain actual imported snapshots, not only source hashes and submission records. Delayed old/new failures can share one display URI while requiring different compiler identities, text and UTF-16 excerpts. Failed-attempt retries also need fresh identities, even when their logical modules never became defaults.
- Graph preflight and final submission-record append must account the same reserved caller text. SourceSnapshot strips an initial UTF-8 BOM while EvaluationSource retains it; hashing/counting these separately can consume a second source slot after execution and publication. Retain the reservation's exact text identity for both accounting steps.
- Initializer progress cannot be inferred from a sealed plan or lifecycle state alone: generated code records an attempted callback before each initializer body and a completed callback after it. Include scratch declarations and scheduled-but-unattempted preflight failures. Session dependency functions/forms need the same lease cancellation boundaries as scratch code; ordinary AOT code must not gain these calls.
- The Phase 02 classfile test loads/verifies emitted classes separately while asserting that ordinary LyraRuntime loading remains guarded. Removing the runtime guard to make a compiler projection test pass would incorrectly claim Phase 03 execution support.

## Project Relevance

The compiler/runtime/REPL now distinguish retained producer authority from new execution, including diamonds, selected/re-export contracts, borrowed metadata, mutable callable consumers and failed-attempt retries. Phase04 validation executes same-file old/new closures, fresh topology, cancellation prefixes and exact delayed source mapping. Use Java 25 focused semantic/IR tests and the complete reactor; artifact inventory assertions alone cannot prove absence of retained executable bodies.

## Open Questions

Application-root registration, root-lifetime attachment/reset/reopen, application safe points and remote/CLI integration remain later accepted work. Standalone reload tests do not establish those attachment contracts. See the Phase04 review and repair changelog dated 2026-09-07.
