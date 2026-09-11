# Three-Module Maven Foundation

## Date

2026-08-30

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Replaced the prototype root Maven artifact with a Java 25 parent/aggregator for the `lyra-runtime`, `lyra-compiler`, and `lyra-cli` classpath modules. Centralized compiler, preview, test-plugin, JMH, encoding, and reproducible-output settings without moving or compiling the prototype source tree.

## Files

- `pom.xml`
- `lyra-runtime/pom.xml`
- `lyra-compiler/pom.xml`
- `lyra-cli/pom.xml`

## Behavioral Impact

No Lyra language, compiler, runtime, or CLI behavior was added. The old root `src` tree remains outside the reactor because the root project now has `pom` packaging.

## Specification Impact

None. This change establishes the Maven layout required by the accepted implementation plan and backend/runtime contract without changing either living specification.

## Risks

The three module source and test sets are intentionally empty until later implementation domains migrate behavior into their owning artifacts.

## Follow-up Items

Implement subsequent plan domains within the three module boundaries; do not restore the prototype root source set as a second product surface.
