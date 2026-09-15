# Callable-value calls pay full signature parse + closure authentication on every call, ~137x slower than equivalent direct :: calls

## Summary

A callable-value call `(fib ...)` runs a complete `LyraSignature.parse` plus full closure
ownership authentication before every invocation, while the equivalent direct call
`::fib[...]` loads the compiler-known closure and invokes it directly. For fib(30)
(~2.7M recursive calls) the callable form measured ~1236 ms versus ~9 ms for the direct
form (~137x; the user reported 20x on their machine, same mechanism).

## Scope

- Compiler: JvmBytecodeEmitter.emitCallableCall (vs emitDirectCall)
- Runtime: LyraClosureSupport.requireAuthenticatedForGeneratedInvocation, LyraSignature.parse
- The callable authentication boundary itself (backend-runtime.md) is intended and out of
  scope to remove; the defect is the absence of any fast path for provably compiler-owned
  targets.

## Reproduction

```lyra
// slow (~1236 ms / fib(30) best-of-10 after warmup)
let fib :Fn<I32;I32> = (=> |n|
  (match n ?? 0 -> 0 ?? 1 -> 1 ?? _ -> (+ (fib (- n 1)) (fib (- n 2)))))
let @pub run :Fn<;I32> = (=> | | ::fib[30])
```

```lyra
// fast (~9 ms)
let fib :Fn<I32;I32> = (=> |n|
  (match n ?? 0 -> 0 ?? 1 -> 1 ?? _ -> (+ ::fib[(- n 1)], ::fib[(- n 2)])))
let @pub run :Fn<;I32> = (=> | | ::fib[30])
```

Compile via LyraCompiler/CompileRequest, load, and invoke the `run` export repeatedly.

## Expected

After resolution, callable-value and direct-name calls over the same exact authenticated
declaration/storage route should share one lowering. Eligibility must come from sealed
local/recursive, parameter, capture/shared-cell, import/session, intrinsic or member-route
evidence, never from spelling, type/descriptor, generated class or declaration shape.
Aggregate/index projections, returned callables and other computed targets remain dynamic
with full per-call authentication. Dynamic expected signatures should be resolved once per
producer-scoped generated instance without creating a global authority cache.

## Actual

Every `(fib ...)` call site emits, per call:

```text
aload_0
getfield $lyra$authority
ldc "Fn<I32;I32>"
invokestatic LyraSignature.parse:(Ljava/lang/String;)LyraSignature
invokestatic LyraClosureSupport.requireAuthenticatedForGeneratedInvocation:
    (Object;LyraClosureAuthority;LyraSignature;)LyraClosure
checkcast lyra/generated/$lyra$fn$6fcc8ef2886f65eb
... invokeinterface invoke:(I)I
```

while the `::fib[...]` call site emits only `aload_0; iload n; invokeinterface`.
Measured marginal cost ~450 ns/call, entirely consistent with the timing gap.

## Evidence

- TypeChecker.checkCallableCall -> TypedExpressionKind.CALLABLE_CALL; checkDirectCall ->
  TypedExpressionKind.DIRECT_CALL (lyra-compiler, semantic/TypeChecker.java ~2319/2347).
- JvmBytecodeEmitter.emitCallableCall (~4501) runs emitSignatureOverAuthority +
  authenticateGeneratedFunctionValue before every invokeinterface; emitDirectCall (~4469)
  does not. targetDeclaration() is already computed in emitCallableCall but only used to
  select intrinsics.
- LyraSignature.parse -> LyraTypeParser.parse: full recursive-descent parse of the
  canonical spelling plus canonical re-serialization, no caching.
- LyraClosureSupport.requireAuthenticatedForGeneratedInvocation: token usability/thread
  checks, sameArtifact/sameSession ownership comparison, second token check, structural
  LyraSignature.equals.
- Both generated closures' `invoke` methods were dumped and diffed (temporary dump test,
  removed after the investigation); the only structural difference was the per-call
  authentication block in the callable variant.
- Temporary benchmark/dump tests were removed afterwards; no repository files were changed.
  Note: `mvn install -DskipTests` of lyra-runtime was run locally so the compiler test
  module could resolve the WIP runtime classes; local Maven repo only.

## Impact

- Self-recursive and local-first-class function calls written in the `(f ...)` form are
  two orders of magnitude slower than the direct form, in a language that encourages
  callable-value composition. Any hot loop over callable values pays the same fixed
  per-call authentication tax.
- No correctness impact: both forms return identical results (832040 verified).

## Status

BLOCKED: the uncommitted Tranche 4 worktree based on
`81b643c84f0b3eec905ccbfdabd1a6475a68c20c` is buildable and focused checks pass,
but issue #6 is not complete. Final escalation found and repaired an additional
mutable-self argument-rebinding compiler invariant and tightened receiver-proof and
bytecode-validation checks. The remaining getter-side blocker is now implemented:
nominal callable getters, public getters, setters, initialization boundaries and
delegated-read route issuance no longer call `LyraClosureAuthority.resolveSignature`
or parse per read. Each generated nominal representation carries deterministic
private final per-instance expected callable signature fields, resolved exactly
once from the bound producer authority in the generated constructor after exact
schema availability and before use; every boundary still re-authenticates the
exact object, field route, selected candidate, complete signature,
owner/artifact/session and lifecycle. Focused structural tests prove the
constructor resolves each distinct signature once, getter/adaptor methods contain
no per-read resolution/parsing, and runtime tests keep wrong-signature,
failed-construction, closed-producer and invalid-route rejection fail-closed.
A later cross-generation regression repair completed full Maven validation and a
fresh full-protocol Phase 23 gate. Extended fuzz, graphical UI, Phase 24, commit
and issue closure remain outside this repair. GitHub issue #6 was checked
read-only and remains OPEN.

The implemented contract is broader and safer than the report's original proposed
immutable-local shape check: both direct-name and callable-value IR calls may carry a
producer-issued `CallableStorageRouteProof`, independently recomputed by `IrValidator`.
The proof covers exact local/recursive, parameter, capture, shared-cell, import, retained
producer/generation, compiler-certified source-root external binding, intrinsic and
nominal member-index/receiver-occurrence routes only after their complete entry/write
boundary authenticates the callable contract. Computed/aggregate targets, raw
imported/intrinsic external bindings and retained names without an exact route remain
dynamic. Expected dynamic signatures are private final per-state/per-closure-instance
fields resolved once through that producer's authority; there is no static/global cache.

The same tranche also unifies S/F receiver selection, guarded mutable self-tail lowering,
complete source-bearing `StackOverflowError` regions, nilable-Fn coalesce stack-map typing,
parenthesized/sibling direct-call regressions, conformance generation and machine-gated JMH
pairs. Shared issue-#7 nominal delegate classes remain byte-identical and retain their
existing occurrence authority.

## Next Action

- Getter-side nominal signature metadata is implemented: nominal getters,
  setters, initialization boundaries and delegated-read route issuance read
  deterministic per-instance fields resolved exactly once per generated object
  from its producer authority; the exact object/field-route/selected-candidate/
  signature/owner/lifecycle authentication remains on every read. Structural
  getter/adaptor inspection and failed/closed-producer runtime regressions
  cover both spellings and both AOT/session emission modes.
- Preserve the fresh full-protocol Phase 23 evidence; both `callParity.fib` and
  `callParity.named` use finite positive primary scores and pass
  `score(S)/score(F) <= 1.10`.
- Run the remaining extended-fuzz/graphical-UI/Phase 24 validation required by the accepted plan when authorized, review the final
  diff, commit the coherent Tranche 4 unit, post exact commit/test/gate evidence to GitHub,
  then close issue #6.
- Mirrored issue: https://github.com/dhickel/lyra/issues/6.

## Cross-generation external-binding repair evidence (2026-09-14)

- Root cause: parity lowering newly sent an unqualified callable from a prior submission through current-caller dynamic authentication. That declaration is an `EXTERNAL` binding using `$lyra$sessionAccessor`, not an imported `IrSessionExecution.ExternalAccess`; a generated root plus only `std/io` intentionally does not satisfy `Linkage.sourceLocal`, so current-caller authentication rejected the valid producer closure.
- Repair: `CallableStorageRouteProof.EXTERNAL_BINDING` is issued and independently recomputed only when `SessionFlowCertificate` certifies the exact binding and every callable alternative has source-root lambda provenance. The runtime still validates the exact initialized accessor/capability. Raw imported/intrinsic alternatives keep the dynamic boundary; `sourceLocal` and general runtime authentication are unchanged.
- `EditorRuntimeTest`, `PersistentCallableTest`, the 81-test `NominalSessionTest` anti-laundering suite, focused compiler/IR/session/Phase 23 structural tests and full `mvn test` pass.
- Full `./tools/phase23-evidence.sh` gate mode passes. S/F ratios: fib `1.0046255892115525`, named `0.9974556625661044`; all selected allocation, latency, failure, footprint and parity gates pass.

## Prior Attempt Evidence (2026-09-14)

These historical counts preceded the final escalation and are not completion evidence.

- `CallableCallParityTest` (24 tests) proves named-route behavior, selected-target-before-
  argument semantics, S/F normalized instruction/call-graph parity, guarded deep self-tail
  calls, structured overflow frame order (including an enclosing argument-evaluation call),
  exact `StackOverflowError` catches, non-stack VM-error escape, nilable-Fn coalesce,
  dynamic forgery rejection and isolated instance signature fields.
- `TypedIrTest` (20 tests) proves S/F convergence on one resolved route classification and
  independently rejects missing/forged direct and callable route proofs. Its import checks
  and `SessionPinnedModuleCompilerTest` pin exact import and retained-session
  producer/generation identities for S and direct calls.
- Explicit-selector backend/JVM validation passed 247 tests; semantic/flow/session transfer
  validation passed 220 tests; non-fuzz corpus/coverage validation passed 278 tests;
  `LegacySchema1EncodingTest` passed 3 tests after refreshing the deterministic debug-map
  fixture. `mvn -Pjmh -pl lyra-compiler -am -DskipTests test-compile` also passed.
- `Phase23EvidenceGateContractTest` and `tools/phase23_gate.py --self-test` pass and pin
  mandatory row presence, duplicates, finite-positive metrics, exact full-protocol metadata,
  pair configuration equality and both sides of the ratio threshold. `bash -n` and Python
  byte-compilation checks pass for the tooling.
- No full Maven suite, fuzz campaign, UI suite, fresh/full Phase 23 benchmark, Phase 24 gate,
  commit or GitHub close is claimed; those commands/actions were explicitly excluded from
  this repair task. Pre-existing Phase 23 evidence is stale and is not qualification evidence
  for this worktree.

## Final Escalation Evidence (2026-09-14)

- Final explicitly selected runtime/compiler/REPL run passed **799 tests**: 7 runtime,
  669 compiler, 123 REPL, no failures/errors/skips. Exact selectors and outcomes are in
  `.internal-dev/reviews/2026-09-14-callable-call-final-escalation.md` and
  `/tmp/lyra-issue6-final-validation.log`.
- `CallableCallParityTest` now has 28 tests; `TypedIrTest` has 21. New assertions cover
  self selection before argument rebinding, exact target-overflow frame order,
  post-entry foreign-SAM rejection, broader named-route normalized control flow,
  exception tables, and semantic receiver-occurrence correspondence.
- The mutable self regression initially failed with `INCONSISTENT_SUMMARY: write is
  not a parameter write`. Branch/match joins now distinguish declaration writes and
  retain their operation sites. Deferred callable declarations also enter summary
  canonical identity. The default typed generator includes `MUTABLE_SELF_CALL`, with
  its independent expected selection executed for every numeric type in a focused
  coverage test. No fuzz campaign was run.
- A blanket aggregate-authentication experiment failed the required raw/routed
  coexistence test and was removed. Existing selected-value authorization is preserved;
  `NominalSessionTest` subsequently passed all 81 tests. This was not a reason to
  weaken or rewrite that regression.
- JMH-profile compilation and explicitly selected Phase 23 contract/structural tests
  passed (6 tests). Evaluator self-tests, shell/Python syntax and diff checks pass.
  Protocol minimum counts are preserved; fixed 1.10/nextafter threshold edges and
  invalid metrics on both pairs are tested. No benchmark measurements were taken.
- No scratch source/class probes remain. Old disposable probe reports were moved to
  `/tmp/lyra-issue6-abandoned-probe-reports/`; legitimate benchmark probes and all
  production/regression sources remain. Two unused partial-implementation helpers
  were removed. The pre-existing callback-loop/generator workaround was not changed.

## Extended form-homogeneity review (appended to issue #6)

A full `(f args)` vs `::f[args]` review found five more divergences beyond the
per-call tax; full evidence is in the issue comment (id 5639745595):

1. Tail calls: `emitTail` lowers only `IrNode.DirectCall` self-tail calls to
   constant-stack loops. `(count (- n 1))` tail recursion throws raw
   `StackOverflowError` at depth ~20k while `::count[(- n 1)]` succeeds; the
   spec-mandated LYR-STACK conversion also leaks because argument evaluation
   sits outside the SOE catch range.
2. `::f[...]` is a grammar postfix: after any expression form it is absorbed as
   a receiver-call (`{ let add = (=> |x| (+ x 1)) ::add[1] }` parses as ONE let
   with initializer `(lambda)::add[1]`), producing the misleading
   LYC-RESOLVE-017 on the lambda parameter. `(add 1)` does not absorb.
3. A parenthesized direct call `(::id[5])` misparses as a zero-arg call on the
   direct call's result (LYC-TYPE-007), so `::f[...]` cannot be parenthesized
   or delimited after another expression (no form terminator exists).
4. Nilable-Fn coalesce `(f : (=> |x| 0))` crashes the emitter
   (`Could not resolve class $lyra$fn$...` in Class-File API stack-map
   generation; interface not yet registered) — independent of call form, but it
   blocks the nilable-callable path entirely.
5. Nominal method calls (`receiver::method[...]` and `(receiver:.method ...)`)
   both crash the emitter in the current nominal WIP, so method-form parity is
   unassessed.

Parity confirmed for: parameter calls, capture calls, @mut rebinding,
shadowing, iter/while spellings, intrinsic calls, arity diagnostics,
nilable-target rejection, moderate-depth recursion, tuple-member calls.
