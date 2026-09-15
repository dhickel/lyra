# Tutorial 9: Use a persistent REPL

**Outcome:** keep initialized values across submissions, inspect the workspace, load files, reload pinned modules, reset, and cancel work.

## Prerequisites

Complete Tutorial 3. For module reload, also complete [Tutorial 6](06-modules.md).

## 1. Start an empty local session

```sh
lyra repl --plain
```

A local REPL starts with an empty scratch workspace. A positional directory and `--source-root` configure discovery only. They do not load a file, initialize a root, or call `main`.

Submit these as three separate inputs:

```lyra
let @mut count :I32 = 1
```

```lyra
count := 2
```

```lyra
count
```

The final result is an `I32` snapshot containing `2`. Earlier source is not replayed.

## 2. Inspect without executing

Enter these literal backslash commands:

```text
\bindings
\type count := 100
```

`\bindings` lists committed names and types. `\type` analyzes source against the current context but does not execute, pin imports, publish declarations, or enter source history. A later `count` still produces `2`.

Use `\history` to inspect source submissions. Commands and program input are not source history.

## 3. Load and import modules explicitly

To submit a UTF-8 file once:

```text
\load path/to/file.lyra
```

To discover a module under a configured root, submit a normal import header:

```lyra
import math->basic as math
math->::double[21]
```

An initialized imported module is pinned to its exact source revision. Editing its file does not update the pin.

Move a REPL-owned module to fresh source explicitly:

```text
\reload math->basic
```

Reload constructs a fresh reachable module graph and publishes new defaults only after success. Existing closures, selected imports, and old references keep their original producers.

## 4. Reset and cancel

```text
\reset
```

Reset clears scratch declarations, aliases, and history. It does not replay source.

In an interactive console, Ctrl+C requests cancellation of the active evaluation. Cancellation is cooperative, and blocking host I/O can delay it. Completed output and mutations remain even though names staged by a cancelled submission are not published.

Use `\help` for the command list and `\quit` to leave.

Next: [Package code and call it from Java](10-packaging-and-java.md). See [Use the local REPL](../how-to/use-local-repl.md) for operational details.
