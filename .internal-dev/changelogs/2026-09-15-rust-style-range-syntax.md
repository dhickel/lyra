# Rust-style inclusive range syntax

## Date

2026-09-15

## Git Commit

f6e48839860cb2625904e4570db1a9b732c375f8

## Change Summary

Changed inclusive range syntax from `...` to Rust-style `..=` while retaining `..` for exclusive ranges. Updated lexing, semantic and IR metadata, session certificates, snapshots, fuzz generation, tests, specifications, and user documentation. The obsolete `...` spelling is rejected.

## Files

- `lyra-compiler/src/main/java/`
- `lyra-compiler/src/test/java/`
- `lyra-repl/src/main/java/`
- `lyra-repl/src/test/java/`
- `docs/`
- `.internal-dev/specifications/`
- `.internal-dev/knowledge/`

## Behavioral Impact

Source must use `(start..end:step)` for exclusive ranges or `(start..=end:step)` for inclusive ranges. Snapshot text uses the same canonical markers. Range traversal semantics are unchanged.

## Specification Impact

Updated the language-core and REPL specifications, grammar resource, migration references, and durable range decision to define `..=` as the inclusive marker.

## Risks

The former `...` spelling is intentionally incompatible and may require source migration. Full Maven tests passed on the available Java 25 runtime; two graphical editor tests remain skipped when no display is available.

## Follow-up Items

The Phase 24 release audit and graphical/native editor release validation remain separate release-gate work.
