# Nominal types

## Topic

Accepted struct/class behavior and integration boundaries during implementation.

## Source References

- ../specifications/language-core.md — accepted nominal extension.
- ../specifications/backend-runtime.md and ../specifications/repl.md.
- ../plans/nominal-types/plan.md — active, unfinished implementation.
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
resolution are implemented. Complete initialization/flow and execution integration
remain in progress.

- Unary brackets retain IndexAccess syntax; non-unary brackets retain
  BracketApplication. Both must resolve the target's type/value role later. An
  uppercase array variable must remain indexable, and a lowercase type alias must
  not be rejected by lexical capitalization guesses.
- Named type annotations retain namespace segments/arrows. Qualified construction
  uses existing `model->:.Counter[args]`, avoiding ambiguity with conditional arrows.
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
- JVM mappings, runtime schemas, object authorities and retained nominal sessions
  are not implemented by the semantic heap model.

## Open Questions

No additional owner decision blocks starting implementation. Exact internal schema
encoding and typed factory naming must be resolved and tested in the ABI phase;
they must not silently weaken existing compatibility or producer authority.
