# Nominal class plans self-review

## Scope

Exact nominal class/member/field/factory layouts and generated dependency inventories.

## Findings

- Layouts preserve the closed source schema, full nominal digest, field order and
  complete single-value physical contracts. Forged origins, indices, fields,
  factory signatures and missing member inventories reject.
- Initialization getters/setters carry the runtime construction capability. Normal
  internal access carries caller authority. Public accessors and mutable setters
  exist only where the source schema permits them.
- Source-driven tests first exposed absent nominal class targets, then independent
  linkage-policy and ABI-dependency checks. Each closed inventory was extended;
  no missing-class, dependency or parity invariant was suppressed.
- Recursive nominal references use non-ordering edges, including references through
  tuples/functions. Ordinary dependency-cycle checks retain their prior behavior.
- Nominal declarations denote constructor roles, not runtime instances. Module
  storage planning and its independent parity check now both exclude their names.
- Scalar ABI parity includes the distinct nominal-field position. Two independent
  source generators assert primitive/nullable-array descriptors and mutability.

## Risk Assessment

The nominal emitter remains unfinished. These shapes do not prove method bodies,
constructor/default evaluation, source failures, typed Java use, equality or retained
sessions. Generated value stores must authenticate reference/callable contents when
wired, and lexical privacy must not be inferred from runtime module authority.

## Recommendations

Use these exact members for typed object bytecode and retain all source/ABI audits.
Implement source-to-JVM construction and accessor integration next, including
exceptional factory cleanup and exact constructor/initializer ordering.

## Follow-ups

Final `mvn -q test`, extended `tools/fuzz-language.sh -q`, and diff checks passed.
The complete objective stays active; these are planning tests, not nominal execution.
