# Domain 6 Source Resolution Repairs

## Date

2026-08-30

## Git Commit

`c02851d4a3a56ac652079724664e7de2224c6295`

## Change Summary

Repaired module-graph discovery so `std->io` resolves to a compiler-owned intrinsic source, URI roots can acquire one or more logical aliases without duplicate nodes, and edge ordering/revision encoding retain path-versus-URI endpoint kinds.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source/IntrinsicModule.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source/ModuleGraphDiscovery.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source/ModuleGraph.java`
- `lyra-compiler/src/test/java/ModuleGraphDiscoveryTest.java`

## Behavioral Impact

Legitimate `std->io` imports now publish a stable intrinsic graph node without querying user resolvers or accepting source-root shadows. Reachable logical names may alias one matching stable/physical source, including an initially unnamed parsed URI root, while identity or byte conflicts remain structured failures. Published graphs expose an immutable complete logical alias map. Edge order and graph revisions now distinguish path and URI endpoint identities even when their text matches.

## Specification Impact

None. The repair implements the existing module identity, intrinsic `std->io`, immutability, and determinism contracts in `language-core.md` and `backend-runtime.md` without changing them.

## Risks

The intrinsic node intentionally contains no exports; later semantic and backend phases remain responsible for pinning and implementing the specified `std->io` interface and lowering.

## Follow-up Items

- Phase 20 must attach the normative `std->io` exports and runtime lowering without replacing the compiler-owned module identity.
