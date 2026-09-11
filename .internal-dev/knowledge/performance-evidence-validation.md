# Performance Evidence Validation

## Topic

Phase 23 JMH, allocation, comparator, class-footprint, and reproducible evidence validation.

## Source References

- `.internal-dev/specifications/backend-runtime.md`
- `.internal-dev/specifications/decisions.md`
- `tools/phase23-evidence.sh`
- `tools/phase23_gate.py`
- `tools/phase23-gates.json`
- `lyra-compiler/src/jmh/java/io/mindspice/lyra/compiler/benchmark/Phase23Benchmark.java`

## Key Takeaways

- Raw direct Java calls are not an equivalent denominator for generated dynamic exports: generated calls cross exact `MethodHandle` and lifecycle/ownership boundaries. Phase 23 retains raw direct rows as an optimization-floor observation, while release ratios use equivalent owner/open-checked Java MethodHandle and facade paths with mutable inputs to avoid constant folding.
- Primitive steady-state allocation evidence must combine Java 25 Class-File API descriptor/instruction inspection with JMH GC-profiler measurements. Near-zero profiler values alone do not prove absence of boxing, and escape-analysis-only conclusions are insufficient.
- ClassLoadingMXBean and Metaspace samples are whole-process observations. They are useful evidence but must not be treated as artifact-attributed release gates. Exact emitted class count and bounded cold load/call are the stable artifact gates.
- Keep JMH sources under an explicit profile/test source path and dependencies test-scoped. The normal Maven reactor must not compile or package benchmark classes, and the evidence runner must explicitly verify no JMH classes enter product artifacts.
- Keep the full two-fork benchmark run separate from bounded smoke evidence. Smoke output must state that confidence is not meaningful, while gate mode enforces the complete matrix, GC profiler, fork count, warmup, and measurement minimums.

## Project Relevance

This repository uses `./tools/phase23-evidence.sh` for the complete owner-gated run and `PHASE23_MODE=observed` with explicit reduced settings only for quick diagnostics. Machine-readable gate results and normalized JSON/CSV/text files are generated under `target/`, not committed as noisy build output.

## Open Questions

- Re-run the full gate on supported target platforms after substantial production changes.
- Native Windows launcher and benchmark evidence was not available in the validation environment.
- Phase 24 still owns the final requirement matrix and release-scope audit.
