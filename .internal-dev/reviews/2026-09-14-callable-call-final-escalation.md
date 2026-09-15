# Final issue #6 escalation review

## Scope

- Status: **BLOCKED**, partial repair preserved and buildable.
- Baseline and final HEAD: `81b643c84f0b3eec905ccbfdabd1a6475a68c20c`.
- Final escalation: `openai-codex/gpt-6-astra`, with two read-only `gpt-5.6-luna:high` audit helpers. Both helpers completed. A redundant senior spawn was rejected at the existing nested-delegation boundary; no substitute model was used for the repair.
- Read applicable guides, accepted issue plan, current language/backend/REPL specifications, current diff, issue report, relevant knowledge and actual test reports. GitHub issue #6 and its two comments were retrieved read-only; it remains OPEN. No commit, reset, push, issue publication or closure.
- No full Maven suite, fuzz campaign, graphical UI test, Phase 24 or full Phase 23 benchmark was run.

## Findings

### Repaired in this pass

1. **Mutable self argument-rebinding invariant.** `CallableSummaryCompiler.writeTargetKey` treated every non-capture write as a parameter write. A conditional recursive function whose argument rebinds its module slot failed with `INCONSISTENT_SUMMARY: write is not a parameter write`. Branch/match joins now distinguish declaration storage; `withWriteValue` preserves `operationSite` via `CapturedCellWrite.withValue`. Both S/F spellings execute the already-selected old closure and later calls observe the replacement. The exact initial failing report is preserved at `/tmp/lyra-issue6-argument-rebind-failure.xml`.
2. **Receiver proof correspondence.** Nominal access validation now checks the receiver site against the receiver child of the authorized semantic source expression. A candidate cannot substitute a same-type expression and merely update its candidate proof. `TypedIrTest` retains the adversarial case.
3. **Summary identity.** Deferred callable declaration IDs now enter `CallableSummarySet.canonicalKey`, matching their existing semantic equality/hash participation. A deterministic identity regression covers ordering and distinct sets.
4. **Validation gaps.** Structural comparisons now include branch/switch instruction-index destinations, local slots and exception tables; unresolved labels fail instead of becoming `-1`. The named-route matrix adds parameter, immutable/shared-cell capture, mutable, selective/qualified import and intrinsic comparisons. Deterministic tests cover target-selection overflow frame order and post-entry SAM injection at callable declaration/rebind/dynamic-call boundaries.
5. **Independent model.** `MUTABLE_SELF_CALL` joins the default typed-program generator. Its independent oracle returns the selected old target's result, not the replacement's result. A focused coverage test executes both renderings for all ten numeric types. The random campaign itself was not run.
6. **Performance evaluator.** Restored the original minimum fork/warmup/measurement budgets; retained one-second iterations, required GC metrics and pair-configuration equality. Added fixed-threshold checks at 1.10 and its next representable value and invalid primary metrics for both sides of both pairs.
7. **Cleanup.** Removed unused production helpers `dynamicInvocationSignatures(IrNode)`, `hasAggregateReferenceLeaves` and `containsReferenceLeaf`. No abandoned scratch source/class was present. Twelve disposable probe reports were moved, without overwriting, to `/tmp/lyra-issue6-abandoned-probe-reports/`. Legitimate benchmark probes and all current regression sources remain.

### Audit findings reconciled, not blindly applied

- One helper proposed eager authentication of all callable-bearing aggregate storage/parameters/returns. An explicit experiment failed `NominalSessionTest.delegatedAndRawCallablesCoexistInAggregatesWithoutCrossBlessing`: the accepted contract permits routed and raw occurrences to coexist and rejects unauthorized selected use. The experiment and its incompatible expectations were removed; the original regression was not altered. All 81 nominal-session tests subsequently passed. Named scalar function stores remain authenticated; computed/aggregate targets retain per-call authentication. This is not authority to widen aggregate checks or the session bridge.
- Imported `std->io` exports are resolved intrinsic function names and legally use `::print`/`::println`. They are not the bare-head special forms/operators forbidden after `::`. The helper's proposed syntax rejection was not accepted.
- A route proof is validated against sealed semantic/IR identities and relies on generated authenticated entry/write implementations. Its presence does not independently prove arbitrary future emitter changes safe; tests must inspect the actual boundary and downstream getter/adapter path. No speculative second certificate/ABI was introduced.
- The no-redundant-metadata claim cannot be established by caller-only comparison. Nominal getters still call `authenticateNominalFieldValue(..., instanceSignatures=false)`, which calls `emitScopedSignatureOverAuthority` and emits `LyraClosureAuthority.resolveSignature` per callable field read. No removal of required object/field/candidate authentication is permitted to eliminate that metadata work.

## Risk Assessment

### Remaining implementation and acceptance gaps

- **Nominal member transitive fast path is unfinished:** getter-side signature resolution remains per read. Required receiver/schema/field and occurrence-delegate checks remain intact, so this is a performance/acceptance gap, not a demonstrated invocation bypass.
- The complete getter/adapter-inclusive structural matrix and exact initialization/failed/closed-producer tests for the final metadata placement remain necessary. Existing caller-route, nilable, source-frame, session-authority and cancellation tests do not replace them.
- Fresh full-protocol Phase 23 primary-score ratios are absent. JMH compilation and evaluator/structural tests are not benchmark results.
- Full/default and extended fuzz, broad reactor qualification and release gates were explicitly excluded. The pre-existing negative-range rendering workaround in `LanguageFuzzWorker` and callback-loop bug were not changed or qualified.
- Existing local modifications to specifications and records state intended contracts, not proof of complete issue acceptance. The issue report and changelog now explicitly say BLOCKED.

### Files changed by this pass

- Production: `semantic/flow/CallableSummaryCompiler.java`, `CallableSummarySet.java`, `ir/IrValidator.java`, `backend/jvm/JvmBytecodeEmitter.java` (unused-helper removal only), `GeneratedTypePlanner.java` (unused-overload removal only).
- Tests/models: `CallableSummaryTest.java`, `backend/jvm/CallableCallParityTest.java`, `Phase23EvidenceGateContractTest.java`, `ir/TypedIrTest.java`, `conformance/TypedProgramGenerator.java`, `LanguageCoverageTest.java`.
- Tooling: `tools/phase23-evidence.sh`, `tools/phase23_gate.py`.
- Records/docs: issue #6 report, existing callable-call-parity changelog, callable-summary/JVM-emitter/performance knowledge, `docs/language-testing.md`, this review.
- Other files in the pre-existing 41-path worktree were preserved; their presence in the overall diff is not a claim that this pass authored them. No protocol-v2, JavaFX or callback-loop implementation change.
- Specification impact of this final pass: no new intended contract. Repairs enforce the accepted contracts already represented by the preserved language/backend specification edits.

## Validation

Environment: OpenJDK 25.0.4+7 (Red Hat), Maven 3.9.11, Linux amd64.

Final selected suite:

```sh
mvn -pl lyra-repl -am -Dtest=CallableCallParityTest,TypedIrTest,CallableSummaryTest,Domain11FlowStateTest,Domain11InitializationFlowTest,SemanticFlowAlgebraTest,GeneratedTypePlannerTest,NominalBytecodeTest,Phase15SmokeTest,Phase16SmokeTest,Phase17SmokeTest,Phase18SmokeTest,Phase23EvidenceGateContractTest,Phase23StructuralBytecodeTest,SessionPinnedModuleCompilerTest,LegacySchema1EncodingTest,LanguageConformanceTest,LanguageCoverageTest,ParserTest,GrammarMatcherTest,NominalConstructionTest,SessionClosureAuthorityTest,NominalSessionTest,SessionModuleRuntimeTest,LyraSessionTest,SessionGenerationLifecycleTest,AttachmentCancellationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

**PASS: 799 tests, zero failures/errors/skips** (runtime 7, compiler 669, REPL 123). Log: `/tmp/lyra-issue6-final-validation.log`. Includes generated artifact loading/invocation and the existing external JVM verification smoke checks. `CallableCallParityTest`: 28; `TypedIrTest`: 21; `CallableSummaryTest`: 58; nominal sessions: 81.

Explicit JMH-profile contract compilation/test:

```sh
mvn -Pjmh -pl lyra-compiler -am -Dtest=Phase23EvidenceGateContractTest,Phase23StructuralBytecodeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

**PASS: 6 tests**, JMH sources/annotation-generated harness compiled; no measurement executed. Log: `/tmp/lyra-issue6-final-jmh-contract.log`.

```sh
python3 tools/phase23_gate.py --self-test
bash -n tools/phase23-evidence.sh
python3 -c "from pathlib import Path; compile(Path('tools/phase23_gate.py').read_text(), 'tools/phase23_gate.py', 'exec')"
git diff --check
```

All passed.

Earlier focused runs passed 96 baseline route/JVM tests, 143 authority/session tests, and 142 flow/parity tests. A missing `Set` import in the new summary-key test briefly failed test compilation and was corrected before final validation. The only functional repair failure was the recorded mutable-self invariant; the separate aggregate experiment was withdrawn on concrete contract-regression evidence. Earlier logs are diagnostic history, not additive unique test counts.

## Recommendations

Keep issue #6 OPEN and retain the uncommitted worktree. Do not present the 799-test selection or JMH contract tests as the full issue gate. Do not weaken occurrence authority or change raw/routed aggregate coexistence.

## Follow-ups

Exact next implementation action: extend nominal getter/adaptor-inclusive structural inspection, then replace redundant per-read callable signature resolution with producer-scoped preinitialized metadata compatible with the existing shared nominal/delegate class identities. Preserve exact receiver/field authentication, signature checks, selected-value semantics and producer lifecycle. Validate initialization/failed/closed-producer cases and both spellings. Only after those changes and authorization should full Phase 23, broader/fuzz/release qualification and a commit/issue closeout be attempted.

## Independent validation addendum (2026-09-14)

The worktree now contains the targeted nominal getter/adaptor metadata repair described by the changelog and bug report: `NominalClassLayout` plans deterministic private-final per-instance `LyraSignature` fields, generated constructors resolve them once through `LyraNominalObject.resolveExpectedSignature`, and getter/setter/initialization/delegate paths read the fields without per-read resolution or parsing. The prior review findings above are retained as historical evidence and are superseded on this point by the current source and structural tests.

The exact selector in this validation pass passed **801 tests** with zero failures/errors/skips: runtime 7, compiler 671, REPL 123. Current counts are `CallableCallParityTest` 29 and `NominalBytecodeTest` 32; the earlier 799/28 counts in this review are historical. No additional production defect was found in that pass. `git diff --check`, Phase 23 evaluator self-test, shell syntax and Python compilation also passed.

## Cross-generation external-binding review addendum (2026-09-14)

A later `EditorRuntimeTest` run exposed a parity regression on the first retained `::next[]` call from a root that imports only `std/io`. The current caller and producer have different rootless linkages, and the producer intentionally does not satisfy `Linkage.sourceLocal`; changing that classifier would violate the pinned authority contract. The repair instead adds an `EXTERNAL_BINDING` route that is issued only for an exact certified binding whose callable alternatives are root-route lambdas from source-root summaries. Raw imported/intrinsic alternatives remain dynamic, as proven by both compiler shape assertions and the `NominalSessionTest` raw-before/raw-after cases.

An independent `gpt-5.6-luna:high` read-only review found no blocking authority widening, provenance laundering, mutable/replacement, forgeability or allocation issue in the certificate/IR paths. Its tooling could not independently resolve the emitter/test paths, so root inspection and executable validation remain the evidence for those portions. Post-repair focused editor/REPL/compiler suites, full `mvn test`, `git diff --check`, and full gate-mode `./tools/phase23-evidence.sh` all pass. Phase 23 ratios are fib `1.0046255892115525` and named `0.9974556625661044`; all selected gates pass. Extended fuzz, graphical UI and Phase 24 remain unrun.
