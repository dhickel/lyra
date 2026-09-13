# Context

The user requested complete struct/class syntax, semantics, direct Java 25
bytecode support, runtime behavior, and persistent-session support. Work must
continue until both nominal forms are complete; passing a narrow happy path is
not sufficient. The working baseline for this handoff is commit
`d3bb94ff26eb6d3e95e419414e86abf5ef890ce2`, followed by the WIP checkpoint
that adds this handoff.

The principal completed commits are:

- `c7bac45778608170ed8d0894b62b5b463494c7dd` — execute nominal factories and
  source members (explicit WIP checkpoint).
- `e5014a2ffda45a76ff98f5a009a5065d1c7bb4fa` — contextual method replacement
  and nominal equality.
- `d3bb94ff26eb6d3e95e419414e86abf5ef890ce2` — persistent nominal schemas,
  exact shared JVM classes, object/callable state, producer-bound factories,
  REPL/application attachment, and nominal snapshots.

That baseline passed the complete Maven suite, the extended language fuzz
campaign, and the Phase 24 release audit. A subsequent adversarial review found
additional retained-factory flow defects. The current WIP deliberately contains
new regression tests and is not a completion claim.

## Status (reconciled 2026-09-13)

The retained-factory work this handoff scoped is delivered and committed:
phase 1 `432121b` closed the initializer transfer algebra, phase 2 `c6f4274`
completed every legal expression category and consumer-scoped fresh
provenance, phase 3 `df6e1d5`/`2c3602a` completed constructor/runtime
integration and the immutable-`self` provenance proof closure, and phase 4
`2b49f7c` added the independent compiler/session campaigns and the combined
extended run. The records phase (phase 5, this reconciliation) updates the
living specifications, decisions, review and this handoff. Two open
limitations remain and are tracked as GitHub issues #7
(`bugs/retained-nominal/unit-initializer-later-observation-linkage.md`) and #8
(`bugs/retained-nominal/nilable-member-read-contract.md`); neither is a
completion claim. Final qualification on the final commit is the remaining
gate; the Phase 24 release audit stays mandatory.

# Objective

Finish nominal structs and classes as first-class language features across the
entire pipeline:

```text
grammar -> AST -> resolution -> typing/initialization proof -> semantic flow
-> typed IR -> Java 25 bytecode -> runtime/CLI/REPL/attachment
```

In particular, complete retained factory semantics so constructing a class or
struct in a later REPL submission has compiler flow facts identical in meaning
to executing the original producer factory: ordered defaults, aliases,
aggregate/callable results, constructor writes, captured-cell effects, and
exact producer/runtime authority must survive without source replay.

# Settled Decisions

- `struct` is a concrete nominal data type. Fields are public by default;
  `@mut` fields and nested mutable data are legal. Structs do not contain
  methods or any nested `Fn` data positions and have no custom constructor.
- `class` is a concrete nominal reference type. Fields and methods are private
  by default; `@pub` exposes them. No inheritance, interfaces, polymorphism,
  overloads, static members, or user-defined operators are in scope.
- Construction is `Type[arguments]`. Struct arguments supply fields without
  defaults in declaration order. A class constructor is the capitalized class
  name, for example `Counter = (=> |start :I32| ...)`.
- `:.` reads field data or obtains a method reference. `::` invokes the current
  method slot. A saved `:.method` keeps the callable captured at read time.
- `@mut` permits reassignment of a method slot just like a mutable field.
  Replacement lambdas receive contextual `self` binding.
- Nominal equality is structural and cycle-safe for structs; classes retain
  identity equality.
- Persistent sessions retain exact nominal declaration identity, JVM class,
  initialized object state, closures/cells, and a producer-bound factory
  capability. They do not recompile or replay producer source.
- Semantic flow is compiler proof only; runtime capability authentication is a
  separate boundary.
- A conservative retained initializer abstraction may over-approximate aliasing
  but must never invent a runtime value, lose a callable target, or understate
  mutation/effects.

# Constraints

- Follow repository `AGENTS.md`, `.internal-dev/AGENTS.md`, and
  `.internal-dev/specifications/AGENTS.md`.
- Preserve all current work. Every coherent unit ends in a Git commit. An
  unfinished checkpoint must be explicitly labeled WIP and must not be
  presented as complete.
- Do not weaken semantic validators, suppress invariants, remove coverage, or
  classify valid nominal programs as unsupported merely to make tests pass.
- Expected source failures remain structured diagnostics; compiler invariant
  failures must not escape as `LyraCompilerBugException` for valid programs.
- Keep Java 25 direct bytecode emission and deterministic output.
- Update positive, negative, boundary, runtime-failure, fuzz/oracle inventory,
  coverage documentation, specifications, changelog, knowledge, and review
  evidence for every public-language/backend change.
- `tools/phase24-release-audit.sh` is the mandatory final release gate.

# Scope

## Completed before the current WIP

- Grammar/AST/resolution/type checking for nominal declarations, visibility,
  fields, methods, constructors, construction, field reads/writes, method
  references/calls, and contextual replacement.
- Definite initialization, incomplete-`self` escape rejection, ordered
  initialization, field-sensitive heap flow, callable transfer, nominal IR and
  IR validation.
- Direct emitted nominal JVM classes, typed fields, generated accessors,
  factories, constructor failure cleanup, method invocation/replacement,
  struct/class equality, packaging, typed Java facade use.
- Persistent session nominal schemas and names; exact shared nominal classes;
  retained heap/callable state; authenticated producer factory capabilities;
  local and attached REPL publication; public-only bounded snapshots.
- The stale Phase 24 deferred-form classifier was corrected so implemented
  `match`, `iter`, and nominal forms are audited as active features.

## Current WIP changes

Files currently changed relative to `d3bb94f`:

- `SessionFlowCertificate.java`
  - Adds retained per-member value templates.
  - Preserves literal nil/scalar values, direct/nested array identities, tuple
    routes, and ordered `self:.earlierField` aliases.
  - Adds a conservative type-shaped array template for nonliteral
    aggregate-producing defaults.
  - Retains constructor lambda identity and recognizes nominal constructor/
    member lambdas as producer-certified.
  - Begins a `RetainedInitializerCall` record for direct initializer calls,
    including target, exact function type, argument templates, and typed
    argument expressions. Issuance populates it, but consumption is not yet
    implemented.
- `SemanticFlowAnalyzer.java`
  - Uses retained value templates for non-lambda defaults.
  - Replays retained constructors through their producer-certified
    `CallableSummary`, including field writes and captured shared-cell effects.
  - Reuses one helper to rebuild retained initializer/constructor callable
    captures, substituting the fresh `self` object and current retained cells.
  - Makes transferred write occurrences use the current call span.
  - Allows an exact currently-bound capture root (notably constructor `self`)
    to receive aggregate member writes.
- `JvmBytecodeEmitter.java`
  - Restricts root-initializer emission to `SESSION_EXECUTE`. A nominal factory
    now reads already-initialized producer root functions rather than trying to
    execute their binding initializers again, which previously caused
    `submission binding attempt is out of order`.
- `NominalSessionTest.java`
  - Adds later-submission regressions for direct aggregate defaults, nested
    tuple/array defaults, ordered field aliases, aggregate-returning default
    calls, callable-returning default calls, constructor aggregate writes, and
    constructor captured-state effects.

## Work still required

Historical list as of the original handoff; each item's delivery state is
recorded in the reconciliation note at the end of this section.

1. Consume `RetainedInitializerCall` in `SemanticFlowAnalyzer`.
   `retainedNominalInitializer` currently returns only `ValueAlternatives`.
   Refactor it to return an `Eval` (value, updated state, internal events,
   effects), or add an equivalent helper. For a retained initializer call:
   resolve the target callable from current state/certificate, refresh its
   shared cells, convert the retained argument templates to formulas, and call
   `invokeCandidate`. Join multiple callable alternatives exactly as ordinary
   calls do. Apply returned state/writes/effects, but do not publish the
   synthetic internal call event as a typed root boundary (the same issue was
   encountered with constructor replay).
2. Make call arguments dynamic where required. Current call metadata holds
   static argument templates. Ensure `self`/earlier-member arguments and
   retained mutable captures resolve from the fresh object/current certificate,
   not a stale producer snapshot.
3. Generalize initializer transfer beyond direct named calls if the language
   permits the forms: callable-value calls, namespace direct calls, conditionals,
   blocks, coalesce/match, and calls nested inside tuple/array literals. The
   correct design is producer-certified symbolic transfer, not fabricated
   callable identities.
4. Test callable-bearing composite defaults, not just root `Fn`, because the
   validator requires an exact callable at every function route inside tuples
   or class arrays. Structs prohibit these positions, classes do not.
5. Test constructor combinations: constructor returns/installs a closure with
   captures, writes an aggregate nested in a tuple, calls another retained
   function, mutates a retained shared cell, and fails after partial writes.
   Confirm compiler flow, runtime ordering, failure/cancellation cleanup, and
   subsequent session usability.
6. Review the conservative aggregate template. It intentionally aliases all
   arrays at one initializer site across factory invocations. Confirm this is a
   sound accepted abstraction and document the precision tradeoff, or replace
   it with exact summary-derived fresh allocation identities.
7. Add focused compiler/API tests for `RetainedNominal`/initializer-call proof
   integrity and negative forgery/inventory cases; do not rely only on REPL
   integration tests.
8. Update the language fuzz generator and independent oracle/model with the new
   retained nominal factory cases and preserve any discovered replay.
9. Reconcile living specs/docs. The baseline documents currently describe
   nominal session completion; amend them with the exact retained initializer
   transfer contract after implementation. Add a new changelog/review and
   update reusable nominal/session knowledge.

### Reconciliation of the required work (2026-09-13)

1. Delivered in phase 1 (`432121b`): `retainedNominalInitializer` returns a
   full `Eval` and consumes the closed transfer algebra through the ordinary
   `invokeCandidate` path with candidate joins, transferred state/writes/
   effects and withheld synthetic root events. The `RetainedInitializerCall`
   record and parallel template inventories were removed, not consumed.
2. Delivered: the closed algebra resolves original declaration/capture
   identities and current shared cells at use; the fresh receiver and current
   certificate supply `self`/earlier-member values.
3. Delivered in phase 2 (`c6f4274`): the closed algebra covers every currently
   legal initializer composition (callable-value calls, namespace calls,
   conditionals, blocks, coalesce/match, composites, applies, sequences,
   projections, nested construction, loops).
4. Delivered: callable-bearing tuple/array class composites are transferred
   and certified at every function route (`NominalSessionTest`, the retained
   fuzz composites profile).
5. Delivered in phase 3 (`df6e1d5`, `2c3602a`): constructor compositions cover
   installed/captured closures, helper-returned closures, tuple-nested
   aggregate writes, retained calls, shared-cell mutation, and failure after
   partial writes with ordered runtime behavior, cleanup and recovery.
6. Resolved by the recorded owner answers: the conservative aggregate template
   was replaced with exact summary-derived fresh allocation identities
   (`RetainedAllocationDerivation`), with the finite-site caveat documented in
   `specifications/decisions.md` and the review.
7. Delivered: `RetainedNominalFlowCertificateTest` (44 tests) covers issuance,
   deep immutability, exact inventories, generation continuity and negative
   forgery/mismatch cases.
8. Delivered in phase 4 (`2b49f7c`): the retained compiler fuzz family and the
   extended session model with deterministic operation budgets, replay data
   and guard inventories; two valid-source crashes were closed and kept as
   regressions.
9. Delivered by this phase-5 reconciliation: living specifications, decisions,
   review, knowledge and this handoff now match the committed implementation;
   the Phase 24 audit inventory includes the nominal suites. Open
   limitations issues #7 and #8 remain documented in
   `specifications/repl.md`, the bug reports and the review.

Remaining open work: issues #7 and #8, and the plan's final qualification
sequence on the final commit (including `tools/phase24-release-audit.sh`).


# Recommended Direction

Treat every retained field initializer as a producer-certified transfer, with
literal/value templates as a fast path and callable-summary invocation for
calls. Do not make runtime factory execution depend on compiler templates: the
runtime continues to invoke the authenticated original `$lyra$new$<id>` method.
The templates/summaries exist only to reconstruct exact static flow facts for
the consumer graph.

For direct calls, mirror `invokeCall`/`invokeCandidate` rather than implementing
a second summary interpreter. Resolve the target declaration through
`originDeclaration`, prefer the current `BindingFlowState`, fall back to the
session certificate, refresh shared cells, and preserve candidate alternatives.
Use the later construction expression as the current use/call site so aggregate
ownership witnesses belong to the consumer source. Constructor replay already
demonstrates most of this wiring.

After direct calls pass, introduce a small closed retained-initializer transfer
algebra if necessary (literal/template, earlier-member selection, call,
conditional join, block sequence) rather than retaining arbitrary executable
source or weakening the validator. Keep it immutable, bounded, and validated at
certificate construction.

The emitter fix should remain: `initializeRootDeclaration` may be emitted from
`SESSION_EXECUTE`, but ordinary state methods and nominal factories must only
load/check existing root storage. Add a backend regression proving both a fresh
same-submission construction and a later retained construction can call a
top-level factory default exactly once.

# Validation

Current verified evidence in this WIP:

- `mvn -q -pl lyra-compiler -am -DskipTests compile` passes.
- The direct aggregate, nested aggregate, ordered alias, constructor aggregate
  write, constructor captured-state effect, and aggregate-returning call tests
  passed individually after their corresponding changes.
- The complete `NominalSessionTest` passed before the final callable-returning
  regression and before `RetainedInitializerCall` was added.
- The exact current red test is:

  ```sh
  mvn -q -pl lyra-repl \
    -Dtest='NominalSessionTest#retainedClassFactoriesPreserveCallableReturningDefaultCalls' test
  ```

  It fails during the second submission with
  `LyraCompilerBugException`, caused by
  `function route has neither a callable nor route-specific nil provenance`.
  This is expected until `RetainedInitializerCall` is consumed.

Before completion, run in order:

```sh
mvn -q -DskipTests install
mvn -q -pl lyra-repl -Dtest=NominalSessionTest test
mvn test
tools/fuzz-language.sh -q
tools/phase24-release-audit.sh
git diff --check
git status --short
```

Completion requires all ordinary tests, the bounded/default fuzz selected by
Maven, the extended fuzz command, and Phase 24 audit to pass with no nominal
feature deferred or excluded. Review Surefire totals and audit matrices rather
than trusting exit status alone. The final worktree must be committed and clean.

# Open Questions

- Should retained initializer calls be represented by the current
  `RetainedInitializerCall` record or generalized immediately into a closed
  expression-transfer algebra? Direct call consumption is the smallest next
  slice; generalization is needed if additional valid initializer shapes expose
  gaps.
- Is conservative same-site aliasing across retained factory invocations an
  acceptable permanent precision choice, or should summary fresh-allocation
  provenance be instantiated exactly per construction flow site?
- For initializer calls through a mutable function slot, should target
  resolution always use the latest retained cell (recommended), while the
  certificate only authenticates its allowed callable alternatives?
- Which failure-path state is published when a producer factory throws after
  constructor/shared-cell effects? Match existing ordered callable and module
  initializer failure semantics; do not invent a nominal-specific rule.
