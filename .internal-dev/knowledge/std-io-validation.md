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
- Plain REPL source entry must consume the shared `RuntimeIoEnvironment` decoder one byte at a time. A `BufferedReader` or a second `InputStreamReader` can consume queued program bytes, especially when a source line, `readLine`, and the next source submission are adjacent.
- Rich JLine source entry must keep one terminal reader owner. The program-input bridge resumes the terminal only during generated `readLine`, pauses after LF, and bypasses completion/highlighting/history so queued program input is not lost to source editing.
- The local CLI creates one UTF-8 environment and passes that exact instance to both `SessionOptions` and `PlainConsole`; attach creates a separate local-only environment and never transports it over the remote protocol.

## Project Relevance

These rules preserve the direct intrinsic boundary without adding a second evaluator, generic host callback path, mutable global stream state, or CLI behavior. Future launcher/CLI work must reuse the same explicit runtime environment.

## Open Questions

The later launcher phase must define command-level stream wiring while retaining the runtime’s exact byte and ownership semantics. Focused REPL/CLI checks can pass against installed sibling artifacts when unrelated compiler work prevents a full reactor build; report that limitation rather than treating callable-linkage failures or stale artifacts as console regressions. In this worktree the full reactor is blocked by a syntax error at `lyra-compiler/.../SemanticFlowFacts.java:208`, and a later standalone REPL lifecycle compile also saw the unrelated `ConsoleSession.java:192`/`SourceOrigin.version()` mismatch.
