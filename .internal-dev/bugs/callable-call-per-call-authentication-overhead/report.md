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

For an immutable, compiler-owned local binding whose contract exactly matches the call's
static function type, the callable-value call should cost the same as the direct call:
load the known closure and `invokeinterface` the typed Fn interface. The per-call
authentication should apply only where the value could originate outside the artifact
(parameters, aggregate slots, mutable cells, session/imported bindings), or should be
hoisted/interened so its marginal cost is near zero.

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

Open. Reproduced on the current WIP worktree (commit c9415a8 + preserved uncommitted
nominal-schema work).

## Next Action

- Decide and implement the compiler fast path: in emitCallableCall, when the target is an
  immutable compiler-owned declaration whose contract equals the call's static function
  type, emit the direct declaration load + invokeinterface.
- At minimum, hoist the parsed LyraSignature into a call-site static field and intern
  LyraSignature instances so even the general path stops parsing per call.
- Keep full per-call authentication for genuinely first-class targets (parameters,
  aggregate slots, mutable cells, session/imported bindings).
- Add a performance regression test comparing both fib forms once fixed.
- Mirrored to GitHub: https://github.com/dhickel/lyra/issues/6 (created 2026-09-11).
