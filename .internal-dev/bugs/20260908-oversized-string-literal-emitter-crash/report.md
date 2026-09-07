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

Open.

## Next Action

Add an emit-phase preflight or sealed-IR validation for `CONSTANT_Utf8` length (65535 bytes modified UTF-8 per literal, accounting for UTF-16 to modified-UTF-8 expansion) and return the structured diagnostic before class emission. Route through a dedicated phase gate if the compiler model requires it; do not silently split or intern literals.
