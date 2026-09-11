# Phase 11 Semantic Flow Closeout

## Date

2026-09-02

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Completed the Phase 11 semantic-flow restructuring and scoped Gate 11E closeout. The implementation now uses shared immutable flow algebra, canonical typed callable/eager flow analysis, facts-only initialization planning, producer-certified flow provenance, exact source-site/route/capture/effect evidence, and one package-owned typed-graph sealing path. The resolver retains only a bounded source-local ownership projection and now defers all callable/capture/summary/effect transfer to canonical flow.

## Files

The implementation spans the existing Phase 11 semantic and flow sources under `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/`, `.../semantic/flow/`, and `.../identity/`, plus focused semantic/sealing tests and internal decision/knowledge/changelog records. Notable additions and repairs include `FlowSiteId`, producer certification, `ResolvedReferenceTopology`, `ResolvedTopologyValidator`, route-specific `NilProvenance`, exact import-edge spans, callable parameter/computed-declaration transfer, bounded resolver call authority, and complete `NamespaceDirectCall` argument collection.

## Behavioral Impact

Preserved source-language behavior and diagnostic contracts while fixing false ownership rejection for predicate-bound callable calls, preserving caller-dependent and computed function-value identities, rejecting malformed namespace-call collection paths with structured diagnostics, and retaining exact aggregate ownership, capture, import/export, scope, route, effect, and initialization behavior. Semantic facts remain deeply immutable, deterministic, JVM-independent, internal, and nonserialized.

## Specification Impact

Phase 11 internal architecture and the owner-approved bounded resolver projection are recorded in `specifications/decisions.md`. The owner explicitly deferred the resolver-issued producer certificate for reconstructed same-package topology as defense-in-depth; package-private compiler code remains the trusted boundary and source-derived topology validation remains mandatory. No source-visible language or Phase 12/backend specification was changed.

## Risks

The deferred same-package topology certificate means a package-private compiler caller could still construct a mutually consistent topology not issued by the canonical resolver. This is not an external API or hostile-code boundary and is an accepted owner decision, but it remains a defense-in-depth limitation. Canonical flow producer correctness remains covered by the Phase 11 semantic matrix.

## Validation

- Independent scoped Phase 11/Gate 11E verdict: `PASS (scoped with accepted certificate exception)`.
- Targeted resolver adapter verdict: `PASS`.
- Targeted namespace-direct-call collection verdict: `PASS`.
- `mvn -pl lyra-compiler clean test`: 283 tests passed.
- `mvn clean verify`: four-module reactor passed.
- `git diff --check`: passed.

## Follow-up Items

- Return to the original 24-phase implementation plan at Phase 12: complete and seal the typed IR.
- Do not treat the deferred topology certificate as satisfied by the original unmodified Gate 11E wording.
- Keep Phase 12 out of the current Phase 11 closeout record until its own scoped plan and validation are active.
