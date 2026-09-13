# Retained nominal struct/class factory finalization review

## Scope

Independent completion review of the retained nominal struct/class factory work
in plan `20260912-180414-complete-retained-nominal-struct-class-factory-semantics`,
phases 1-4, delivered in commits `432121b` (phase 1 closed initializer
transfer), `c6f4274` (phase 2 expression algebra and fresh provenance),
`df6e1d5`/`2c3602a` (phase 3 constructor/runtime integration and proof
closure), and `2b49f7c` (phase 4 independent campaigns), on baseline HEAD
`2b49f7c` with a clean worktree. This review verifies the delivered behavior
against the code, recorded per-phase evidence, the campaign evidence, and the
two documented open limitations (issues #7 and #8). It is not a substitute for
final qualification: the plan's final command sequence must still be run on the
final commit, and the Phase 24 release audit remains the release gate.

## Findings

**What is proven, with code paths:**

- The closed retained-initializer transfer algebra exists exactly as described:
  `SessionFlowCertificate.RetainedInitializerTransfer` is a sealed interface
  with variants for literals (`Value`), references (`Reference`, declaration
  identity plus routed projection), lambdas (`Lambda`), direct/namespace calls
  (`Call`) and callable-value calls (`CallableCall`), composites (`Composite`),
  applies (`Apply` with OPERATOR/SHORT_CIRCUIT/CONVERSION/NARROWING/RANGE
  kinds), alternatives (`Alternative` with CONDITIONAL/COALESCE/MATCH kinds,
  explicit prefix `Step`s and independent branches), sequences (`Sequence`,
  `Declare`, `Rebind`), projections/indexing/length (`Project`), nested
  construction (`Construct`/`ConstructionSite`), loops (`Loop`), and
  allocations (`ArrayAllocation`). `RetainedNominal` enforces exact member
  coverage: the inventory must match the schema member count, per-member
  initializer presence, implicit-convertible types, nominal name and
  constructor identity. Issuance validates kinds, operators, child types,
  routes, schemas, index contracts and provenance, and rejects forged,
  mismatched, missing, duplicate or foreign-generation evidence
  (`RetainedNominalFlowCertificateTest`, 44 tests).
- Consumption reuses ordinary callable-summary machinery: `SemanticFlowAnalyzer`
  evaluates each transfer through the ordinary `invokeCandidate` path with
  ordered evaluation, target-before-arguments selection, left-to-right
  argument effects, shared-cell refresh, transferred writes/binds/effects and
  failure prefixes; only synthetic internal call events stay unpublished as
  typed root boundaries. Initializer shapes outside the closed algebra fail
  issuance with `LYC-SESSION-001` at the member span
  (`SessionFlowCertificate.retainedInitializerDiagnostic`); no currently legal
  form is unrepresented, so the guard is now a safety net, and valid source
  does not escape as `LyraCompilerBugException`.
- Certification is exact: route-exact object/aggregate/callable proofs
  (`ObjectProofKey`, `RouteProof`), exact nil provenance by site and span,
  consumer-scoped fresh-allocation provenance via
  `RetainedAllocationDerivation` with tagged disjoint identity domains,
  destination-sensitive aggregate and object certification (return/write
  formulas; overwritten and unreachable allocations mint no identity),
  predecessor transfer continuity, and IR link validation aligned with the
  semantic gate (`certifiesLinkedCallable`, route-blind by design).
- Constructor/runtime contracts hold: constructors stay Unit-returning and the
  construction expression returns the instance; nested retained construction
  recurses under the derived object context; ordered argument/default/
  constructor execution, nontransactional failure and cancellation with no
  staged publication, producer recovery, exactly-once root/default
  initialization (`JvmBytecodeEmitter` restricts root binding initialization
  to `SESSION_EXECUTE`), attached-root factory invocation from Lyra and Java
  (`ApplicationAttachmentTest`), and exact producer source maps are tested.
- The immutable-`self` mutation-authority rule is implemented as one bounded,
  monotone, flow-insensitive provenance lattice (`SelfAliasProvenance`,
  eight-pass bound) shared by the resolver closing sweep,
  `ResolvedTopologyValidator` and `IrValidator`; violations are structured
  `LYC-RESOLVE-021` and non-convergence fails closed rather than publishing
  partial provenance. The deliberate precision cost (collapsed sibling
  positions, declaration-wide instance-insensitive member taint, never-cleared
  may-alias facts, unwalked opaque retained bodies, fail-closed deep chains)
  is documented in the specifications, not hidden.

**Validation evidence:**

- Phase 1 (`432121b`): the previously red
  `retainedClassFactoriesPreserveCallableReturningDefaultCalls` passes and the
  whole `NominalSessionTest` suite (15 tests at that point) passes; transfer
  continuity, fabricated-placeholder removal and certification exactness are
  pinned by new certificate tests.
- Phase 2 (`c6f4274`): deep issuance validation with negative forgery tests;
  every `TypedExpressionKind` legal in an initializer is classified with
  explicit transfer, justified reduction, or an existing language-level
  prohibition; `RetainedAllocationDerivation` distinguishes construction
  sites, generations and nested invocation nodes.
- Phase 3 (`df6e1d5`, `2c3602a`): four independent review rounds drove the
  proof closure recorded in `bugs/retained-nominal/phase3-constructor-proof-closure.md`
  (closed). Recorded validation: focused compiler set 92 tests, runtime 42,
  selected REPL suites 99, full reactor runtime 42/compiler 1,214/REPL 333/
  CLI 66/editor 24 with 2 expected graphical skips and 0 failures; the
  extended campaign (`tools/fuzz-language.sh`, 1,800 cases per seed) passed.
  The follow-up rounds passed runtime 42/compiler 1,217/REPL 333/CLI 66 and
  the same editor count.
- Phase 4 (`2b49f7c`): `RetainedNominalModel` drives real three-generation
  session submissions (compile, load, link, prepare, execute, stage, commit)
  over 47 operation names across ten deterministic profiles with an
  independent plain-Java oracle; `SessionStateFuzzTest` gained retained
  construction, identity, composite, effect-ordering, failure-publication,
  recovery and reset coverage; `FuzzInfrastructureTest` enforces the 120-case
  minimum balanced across all twelve families, per-seed coverage of every
  retained profile and operation, issue-#7 pinning and replay round-trips;
  `tools/fuzz-language.sh` runs both models in one invocation (compiler
  cases=1800 over four seeds, session three seeds at steps=240) and fails if
  either model fails. Phase 4 also closed two retained-path crashes on valid
  source (nilable-element array indexing and composite prefixing) and logged
  issue #8.
- Sprint-planner per-phase validator verdicts for phases 1-4 are runtime
  records of the sprint-planner extension (its run state is temporary by
  design and is removed after successful runs; `.internal-dev/sprints/` is
  empty). The durable in-repository per-phase evidence is the commit messages
  and the phase changelogs cited above, which this review re-verified against
  the code.

**Work in progress:** none. Phases 1-4 are committed and the worktree was
clean at review start. This review phase (phase 5) reconciles the records;
final qualification remains to be run on the final commit.

**Open limitations (documented, not hidden):**

- Issue #7 (`bugs/retained-nominal/unit-initializer-later-observation-linkage.md`):
  observing a retained nominal whose member initializer is Unit-typed or
  imports the intrinsic module one generation after construction fails at
  runtime with `LYR-LINK` from `LyraNominalObject`; the four inventory cases
  remain pinned as structured-failure assertions in `NominalSessionTest`. The
  accepted fix direction anchors generated nominal-instance ownership to the
  session root; `SessionStorageDomain.Linkage.sourceLocal` must not be
  relaxed (`SessionClosureAuthorityTest` pins the importing-graph posture).
- Issue #8 (`bugs/retained-nominal/nilable-member-read-contract.md`):
  annotated retained nilable-member reads and nil-contract-consuming forms
  over member reads are rejected at the IR boundary with `LYC-IR-003`; the
  bare read path works. Fail-closed and structured; no wrong value is
  produced.
- Final qualification (the plan's full command sequence, including the Phase
  24 release audit on the final commit) has not yet been run for this job and
  is a root-agent follow-up.

## Risk Assessment

- The transfer algebra is closed for today's source-admissible initializer
  forms. Any future syntax change must extend the algebra and its coverage
  inventory together, or valid source would regress to `LYC-SESSION-001`;
  the fail-closed guard makes that regression structured rather than silent.
- `certifiesLinkedCallable` is route-blind IR-link evidence by design; using
  it as semantic route authorization would admit forged routes. The semantic
  flow validator remains the authoritative route-exactness gate.
- The immutable-`self` provenance over-approximation rejects legal code in
  documented shapes (collapsed sibling positions, declaration-wide member
  taint, non-cleared rebinds, deep alias chains). It cannot erase a route to
  `self`; the cost is precision, accepted by the recorded decision.
- Issue #7 remains a real cross-generation linkage gap for Unit/intrinsic
  member initializers. It is fail-closed and confined to observation of such
  nominals; construction and producer compilation succeed. Issue #8 rejects
  annotated nilable member reads at the IR boundary; both gaps are pinned by
  tests so they stay visible.
- Retained fuzz cases add roughly 50 percent to fuzz runtime (about 45
  seconds bounded, about 220 seconds extended), within the documented
  budgets. The fuzz worker mirrors `LyraSession`'s requirement and factory
  derivation; a future protocol change must update both.

## Recommendations

- Proceed with final qualification exactly as the plan requires: run the full
  command sequence on the final commit, inspect Surefire totals, the audit
  matrices and the campaign summaries rather than exit codes alone, and
  re-run the Phase 24 audit after any final changes.
- Accept this reconciliation as the phase-5 closeout evidence once the
  validation results in this review's run are confirmed against the final
  commit and the root agent has committed the records.
- For issue #7, design the route-scoped nominal-member/session-root authority
  across generated member access and runtime closure authentication as a
  separate authorized change; do not ship the `sourceLocal` relaxation or the
  nominal-only same-workspace predicate.
- For issue #8, derive the nilable member contract at the IR/flow boundary
  for member reads and add positive and negative coverage in the nominal
  semantic and session suites; document the annotated-read form in
  `language-core.md` when it lands.

## Follow-ups

- Fix issue #7 and flip the four pinned `LYR-LINK` inventory cases to value
  assertions in `NominalSessionTest` and the retained fuzz model together.
- Fix issue #8 and flip its rejected forms to positives with the derived
  contract, then extend the nilability matrix in `docs/language-testing.md`.
- Run the plan's final qualification sequence on the final commit, then
  re-run `tools/phase24-release-audit.sh` and record the verdict as the final
  qualification evidence in the root-agent changelog.
- Any future initializer syntax must update the transfer algebra, the
  `TypedExpressionKind` legality inventory, the retained fuzz profiles and
  `docs/language-testing.md` in one change.
