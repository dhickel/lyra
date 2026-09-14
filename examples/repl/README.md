# Runnable REPL Examples

These sources drive the documented workflows end to end. All commands assume the
reactor is packaged (`mvn clean verify`) and run from this directory with
`CLI=../lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar`.

- `counter.lyra`, `shapes.lyra`, `main-counter.lyra`: module imports, pinned
  reuse, `std->io`, and `\reload` via the local console.
- `app-counter.lyra`: an attachable REPL-capable application for
  `run --repl`/`attach` and compiled `-Dlyra.repl.enabled=true` activation.
- `HostExample.java`: the synchronous Java evaluation API plus explicit
  attachment polling (compile with `javac`, run with the reactor test classes
  on the classpath; see `examples/repl/run-java-host.sh`).
- `run-java-host.sh`: scripted Java-host demonstration.

## 1. Local console: import, state, std->io, reload

```sh
java -jar $CLI repl .    # empty scratch workspace; this directory is only a search root
```

```lyra
import counter
counter->::next[]          ; 1
counter->::next[]          ; 2  -- pinned instance, no rerun of the initializer
import std->io io->::println["hi "]
\bindings                  ; committed declarations/types only
\reload counter            ; fresh closure, reports scheduled/attempted/completed
counter->::next[]          ; continues from the rebuilt module's initializer
\quit
```

Edit `counter.lyra` between `\reload` calls to observe changed topology; the old
value already bound into scope keeps its original producer. The example uses
`;` for presentation comments; Lyra source itself uses `//` comments.

## 2. Imported aggregates and callable values

```lyra
import shapes
let f = shapes->::maker[]   // real imported function instance
(f 2)                       // 4 (area of a 2x2 square)
let points = shapes->:.points  // live imported aggregate
shapes->:.points[0] := 99   // LYC-RESOLVE-022 ownership diagnostic
```

## 3. Run/compile attachable activation

```sh
# Terminal A: attachable run; endpoint and the execution-authority warning print here
java -jar $CLI run app-counter.lyra --repl --repl-port 0

# Terminal B: attach without any token
java -jar $CLI attach 127.0.0.1:PORT    # PORT from terminal A
# > count                    -- reads real root storage
# > count := 41              -- writes the real public @mut root field; main observes it
# > \quit                    -- application main resumes and exits normally

# Compiled capability (never listens):
java -jar $CLI compile app-counter.lyra --repl --format bundled-jar --output app-counter.jar
java -Dlyra.repl.enabled=true -Dlyra.repl.port=0 -jar app-counter.jar
```

`compile --repl` records capability and embedded debug context only; it never
starts a listener. Absent `-Dlyra.repl.enabled=true` the artifact runs an
ordinary main with no listener.

## 4. Java host

```sh
./run-java-host.sh
```

The script compiles `HostExample.java` and drives a local session (persistent
counter, cancellation identity, snapshots) and an attached root (explicit
owner-thread poll, live `count` mutation, service reopen).
