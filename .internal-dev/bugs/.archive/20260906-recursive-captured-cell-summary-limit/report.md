# Recursive captured-cell summary growth

## Summary

A recursive source-local function writing a captured scalar cell exhausts the summary write domain and escapes the compiler API as `LyraCompilerBugException`.

## Scope

Pre-existing semantic callable-summary solving, discovered while validating the initialized-generation callable runtime bridge. No semantic compiler source was changed by that bridge pass.

## Reproduction

Compile this with `LyraCompiler.compileSession(new SessionCompileRequest("producer-cancel.lyra", source, SessionSnapshot.empty()))`:

```lyra
let @mut count :I32 = 0
let @pub loop :Fn<;I32> = (=> || { count := 7 (loop) })
let @pub read :Fn<;I32> = (=> || count)
```

## Expected

A certified recursive callable summary and executable self-tail loop; at minimum, a documented finite-domain limit must not be confused with a runtime bridge failure.

## Actual

`LyraCompilerBugException: session compiler invariant failed outside a phase boundary`, caused by `IllegalStateException: canonical semantic flow analysis failed: DOMAIN_LIMIT: captured-cell writes exceed the finite summary domain: 257 > 256` at `TypeChecker$State.freeze`.

## Evidence

First focused run of `mvn test -pl lyra-compiler -am -Dtest=SessionCallableRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false`, Java 25, 2026-09-06, baseline HEAD `86696c7598b46185c56e00c421dca8ef2392eca1`. The initial cancellation test exposed this before runtime linkage. The runtime test now separates a nonrecursive completed cell write from a write-free recursive loop; it does not claim this failing semantic case is fixed.

## Impact

Blocks some ordinary recursive mutation programs and the full callable REPL acceptance corpus. Increasing limits or treating unknown calls as effect-free is not a sound repair.

## Status

Resolved in baseline commit `ae793e7d6776f6611fa78ad80ea357ac827ed131` by recursive write/ownership normalization. Phase 13 revalidated the exact `count := 7 (loop)` corpus through `CallableSummaryTest` and the complete Java 25 reactor. GitHub issue https://github.com/dhickel/lyra/issues/2 was closed on 2026-09-08 after fresh `mvn -q clean verify` evidence.

## Next Action

None. Preserve the recursive operation-key normalization and its finite-domain regression coverage.
