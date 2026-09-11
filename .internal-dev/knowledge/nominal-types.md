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
`NominalTypeId` and lexical/grammar/AST support are implemented. Nominal semantic
declaration/type resolution and all execution integration remain unfinished.

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
- LYC-RESOLVE-026 is an explicit temporary semantic boundary. It must be removed
  from valid nominal execution paths before declaring the requested feature complete.

## Open Questions

No additional owner decision blocks starting implementation. Exact internal schema
encoding and typed factory naming must be resolved and tested in the ABI phase;
they must not silently weaken existing compatibility or producer authority.
