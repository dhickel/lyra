# Phase 15 JVM Emitter Repairs

- Repaired reference equality and nullable state presence/payload accessor selection.
- Added unsigned normalization for U8/U16 equality and repeated binary-fold operands.
- Preserved signed I32/I64 minimum literals represented as unary-minus magnitudes.
- Added adversarial Phase 15 smoke tests for these boundaries.
- Validated with the focused suite, the complete compiler suite, the full Maven reactor, and an external Java 25 `-Xverify:all` invocation.
