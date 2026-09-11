# Nominal syntax checkpoint self-review

## Scope

Lexer/grammar/AST/replay support, fail-closed semantic boundary, syntax fuzzing,
editor keyword styling and console delimiter completeness. Self-review only.

## Findings

- Grammar descriptors retain exact keyword, modifier, name, annotation, initializer,
  constructor equals and delimiter roles. Corrupted descriptors pointing at nested
  punctuation or duplicate member modifiers fail validation before parser replay.
- Class constructors retain full lambda syntax; semantic contextual Unit typing and
  definite initialization are still pending. Struct constructor declarations and
  nested type declarations are rejected by grammar.
- Unary constructor/index ambiguity is intentionally unresolved in syntax. Ordinary
  uppercase array values remain indexable. Non-unary applications retain all args;
  invalid value arity and unknown type names still fail compilation with source
  diagnostics, not compiler invariant exceptions.
- AST lists are immutable, spans preserve UTF-16 source offsets, and exhaustive
  SyntaxVisitor methods were extended without defaults or skipped node dispatch.
- Keyword reservation required migrating the Java name-mangling fixture from class
  to public. Its assertions are intact. The old blanket excluded-class fixture now
  rejects inheritance; it does not treat unsupported valid nominal execution as a pass.
- Independent generated syntax models check fields/modifiers/initializers/constructor
  presence and deterministic truncation failures. They are not runtime state models.

## Risk Assessment

Full runtime support remains absent. LYC-RESOLVE-026 explicitly prevents nominal
declarations from entering an incomplete semantic graph. It must not survive valid
source paths at full-feature closeout. Named type schema loading and callable/object
ownership remain major implementation work, not incidental follow-ups.

## Recommendations

Retain WIP status. Continue nominal declaration collection and exact type/schema
resolution, then constructor/privacy/flow certification before touching JVM lowering.
Preserve the two-phase grammar/replay validation boundary throughout that work.

## Follow-ups

The active nominal plan lists the remaining semantic, backend, Java/artifact and REPL
gates. Final suite/fuzz results are recorded in the accompanying changelog. No release
audit, independent review, benchmark improvement or graphical qualification is claimed.
