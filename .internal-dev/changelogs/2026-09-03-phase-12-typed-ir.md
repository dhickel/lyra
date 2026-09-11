# Phase 12 Closed Typed IR

## Date

2026-09-03

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Completed the closed immutable typed-IR boundary. Lowering now carries producer-issued flow sites, explicit mutation routes, aggregate allocation/provenance, runtime-check identities, and explicit evaluation/control-flow edges. The IR publishes immutable module state, import/export linkage, function SCC linkage, closure initialization records, shared capture-cell metadata, canonical initialization dependencies/order, and the complete producer-owned flow artifact projection.

The builder publishes only after the IR validator accepts exact source-expression coverage and metadata parity. Constructor-created candidates remain available for negative validator fixtures but are rejected by downstream validation/consumer gates.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrNode.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrVisitor.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrTraversal.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/TypedIrBuilder.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/TypedIr.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrProgramMetadata.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrFlowMetadata.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrModule.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrModuleState.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrDeclaration.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrReference.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrLambda.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrCapture.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrCell.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrExport.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrImportBinding.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrFunctionLink.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrFunctionScc.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrFunctionLinkage.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrClosureInitialization.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrAggregateAllocation.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrAggregateProvenance.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrEvaluationOrder.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrExpressionSite.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrFailureSite.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrInitializationDependency.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrInitializationCycle.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrInitializationPlan.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedFailureSite.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/ir/TypedIrTest.java`

## Behavioral Impact

No source-language behavior was changed. Member direct calls remain rejected by the existing typed-language boundary. The new IR preserves the accepted Phase-11 facts and plan rather than rerunning semantic flow or initialization analysis.

## Specification Impact

Specification Impact: none. The implementation fills the Phase-12 IR contract already defined by `language-core.md`, `backend-runtime.md`, and the original plan; it does not add source syntax, runtime behavior, JVM emission, CLI behavior, or a public backend SPI.

## Risks

`TypedIr.semanticGraph()` remains as a deprecated compatibility view for existing negative-validator construction helpers; validated consumers must use the sealing gate and the complete IR metadata. The IR retains the immutable producer-owned flow facts as internal provenance metadata, not as a serialized artifact format.

## Follow-up Items

Phase 13 runtime and ABI work is intentionally not included in this change.
