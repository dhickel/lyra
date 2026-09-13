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

Open. Out of scope for the phase-4 campaign work that discovered it; not caused by the retained transfer algebra, which now transfers and certifies nilable member values correctly.

## Next Action

Derive the nilable member contract at the IR/flow boundary for member reads and document the annotated-read form in the language contract; add positive and negative coverage in the nominal semantic and session suites once the derivation exists.
