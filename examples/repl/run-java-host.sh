#!/usr/bin/env bash
# Compile and run the Java-host REPL example against the reactor output.
# Requires a packaged reactor (mvn clean verify) so module target/classes exist.
set -Eeuo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
OUT=target/examples-repl-java-host
mkdir -p "$OUT"
javac -d "$OUT" \
    -cp lyra-runtime/target/classes:lyra-compiler/target/classes:lyra-repl/target/classes \
    examples/repl/HostExample.java
java -Xverify:all \
    -cp "$OUT":lyra-runtime/target/classes:lyra-compiler/target/classes:lyra-repl/target/classes \
    HostExample
