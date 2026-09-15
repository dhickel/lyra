#!/usr/bin/env bash
# Capture Phase 23 evidence and, by default, enforce the owner-ratified gates.
# Set PHASE23_MODE=observed for a bounded/non-gating evidence run.
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"
OUT=${PHASE23_OUTPUT_DIR:-"$ROOT/target/phase23-evidence"}
if [[ "$OUT" != /* ]]; then
    OUT="$ROOT/$OUT"
fi
BENCHMARK_REGEX=${PHASE23_BENCHMARK_REGEX:-Phase23Benchmark}
FORKS=${PHASE23_FORKS:-2}
WARMUP_ITERATIONS=${PHASE23_WARMUP_ITERATIONS:-3}
MEASUREMENT_ITERATIONS=${PHASE23_MEASUREMENT_ITERATIONS:-5}
WARMUP_TIME=${PHASE23_WARMUP_TIME:-1s}
MEASUREMENT_TIME=${PHASE23_MEASUREMENT_TIME:-1s}
PROFILER=${PHASE23_PROFILER:-gc}
EVIDENCE_MODE=${PHASE23_MODE:-gate}
MAVEN_COMMAND=${MAVEN_COMMAND:-mvn}
GATE_THRESHOLDS="$ROOT/tools/phase23-gates.json"
GATE_EVALUATOR="$ROOT/tools/phase23_gate.py"

fail() {
    printf 'phase23-evidence: %s\n' "$*" >&2
    exit 1
}

[[ "$FORKS" =~ ^[0-9]+$ ]] || fail "PHASE23_FORKS must be an integer"
[[ "$WARMUP_ITERATIONS" =~ ^[0-9]+$ ]] || fail "PHASE23_WARMUP_ITERATIONS must be an integer"
[[ "$MEASUREMENT_ITERATIONS" =~ ^[0-9]+$ ]] || fail "PHASE23_MEASUREMENT_ITERATIONS must be an integer"
case "$PROFILER" in
    gc|none) ;;
    *) fail "PHASE23_PROFILER must be gc or none" ;;
esac
case "$EVIDENCE_MODE" in
    gate|observed) ;;
    *) fail "PHASE23_MODE must be gate or observed" ;;
esac
[[ -s "$GATE_THRESHOLDS" ]] || fail "gate thresholds are missing: $GATE_THRESHOLDS"
[[ -s "$GATE_EVALUATOR" ]] || fail "gate evaluator is missing: $GATE_EVALUATOR"
if [[ "$EVIDENCE_MODE" == gate ]]; then
    [[ "$BENCHMARK_REGEX" == Phase23Benchmark ]] || fail "gate mode requires the complete Phase23Benchmark matrix"
    [[ "$PROFILER" == gc ]] || fail "gate mode requires the gc profiler"
    (( FORKS >= 2 )) || fail "gate mode requires at least 2 forks"
    (( WARMUP_ITERATIONS >= 3 )) || fail "gate mode requires at least 3 warmup iterations"
    (( MEASUREMENT_ITERATIONS >= 5 )) || fail "gate mode requires at least 5 measurement iterations"
    [[ "$WARMUP_TIME" == 1s ]] || fail "gate mode requires one-second warmup iterations"
    [[ "$MEASUREMENT_TIME" == 1s ]] || fail "gate mode requires one-second measurement iterations"
fi
command -v "$MAVEN_COMMAND" >/dev/null 2>&1 || fail "Maven command not found: $MAVEN_COMMAND"
command -v python3 >/dev/null 2>&1 || fail "python3 is required to normalize JMH evidence"

if [[ -n "${JAVA_HOME:-}" ]]; then
    [[ -x "$JAVA_HOME/bin/java" ]] || fail "JAVA_HOME does not contain an executable bin/java: $JAVA_HOME"
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN=$(command -v java) || fail "java command not found"
fi

mkdir -p "$OUT"
BUILD_LOG="$OUT/build-test-compile.log"
STRUCTURAL_LOG="$OUT/structural-test.log"
CP_FILE="$OUT/dependency-classpath.txt"
JMH_JSON="$OUT/jmh.json"
JMH_LOG="$OUT/jmh-output.txt"
FOOTPRINT_JSON="$OUT/phase23-footprint.json"
FOOTPRINT_GENERATED_JSON="$OUT/phase23-footprint-generated.json"
FOOTPRINT_DIRECT_JSON="$OUT/phase23-footprint-direct-java.json"
FOOTPRINT_LOG="$OUT/phase23-footprint.log"
FOOTPRINT_DIRECT_LOG="$OUT/phase23-footprint-direct-java.log"
GATE_JSON="$OUT/phase23-gate.json"
JAVA_VERSION="$OUT/java-version.txt"
JVM_FLAGS="$OUT/jvm-flags.txt"
MAVEN_VERSION="$OUT/maven-version.txt"
rm -f "$JMH_JSON" "$FOOTPRINT_JSON" "$FOOTPRINT_GENERATED_JSON" \
    "$FOOTPRINT_DIRECT_JSON" "$GATE_JSON" "$OUT/phase23-evidence.json" \
    "$OUT/phase23-evidence.csv" "$OUT/phase23-footprint.csv" "$OUT/phase23-evidence.txt"

printf 'Running the structural no-boxing and tail-lowering gate...\n'
if ! "$MAVEN_COMMAND" -pl lyra-compiler -am \
        -Dtest=Phase23StructuralBytecodeTest \
        -Dsurefire.failIfNoSpecifiedTests=false test >"$STRUCTURAL_LOG" 2>&1; then
    cat "$STRUCTURAL_LOG" >&2
    fail "structural Phase 23 test failed"
fi

printf 'Building the JMH-scoped compiler test fixture...\n'
if ! "$MAVEN_COMMAND" -Pjmh -pl lyra-compiler -am test-compile >"$BUILD_LOG" 2>&1; then
    cat "$BUILD_LOG" >&2
    fail "profile-scoped test compilation failed"
fi

printf 'Resolving the JMH test class path...\n'
if ! "$MAVEN_COMMAND" -q -Pjmh -pl lyra-compiler dependency:build-classpath \
        -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=test >>"$BUILD_LOG" 2>&1; then
    cat "$BUILD_LOG" >&2
    fail "JMH dependency classpath resolution failed"
fi

TEST_CLASSES="$ROOT/lyra-compiler/target/test-classes"
MAIN_CLASSES="$ROOT/lyra-compiler/target/classes"
RUNTIME_CLASSES="$ROOT/lyra-runtime/target/classes"
[[ -d "$TEST_CLASSES" ]] || fail "compiler test classes are missing: $TEST_CLASSES"
[[ -d "$MAIN_CLASSES" ]] || fail "compiler main classes are missing: $MAIN_CLASSES"
[[ -d "$RUNTIME_CLASSES" ]] || fail "runtime classes are missing: $RUNTIME_CLASSES"
[[ -s "$CP_FILE" ]] || fail "JMH dependency classpath is empty: $CP_FILE"
[[ -f "$TEST_CLASSES/META-INF/BenchmarkList" ]] || fail "JMH annotation processing did not create BenchmarkList"

"$JAVA_BIN" -version 2>"$JAVA_VERSION"
"$JAVA_BIN" -XX:+PrintFlagsFinal -version >"$JVM_FLAGS" 2>&1 || true
"$MAVEN_COMMAND" -version >"$MAVEN_VERSION" 2>&1

CP="$TEST_CLASSES:$MAIN_CLASSES:$RUNTIME_CLASSES:$(<"$CP_FILE")"
JMH_ARGS=(
    "$BENCHMARK_REGEX"
    -bm avgt
    -tu ns
    -t 1
    -f "$FORKS"
    -wi "$WARMUP_ITERATIONS"
    -i "$MEASUREMENT_ITERATIONS"
    -w "$WARMUP_TIME"
    -r "$MEASUREMENT_TIME"
    -rf json
    -rff "$JMH_JSON"
    -v NORMAL
)
if [[ "$PROFILER" != "none" ]]; then
    JMH_ARGS+=( -prof "$PROFILER" )
fi

printf 'Running JMH (%s; forks=%s, warmup=%s x %s, measurement=%s x %s)...\n' \
    "$BENCHMARK_REGEX" "$FORKS" "$WARMUP_ITERATIONS" "$WARMUP_TIME" \
    "$MEASUREMENT_ITERATIONS" "$MEASUREMENT_TIME"
if ! "$JAVA_BIN" --enable-preview -cp "$CP" \
        org.openjdk.jmh.Main "${JMH_ARGS[@]}" >"$JMH_LOG" 2>&1; then
    cat "$JMH_LOG" >&2
    fail "JMH execution failed"
fi
[[ -s "$JMH_JSON" ]] || fail "JMH did not produce JSON output: $JMH_JSON"

printf 'Capturing paired generated-Lyra and direct-Java class-count/Metaspace observations...\n'
if ! "$JAVA_BIN" --enable-preview -cp "$CP" \
        io.mindspice.lyra.compiler.benchmark.Phase23FootprintProbe "$FOOTPRINT_GENERATED_JSON" \
        >"$FOOTPRINT_LOG" 2>&1; then
    cat "$FOOTPRINT_LOG" >&2
    fail "generated class-count/Metaspace footprint probe failed"
fi
if ! "$JAVA_BIN" --enable-preview -cp "$CP" \
        io.mindspice.lyra.compiler.benchmark.Phase23FootprintProbe "$FOOTPRINT_DIRECT_JSON" \
        --direct-java >"$FOOTPRINT_DIRECT_LOG" 2>&1; then
    cat "$FOOTPRINT_DIRECT_LOG" >&2
    fail "direct-Java class-count/Metaspace footprint probe failed"
fi
[[ -s "$FOOTPRINT_GENERATED_JSON" ]] || fail "generated footprint probe did not produce JSON output: $FOOTPRINT_GENERATED_JSON"
[[ -s "$FOOTPRINT_DIRECT_JSON" ]] || fail "direct-Java footprint probe did not produce JSON output: $FOOTPRINT_DIRECT_JSON"

set +e
python3 "$GATE_EVALUATOR" --jmh "$JMH_JSON" --footprint "$FOOTPRINT_GENERATED_JSON" \
    --thresholds "$GATE_THRESHOLDS" --output "$GATE_JSON" --mode "$EVIDENCE_MODE"
GATE_EXIT=$?
set -e
[[ -s "$GATE_JSON" ]] || fail "gate evaluator did not produce output: $GATE_JSON"
if (( GATE_EXIT > 1 )); then
    fail "gate evidence was invalid or incomplete"
fi

python3 - "$ROOT" "$OUT" "$JMH_JSON" "$FOOTPRINT_GENERATED_JSON" "$FOOTPRINT_DIRECT_JSON" "$GATE_JSON" "$JAVA_VERSION" "$JVM_FLAGS" "$MAVEN_VERSION" \
        "$BENCHMARK_REGEX" "$PROFILER" "$EVIDENCE_MODE" "$FORKS" "$WARMUP_ITERATIONS" \
        "$MEASUREMENT_ITERATIONS" "$WARMUP_TIME" "$MEASUREMENT_TIME" <<'PY'
import csv
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
from pathlib import Path

(
    root_text,
    out_text,
    jmh_path_text,
    generated_footprint_path_text,
    direct_footprint_path_text,
    gate_path_text,
    java_version_path_text,
    jvm_flags_path_text,
    maven_version_path_text,
    benchmark_regex,
    profiler,
    evidence_mode,
    forks_text,
    warmup_iterations_text,
    measurement_iterations_text,
    warmup_time,
    measurement_time,
) = sys.argv[1:]
root = Path(root_text)
out = Path(out_text)
jmh_path = Path(jmh_path_text)
generated_footprint_path = Path(generated_footprint_path_text)
direct_footprint_path = Path(direct_footprint_path_text)
gate_path = Path(gate_path_text)

with jmh_path.open(encoding="utf-8") as handle:
    raw_results = json.load(handle)
if not raw_results:
    raise SystemExit("phase23-evidence: JMH JSON contains no results")
with generated_footprint_path.open(encoding="utf-8") as handle:
    generated_footprint = json.load(handle)
with direct_footprint_path.open(encoding="utf-8") as handle:
    direct_footprint = json.load(handle)
with gate_path.open(encoding="utf-8") as handle:
    gate_result = json.load(handle)
if generated_footprint.get("mode") != "generated-lyra":
    raise SystemExit("phase23-evidence: generated footprint probe returned the wrong mode")
if direct_footprint.get("mode") != "direct-java":
    raise SystemExit("phase23-evidence: direct-Java footprint probe returned the wrong mode")
for label, footprint in (("generated", generated_footprint), ("direct-Java", direct_footprint)):
    if footprint.get("fixtureInvocationResult") != 42:
        raise SystemExit(f"phase23-evidence: {label} footprint fixture did not return 42")
if not generated_footprint.get("artifactClassCount", 0) > 0:
    raise SystemExit("phase23-evidence: generated footprint has no artifact class count")
if direct_footprint.get("directBaselineClassCount") != 1:
    raise SystemExit("phase23-evidence: direct-Java footprint has no one-class baseline")
footprint = {
    "schema": "lyra.phase23.footprint-comparison.v1",
    "comparison": "separate-JVM observations; emitted class count is gated, process-wide class loading and Metaspace are evidence-only",
    "generatedLyra": generated_footprint,
    "directJava": direct_footprint,
}

required_scenarios = {
    "typed-direct-call",
    "arithmetic",
    "branch",
    "tail-recursion",
    "closure-cell",
    "array-read",
    "tuple-read",
    "string",
    "array-allocation",
    "tuple-allocation",
    "closure-allocation",
    "string-allocation",
    "failure",
}
required_methods = {
    "fibDirectNameCall",
    "fibSExpressionCall",
    "generatedCachedExportHandleCall",
    "generatedColdLoadAndCall",
    "generatedFacadeCall",
    "generatedWarmExactHandleCall",
    "javaColdConstructionAndCall",
    "javaEquivalentWorkload",
    "javaFacadeCall",
    "javaWarmDirectCall",
    "javaWarmExactHandleCall",
    "namedDirectNameCall",
    "namedSExpressionCall",
}
short_names = {item.get("benchmark", "").rsplit(".", 1)[-1] for item in raw_results}
observed_scenarios = sorted({
    item.get("params", {}).get("scenario")
    for item in raw_results
    if item.get("benchmark", "").endswith(".generatedWorkload")
})
observed_methods = sorted(short_names)
if benchmark_regex == "Phase23Benchmark":
    missing_methods = required_methods - short_names
    if missing_methods:
        raise SystemExit(
            "phase23-evidence: default run is missing benchmarks: "
            + ", ".join(sorted(missing_methods)))
    for paired_method in ("generatedWorkload", "javaEquivalentWorkload", "javaWorkload"):
        observed = {
            item.get("params", {}).get("scenario")
            for item in raw_results
            if item.get("benchmark", "").endswith("." + paired_method)
        }
        missing_scenarios = required_scenarios - observed
        if missing_scenarios:
            raise SystemExit(
                f"phase23-evidence: {paired_method} is missing scenarios: "
                + ", ".join(sorted(missing_scenarios)))

def text_file(path_text):
    return Path(path_text).read_text(encoding="utf-8", errors="replace").strip()

def git(*args):
    try:
        return subprocess.check_output(
            ["git", "-C", str(root), *args],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return None

def source_hash():
    sources = [
        root / "lyra-compiler/src/jmh/java/io/mindspice/lyra/compiler/benchmark/Phase23Benchmark.java",
        root / "lyra-compiler/src/jmh/java/io/mindspice/lyra/compiler/benchmark/Phase23FootprintProbe.java",
    ]
    digest = hashlib.sha256()
    for source in sources:
        digest.update(str(source.relative_to(root)).encode("utf-8"))
        digest.update(b"\0")
        digest.update(source.read_bytes())
    return digest.hexdigest()

def metric_row(metric):
    return {
        "score": metric.get("score"),
        "scoreError": metric.get("scoreError"),
        "scoreConfidence": metric.get("scoreConfidence"),
        "scoreUnit": metric.get("scoreUnit"),
    }

def result_row(item):
    return {
        "benchmark": item.get("benchmark"),
        "mode": item.get("mode"),
        "threads": item.get("threads"),
        "forks": item.get("forks"),
        "params": item.get("params", {}),
        "primaryMetric": metric_row(item.get("primaryMetric", {})),
        "secondaryMetrics": {
            name: metric_row(metric)
            for name, metric in sorted(item.get("secondaryMetrics", {}).items())
        },
    }

def secondary(item, name):
    return item.get("secondaryMetrics", {}).get(name, {})

def score(item, name):
    return secondary(item, name).get("score", "")

def unit(item, name):
    return secondary(item, name).get("scoreUnit", "")

def csv_value(value):
    if value is None:
        return ""
    if isinstance(value, (dict, list)):
        return json.dumps(value, sort_keys=True, separators=(",", ":"))
    return str(value)

first = raw_results[0]
flag_text = text_file(jvm_flags_path_text)
gc_flags = sorted(set(re.findall(r"\b(Use[A-Za-z0-9]+GC)\s*=\s*true", flag_text)))
command_line_jvm_flags = list(dict.fromkeys(first.get("jvmArgs", ["--enable-preview"])))
cpu_model = ""
try:
    for line in Path("/proc/cpuinfo").read_text(encoding="utf-8", errors="replace").splitlines():
        if ":" in line and line.lower().startswith(("model name", "hardware")):
            cpu_model = line.split(":", 1)[1].strip()
            break
except OSError:
    pass
if profiler == "gc":
    missing_allocation = [
        item.get("benchmark", "") for item in raw_results
        if "gc.alloc.rate.norm" not in item.get("secondaryMetrics", {})
    ]
    if missing_allocation:
        raise SystemExit(
            "phase23-evidence: gc profiler did not produce gc.alloc.rate.norm for: "
            + ", ".join(missing_allocation))

normalized = [result_row(item) for item in raw_results]
limitations = [
    "ClassLoadingMXBean and Metaspace values are whole-process observations, not attribution to one artifact or class.",
    "Raw direct-Java rows are retained as an optimization-floor observation but are not ratio denominators: generated dynamic exports cross an exact MethodHandle boundary and generated facades enforce owner/open lifecycle checks.",
    "Applicable latency ratios use owner/open-checked Java exact-MethodHandle workloads and facade calls with identical non-final state inputs; this prevents raw Java inlining and constant folding from being treated as equivalent boundary work."
]
if int(forks_text) < 2 or int(measurement_iterations_text) < 2:
    limitations.append(
        "This is a bounded smoke run with fewer than two forks or measurements; no meaningful statistical confidence interval is available."
    )
if profiler == "none":
    limitations.append(
        "GC allocation profiling was explicitly disabled; allocation conclusions are unavailable."
    )
if benchmark_regex != "Phase23Benchmark":
    limitations.append(
        "A custom benchmark regex was requested; the complete Phase 23 workload matrix was not validated by this run."
    )
evidence = {
    "schema": "lyra.phase23.performance-evidence.v2",
    "status": "gate-" + gate_result["status"] if evidence_mode == "gate" else "observed-evidence",
    "gate": gate_result,
    "source": {
        "benchmarkRegex": benchmark_regex,
        "benchmarkFixture": "io.mindspice.lyra.compiler.benchmark.Phase23Benchmark",
        "fixtureSourceSha256": source_hash(),
        "gitRevision": git("rev-parse", "HEAD"),
        "gitStatusPorcelain": git("status", "--porcelain=v1"),
    },
    "environment": {
        "javaVersion": text_file(java_version_path_text),
        "javaExecutable": first.get("jvm"),
        "jdkVersion": first.get("jdkVersion"),
        "vmName": first.get("vmName"),
        "vmVersion": first.get("vmVersion"),
        "jvmFlags": command_line_jvm_flags,
        "jvmFlagsSnapshot": "jvm-flags.txt",
        "gcFlagsDetected": gc_flags,
        "os": {
            "name": platform.system(),
            "release": platform.release(),
            "version": platform.version(),
            "machine": platform.machine(),
            "processor": platform.processor(),
            "cpuModel": cpu_model or "unavailable",
            "logicalProcessors": os.cpu_count(),
        },
        "mavenVersion": text_file(maven_version_path_text),
    },
    "limitations": limitations,
    "methodology": {
        "comparatorPolicy": {
            "gateDenominator": "equivalent owner/open-checked Java exact MethodHandle for dynamic exports and equivalent owner/open-checked typed Java facade for facade calls",
            "inputs": "identical mutable JMH state fields prevent compile-time constant folding in generated and Java paths",
            "rawBaseline": "raw direct Java remains in results but is not used as the denominator for boundary-sensitive release ratios"
        },
        "benchmarkMode": "AverageTime",
        "outputUnit": "ns/op",
        "threads": 1,
        "forks": int(forks_text),
        "warmupIterations": int(warmup_iterations_text),
        "warmupTime": warmup_time,
        "measurementIterations": int(measurement_iterations_text),
        "measurementTime": measurement_time,
        "profiler": profiler,
        "confidence": "JMH scoreError and scoreConfidence are preserved for primary and secondary metrics in raw/normalized JSON; primary error and bounds are also exported to CSV. A one-fork/one-measurement smoke has no meaningful confidence interval.",
        "allocation": {
            "status": "collected" if profiler == "gc" else "not-collected",
            "metric": "gc.alloc.rate.norm" if profiler == "gc" else None,
            "unit": "B/op" if profiler == "gc" else None,
            "method": "JMH gc profiler per fork/trial" if profiler == "gc" else "Profiler explicitly disabled by PHASE23_PROFILER=none",
            "scope": "Generated and direct-Java benchmark method plus harness-visible allocations; compare paired rows, do not treat as artifact-only allocation." if profiler == "gc" else "No allocation observation is available.",
            "limitation": None if profiler == "gc" else "GC allocation evidence was not collected; no allocation conclusion is made.",
        },
        "classLoading": {
            "probe": "Phase23FootprintProbe",
            "method": "Separate JVM probes record the exact generated artifact class count or one direct-Java baseline class, plus ClassLoadingMXBean samples before/after load and invoke.",
            "scope": "Artifact/direct-baseline class count is exact; loaded-class samples are whole-process observations.",
            "comparison": "Generated Lyra and direct-Java baseline are measured in separate JVM processes; process-wide deltas are observations, not artifact attribution.",
        },
        "metaspace": {
            "probe": "Phase23FootprintProbe",
            "method": "Separate JVM probes sum MemoryPoolMXBean pools whose name contains Metaspace before/after the generated artifact or direct-Java baseline is loaded and invoked.",
            "scope": "Whole probe JVM; not attribution to one class and not a threshold gate.",
            "comparison": "Generated and direct-Java deltas are paired observations with process-wide measurement noise."
        },
    },
    "workloads": {
        "requiredPairedScenarios": sorted(required_scenarios),
        "observedGeneratedWorkloadScenarios": observed_scenarios,
        "observedBenchmarkMethods": observed_methods,
    },
    "validation": {
        "structuralTest": "Phase23StructuralBytecodeTest",
        "structuralTestStatus": "passed",
        "structuralTestLog": "structural-test.log",
        "profileBuildStatus": "passed",
        "profileBuildLog": "build-test-compile.log",
        "jmhLog": "jmh-output.txt",
        "rawJmhJson": "jmh.json",
        "footprintJson": "phase23-footprint.json",
        "footprintGeneratedJson": "phase23-footprint-generated.json",
        "footprintDirectJavaJson": "phase23-footprint-direct-java.json",
        "footprintProbeStatus": "passed",
        "gateResult": "phase23-gate.json",
        "footprintLog": "phase23-footprint.log",
        "footprintDirectJavaLog": "phase23-footprint-direct-java.log",
        "normalizedJson": "phase23-evidence.json",
        "csv": "phase23-evidence.csv",
        "footprintCsv": "phase23-footprint.csv",
    },
    "footprint": footprint,
    "results": normalized,
}

footprint_json_path = out / "phase23-footprint.json"
with footprint_json_path.open("w", encoding="utf-8", newline="\n") as handle:
    json.dump(footprint, handle, indent=2, sort_keys=True)
    handle.write("\n")

json_path = out / "phase23-evidence.json"
with json_path.open("w", encoding="utf-8", newline="\n") as handle:
    json.dump(evidence, handle, indent=2, sort_keys=True)
    handle.write("\n")

fieldnames = [
    "benchmark",
    "params",
    "mode",
    "threads",
    "forks",
    "score",
    "scoreError",
    "scoreConfidenceLow",
    "scoreConfidenceHigh",
    "scoreUnit",
    "gcAllocRate",
    "gcAllocRateUnit",
    "gcAllocRateNorm",
    "gcAllocRateNormUnit",
    "gcCount",
    "gcCountUnit",
    "gcTime",
    "gcTimeUnit",
]
csv_path = out / "phase23-evidence.csv"
with csv_path.open("w", encoding="utf-8", newline="") as handle:
    writer = csv.DictWriter(handle, fieldnames=fieldnames)
    writer.writeheader()
    for item in raw_results:
        primary = item.get("primaryMetric", {})
        writer.writerow({
            "benchmark": item.get("benchmark", ""),
            "params": csv_value(item.get("params", {})),
            "mode": item.get("mode", ""),
            "threads": item.get("threads", ""),
            "forks": item.get("forks", ""),
            "score": csv_value(primary.get("score")),
            "scoreError": csv_value(primary.get("scoreError")),
            "scoreConfidenceLow": csv_value((primary.get("scoreConfidence") or ["", ""])[0]),
            "scoreConfidenceHigh": csv_value((primary.get("scoreConfidence") or ["", ""])[1]),
            "scoreUnit": primary.get("scoreUnit", ""),
            "gcAllocRate": csv_value(score(item, "gc.alloc.rate")),
            "gcAllocRateUnit": unit(item, "gc.alloc.rate"),
            "gcAllocRateNorm": csv_value(score(item, "gc.alloc.rate.norm")),
            "gcAllocRateNormUnit": unit(item, "gc.alloc.rate.norm"),
            "gcCount": csv_value(score(item, "gc.count")),
            "gcCountUnit": unit(item, "gc.count"),
            "gcTime": csv_value(score(item, "gc.time")),
            "gcTimeUnit": unit(item, "gc.time"),
        })

footprint_fieldnames = ["baseline", "metric", "value", "unit", "scope"]
footprint_csv_path = out / "phase23-footprint.csv"
with footprint_csv_path.open("w", encoding="utf-8", newline="") as handle:
    writer = csv.DictWriter(handle, fieldnames=footprint_fieldnames)
    writer.writeheader()
    for baseline, observed in (("generated-lyra", generated_footprint), ("direct-java", direct_footprint)):
        class_count_key = "artifactClassCount" if baseline == "generated-lyra" else "directBaselineClassCount"
        class_scope = "compiled generated artifact" if baseline == "generated-lyra" else "compiled direct-Java baseline"
        for metric, value, unit, scope in [
            ("classCount", observed.get(class_count_key), "classes", class_scope),
            ("loadedClassCountBefore", observed.get("loadedClassCountBefore"), "classes", "whole probe JVM"),
            ("loadedClassCountAfter", observed.get("loadedClassCountAfter"), "classes", "whole probe JVM"),
            ("loadedClassCountDelta", observed.get("loadedClassCountDelta"), "classes", "whole probe JVM"),
            ("metaspaceUsedBytesBefore", observed.get("metaspaceUsedBytesBefore"), "bytes", "whole probe JVM"),
            ("metaspaceUsedBytesAfter", observed.get("metaspaceUsedBytesAfter"), "bytes", "whole probe JVM"),
            ("metaspaceUsedBytesDelta", observed.get("metaspaceUsedBytesDelta"), "bytes", "whole probe JVM"),
        ]:
            writer.writerow({"baseline": baseline, "metric": metric, "value": value, "unit": unit, "scope": scope})

human_path = out / "phase23-evidence.txt"
with human_path.open("w", encoding="utf-8", newline="\n") as handle:
    handle.write("Lyra Phase 23 performance/allocation evidence\n")
    handle.write("=============================================\n\n")
    handle.write(f"Status: {evidence['status']}. Owner-ratified numeric gates were evaluated.\n")
    handle.write(f"Evaluation mode: {evidence_mode}; all selected gates pass: {gate_result['allSelectedGatesPass']}.\n")
    handle.write("Structural Class-File API gate: Phase23StructuralBytecodeTest passed.\n")
    for limitation in limitations:
        handle.write(f"Limitation: {limitation}\n")
    handle.write("\n")
    handle.write("Environment\n")
    handle.write("-----------\n")
    handle.write(f"JDK: {first.get('jdkVersion')} {first.get('vmName')} {first.get('vmVersion')}\n")
    handle.write(f"JVM: {first.get('jvm')}\n")
    handle.write(f"JVM flags: {', '.join(command_line_jvm_flags)}\n")
    handle.write("JVM flag snapshot: jvm-flags.txt\n")
    handle.write(f"OS: {platform.system()} {platform.release()} {platform.machine()}\n")
    handle.write(f"CPU model: {cpu_model or 'unavailable'}\n")
    handle.write(f"Logical processors: {os.cpu_count()}\n")
    handle.write(f"Detected GC flags: {', '.join(gc_flags) or 'not parsed'}\n")
    handle.write(f"Git revision: {git('rev-parse', 'HEAD') or 'unavailable'}\n")
    handle.write(f"Fixture sources SHA-256: {source_hash()}\n\n")
    handle.write("Methodology\n")
    handle.write("-----------\n")
    handle.write(f"Mode: AverageTime, ns/op, one thread, {forks_text} fork(s)\n")
    handle.write(f"Warmup: {warmup_iterations_text} x {warmup_time}; measurement: {measurement_iterations_text} x {measurement_time}\n")
    handle.write(f"Profiler: {profiler}\n")
    handle.write("Confidence: JMH scoreError and scoreConfidence are retained in JSON; primary error/bounds are in CSV.\n")
    handle.write("Comparator: release ratios use owner/open-checked Java exact-handle paths; facade ratios use the same checked Java facade. Raw direct Java is retained only as an optimization-floor observation.\n")
    if profiler == "gc":
        handle.write("Allocation: gc.alloc.rate.norm (B/op), paired generated/direct-Java rows; harness-visible and not artifact-only.\n")
    else:
        handle.write("Allocation: NOT COLLECTED because PHASE23_PROFILER=none; no allocation conclusion is made.\n")
    handle.write("Observed generated workload scenarios: " + ", ".join(observed_scenarios) + "\n")
    handle.write("Observed benchmark methods: " + ", ".join(observed_methods) + "\n")
    handle.write("Class/metaspace probe: generated artifact and direct-Java baseline class counts are exact; loaded-class and Metaspace values are whole-JVM before/after samples from separate processes.\n")
    handle.write(
        f"Footprint generated: artifact={generated_footprint.get('artifactClassCount')} classes; "
        f"loaded delta={generated_footprint.get('loadedClassCountDelta')} classes; "
        f"Metaspace delta={generated_footprint.get('metaspaceUsedBytesDelta')} bytes.\n"
        f"Footprint direct-Java: baseline={direct_footprint.get('directBaselineClassCount')} classes; "
        f"loaded delta={direct_footprint.get('loadedClassCountDelta')} classes; "
        f"Metaspace delta={direct_footprint.get('metaspaceUsedBytesDelta')} bytes.\n\n"
    )
    handle.write("Selected gates\n")
    handle.write("--------------\n")
    for item in gate_result["checks"]:
        handle.write(f"{item['status'].upper()} {item['name']}: {item['observed']} {item['unit']} <= {item['limit']} ({item['comparison']})\n")
    handle.write("Class-loading and Metaspace: evidence-only because the probes are whole-process scoped.\n\n")
    handle.write("Results\n")
    handle.write("-------\n")
    for item in raw_results:
        primary = item.get("primaryMetric", {})
        params = item.get("params", {})
        handle.write(
            f"{item.get('benchmark')} {json.dumps(params, sort_keys=True)}: "
            f"{primary.get('score')} {primary.get('scoreUnit')}"
        )
        alloc = secondary(item, "gc.alloc.rate.norm")
        if alloc:
            handle.write(f"; alloc.norm={alloc.get('score')} {alloc.get('scoreUnit')}")
        handle.write("\n")
    handle.write("\nFiles\n-----\n")
    handle.write("jmh.json: raw JMH JSON\n")
    handle.write("phase23-footprint-generated.json: raw generated-Lyra footprint observation\n")
    handle.write("phase23-footprint-direct-java.json: raw direct-Java footprint observation\n")
    handle.write("phase23-footprint.json: normalized paired footprint observations\n")
    handle.write("phase23-gate.json: explicit thresholds and machine-readable pass/fail checks\n")
    handle.write("phase23-evidence.json: normalized machine-readable evidence\n")
    handle.write("phase23-evidence.csv: tabular JMH machine-readable evidence\n")
    handle.write("phase23-footprint.csv: tabular paired footprint evidence\n")
    handle.write("phase23-evidence.txt: this human-readable summary\n")

print(f"Evidence written to {out}")
PY

if (( GATE_EXIT != 0 )); then
    fail "one or more owner-ratified Phase 23 gates failed; inspect $GATE_JSON"
fi

printf 'Phase 23 evidence complete: %s\n' "$OUT"
printf '  %s\n' "$OUT/phase23-gate.json" "$OUT/phase23-evidence.json" "$OUT/phase23-evidence.csv" \
    "$OUT/phase23-footprint-generated.json" "$OUT/phase23-footprint-direct-java.json" \
    "$OUT/phase23-footprint.json" "$OUT/phase23-footprint.csv" "$OUT/phase23-evidence.txt"
