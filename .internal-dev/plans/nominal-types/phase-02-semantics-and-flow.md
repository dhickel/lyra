# Phase 2 — Member semantics, initialization and flow

## Context

Nominal declarations need lexical privacy, definite initialization and mutable
field/callable provenance in the same sealed semantic pipeline as existing values.

## Goal

Prove exact contracts, access, initialization, aliases and callable effects before IR.

## In Scope

Member resolution/types; constructor context; implicit self captures; replaceable
methods; struct data restrictions; object allocation and field-sensitive flow;
ordinary/imported mutation permission; recursive nominal schemas.

## Out of Scope

Effect annotations, polymorphism, interfaces, automatic receiver retargeting,
relaxing ownership or accepting arbitrary unknown callables to satisfy summaries.

## Implementation Steps

1. Predeclare type/member signatures; preserve one type/value namespace, module
   visibility and exact public contracts. Validate recursively data-only structs.
2. Add lexical declaring-class authority independently of contextual self. External
   replacement lambdas receive receiver context only; existing bound functions
   retain original capture identity on assignment to another member slot.
3. Add constructor definite-assignment transfer/join states. Track initializers,
   immutable single initialization, reads and incomplete receiver escape through
   aggregates/closures. Unexecuted loop bodies prove no initialization.
4. Model allocation/field routes, selected callable identities and self captures in
   flow certification, including method writes, recursion, aliases and loops.
5. Extend semantic sealing, initialization scheduling and diagnostics. No emitter
   inference or missing-summary fallback may repair incomplete semantic metadata.

## Validation

Assert private access fails outside the class (including replacement lambda bodies),
private access works inside it, immutable receivers can invoke mutating methods,
direct writes require correct root/member authority, replacements preserve exact
Fn types, and method extraction cannot add authority. Exercise initialization joins,
zero-trip loops, duplicate immutable initialization and escaped incomplete closures.
Use independent state models for field aliases and repeated callback effects.

## Exit Criteria

All accepted operations have sealed typed/flow records, negatives have source-linked
diagnostics, and the full suite plus extended semantic fuzz pass. Commit evidence;
do not label runtime support complete at this boundary.
