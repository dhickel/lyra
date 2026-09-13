# Retained nominal independent campaigns and combined extended run

## Date

2026-09-13

## Git Commit

2c3602afa8299e3d2a89ce03cf6c4f0ff458ce37

## Change Summary

Phase 4 of the retained nominal struct/class factory completion. Both independent campaign models now generate retained nominal work, the extended campaign script runs them together with explicit larger budgets and documented replay routing, and the generation surfaced and closed two retained-path crashes on valid source.

- Compiler model: a twelfth fuzz family `retained` in `LanguageFuzzWorker` plus an independent `RetainedNominalModel` that drives real three-generation session submissions (compile, load, link, prepare, execute, stage, commit). Forty-seven operation names across ten deterministic profiles cover transfer variants (literals, references, `self:` member routes, operators, conversions), calls (lambda, direct, callable, aggregate-returning), composites (array, callable tuple, index, string index, length, nilable-element index), sequences (declare and rebind, shadowing, overwritten rebinds), alternatives (conditional, Unit conditional, coalesce, match, guarded match), ranges and nested construction, fresh versus shared identity and alias preservation, saved versus current callable slots, constructor effects, failure and recovery, route/inventory forgeries, link mismatches, and the four pinned issue-#7 shapes. Expected values, transfer variants, shapes, effect sums and failure codes come from the test-only model.
- Session model: `SessionStateFuzzTest` gained retained construction across generations, shared versus fresh identity, callable-bearing composites, ordered constructor and default effects, failure publication without staged names, recovery, and mutable-alias visibility, with the existing every-20-step reset cadence keeping the source-record budget valid at the largest documented step count and the child-process limits unchanged.
- Extended script: `tools/fuzz-language.sh` runs both models in one invocation over the reactor (compiler `cases=1800` and four seeds, session three seeds at `steps=240`), documents session replay selection and transcript location, and prints per-seed compiler summaries, per-seed session pass lines and failure-evidence paths. Either model failing fails the invocation.
- Guards: `FuzzInfrastructureTest` now enforces a 120-case minimum balanced across all twelve modes, covers every retained profile and operation per seed, pins the issue-#7 shapes and negative operations, round-trips replay for representative retained profiles, and rejects zeroed totals, removed operations or removed profiles; the driver validates nonzero per-name counts on every seed.
- Crash fix: retained nilable-element array indexing (`Array<@nil I32>[#NIL 1I32][1I32]` as a member initializer) no longer raises `retained projection route/index/result differs`. The routed-type comparison no longer strips nilability, which is stricter rather than looser: a `@nil` route can no longer certify a non-nilable result, and genuinely incompatible routes are still rejected. The same investigation closed a second crash where consumer-side composite prefixing defeated exact nil-route matching in `certifiesNil`; the new prefix-aware walk keeps site and span exact and still rejects foreign nil provenance.
- Records: the coverage documentation gains the retained-nominal matrix row, the combined commands and budgets, and the new architecture item; the fuzz knowledge file gains the worker session protocol, generator facts, reserved names, slot semantics, the binding-authority boundary, the reset-alignment trap, and the summary-guard design. A new open limitation is logged as `.internal-dev/bugs/retained-nominal/nilable-member-read-contract.md` (GitHub issue #8).

## Files

- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/conformance/LanguageFuzzWorker.java`, `LanguageFuzzTest.java`, `FuzzInfrastructureTest.java` (modified)
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/conformance/RetainedNominalModel.java` (new)
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/RetainedNominalFlowCertificateTest.java` (extended)
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionFlowCertificate.java` (nilable route fix)
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/SessionStateFuzzTest.java`, `NominalSessionTest.java` (modified)
- `tools/fuzz-language.sh` (rewritten for both models)
- `docs/language-testing.md`, `.internal-dev/knowledge/language-conformance-and-fuzzing.md` (updated)
- `.internal-dev/bugs/retained-nominal/nilable-member-read-contract.md` (new)

## Behavioral Impact

- A retained member initializer that indexes a nilable-element array now compiles and executes across generations (`1`, nil, `1`), and composite members containing `#NIL` elements construct across generations without a flow-site error.
- A genuinely `@nil` retained route can no longer certify a non-nilable result, and a genuinely foreign nil provenance is still rejected.
- The default bounded campaign now also exercises retained nominal work (`retained=15`, about 100 retained operations per seed), and the extended campaign exercises 150 retained cases per seed plus three session seeds at 240 steps.

## Specification Impact

Specification reconciliation is the plan's final audit phase. Contracts to update there: the campaign coverage matrix and combined extended command, the retained transfer algebra prerequisites for generated cases, and the open limitations recorded as issues #7 and #8.

## Risks

- Retained cases add roughly 50 percent to fuzz runtime (about 45 seconds bounded, about 220 seconds extended), within the documented budgets.
- The fuzz worker mirrors `LyraSession`'s requirement and factory derivation; a future protocol change must update both.
- Issue #7 (Unit or intrinsic-initialized retained member observed a generation later) and issue #8 (annotated retained nilable member reads) remain open and are documented rather than worked around.

## Follow-up Items

- Fix issues #7 and #8, then flip their pinned or documented cases to positive assertions.
- Reconcile specifications, decisions and the Phase 24 audit inventory in the audit phase.
