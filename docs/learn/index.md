# Learn Lyra

This section separates learning material by purpose. Start with the tutorials if you have not written Lyra before. Use the how-to guides for a specific task, the explanations for the language model, and the reference links for exact rules.

## Prerequisites

- Java 25
- Maven 3.9 or newer when building from this repository
- A checkout of this repository for the commands and examples below

Build and install the current checkout:

```sh
mvn package
./tools/install-lyra.sh
export PATH="$HOME/.local/bin:$PATH"
lyra --version
```

You can use `java -jar lyra-cli/target/lyra-cli-0.1.0.jar` instead of an installed `lyra` command. The tutorials use `lyra` for brevity.

## Tutorials

Follow these in order:

1. [Build and run your first program](tutorials/01-first-program.md)
2. [Bind and update values](tutorials/02-bindings-and-mutation.md)
3. [Write and call functions](tutorials/03-functions.md)
4. [Work with values and collections](tutorials/04-values-and-collections.md)
5. [Control evaluation](tutorials/05-control-flow.md)
6. [Split a program into modules](tutorials/06-modules.md)
7. [Define structs and classes](tutorials/07-structs-and-classes.md)
8. [Traverse ranges and repeat work](tutorials/08-ranges-and-loops.md)
9. [Use a persistent REPL](tutorials/09-persistent-repl.md)
10. [Package code and call it from Java](tutorials/10-packaging-and-java.md)

Tutorials 1 through 5 cover the basic language. After those, use the [reference map](reference.md) for lookup and continue only with the product workflows you need.

## How-to guides

- [Run programs, pass arguments, and define `main`](how-to/run-and-main.md)
- [Configure source roots and imports](how-to/modules-and-imports.md)
- [Build classes and JARs](how-to/package-artifacts.md)
- [Compile, load, and invoke Lyra from Java](how-to/use-java-api.md)
- [Use the local REPL and reload pinned modules](how-to/use-local-repl.md)
- [Attach to a running application](how-to/attach-to-application.md)
- [Use the editor](how-to/use-editor.md)
- [Diagnose compiler and runtime failures](how-to/diagnose-failures.md)

## Explanations

- [Static typing and conversions](explanations/static-typing.md)
- [Binding-local mutation](explanations/mutation.md)
- [Evaluation order and laziness](explanations/evaluation-order.md)
- [Calls and the three accessors](explanations/calls-and-accessors.md)
- [Functions, closures, and saved methods](explanations/closures-and-methods.md)
- [Nominal and structural data](explanations/nominal-and-structural-types.md)
- [Module instances and reload](explanations/module-instances.md)
- [Direct bytecode and Java facades](explanations/bytecode-and-java.md)
- [Persistent sessions and snapshots](explanations/persistent-sessions.md)
- [Trusted attachment](explanations/trusted-attachment.md)
- [Editor process and debugger boundaries](explanations/editor-execution.md)

## Reference

The [reference map](reference.md) links to the current formal language contract, readable grammar, CLI and operational references, and test evidence. Learning pages intentionally do not reproduce complete operator, type, command, or diagnostic tables.
