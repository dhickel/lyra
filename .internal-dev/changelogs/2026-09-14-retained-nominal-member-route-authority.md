# Retained nominal member-field route authority (issue #7)

## Date

2026-09-14

## Git Commit

f0ce3ebda841eb46b201789b78714c19a40fa54b (baseline HEAD; this changelog describes uncommitted Tranche 3 issue-#7 work built on the same worktree as the uncommitted issue-#8 nilable member-read contract fixes)

## Change Summary

Implements issue #7: retained nominal member-field delegation with
route-specific authority and selected-closure identity, while leaving `SessionStorageDomain.
Linkage.sourceLocal` and the general same-session/importing-graph callable
authentication untouched.

- Session-constructed nominal objects anchor their ownership to the exact
  session identity at construction (`SessionStorageDomain.NominalAnchor`:
  standalone domain/epoch or explicit root lifetime).
  `LyraNominalObject.checkOwnership` accepts a generated caller through the
  anchor after the exact object/schema/producer/owner/lifecycle checks; the
  anchor never widens callable `sameSession`, never grants private access,
  and re-checks active epoch/root lifetime on every use.
- Callable-typed generated member reads that cross artifact boundaries
  without the source-local bridge now return an occurrence-scoped route
  delegate: a per-member generated shared structural class
  (`$lyra$delegate$<nominal-hash>$<index>`, session-only, validated by
  `SessionTypeLoader` as a new structural kind) extending
  `LyraNominalMemberDelegate`, implementing the member's exact function
  interface and carrying the authenticated source object, the
  compiler-certified field index and the exact value selected by that read.
  Same-artifact and source-local same-session reads keep returning the exact
  stored closure, preserving identity (`eq?`) and existing behavior.
- `LyraClosureSupport.requireAuthenticated[ForGeneratedInvocation]` recognize
  the delegate. A selected delegate is flattened to one raw closure plus an
  immutable identity-deduplicated list of exact source/index/signature
  dependencies; claim, snapshot, authentication and direct invocation validate
  that list iteratively, preserving every producer/root/epoch obligation
  without field rereads or recursive chains. The raw imported closure presented
  directly stays rejected before and after delegated use.
- Generated callable-field setters authorize the exact writable field route and
  authenticate replacements under the actual generated caller (or the exact
  nominal producer already owning a raw value), then store the field's generated
  delegate. Consumer-created lambdas replace retained fields legally, while
  private/immutable/wrong-field/signature/root routes and retired replacement
  producers reject. Saved selections remain old and new reads select the
  replacement.
- Route wrappers do not create language-level function identities.
  `LyraClosureSupport.sameIdentity` compares only the selected raw
  `LyraClosureIdentity`, returning no callable and granting no authority.
  Repeated unchanged-slot reads and delegate/raw occurrences compare true;
  replacement changes new-read identity. Nominal leaves ride on the object
  anchor without wrapping.
- Function parameter authentication and Java facade callable arguments now
  preserve the authenticated occurrence: the single existing
  `requireAuthenticated...` call runs over a duplicate and the original value
  stays in the parameter slot, so route evidence survives parameter/return
  propagation instead of being replaced by the helper's returned raw closure.
- `SnapshotReader` recognizes delegates for bounded function snapshots via a
  non-invoking `checkUsable` route check and compares selected closure identity
  for aliases, rather than treating fresh per-occurrence wrappers as distinct
  language-level functions.
- Direct callable member reads inside lambda invocation/return summaries now
  retain an exact non-root declaration projection, defer it to caller time,
  and recover the callable only through the certified object/schema/field
  route. Direct capture, direct return and direct `::` invocation no longer
  escape `MISSING_CALLABLE_FACT`.
- The four pinned issue-#7 Unit/intrinsic inventory cases flip together:
  namespace member callable, namespace direct-call Unit, iter Unit and while
  Unit now observe exact Unit values across declaration, construction and
  later observation with exactly-once construction effects; the
  namespace-member case actually invokes the retained callable.
  `RetainedNominalModel` pinned-unit profile and the fuzz worker's Unit
  oracle follow.

## Files

- `lyra-runtime/.../LyraNominalMemberDelegate.java` (new): occurrence-scoped
  delegate base over one raw closure and flat exact route dependencies.
- `lyra-runtime/.../LyraNominalObject.java`: construction-time
  `NominalAnchor`, anchor-aware ownership, flat route normalization/validation,
  exact callable write-route issuance, and read-delegation selection.
- `lyra-runtime/.../SessionStorageDomain.java`: `NominalAnchor` capability,
  package-private `Linkage.domain()/epoch()`.
- `lyra-runtime/.../LyraOwnershipToken.java`: package-private linkage accessor.
- `lyra-runtime/.../LyraClosureSupport.java`: delegate recognition in both
  authentication entry points plus non-authorizing selected-identity comparison.
- `lyra-runtime/.../SessionTypeLoader.java`: `$lyra$delegate$` shared
  structural kind with two-pass exact name/hash/index, same-domain nominal/
  function route and member-shape validation; delegates skip eager standalone
  verification like nominals (hierarchy-aware checks run at definition time).
- `lyra-repl/.../SnapshotReader.java`: delegate function snapshots with
  selected-closure alias/reference tracking.
- `lyra-compiler/.../backend/jvm/GeneratedClassKind.java`,
  `GeneratedMemberKind.java`, `GeneratedDependencyKind.java`,
  `GeneratedMemberPlan.java`, `GeneratedClassPlan.java`: new
  `NOMINAL_MEMBER_DELEGATE` class/member kinds, shape and visibility rules.
- `lyra-compiler/.../backend/jvm/NominalMemberDelegateLayout.java` (new),
  `GeneratedTypePlanner.java`, `GeneratedTypePlan.java`, `JvmAbiParity.java`,
  `JvmTypeNameTable.java`: session-only delegate planning, name derivation,
  inventory/dependency validation and parity expectations.
- `lyra-compiler/.../backend/jvm/JvmBytecodeEmitter.java`: delegate class
  emission, route-delegated generated getter and exact callable-setter wraps
  (checkcast-retagged before joins so stack-map generation never resolves
  generated identities), route-aware function identity, and occurrence-
  preserving parameter/facade argument authentication.
- `lyra-repl/src/test/java/.../NominalSessionTest.java`: flipped four-case
  inventory with exact Unit values/effects/invocation, plus
  anti-laundering before/after, routed/raw aggregate coexistence, saved/
  captured/parameter/returned/rebound/later-submission propagation, direct
  lambda capture/return/invocation summary recovery, original-
  producer and consumer-lambda replacement with saved selection, selected-
  closure `eq?`/snapshot identity, nested aggregate leaves stay raw, private
  member stays private.
- `lyra-compiler/src/test/java/io/mindspice/lyra/runtime/
  NominalMemberDelegateAuthorityTest.java` (new): runtime-boundary exact
  object/route, raw-before/after, wrong signature, foreign session,
  private/immutable writable-route and retired-epoch negatives; a 10,000-step
  alternating two-object read/reassign chain stays flat with two dependencies,
  and retiring either route source or the replacement producer rejects;
  source-local producers keep exact stored values without delegation.
- `lyra-compiler/src/test/java/.../api/SessionCompilerTest.java`: session
  artifacts emit the exact delegate class inventory for callable nominal
  members; AOT artifacts do not.
- `lyra-compiler/src/test/java/.../conformance/RetainedNominalModel.java`,
  `LanguageFuzzWorker.java`, `FuzzInfrastructureTest.java`: flipped
  pinned-unit expectations to Unit values with one real invocation; Unit
  oracle support; wording updates.
- `.internal-dev/specifications/{backend-runtime,repl,language-core}.md`:
  nominal session/root anchor and occurrence-scoped route-delegation
  contracts; removed the tracked issue #7/#8 open-limitation paragraph.
- `lyra-compiler/.../semantic/flow/{CallableSummarySolver,CallableSummarySet}.java`
  and `semantic/SemanticFlowAnalyzer.java`: defer exact non-root callable
  declaration projections and recover them only through canonical caller-time
  object/route resolution.
- `.internal-dev/knowledge/nominal-session-linkage.md`: anchor/delegate
  design, sourceLocal interaction, parameter-preservation and exact direct
  retained-member callable-summary recovery rules.
- `.internal-dev/bugs/retained-nominal/unit-initializer-later-observation-linkage.md`:
  Status fixed with test references; historical diagnosis preserved.
- `.internal-dev/bugs/retained-nominal/lambda-invoked-retained-member-callable-summary.md` (new):
  records the pre-existing `MISSING_CALLABLE_FACT` defect discovered during
  issue #7 work and its in-tranche exact-route repair; GitHub issue #15 remains
  open pending commit evidence.
- `docs/language-testing.md`: retained coverage rows updated for the flipped
  inventory, delegate inventory and authority negatives.

## Behavioral Impact

- Retained nominals declared by importing (non-source-local) graphs can now
  be observed and, for callable members, invoked from later generations
  through exact route evidence. Unit/intrinsic member values, object
  observations and member values are unchanged otherwise.
- Cross-generation callable use through the general session bridge is
  unchanged: `sourceLocal` classification, `SessionClosureAuthorityTest`
  assertions and raw imported closure rejection are preserved.
- Selected-closure identity: repeated delegated reads of one unchanged member
  and routed/raw occurrences of the same closure are identical under `eq?`;
  wrapper allocation carries authority only. A saved occurrence survives field
  replacement with its old identity, while a new read observes the replacement.
  Source-local same-session reads keep returning the exact stored closure.
- AOT artifacts are byte-inventory unchanged (no delegate classes); session
  artifacts gain deterministic shared delegate classes for callable members.
- Function parameters/facade arguments preserve the authenticated occurrence
  rather than replacing it with the raw closure; foreign SAM rejection and
  existing authentication counts are unchanged.

## Specification Impact

`backend-runtime.md`, `repl.md` and `language-core.md` now specify the
nominal session/root ownership anchor and the occurrence-scoped route
delegation contract; the tracked issue #7/#8 open-limitation paragraph is
retired.

## Validation

Focused validation only, as required for this uncommitted tranche work:

- The senior repair's explicit selector excluding all prohibited fuzz classes:
  `JAVA_HOME=/usr/lib/jvm/java-25-openjdk mvn -pl lyra-repl -am test -Dtest=RuntimeFoundationTest,NominalConstructionTest,LegacySchema1EncodingTest,GeneratedTypePlannerTest,SessionTypeAdmissionTest,SessionCompilerTest,NominalBytecodeTest,NilableMemberReadContractTest,CallableSummaryTest,NominalMemberDelegateAuthorityTest,SessionClosureAuthorityTest,NominalSessionTest -Dsurefire.failIfNoSpecifiedTests=false`
  — PASS, 254 tests (runtime 16, compiler 157, REPL 81). This includes the
  independent snapshot-alias regression added during validation.
- Independent final validation reran the retained-member selectors with no
  failures; its reported aggregate varied from the reproducible selector count
  because stale Surefire inventories were present, so that aggregate is not
  used as the tranche's test count.
- `git diff --check` — PASS.

An earlier delegated validation command accidentally included
`FuzzInfrastructureTest` (15 tests), and another module-scoped command
unintentionally ran `SessionStateFuzzTest` (3 tests); both passed. These were
execution-policy mistakes, not intentional fuzz qualification, and no further
fuzz test or campaign is being run in this tranche. No full Maven suite, UI
suite or Phase 23/24/release gate was run.

## Risks

- Runtime ABI compatibility metadata now rejects a newer 1.1 session artifact
  on an older 1.0 runtime before class loading; runtime 1.1 continues to accept
  older 1.0 artifacts.
- Callable leaves inside aggregate members remain raw and rejected from
  non-authenticated generations by design; exact readable/writable field routes
  are the only delegated shape. The boolean identity helper deliberately
  performs no route/lifecycle checks, so invocation/storage boundaries must
  continue authenticating independently.
- Direct lambda-body invocation/return recovery now relies on exact non-root
  declaration projections and caller-time certified object/schema lookup; any
  future summary generalization must preserve those fail-closed restrictions.
- Snapshot alias tracking for delegated occurrences was independently checked
  after implementation: wrapper identity is not logical function identity, so
  repeated routed reads in one aggregate render a reference to the first
  selected closure while retaining the existing pre-render route check.

## Follow-up Items

- Archive the issue #7 bug record after commit (workflow contract).
- Synchronize and close GitHub issue #15 only after commit evidence is
  available; its local record remains active until then.
- Extended fuzz campaign (`tools/fuzz-language.sh`) and Phase 23/24 gates
  remain for the completed tranche's release qualification; they were
  intentionally not run for this issue-#7 unit.
