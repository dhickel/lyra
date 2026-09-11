# Summary

A mutable local function that reassigns its own binding is not seeded in the
callable summary's symbolic state. Compilation throws a compiler invariant.

# Scope

General mutable function self-reference; independent of the callback-loop forms.
Discovered while testing replacement of a selected while callback. Replacement
from a separate predicate is supported and remains covered by the loop tests.

# Reproduction

```lyra
let @pub run :Fn<;I32> = (=> || {
  let @mut n :I32 = 0
  let replacement :Fn<;Unit> = (=> || { n := (+ n 10) })
  let @mut action :Fn<;Unit> = (=> || { n := (+ n 1) action := replacement })
  ::while[|| (< n 3) action]
  n
})
```

# Expected

Compilation succeeds; the selected original action runs three times, returning 3.

# Actual

LyraCompilerBugException caused by INVALID_TYPED_EXPRESSION:
`rebind target has no current symbolic value` in CallableSummaryCompiler.rebinding.

# Evidence

Observed during CallbackLoopIntegrationTest on 2026-09-11. Shared-cell writes
from a different lambda compile and execute; the missing state is the self binding.

# Impact

Before the fix, self-rebinding function bodies could not be compiled. The fix
preserves loop ownership/flow checks and the ordinary recursive self-reference path.

# Status

Resolved 2026-09-11; GitHub mirror https://github.com/dhickel/lyra/issues/5 closed.
Related all-state issue search before creation returned no matches.

# Next Action

No open action. Rebinding targets now receive shared-cell captures, while ordinary
recursive self references retain their existing representation. Lazy self-cell
construction seeds the callable identity before building its captures.
CallbackLoopIntegrationTest proves the original selected callback identity both
directly and through an enclosing function. CallableSummaryTest's escaped-local
recursive-function regression remains passing. Full `mvn -q test` passed after
the fix; extended callback-loop execution tests also passed.
