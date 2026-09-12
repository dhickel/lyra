#!/usr/bin/env bash
# Extended deterministic language/runtime campaign. Ordinary mvn test runs the bounded baseline too.
set -euo pipefail
LYRA_FUZZ_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    cat <<'EOF'
Usage: tools/fuzz-language.sh [Maven options]

Runs compiler/runtime fuzzing with 1,800 cases per seed (7,200 by default).
Pass ordinary Maven properties to customize or replay:

  tools/fuzz-language.sh -Dlyra.fuzz.seeds=42 -Dlyra.fuzz.cases=9000
  tools/fuzz-language.sh -Dlyra.fuzz.replay=/absolute/path/current.properties

The baseline also runs in mvn test. See docs/language-testing.md for the
coverage matrix, session campaigns, timeouts, replay, and failure reduction.
EOF
    exit 0
fi
cd -- "$LYRA_FUZZ_ROOT"
exec mvn -pl lyra-compiler -am test -Dtest=LanguageFuzzTest,RangeIntegrationTest,CallbackLoopIntegrationTest,NominalSyntaxTest,NominalSemanticsTest,NominalTypeTest,NominalTypeContractTest,NominalArtifactMetadataTest,NominalConstructionTest,GeneratedTypePlannerTest,NominalBytecodeTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dlyra.fuzz.cases=1800 "$@"
