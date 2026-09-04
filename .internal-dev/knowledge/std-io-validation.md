# std->io Validation Lessons

## Topic

Phase 20 intrinsic I/O and compiler/runtime integration.

## Source References

- `.internal-dev/specifications/backend-runtime.md`, intrinsic `std->io` and runtime I/O sections.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraIo.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/RuntimeIoEnvironment.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/Phase20IoTest.java`

## Key Takeaways

- When runtime sources change, compiler-only Maven tests may resolve a stale installed sibling snapshot. Install the runtime or use a reactor invocation with `-am` before treating missing generated runtime classes as a source defect.
- Intrinsic calls must lower through the existing validated IR and emitter to exact runtime descriptors. The runtime authority supplies the immutable I/O environment and performs owner/lifecycle checks before touching streams.
- Input decoding must retain decoder state across byte reads so split multibyte sequences, BOM handling, malformed input, terminal CRLF, lone CR, partial EOF, and clean EOF remain distinguishable.
- Output encoding and flush failures are one structured `LYR-IO` boundary; configured streams are caller-owned and are never closed by Lyra.

## Project Relevance

These rules preserve the direct intrinsic boundary without adding a second evaluator, generic host callback path, mutable global stream state, or CLI behavior. Future launcher/CLI work must reuse the same explicit runtime environment.

## Open Questions

The later launcher phase must define command-level stream wiring while retaining the runtime’s exact byte and ownership semantics.
