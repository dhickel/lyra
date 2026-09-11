# Phase 23 Performance and Allocation Evidence

## Date

2026-09-04

## Git Commit

b92edf9ce78f2e20c4410249f4da08314d14a277

## Change Summary

Added the Phase 23 JMH performance/allocation evidence gate for the production Lyra source-to-validated-IR-to-Java-25-Class-File pipeline. The benchmark matrix compares generated Lyra with equivalent owner/open-checked Java exact-handle and facade paths, while retaining raw direct-Java optimization-floor observations. Added structural Class-File API no-boxing and self-tail-loop checks, class-count/Metaspace probes, and reproducible JSON/CSV/text evidence tooling.

## Files

- `pom.xml`
- `lyra-compiler/pom.xml`
- `lyra-compiler/src/jmh/java/io/mindspice/lyra/compiler/benchmark/Phase23Benchmark.java`
- `lyra-compiler/src/jmh/java/io/mindspice/lyra/compiler/benchmark/Phase23FootprintProbe.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/Phase23StructuralBytecodeTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/Phase23EvidenceGateContractTest.java`
- `tools/phase23-evidence.sh`
- `tools/phase23-gates.json`
- `tools/phase23_gate.py`

## Behavioral Impact

No language semantics or public API changed. Non-nullable generated facade function calls now use the already-authenticated typed function slot directly; nullable function calls retain the authenticated boundary and its structured null failure. This removes repeated signature parsing/authentication from the primitive hot path while preserving setter and construction authentication.

## Specification Impact

Implements the existing Phase 23 performance/allocation evidence and owner-ratification requirements in `backend-runtime.md`; no language, runtime ABI, public API, or deferred-feature contract changed. The owner-ratified gate is explicit in `tools/phase23-gates.json`. Phase 24 release review remains unstarted.

## Risks

Timing and process-wide class/Metaspace observations remain host-sensitive. Class-loading and Metaspace are recorded evidence only because the probes measure whole JVMs. JMH evidence uses equivalent checked boundaries for release ratios and retains raw direct-Java rows separately.

## Follow-up Items

Run Phase 24 final scope and release review only after this Phase 23 gate and its evidence are accepted. Do not treat the Phase 23 gate as a substitute for the final requirement matrix.
