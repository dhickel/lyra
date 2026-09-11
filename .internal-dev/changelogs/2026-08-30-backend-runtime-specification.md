# Lyra standalone JVM backend and runtime specified

## Date

2026-08-30

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Established the owner-approved standalone-first compiler/backend/runtime contract and corrected the repository mission accordingly. The contract selects direct Java 25 bytecode through the Class-File API, a closed typed IR, deterministic class/JAR output, typed generated Java facades, runtime compilation, explicit module lifecycle, source-mapped failures, and a minimal console boundary.

## Files

- `AGENTS.md`
- `.internal-dev/specifications/backend-runtime.md`
- `.internal-dev/specifications/language-core.md`
- `.internal-dev/specifications/decisions.md`
- `.internal-dev/specifications/deferred-features.md`
- `.internal-dev/specifications/index.md`
- `.internal-dev/changelogs/2026-08-30-backend-runtime-specification.md`

## Behavioral Impact

No compiler/backend code changed. Intended behavior is now explicit for compiler phases, semantic identity, typed IR, JVM values/functions, module resolution and initialization, CLI and JAR workflows, Java APIs, metadata, lifecycle/thread ownership, standard I/O, failures, and performance evidence.

## Specification Impact

Created `backend-runtime.md` as the normative backend/standalone contract. Amended `language-core.md` with function identity, complete signed/unsigned widening behavior, explicit scalar string conversion, and string/array length needed by the standalone boundary. Updated durable decisions, deferred compatibility wording, and the specification index.

## Risks

- The repository remains a parser prototype and does not implement this contract.
- The normative backend surface is substantial and must be delivered through complete tested vertical slices without treating an early subset as final.
- Numerical release performance gates still require measurement and owner ratification.
- Lyra-to-Java calls, engine/Vulkan integration, and broader standard-library APIs remain deliberately deferred.

## Follow-up Items

- Produce an implementation plan from `language-core.md` and `backend-runtime.md`.
- Reconcile and assert the front end before adding stable semantic identities and typed IR.
- Establish the bytecode/performance evidence gate before broad backend expansion.
- Run separate specifications later for Lyra-to-Java interop and engine integration.
