# REPL Callable Linkage

## Topic

The distinction between physical cross-generation callable interchange and certified named REPL callable persistence.

## Source References

- `.internal-dev/specifications/repl.md` and `backend-runtime.md`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraOwnershipToken.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/SessionCallableRuntimeTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/runtime/SessionClosureAuthorityTest.java`
- `.internal-dev/bugs/20260906-recursive-captured-cell-summary-limit/report.md`

## Key Takeaways

- Shared structural function interfaces already provide exact physical JVM type identity across loaded generations. Original closure objects retain original state and specialized captured cells; no adapter, copying, shared cell class loader, or Object execution ABI is required for passing an initialized closure through an exact generated function parameter.
- Artifact identity must remain distinct from opt-in session authority. Both producer and consumer must be source-local runtime-loaded generations of the same live domain/epoch. Same-artifact initialization rules do not justify cross-artifact INITIALIZING or FAILED producer authority.
- A linkage must pin the complete immutable artifact metadata, not just a caller-reusable artifactId. In the first reviewed implementation, recomputing a mutable sourceLocal flag during validation could reclassify existing authority if a different valid metadata inventory reused the ID. Final code pins metadata and classification once and rejects substitution before loading/execution.
- Candidate-side authentication needs tests separate from JVM casts and expected-owner checks. Package-level runtime tests can prove foreign domains, imported graphs, retired producers, logical signature mismatches with identical primitive descriptors, and OPEN-producer requirements without exposing an authority getter in the public API.
- Epoch retirement must also invalidate closure validity, creation/invocation and module-owned I/O, not just external accessor tables. Owner checks remain first; a terminal module lifecycle keeps precedence over a later retired session epoch.
- Physical bridge tests use existing Java export signatures and complement, rather than replace, compiler-side named persistence. The session compiler now transports producer-certified callable identities, summaries, captures, shared-cell snapshots, allocation sites and operation-site spans through `SessionFlowCertificate`; opaque or type-only scalar callable transfer remains invalid.
- Summary fixed points for a recursive function writing a captured scalar currently can exhaust the 256-write domain. This is pre-existing compiler behavior, reproduced before reaching the runtime bridge and mirrored as GitHub issue #2. Separating a nonrecursive completed write from a write-free recursive loop validates runtime cancellation without claiming that semantic defect is repaired.
- Lyra `/` produces F64, including integer operands; use checked I32 addition overflow for an I32 runtime-failure fixture. The initial failure fixture incorrectly assumed integer division semantics.
- This senior session was already a first-generation gpt-6-astra:xhigh child. A control-enabled nested senior launch was rejected by the harness. Such a child must implement directly and return a precise next-level escalation request to its parent, not retry another nested senior or downgrade the model.

## Project Relevance

The initialized-generation runtime bridge is exercised in both chronological directions, through higher-order returned closures, function-bearing arrays/tuples and aggregate signatures. Actual later-source ordinary assignment updates the original captured cell. Tests cover lexical replacement, failed new initializer effects on old cells, ordinary invocation failure and exact producer spans, cancellation and recovery, reset/close, foreign SAM/artifact/session rejection, and unchanged compiler callable guards.

Final validation on Java 25/Linux: the focused callable/session suites and forked Java consumer pass under the reactor's Java 25 verification path; `mvn test` and `mvn clean verify` pass with zero failures/errors/skips. Phase 23 measurements and the full Phase 24 audit remain separate release gates; native macOS/Windows checks were not run.

## Open Questions

Named compiler/REPL callable persistence is implemented for certified source-local generations, including current mutable/captured flow, per-binding initialization, failed/cancelled-generation escape retention and source-origin presentation through `EvaluationResult`. Imported/pinned modules, reload, configured/application roots, owner executor/input coordination, application safe points, and debug deployment remain unfinished. Callable linkage remains intentionally fail-closed for imported graphs and uncertified generations.
