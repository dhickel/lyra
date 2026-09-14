#!/usr/bin/env bash
# Run the deterministic Phase 24 release/scope audit.
#
# The requirement matrix is intentionally conservative: a BLOCKED row is an
# evidence gap, not a claim that the implementation is incorrect.  The audit
# exits 0 only when every non-deferred row is PASS, the fresh Phase 23 gate
# passes, and the Phase 14 exact-method REPL coverage inventory verifies
# against the clean reactor reports and living-specification classifications.
# It never deletes tracked/untracked user files or changes source/
# internal records; generated evidence is confined to target/phase24-audit by
# default.  Ignored target trees are snapshotted, restored, and compared.
set -Eeuo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
MATRIX="$ROOT/tools/phase24-requirement-matrix.tsv"
COVERAGE="$ROOT/tools/phase24-conformance-coverage.tsv"
REPL_COVERAGE="$ROOT/tools/phase24-repl-coverage.tsv"
OUT=${PHASE24_OUTPUT_DIR:-"$ROOT/target/phase24-audit"}
if [[ "$OUT" != /* ]]; then
    OUT="$ROOT/$OUT"
fi
if [[ -L "$OUT" ]]; then
    printf 'phase24-audit: PHASE24_OUTPUT_DIR must not be a symbolic link: %s\n' "$OUT" >&2
    exit 2
fi
OUT=$(realpath -m -- "$OUT")
case "$OUT" in
    "$ROOT/target"/*) ;;
    *)
        printf 'phase24-audit: PHASE24_OUTPUT_DIR must be below %s/target\n' "$ROOT" >&2
        exit 2
        ;;
esac
usage() {
    cat <<'EOF'
Usage: tools/phase24-release-audit.sh

Runs the clean Java 25 Maven reactor, assertion-report inventory, package and
scope checks, deterministic classes/thin/bundled subprocess checks, and a fresh
owner-gated Phase 23 evidence run.  Reports are written to
 target/phase24-audit (or PHASE24_OUTPUT_DIR).

The command exits 0 only for a complete PASS.  A known evidence gap or failed
check produces BLOCKED and exit 1.  Tracked/untracked worktree state is not
cleaned; pre-existing ignored target trees are restored before exit.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi
if (($# != 0)); then
    printf 'phase24-audit: unexpected argument: %s\n' "$1" >&2
    usage >&2
    exit 2
fi

# clean removes every reactor target/ directory, so keep all ignored target
# trees and the build log outside target until the clean reactor completes.
# The final audit directory is copied back after the original trees are
# restored; all other pre-existing ignored output must remain byte-for-byte
# unchanged.
RUN_TMP=$(mktemp -d)
PRESERVED_TARGETS="$RUN_TMP/preserved-targets"
mkdir -p "$PRESERVED_TARGETS"
TARGET_DIRS=(
    "$ROOT/target"
    "$ROOT/lyra-runtime/target"
    "$ROOT/lyra-compiler/target"
    "$ROOT/lyra-repl/target"
    "$ROOT/lyra-cli/target"
    "$ROOT/lyra-editor/target"
)
declare -A TARGET_PRESENT=()
for target_dir in "${TARGET_DIRS[@]}"; do
    if [[ -L "$target_dir" ]]; then
        printf 'phase24-audit: target directory must not be a symbolic link: %s\n' "$target_dir" >&2
        exit 2
    fi
    target_name="${target_dir#"$ROOT/"}"
    if [[ -d "$target_dir" ]]; then
        mkdir -p "$(dirname -- "$PRESERVED_TARGETS/$target_name")"
        cp -a "$target_dir" "$PRESERVED_TARGETS/$target_name"
        TARGET_PRESENT["$target_name"]=1
    else
        TARGET_PRESENT["$target_name"]=0
    fi
done
TARGET_RESTORED=0
TARGET_PRESERVATION_OK=0
PYTHON_BIN=""
restore_target() {
    if (( TARGET_RESTORED == 1 )); then
        return
    fi
    set +e
    FINAL_AUDIT="$RUN_TMP/final-audit"
    if [[ -d "$OUT" ]]; then
        rm -rf -- "$FINAL_AUDIT"
        cp -a "$OUT" "$FINAL_AUDIT"
    fi
    for target_dir in "${TARGET_DIRS[@]}"; do
        target_name="${target_dir#"$ROOT/"}"
        rm -rf -- "$target_dir"
        if [[ "${TARGET_PRESENT[$target_name]}" == 1 ]]; then
            mkdir -p "$(dirname -- "$target_dir")"
            cp -a "$PRESERVED_TARGETS/$target_name" "$target_dir"
        fi
    done
    if [[ -d "$FINAL_AUDIT" ]]; then
        rm -rf -- "$OUT"
        mkdir -p "$(dirname -- "$OUT")"
        cp -a "$FINAL_AUDIT" "$OUT"
    fi
    TARGET_RESTORED=1
    if [[ -n "$PYTHON_BIN" && -x "$PYTHON_BIN" ]]; then
        if "$PYTHON_BIN" - "$ROOT" "$PRESERVED_TARGETS" "$OUT" >"$RUN_TMP/target-preservation.log" 2>&1 <<'PY'
import hashlib
import os
import sys
from pathlib import Path

root, preserved, output = map(Path, sys.argv[1:])
output_relative = Path(output).relative_to(root / "target")

def snapshot(path, excluded=None):
    if not path.exists():
        return None
    values = []
    for item in sorted(path.rglob("*")):
        relative = item.relative_to(path)
        if excluded is not None and (relative == excluded or excluded in relative.parents):
            continue
        if item.is_symlink():
            values.append(("link", relative.as_posix(), os.readlink(item)))
        elif item.is_dir():
            values.append(("dir", relative.as_posix()))
        elif item.is_file():
            digest = hashlib.sha256(item.read_bytes()).hexdigest()
            values.append(("file", relative.as_posix(), digest))
        else:
            raise SystemExit(f"unsupported target entry: {item}")
    return values

def compare(name, excluded=None):
    expected = preserved / name
    actual = root / name
    if snapshot(expected, excluded) != snapshot(actual, excluded):
        raise SystemExit(f"pre-existing ignored output changed: {name}")

for name in ("lyra-runtime/target", "lyra-compiler/target", "lyra-repl/target", "lyra-cli/target", "lyra-editor/target"):
    compare(name)
if (preserved / "target").exists():
    compare("target", output_relative)
elif snapshot(root / "target", output_relative):
    raise SystemExit("root target was absent before the audit but retained non-audit output")
print("target preservation: PASS (all pre-existing target trees match outside the fresh audit output)")
PY
        then
            TARGET_PRESERVATION_OK=1
        fi
    fi
    mkdir -p "$OUT"
    if [[ -s "$RUN_TMP/target-preservation.log" ]]; then
        cp "$RUN_TMP/target-preservation.log" "$OUT/target-preservation.log"
    else
        printf 'target preservation: BLOCKED (Python preservation comparison was unavailable)\n' >"$OUT/target-preservation.log"
    fi
    set -e
}
trap 'restore_target; rm -rf -- "$RUN_TMP"' EXIT
BEFORE_STATUS="$RUN_TMP/workspace-before.porcelain"
AFTER_STATUS="$RUN_TMP/workspace-after.porcelain"
git -C "$ROOT" status --porcelain=v1 --untracked-files=all >"$BEFORE_STATUS"

if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
    JDEPS_BIN="$JAVA_HOME/bin/jdeps"
else
    JAVA_BIN=$(command -v java || true)
    JDEPS_BIN=$(command -v jdeps || true)
fi
MAVEN_COMMAND=${MAVEN_COMMAND:-mvn}
PYTHON_BIN=$(command -v python3 || true)

# Results are kept as PASS/BLOCKED so the matrix renderer can remain entirely
# deterministic and can report every row even after an early command fails.
declare -A CHECK_STATUS=()
declare -A CHECK_EVIDENCE=()
declare -A CHECK_NOTE=()

set_check() {
    local name=$1
    local status=$2
    local evidence=$3
    local note=${4:-}
    CHECK_STATUS["$name"]="$status"
    CHECK_EVIDENCE["$name"]="$evidence"
    CHECK_NOTE["$name"]="${note//$'\t'/ }"
    CHECK_NOTE["$name"]="${CHECK_NOTE[$name]//$'\n'/ }"
}

# A clean full reactor is the common evidence source for all focused test
# groups.  It is deliberately the only Maven clean in this script.  Keep its
# output outside target because Maven removes target before verify starts.
if [[ -x "$MAVEN_COMMAND" || -n "$(command -v "$MAVEN_COMMAND" 2>/dev/null || true)" ]]; then
    if "$MAVEN_COMMAND" -B -ntp clean verify >"$RUN_TMP/build.log" 2>&1; then
        BUILD_OK=1
    else
        BUILD_OK=0
    fi
else
    printf 'Maven command not found: %s\n' "$MAVEN_COMMAND" >"$RUN_TMP/build.log"
    BUILD_OK=0
fi
mkdir -p "$OUT"
cp "$BEFORE_STATUS" "$OUT/workspace-before.porcelain"
cp "$RUN_TMP/build.log" "$OUT/build.log"
if (( BUILD_OK == 1 )); then
    set_check build PASS build.log "mvn clean verify passed"
else
    set_check build BLOCKED build.log "mvn clean verify failed"
fi

if [[ -x "$JAVA_BIN" ]] && [[ -x "$JDEPS_BIN" ]] \
        && command -v "$MAVEN_COMMAND" >/dev/null 2>&1 \
        && [[ -x "$PYTHON_BIN" ]]; then
    {
        "$JAVA_BIN" -version
        "$MAVEN_COMMAND" -version
        "$PYTHON_BIN" --version
    } >"$OUT/environment.log" 2>&1 || true
    if grep -Eq 'version "25([.0-9+]|-)+' "$OUT/environment.log" \
            && grep -q 'Apache Maven' "$OUT/environment.log" \
            && grep -Eq '^Python 3\.' "$OUT/environment.log"; then
        set_check environment PASS environment.log "Java 25, Maven, and Python are available"
    else
        set_check environment BLOCKED environment.log "the required Java 25/Maven/Python toolchain is unavailable"
    fi
else
    printf 'java=%s\njdeps=%s\nmaven=%s\npython=%s\n' \
        "$JAVA_BIN" "$JDEPS_BIN" "$MAVEN_COMMAND" "$PYTHON_BIN" >"$OUT/environment.log"
    set_check environment BLOCKED environment.log "required Java 25, jdeps, Maven, or Python executable is unavailable"
fi

HOST_OS=$(uname -s 2>/dev/null || printf 'unknown')
if [[ "$HOST_OS" =~ ^(MINGW|MSYS|CYGWIN|Windows_NT) ]]; then
    printf 'native Windows validation requires a native Windows runner; detected host: %s\n' "$HOST_OS" >"$OUT/windows.log"
    set_check windows BLOCKED windows.log "native Windows launcher validation is required but this POSIX audit cannot execute it"
else
    printf 'native Windows validation is N/A: no native Windows execution environment is available; detected host: %s\n' "$HOST_OS" >"$OUT/windows.log"
    set_check windows N/A windows.log "N/A: native Windows launcher validation is unavailable on this $HOST_OS host"
fi

if [[ -s "$MATRIX" && -s "$COVERAGE" ]]; then
    set_check matrix PASS "tools/phase24-requirement-matrix.tsv;tools/phase24-conformance-coverage.tsv" "base matrix and method-level conformance coverage are present"
else
    set_check matrix BLOCKED "tools/phase24-requirement-matrix.tsv;tools/phase24-conformance-coverage.tsv" "base matrix or method-level conformance coverage is missing or empty"
fi

if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" "$MATRIX" "$COVERAGE" >"$OUT/coverage.log" 2>&1 <<'PY'
import csv
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root, matrix_path, coverage_path = map(Path, sys.argv[1:])
header = ["id", "source", "category", "status", "requirement", "evidence", "check"]
reported_test_methods = set()
for report in sorted(root.glob("lyra-*/target/surefire-reports/TEST-*.xml")) \
        + sorted(root.glob("lyra-*/target/failsafe-reports/TEST-*.xml")):
    suite = ET.parse(report).getroot()
    reported_test_methods.update(test.attrib.get("name") for test in suite.findall("testcase"))

def rows(path):
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if reader.fieldnames != header:
            raise SystemExit(f"{path.name}: unexpected header {reader.fieldnames!r}")
        return list(reader)

def verify_reference(reference):
    if "#" not in reference:
        path = root / reference
        if not path.exists():
            raise SystemExit(f"missing evidence path: {reference}")
        return
    file_name, symbol = reference.split("#", 1)
    path = root / file_name
    if not path.is_file():
        raise SystemExit(f"missing evidence source: {reference}")
    if path.suffix == ".tsv":
        with path.open(encoding="utf-8", newline="") as handle:
            referenced = csv.DictReader(handle, delimiter="\t")
            if not any(row.get("id") == symbol for row in referenced):
                raise SystemExit(f"missing coverage row {symbol} in {file_name}")
        return
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".java":
        declaration = rf"(?m)(?:^\s*@[^\n]+\n)+\s*(?:public\s+|private\s+|protected\s+)?(?:static\s+|final\s+|synchronized\s+)*[\w<>?,\[\] ]+\s+{re.escape(symbol)}\s*\("
        if not re.search(declaration, text):
            raise SystemExit(f"evidence method is not an annotated test method {symbol} in {file_name}")
        if symbol not in reported_test_methods:
            raise SystemExit(f"evidence test method did not execute in the clean reactor: {symbol}")
    elif not re.search(rf"\b{re.escape(symbol)}\s*\(", text):
        raise SystemExit(f"missing assertion method {symbol} in {file_name}")

expected_base = set()
for prefix, count in (("P24-RB", 20), ("P24-TGT", 22), ("P24-CON", 17),
                      ("P24-PLAN-VAL", 25), ("P24-LANG-VAL", 13),
                      ("P24-BACK-VAL", 14), ("P24-DEF", 10), ("P24-AUD", 13)):
    expected_base.update({f"{prefix}-{index:03d}" for index in range(1, count + 1)})

base = rows(matrix_path)
coverage = rows(coverage_path)
if len(coverage) != 25:
    raise SystemExit(f"expected 25 one-to-one language/backend validation rows, found {len(coverage)}")
ids = [row["id"] for row in coverage]
if len(ids) != len(set(ids)) or any(not value.startswith("P24-COV-") for value in ids):
    raise SystemExit("coverage IDs must be unique P24-COV-* values")
for row in coverage:
    if row["status"] != "PASS" or row["check"] != "coverage":
        raise SystemExit(f"coverage row is not a passing coverage assertion: {row['id']}")
    refs = [item for item in row["evidence"].split(";") if item]
    if not refs:
        raise SystemExit(f"coverage row has no evidence references: {row['id']}")
    for reference in refs:
        verify_reference(reference)
expected_languages = {f"P24-COV-LANG-{index:03d}" for index in range(1, 13)}
expected_backend = {f"P24-COV-BACK-{index:03d}" for index in range(1, 14)}
if set(ids) != expected_languages | expected_backend:
    raise SystemExit("coverage IDs do not exactly cover both living-spec validation lists")
by_id = {row["id"]: row for row in base}
base_ids = set(by_id)
repl_ids = {value for value in base_ids if value.startswith("P24-REPL-")}
unexpected = sorted(base_ids - expected_base - repl_ids)
missing = sorted(expected_base - base_ids)
if missing or unexpected:
    raise SystemExit(f"base matrix IDs are not one-to-one; missing={missing}, extra={unexpected}")
for row in base:
    source_path = root / row["source"].split("#", 1)[0]
    if not source_path.is_file():
        raise SystemExit(f"missing matrix source: {row['id']} -> {row['source']}")
    if row["id"].startswith("P24-DEF-"):
        if row["status"] != "DEFERRED":
            raise SystemExit(f"deferred row is not declared DEFERRED: {row['id']}")
    elif row["id"].startswith("P24-REPL-"):
        # The optional REPL matrix tracks both delivered slices and explicit
        # blockers. Unlike the legacy backend matrix, it must not erase an
        # incomplete live-linkage boundary by requiring every row to PASS.
        if row["status"] not in {"PASS", "BLOCKED"}:
            raise SystemExit(f"REPL row has an invalid status: {row['id']}")
    elif row["status"] != "PASS":
        raise SystemExit(f"non-deferred row is not declared PASS: {row['id']}")
    references = [item for item in row["evidence"].split(";") if item]
    if not references:
        raise SystemExit(f"matrix row has no exact evidence references: {row['id']}")
    for reference in references:
        verify_reference(reference)
print(f"coverage: PASS ({len(coverage)} living-spec rows and {len(base)} exact base requirement rows have evidence references)")
PY
    then
        set_check coverage PASS coverage.log "all living-spec validation bullets have exact annotated, executed assertion-test/tool evidence"
    else
        set_check coverage BLOCKED coverage.log "one-to-one living-spec coverage validation failed"
    fi
else
    set_check coverage BLOCKED coverage.log "coverage check could not start Python"
fi

# The REPL coverage inventory is the Phase 14 exact-method gate: every retained
# row must cite annotated test methods that executed in the clean reactor, and
# every superseded/deferred row must cite a living specification heading that
# classifies the removal.  Status declarations alone never pass this check.
if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" "$REPL_COVERAGE" >"$OUT/repl-coverage.log" 2>&1 <<'PY'
import csv
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root, repl_coverage_path = map(Path, sys.argv[1:])
header = ["id", "status", "requirement", "evidence", "notes"]

reported_test_methods = set()
for report in sorted(root.glob("lyra-*/target/surefire-reports/TEST-*.xml")) \
        + sorted(root.glob("lyra-*/target/failsafe-reports/TEST-*.xml")):
    suite = ET.parse(report).getroot()
    reported_test_methods.update(test.attrib.get("name") for test in suite.findall("testcase"))

def slugify(text):
    return re.sub(r"[^0-9a-z]+", "-", text.lower()).strip("-")

def heading_slugs(path):
    slugs = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            heading = stripped.lstrip("#").strip()
            if heading:
                slugs.add(slugify(heading))
    return slugs

def verify_reference(reference, row_id):
    if "#" not in reference:
        path = root / reference
        if not path.exists():
            raise SystemExit(f"{row_id}: missing evidence path: {reference}")
        return
    file_name, symbol = reference.split("#", 1)
    path = root / file_name
    if not path.is_file():
        raise SystemExit(f"{row_id}: missing evidence source: {reference}")
    if path.suffix == ".java":
        text = path.read_text(encoding="utf-8")
        declaration = rf"(?m)(?:^\s*@[^\n]+\n)+\s*(?:public\s+|private\s+|protected\s+)?(?:static\s+|final\s+|synchronized\s+)*[\w<>?,\[\] ]+\s+{re.escape(symbol)}\s*\("
        if not re.search(declaration, text):
            raise SystemExit(f"{row_id}: evidence method is not an annotated test method {symbol} in {file_name}")
        if symbol not in reported_test_methods:
            raise SystemExit(f"{row_id}: evidence test method did not execute in the clean reactor: {symbol}")
    elif path.suffix == ".tsv":
        with path.open(encoding="utf-8", newline="") as handle:
            referenced = csv.DictReader(handle, delimiter="\t")
            if not any(row.get("id") == symbol for row in referenced):
                raise SystemExit(f"{row_id}: missing coverage row {symbol} in {file_name}")
    elif path.suffix == ".md":
        if slugify(symbol) not in heading_slugs(path):
            raise SystemExit(f"{row_id}: classification heading missing in {file_name}: {symbol}")
    else:
        text = path.read_text(encoding="utf-8")
        if not re.search(rf"\b{re.escape(symbol)}\s*\(", text):
            raise SystemExit(f"{row_id}: missing assertion tool/function {symbol} in {file_name}")

with repl_coverage_path.open(encoding="utf-8", newline="") as handle:
    reader = csv.DictReader(handle, delimiter="\t")
    if reader.fieldnames != header:
        raise SystemExit(f"unexpected header {reader.fieldnames!r}")
    rows = list(reader)

expected_ids = {
    *[f"P24-REPL-R{index:02d}" for index in range(1, 19)],
    "P24-REPL-R07-SEC", "P24-REPL-R14-SEC", "P24-REPL-R15-SEC",
    "P24-REPL-DEF-001", "P24-REPL-DEF-002", "P24-REPL-DEF-003",
}
ids = [row["id"] for row in rows]
if len(ids) != len(set(ids)) or any(not value for value in ids):
    raise SystemExit("REPL coverage IDs must be non-empty and unique")
missing = sorted(expected_ids - set(ids))
extra = sorted(set(ids) - expected_ids)
if missing or extra:
    raise SystemExit(f"REPL coverage IDs do not exactly match the retained/superseded/deferred contract; missing={missing}, extra={extra}")

retained = 0
classified = 0
for row in rows:
    row_id = row["id"]
    status = row["status"]
    if status == "PASS":
        retained += 1
    elif status in {"SUPERSEDED", "DEFERRED"}:
        classified += 1
        if not (row_id.endswith("-SEC") or row_id.startswith("P24-REPL-DEF-")):
            raise SystemExit(f"classified row has a non-classification ID: {row_id}")
    else:
        raise SystemExit(f"REPL coverage row has an invalid status: {row_id} -> {status}")
    refs = [item for item in row["evidence"].split(";") if item]
    if not refs:
        raise SystemExit(f"REPL coverage row has no evidence references: {row_id}")
    for reference in refs:
        if status == "PASS":
            verify_reference(reference, row_id)
        else:
            file_name, _, symbol = reference.partition("#")
            path = root / file_name
            if not path.is_file() or path.suffix != ".md":
                raise SystemExit(f"classified row must cite a living specification: {row_id} -> {reference}")
            if symbol and slugify(symbol) not in heading_slugs(path):
                raise SystemExit(f"classification heading missing in {file_name}: {symbol}")
print(f"repl-coverage: PASS ({retained} retained rows with exact executed methods; {classified} explicitly classified superseded/deferred rows)")
PY
    then
        set_check repl-coverage PASS repl-coverage.log "every retained REPL requirement cites exact annotated methods executed in the clean reactor and every removal is explicitly classified by a living specification"
    else
        set_check repl-coverage BLOCKED repl-coverage.log "REPL coverage inventory did not verify against the clean reactor reports and living specifications"
    fi
else
    set_check repl-coverage BLOCKED repl-coverage.log "REPL coverage check could not start Python"
fi

if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" >"$OUT/docs.log" 2>&1 <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
required = [
    root / "README.md",
    root / ".internal-dev/changelogs/2026-09-04-phase-22-conformance-validation.md",
    root / ".internal-dev/changelogs/2026-09-04-phase-23-performance-evidence.md",
    root / ".internal-dev/reviews/2026-09-04-phase-23-independent-validation.md",
]
missing = [str(path.relative_to(root)) for path in required if not path.is_file() or not path.read_text(encoding="utf-8").strip()]
if missing:
    raise SystemExit("missing release documentation: " + ", ".join(missing))
readme = (root / "README.md").read_text(encoding="utf-8")
for marker in ("mvn clean verify", "Phase 23", "Phase 24", "std->io", "deferred"):
    if marker.lower() not in readme.lower():
        raise SystemExit(f"README is missing release marker: {marker}")
print("docs: PASS (release README and phase records are present)")
PY
    then
        set_check docs PASS docs.log "release README and synchronized phase records are present"
    else
        set_check docs BLOCKED docs.log "release-facing documentation inventory failed"
    fi
else
    set_check docs BLOCKED docs.log "documentation check could not start Python"
fi

if [[ -x "$JAVA_BIN" && -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" >"$OUT/layout.log" 2>&1 <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
ns = "{http://maven.apache.org/POM/4.0.0}"
def children(element, tag):
    return [child for child in element if child.tag == ns + tag]
def text(element, tag):
    value = element.find(ns + tag)
    return None if value is None else (value.text or "").strip()

def fail(message):
    raise SystemExit(message)

pom = ET.parse(root / "pom.xml").getroot()
modules = [child.text.strip() for child in pom.find(ns + "modules")]
if modules != ["lyra-runtime", "lyra-compiler", "lyra-repl", "lyra-cli", "lyra-editor"]:
    fail(f"unexpected reactor modules: {modules!r}")
properties = pom.find(ns + "properties")
if properties is None or text(properties, "java.version") != "25":
    fail("root POM does not centralize Java 25")
for name, artifact in (("lyra-runtime", "lyra-runtime"),
                      ("lyra-compiler", "lyra-compiler"),
                      ("lyra-repl", "lyra-repl"),
                      ("lyra-cli", "lyra-cli"),
                      ("lyra-editor", "lyra-editor")):
    path = root / name / "pom.xml"
    if not path.is_file():
        fail(f"missing module POM: {path}")
    module = ET.parse(path).getroot()
    if text(module, "artifactId") != artifact:
        fail(f"wrong artifactId in {path}")
    if text(module, "version") not in (None, ""):
        fail(f"module must inherit the parent version: {path}")
    if list((root / name / "src").rglob("module-info.java")):
        fail(f"JPMS descriptor found in {name}")
runtime_root = ET.parse(root / "lyra-runtime" / "pom.xml").getroot()
runtime_dependencies = runtime_root.find(ns + "dependencies")
if runtime_dependencies is not None:
    for dependency in runtime_dependencies:
        group = text(dependency, "groupId")
        scope = text(dependency, "scope")
        if group != "org.junit.jupiter" or scope != "test":
            fail("runtime POM has a non-test compiler/module dependency")
compiler_pom = (root / "lyra-compiler" / "pom.xml").read_text(encoding="utf-8")
repl_pom = (root / "lyra-repl" / "pom.xml").read_text(encoding="utf-8")
cli_pom = (root / "lyra-cli" / "pom.xml").read_text(encoding="utf-8")
if "<artifactId>lyra-runtime</artifactId>" not in compiler_pom:
    fail("compiler does not declare runtime")
if "<artifactId>lyra-compiler</artifactId>" not in repl_pom or "<artifactId>lyra-runtime</artifactId>" not in repl_pom:
    fail("REPL does not declare compiler and runtime")
if "<artifactId>lyra-repl</artifactId>" not in cli_pom:
    fail("CLI does not declare REPL")
editor_pom = (root / "lyra-editor" / "pom.xml").read_text(encoding="utf-8")
if "<artifactId>lyra-repl</artifactId>" not in editor_pom or "<artifactId>javafx-controls</artifactId>" not in editor_pom:
    fail("editor does not declare REPL and JavaFX")
for name in ("lyra-runtime", "lyra-compiler", "lyra-repl", "lyra-cli"):
    if "org.openjfx" in (root / name / "pom.xml").read_text(encoding="utf-8"):
        fail(f"JavaFX dependency leaked into {name}")
print("layout: PASS")
PY
    then
        set_check layout PASS layout.log "five-module Java 25 classpath layout is valid; JavaFX remains editor-only"
    else
        set_check layout BLOCKED layout.log "reactor/layout contract failed"
    fi
else
    set_check layout BLOCKED layout.log "layout check could not start its toolchain"
fi

if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" >"$OUT/contracts.log" 2>&1 <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
required = [
    root / ".internal-dev/specifications/language-core.md",
    root / ".internal-dev/specifications/backend-runtime.md",
    root / ".internal-dev/specifications/deferred-features.md",
    root / "lyra-compiler/src/main/resources/grammar_spec.md",
    root / "tools/phase23-gates.json",
]
for path in required:
    if not path.is_file() or not path.read_text(encoding="utf-8").strip():
        raise SystemExit(f"missing contract input: {path}")
language = required[0].read_text(encoding="utf-8")
backend = required[1].read_text(encoding="utf-8")
grammar = required[3].read_text(encoding="utf-8")
for needle, value in (("#NIL", language), ("Validation", language),
                     ("Class-File API", backend), ("std->io", backend),
                     ("Excluded syntax", grammar), ("Array[]", grammar)):
    if needle not in value:
        raise SystemExit(f"contract input is missing expected marker: {needle}")
gates = json.loads(required[4].read_text(encoding="utf-8"))
if gates.get("schema") != "lyra.phase23.gates.v1":
    raise SystemExit("Phase 23 thresholds have the wrong schema")
print("contracts: PASS")
PY
    then
        set_check contracts PASS contracts.log "living specifications and ratified gate data are present"
    else
        set_check contracts BLOCKED contracts.log "contract inventory failed"
    fi
else
    set_check contracts BLOCKED contracts.log "contract check could not start Python"
fi

if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" >"$OUT/scope.log" 2>&1 <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
main_roots = [root / "lyra-runtime/src/main", root / "lyra-compiler/src/main", root / "lyra-repl/src/main", root / "lyra-cli/src/main", root / "lyra-editor/src/main"]
java_files = sorted(path for base in main_roots for path in base.rglob("*.java"))

def strip_java_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\r\n]*", "", text)

bad = []
for path in java_files:
    raw = path.read_text(encoding="utf-8")
    code = strip_java_comments(raw)
    rel = path.relative_to(root).as_posix()
    if re.search(r"(?m)^\s*package\s+(?:parse|lang|util)(?:\.|;)", code):
        bad.append(f"legacy package: {rel}")
    if re.search(r"(?m)^\s*import\s+(?:parse|lang|util)(?:\.|;)", code):
        bad.append(f"legacy import: {rel}")
    for pattern, label in (
        (r"org\.objectweb\.asm|org\.graalvm|com\.oracle\.truffle", "alternate backend dependency"),
        (r"(?m)^\s*public\b[^\n]*\bObject\s*\.\.\.", "Object varargs production ABI"),
        (r"throw\s+new\s+UnsupportedOperationException", "unsupported production stub"),
        (r"\b(?:TODO|FIXME|XXX)\b", "unfinished marker"),
        (r"\b(?:Interpreter|TruffleLanguage|LyraInterpreter)\b", "interpreter product"),
    ):
        if re.search(pattern, code):
            bad.append(f"{label}: {rel}")

for path in root.glob("*/src/**/module-info.java"):
    bad.append(f"JPMS descriptor: {path.relative_to(root).as_posix()}")

resource = root / "lyra-compiler/src/main/resources/grammar_spec.md"
grammar = resource.read_text(encoding="utf-8")
marker = "## Excluded syntax"
if marker not in grammar:
    bad.append("grammar resource has no explicit excluded-syntax boundary")
else:
    active = grammar.split(marker, 1)[0]
    for word in ("Any", "throw", "try", "catch", "finally",
                 "nor", "nand", "xnor", "record", "variant", "macro", "generic"):
        if re.search(rf"\b{re.escape(word)}\b", active):
            bad.append(f"deferred grammar production/token before exclusion: {word}")
    if "<<" in active or ">>" in active:
        bad.append("deferred shift operator in active grammar")

if bad:
    for item in sorted(set(bad)):
        print(item)
    raise SystemExit(1)
print(f"scope: PASS ({len(java_files)} production Java files scanned)")
print("The IntrinsicModule empty source is a pinned intrinsic graph input, not an AST/IR placeholder.")
PY
    then
        set_check scope PASS scope.log "production scope and active grammar contain no deferred/alternate execution product"
    else
        set_check scope BLOCKED scope.log "production scope or active grammar scan found a violation"
    fi
else
    set_check scope BLOCKED scope.log "scope check could not start Python"
fi

if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" >"$OUT/legacy.log" 2>&1 <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
legacy = root / "src"
if any(path.is_file() for path in legacy.rglob("*.java")):
    raise SystemExit("legacy root src Java files remain")
if (legacy / "main/resources/grammar_spec.md").exists():
    raise SystemExit("legacy root grammar resource remains")
for path in sorted(root.glob("lyra-*/src/**/*.java")):
    text = path.read_text(encoding="utf-8")
    if re.search(r"(?m)^\s*(?:package|import)\s+(?:parse|lang|util)(?:\.|;)", text):
        raise SystemExit(f"legacy package/import in {path}")
print("legacy: PASS")
PY
    then
        set_check legacy PASS legacy.log "prototype source set and package adapters are absent"
    else
        set_check legacy BLOCKED legacy.log "prototype source/package inventory failed"
    fi
else
    set_check legacy BLOCKED legacy.log "legacy check could not start Python"
fi

if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" >"$OUT/deferred.log" 2>&1 <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
production = []
for base in (root / "lyra-runtime/src/main", root / "lyra-compiler/src/main", root / "lyra-repl/src/main", root / "lyra-cli/src/main"):
    production.extend(base.rglob("*.java"))

def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\r\n]*", "", text)

def strip_literals(text):
    text = re.sub(r'"(?:\\.|[^"\\])*"', "", text, flags=re.S)
    return re.sub(r"'(?:\\.|[^'\\])*'", "", text, flags=re.S)

for path in sorted(production):
    code = strip_literals(strip_comments(path.read_text(encoding="utf-8")))
    if re.search(r"\b(?:Any|nor|nand|xnor)\b", code):
        raise SystemExit(f"deferred product symbol in {path.relative_to(root)}")
    if "org.objectweb.asm" in code or "picocli" in code.lower():
        raise SystemExit(f"deferred/alternate dependency in {path.relative_to(root)}")

grammar = (root / "lyra-compiler/src/main/resources/grammar_spec.md").read_text(encoding="utf-8")
active = grammar.split("## Excluded syntax", 1)[0]
for word in ("Any", "throw", "try", "catch", "finally", "nor", "nand", "xnor"):
    if re.search(rf"\b{re.escape(word)}\b", active):
        raise SystemExit(f"active grammar contains deferred spelling: {word}")
if re.search(r"(?m)^\s*(?:record|variant|macro)\b", active):
    raise SystemExit("active grammar contains deferred declaration production")
print("deferred: PASS")
print("Deferred rows in the matrix are intentionally excluded from the release gate.")
PY
    then
        set_check deferred PASS deferred.log "deferred features are absent from active syntax and production API"
    else
        set_check deferred BLOCKED deferred.log "deferred-feature boundary scan failed"
    fi
else
    set_check deferred BLOCKED deferred.log "deferred check could not start Python"
fi

if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" >"$OUT/test-reports.log" 2>&1 <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
reports = sorted(path for module in ("lyra-runtime", "lyra-compiler", "lyra-repl", "lyra-cli")
                 for path in (root / module / "target/surefire-reports").glob("TEST-*.xml"))
if not reports:
    raise SystemExit("no Surefire reports found")
names = set()
for report in reports:
    suite = ET.parse(report).getroot()
    name = suite.attrib.get("name", "")
    names.add(name)
    for field in ("failures", "errors", "skipped"):
        if int(suite.attrib.get(field, "0")) != 0:
            raise SystemExit(f"{name}: {field} is non-zero")
    if int(suite.attrib.get("tests", "0")) <= 0:
        raise SystemExit(f"{name}: no tests reported")
expected = {
    "LexerTest", "ParserTest", "SourceFoundationTest", "GrammarMatcherTest",
    "ModuleGraphDiscoveryTest", "TypeIdentityTest", "SemanticResolverTest",
    "TypeCheckerTest", "SemanticFlowAlgebraTest", "CallableSummaryTest",
    "Domain11AggregateOwnershipTest", "Domain11ContextualTypingTest",
    "Domain11FlowStateTest", "Domain11InitializationFlowTest", "Domain11SemanticTest",
    "Domain11SealingTest", "TypedIrTest", "ClassFileApiSpikeTest",
    # Language-contract v2 corpus, coverage guard, and independent replay/oracle evidence.
    "LanguageCoverageTest", "LanguageConformanceTest", "LanguageAbiTest",
    "LanguageNumericTest", "LanguageIndexTest", "LanguageBuiltinTest",
    "MatchFuzzRegressionTest", "PrimitiveTypeInitializationTest",
    "GeneratedTypePlannerTest", "JvmAbiMapperTest", "Phase15SmokeTest",
    "Phase16SmokeTest", "Phase17SmokeTest", "Phase18SmokeTest", "Phase18ArtifactTest",
    "Phase19PublicApiTest", "Phase20IoTest", "Phase22ConformanceTest",
    "Phase23EvidenceGateContractTest", "Phase23StructuralBytecodeTest",
    "RuntimeFoundationTest", "LyraSessionTest", "PlainConsoleTest", "ConsoleParsingTest",
    "PersistentScalarTest", "PersistentAggregateTest", "PersistentCallableTest",
    "SessionStorageLinkTest", "SessionAggregateLinkTest", "SessionCallableRuntimeTest",
    "SessionCapturedInstanceFlowTest", "SessionDeclarationWriteFlowTest", "SessionFailureFlowTest",
    "SessionRepairCompatibilityTest", "SessionTypeAdmissionTest", "SessionCompilerTest",
    "ExecutedSnapshotTest", "SessionJavaConsumerTest", "ReplContractsTest",
    "RemoteConsoleSessionTest", "RemoteProtocolV2Test", "RemoteServerTest",
    "RemoteWireRobustnessTest", "RemoteSessionExecutionTest", "NoAuthAttachmentTest",
    "RemoteFileModuleTest", "Phase21CliTest", "Phase22CliConformanceTest",
    "AttachCliTest", "JLineConsoleTest", "JLinePtyTest",
    "PersistentImportTest", "SessionModuleRuntimeTest", "ModuleReloadTest",
    "SessionGenerationLifecycleTest", "ReloadSequenceReproTest",
    "ApplicationAttachmentTest", "AttachedValueLifetimeTest", "ApplicationSafePointTest",
    "AttachmentCancellationTest", "ApplicationAttachmentJavaConsumerTest", "ManagedConsoleSessionTest",
    "CrossSurfaceLocalApiTest", "CrossSurfacePlainConsoleTest", "CrossSurfaceManagedConsoleTest",
    "CrossSurfaceAttachedAppTest", "CrossSurfaceRemoteStandaloneTest", "ReplActivationJavaHostTest",
    "SessionImportedFlowTest", "SessionPinnedModuleCompilerTest", "PreparedSubmissionTest",
    "RetainedCaptureFlowTest", "ReplProfileEmissionTest", "RootTypeRegistrationTest",
    "ReplPackagingCompatibilityTest", "LegacySchema1EncodingTest",
    "DebugArtifactMetadataTest", "RuntimeControlTest", "ReplRunCliTest",
    # Retained nominal struct/class factory suites (phases 1-4): declaration,
    # certificate, bytecode, runtime, session, and campaign coverage.  These
    # are active feature suites, never deferred; omitting one must fail the
    # report inventory, not silently pass.
    "NominalSyntaxTest", "NominalTypeTest", "NominalTypeIdTest", "NominalSemanticsTest",
    "NominalBytecodeTest", "RetainedNominalFlowCertificateTest",
    "NominalArtifactMetadataTest", "NominalConstructionTest", "NominalTypeContractTest",
    "NominalSessionTest", "SessionStateFuzzTest",
    "LanguageFuzzTest", "FuzzInfrastructureTest",
}
short = {name.rsplit(".", 1)[-1] for name in names}
missing = sorted(expected - short)
if missing:
    raise SystemExit("missing expected test suites: " + ", ".join(missing))
print(f"reports: PASS ({len(reports)} suites; all tests non-skipped and passing)")
PY
    then
        set_check tests PASS test-reports.log "all discovered test suites passed with no skips"
    else
        set_check tests BLOCKED test-reports.log "test report inventory failed"
    fi
else
    set_check tests BLOCKED test-reports.log "test report check could not start Python"
fi

test_group() {
    local check=$1
    shift
    local log="$OUT/test-$check.log"
    if [[ "${CHECK_STATUS[tests]:-BLOCKED}" != PASS || ! -x "$PYTHON_BIN" ]]; then
        set_check "$check" BLOCKED "test-reports.log" "the full reactor test evidence is unavailable"
        return
    fi
    if "$PYTHON_BIN" - "$ROOT" "$@" >"$log" 2>&1 <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
expected = sys.argv[2:]
reports = sorted(path for module in ("lyra-runtime", "lyra-compiler", "lyra-repl", "lyra-cli")
                 for path in (root / module / "target/surefire-reports").glob("TEST-*.xml"))
names = {}
for report in reports:
    suite = ET.parse(report).getroot()
    names[suite.attrib.get("name", "")] = report
for wanted in expected:
    matches = [name for name in names if name == wanted or name.rsplit(".", 1)[-1] == wanted]
    if not matches:
        raise SystemExit(f"missing test suite: {wanted}")
    for name in matches:
        suite = ET.parse(names[name]).getroot()
        if any(int(suite.attrib.get(field, "0")) for field in ("failures", "errors", "skipped")):
            raise SystemExit(f"non-passing suite: {name}")
print("PASS: " + ", ".join(expected))
PY
    then
        set_check "$check" PASS "test-$check.log" "focused assertion suites passed in the clean reactor"
    else
        set_check "$check" BLOCKED "test-$check.log" "one or more focused assertion suites are missing or failed"
    fi
}

# Failsafe integration suites (JLine distribution, artifact deployment, launcher
# activation) run only under verify, so they are checked against failsafe reports.
integration_group() {
    local check=$1
    shift
    local log="$OUT/test-$check.log"
    if [[ "${CHECK_STATUS[tests]:-BLOCKED}" != PASS || ! -x "$PYTHON_BIN" ]]; then
        set_check "$check" BLOCKED "test-reports.log" "the full reactor verify evidence is unavailable"
        return
    fi
    if "$PYTHON_BIN" - "$ROOT" "$@" >"$log" 2>&1 <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
expected = sys.argv[2:]
reports = sorted(path for module in ("lyra-runtime", "lyra-compiler", "lyra-repl", "lyra-cli")
                 for path in (root / module / "target/failsafe-reports").glob("TEST-*.xml"))
names = {}
for report in reports:
    suite = ET.parse(report).getroot()
    names[suite.attrib.get("name", "")] = report
for wanted in expected:
    matches = [name for name in names if name == wanted or name.rsplit(".", 1)[-1] == wanted]
    if not matches:
        raise SystemExit(f"missing integration suite: {wanted}")
    for name in matches:
        suite = ET.parse(names[name]).getroot()
        if any(int(suite.attrib.get(field, "0")) for field in ("failures", "errors", "skipped")):
            raise SystemExit(f"non-passing integration suite: {name}")
print("PASS: " + ", ".join(expected))
PY
    then
        set_check "$check" PASS "test-$check.log" "focused integration suites passed in the clean reactor verify"
    else
        set_check "$check" BLOCKED "test-$check.log" "one or more focused integration suites are missing or failed"
    fi
}

test_group frontend LexerTest SourceFoundationTest ParserTest NominalSyntaxTest
test_group grammar GrammarMatcherTest ParserTest
test_group sealing Domain11SealingTest TypedIrTest
test_group identity TypeIdentityTest ModuleGraphDiscoveryTest GeneratedTypePlannerTest
test_group module ModuleGraphDiscoveryTest SemanticResolverTest Domain11InitializationFlowTest Phase16SmokeTest Phase22ConformanceTest
test_group semantic SemanticResolverTest TypeCheckerTest Domain11SemanticTest Domain11ContextualTypingTest Domain11FlowStateTest SemanticFlowAlgebraTest CallableSummaryTest NominalSemanticsTest RetainedNominalFlowCertificateTest
test_group types TypeIdentityTest TypeCheckerTest Domain11ContextualTypingTest JvmAbiMapperTest NominalTypeTest NominalTypeIdTest
test_group ir TypedIrTest Domain11SealingTest Domain11FlowStateTest
# Phase22ConformanceTest has one compiler and one CLI suite; the short-name
# matcher is sufficient for the compiler-side group because the compiler suite
# is present whenever the full report inventory passes.
test_group jvm ClassFileApiSpikeTest Phase15SmokeTest Phase16SmokeTest Phase23StructuralBytecodeTest NominalBytecodeTest
test_group abi JvmAbiMapperTest Phase15SmokeTest Phase16SmokeTest Phase22ConformanceTest NominalTypeContractTest
test_group artifact Phase18ArtifactTest Phase18SmokeTest Phase22ConformanceTest
test_group api Phase19PublicApiTest Phase17SmokeTest Phase22ConformanceTest
test_group runtime RuntimeFoundationTest Phase17SmokeTest Phase22ConformanceTest NominalConstructionTest NominalArtifactMetadataTest
test_group io Phase20IoTest
# Diagnostic behavior is asserted in compiler API/runtime conformance and CLI.
test_group diagnostics Phase22ConformanceTest RuntimeFoundationTest Phase20IoTest
test_group cli Phase21CliTest Phase22CliConformanceTest
test_group repl-session LyraSessionTest ReplContractsTest PersistentScalarTest PersistentAggregateTest PersistentCallableTest SessionStorageLinkTest SessionAggregateLinkTest SessionCallableRuntimeTest SessionCapturedInstanceFlowTest SessionDeclarationWriteFlowTest SessionFailureFlowTest SessionRepairCompatibilityTest SessionTypeAdmissionTest SessionJavaConsumerTest NominalSessionTest SessionStateFuzzTest
test_group repl-results ExecutedSnapshotTest SessionCompilerTest
test_group repl-remote RemoteConsoleSessionTest RemoteProtocolV2Test RemoteServerTest RemoteWireRobustnessTest RemoteSessionExecutionTest NoAuthAttachmentTest RemoteFileModuleTest
test_group repl-console PlainConsoleTest ConsoleParsingTest AttachCliTest JLineConsoleTest JLinePtyTest
# Phase 14 executable linkage/lifecycle/attachment/integration and module/root/
# debug/I/O/PTY/reopen/flow/limit groups.  Every retained REPL matrix row uses
# one of these groups as its check, so the clean reactor must really run them.
# Retained nominal campaign evidence: the bounded compiler fuzz family and the
# persistent-session state model that carry the nominal operation matrix.  Their
# mandatory presence is additionally gated by the expected-report inventory
# above; this group records clean-reactor evidence in the audit output.
test_group nominal-campaigns LanguageFuzzTest FuzzInfrastructureTest SessionStateFuzzTest
test_group repl-linkage SessionStorageLinkTest SessionAggregateLinkTest SessionCallableRuntimeTest SessionCapturedInstanceFlowTest SessionDeclarationWriteFlowTest PersistentScalarTest PersistentAggregateTest PersistentCallableTest PersistentImportTest
test_group repl-module SessionPinnedModuleCompilerTest SessionImportedFlowTest PreparedSubmissionTest PersistentImportTest SessionModuleRuntimeTest
test_group repl-lifecycle ModuleReloadTest SessionGenerationLifecycleTest ReloadSequenceReproTest SessionFailureFlowTest SessionRepairCompatibilityTest SessionTypeAdmissionTest
test_group repl-root RootTypeRegistrationTest ReplProfileEmissionTest RetainedCaptureFlowTest
test_group repl-attachment ApplicationAttachmentTest AttachedValueLifetimeTest ApplicationSafePointTest AttachmentCancellationTest ApplicationAttachmentJavaConsumerTest ManagedConsoleSessionTest
test_group repl-integration RemoteSessionExecutionTest CrossSurfaceLocalApiTest CrossSurfacePlainConsoleTest CrossSurfaceManagedConsoleTest CrossSurfaceAttachedAppTest CrossSurfaceRemoteStandaloneTest ReplActivationJavaHostTest SessionJavaConsumerTest ReplRunCliTest
test_group repl-debug DebugArtifactMetadataTest ReplPackagingCompatibilityTest LegacySchema1EncodingTest RuntimeControlTest
test_group repl-flow Domain11FlowStateTest SemanticFlowAlgebraTest CallableSummaryTest Domain11AggregateOwnershipTest Domain11InitializationFlowTest
test_group repl-io Phase20IoTest
test_group repl-pty JLinePtyTest JLineConsoleTest AttachCliTest PlainConsoleTest ConsoleParsingTest
test_group repl-reopen AttachedValueLifetimeTest ApplicationAttachmentTest RootTypeRegistrationTest
test_group repl-limit ExecutedSnapshotTest LyraSessionTest SessionModuleRuntimeTest ModuleReloadTest
integration_group repl-deployment ReplArtifactIT ReplLauncherIT JLineDistributionIT

if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" >"$OUT/abi-scan.log" 2>&1 <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
paths = sorted((root / "lyra-compiler/src/main/java").rglob("*.java"))
text = "\n".join(path.read_text(encoding="utf-8") for path in paths)
for needle in ("ClassFile", "jvmDescriptor", "MethodType", "Fn<", "@nil"):
    if needle not in text:
        raise SystemExit(f"ABI implementation marker missing: {needle}")
if re.search(r"org\.objectweb\.asm|Object\s*\.\.\.", text):
    raise SystemExit("alternate/generic ABI marker found")
print("abi: PASS")
PY
    then
        set_check abi PASS abi-scan.log "production ABI mapper has direct primitive/signature markers"
    else
        set_check abi BLOCKED abi-scan.log "ABI production scan failed"
    fi
else
    set_check abi BLOCKED abi-scan.log "ABI scan could not start Python"
fi

if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" >"$OUT/api-scan.log" 2>&1 <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
files = sorted(path for base in (root / "lyra-compiler/src/main/java", root / "lyra-runtime/src/main/java")
              for path in base.rglob("*.java"))
text = "\n".join(path.read_text(encoding="utf-8") for path in files)
for needle in ("CompileRequest", "CompileResult", "LoadedArtifact", "ModuleHandle",
               "ExportHandle", "invokeExact", "RuntimeOptions"):
    if needle not in text:
        raise SystemExit(f"public API marker missing: {needle}")
if re.search(r"org\.objectweb\.asm|picocli", text, flags=re.I):
    raise SystemExit("unapproved public/tooling dependency marker found")
print("api: PASS")
PY
    then
        set_check api PASS api-scan.log "public compiler/runtime API markers and consumer suites are present"
    else
        set_check api BLOCKED api-scan.log "public API source scan failed"
    fi
else
    set_check api BLOCKED api-scan.log "API scan could not start Python"
fi

if [[ -n "${JDEPS_BIN:-}" && -x "$JDEPS_BIN" ]] && [[ -x "$PYTHON_BIN" ]]; then
    RUNTIME_JAR="$ROOT/lyra-runtime/target/lyra-runtime-1.0-SNAPSHOT.jar"
    COMPILER_JAR="$ROOT/lyra-compiler/target/lyra-compiler-1.0-SNAPSHOT.jar"
    REPL_JAR="$ROOT/lyra-repl/target/lyra-repl-1.0-SNAPSHOT.jar"
    CLI_FAT_JAR="$ROOT/lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar"
    CLI_ORIGINAL_JAR="$ROOT/lyra-cli/target/original-lyra-cli-1.0-SNAPSHOT.jar"
    JLINE_CP=$(find "${HOME:-/nonexistent}/.m2/repository/org/jline" -type f \
        -name '*.jar' ! -name '*-sources.jar' -print 2>/dev/null | paste -sd: -)
    if {
        "$JDEPS_BIN" --multi-release 25 --recursive "$RUNTIME_JAR"
        "$JDEPS_BIN" --multi-release 25 --recursive --class-path "$RUNTIME_JAR" "$COMPILER_JAR"
        "$JDEPS_BIN" --multi-release 25 --recursive --class-path "$COMPILER_JAR:$RUNTIME_JAR" "$REPL_JAR"
        if [[ -f "$CLI_ORIGINAL_JAR" ]]; then
            "$JDEPS_BIN" --multi-release 25 --recursive \
                --class-path "$REPL_JAR:$COMPILER_JAR:$RUNTIME_JAR${JLINE_CP:+:$JLINE_CP}" \
                "$CLI_ORIGINAL_JAR"
        fi
        "$JDEPS_BIN" --multi-release 25 --recursive "$CLI_FAT_JAR"
    } >"$OUT/jdeps.log" 2>&1 && ! grep -Eiq 'not found|error:' "$OUT/jdeps.log"; then
        set_check jdeps PASS jdeps.log "runtime/compiler/REPL/CLI packaged dependency closure has no unresolved entries"
    else
        set_check jdeps BLOCKED jdeps.log "jdeps found an unresolved dependency or could not inspect a package"
    fi
else
    set_check jdeps BLOCKED jdeps.log "jdeps check could not start its toolchain"
fi

if [[ -x "$PYTHON_BIN" ]]; then
    if "$PYTHON_BIN" - "$ROOT" >"$OUT/products.log" 2>&1 <<'PY'
import sys
import zipfile
from pathlib import Path

root = Path(sys.argv[1])
expected = [
    root / "lyra-runtime/target/lyra-runtime-1.0-SNAPSHOT.jar",
    root / "lyra-compiler/target/lyra-compiler-1.0-SNAPSHOT.jar",
    root / "lyra-repl/target/lyra-repl-1.0-SNAPSHOT.jar",
    root / "lyra-cli/target/original-lyra-cli-1.0-SNAPSHOT.jar",
    root / "lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar",
]
for path in expected:
    if not path.is_file():
        raise SystemExit(f"missing product artifact: {path.relative_to(root)}")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        forbidden = [name for name in names if (
            "org/openjdk/jmh/" in name
            or name in {"META-INF/BenchmarkList", "META-INF/CompilerHints"}
            or "Phase23Benchmark" in name
        )]
        if forbidden:
            raise SystemExit(f"benchmark/test implementation leaked into {path.name}: {forbidden}")
print("products: PASS (runtime/compiler/thin/bundled artifacts contain no JMH or benchmark implementation)")
PY
    then
        set_check products PASS products.log "published runtime/compiler/REPL/CLI artifacts contain no JMH/test benchmark implementation"
    else
        set_check products BLOCKED products.log "product artifact scope scan failed"
    fi
else
    set_check products BLOCKED products.log "product artifact scan could not start Python"
fi

if [[ -n "${MAVEN_COMMAND:-}" ]] && command -v "$MAVEN_COMMAND" >/dev/null 2>&1 \
        && [[ -x "$PYTHON_BIN" ]]; then
    if "$MAVEN_COMMAND" -B -ntp dependency:tree -Dverbose >"$OUT/dependency-tree.log" 2>&1 \
            && "$MAVEN_COMMAND" -B -ntp dependency:analyze -DskipTests >"$OUT/dependency-analyze.log" 2>&1; then
        if "$PYTHON_BIN" - "$OUT/dependency-analyze.log" >"$OUT/dependency-classification.log" 2>&1 <<'PY'
import re
import sys
from pathlib import Path

text = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
allowed = {
    "org.junit.jupiter:junit-jupiter-api:jar:5.11.0:test",
    "org.junit.jupiter:junit-jupiter:jar:5.11.0:test",
    "org.jline:jline-terminal-ffm:jar:4.0.0:compile",
}
found = []
for line in text.splitlines():
    match = re.search(r"\[WARNING\]\s{3,}([^ ]+)", line)
    if match and ":jar:" in match.group(1):
        found.append(match.group(1))
unknown = sorted(set(found) - allowed)
if unknown:
    raise SystemExit("unclassified dependency-analyze warning(s): " + ", ".join(unknown))
if found:
    print("PASS: only the known JUnit aggregate/API test-scope analyzer warning is present")
    print("CLASSIFIED: " + ", ".join(sorted(set(found))))
else:
    print("PASS: dependency-analyze produced no undeclared/unused dependency warnings")
PY
        then
            set_check dependencies PASS dependency-tree.log "dependency analysis passed; any aggregate JUnit test warning is explicitly classified in dependency-classification.log"
        else
            set_check dependencies BLOCKED dependency-classification.log "dependency warnings are not limited to the known test-scope analyzer case"
        fi
    else
        set_check dependencies BLOCKED dependency-tree.log "dependency tree/analyze command failed"
    fi
else
    set_check dependencies BLOCKED dependency-tree.log "dependency check could not start Maven/Python"
fi

# The deterministic smoke creates all three publication modes, checks ZIP and
# class-file invariants, verifies generated classes in an external JVM, runs a
# thin JAR with the external runtime, and executes the bundled launcher.
determinism_smoke() {
    local smoke="$OUT/determinism"
    local log="$OUT/determinism.log"
    rm -rf "$smoke"
    mkdir -p "$smoke"
    if [[ ! -f "$ROOT/lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar" ]]; then
        printf 'CLI package is missing\n' >"$log"
        return 1
    fi
    (
        set -euo pipefail
        local source="$smoke/main.lyra"
        local classes_a="$smoke/classes-a"
        local classes_b="$smoke/classes-b"
        local thin_a="$smoke/thin-a.jar"
        local thin_b="$smoke/thin-b.jar"
        local bundled_a="$smoke/bundled-a.jar"
        local bundled_b="$smoke/bundled-b.jar"
        local cli="$ROOT/lyra-cli/target/lyra-cli-1.0-SNAPSHOT.jar"
        local runtime="$ROOT/lyra-runtime/target/lyra-runtime-1.0-SNAPSHOT.jar"
        printf '%s\n' \
            'let @pub answer :I32 = 42' \
            'let @pub main :Fn<Array<String>;I32> = (=> |args| args:.length)' >"$source"

        "$JAVA_BIN" -jar "$cli" compile "$source" --format classes \
            --include-sources --output "$classes_a"
        "$JAVA_BIN" -jar "$cli" compile "$source" --format classes \
            --include-sources --output "$classes_b"
        "$JAVA_BIN" -jar "$cli" compile "$source" --format thin-jar \
            --include-sources --output "$thin_a"
        "$JAVA_BIN" -jar "$cli" compile "$source" --format thin-jar \
            --include-sources --output "$thin_b"
        "$JAVA_BIN" -jar "$cli" compile "$source" --format bundled-jar \
            --include-sources --output "$bundled_a"
        "$JAVA_BIN" -jar "$cli" compile "$source" --format bundled-jar \
            --include-sources --output "$bundled_b"

        "$PYTHON_BIN" - "$classes_a" "$classes_b" "$thin_a" "$thin_b" \
            "$bundled_a" "$bundled_b" <<'PY'
import json
import sys
import zipfile
from pathlib import Path

classes_a, classes_b, thin_a, thin_b, bundled_a, bundled_b = map(Path, sys.argv[1:])

def files(root):
    return sorted(path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file())

def equal_dirs(first, second):
    if files(first) != files(second):
        raise SystemExit(f"class-directory file names differ: {first} vs {second}")
    for name in files(first):
        if (first / name).read_bytes() != (second / name).read_bytes():
            raise SystemExit(f"class-directory bytes differ: {name}")

def check_class_files(root):
    class_files = sorted(root.rglob("*.class"))
    if not class_files:
        raise SystemExit(f"no class files in {root}")
    for path in class_files:
        data = path.read_bytes()
        if data[:4] != b"\xca\xfe\xba\xbe":
            raise SystemExit(f"bad class magic: {path}")
        major = int.from_bytes(data[6:8], "big")
        minor = int.from_bytes(data[4:6], "big")
        if major != 69 or minor not in (0, 65535):
            raise SystemExit(f"wrong Java 25 class version {major}.{minor}: {path}")

def check_jar(path, mode, bundled):
    with zipfile.ZipFile(path) as jar:
        infos = jar.infolist()
        names = [info.filename for info in infos]
        if names != sorted(names) or len(names) != len(set(names)):
            raise SystemExit(f"non-canonical entry order/duplicates: {path}")
        if any(name.startswith("/") or ".." in name.split("/") for name in names):
            raise SystemExit(f"unsafe entry name in {path}")
        for info in infos:
            if info.is_dir() or info.compress_type != zipfile.ZIP_STORED:
                raise SystemExit(f"non-stored/directory entry in {path}: {info.filename}")
            if info.date_time != (1980, 1, 1, 0, 0, 0) or info.extra or info.comment:
                raise SystemExit(f"non-normalized ZIP data in {path}: {info.filename}")
        metadata = json.loads(jar.read("META-INF/lyra/artifact.json"))
        if metadata.get("packagingMode") != mode:
            raise SystemExit(f"wrong packaging metadata in {path}")
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        if bundled:
            if "Main-Class: io.mindspice.lyra.runtime.LyraLauncher\r\n" not in manifest:
                raise SystemExit("bundled launcher manifest is missing")
            if "io/mindspice/lyra/runtime/LyraLauncher.class" not in names:
                raise SystemExit("bundled launcher class is missing")
        else:
            if any(name.startswith("io/mindspice/lyra/runtime/") for name in names):
                raise SystemExit("thin JAR contains runtime classes")
        for name in names:
            if name.endswith(".class"):
                data = jar.read(name)
                if data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") != 69:
                    raise SystemExit(f"wrong class version in {path}: {name}")

equal_dirs(classes_a, classes_b)
check_class_files(classes_a)
for first, second in ((thin_a, thin_b), (bundled_a, bundled_b)):
    if first.read_bytes() != second.read_bytes():
        raise SystemExit(f"JAR bytes differ: {first} vs {second}")
check_jar(thin_a, "thin-jar", False)
check_jar(bundled_a, "bundled-jar", True)
facades = [name[:-6].replace("/", ".") for name in files(classes_a)
           if name.endswith(".class") and "/$lyra$facade$" in name]
if len(facades) != 1:
    raise SystemExit(f"expected one generated facade, found {facades}")
Path(classes_a.parent / "facade-name.txt").write_text(facades[0] + "\n", encoding="utf-8")
print("artifact bytes/classes/JAR metadata: PASS")
print("facade=" + facades[0])
PY

        local facade
        facade=$(<"$smoke/facade-name.txt")
        local classpath="$classes_a:$ROOT/lyra-runtime/target/classes:$ROOT/lyra-compiler/target/classes:$ROOT/lyra-compiler/target/test-classes:$ROOT/lyra-cli/target/classes:$ROOT/lyra-cli/target/test-classes"
        "$JAVA_BIN" -Xverify:all -cp "$classpath" \
            'io.mindspice.lyra.compiler.api.Phase22ConformanceTest$Phase15Probe' \
            "$classes_a" "$facade"
        local thin_classpath="$thin_a:$runtime:$ROOT/lyra-compiler/target/classes:$ROOT/lyra-compiler/target/test-classes:$ROOT/lyra-repl/target/classes:$ROOT/lyra-repl/target/test-classes:$ROOT/lyra-cli/target/classes:$ROOT/lyra-cli/target/test-classes"
        "$JAVA_BIN" -Xverify:all -cp "$thin_classpath" \
            'io.mindspice.lyra.cli.Phase22CliConformanceTest$ThinConsumer' "$facade"

        set +e
        "$JAVA_BIN" -Xverify:all -jar "$bundled_a" >"$smoke/bundled-no-args.out" 2>"$smoke/bundled-no-args.err"
        local no_args_status=$?
        "$JAVA_BIN" -Xverify:all -jar "$bundled_a" left right >"$smoke/bundled-args.out" 2>"$smoke/bundled-args.err"
        local args_status=$?
        set -e
        [[ "$no_args_status" -eq 0 ]]
        [[ "$args_status" -eq 2 ]]
        [[ ! -s "$smoke/bundled-no-args.out" && ! -s "$smoke/bundled-no-args.err" ]]
        [[ ! -s "$smoke/bundled-args.out" && ! -s "$smoke/bundled-args.err" ]]
        printf 'determinism/package smoke: PASS\n'
    ) >"$log" 2>&1
}

if determinism_smoke; then
    set_check determinism PASS determinism.log "paired classes/thin/bundled artifacts and external JVM checks passed"
    set_check packaging PASS determinism.log "classes/thin/bundled packaging, -Xverify:all, and runnable JAR checks passed"
else
    set_check determinism BLOCKED determinism.log "deterministic publication or external package smoke failed"
    set_check packaging BLOCKED determinism.log "class/thin/bundled package smoke failed"
fi

# Reproduce the owner-ratified Phase 23 gate with fixed settings.  Do not
# inherit a caller's reduced benchmark regex, profiler, or sample counts.
PHASE23_OUT="$OUT/phase23-evidence"
mkdir -p "$PHASE23_OUT"
if [[ -x "$ROOT/tools/phase23-evidence.sh" ]] && command -v "$MAVEN_COMMAND" >/dev/null 2>&1; then
    if env \
        PHASE23_OUTPUT_DIR="$PHASE23_OUT" \
        PHASE23_BENCHMARK_REGEX=Phase23Benchmark \
        PHASE23_FORKS=2 \
        PHASE23_WARMUP_ITERATIONS=3 \
        PHASE23_MEASUREMENT_ITERATIONS=5 \
        PHASE23_WARMUP_TIME=1s \
        PHASE23_MEASUREMENT_TIME=1s \
        PHASE23_PROFILER=gc \
        PHASE23_MODE=gate \
        MAVEN_COMMAND="$MAVEN_COMMAND" \
        "$ROOT/tools/phase23-evidence.sh" >"$OUT/phase23.log" 2>&1; then
        if "$PYTHON_BIN" - "$PHASE23_OUT/phase23-gate.json" "$PHASE23_OUT/phase23-evidence.json" >"$OUT/phase23-contract.log" 2>&1 <<'PY'
import json
import sys
from pathlib import Path

gate = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
evidence = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
if gate.get("status") != "pass" or gate.get("allSelectedGatesPass") is not True:
    raise SystemExit("fresh Phase 23 gate is not pass")
if evidence.get("status") != "gate-pass":
    raise SystemExit("fresh Phase 23 evidence status is not gate-pass")
if evidence.get("methodology", {}).get("forks") != 2:
    raise SystemExit("Phase 23 fork setting was not fixed at 2")
if evidence.get("methodology", {}).get("warmupIterations") != 3:
    raise SystemExit("Phase 23 warmup setting was not fixed at 3")
if evidence.get("methodology", {}).get("measurementIterations") != 5:
    raise SystemExit("Phase 23 measurement setting was not fixed at 5")
if evidence.get("methodology", {}).get("profiler") != "gc":
    raise SystemExit("Phase 23 profiler was not fixed at gc")
print("fresh Phase 23 gate: PASS")
PY
        then
            set_check phase23 PASS phase23-evidence/phase23-gate.json "fresh owner-ratified Phase 23 gate passed with fixed 2/3/5/gc settings"
        else
            set_check phase23 BLOCKED phase23-contract.log "Phase 23 command completed but its evidence contract failed"
        fi
    else
        set_check phase23 BLOCKED phase23.log "fresh Phase 23 evidence command failed"
    fi
else
    set_check phase23 BLOCKED phase23.log "Phase 23 evidence script or Maven is unavailable"
fi

# Restore the ignored target tree before comparing state.  The fresh audit
# directory is copied back into its isolated target/phase24-audit namespace.
restore_target

# Capture worktree state after all commands.  target/ is ignored, so this
# comparison detects accidental edits to tracked/deleted/untracked user files.
git -C "$ROOT" status --porcelain=v1 --untracked-files=all >"$AFTER_STATUS"
cp "$AFTER_STATUS" "$OUT/workspace-after.porcelain"
if cmp -s "$BEFORE_STATUS" "$AFTER_STATUS" \
        && (( TARGET_RESTORED == 1 )) \
        && (( TARGET_PRESERVATION_OK == 1 )); then
    set_check workspace PASS "workspace-before.porcelain;target-preservation.log" \
        "git porcelain is unchanged and every pre-existing reactor target tree matches outside the fresh audit output"
else
    diff -u "$BEFORE_STATUS" "$AFTER_STATUS" >"$OUT/workspace-diff.log" || true
    set_check workspace BLOCKED "workspace-diff.log;target-preservation.log" \
        "audit changed non-ignored worktree state or could not prove target preservation"
fi

# Serialize the check table in sorted order.  Associative-array iteration is
# deliberately not used for output order.
CHECK_ROWS="$OUT/check-results.rows"
{
    for check in "${!CHECK_STATUS[@]}"; do
        printf '%s\t%s\t%s\t%s\n' "$check" "${CHECK_STATUS[$check]}" \
            "${CHECK_EVIDENCE[$check]}" "${CHECK_NOTE[$check]}"
    done
} | sort -t $'\t' -k1,1 >"$CHECK_ROWS"
{
    printf 'check\tstatus\tevidence\tnote\n'
    cat "$CHECK_ROWS"
} >"$OUT/check-results.tsv"

if [[ ! -x "$PYTHON_BIN" ]]; then
    printf 'phase24-audit: Python disappeared before matrix rendering\n' >&2
    exit 2
fi

if "$PYTHON_BIN" - "$MATRIX" "$COVERAGE" "$OUT/check-results.tsv" "$OUT/requirement-matrix.tsv" \
        "$OUT/audit-summary.json" "$OUT/audit-summary.txt" >"$OUT/matrix-render.log" 2>&1 <<'PY'
import csv
import json
import sys
from pathlib import Path

matrix_path, coverage_path, checks_path, output_path, json_path, text_path = map(Path, sys.argv[1:])
allowed = {"PASS", "BLOCKED", "DEFERRED", "N/A"}
required_header = ["id", "source", "category", "status", "requirement", "evidence", "check"]

def read_tsv(path):
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if reader.fieldnames != required_header and path == matrix_path:
            raise SystemExit(f"matrix header mismatch: {reader.fieldnames!r}")
        return list(reader)

rows = read_tsv(matrix_path)
rows.extend(read_tsv(coverage_path))
if not rows:
    raise SystemExit("requirement matrix is empty")
ids = [row.get("id", "") for row in rows]
if any(not value for value in ids) or len(ids) != len(set(ids)):
    raise SystemExit("matrix IDs must be non-empty and unique")
for row in rows:
    if row.get("status") not in allowed:
        raise SystemExit(f"invalid matrix status for {row.get('id')}: {row.get('status')}")
    if not row.get("requirement") or not row.get("source") or not row.get("category"):
        raise SystemExit(f"incomplete matrix row: {row.get('id')}")

checks = {}
with checks_path.open(encoding="utf-8", newline="") as handle:
    reader = csv.DictReader(handle, delimiter="\t")
    if reader.fieldnames != ["check", "status", "evidence", "note"]:
        raise SystemExit(f"check header mismatch: {reader.fieldnames!r}")
    for row in reader:
        if row["status"] not in allowed - {"DEFERRED"}:
            raise SystemExit(f"invalid check status: {row}")
        checks[row["check"]] = row

rendered = []
for row in rows:
    declared = row["status"]
    if declared == "DEFERRED":
        actual = "DEFERRED"
        note = "deferred by the living deferred-features contract; excluded from release gating"
    elif declared == "BLOCKED":
        actual = "BLOCKED"
        note = "declared evidence gap: " + row["evidence"]
    else:
        check = checks.get(row["check"])
        if check is None:
            actual = "BLOCKED"
            note = "required check was not executed: " + row["check"]
        elif check["status"] not in {"PASS", "N/A"}:
            actual = "BLOCKED"
            note = check["note"] or ("check failed: " + row["check"])
        else:
            actual = check["status"]
            note = check["note"] or ("check passed" if actual == "PASS" else "not applicable")
    rendered.append({
        "id": row["id"],
        "source": row["source"],
        "category": row["category"],
        "declared_status": declared,
        "status": actual,
        "requirement": row["requirement"],
        "evidence": row["evidence"],
        "check": row["check"],
        "notes": note,
    })

blocked = [row for row in rendered if row["status"] == "BLOCKED"]
non_deferred = [row for row in rendered if row["status"] != "DEFERRED"]
phase23 = checks.get("phase23", {}).get("status") == "PASS"
repl_coverage = checks.get("repl-coverage", {}).get("status") == "PASS"
status = "PASS" if not blocked and phase23 and repl_coverage else "BLOCKED"
counts = {value: sum(row["status"] == value for row in rendered) for value in allowed}
if any(row["status"] == "N/A" and row["declared_status"] != "PASS" for row in rendered):
    raise SystemExit("N/A is reserved for an environment-derived result, not a declared matrix status")

with output_path.open("w", encoding="utf-8", newline="\n") as handle:
    fields = ["id", "source", "category", "declared_status", "status", "requirement", "evidence", "check", "notes"]
    handle.write("\t".join(fields) + "\n")
    for row in rendered:
        handle.write("\t".join(row[field].replace("\t", " ").replace("\n", " ") for field in fields) + "\n")

result = {
    "schema": "lyra.phase24.release-audit.v1",
    "status": status,
    "matrix": {
        "source": "tools/phase24-requirement-matrix.tsv",
        "rendered": str(output_path),
        "rows": len(rendered),
        "nonDeferredRows": len(non_deferred),
        "counts": counts,
    },
    "checks": [checks[name] for name in sorted(checks)],
    "blockedRequirements": [row["id"] for row in blocked],
    "deferredRequirements": [row["id"] for row in rendered if row["status"] == "DEFERRED"],
    "notApplicableRequirements": [row["id"] for row in rendered if row["status"] == "N/A"],
    "phase23GatePass": phase23,
    "replCoverageGatePass": repl_coverage,
}
json_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")

with text_path.open("w", encoding="utf-8", newline="\n") as handle:
    handle.write("Lyra Phase 24 release/scope audit\n")
    handle.write("================================\n")
    handle.write(f"Status: {status}\n")
    handle.write(f"Matrix rows: {len(rendered)}; non-deferred: {len(non_deferred)}; "
                 f"PASS={counts['PASS']} BLOCKED={counts['BLOCKED']} DEFERRED={counts['DEFERRED']} N/A={counts['N/A']}\n")
    handle.write(f"Fresh Phase 23 gate: {'PASS' if phase23 else 'BLOCKED'}\n")
    handle.write(f"Exact REPL coverage gate: {'PASS' if repl_coverage else 'BLOCKED'}\n\n")
    handle.write("Blocked requirements\n")
    handle.write("--------------------\n")
    if blocked:
        for row in blocked:
            handle.write(f"BLOCKED {row['id']}: {row['requirement']}\n")
            handle.write(f"  evidence: {row['evidence']}\n")
    else:
        handle.write("none\n")
    handle.write("\nDeferred requirements\n")
    handle.write("----------------------\n")
    for row in rendered:
        if row["status"] == "DEFERRED":
            handle.write(f"DEFERRED {row['id']}: {row['requirement']}\n")
    handle.write("\nNot applicable requirements\n---------------------------\n")
    for row in rendered:
        if row["status"] == "N/A":
            handle.write(f"N/A {row['id']}: {row['requirement']}\n")
            handle.write(f"  reason: {row['notes']}\n")
    handle.write("\nChecks\n------\n")
    for name in sorted(checks):
        row = checks[name]
        handle.write(f"{row['status']} {name}: {row['note']} [{row['evidence']}]\n")

print(f"matrix: {status}")
print(f"blocked={len(blocked)} deferred={counts['DEFERRED']} pass={counts['PASS']}")
PY
then
    FINAL_STATUS=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["status"])' "$OUT/audit-summary.json")
    printf 'Phase 24 audit: %s\n' "$FINAL_STATUS"
    printf '  matrix: %s\n  summary: %s\n  evidence: %s\n' \
        "$OUT/requirement-matrix.tsv" "$OUT/audit-summary.txt" "$OUT/audit-summary.json"
    if [[ "$FINAL_STATUS" == PASS ]]; then
        exit 0
    fi
    awk -F $'\t' 'NR > 1 && $5 == "BLOCKED" { print "  BLOCKED " $1 ": " $6 }' \
        "$OUT/requirement-matrix.tsv" >&2
    exit 1
else
    printf 'phase24-audit: matrix rendering failed; inspect %s\n' "$OUT/matrix-render.log" >&2
    exit 2
fi
