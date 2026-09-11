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

## Open Questions

No additional owner decision blocks starting implementation. Exact internal schema
encoding and typed factory naming must be resolved and tested in the ABI phase;
they must not silently weaken existing compatibility or producer authority.
