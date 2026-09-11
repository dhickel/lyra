#!/usr/bin/env bash
# Build a native application image on the platform that will run it.
set -euo pipefail
LYRA_EDITOR_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
LYRA_EDITOR_PACKAGE=${JAVA_HOME:+$JAVA_HOME/bin/}jpackage
LYRA_EDITOR_DEST=${1:-"$LYRA_EDITOR_ROOT/lyra-editor/target/native"}
LYRA_EDITOR_LINK_ARGS=(
    --add-modules java.se,jdk.compiler,jdk.jdi,jdk.jdwp.agent,jdk.unsupported,jdk.unsupported.desktop,jdk.zipfs,jdk.charsets,jdk.localedata,jdk.crypto.ec,jdk.accessibility
    --jlink-options "--strip-debug --no-header-files --no-man-pages"
)
mvn -f "$LYRA_EDITOR_ROOT/pom.xml" -pl lyra-editor -am package -DskipTests
LYRA_EDITOR_STAGE=$(mktemp -d "$LYRA_EDITOR_ROOT/lyra-editor/target/jpackage-stage.XXXXXXXX")
trap 'rm -rf -- "$LYRA_EDITOR_STAGE"' EXIT
LYRA_EDITOR_INPUT="$LYRA_EDITOR_STAGE/app"
mkdir -p -- "$LYRA_EDITOR_INPUT"
if [[ -n "${LYRA_EDITOR_RUNTIME_IMAGE:-}" ]]; then
    # Distribution-managed JDKs may link conf/security/time-zone data into /etc
    # or /usr/share. Resolve those links so the application owns its runtime.
    cp -RL -- "$LYRA_EDITOR_RUNTIME_IMAGE" "$LYRA_EDITOR_STAGE/runtime"
    LYRA_EDITOR_LINK_ARGS=(--runtime-image "$LYRA_EDITOR_STAGE/runtime")
fi
cp -- "$LYRA_EDITOR_ROOT/lyra-editor/target/lyra-editor-1.0-SNAPSHOT.jar" "$LYRA_EDITOR_INPUT/"
cp -R -- "$LYRA_EDITOR_ROOT/lyra-editor/target/lib" "$LYRA_EDITOR_INPUT/lib"
"$LYRA_EDITOR_PACKAGE" --type app-image --name LyraEditor --app-version 1.0 \
    --description "Lyra source editor, REPL and JVM debugger" --vendor "Mindspice" \
    --input "$LYRA_EDITOR_INPUT" --dest "$LYRA_EDITOR_DEST" \
    --main-jar lyra-editor-1.0-SNAPSHOT.jar --main-class io.mindspice.lyra.editor.EditorLauncher \
    "${LYRA_EDITOR_LINK_ARGS[@]}" \
    --java-options --enable-preview --java-options --add-modules=jdk.jdi \
    --java-options --enable-native-access=ALL-UNNAMED
