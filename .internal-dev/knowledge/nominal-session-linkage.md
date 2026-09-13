# Nominal session linkage

## Topic

Persistent nominal types, objects, methods and constructors across compiler/runtime
session generations.

## Source References

- `.internal-dev/specifications/repl.md`
- `.internal-dev/specifications/backend-runtime.md`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionFlowCertificate.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionTypeLoader.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/NominalSessionTest.java`

## Key Takeaways

- Canonical nominal spellings cannot be parsed without the exact artifact schema
  environment. Defer requirement parsing until producer/artifact selection; never
  interpret an unknown nominal hash as a new type.
- Same binary names in sibling generation loaders are different JVM classes. Exact
  identity-named nominal representation classes must use the session/root structural
  parent domain, while module state, cells and closures remain generation-local.
- A retained schema is not executable constructor authority. Persist a typed
  MethodHandle bound to the original initialized module state, authenticate it in the
  immutable link table and cache it outside the construction hot path.
- Retained heap proofs must traverse nominal fields. Otherwise a replaced method can
  survive at runtime while its nested lambda identity is lost by the next compiler
  certificate.
- The standalone class-file verifier cannot resolve self-typed nominal descriptors
  before their staged loader owns the class. Validate the generic inventory eagerly
  and rely on JVM definition verification when the staged class is actually loaded.
- Snapshot inspection uses generated public field accessors only. It never calls
  source methods or `toString`, and it omits private class members.

## Retained certificate transfer

- A later generation re-registers retained nominals as declarations with no initializer
  evidence. Retained-proof construction must load the predecessor certificate rather
  than rebuild evidence from the current graph.
- Callable-local fresh allocations must retain the producer's exact allocation
  identity. Reconstructing a type-shaped or consumer-minted identity for a retained
  summary result makes semantic flow validation fail with a foreign aggregate owner.
- `AllocationProvenance` is the single carrier for those exact producer identities
  in retained summaries.
- Retained summary allocation conversion previously crashed when an owner typed
  lambda was absent. Every owner-lambda lookup needs a certificate fallback.
- A retained block declaration has a producer-only declaration identity and contract.
  Transfer may bind it only while evaluating the sequence, then must remove that
  binding before publishing consumer flow state; otherwise the fact validator
  correctly rejects a binding with no declaration in the consumer graph.
- A retained projection must carry the base value type separately from the projected
  result type. Retagging an aggregate base as its scalar element before selection
  violates route/type invariants and loses exact aggregate provenance.
- Retained lazy alternatives must store typed prefix steps separately from typed
  branch/arm steps. Replaying all children sequentially lets one speculative branch
  contaminate another branch's state. Evaluate the prefix once, start every branch
  from that post-prefix state, and use the ordinary branch or coalesce join. A
  two-child conditional retains an explicit Unit else branch; match arm selectors
  remain arm-local and precede only that arm's result.
- Retained transfer nodes need their exact resolved result/base/argument types and
  complete nominal schemas. Consumer replay must consume each child under that
  child's own type; parent-type placeholders hide invalid callable, aggregate and
  nominal routes and prevent the certificate from rejecting malformed evidence.
- Mutable captures retain one producer-certified cell identity as authority, but a
  producer-local cell receives a bounded consumer identity derived from its exact
  construction/invocation context. Retained callables carry that cell context so
  aliases within one constructed instance share writes while separate instances do
  not. Boundary-published producer cells retain their original identity. Local
  bindings are removed from published prefixes/final state while the scoped shared
  cell remains available to retained closures.
- Consumer constructions do not reuse producer allocation identities. Derive a
  bounded identity from the current graph-owned construction/invocation site, every
  intervening `SummaryCallId` and its producer call site, and the exact producer
  allocation site. This distinguishes two calls to one allocator while preserving
  one identity for repeated execution of the same finite call path. Reject both
  derivation collisions and facts that cannot be recomputed from the exact consumer
  target and its closed retained transfer/summary evidence.
- Current-generation wrapper summaries remain symbolic at publication: parameter and
  capture calls do not contain the predecessor allocation formula. Validation must
  retain the exact selected `CallableFlow`, including creation-time captures, then
  follow graph-owned `SummaryCallId`/source-site edges into compiler-certified
  predecessor summaries. Signature-only target recovery is not sufficient. Every
  summary invocation still needs a graph-owned or enclosing retained derivation
  context, even when the initially selected callable is current-generation.
- Retained callable-slot types must compare without top-level qualifiers. Mutability
  belongs to the binding/cell contract, while the stored default and every replacement
  retain the same unqualified function signature.
- Retained-derived declarations and sites need explicit disjoint tagged domains for
  invocation contexts, arrays, object sites, object allocations and shared cells.
  Constructor checks must recompute those identities from ordinary source sites rather
  than accepting a caller-supplied hash, route, schema or allocation value.
- A current capture/parameter effect can target a source-local predecessor that is not
  represented as a retained module-graph node. Initialization planning and flow
  sealing may ignore that already-initialized external module only when the session
  certificate owns its source identity and independently certifies the exact target
  lambda; treating any absent module as retained would weaken the boundary.
- `TypedExpressionKind.NARROWING` currently has no TypeChecker issuance path. Explicit
  numeric casts issue `CONVERSION`, while predicate refinements remain represented by
  their conditional/match expressions. Retained transfer accepts `NARROWING` as a
  closed apply kind, but no source-level nominal initializer can exercise it until
  semantic typing gains an actual narrowing expression node.

- Callable identity/capture keys alone do not authenticate a routed occurrence.
  Transfer/summary route evidence must preserve both composite prefixes and selected
  suffixes. Parameter-route propagation must stay call-local; a global parameter
  bucket incorrectly mixes callable identities from separate invocations.
- Semantic sealing and IR validation both consume retained callable certificates.
  Tightening the predecessor-only predicate exposes legal consumer-only projections
  at the IR boundary unless that boundary also uses the consumer's source-derived
  route proof (`LYC-IR-003`, foreign lambda creation site).
- Cross-generation initializer coverage needs an observation after construction.
  The four namespace/Unit inventory cases with a producer `std->io` import construct
  successfully in generation 2 but fail observation in generation 3 with `LYR-LINK`
  (unrelated nominal artifact/session), independent of the transfer algebra.
- Do not fix that by widening `SessionStorageDomain.Linkage.sourceLocal` to count
  real source modules while ignoring the intrinsic module: the intended posture is
  that an importing graph is not eligible for the session-authentication bridge, and
  `SessionClosureAuthorityTest` pins it ("imported graph authority is not certified").
  A generated nominal instance is owned by its creating submission's authority, so
  the correct fix anchors that ownership to the session root (or adds an authority
  path independent of the importing-graph rule) instead of relaxing the classifier.
- A nominal-only same-workspace predicate is insufficient: it admits the object and
  fixes Unit-valued member reads, but a callable member then correctly fails the
  separate closure-authentication boundary. A complete solution needs route-scoped
  delegation for a callable reached through an authenticated nominal field, or a
  session-root construction authority that owns installed field values, while the
  same imported closure presented directly must remain rejected.
- Tuple-nested array writes rooted at constructor `self` are aggregate mutations,
  not rebinding of the immutable `self` declaration. Resolver, type checker,
  topology validation and IR validation must classify that root consistently.
- Consumer-derived retained array identities still need their producer scope, span
  and origin site registered in the analyzer's canonical allocation indexes. The
  derived identity changes; its producer provenance does not.
- A retained constructor summary can allocate nested objects and arrays just as a
  member initializer can. Consumer-construction certificate checks must search the
  constructor summary as well as member-initializer transfers. Each nested retained
  object site must be pushed as the active construction context while its defaults
  and constructor summary execute; certificate recursion must use that same derived
  context before descending into another constructor.
- Immutable `self` is not general aggregate-mutation authority. Direct member-field
  assignment keeps its established method/contextual-replacement behavior, but an
  indexed or otherwise non-field write through `self` is valid only when the root
  reference belongs to the exact constructor lambda of that nominal. Resolver,
  topology and IR checks must agree so ordinary invalid source remains a structured
  diagnostic instead of reaching semantic-flow invariants.
- Derived aggregate certification follows destination reachability, not transfer-tree
  containment. A retained block contributes its final value and explicit writes;
  declaration identities resolve lexically so shadowed bindings cannot leak an older
  allocation. Call and class-constructor arguments count only when summary return/write
  formulas route the parameter to the destination; unused arguments do not. Conditional
  transfer evidence records statically reachable result branches, while conservative
  eager-effect evidence may still include every branch.
- Conversion and narrowing transfer nodes must stay transparent in the derived
  result walk. A tuple element such as `Tuple[selected 3]` inside a retained block is
  an `Apply` over a sequence-local `Reference`; routing the apply to the plain transfer
  walk loses the sequence bindings and the consumer compilation dies with
  `aggregate owner module is foreign` instead of accepting the rebind-then-tuple proof.
- A rebind of a root binding is transient local flow: only the final-result walk may
  certify its value, so an intermediate value overwritten before the result mints no
  identity. A rebind whose target is a non-root (field/slot) route is a genuine state
  write and keeps certifying its value eagerly.
- Derived-object argument traversal must be return/write-formula and
  destination-route aware, mirroring the aggregate walk: an object argument the callee
  neither returns nor writes is not reachable and must not mint a certified object
  identity.
- Immutable-`self` provenance survives alias bindings, captures and parameters that
  receive `self` at exact call/construction sites, and it is a conservative
  forward may-alias property, not a per-site exactness proof. Control-flow
  merges join the provenance of every reachable branch: a conditional, match or
  coalesce result unions branch provenance, and a rebind inside one arm must
  merge the branch-end states back into the pre-merge state (then-only
  conditionals include the skipped path) or the last-walked arm silently erases
  the other path's provenance.
- Call results conservatively carry the union of their target and argument
  provenance unless the call is a construction, which mints a fresh object and
  must return none. Do not use callable-body analysis to prove non-identity;
  an identity-returning callable is covered by its argument union, and a
  self-returning closure is covered by its recorded body-result provenance.
- Callable-parameter transfer needs fixed-point iteration, not a single
  source-order pass: `self -> f.param -> g.param` only resolves when later
  passes carry the newly learned parameter facts back through the call sites.
  Iterate with a small explicit bound, stop when a pass adds no new provenance,
  and use one shared engine for the resolver closing sweep, topology validation
  and IR validation so no layer accepts a mutation another layer rejects. A
  live single-pass map stays a sound early filter only when every live fact is
  a subset of the fixed-point facts (joins and unions only grow it). The same
  subset property applies to the live member-taint mirror: live member facts
  are union-merged from live value facts, so a fact the live filter rejects
  is always a fact the closing sweep rejects; the sweep remains authoritative
  and may reject more.
- Branch-merge and call-result provenance must never accept what the direct
  form rejects; over-approximation is allowed and only rejects more mutation
  sites, while under-approximation is the actual bypass.
- A closure body is a provenance channel even without full body analysis:
  record each analyzed lambda's body-result provenance while walking it
  (result-position walks already join every branch), union it into the call
  result of every call whose target resolves to that lambda, and make the
  lambda expression itself carry its body provenance so the closure value
  keeps the taint through alias bindings and higher-order arguments. This is
  a sound bounded over-approximation: it only rejects mutation sites the
  direct form would reject if the returned value were named; scalar-typed
  taint is inert because only array-element mutations consult provenance.
- A fixed point that only iterates parameter facts cannot propagate through
  declaration-to-declaration and closure-to-closure edges: seed the entire
  alias map plus the per-lambda result map each pass and converge on full-map
  equality, or backward-ordered forwarding chains stop one level short and
  self-returning closures launder provenance. Forward-ordered chains resolve
  in a single pass; only backward/cyclic edges need iteration.
- The bounded analysis must converge or fail closed. When the pass bound is
  exhausted with facts still changing, emit the structured resolver diagnostic
  (LYC-RESOLVE-021) with a message that provenance could not be decided within
  the bounded analysis, mapped to the first call site that added provenance in
  the final pass (a member-slot assignment or lambda-result growth site as
  fallback). Publishing the
  last partial state silently admits mutations the converged state rejects.
  Do not raise the bound instead; it must decide or reject.
- Internal validation layers (`ResolvedTopologyValidator`, `IrValidator`)
  share the engine through a converged-only entry point that raises an
  invariant on non-convergence, so every layer re-checks the same rule on
  graphs the resolver already gated.
- Member rebinds are tracked through member-slot assignment provenance, not
  per-instance slot contents: when an assignment targets a member declaration
  (through `recv:.member` or an element write through a member aggregate) with
  a value that carries self provenance - a closure whose recorded body-result
  provenance is non-empty, a direct self alias, or any already-tainted value -
  the member declaration is marked self-tainted in one declaration-keyed map
  that is union-merged, monotone and iterated inside the same bounded fixed
  point. Every read of a self-tainted member carries the taint, including a
  call through the member slot, which otherwise resolves to the declaration's
  original lambda and would launder the rebound closure's provenance (observed
  result 7 before the rule). The member map must join the same convergence
  comparison and bound-exhaustion fail-closed path as the alias and
  lambda-result maps. The rule is deliberately flow-insensitive: a member
  tainted by any assignment in the graph taints every read on every instance,
  which only over-rejects; the under-approximating alternative (instance-local
  slot tracking) is the actual bypass. Contextual replacement `self`
  declarations carry the nominal's exact schema contract, so ownership checks
  must recognize a `self` by `effectiveContract.valueType()` matching the
  nominal schema type in addition to the nominal's own `self` identity, or
  constructor-installed closures carrying contextual-self taint are rejected
  inside the exact constructor.
- A declaration-wide self set plus a declaration-wide member taint is not enough
  for aggregate aliases. A read such as `other:.slots` must retain `slots` as a
  storage back-reference when copied into a local, tuple, array, call parameter
  or another member. Otherwise an element write through the copy changes the
  real member storage without tainting the member declaration, and a later read
  launders a self-returning closure.
- The closed approximation now used by `SelfAliasProvenance` is a finite
  declaration-keyed value-position lattice with four joined components: direct
  receiver aliases, receiver-backed storage, possible originating member
  declarations, and known lambda identities. Lambda identity and body-result
  provenance stay separate until a call; this avoids treating a closure object
  as the receiver it may return while still preserving body provenance through
  callable aliases and aggregate storage. Both assignment spellings weakly
  update every possible member origin, and element writes weakly update the
  local aggregate root so a later projection cannot erase the route. The
  concrete mutation target also needs its own provenance fact. Opaque or
  retained calls conservatively treat every self-aliasing target/argument as
  a possible source of self-backed result storage; a known body may add facts
  but never erase that route. Checking only
  the root declaration misses `other:.values[0]` after
  `other:.values := self:.values`, because `other` is fresh while the selected
  member storage is not.
- Aggregate positions and member instances are intentionally collapsed. Any
  sibling projection may inherit the union of origins, any instance read of a
  written member may inherit its stored facts, and a rebind never clears a prior
  may-alias fact. This is the explicit precision cost of the bounded sound
  approximation. The maps remain monotone and deterministic; all four components,
  member values and lambda results participate in the existing eight-pass
  convergence comparison and fail-closed `LYC-RESOLVE-021` path.
- Direct self aliases and self-backed member storage are distinct facts. A local
  callable aggregate may contain a closure that returns `self` without itself
  being the nominal receiver; later invocation materializes the lambda result.
  In contrast, `let alias = self:.values` records receiver-backed storage and
  remains unauthorized outside the exact constructor even though the alias type
  is `Array<I32>` rather than the nominal type.
- Typed semantic provenance must resolve `SyntaxNode.NamedType` through the exact
  resolver-issued `TYPE` syntax link, just as `TypeChecker.typeFromSyntax` does.
  Nominal type environments validate an already-resolved identity and cannot map
  source spelling. Missing this branch makes explicit nested forms such as
  `Array<Fn<;Box>>[...]` raise an invariant while inferred forms happen to pass.
- Constructor-local closures that receive or return incomplete nominal `self`
  currently compile but fail when invoked during construction with `LYR-INIT`.
  This is a runtime construction-capability boundary, not immutable-self
  provenance. Do not weaken initialization validation to turn it into a positive;
  a successful runtime contract would need a separately authorized capability
  path across compiler and runtime production code.
- The resolver can only run the frozen-graph sweep before topology validation
  if graph publication supports deferring that one validator; otherwise the
  topology invariant fires before the structured resolver diagnostic can be
  emitted. Deferring must keep every other publication path validating
  immediately.
- REPL runtime frames keep an internal session `SourceId` while `SourceOrigin.label`
  carries the caller label. Exact producer-frame tests should assert the mapped UTF-16
  offsets, caller origin range and line/column derived from the registered producer
  text rather than equating the internal source ID with the display label.
- An unannotated observation `Tuple[box:.x:.0 (box:.x:.1)]` hits the existing
  context-free tuple provenance check. An explicitly typed `Tuple<I32,I32>` binding
  permits asserting both the scalar and invoked lambda result without changing the
  retained mixed-tuple initializer under test.

## Project Relevance

These rules preserve exact type identity, lexical privacy, method receiver capture
and constructor behavior across standalone and attached REPL submissions without
source replay or map-shaped object representations.

## Open Questions

Graphical editor qualification and native-platform release evidence remain governed
by their existing release gates; they do not change nominal runtime semantics.
