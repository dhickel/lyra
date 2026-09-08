# Oversized string literal crashes the JVM emitter instead of diagnosing

## Summary

A string literal whose modified-UTF-8 constant-pool encoding exceeds the JVM limit escapes the compiler API as `LyraCompilerBugException` instead of a structured emit-phase diagnostic.

## Scope

Pre-existing emitter/constant-pool boundary, discovered while validating Phase 08 dynamic result truncation. No emitter source was changed by Phase 08.

## Reproduction

```java
try (var session = LyraSession.open()) {
    session.submit("a.lyra", "let @mut count :I32 = 1");
    String huge = "x".repeat(64 * 1024);
    session.submit("b.lyra", "{ count := 2 \"" + huge + "\" }");
}
```

## Expected

A structured compilation failure (for example `LYC-EMIT-*`) reporting that the literal exceeds the JVM `CONSTANT_Utf8` limit, published before any source effect runs.

## Actual

`LyraCompilerBugException: session compiler invariant failed outside a phase boundary`, caused by `java.lang.IllegalArgumentException: string too long` at `jdk.internal.classfile.impl.BufWriterImpl.writeUtfEntry` during `JvmBytecodeEmitter.emitClass`.

## Evidence

Reproduced with plain `LyraSession` (no managed adapter) on baseline `59d787d`: `64k literal -> LyraCompilerBugException: java.lang.IllegalArgumentException: string too long`. A 20 KiB literal compiles, executes, and truncates correctly at the snapshot boundary.

## Impact

A reachable user input (a long string literal) crashes the compiler with an infrastructure failure instead of an ordinary source diagnostic. It does not corrupt state; the session remains usable after the exception. The Phase 08 managed-console tests use a 20 KiB literal, which exceeds the 16 KiB render budget and exercises the dynamic truncation contract without hitting this limit.

## Status

Resolved by Phase 13 on baseline `9ee3f02acda2988752b737ee8aac057f09e633ae`. The emitter now preflights each string literal's exact JVM modified-UTF-8 length and returns `LYC-EMIT-001` at the literal span before class emission. The session regression proves an earlier mutable value remains unchanged. Mirrored and closed as https://github.com/dhickel/lyra/issues/3 after Java 25 `mvn -q clean verify` passed on 2026-09-08.

## Next Action

None. Preserve the 65,535-byte boundary and astral-character regression; do not split literals or convert this expected source limit into an infrastructure failure.
