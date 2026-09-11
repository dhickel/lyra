# Phase 11 Max-Gate Semantic Repairs

## Date

2026-08-31

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Repaired the ten independent phase-11 semantic counterexamples without broadening the language scope. Aggregate ownership now retains exact tuple/index selector routes, higher-order provenance evaluates positional callable parameter bindings, coalesce and conditional provenance joins preserve all reachable states, and callable aggregate discovery observes flow-local replacements. Eager initialization now executes known lambda calls with bound arguments, follows complete nested aggregate routes, filters assignments by source order and execution context, and derives context for inferred composite nil branches.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticResolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/InitializationAnalyzer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticProvenance.java`
- `lyra-compiler/src/test/java/Domain11SemanticTest.java`
- `.internal-dev/knowledge/semantic-aggregate-provenance.md`

## Behavioral Impact

Local aggregate siblings remain independently authorized, imported identities cannot be laundered through higher-order calls, replacements, fallback-only rebinding, or conditional returns, and fresh/local values remain accepted. Eager dependency analysis reports only selected callable routes and assignments reachable at the analyzed source position, while still rejecting executed dependency cycles. Inferred `Array[#NIL]` branches receive nested nil context from their peer branch and retain bare/nonnil nil rejection.

## Specification Impact

Specification Impact: none. The repairs implement the existing identity-sensitive mutation, strict eager evaluation, source-order flow, conservative cycle, and nil-context contracts in `language-core.md` and `backend-runtime.md` without changing their normative behavior.

## Risks

Phase 11 remains limited to the current primitive, array, tuple, function, module, and semantic-analysis scope. Typed IR sealing and all later JVM backend/runtime/CLI work remain unfinished, and no phase-11 completion claim is made by this record.

## Follow-up Items

Continue phase 12 typed-IR sealing only after preserving exact aggregate routes, immutable publication, bound-call guards, and source-order initialization evidence.
