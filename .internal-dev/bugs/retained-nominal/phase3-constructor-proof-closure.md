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

Closed for findings 1-4, including a follow-up round after independent
validation proved the first provenance engine partial. Two remaining
provenance holes from that validation (a closure returning captured `self`
laundering provenance, and silent partial publication when the propagation
bound is exhausted) are also closed in the final follow-up, and the last
observed escape family - a self-returning closure installed into a member by
rebind and read back through the member slot - is closed by a member-slot
taint map in the same bounded fixed point.

1. Immutable-`self` provenance is now a conservative forward data-flow property
   computed by one bounded fixed-point engine (`SelfAliasProvenance`) that the
   resolver closing sweep, `ResolvedTopologyValidator` and `IrValidator` all
   consume. Conditional, match and coalesce merges join the provenance of every
   reachable branch; call results carry the union of their target and argument
   provenance (identity-returning calls included) unless the call is a
   construction; the full alias map is iterated to a fixed point with an explicit
   bound of eight passes and stops as soon as a pass adds no provenance. A call
   whose target resolves to a lambda in the analyzed graph additionally carries
   that lambda's body-result provenance, and a lambda expression carries its own
   body provenance, so a closure that returns captured self-derived state cannot
   launder it through its call result, a direct closure alias, a higher-order
   argument, a tuple projection or a field-stored call. The iteration either
   converges or fails closed: when the bound is reached with provenance still
   changing, the resolver emits `LYC-RESOLVE-021` stating that immutable-self
   provenance could not be decided within the bounded analysis, source-mapped to
   the forwarding call site still moving at the bound, instead of publishing the
   partial state. The direct alias, alias-of-alias, tuple-held alias, capture and
   single-call-parameter forms were already rejected in the first round; the
   follow-up closes the branch-merge rebind, two-level callable forwarding and
   identity-call-result bypasses with `LYC-RESOLVE-021`, and the final follow-up
   closes the closure-result and bound-exhaustion bypasses. A member-slot taint
   map closes the rebound-member route: an assignment targeting a member
   declaration with a self-carrying value marks the member self-tainted
   (declaration-keyed, union-merged, monotone, same bound and convergence
   check), and every read of a self-tainted member carries the taint, including
   a call through the member slot, which resolves to the declaration's original
   lambda and would otherwise launder the rebound closure's provenance. The
   direct constructor form, a constructor-local alias, a conditional-merged
   constructor alias, a constructor-local self-returning closure, a
   constructor-installed member value read back inside the exact constructor
   and a member slot holding a fresh-object-returning closure still compile, and
   the constructor writes execute. The resolver publishes its graph with
   topology validation deferred so the closing sweep can emit the structured
   resolver diagnostic before the topology invariant can fire; the topology
   validation still runs on every published graph.
2. `matchesDerivedAggregateResult` now handles `Apply` conversion/narrowing over a
   sequence-local `Reference`, so the rebind-then-tuple initializer certifies at
   tuple route `.0` and the consumer graph seals with the exact cross-module
   array fact.
3. `matchesDerivedAggregateSequence` certifies a rebind value eagerly only when
   the target is a non-root state write; root-binding rebinds update the binding
   map and only the final-result walk certifies. The overwritten intermediate
   allocation no longer certifies; final-result, returned-binding and
   object-field-write positives still do.
4. `matchesDerivedObjectArguments` mirrors `matchesDerivedAggregateArguments`:
   object arguments count only when summary return/write formulas route the
   parameter to the destination. Ignored arguments mint no identity; returned
   and written arguments still certify.

Regression tests: `NominalBytecodeTest.selfAliasAggregateMutationsRequireTheExactConstructor`,
`branchMergedAliasesJoinEveryBranchProvenance`,
`twoLevelCallableForwardingRejectsMutationThroughForwardedSelf`,
`identityCallResultsCarrySelfProvenance`,
`closureCallResultsCarrySelfBodyProvenance`,
`boundedSelfProvenanceAnalysisFailsClosedOnDeepForwarding`,
`constructorCallResultAliasesStayLegalInsideTheExactConstructor`,
`ordinaryMutableAggregatesAndAliasRebindsKeepTheirBehavior`,
`reboundMemberSelfClosuresTaintTheMemberSlot`,
`constructorInstalledMemberValuesStayLegalInsideTheExactConstructor`,
`freshObjectClosureMemberSlotsStayLegal`;
`RetainedNominalFlowCertificateTest.rebindThenTupleRetainedInitializerCompilesWithExactMemberRouting`,
`derivedCertificationRejectsOverwrittenRebindAllocations`,
`derivedCertificationKeepsReachableRebindPositives`,
`derivedObjectCertificationRequiresReturnOrWriteDestinations`. Each self-alias
bypass regression fails against the previous single-pass engine (verified by
temporarily restoring it) and each certificate regression fails when its fix is
reverted. The final follow-up regressions (`closureCallResultsCarrySelfBodyProvenance`
and `boundedSelfProvenanceAnalysisFailsClosedOnDeepForwarding`) both fail with
`CompileResult.Success` against the pre-follow-up engine and pass with the
closure-result and fail-closed rules in place. The member-slot regressions
(`reboundMemberSelfClosuresTaintTheMemberSlot`) compile and execute with
observed result 7 against the engine without the member-taint map (probe-run
before the fix: repro, tuple projection and different-instance shapes all
executed with result 7) and are rejected with `LYC-RESOLVE-021` mapped to the
mutation once the member map joins the fixed point, while
`constructorInstalledMemberValuesStayLegalInsideTheExactConstructor` and
`freshObjectClosureMemberSlotsStayLegal` compile and execute both before and
after.

Residual limitations (documented, not defects in the requested scope): rebinding
a parameter away from self and then mutating through it is rejected by the
flow-insensitive final map even though the post-rebind value is not self;
retained-module callables carry only target/argument provenance because their
bodies are not walked, so state laundered through retained module storage is
not traced (nominal class identity also prevents a retained module from naming
a caller's self type, so no executing laundering shape was found). The final
follow-up closes the rebound-member closure laundering escape: a declaration-keyed
member-taint map joins the same bounded fixed point, so an assignment targeting
a member declaration with a self-carrying value (a closure whose body-result
provenance is non-empty, a direct self alias, or an already-tainted value, through
`recv:.member` or an element write through a member aggregate) marks the member
self-tainted, and every read of the member - a value read or a call through the
member slot - carries the taint. The rule is flow-insensitive by design: a
member tainted by any assignment is treated as self-derived on every instance
and every read, so the precision cost is that reads of an assigned-to
self-returning member slot are over-rejected outside the exact constructor even
when a flow-sensitive analysis could prove the stored closure returns a fresh
object on a particular path; the over-approximation only rejects mutation
sites. Contextual replacement `self` declarations are recognized by their exact
nominal schema contract so constructor-installed closures stay legal inside the
exact constructor. Conditional branches that contain rebinding
assignments still hit the pre-existing typed-flow summary-sequence invariant in
member lambdas; the replacement-lambda and match regressions cover the
branch-merge rule without touching that unrelated flow limitation, and
constructor member-callable calls remain a separate pre-existing runtime
limitation (executing a closure that captures an uninitialized nominal self
during construction still raises LYR-INIT; the constructor-installed closure
positive asserts compilation and the executing constructor positives use an
aggregate read through the member slot).

Validation state: the member-slot follow-up passed `mvn -q -DskipTests install`;
the focused compiler set (`NominalBytecodeTest`,
`RetainedNominalFlowCertificateTest`, `SessionCompilerTest`,
`SessionClosureAuthorityTest`, `SessionFailureFlowTest`) — 92 tests;
`mvn -pl lyra-runtime test` — 42 tests; the `lyra-repl` selection
(`NominalSessionTest`, `ApplicationAttachmentTest`, `ModuleReloadTest`) —
99 tests; and the full reactor `mvn test` — runtime 42, compiler 1214,
REPL 333, CLI 66, editor 24 with 2 graphical skips, 0 failures. The bounded
fuzz baselines ran inside the reactor and the extended deterministic campaign
(`tools/fuzz-language.sh`, 1,800 cases per seed) also passes. Issue #7 remains
open and separate; `SessionStorageDomain.Linkage.sourceLocal` and the four
pinned `LYR-LINK` inventory cases in `NominalSessionTest` are unchanged and
still assert the structured failure.

### Aggregate-origin provenance follow-up

A later senior escalation reproduced one remaining class-wide escape: a mutable
member aggregate copied into a local (or projected from a tuple) lost the member
declaration before an element write installed a self-returning closure. Reading
that member back then returned the captured receiver and allowed an ordinary
method to mutate the original receiver's aggregate. The direct reproducer
compiled and executed as `7` before this follow-up.

`SelfAliasProvenance` now computes one finite declaration-keyed value-position
lattice instead of only a flat self set. Each value joins four bounded fact sets:
direct receiver aliases, receiver-backed storage, originating nominal member
declarations, and known lambda identities. Lambda bodies retain separate result
facts; calls materialize those results from callable identities. Member reads add
the member declaration, and both assignment spellings weakly update every member
origin reachable from the target. Writes into local aggregates also weakly update
the aggregate declaration, so another array/tuple/local/call/member hop cannot
erase the back-reference. Branches, rebinds, calls and member stores use only
union joins. Opaque/retained call results conservatively mark every self-aliasing
target or argument as a possible source of self-backed storage; available bodies
can add facts but cannot erase that route. Fresh nominal construction still returns
bottom after propagating arguments into constructor parameters. The existing eight-pass convergence bound
and resolver fail-closed `LYC-RESOLVE-021` path remain unchanged.

The aggregate shape is deliberately collapsed to the union of all reachable
positions. This is sound but instance- and sibling-insensitive: if one tuple/array
position or one instance may retain a member back-reference, every projection of
that aggregate and every instance read of that member may carry it. Rebindings
are weak updates, so a declaration is not cleared after it once held a tainted
value. These losses can reject legal code but cannot erase a route to `self`.

`NominalBytecodeTest.aliasedMemberAggregatesPreserveOriginTaint` covers the direct
local escape, mandatory tuple projection, an array populated by element write,
call-parameter forwarding, member-to-member forwarding, and prefix assignment.
All reject at the eventual `alias:.values[0]` mutation with
`LYC-RESOLVE-021`. An adversarial follow-up then found and reproduced one more
position-loss shape, `other:.values := self:.values; other:.values[0] := 7`,
where checking only the fresh root declaration `other` missed the routed member
storage. The lattice now retains a provenance fact for each concrete mutation
target span and joins that position with the root declaration during validation;
`memberStorageTransfersPreserveTheMutationPosition` failed before that addition
and now rejects at `other:.values[0]`. Existing constructor and non-self positives continue to
execute: direct/local constructor writes produce `[7,2]` and `[8,4]`;
conditional constructor aliases produce `[7,8]` and `[9,4]`; a
constructor-installed aggregate read produces `[7,8]` through both fields;
ordinary mutable aggregates return `16`; and a fresh-object-returning member
closure read across a different instance returns `7`.

The independent typed-provenance invariant is also repaired:
`TypedSemanticProvenance.syntaxType` now resolves `SyntaxNode.NamedType` through
the resolver-issued exact `TYPE`/declaration link. The explicit
`Array<Fn<;Box>>[(=> || Box[])]` regression compiles and executes as `7` instead
of raising `unknown syntax type` outside a phase boundary.

Validation after this follow-up: skip-test reactor install passed; the requested
compiler selection passed 95 tests; runtime passed 42; the selected REPL suites
passed 99; and the full reactor passed with runtime 42, compiler 1217, REPL 333,
CLI 66, and editor 24 with two graphical skips. The extended fuzz campaign was
left to the root agent as requested. `SessionStorageDomain.Linkage.sourceLocal`
and the four issue-#7 `LYR-LINK` inventory cases remain unchanged.

One requested legal-positive qualification cannot be represented as a successful
runtime assertion inside the granted write boundary. Both a constructor-local
identity closure passed incomplete `self` and a constructor-local closure
returning captured `self` compile, but executing either during construction fails
with `LYR-INIT: nominal object is not fully initialized`. That is the existing
runtime construction-authority rule, not a provenance rejection. Changing it
would require runtime production design/edits, which this escalation explicitly
forbade; no validator or initialization rule was weakened.

## Next Action

Compiler-side aggregate-origin provenance and explicit nominal literal typing are
closed with regression coverage. If constructor-local invocation of closures that
receive/return incomplete `self` is intended to execute, authorize a separate
runtime construction-capability design across the compiler/runtime boundary.
Issue #7 must not be addressed by relaxing importing-graph authority. Root-agent
diff review, extended fuzz qualification, records and Git ownership remain.
