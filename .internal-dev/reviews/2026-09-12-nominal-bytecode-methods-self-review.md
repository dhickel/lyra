# Nominal bytecode methods self-review

## Scope

Generated nominal representation classes, accessor bytecode, nested reference
authentication, artifact metadata replacement and runtime nominal linkage.

## Findings

The class shape follows the independent generated layout exactly. Value validation
precedes initialization marking and field mutation. Public operations require OPEN
state; generated operations retain owner/lifecycle checks. Only an active matching
ticket accepts the receiver's own incomplete self reference. Arrays and tuples are
walked for reference leaves without copying. Nominal-bearing callable signatures use
producer-scoped schema resolution. Schema-2 facade metadata replacement retains the
same unique provisional-marker checks used by schema 1.

## Risk Assessment

Medium. Real emitted classes execute through the host JVM, but source factories and
source member/method lowering are not present. Session class lookup verifies the
expected final-base shape but not yet the complete schema/member inventory.

## Recommendations

Keep this as an explicit progress checkpoint. Build factories from typed nominal IR
and its existing initialization proof; do not duplicate semantic analysis in the
emitter. Add source-origin integration tests before expanding Java-facing names.

## Follow-ups

Implement construction and field operations, then method replacement/binding,
structural equality, persistent sessions and the Phase 24 release audit.
