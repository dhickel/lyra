# Build classes and JARs

## Prerequisites

Build or install the CLI. Decide whether the output is a library or a runnable application.

## Compile an artifact

```sh
lyra compile app.lyra --output build/app.jar
```

The defaults are:

- format: `bundled-jar`
- Java package: `lyra.generated`
- output when omitted: `build/lyra/<root-name>.jar`

A bundled JAR needs the exact public `main :Fn<Array<String>;I32>` entry point.

## Select a layout

Class directory:

```sh
lyra compile library.lyra \
  --format classes \
  --output build/library-classes
```

Thin JAR:

```sh
lyra compile library.lyra \
  --format thin-jar \
  --output build/library-thin.jar
```

Bundled runnable JAR:

```sh
lyra compile app.lyra \
  --format bundled-jar \
  --output build/app.jar
java -jar build/app.jar
```

A thin JAR contains generated classes and metadata but not a standalone runtime launcher. Put it beside a compatible `lyra-runtime` on the Java class path. A bundled JAR includes the runtime and launcher.

## Include source and choose a Java package

```sh
lyra compile library.lyra \
  --format thin-jar \
  --output build/library.jar \
  --include-sources \
  --java-package com.example.generated
```

`--include-sources` embeds source snapshots used by the artifact. `--java-package` changes generated Java names and participates in artifact identity.

## Replace an output safely

Compilation refuses an existing output by default:

```sh
lyra compile app.lyra --output build/app.jar
```

Use `--force` only after confirming that the path is the artifact you intend to replace:

```sh
lyra compile app.lyra --output build/app.jar --force
```

Publication is staged and replaces the destination atomically. The CLI rejects output symlinks and mismatched file/directory kinds.

## Debug-capable artifacts

```sh
lyra compile app.lyra --repl --format bundled-jar --output build/app-debug.jar
```

This records REPL capability and embedded context but does not open a listener. Activation is a separate runtime action described in [Attach to an application](attach-to-application.md).
