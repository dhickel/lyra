# Nominal session finalization self-review

## Scope

Cross-generation nominal resolution, heap/callable certification, exact class and
constructor linkage, attached-session parity and immutable bounded snapshots.

## Findings

- Retained definitions preserve declaration, self, member and lambda identities;
  active source names may move to a replacement type without retargeting old values.
- Heap overlays preserve object states, and proof collection follows callable values
  nested in nominal fields. Contextual replacement removes its temporary synthetic
  `self` binding after RHS evaluation.
- Exact nominal representation bytes are admitted once into the session/root parent
  loader. Later artifacts resolve the same JVM class rather than a shape-equivalent
  sibling-loader class.
- Retained construction requires both compiler-issued nominal identity and a runtime
  factory capability bound to an OPEN initialized producer. The MethodHandle and
  MethodType are cached and checked before invocation; schema metadata alone cannot
  manufacture objects.
- Standalone and attached workspaces publish value and factory capabilities in the
  same revision commit and clear both on reset/close.
- Snapshot traversal authenticates the exact generated nominal class, follows only
  public generated field getters, preserves identity references and applies existing
  depth/element/render budgets.

## Risk Assessment

Low after the full Maven suite, extended fuzz and Phase 24 audit. The highest-risk boundaries—loader identity,
third-generation method calls, retained constructors, private snapshots and attached
workspace compilation—are covered directly or by existing cross-surface tests.

## Recommendations

Keep nominal factory capabilities separate from value bindings and schemas. Do not
move nominal objects, module state or closures into a universal runtime value model.
Retain the JVM-load verification boundary for self-referential nominal classes.

## Follow-ups

Graphical UI and non-Linux native evidence remain separate environmental
qualification, not semantic implementation.
