# Run a program, pass arguments, and define `main`

## Prerequisites

Build or install the CLI. See [Tutorial 1](../tutorials/01-first-program.md).

## Define the entry point

An executable root must export exactly this contract:

```lyra
let @pub main :Fn<Array<String>;I32> = (=> |args| {
    args:.length
})
```

Run it and pass program arguments only after `--`:

```sh
lyra run main.lyra -- first "two words" --literal-option
```

This `main` returns `3`, so the process requests exit status `3`. Tokens before `--` are Lyra CLI options. Tokens after it are passed to `main` unchanged.

A logical root also works when a source root maps it to a file:

```sh
lyra run app->main --source-root src -- first second
```

## Diagnose entry failures

`run` and bundled-JAR packaging fail with `LYC-PACKAGE-001` unless the root has one public callable export named `main` with the exact `Fn<Array<String>;I32>` contract. Common invalid forms are:

```lyra
let main :Fn<Array<String>;I32> = ...       // private
let @pub main :Fn<;I32> = ...              // wrong parameter list
let @pub main :Fn<Array<String>;I64> = ...  // wrong return type
```

Classes and thin JARs may be used as libraries without `main`. The CLI's default `bundled-jar` format is runnable and therefore requires it.

A normally returned `I32` is the requested process status. A compiler or runtime diagnostic is a tool failure instead. See [Diagnose failures](diagnose-failures.md).
