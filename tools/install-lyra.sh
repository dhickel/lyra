#!/bin/sh
# Install a built Lyra CLI as a versioned release with a stable `lyra`
# launcher and a Java 25 AOT startup cache.
set -eu

usage() {
    cat <<'EOF'
Usage: install-lyra.sh [options]

Install the built Lyra CLI without requiring a package manager.

Options:
  --prefix DIR       Release root (default: $XDG_DATA_HOME/lyra or
                     $HOME/.local/share/lyra)
  --bin-dir DIR      Stable launcher directory (default: $XDG_BIN_HOME or
                     $HOME/.local/bin)
  --jar FILE         Shaded lyra-cli JAR to install
  --force            Replace an existing release with the same version
  --help             Show this help

The installer requires Java 25. It generates a Java 25 AOT cache for the
installed release. If cache generation is unavailable, installation still
completes and the launcher falls back to an ordinary JVM start.
EOF
}

fail() {
    printf 'lyra install: %s\n' "$1" >&2
    exit 2
}

warn() {
    printf 'lyra install: warning: %s\n' "$1" >&2
}

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
HOME_DIR=${HOME:-}
[ -n "$HOME_DIR" ] || fail 'HOME is not set'

DATA_HOME=${XDG_DATA_HOME:-$HOME_DIR/.local/share}
DEFAULT_PREFIX=$DATA_HOME/lyra
DEFAULT_BIN_DIR=${XDG_BIN_HOME:-$HOME_DIR/.local/bin}
PREFIX=${LYRA_PREFIX:-$DEFAULT_PREFIX}
BIN_DIR=${LYRA_BIN_DIR:-$DEFAULT_BIN_DIR}
JAR_PATH=${LYRA_CLI_JAR:-}
FORCE=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --prefix)
            [ "$#" -ge 2 ] || fail '--prefix requires a directory'
            PREFIX=$2
            shift 2
            ;;
        --bin-dir)
            [ "$#" -ge 2 ] || fail '--bin-dir requires a directory'
            BIN_DIR=$2
            shift 2
            ;;
        --jar)
            [ "$#" -ge 2 ] || fail '--jar requires a file'
            JAR_PATH=$2
            shift 2
            ;;
        --force)
            FORCE=1
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            fail "unknown option: $1"
            ;;
    esac
done

JAVA_COMMAND=${JAVA_COMMAND:-java}
JAVA_VERSION_LINE=$("$JAVA_COMMAND" -version 2>&1 | head -n 1 || true)
JAVA_MAJOR=$(printf '%s\n' "$JAVA_VERSION_LINE" \
    | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')
[ "$JAVA_MAJOR" = 25 ] || fail "Java 25 is required (found: ${JAVA_VERSION_LINE:-unavailable})"

if [ -z "$JAR_PATH" ]; then
    BUILD_DIR=$SCRIPT_DIR/../lyra-cli/target
    PROJECT_VERSION=$(awk '/<version>[^<]*<\/version>/{gsub(/.*<version>|<\/version>.*/, ""); print; exit}' \
        "$SCRIPT_DIR/../pom.xml")
    JAR_PATH=$BUILD_DIR/lyra-cli-$PROJECT_VERSION.jar
    if [ ! -f "$JAR_PATH" ]; then
        fail "cannot find the shaded CLI JAR for version $PROJECT_VERSION in $BUILD_DIR; use --jar FILE"
    fi
fi

[ -f "$JAR_PATH" ] || fail "CLI JAR does not exist: $JAR_PATH"
JAR_PATH=$(CDPATH= cd -- "$(dirname -- "$JAR_PATH")" && pwd)/$(basename -- "$JAR_PATH")
JAR_NAME=$(basename -- "$JAR_PATH")
case "$JAR_NAME" in
    lyra-cli-*.jar)
        VERSION=${JAR_NAME#lyra-cli-}
        VERSION=${VERSION%.jar}
        ;;
    *)
        fail "cannot derive a release version from $JAR_NAME; use a lyra-cli-VERSION.jar name"
        ;;
esac
case "$VERSION" in
    ''|*[!A-Za-z0-9._-]*) fail "unsafe release version: $VERSION" ;;
esac

if [ -e "$BIN_DIR/lyra" ] && [ ! -L "$BIN_DIR/lyra" ]; then
    fail "$BIN_DIR/lyra already exists and is not a symlink"
fi

VERSIONS_DIR=$PREFIX/versions
RELEASE_DIR=$VERSIONS_DIR/$VERSION
if [ -e "$RELEASE_DIR" ] || [ -L "$RELEASE_DIR" ]; then
    [ "$FORCE" -eq 1 ] || fail "release already exists: $RELEASE_DIR (use --force to replace it)"
fi
if [ -e "$PREFIX/current" ] && [ ! -L "$PREFIX/current" ]; then
    fail "$PREFIX/current exists and is not a symlink"
fi

hash_stream() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 | awk '{print $1}'
    else
        return 1
    fi
}

digest_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        return 1
    fi
}

java_fingerprint() {
    {
        "$JAVA_COMMAND" -version 2>&1 || true
        printf '%s\n' "$(uname -s)" "$(uname -m)"
    } | hash_stream
}

JAR_DIGEST=$(digest_file "$JAR_PATH") || fail 'sha256sum or shasum is required for AOT cache identity'
JAVA_DIGEST=$(java_fingerprint) || fail 'could not compute the Java runtime identity'

mkdir -p "$PREFIX"
STAGING=$(mktemp -d "$PREFIX/.lyra-install.XXXXXX")
cleanup() {
    rm -rf "$STAGING"
}
trap cleanup EXIT HUP INT TERM

STAGED_RELEASE=$STAGING/release
mkdir -p "$STAGED_RELEASE/bin" "$STAGED_RELEASE/lib" \
    "$STAGED_RELEASE/aot/$JAVA_DIGEST-$JAR_DIGEST"
cp "$JAR_PATH" "$STAGED_RELEASE/lib/lyra-cli.jar"
chmod 0644 "$STAGED_RELEASE/lib/lyra-cli.jar"
printf '%s\n' "$VERSION" > "$STAGED_RELEASE/VERSION"
chmod 0644 "$STAGED_RELEASE/VERSION"

cat > "$STAGED_RELEASE/bin/lyra" <<'EOF'
#!/bin/sh
# Installed Lyra launcher. The release directory is resolved through all
# symlinks so AOT class-path identity remains stable across `current` updates.
set -eu

SOURCE=$0
while [ -h "$SOURCE" ]; do
    SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$SOURCE")" && pwd)
    LINK=$(readlink "$SOURCE")
    case "$LINK" in
        /*) SOURCE=$LINK ;;
        *) SOURCE=$SCRIPT_DIR/$LINK ;;
    esac
done
SCRIPT_DIR=$(CDPATH= cd -P -- "$(dirname -- "$SOURCE")" && pwd)
RELEASE_DIR=$(CDPATH= cd -P -- "$SCRIPT_DIR/.." && pwd)
CLI_JAR=$RELEASE_DIR/lib/lyra-cli.jar

[ -f "$CLI_JAR" ] || {
    printf 'lyra: installed CLI JAR is missing: %s\n' "$CLI_JAR" >&2
    exit 2
}

JAVA_COMMAND=${JAVA_COMMAND:-java}

hash_stream() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 | awk '{print $1}'
    else
        return 1
    fi
}

digest_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        return 1
    fi
}

java_fingerprint() {
    {
        "$JAVA_COMMAND" -version 2>&1 || true
        printf '%s\n' "$(uname -s)" "$(uname -m)"
    } | hash_stream
}

AOT_CACHE=
if JAR_DIGEST=$(digest_file "$CLI_JAR" 2>/dev/null) \
        && JAVA_DIGEST=$(java_fingerprint 2>/dev/null); then
    VERSION=$(cat "$RELEASE_DIR/VERSION")
    AOT_CACHE=$RELEASE_DIR/aot/$JAVA_DIGEST-$JAR_DIGEST/cli-repl.aot
fi

# `lyra` with no command is the installed REPL entry point. Explicit
# subcommands continue to use the existing CLI command surface.
if [ "$#" -eq 0 ]; then
    set -- repl
fi

# The CLI-only JLine FFM provider requires native access on Java 25. User JVM
# options are intentionally appended after the AOT option so an operator can
# override it with -XX:AOTMode=off or another supported setting.
if [ -n "$AOT_CACHE" ] && [ -f "$AOT_CACHE" ]; then
    exec "$JAVA_COMMAND" --enable-native-access=ALL-UNNAMED \
        "-XX:AOTCache=$AOT_CACHE" ${LYRA_JAVA_OPTS:-} \
        -jar "$CLI_JAR" "$@"
else
    exec "$JAVA_COMMAND" --enable-native-access=ALL-UNNAMED \
        ${LYRA_JAVA_OPTS:-} -jar "$CLI_JAR" "$@"
fi
EOF
chmod 0755 "$STAGED_RELEASE/bin/lyra"

mkdir -p "$VERSIONS_DIR" "$BIN_DIR"
if [ -e "$RELEASE_DIR" ] || [ -L "$RELEASE_DIR" ]; then
    rm -rf "$RELEASE_DIR"
fi
mv "$STAGED_RELEASE" "$RELEASE_DIR"

AOT_CACHE=$RELEASE_DIR/aot/$JAVA_DIGEST-$JAR_DIGEST/cli-repl.aot
AOT_LOG=$STAGING/aot.log
if printf '\\quit\n' | "$JAVA_COMMAND" --enable-native-access=ALL-UNNAMED \
        "-XX:AOTCacheOutput=$AOT_CACHE" -jar "$RELEASE_DIR/lib/lyra-cli.jar" \
        repl --plain > /dev/null 2> "$AOT_LOG"; then
    if [ -s "$AOT_CACHE" ]; then
        chmod 0755 "$(dirname -- "$AOT_CACHE")"
        chmod 0644 "$AOT_CACHE"
        AOT_STATUS='generated'
    else
        AOT_STATUS='unavailable'
        warn 'Java did not produce an AOT cache; the launcher will use normal startup'
    fi
else
    AOT_STATUS='unavailable'
    warn 'AOT cache generation failed; the launcher will use normal startup'
    if [ -s "$AOT_LOG" ]; then
        sed 's/^/  /' "$AOT_LOG" >&2
    fi
fi

CURRENT_TMP=$PREFIX/.current.$$
rm -f "$CURRENT_TMP"
ln -s "versions/$VERSION" "$CURRENT_TMP"
mv -f "$CURRENT_TMP" "$PREFIX/current"

BIN_TMP=$BIN_DIR/.lyra.$$
rm -f "$BIN_TMP"
ln -s "$PREFIX/current/bin/lyra" "$BIN_TMP"
mv -f "$BIN_TMP" "$BIN_DIR/lyra"

printf 'lyra: installed %s\n' "$VERSION"
printf 'lyra: launcher %s\n' "$BIN_DIR/lyra"
printf 'lyra: AOT cache %s\n' "$AOT_STATUS"
case ":${PATH:-}:" in
    *:"$BIN_DIR":*) ;;
    *) printf 'lyra: add %s to PATH to run `lyra` directly\n' "$BIN_DIR" ;;
esac
