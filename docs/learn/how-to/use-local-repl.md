# Use the local REPL and reload pinned modules

## Prerequisites

Build or install the CLI. Have a directory containing any modules you want to import.

## Start an empty workspace

```sh
lyra repl project --source-root shared --history .lyra-history --plain
```

`project` and each `--source-root` configure discovery only. Startup remains empty. No root is initialized and `main` is not called.

Omit `--plain` on a terminal to use the JLine console. Select `--keymap emacs` or `--keymap vi` when needed.

## Use the command set

Enter these with one literal leading backslash:

```text
\help
\bindings
\type SOURCE
\load FILE
\reload MODULE
\reset
\history
\quit
```

`\type SOURCE` checks a complete source unit without executing, pinning, publishing, or recording it. `\load FILE` reads one UTF-8 file on the execution host and submits it once. Quoted paths with spaces are accepted.

Commands are recognized only at a top-level source-unit boundary. A colon-leading input such as `:Point[1 2]` or `::function[]` is ordinary Lyra source.

## Work with persistent state

Submit separately:

```lyra
let @mut count :I32 = 1
```

```lyra
count := 42
```

```lyra
count
```

The final snapshot contains `42`. Successful declarations and initialized values remain linked to their original storage. The REPL does not reconstruct state by replaying prior source.

## Correct a pinned import

Suppose `project/counter.lyra` exports `next`. Start with `lyra repl project`, then submit:

```lyra
import counter
counter->::next[]
```

The initialized module is pinned to its source revision. Editing or deleting `counter.lyra` does not replace it. Inspect committed aliases with `\bindings`, then explicitly reload:

```text
\reload counter
```

Reload resolves fresh source, reconstructs only the REPL-owned reachable dependency graph, and publishes new defaults after successful initialization. It reports scheduled, attempted, and completed initializers. A failed or cancelled reload leaves previous defaults in place, while already completed output and mutations remain.

There is no file watcher, automatic replay, state migration, or retargeting of old closures and references.

## Cancellation and reset

Ctrl+C in the managed console requests cancellation of the active evaluation. Cancellation is cooperative. Blocking `readLine` or other host I/O can delay observation.

`\reset` clears scratch bindings, aliases, and source history. Immutable snapshots already returned remain ordinary detached display data. See [Persistent sessions and snapshots](../explanations/persistent-sessions.md).
