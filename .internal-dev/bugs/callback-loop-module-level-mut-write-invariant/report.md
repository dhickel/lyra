# Callback loop writing a module-level `@mut` binding fails with a compiler invariant

## Summary

A compiler-recognized callback loop (`iter` or `while`, in every spelling) whose callback body **writes a module-level `@mut` binding** fails compilation with `LyraCompilerBugException` instead of compiling. The unwrapped cause is:

```text
java.lang.IllegalArgumentException: invalid semantic flow facts:
semantic event flow-site identity does not match its source span
  at SemanticFlowFactValidator$Validator.requireSite(SemanticFlowFactValidator.java:881)
  at SemanticFlowFactValidator$Validator.validateEvent(SemanticFlowFactValidator.java:402)
  at SemanticFlowFactValidator$Validator.validateEventsAndRootCoverage(SemanticFlowFactValidator.java:313)
```

An unchecked compiler invariant escapes the phase boundary as a compiler-bug exception, so the failure is not a structured diagnostic and cannot be recovered by source changes alone.

## Scope

- `lyra-compiler` semantic flow certification for callback loops (`SemanticFlowAnalyzer` loop handling and `SemanticFlowFactValidator` event/site validation).
- Reproduces for `iter` and `while`, for `::iter[...]` / `(iter ...)` / `iter[...]` spellings alike, and for both zero-parameter and one-parameter `iter` callbacks.
- Not caused by and not specific to any recent change: it reproduces on baseline commit `de93506` (and on the earlier pre-change spellings), in a clean worktree, with no local edits.

## Reproduction

Compile with `LyraCompiler.compile(CompileRequest.source("main.lyra", source))`:

```lyra
let @mut n :I64 = 0

let @pub run :Fn<;I64> = (=> || {
  (iter (0..3:1) || { n := (+ n 1I64) })
  n
})
```

Equivalent (`while`, and the bracket spellings) also fail:

```lyra
let @mut n :I64 = 0
let @pub run :Fn<;I64> = (=> || {
  while[|| (< n 3I64) || { n := (+ n 1I64) }]
  n
})
```

## Expected

The program compiles and `run` returns `3` (the loop's writes are ordinary shared-cell mutation, exactly as the living language contract describes for callback captures).

## Actual

`LyraCompilerBugException: compiler invariant failed outside a phase boundary` wrapping the `IllegalArgumentException` above. No `CompileResult.Failure` and therefore no structured diagnostic with a stable code and span.

## Evidence

Measured on baseline `de93506` with a scratch driver that calls the public compiler API:

| Shape | Result |
| --- | --- |
| `(iter (0..3:1) \|\| { n := (+ n 1I64) })` with module-level `@mut n` | compiler-bug exception |
| `::iter[(0..3:1) \|\| { n := (+ n 1I64) }]` with module-level `@mut n` | compiler-bug exception |
| `iter[(0..5:1) \|x\| { n := x }]` with module-level `@mut n` | compiler-bug exception |
| `(while \|\| (< n 3I64) \|\| { n := (+ n 1I64) })` with module-level `@mut n` | compiler-bug exception |
| Same loop with the `@mut` binding declared **inside** the enclosing lambda body | compiles and runs correctly |
| Loop callback that only **reads** module-level state | compiles and runs correctly |
| Direct call of a plain lambda that writes module-level state (`(g)` where `g` writes `n`) | compiles and runs correctly |

So the trigger is specifically a callback-loop callback that writes a module-level mutable binding; the loop's own callback-effect/capture flow facts are certified inconsistently with the event span the validator reconstructs.

## Impact

- A documented, ordinary use of the callback loops (accumulating into module state) cannot be compiled at all.
- Because the failure is an unchecked compiler invariant rather than a structured diagnostic, it also violates the repository rule that expected source failures must not escape as compiler-bug exceptions.

## Status

Open. Discovered in passing while migrating language fixtures for issues #9-#12 on 2026-09-14; independently reproduced on the untouched baseline commit `de93506`, so it is recorded as an unrelated pre-existing defect and is not absorbed into that migration job.

## Next Action

Attribute the loop callback's effect event to its exact source span (or stop inventing a site identity the validator rejects) in `SemanticFlowAnalyzer` loop handling and `SemanticFlowFactValidator.validateEvent`, then add positive assertions for module-level and lambda-local mutable captures in both `iter` and `while`, including one-parameter `iter` callbacks and the bracket spellings.
