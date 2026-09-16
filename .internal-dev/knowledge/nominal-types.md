# Nominal types

## Topic

Accepted struct/class behavior and integration boundaries during implementation.

## Source References

- ../specifications/language-core.md — accepted nominal extension.
- ../specifications/backend-runtime.md and ../specifications/repl.md.
- `.internal-dev/specifications/language-core.md` — living nominal contract (implementation status).
- Compiler SyntaxVisitor, LyraType, ModuleIdentity, DeclarationId and JvmTypePlan.

## Key Takeaways

- Owner explicitly rejected immutable-only structs and non-reassignable methods.
  `@mut` means slot replacement, not method-effect classification.
- Methods may mutate their own mutable fields via an immutable receiver binding.
  That does not remove ordinary direct assignment/import mutation restrictions.
- Saved method references keep the selected callable and receiver, not a copy of
  receiver state. Assigning a bound function elsewhere must not retarget captures.
- Contextual self in an external replacement does not confer private access.
  The earlier Counter example using private value externally is intentionally invalid.
- Compiler/runtime type models and JVM canonical validation are separate closed
  boundaries. A type-name parser fallback cannot implement nominal identity.
- Compilation-local declaration ordinals are not cross-generation identity.
- Mutable fields prevent using Java records as the general representation; nominal
  identity prevents treating equal shapes as structural tuples.

## Project Relevance

These corrections prevent semantic drift during the requested full-pipeline feature.
`NominalTypeId`, lexical/grammar/AST support and initial semantic declaration/member
resolution are implemented. The retained-factory completion (plan
`20260912-180414-complete-retained-nominal-struct-class-factory-semantics`) landed
in commits `432121b`, `c6f4274`, `df6e1d5`, `2c3602a` and `2b49f7c`: closed
initializer transfer, consumer-scoped fresh provenance, constructor/runtime
integration with the immutable-`self` provenance lattice, and the independent
compiler/session campaigns. Follow-up issues #7 and #8 are implemented in the current Tranche 3 worktree:
retained Unit/intrinsic member observation uses exact occurrence-scoped route
authority, and nilable member reads derive their exact schema contracts. Their
active bug records remain unarchived until integrated validation and closeout.

- Unary brackets retain IndexAccess syntax; non-unary brackets retain
  BracketApplication. Both must resolve the target's type/value role later. An
  uppercase array variable must remain indexable, and a lowercase type alias must
  not be rejected by lexical capitalization guesses.
- Named type annotations retain namespace segments/arrows. Canonical qualified
  construction is `model->Counter[args]`; the older `:model->Counter[args]`
  spelling remains accepted, while `model->:.Counter[args]` is invalid.
- Former unknown-type and multi-index grammar failures now belong to resolution;
  negative compile tests still require real unresolved-name/value-index diagnostics.
- The Java-name mangling fixture now uses the Java keyword `public` because `class`
  is a reserved Lyra keyword. All descriptor/invocation assertions are preserved.
- The old excluded-class conformance fixture now rejects inheritance. Empty class
  syntax is positive parser coverage, not a claim of executable class conformance.
- Declaration collection now issues NominalTypeId from the actual module revision.
  NominalType contains only that identity; NominalSchema holds ordered fields and
  constructor contracts. NominalTypeEnvironment checks exact recursive references
  and data-only struct closure without traversing class implementation fields.
- Self is an immutable receiver reference with special member mutation permission,
  not a rebindable variable/shared-cell capture. Member lambda ownership is sealed
  against the declaring schema separately from ordinary let ownership.
- Named member mutation uses MEMBER_FIELD. Positional tuple writes must retain their
  existing rejection path: treating every MemberAccess as nominal broke the tuple
  conformance fixture and fuzz seed 8675309, case seed 1995688059202936941, index 46.
  The minimized source is `let @mut x = Tuple[1 2] x:.0 := 3`; the permanent existing
  aggregates/tuple-write.lyra fixture asserts its TYPE012 rejection.
- Constructor/member resolution is not definite initialization or callable flow
  certification. Do not treat these resolver-only positives as backend coverage.
- Namespace collection now precedes source signature collection across modules.
  Tests selecting the original callable must distinguish LET from IMPORT_VALUE;
  graph-local declaration allocation order is not an origin-selection contract.
- Typed nominal declarations carry a privately issued NominalInitializationProof
  bound to child object identities. Lowering consumes that proof; instance defaults
  and constructors are deferred, represented by INSTANCE_INITIALIZATION edges.
- NominalObjectIdentity/State are compiler analysis records, not the runtime object
  representation. Keep cyclic heap fields separate from aggregate value routes.
  NominalMember projection steps carry exact owner/index/type contracts. Saved
  callable captures retain object identities while field state remains shared.
- Self-field writes use aggregate-capture transfer, never a fictitious mutable self
  cell. Constructor default/argument evaluation records real source mutation events.
- Canonical nominal spellings are cached once per type; repeated comparisons no
  longer recompute SHA-256. This is not a measured benchmark claim.
- Constructor call references now preserve exact nominal declaration/source identity
  and transfer initialization through the caller-owned heap. Transitive factory
  calls must remain deferred in the solver; materializing them without a caller
  loses allocation and ambient-state effects. Event-major write sequences order
  constructor calls with surrounding writes; activation-local memoization prevents
  return and write projections from executing the same call twice.
- Mutable module function slots are linked storage outside REPL graphs too. Their
  symbolic values must be seeded and their writes transferred, not silently omitted
  merely because the resolver does not represent them as lexical captures.
- A constructor may replace an ambient callable slot. The enclosing factory must
  read its post-construction value, not reuse a symbolic value from before the call.
  Focused and seeded independent slot-order models cover this semantic boundary.
  This does not establish complete branch/cyclic/imported/repeated heap transfers.
- Runtime nominal identity/schema contracts and explicit schema-aware canonical
  type/signature parsing now exist independently of the compiler model. Both hash
  the same versioned UTF-8-length-prefixed origin contract. Default parsing has an
  empty schema environment and rejects nominal spellings, including valid hashes.
  Do not introduce a process-global registry to bypass this boundary.
- Nominal JVM references use a distinct generated family and full declaration
  digest. Physical mapping validation checks that exact digest, not only a nominal
  class-name prefix. These descriptor plans do not yet emit or authenticate objects.
- Generated nominal artifact publication, object authorities and retained nominal
  sessions are not implemented by the compiler-only semantic heap or type records;
  they are delivered by the certificate/session and runtime factory layers of the
  retained-factory completion.
- Artifact schema 2 now encodes the nonempty nominal schema graph before exports;
  schema 1 omits it and keeps old revision inputs. The two-pass reader verifies
  origin/digest pairs before resolving recursive contracts. The complete canonical
  schema JSON is bound into the artifact revision with its own domain, so changing
  even a valid field visibility/mutability/name cannot keep the old revision.
  Both metadata construction and reader compatibility validation recompute this
  extension; updating only one causes nominal round-trip failures.
- The metadata reader is one gate among several that are now all delivered: the
  compiler emits nominal artifacts, the runtime authenticates generated nominal
  classes and schema-aware live loader/session linkage, and retained nominal
  sessions execute producer-bound factories.
- NominalRuntimeContracts projects a validated IR's closed schemas directly into
  independent runtime records. It caches declaration references during recursive
  contract conversion and compares canonical spellings at the boundary. Embedded
  facade metadata and artifact assembly both use this projection and bind it into
  revisions; neither may call the schema-free type parser for nominal exports.
  Generated object class plans and emission are delivered by the generated-type
  planner and `JvmBytecodeEmitter`; retained construction executes the
  producer-bound factory.

Nominal construction capabilities bind a single exact receiver and final class;
the receiver constructor must also check its own embedded nominal contract.
Checking only the caller-supplied class would let a ticket retype a representation.
Source fields stay typed in the subclass; the runtime base holds only authority,
schema and initialization bits, releasing the bits at completion/failure. Generated
initialization must validate values before marking a slot and immediately emit its
typed store; exceptional exits invalidate the partial receiver. Private access is
lexical compiler evidence, not a property inferred from construction authority.
The runtime module guard must permit separate instances of the same declaring
module in the same authenticated domain, not require identical instance tokens.

Producer-scoped signature resolution must update both loaded-artifact keys and
direct generated-facade key creation. A closure constructor already has its
authority argument on the operand stack before its superclass constructor runs;
duplicate that authority for nominal signature resolution rather than reading
uninitialized instance capture fields. Schema-2 compatibility must also reach
RuntimeOptions through LyraRuntimeConstants, not only the metadata reader gate.
The cache contains immutable signatures only; individual lifecycle checks must
precede lookup even when another still-open instance shares the same artifact key.

Nominal class plans must enter the exact generated-class index before any descriptor
may name them. Closed dependency checks independently exist in GeneratedClassDependency,
GeneratedTypePlanner ordering, GeneratedTypePlan validation and JvmAbiParity; all need
the explicit nominal linkage category. Nominal-to-tuple/function and all nominal
targets are non-ordering links so recursive schemas remain complete without cycles.
Field schemas still drive exact primitive/reference storage and source visibility;
nominal declaration names are constructor roles, never module instance fields.

Emitted nominal accessors must validate a reference value before marking an
initialization slot or changing a mutable field. The validated value is then stored
immediately and unchanged, preserving array/object alias identity. Recursive nominal
and callable contracts resolve through the owning artifact authority; generated
nominal methods must use the scoped signature cache even for an otherwise ordinary
function field. The definite-initialization proof permits an object to store its own
incomplete receiver internally, so only the matching active construction ticket may
authenticate that exact self value. It must not admit another incomplete object or
make the receiver usable through an ordinary public/generated boundary.

Module-state factories are the sole generated construction entry used by Lyra
source. They evaluate constructor arguments into exact typed locals before loading
the defining state, install required struct fields before defaults, and execute
class defaults before the constructor body. A broad factory handler must be ordered
after source call-site handlers so source failures keep their diagnostic code while
the construction ticket is still invalidated. Nominal declaration captures,
including selective-import aliases whose origin is nominal, are type roles rather
than physical closure fields; the closure's normal module-state link reaches the
origin factory. Lazy semantic-flow lookup must recheck the binding state after
executing a nominal declaration because its type marker intentionally has no
ordinary initializer value.

Direct replacement lambdas need a resolver-issued contextual SELF declaration in
the replacement lambda's own scope. Its exact nominal contract comes from the
selected member target, and nested lambdas capture it through the ordinary capture
chain. The emitter binds only that declaration to the receiver local while creating
the replacement closure; assigning an existing callable must bypass this binding.
Lexical member authorization remains tied to the source lambda location, so this
receiver capability does not grant access to private members.

Cycle-safe struct equality cannot use `equals` on generated objects or retain a
comparison set across source expressions. Each top-level typed equality creates a
fresh symmetric identity-pair context; recursive generated struct methods share it
while directly comparing exact private field storage. A visited pair is coinductively
equal only within that traversal. Validate both operands' producer, lifecycle and
exact schema before field access. Class leaves use reference identity, including
inside structs, while structs remain invalid operands for `eq?`.

## Open Questions

No additional owner decision blocks starting implementation. Exact internal schema
encoding and typed factory naming must be resolved and tested in the ABI phase;
they must not silently weaken existing compatibility or producer authority.
