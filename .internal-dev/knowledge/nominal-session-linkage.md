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
  (unrelated nominal artifact/session). This was reproduced against the starting
  phase-2 production files, independently of route-certification edits.
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
