# Canonical unprefixed nominal construction

## Date

2026-09-15

## Git Commit

adbe9987c0780005d0904359de0964e13ab1d077

## Change Summary

Removed the required colon from user-defined nominal construction. `Type[arguments]` and `module->Type[arguments]` are now canonical, while the existing colon-prefixed forms remain accepted for compatibility. Built-in bracket forms and uppercase value indexing remain distinct through semantic resolution.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/grammar/`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/parse/`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/`
- `lyra-compiler/src/test/`
- `lyra-repl/src/test/`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntimeConstants.java`
- `docs/`
- `.internal-dev/specifications/`
- `.internal-dev/knowledge/`
- `tools/phase24-conformance-coverage.tsv`

## Behavioral Impact

User-defined struct and class values can be constructed without a leading colon, including qualified module constructions. `module->:.Type[...]` remains invalid. Nominal factory, initialization, flow, provenance, IR, bytecode, and runtime authority behavior is unchanged.

## Specification Impact

Updated the language-core, REPL, grammar, reference, tutorial, diagnostics, and test-coverage documentation. Added a durable decision amendment and recorded that language contract version 2 remains unchanged because the former spelling is still accepted.

## Risks

Bracket syntax is resolved after parsing so an uppercase value such as `Values[1]` remains indexing. Qualified construction parsing recognizes the declared nominal naming convention to reach semantic resolution. The legacy colon spelling remains in active compatibility coverage.

## Follow-up Items

The Phase 24 release audit and graphical/native editor release validation remain separate release-gate work.
