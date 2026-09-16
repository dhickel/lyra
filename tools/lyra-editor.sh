#!/usr/bin/env bash
set -euo pipefail
LYRA_EDITOR_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
LYRA_EDITOR_JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
if [[ ! -f "$LYRA_EDITOR_ROOT/lyra-editor/target/lyra-editor-0.1.1.jar" || ! -d "$LYRA_EDITOR_ROOT/lyra-editor/target/lib" ]]; then
    mvn -f "$LYRA_EDITOR_ROOT/pom.xml" -pl lyra-editor -am package -DskipTests
fi
exec "$LYRA_EDITOR_JAVA" --enable-preview --add-modules=jdk.jdi --enable-native-access=ALL-UNNAMED \
    -cp "$LYRA_EDITOR_ROOT/lyra-editor/target/*:$LYRA_EDITOR_ROOT/lyra-editor/target/lib/*" \
    io.mindspice.lyra.editor.EditorLauncher "$@"
