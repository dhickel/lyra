# Tranche 3 retained nominal validation review

## Verdict

PASS after one focused repair. The uncommitted Tranche 3 retained-nominal work satisfies the exercised issue #7, #8 and #15 criteria. No commit, reset, or unrelated revert was performed.

## Scope

Independently reviewed the uncommitted retained-nominal changes at baseline HEAD `f0ce3ebda841eb46b201789b78714c19a40fa54b`, covering:

- nilable nominal member contract derivation and fail-closed semantic/IR sealing;
- occurrence-scoped callable member routes, exact object/schema/field/signature authority, flattening and selected identity;
- direct retained-member callable summary recovery inside lambda capture, return and invocation;
- session structural delegate loading, AOT/session inventory separation, ABI metadata and fixture consistency;
- propagation, producer/consumer lifecycle, reset/root retirement, private access, raw anti-laundering and source-local exclusion boundaries.

## Findings

- Issue #8 is correctly repaired. `TypedSemanticProvenance` derives the exact resolved nominal member type, including nilability, independently of the published typed expression. `IrValidator` admits retained nil provenance only through exact session certificate evidence. Positive annotation, coalesce, narrowing and match cases pass; nilable receivers, mismatches, forged links and invented narrowing remain structured failures.
- Issue #7 is correctly repaired in the exercised paths. Session-generated delegates are deterministic, occurrence-scoped, single-use and flattened to one selected raw closure plus exact route dependencies. They revalidate object ownership, schema field, signature, producer/session/root lifecycle and caller authority without rereading mutable fields or changing `sourceLocal`.
- Issue #15 is correctly repaired in the exercised paths. Direct non-root callable member projections in lambda summaries are deferred and recovered through certified caller-time object/route resolution. The direct capture, return and invocation cases pass without `MISSING_CALLABLE_FACT`.
- A focused defect was found in `SnapshotReader`: each delegated read creates a fresh wrapper, so identity-map lookup by wrapper object rendered repeated reads of the same selected closure as separate function identities. `SnapshotReader` now compares selected closure identity through the existing non-authorizing `LyraClosureSupport.sameIdentity` helper, while retaining the pre-render route check. `NominalSessionTest` now covers the repeated-delegate snapshot alias.
- ABI metadata is coherent at the exercised boundary: runtime ABI is `1.1`; current metadata emits `1.1`; current runtime accepts `1.0`; `1.0` rejects artifacts requiring `1.1`; schema and language contract versions remain distinct. The current metadata fixture matches compiler output.
- Session loader and planner checks pass for delegate superclass/interface/inventory, exact nominal/function route types, deterministic naming, and two-pass staging. AOT artifacts do not emit delegates; session artifacts do.
- Raw imported closures remain rejected before and after delegated use and when coexisting with routed occurrences. Saved, captured, parameter, returned, aggregate and later-generation values preserve route evidence. Producer close/failure, reset, root close, unrelated root/session and wrong route/signature cases remain rejected.

## Risk Assessment

Focused validation is green, but this review does not claim the broader full-suite, extended fuzz campaign, Phase 23, Phase 24, UI, or release gates because the request restricted validation to explicit `-Dtest` Maven commands and `git diff --check`. Those remain separately unexecuted gates.

The snapshot repair uses bounded linear comparison over already visited function values. Snapshot limits bound traversal and the helper exposes only a boolean identity result; it does not authorize invocation or expose a callable.

## Recommendations

Retain the repaired snapshot alias regression with the tranche. Before final tranche closeout, run the repository-required broader validation under its normal release workflow and preserve the current worktree changes.

## Follow-ups

- No further code defect was identified in the exercised Tranche 3 paths.
- The existing issue/changelog follow-ups still require commit evidence before archival or external issue closure.
- The repair and reusable snapshot identity lesson were recorded in the retained-nominal changelog and knowledge record.
