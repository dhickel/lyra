# LyraType interface static field participates in an initialization cycle with PrimitiveType

## Summary

`LyraType` (an interface) declares `PrimitiveType I32 = PrimitiveType.I32;` as a static field while `PrimitiveType` is an enum implementing `LyraType`. The two types therefore form a class-initialization cycle: whichever side initializes first can observe the other's static field as `null`, so `LyraType.I32` is conditionally null depending on which class the JVM initializes first.

## Scope

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/types/LyraType.java` (static field declarations) and `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/types/PrimitiveType.java` (enum implementing the interface).
- Potential impact on any code that reads `LyraType.<primitive>` constants, including tests whose execution order differs between a clean and an incremental run.

## Reproduction

Not reproduced deterministically by the root agent: `mvn clean test` and `mvn test` both pass on the current tree. An independent validator reported that a clean isolated reactor run produced order-sensitive failures in `NominalTypeContractTest`, `NominalConstructionTest` and `NominalArtifactMetadataTest` with a null `LyraType.I32`, but the root agent could not reproduce it afterwards on a quiescent worktree. The concurrency context matters: the report was produced while another writer was editing the repository, so the failure may have been transient.

The static-initialization cycle itself is a matter of record and inspection:

```java
// LyraType.java
PrimitiveType I32 = PrimitiveType.I32;
// PrimitiveType.java
public enum PrimitiveType implements LyraType { I32(...), ... }
```

## Expected

Primitive type constants are available regardless of class-initialization order.

## Actual

Reading `LyraType.I32` before `PrimitiveType` finishes initializing yields `null`. Depending on JVM initialization order this can surface as a null type in any consumer.

## Evidence

- `LyraType.java:19` declares the static field; `PrimitiveType.java:7` implements `LyraType`.
- `mvn clean test` and `mvn test` pass on the final tree, so the hazard is latent rather than currently active in the suite.
- The validator's reported failure mode (`LyraType.I32 == null` when `PrimitiveType` initializes first) matches the cycle exactly.

## Impact

- Latent order-dependent null type: could produce a wrong or crashing compilation path with no source-level cause.
- Fail-loud risk is uncertain; no wrong artifact was observed.

## Status

Open, latent. Discovered during phase-5 validation of the retained nominal work; not caused by that work and not currently reproducible in the suite.

## Next Action

Remove the cycle by moving primitive constants onto the enum (or an enum-owned holder) and referencing them through `PrimitiveType` directly, then add a regression that loads `PrimitiveType` first in an isolated class loader and asserts every primitive constant is non-null.
