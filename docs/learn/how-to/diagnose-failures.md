# Diagnose compiler and runtime failures

## Prerequisites

Reproduce the failure with the same source root and command. Keep the rendered code, source label, location, excerpt, and Lyra frames.

## Read a compiler diagnostic

Compiler failures use stable `LYC-<PHASE>-<NUMBER>` codes and source spans. Start with the first blocking diagnostic for the attempted phase.

Common checks:

- **Syntax:** use `//` comments, balance delimiters, and remember that parentheses mean callable application rather than general grouping.
- **Type annotations:** write `name :Type`, not `name:Type` or `name : Type`.
- **Names:** declarations are source-ordered. Imports must appear in the header and only public exports can cross a module boundary.
- **Types:** Lyra does not use `Any` or implicit lossy conversion. Add a valid explicit conversion such as `I32[value]` when required.
- **Calls:** check exact arity and parameter contracts. Built-ins use bare heads such as `+[a b]`, `match[...]`, `cond[...]`, `iter[...]`, and `while[...]`; they do not use `::`.
- **Entry point:** `LYC-PACKAGE-001` means a runnable root lacks exactly one public `main :Fn<Array<String>;I32>`.

Use `\type SOURCE` in a REPL to check source against committed context without executing or publishing it.

## Read a runtime failure

Runtime failures are unchecked and source-mapped. Frequent categories include:

- `LYR-ARITH`: overflow, underflow, division or remainder by zero, zero range step, or non-finite floating result
- `LYR-BOUNDS`: invalid string or array index
- `LYR-CONVERT`: an invalid runtime conversion
- `LYR-IO`: stream, encoding, input, or interruption failure
- `LYR-STACK`: non-tail recursion exhausted the JVM stack
- `LYR-INIT`: module initialization failed
- `LYR-THREAD`: a module, closure, or handle was used from the wrong thread
- `LYR-CLOSED`: a closed module or retained value was used
- `LYR-LIFECYCLE`: resources were closed in the wrong order or while dependents remained open
- `LYR-LINK`, `LYR-VERIFY`, `LYR-COMPAT`: callable authority, class linkage, verification, metadata, Java profile, or ABI mismatch

The first frame identifies the failing Lyra location. Later frames show the source-level call path. Missing embedded source text does not invalidate a frame.

## Preserve effect boundaries

A compile, type, or link failure executes no submitted Lyra source. A runtime failure or cooperative cancellation is different: completed output and mutations remain, while names staged by the failed submission are not published. Do not retry under the assumption that effects rolled back.

## Java callers

Catch `LyraRuntimeException` when you need structured data:

```java
try {
    // exact Lyra invocation
} catch (io.mindspice.lyra.runtime.LyraRuntimeException failure) {
    System.err.print(failure.render());
}
```

API misuse such as a wrong export name/signature can also produce `IllegalArgumentException`. Artifact filesystem operations can throw `IOException`. Compiler invariant failures use `LyraCompilerBugException` rather than a normal source diagnostic.

See the [reference map](../reference.md) for complete contracts and the [testing guide](../../language-testing.md) for executable evidence.
