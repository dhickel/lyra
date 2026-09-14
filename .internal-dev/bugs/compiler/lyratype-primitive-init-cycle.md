# LyraType interface static field participates in an initialization cycle with PrimitiveType

## Summary

`LyraType` (an interface) declared `PrimitiveType I32 = PrimitiveType.I32;` as a static field while `PrimitiveType` is an enum implementing `LyraType`, so the interface's field initializer reads a constant of a type whose initialization is in progress. Whether the field can be observed as `null` depends on the interface's method shape. Mechanism: when `PrimitiveType` initializes, JLS 12.4.2 initializes its superinterfaces that declare at least one default method first (the pre-SE-9/JVMS phrasing was 'at least one non-abstract, non-static method'). The runtime `LyraType` declares `default canonical`, `default spelling`, and `default isNullable`, so it was initialized while `PrimitiveType`'s constant fields were still null and its `I32 = PrimitiveType.I32` field initializer stored null. The compiler `LyraType` declares only abstract methods, so enum-first initialization did not initialize the interface at all: its field initializer ran later against the completed enum and stored the constant, in either order.

Verified module behavior (2026-09-14, see Verification Correction below): the runtime `LyraType` declares default methods (`canonical`, `spelling`, `isNullable`) and did observe `null` when `PrimitiveType` initialized first. The compiler `LyraType` declares only abstract methods, so the JVM did not initialize it first and its alias fields were not observed null. The compiler fields were still removed, because they were unsafe in structure and would become a live hazard if any default method were ever added.

## Scope

The report was filed against the compiler module, where the hazard was latent; the fix covers both modules.

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/types/LyraType.java` (static field declarations, all-abstract methods) and `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/types/PrimitiveType.java` (enum implementing the interface).
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraType.java` (static field declarations, default methods) and `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/PrimitiveType.java` (enum implementing the interface). The runtime shape is the one that deterministically observed null.
- Potential impact on any code that reads `LyraType.<primitive>` constants, including tests whose execution order differs between a clean and an incremental run.

## Reproduction

Not reproduced deterministically by the root agent: `mvn clean test` and `mvn test` both pass on the current tree. An independent validator reported that a clean isolated reactor run produced order-sensitive failures in `NominalTypeContractTest`, `NominalConstructionTest` and `NominalArtifactMetadataTest` with a null `LyraType.I32`, but the root agent could not reproduce it afterwards on a quiescent worktree. The concurrency context matters: the report was produced while another writer was editing the repository, so the failure may have been transient.

The unsafe field-initializer shape is a matter of record and inspection:

```java
// LyraType.java
PrimitiveType I32 = PrimitiveType.I32;
// PrimitiveType.java
public enum PrimitiveType implements LyraType { I32(...), ... }
```

## Expected

Primitive type constants are available regardless of class-initialization order.

## Actual

Reading `LyraType.I32` after `PrimitiveType` has been initialized first yields `null` in the runtime module, where `LyraType` declares default methods. In the compiler module, where `LyraType` declares only abstract methods, the field was not observed null: that shape's enum-first initialization does not trigger the interface's initialization, so the interface's field initializers run only when the interface is initialized, after the enum is complete. Both the module's interface method shape and the actual initialization order matter; the runtime shape is the failing one.

## Evidence

- `LyraType.java:19` declared the static field; `PrimitiveType.java:7` implements `LyraType`.
- `mvn clean test` and `mvn test` pass on the final tree, so the hazard was latent rather than currently active in the suite.
- The validator's reported failure mode (`LyraType.I32 == null` when `PrimitiveType` initializes first) matches the runtime default-method case.

## Verification Correction (2026-09-14)

An isolated-loader reproduction of the exact pre-change shape, compiled into `/tmp` with `sealed interface LyraType { PrimitiveType I32 = PrimitiveType.I32; ... }` and `enum PrimitiveType implements LyraType`, measured one order per module:

```text
compiler shape (interface methods all abstract)  enum-initialized-first -> LyraType.I32 = I32
runtime shape  (interface has default methods)  enum-initialized-first -> LyraType.I32 = null
```

The compiler `LyraType` javadoc already recorded the abstract-method shape as the reason primitive enum initialization could not recursively observe null interface constants; the runtime interface kept default methods and therefore reintroduced the hazard. The original issue title's "whichever side initializes first" wording is corrected by this measurement.

## Impact

- Latent order-dependent null type: could produce a wrong or crashing compilation path with no source-level cause.
- Fail-loud risk is uncertain; no wrong artifact was observed.

## Status

Resolved in the working tree; the GitHub issue stays OPEN until its implementation commit and grouped validation evidence are posted. Both `LyraType` interfaces declare no constant fields, all repository consumers read `PrimitiveType` directly, and the unused `PrimitiveType.Bool/Char/String/Unit` aliases are removed. Isolated-loader tests (a `URLClassLoader` whose only parent is the platform class loader) now initialize each type first in both modules and assert all fourteen constants and their type operations under both orders, and they assert the removed fields are absent. See `.internal-dev/changelogs/2026-09-14-primitive-constant-ownership.md`.

## Next Action

Post the implementation commit and validation evidence on GitHub issue #13, then archive this report after the issue is closed.
