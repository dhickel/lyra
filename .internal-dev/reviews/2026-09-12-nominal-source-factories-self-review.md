# Nominal source factories self-review

## Scope

Direct source construction, defaults/constructors, member operations, method-slot
selection, import routing and construction failure cleanup.

## Findings

- Every explicit constructor argument is evaluated once into its exact factory ABI
  local before the defining module state is loaded.
- Factories consume the existing closed nominal IR and initialization order; they do
  not infer source semantics. Required struct fields precede declaration-ordered
  defaults, while class defaults precede the constructor body.
- Generated initialization access is used only for the exact incomplete receiver.
  Reads/writes on any other object use normal generated authority checks.
- Factory-wide Throwable cleanup is registered after source/call failure handlers,
  preserving structured source failures while invalidating the partial ticket.
- Method calls select the current callable slot before evaluating explicit arguments;
  saved references retain their prior callable identity.
- Direct and selective type imports are followed by origin identity. Nominal aliases
  are excluded from physical captures and ordinary value facades but retain their
  module-state linkage.

## Risk Assessment

Medium. The implemented path is direct typed JVM execution and focused/seeded tests
exercise it, but the accepted nominal feature still has unimplemented equality,
contextual replacement-receiver, packaging/Java and persistent-session gates.

## Recommendations

Keep the nominal type-role test centralized across planner, parity and emitter so a
re-export alias cannot regress into fake value storage. Preserve call-handler ordering
if factory control flow changes. Add cross-artifact and session object execution only
after exact class/schema inventory validation is complete.

## Follow-ups

Implement contextual `self` for direct replacement lambdas, cycle-safe struct
equality, loader/package integration and persistent nominal sessions. Run the Phase
24 release audit only after those gates are closed.
