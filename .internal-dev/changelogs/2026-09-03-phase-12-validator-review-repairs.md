# Phase 12 Typed IR Validator Review Repairs

## Date

2026-09-03

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Independent Phase-12 review repaired closed-IR publication and validation defects left by the implementation attempt. Numeric runtime checks now match semantic failure-site authority: nil coalescing emits an explicit narrowing/control node without a fabricated `LYR-CONVERT` failure, while deterministic `String[value]` conversion does not create a runtime failure site. Callable summary call IDs, producer source-site paths, aggregate provenance, and eager dependency evidence are retained and checked strictly. Initialization/control-flow metadata and published artifact boundaries reject duplicate/noncanonical or unvalidated data.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/TypedIr.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/TypedIrBuilder.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrNode.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrCheckKind.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrFlowMetadata.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrProgramMetadata.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrInitializationPlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrInitializationCycle.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrEvaluationOrder.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrFunctionLink.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrFunctionLinkage.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrFunctionScc.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrCapture.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrClosureInitialization.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrExport.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrImportBinding.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedFailureSite.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/ir/TypedIrTest.java`
- `lyra-compiler/src/test/java/Domain11SemanticTest.java`
- `.internal-dev/knowledge/typed-ir-sealing.md`

## Behavioral Impact

No source-language behavior or Phase-11 topology-certificate exception was changed. The Phase-12 IR now rejects incomplete provenance and publishes only validator-approved artifacts. Member direct calls remain outside the current typed subset and are rejected before IR publication with the existing structured `LYC-TYPE-013` diagnostic; no IR node erases their receiver semantics.

## Specification Impact

Specification Impact: none. The repairs enforce the existing language-core/backend-runtime Phase-12 contract and clarify its existing distinction between invocation-fatal numeric/conversion failures and non-failing nil coalescing/text conversion.

## Risks

The IR remains an internal, nonserialized compiler artifact. Constructor-created candidates remain available for negative fixtures but require the validator publication gate before downstream use. No JVM backend, runtime, CLI, or public Java API work was added.

## Follow-up Items

None within Phase 12. Phase 13+ remains out of scope for this review.
