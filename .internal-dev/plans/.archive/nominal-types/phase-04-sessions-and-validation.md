# Phase 4 — Persistent objects and full qualification

## Context

Full feature support includes persistent nominal declarations and mutable objects,
not just standalone artifact execution. Existing sessions never replay old source.

## Goal

Persist exact nominal schemas, object identities, member state and bound functions
across submissions, and prove the complete requested feature across product surfaces.

## In Scope

Type namespace publication; nominal producer loading; field/callable certificates;
imports/reload/root attachment; bounded snapshots/protocol; editor analysis; failure,
cancellation and lifetime; conformance/fuzz/inventory documentation and final review.

## Out of Scope

Transparent type migration, rebinding old instances to new schemas, live remote
object handles, source replay, private member exposure or Java engine interop.

## Implementation Steps

1. Publish type schemas atomically with successful submission namespaces; retain
   old identities on lexical replacement/reload and preserve real object storage.
2. Extend nominal session loading separately from structural type interning. Retain
   exact object/capture producers and typed member/callable linkage certificates.
3. Support member mutations, methods and loops using prior-generation objects;
   preserve completed writes but publish no staged names on failure/cancellation.
4. Extend bounded snapshots and protocol validation with nominal display/alias/cycle
   data without invoking user methods or leaking private fields.
5. Cover root attachment/import authority, reload/reset/close and editor metadata.
6. Run full conformance, bounded/extended fuzz, artifact/Java/CLI cross-surface
   integration and review the full diff; record evidence and remaining release gates.

## Validation

At least three submissions must construct, mutate/replace a method, and invoke both
saved/current references on the original instance. Test same-shaped/different nominal
types, failed constructor publication, cyclic snapshots, private fields, producer
lifetime and old instances after reload. Protocol tests reject malformed nominal
schemas/references. Preserve all existing scalar/aggregate/callable session models.

## Exit Criteria

All four phases and required tests pass; no valid nominal source reaches an
unsupported backend path; coverage and review artifacts are committed. Archive the
plan only then. Run `tools/phase24-release-audit.sh` before a release claim; ordinary
feature tests alone do not replace that release gate or graphical qualification.

## Completion

Implemented 2026-09-12. Exact type/schema names, object heap state, nested callable
proofs and original factories persist across standalone and attached session
generations. Nominal JVM classes share the identity-safe session type domain;
generation-local state and executable capabilities remain producer-retained.
Snapshots distinguish struct/class data and exclude private class members.
