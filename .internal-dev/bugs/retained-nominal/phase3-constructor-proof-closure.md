# Phase-3 constructor proof closure is incomplete

## Summary

Independent review found that the current phase-3 constructor fixes authorize immutable-`self` aggregate mutations outside constructors, fail to recurse through nested retained constructor summaries under the exact nested object context, and may certify discarded aggregate allocations by subtree membership rather than destination reachability.

## Scope

Compiler topology/IR validation, retained semantic-flow execution, and `SessionFlowCertificate` aggregate/object derivation checks. Runtime authority issue #7 is separate.

## Reproduction

1. In an ordinary method or contextual replacement, mutate `self:.values[0]`; validation admits the mutation, then semantic flow raises `LyraCompilerBugException` with `INCONSISTENT_SUMMARY: object projection was not resolved against caller heap`.
2. Retain `Outer`, construct nested `Leaf`, and have the `Leaf` constructor install a fresh array or `Deep[]`; consumer compilation raises `LyraCompilerBugException` with `aggregate owner module is foreign` or `object allocation site is foreign and uncertified`.
3. In a retained initializer block, create and discard one array before returning a second; `certifiesDerivedAggregate(...)` accepts the discarded identity.

## Expected

Only exact constructor-owned `self` receives the immutable-root mutation exception. Nested constructors execute and certify under their exact derived object context. Certification accepts only allocations reachable at the proven result/state destination and route.

## Actual

The current partial phase-3 implementation is broader for `self` and shallower/less destination-sensitive for retained constructor derivation than those contracts require.

## Evidence

A read-only independent review reproduced all three cases with `/tmp`-only compiler probes after the focused and full suites passed. The findings identify `ResolvedTopologyValidator` around the immutable-root exception, `IrValidator`'s mirrored check, `SemanticFlowAnalyzer` retained-construction context management, and recursive transfer/summary traversal in `SessionFlowCertificate`.

## Impact

Phase 3 cannot be claimed complete. Valid nested retained constructors can fail with internal compiler exceptions, while unsupported method/contextual-self mutations and unreachable allocations can be admitted by proof checks.

## Resolution

The worktree fix now:

- rejects non-field mutation through immutable `self` unless the root reference belongs to the exact constructor lambda for that nominal, while retaining the established direct member-field case;
- installs every retained nested object site as the active construction context during its factory execution and recursively checks nested constructor summaries under that exact derived context;
- carries explicit statically reachable conditional-result branches and follows only sequence results, exact shadowed bindings and writes when certifying derived aggregate destinations;
- replaces existential tuple/object checks with exact destination-field, type, identity, route and witness assertions; and
- pins retained producer runtime frames to exact UTF-16 spans and one-based line/column positions.

The retained array producer scope/span/origin-site preservation remains unchanged. Runtime factory authority remains on the authenticated producer path. `SessionStorageDomain.Linkage.sourceLocal` and the four issue-#7 `LYR-LINK` inventory cases were not changed.

## Status

Partially fixed and checkpointed as WIP in the uncommitted worktree. Item 2 (nested retained construction contexts), item 4 (exact certificate assertions) and item 5 (exact producer source maps) are confirmed fixed by independent validation. Items 1 and 3 are partial, and independent validation reproduced four remaining proof-closure findings:

1. High: a mutable alias of immutable `self` bypasses the constructor-only narrowing. `let @mut alias :Box = self` followed by `alias:.values[0] := 7` compiles and mutates the array, even though the direct form is now rejected with `LYC-RESOLVE-021`. The immutable-`self` provenance must propagate through aliases and be rejected unless the exact constructor lambda owns the root, consistently in the resolver, topology and IR validators.
2. High: a valid retained initializer of the form `{ let @mut selected :Array<I32> = first  Tuple[selected 1] }` fails consumer compilation with `LyraCompilerBugException` (`aggregate owner module is foreign`). The post-rebind aggregate identity must survive conversion and tuple-member routing so the final destination fact validates.
3. Medium: an intermediate rebind allocation that is overwritten before the final result is still certified. Only final-result or genuinely escaped/state-reachable writes should mint certified identities.
4. Medium: a nominal object passed to a callable that ignores it and returns a fresh object is falsely accepted by derived-object certification. Object-argument traversal must be return/write-formula and destination-route aware.

Validation state: the full reactor is green (runtime 42, compiler 1199, REPL 333, CLI 66, editor 24 with 2 graphical skips, 0 failures) on the WIP checkpoint. Issue #7 remains open and separate.

## Next Action

Close findings 1-4 above with regression tests, then revalidate. Issue #7 must not be addressed by relaxing importing-graph authority. Root-agent diff review, records and Git ownership remain.
