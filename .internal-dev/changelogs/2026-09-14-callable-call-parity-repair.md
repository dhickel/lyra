# Callable call parity repair

## Date

2026-09-14

## Git Commit

`81b643c84f0b3eec905ccbfdabd1a6475a68c20c` (dirty-worktree baseline; this repair is intentionally uncommitted)

## Change Summary

Tranche 4 issue #6 named-call/callable parity is complete and release-gate qualified. Direct-name and callable-value calls share independently validated storage-route classification where authority is proven, while computed/unproved targets retain dynamic authentication. Existing work covers self-tail identity guards, stack-overflow boundaries, nilable function lowering, instance signature fields and Phase 23 pair gates. The final escalation repaired a newly reproduced declaration-write branch-join invariant, sealed nominal receiver occurrence correspondence, included deferred declarations in summary canonical identity, and strengthened tests/models. The getter-side nominal metadata blocker is now implemented: nominal callable getters, setters, initialization boundaries and delegated-read route issuance read deterministic private final per-instance expected callable signature fields resolved exactly once per generated object from its producer authority, and no generated nominal getter/adaptor boundary resolves or parses a signature per read.

## Files

- Compiler semantic/flow and IR route proofing: `semantic/**`, `semantic/flow/**`, `ir/CallableStorageRouteProof.java`, `IrNode.java`, `TypedIrBuilder.java`, `IrValidator.java`.
- JVM planning/emission/parity: `GeneratedClassPlan.java`, `GeneratedMemberKind.java`, `GeneratedMemberPlan.java`, `GeneratedTypePlanner.java`, `JvmAbiParity.java`, `JvmBytecodeEmitter.java`.
- Focused tests and fixtures: `CallableCallParityTest.java`, `TypedIrTest.java`, `SessionPinnedModuleCompilerTest.java`, planner/smoke/conformance tests, `named-call-parity.lyra`, and the deterministic metadata fixture.
- Phase 23 qualification contract: `Phase23Benchmark.java`, `phase23-evidence.sh`, `phase23-gates.json`, `phase23_gate.py`, and its contract test.
- Living specifications, decision record, testing documentation, bug report, and reusable knowledge records.

## Behavioral Impact

- Proven local, recursive, parameter, capture/shared-cell, import/session, intrinsic, and nominal-member routes omit redundant per-call authentication in both source spellings.
- Session-import proofs include exact producer/generation identity; nominal proofs include exact member index and receiver occurrence. A compiler-certified source-root external function binding now has a distinct exact-accessor route, while imported/intrinsic raw callable values and other unproved retained names remain on dynamic authentication.
- Mutable self-tail calls loop only when the selected slot still contains the executing closure, and mutable self-rebinding writes through the exact closure self slot.
- `StackOverflowError` from target selection, authentication, argument evaluation, or invocation becomes ordered source-mapped `LYR-STACK`; other VM errors escape.
- Dynamic expected signatures are deterministic private final fields on each generated state/closure instance and are independently inventoried against IR use. Each generated nominal representation carries the same per-instance metadata for its callable member signatures: the constructor resolves each distinct signature exactly once from the bound producer authority after exact schema availability, and getters, setters, initialization boundaries and delegated-read route issuance read those fields instead of resolving or parsing per read while still re-authenticating the exact object, field route, selected candidate, complete signature, owner/artifact/session and lifecycle.
- `LyraNominalObject.issueCallableMemberRoute`/`issueCallableMemberWriteRoute` now receive the already resolved exact signature and still compare it against the schema-declared field contract on every issue; wrong signatures, foreign candidates, invalid routes and initializing/failed/closed producers keep rejecting fail-closed. Session structural admission (`SessionTypeLoader`) accepts only the exact deterministic private final signature-metadata field shape on nominal representations.
- The Phase 23 evaluator requires both named/fib S/F rows, full protocol metadata, equivalent pair configurations, finite positive scores, and `score(S)/score(F) <= 1.10`. Minimum fork/warmup/measurement counts are preserved rather than incorrectly rejecting stronger runs.
- Branch/match write joins distinguish exact declaration storage from captures/parameters and preserve its operation site. The source regression selects a mutable self closure before argument-time rebinding and must still execute that selected closure.
- Normalized bytecode tests now compare branch/switch destinations, local slots and exception-table ranges, and fail on unresolved labels. Nominal receiver proof checks compare against the authorized semantic source child.
- A blanket callable-bearing aggregate authentication experiment was removed after it broke required raw/routed coexistence; no aggregate authority or callback-loop contract was changed.

## Specification Impact

Updated `language-core.md`, `backend-runtime.md`, `repl.md`, and `decisions.md` to define exact named-call convergence, route authority, certified external-binding accessors, dynamic fallback, receiver selection, mutable self-tail behavior, instance-scoped signature metadata, and stack-overflow mapping.

## Risks

The nominal getter-side metadata blocker is resolved with getter/adaptor-inclusive structural tests plus wrong-signature, failed-construction and closed-producer runtime regressions in AOT and session emission. The cross-generation repair additionally restricts external-binding proof issuance to source-root lambda identities carried by the compiler certificate; this preserves the raw imported/intrinsic anti-laundering boundary without changing `sourceLocal`. Full Maven, the bounded fuzz campaign, and a fresh full-protocol Phase 23 gate pass. The mandatory `tools/phase24-release-audit.sh` release gate passes: 156 of 157 non-deferred requirements PASS with 0 BLOCKED (1 Windows-only N/A) plus 10 explicitly deferred rows. The pre-existing `LanguageFuzzWorker` negative-range rendering workaround is retained; the generator's new named/mutable-self shapes render a leading direct call through the exact parenthesized spelling where the greedy postfix parse would absorb it.

## Follow-up Items

- Nominal getter/adaptor metadata hoisting and transitive structural parity are implemented with exact initialization/failed/closed-lifecycle regressions; shared nominal/delegate class identities, route authority and saved selections are unchanged.
- Update GitHub issue #6 with the exact Phase 24 and Phase 23 gate evidence and close it only after the owner confirms the release-facing record.

Final escalation validation: **799 explicitly selected runtime/compiler/REPL tests passed** (7 + 669 + 123), followed by JMH-profile compilation and 6 selected contract/structural tests. Evaluator self-tests, shell/Python syntax checks and `git diff --check` passed. Exact commands, repaired failures and remaining coverage are in `.internal-dev/reviews/2026-09-14-callable-call-final-escalation.md`. Old generated scratch-probe reports were preserved outside the repository under `/tmp/lyra-issue6-abandoned-probe-reports/`; no scratch source/class probe was added. No commit was made.

Independent validation addendum (2026-09-14): the current exact selector passed **801 tests** with zero failures/errors/skips (7 runtime, 671 compiler, 123 REPL). The earlier 799-test count below is retained as historical evidence; current `CallableCallParityTest` has 29 tests and `NominalBytecodeTest` has 32. The targeted nominal getter/adaptor metadata repair is present and independently confirmed; no additional production defect was found in that pass. A later cross-generation regression repair added exact source-root external-binding route certification after `EditorRuntimeTest` exposed a new per-call authentication mismatch. Post-repair `mvn test`, focused editor/REPL/compiler suites, and the full Phase 23 gate all pass; the two S/F ratios are 1.0046255892 (fib) and 0.9974556626 (named). The mandatory Phase 24 release audit then passed with 156 PASS, 0 BLOCKED, a fresh Phase 23 gate of 31/31 checks (callParity.fib 1.0062, callParity.named 0.9948, allocation.semantic.failure 2600.05 B/op against the ratified 4096 limit), and the REPL coverage and living-spec coverage inventories verified against the clean reactor reports.

Prior-attempt historical validation (not a completion claim):

- Explicit-selector backend/JVM suite: 247 tests passed.
- Explicit-selector semantic/flow/session-transfer suite: 220 tests passed.
- Explicit-selector non-fuzz language corpus/coverage suite: 278 tests passed.
- `LegacySchema1EncodingTest`: 3 tests passed.
- `tools/phase23_gate.py --self-test` and `Phase23EvidenceGateContractTest`: passed.
- JMH-profile `test-compile`, `bash -n tools/phase23-evidence.sh`, Python byte-compilation, and `git diff --check`: passed.
