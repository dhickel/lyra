# JVM Emitter Validation Knowledge

## Phase 15 scalar emitter invariants

- `U8` and `U16` use JVM `byte`/`short` raw storage. JVM narrowing after a valid result can leave a negative signed local (for example U8 255 is `-1`). Every later arithmetic, equality, or comparison that interprets a narrow unsigned local must normalize it with the unsigned mask before use. Normalizing only initial operands is insufficient for variadic left folds and exponentiation.
- The semantic checker intentionally retains a signed minimum literal as its positive magnitude under a unary-minus operator (`2147483648I32`, `9223372036854775808I64`). The generic runtime minimum-overflow check cannot distinguish those magnitudes from wrapped runtime minima, so the emitter must materialize the negated constant directly before entering that path.
- Nullable state storage is represented as separate presence and payload members. Generated getter dispatch must identify the exact `$isPresent$` accessor rather than relying on a broad `$present` substring.
- Reference value equality is consistently emitted through `Objects.equals`; primitive identity/equality paths remain separate.

## Validation pattern

Use both Class-File API loading/invocation tests and an external `java -Xverify:all` process. The external check should include a nontrivial generated method, not only a constant getter, because invalid stack shapes can hide on unexecuted branches.
