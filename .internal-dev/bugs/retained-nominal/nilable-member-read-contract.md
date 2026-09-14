# Retained nilable member reads lack an independently derived contract

## Summary

Reading a nilable nominal member across generations with an explicit annotation (`let v :@nil I32 = box:.n`) is rejected at the IR boundary with the structured `LYC-IR-003` diagnostic, and coalesce, narrowing-predicate and match forms over member reads lack an independently derived nilable contract. The same-generation forms behave the same way, so this is a contract-derivation gap rather than a retained-transfer defect.

## Scope

- `lyra-compiler` semantic/IR boundary for nominal member reads whose declared type is nilable.
- Affects reads; construction, retained transfer certification and the bare (unannotated) read path work.

## Reproduction

```text
generation 1: class Box { let @pub @nil n :I32 = #NIL }
generation 2: let box :Box = Box[]
generation 3: let v :@nil I32 = box:.n
```

Observed: `LYC-IR-003` (structured diagnostic, not a compiler-bug exception).

## Expected

The annotated read either succeeds with the member's nilable contract, or the language rejects the annotation form with a source-level diagnostic that names the missing contract derivation.

## Actual

`LYC-IR-003` at the IR boundary. Coalesce, narrowing-predicate and match forms over member reads report the same missing-contract shape, including in a single generation.

## Evidence

Observed while fixing the retained nilable-element array-index crash (`.internal-dev` phase-4 work). The bare read path (`box:.n` without an annotation) works and is covered by the retained nilable-element tests; only the annotated and nil-contract-consuming forms are affected. No compiler-bug exception is raised.

## Impact

- Blocks annotated cross-generation nilable member reads and nil-contract-consuming expression forms over member reads.
- Fail-closed and structured: no wrong value is produced.

## Status

Fixed by Tranche 3 issue-#8 work. `TypedSemanticProvenance.memberType` now derives nominal member-read contracts (including nilability) from the exact resolved schema member, and `IrValidator` accepts producer-generation nil provenance of retained member reads only through `SessionFlowCertificate.certifiesNil`. Same- and cross-generation annotated reads, coalescing, predicate narrowing and `#NIL` value match compile and execute; nilable receivers, mismatched annotations, forged member links and invented match narrowing remain structured failures, and no valid case reaches `LYC-IR-003`. Covered by `NilableMemberReadContractTest`, `NominalBytecodeTest.nilableMemberReadContractsCompileAndExecuteInOneGeneration`, `NominalSessionTest` cross-generation positives/negatives, and the retained fuzz model's `alternatives` profile nilable member-read operations.

## Next Action

Archive this report under the workflow contract once the accompanying changelog and validation evidence are committed.
