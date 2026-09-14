# Public API Validation Lessons

## Topic

Phase 19 compiler/runtime API validation and generated-facade publication.

## Source References

- `.internal-dev/specifications/backend-runtime.md`, public Java API and artifact metadata sections.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/artifact/ArtifactAssembly.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntime.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/Phase19PublicApiTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/types/PrimitiveTypeInitializationTest.java` and `lyra-runtime/src/test/java/io/mindspice/lyra/runtime/PrimitiveTypeInitializationTest.java` (isolated initialization-order regressions)

## Key Takeaways

- Public compilation options affect module revisions. Artifact assembly must preserve graph-owned revisions instead of recomputing revisions with empty options.
- Generated facades initially carry provisional metadata. Replacing a class-file `CONSTANT_Utf8` requires rebuilding the class-file tail because the final canonical JSON length can differ; the replacement must validate the class-file constant-pool structure.
- A content-prefix search for the provisional metadata can collide with a user string constant. A compiler-owned provisional marker makes the replacement target unambiguous in normal generated output.
- Function export metadata must expose the exact generated invocation member. Reserved facade names such as `close` therefore use `invoke$close` in the runtime export metadata and Java-name map, while getter names continue to use the base mangled export name.
- A loaded-artifact context must count in-progress instantiations while a factory runs. Otherwise a concurrent context close can clear the child loader before the new module instance is registered.
- Primitive constant ownership lives on `PrimitiveType` alone. A constant field on the `LyraType` interface initialized from the enum that implements it is unsafe in a way that depends on the interface's method shape. Mechanism: when `PrimitiveType` initializes, JLS 12.4.2 initializes its superinterfaces that declare at least one default method first (the pre-SE-9/JVMS phrasing was 'at least one non-abstract, non-static method'). The runtime `LyraType` declares `default canonical`, `default spelling`, and `default isNullable`, so it was initialized while `PrimitiveType`'s constant fields were still null and its `I32 = PrimitiveType.I32` field initializer stored null. The compiler `LyraType` declares only abstract methods, so enum-first initialization did not initialize the interface at all: its field initializer ran later against the completed enum and stored the constant, in either order. Measured on isolated copies of both pre-change shapes (JDK 25): runtime shape enum-first `LyraType.I32 = null`, interface-first `I32`; compiler shape `I32` in both orders, because the null is never produced there. Interface static helpers (`array`, `tuple`, `function`, `qualified`, `parse`) are safe; interface *constant fields* initialized from an implementing type are not, and an all-abstract-method interface is not evidence that such a field is safe.
- A genuine initialization-order regression needs an isolated loader whose only parent is the platform class loader. `Class.forName(name, true, loader)` still delegates to that parent first, which is exactly why isolation is real: the platform loader cannot resolve project classes, so the class is defined in the loader instead of reusing the application-loaded copy. `Class.forName(name, true, loader)` with `parent = ClassLoader.getPlatformClassLoader()` over a URL list whose first entry is the module's own classes directory (not `java.class.path`, whose order under Surefire is not the reactor's) is what actually forces the order; a parent that can already resolve the class silently reuses the initialized copy. Assert the loaded class's `getClassLoader()` and that it is not identical to the application-loader copy, or the test proves nothing.
- A removed-public-member regression must assert absence by name (`getField(name)` throwing `NoSuchFieldException`) and by declared-field count of *static* fields. Counting all declared fields also counts enum instance fields, which silently fails a correct change.
- Class-directory, thin-JAR, in-memory, and ordinary Java-consumer paths should all validate the same published metadata and exact bound method-handle behavior.

## Project Relevance

These checks cover the Phase 19 boundary without expanding into Phase 20 intrinsic I/O or later CLI behavior. They are reusable when adding new facade names, metadata options, or loader lifecycle operations.

## Open Questions

Bundled-JAR loading remains intentionally fail-closed until the later launcher phase supplies `LyraLauncher`.
