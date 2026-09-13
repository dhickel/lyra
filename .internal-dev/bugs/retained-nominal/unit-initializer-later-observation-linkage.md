# Unit-typed retained nominal member observed a later generation fails runtime linkage

## Summary

A nominal declared in one session generation whose member initializer is Unit-typed and performs an effect (for example an intrinsic print or an iterator/while loop) cannot be observed through a retained object binding two generations later. The observation fails at runtime with `LYR-LINK: nominal object belongs to an unrelated artifact or session`, even though construction in the next generation succeeds.

## Scope

- Session/REPL retained nominal path: `lyra-repl` session compilation plus `lyra-runtime` nominal object linkage.
- Reproduces for member types `Unit` and `Fn<String;Unit>` initialized by an intrinsic namespace call (`std->io`), by an `iter`/`while` loop, and by a namespace member access.
- Only affects observation of a retained object whose nominal member is Unit-typed (or whose initializer imports an intrinsic module); members with data results are unaffected.
- Scope of impact: cross-generation reads of the object or its members, not same-generation reads.

## Reproduction

```text
generation 1 (session submit):
    import std->io
    class Box { let @pub value :Unit = io->::println["probe"] }

generation 2:
    let box :Box = Box[]

generation 3:
    box:.value
```

The same shape reproduces with `let @pub value :Fn<String;Unit> = io->:.println` and with `::iter[...]` / `::while[...]` Unit initializers. The four variants are captured as the `retainedUnitInitializerInventoryConstructsAndEvaluatesAcrossGenerations` dynamic tests in `lyra-repl/src/test/java/io/mindspice/lyra/repl/NominalSessionTest.java`.

Minimal independent probe used during validation: `HeadProbeTest` in a scratch worktree at commit `432121b`.

## Expected

Generation 3 returns the Unit member value and the object remains usable, exactly as it does for data-typed members.

## Actual

Generation 3 returns `EvaluationResult.RuntimeFailure` with `code=LYR-LINK` and summary `nominal object belongs to an unrelated artifact or session`, raised from `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraNominalObject.java:98`.

## Evidence

- The failure reproduces at commit `432121b` (phase 1 of the retained-factory job) in a clean worktree that predates the phase-2 algebra work, with a scratch test that did not exist before. It is therefore a pre-existing defect and not a phase-2 regression.
- It does not reproduce for a nominal with a data-typed member constructed in the same generation sequence, so the retained object linkage itself works for other member shapes.
- Observation always fails on the first cross-generation read; construction and producer compilation succeed, and no compiler diagnostic is emitted.

## Impact

- Blocks a complete retained-nominal coverage matrix: Unit-typed or effect-performing member initializers cannot be observed across generations.
- Not a security or data-integrity issue: the failure is fail-closed (a structured runtime linkage error, no wrong value is returned).

## Status

Open. Discovered while extending the cross-generation initializer inventory in phase 2 of the retained-factory job; explicitly out of that phase's transfer-algebra scope. The four dynamic tests currently pin the observed structured failure so the gap stays visible and the suite stays green.

## Diagnosis update (phase 3)

A one-line relaxation of `SessionStorageDomain.Linkage.sourceLocal` (counting only real source modules and ignoring the intrinsic `lyra:intrinsic/std/io` module) makes the four cases pass, but it is not an acceptable fix: it breaks the intended rule that a graph which imports the intrinsic namespace is not eligible for the session-authentication bridge. `SessionClosureAuthorityTest.sessionAuthenticationIsSeparateFromArtifactIdentityAndExactSignature` asserts exactly that ("imported graph authority is not certified"), and the relaxation makes the import-only and importing graphs authenticate, turning that test red. The relaxation was reverted.

Why the failure happens under the intended rule: a generated nominal instance is owned by the authority of the submission artifact that created it, so a later generation's object read falls through to the session bridge. The bridge requires `sourceLocal` on both linkages, and an importing submission is deliberately not source-local. The correct fix is therefore not to widen `sourceLocal` but to anchor the generated nominal instance's ownership to the session root identity (or to authenticate a same-session cross-generation read through an authority that is independent of the importing-graph rule), while keeping the storage-domain classifier and every existing authority assertion unchanged.

Phase-3 contained-fix assessment also tested a nominal-only same-workspace authority path without changing `sourceLocal`. That path made the three Unit-valued observations succeed, but the namespace-member case then failed at the separately correct callable boundary with `LYR-LINK: function value belongs to an unrelated Lyra artifact or session`. Broadly admitting that closure would erase the distinction pinned by `SessionClosureAuthorityTest`; the experiment was therefore reverted. A complete fix must carry a route-scoped delegation from an authenticated nominal field (or construct the nominal under a session-root authority that also owns its installed field values) while still rejecting the same imported closure when presented directly. That is a cross-boundary authority design, not a contained `lyra-runtime` predicate change.

## Next Action

Design the route-scoped nominal-member/session-root authority path across generated member access and runtime closure authentication. It must distinguish a compiler-certified callable reached through an authenticated nominal field from a raw closure exported by an importing graph, keep `SessionClosureAuthorityTest` green, and flip all four inventory cases together. The phase-3 constructor integration leaves this issue open rather than partially widening authority.
