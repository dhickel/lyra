# Scope

While design and reserved syntax checkpoint on baseline
28eceddd9c8f356bec6cbd0085193c322df91583.

# Findings

- The while contract uses ordinary zero-argument Bool/Unit callbacks and preserves
  last-expression results. No implicit state producer is needed.
- Shared callback-loop keyword classification keeps parser replay, grammar leaf
  certification and both postfix/conditional boundary checks synchronized.
- Reserved call heads normalize to the existing call AST; validation confines
  the reserved identifier exception to unqualified call heads.
- Rejected-binding/qualified-name/malformed forms and generated source boundaries
  are covered. Editor highlighting consumes the same compiler token vocabulary.
- Lexer modifier assertions now select decoded modifier tokens, retaining their
  value assertions without depending on keyword-count offsets.

# Risk Assessment

This only implements syntax, not semantic contracts or runtime behavior. Exact
contextual signatures, repeated callback effects, certified IR, constant-stack
lowering and cancellation remain unfinished. Merely invoking each callback once
in abstract analysis would be unsound, especially with mutable callable captures.

# Recommendations

Use the shared loop analysis planned for iter; model the predicate's effects on
zero-action and terminal paths. Keep execution status explicit in user handoff.

# Follow-ups

See phase-03-while.md and the checkpoint changelog for scope and validation.
