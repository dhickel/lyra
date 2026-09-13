#!/usr/bin/env bash
# Extended deterministic language/runtime campaign. Ordinary mvn test runs the bounded baseline too.
#
# One Maven reactor invocation exercises both models:
#   1. The compiler/runtime fuzz campaign (LanguageFuzzTest + the nominal matrix),
#      including the retained-nominal transfer/certificate operation family.
#   2. The persistent-session state model (SessionStateFuzzTest) in disposable
#      child JVMs with nominal construction, replacement, failure and reset ops.
# A failure in either model fails the whole invocation.
set -euo pipefail
LYRA_FUZZ_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    cat <<'EOF'
Usage: tools/fuzz-language.sh [Maven options]

Runs the compiler/runtime fuzz campaign AND the persistent-session state model
in one Maven reactor invocation. Default budgets:

  compiler: -Dlyra.fuzz.cases=1800 -Dlyra.fuzz.seeds=1,24301,8675309,9223372036854775807
  session:  -Dlyra.sessionFuzz.seeds=7,83,137 -Dlyra.sessionFuzz.steps=240

Pass ordinary Maven properties to override (the last one wins):

  tools/fuzz-language.sh -Dlyra.fuzz.seeds=42 -Dlyra.fuzz.cases=9000
  tools/fuzz-language.sh -Dlyra.sessionFuzz.seeds=137 -Dlyra.sessionFuzz.steps=500

Compiler replay is unchanged: -Dlyra.fuzz.replay=/absolute/path/current.properties
replays only the saved compiler case while the rest of the nominal matrix and
the session model still run.

The session model has no file replay. A failed session run names the exact
seed/steps command and keeps the full transcript in
lyra-repl/target/session-fuzz/seed-<seed>-*/worker.log; rerun it with:

  mvn -pl lyra-repl -am test -Dtest=SessionStateFuzzTest,LanguageCoverageTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dlyra.sessionFuzz.seeds=<seed> -Dlyra.sessionFuzz.steps=<steps>

The baseline also runs in mvn test. See docs/language-testing.md for the
coverage matrix, session campaigns, timeouts, replay, and failure reduction.
EOF
    exit 0
fi
cd -- "$LYRA_FUZZ_ROOT"
echo "== Lyra extended campaign: compiler fuzz + persistent-session model =="
echo "compiler defaults: cases=1800 seeds=1,24301,8675309,9223372036854775807 (override with -Dlyra.fuzz.cases/-Dlyra.fuzz.seeds)"
echo "session defaults: seeds=7,83,137 steps=240 (override with -Dlyra.sessionFuzz.seeds/-Dlyra.sessionFuzz.steps)"
RUN_LOG=$(mktemp /tmp/lyra-fuzz-XXXXXX.log)
before_compiler=$(ls -1d lyra-compiler/target/language-fuzz/seed-*/ 2>/dev/null | sort || true)
before_session=$(ls -1d lyra-repl/target/session-fuzz/seed-*/ 2>/dev/null | sort || true)
status=0
mvn -pl lyra-repl -am test \
    -Dtest=LanguageFuzzTest,RangeIntegrationTest,CallbackLoopIntegrationTest,NominalSyntaxTest,NominalSemanticsTest,NominalTypeTest,NominalTypeContractTest,NominalArtifactMetadataTest,NominalConstructionTest,GeneratedTypePlannerTest,NominalBytecodeTest,SessionStateFuzzTest,LanguageCoverageTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dlyra.fuzz.cases=1800 -Dlyra.fuzz.seeds=1,24301,8675309,9223372036854775807 \
    -Dlyra.sessionFuzz.seeds=7,83,137 -Dlyra.sessionFuzz.steps=240 \
    "$@" > "$RUN_LOG" 2>&1 || status=$?
echo "== Maven reactor summaries (this run) =="
grep -E "Tests run: .* Failures: " "$RUN_LOG" | tail -n 20 || true
grep -E "BUILD (SUCCESS|FAILURE)" "$RUN_LOG" | tail -n 1 || true
echo "Full log: $RUN_LOG"
echo "== Compiler fuzz summaries (this run: categories, numeric distribution, retained operations) =="
compiler_found=0
for summary in lyra-compiler/target/language-fuzz/seed-*/summary.txt; do
    [[ -f "$summary" ]] || continue
    dir=$(dirname "$summary")
    if ! grep -qxF "$dir/" <<<"$before_compiler"; then
        compiler_found=1
        echo "--- $summary"
        cat "$summary"
    fi
done
if [[ "$compiler_found" == "0" ]]; then
    echo "no new compiler campaign summaries under lyra-compiler/target/language-fuzz/ (see $RUN_LOG)"
fi
echo "== Session model results (this run, per-seed completion lines) =="
session_found=0
for log in lyra-repl/target/session-fuzz/seed-*/worker.log; do
    [[ -f "$log" ]] || continue
    dir=$(dirname "$log")
    if ! grep -qxF "$dir/" <<<"$before_session"; then
        session_found=1
        grep -h "SESSION FUZZ PASS" "$log" | tail -n 1 | sed "s|^|$(basename "$dir"): |" || true
    fi
done
if [[ "$session_found" == "0" ]]; then
    echo "no new session worker logs under lyra-repl/target/session-fuzz/ (see $RUN_LOG)"
fi
if [[ "$status" -ne 0 ]]; then
    echo "== Failure evidence =="
    echo "compiler replays: lyra-compiler/target/language-fuzz/seed-*/current.properties"
    echo "session transcripts: lyra-repl/target/session-fuzz/seed-*/worker.log"
    echo "session replay: mvn -pl lyra-repl -am test -Dtest=SessionStateFuzzTest,LanguageCoverageTest -Dsurefire.failIfNoSpecifiedTests=false -Dlyra.sessionFuzz.seeds=<seed> -Dlyra.sessionFuzz.steps=<steps>"
    exit "$status"
fi
exit 0
