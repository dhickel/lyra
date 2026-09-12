# Nominal signature linkage self-review

## Scope

Producer-scoped runtime signature resolution and its generated-call wiring.

## Findings

- Owner/lifecycle checks precede cache lookup, including initializing, failed and
  closed producers. Shared metadata does not share individual producer validity.
- Artifact metadata supplies a closed schema environment. Reusing an existing key
  requires exact schema equality; unknown nominal contracts reject.
- All generated signature sites already place authority atop the operand stack.
  Duplicating that argument is valid before the closure superclass constructor;
  reading an uninitialized capture field there would not be valid.
- Legacy signature bytecode stays on the existing schema-free parse path. Both
  facade-created and runtime-loaded nominal artifact keys receive schemas.
- A focused test exposed a remaining schema-1-only RuntimeOptions gate. It now
  accepts exactly 1 and 2; boundary tests retain unknown-version rejection.
- Seeded independent lifetime models verify that closing/failing one producer
  rejects its cached lookups while another producer sharing its key stays usable.

## Risk Assessment

Metadata resolution does not prove live-object authorization, nominal class
inventory, exact storage linkage, constructor emission or persistence. Those remain
mandatory separate implementation and source-to-JVM tests.

## Recommendations

Keep producer checks ahead of cache access. Do not use signature cache identity
as an object/private-access capability. Complete generated nominal class plans
and construction before claiming executable nominal-bearing closures.

## Follow-ups

Full `mvn -q test`, extended `tools/fuzz-language.sh -q`, and diff checks passed.
Continue the active nominal implementation after committing this linkage unit.
