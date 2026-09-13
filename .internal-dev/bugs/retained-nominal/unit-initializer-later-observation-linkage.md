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

## Next Action

Diagnose the runtime artifact/session linkage used by retained nominal objects whose member initializer imports an intrinsic module or produces Unit, in `LyraNominalObject` and the session artifact registration path; fix, then flip the four pinned inventory cases to success assertions.
