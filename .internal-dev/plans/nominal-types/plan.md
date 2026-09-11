# Struct/class implementation

## Context

Owner requested complete syntax and backend support after accepting mutable
data-only structs, mutable class method slots, same-name constructors, snapshot
method selection and lexical privacy for replacement lambdas. Baseline is
`7b1bc1e3aac32547d0679a5f5b66d2ecbafe3a69`. The worktree was clean at task start.

## Goal

Compile, execute, package and persist nominal structs/classes through the existing
Java 25 direct-bytecode pipeline with exact typing, ownership and diagnostics.

## Status

In progress: intended contracts, nominal identity/type/schema models, lexical/
grammar/AST support and initial declaration/member resolution are implemented.
Complete contextual replacement, initialization/flow, typed IR, runtime, backend and
session support remain unfinished. NominalSemanticsTest adds resolver-only positive
coverage, not executable conformance. Work continues through the remaining gates;
tested checkpoints are not completion or stopping points.

## Implementation sequence

1. [Nominal syntax and identities](phase-01-syntax-and-identities.md).
2. [Resolution, initialization and flow](phase-02-semantics-and-flow.md).
3. [Typed IR, JVM and artifacts](phase-03-jvm-and-artifacts.md).
4. [Sessions and qualification](phase-04-sessions-and-validation.md).

Each phase ends in a reviewed, tested commit. If syntax is checkpointed before its
backend, mark it explicitly partial; unsupported valid-source tests cannot count
as positive feature coverage. Never suppress existing sealed-phase invariants or
introduce dynamic lookup/source rewriting to get a nominal example to execute.

## Settled boundaries

- `struct [@pub] Name` / `class [@pub] Name`, module-level initially; members use let.
- Class fields and methods private by default; struct fields public by default.
- Explicit complete member types; mutable fields and nested mutable data allowed.
- `Name = (=> |typed params| body)` class constructor; `Name[args]` construction.
- Constructor completion requires every field initialized, no incomplete self escape.
- `:.` reads data/callables; `::` invokes; `@mut` methods may be replaced exactly.
- Methods can mutate their own mutable fields through immutable receiver bindings.
- Method extraction retains selected callable/receiver, not frozen receiver state.
- External replacement lambdas receive contextual self but no private privileges.
- Assigning an existing bound function keeps its original captures/receiver.
- Struct equality is structural, class equality is identity, no polymorphism.

## Integration hazards identified

- `LyraType` is closed separately in compiler and runtime; canonical types are also
  parsed in JVM plan validation. Adding unknown names to one parser is insufficient.
- Current built-in type tokens are lexically recognized. User type annotations must
  retain names for resolution without misclassifying ordinary index expressions.
- `SyntaxVisitor` is intentionally exhaustive and grammar descriptors validate exact
  token roles before replay. New nodes must extend both, not bypass grammar replay.
- Declaration IDs are compilation-local ordinals; nominal persistence needs stable
  module/revision identity and exact retained producer contracts too.
- Existing canonical flow distinguishes shared cells, aggregates, source origins
  and session origins. Mutable nominal fields need equivalent field-sensitive facts.
- JVM session loading currently shares structural tuple/function interfaces, not
  arbitrary nominal schemas; shape-based interning would conflate distinct types.
- Earlier design example accessed a private field in an external replacement; it is
  deliberately invalid under the confirmed lexical-privacy rule.

## Completion evidence

Full `mvn test`, bounded and extended fuzz, source-to-artifact/Java integration,
cross-submission tests, deterministic packaging/verification, source failures,
and updated coverage inventories are mandatory. Run the release audit before
release; do not imply graphical or independent qualification without execution.
