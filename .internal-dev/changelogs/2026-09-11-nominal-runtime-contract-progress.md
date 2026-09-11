# Nominal runtime contract and ABI progress

## Date

2026-09-11

## Git Commit

`502b05719c3a71705892fea56f478b84c9ffc162` (baseline).

## Change Summary

Added independent runtime nominal identities, reference types, ordered schemas and
closed recursive schema environments. Added explicit schema-aware type/signature
parsing; schema-free parsing still rejects nominal types. Added exact nominal JVM
descriptor mapping, a distinct generated class family and compiler/runtime parity
assertions. No object bytecode or artifact encoding is claimed by these contracts.

## Files

Runtime type/schema/parser contracts and tests; JVM ABI mapper, type/name/physical
plans and parity tests; extended fuzz selection, coverage and development records.

## Behavioral Impact

Runtime callers can represent and validate exact recursive nominal contracts without
a compiler dependency. Unknown origins, duplicate schemas, malformed hashes,
recursive function-bearing struct data and incorrect constructor signatures reject.
Nominal descriptor plans preserve the full source declaration digest, including in
nilable and array positions. Existing schema-free nonnominal parsing is unchanged;
its empty nominal environment is shared to avoid per-parse environment allocation.

## Specification Impact

None: the accepted explicit schema environment and exact nominal JVM representation
contracts are partially implemented. Artifact schema encoding remains unchanged.

## Validation

Focused runtime contract and compiler/runtime ABI mapping tests, extended
`tools/fuzz-language.sh -q`, and final ordinary `mvn -q test` passed. The final
ordinary run includes the final fixed-hash/forged-descriptor assertions and shared
empty-environment optimization. Two recursive-schema seeds ran 1,800 cases each
in the extended campaign alongside existing compiler/runtime fuzz. `git diff
--check` passed. No emitted-object, persistence or release-audit claim is made.

## Risks

This is an unfinished implementation unit. Descriptor mapping is not class emission.
Metadata encoding/versioning, generated object factories/accessors and ownership,
object equality and session loading remain required. Existing unresolved semantic
work also remains tracked in the active plan. No release audit is claimed.

## Follow-up Items

Complete exact schema publication and object class/member plans, then emit/invoke
source-defined instances through the JVM/runtime and complete the semantic/session
gates. Commit validated work and continue; do not call this full finalization.
