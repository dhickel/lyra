# Tutorial 1: Build and run your first program

**Outcome:** build Lyra, create one executable source file, and run it.

## Prerequisites

- Java 25
- Maven 3.9 or newer
- A repository checkout

## 1. Build and install the CLI

From the repository root:

```sh
mvn package
./tools/install-lyra.sh
export PATH="$HOME/.local/bin:$PATH"
lyra --version
```

For a repository-local command, replace `lyra` below with:

```sh
java -jar lyra-cli/target/lyra-cli-0.1.1.jar
```

## 2. Create `hello.lyra`

```lyra
import std->io as io

let @pub main :Fn<Array<String>;I32> = (=> |args| {
    io->::println["Hello, Lyra"]
    0
})
```

`std->io` is Lyra's compiler-owned console I/O module. The executable entry point must be the public function `main :Fn<Array<String>;I32>`.

## 3. Run it

```sh
lyra run hello.lyra
```

Expected output:

```text
Hello, Lyra
```

The process requests exit status `0` because `main` returns `0`.

## If it fails

- `main` must be public and have exactly the signature shown above.
- Keep a space before a named type annotation and no space after its colon: `main :Fn<...>`.
- Imports must appear before executable declarations.

Next: [Bind and update values](02-bindings-and-mutation.md). See the [language and CLI reference map](../reference.md).
