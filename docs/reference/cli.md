# Command-line interface

The installed `lyra` launcher requires Java 25. Product version is `0.1.0`.

## Commands

```text
lyra repl [DIR] [--source-root DIR]* [--history PATH] [--plain] [--keymap emacs|vi]
lyra attach HOST:PORT
lyra run ROOT [--source-root DIR]* [--repl] [--repl-port PORT] [--repl-wait] [-- ARGS...]
lyra compile ROOT [--source-root DIR]* [--output PATH]
  [--format classes|thin-jar|bundled-jar]
  [--java-package PACKAGE] [--include-sources] [--force] [--repl]
lyra --help
lyra --version
```

The installed launcher maps no arguments to `repl`, so `lyra` opens the local REPL. The Java CLI entry point and the repository launcher script require an explicit command; use `java -jar lyra-cli-0.1.0.jar repl` or `lyra repl` when bypassing the installed wrapper. A no-argument direct invocation reports usage status 2.

## `repl` and `attach`

`repl` opens an empty workspace. `DIR` and repeatable `--source-root` values must be existing directories and configure discovery only. `--history` selects an optional source-only history file. `--plain` disables the JLine console. `--keymap` accepts `emacs` or `vi`.

`attach` requires one loopback `HOST:PORT` endpoint and accepts no options or credentials. IPv6 uses `[HOST]:PORT`. The Unix and Windows launchers use `JAVA_COMMAND` when set, always add `--enable-native-access=ALL-UNNAMED`, and append options from `LYRA_JAVA_OPTS`. Set `LYRA_JAVA_OPTS=--enable-preview` when starting an artifact whose metadata requires preview. See [REPL and attachment](repl.md).

## `run`

`ROOT` is an existing `.lyra` path or a logical chain resolved through source roots. A path root adds its parent as the implicit source root only when no explicit source root is given. In a shell, quote logical roots containing `->` to prevent redirection. Only tokens after `--` become `main` arguments.

The exact executable contract is:

```lyra
let @pub main :Fn<Array<String>;I32> = (=> |args| 0)
```

`run` compiles and loads in memory, invokes `main`, and closes the module and loading context. A successful `main` return becomes the requested process exit value unchanged at the JVM boundary. Operating systems may narrow it.

`--repl` selects attachable compilation and starts an unauthenticated loopback listener. `--repl-port` accepts `0..65535`, defaults to ephemeral port `0`, and requires `--repl`. `--repl-wait` also requires `--repl` and waits after root registration for a completed protocol-v2 controller handshake before calling `main`.

## `compile`

| Option | Default | Meaning |
| --- | --- | --- |
| `--source-root DIR` | none | Repeatable logical import root |
| `--output PATH` | `build/lyra/<root-name>.jar` | Output directory or JAR path |
| `--format` | `bundled-jar` | `classes`, `thin-jar`, or `bundled-jar` |
| `--java-package` | `lyra.generated` | Base package for generated classes |
| `--include-sources` | off | Embed source snapshots |
| `--force` | off | Replace an existing compatible output target |
| `--repl` | off | Publish attachable debug capability; never starts a listener |

A bundled JAR requires the exact public `main`. Class directories and thin JARs can be libraries without `main`. Thin JARs require a compatible external `lyra-runtime`; bundled JARs include the runtime and launcher. Run an ordinary bundle with `java -jar app.jar`. If metadata says preview is required, use `java --enable-preview -jar app.jar`.

Output publication is staged and replacement is atomic. Existing output is refused without `--force`. A classes target must be a directory; a JAR target must be a regular file; output symlinks are rejected.

## Exit status

| Status | Meaning |
| --- | --- |
| `0` | Help/version, successful compilation, or `main` returned 0 |
| `1` | Source/package/runtime diagnostic, or a plain-console run with recoverable evaluation failures |
| `2` | Invalid usage/configuration, artifact/output I/O, compiler infrastructure, or deployment compatibility failure |
| other | A successfully invoked `main` returned that `I32` value |

If invocation and cleanup both fail, invocation remains primary and cleanup is reported as suppressed. A sole cleanup failure is status 1. Diagnostics go to stderr; program I/O uses its configured streams.

See [artifacts and Java](artifacts-and-java.md) for output contents and [diagnostics](diagnostics.md) for failure rendering.
