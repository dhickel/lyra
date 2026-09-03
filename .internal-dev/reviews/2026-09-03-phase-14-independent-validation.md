# Phase 14 Independent JVM ABI and Generated-Type Validation Review

## Scope

Independent adversarial validation and repair of Phase 14 only: immutable Lyra-to-JVM ABI mapping, descriptor/signature parity, Java export naming, generated tuple/function/closure/cell/module/facade type planning, explicit generated-class dependencies, and deterministic class ordering. No bytecode emission, runtime implementation, CLI work, or Phase-15 changes were introduced.

## Findings

- ABI plans now validate canonical Lyra type structure, qualifier order/context, exact physical descriptors, JVM parameter-slot limits, Unit return-only `void`, nullable primitive presence/payload versus Java wrapper boundaries, nullable references, arrays, tuples, functions, and generated-reference kinds.
- Binding mutability remains separate from the value type. Signature and member plans reject split storage representations in method descriptors and preserve exact canonical Lyra contracts beside JVM descriptors.
- Export plans now require coherent export/origin identities, callable semantic identities, Java-visible value contexts, deterministic getter/function-getter/setter/invocation names, complete member metadata, and valid local versus re-export provenance.
- Generated class plans validate immutable member metadata, field privacy/finality roles, tuple field/constructor correspondence, cell representation/accessor shape, facade infrastructure shape, reserved-name escaping, explicit generated dependencies, and linkage-only recursive edges.
- The planner now includes closure invocation signature types in generated dependency edges, preventing tuple/function descriptor references from lacking ordering dependencies. Generated names, collision handling, class maps, and predecessor-before-dependent ordering are stable across repeated planning and unordered inputs.
- Cross-module recursive function SCCs now have explicit post-construction module-state import-link members; module-state storage excludes lambda/block locals, selective imports retain live target-state linkage, closures retain module-state ownership, and parity checks cover class inventories, state layouts, import linkage, and mutability.
- Java keyword handling includes Java 25 restricted/contextual names in the mapper-facing name validators. Adversarial tests cover malformed descriptors, forged mappings/materializations, invalid contexts, slot overflow, reserved/colliding names, malformed class/facade shapes, recursive/linkage ordering, unvalidated IR, and deep immutability.

## Risk Assessment

Phase-14 implementation and focused/full compiler behavior are validated. The mapper and planner remain planning-only artifacts; they do not emit class bytes or execute initializers. Existing worktree state contains substantial pre-existing prototype deletions, untracked project/internal-development files, and POM changes; those were preserved. No runtime, CLI, or bytecode-emission source was changed for this review.

Maven Dependency Plugin 3.9.0 now runs against Java 25 class-file major version 69. It reports only known test-scope and empty-CLI dependency-model warnings; `dependency:tree`, `jdeps`, and runtime/compiler dependency boundaries remain clean. These warnings are not production leakage or dependency-resolution failures.

## Recommendations

- Keep the later emitter behind `TypedIr.requireValidated()`, consume the generated type plan as immutable input, and preserve explicit generated dependency edges when adding emission.
- Upgrade or replace dependency analysis tooling only in a build-tooling task; do not lower the Java target or alter Phase-14 ABI behavior to accommodate the analyzer.
- Preserve the Phase-14 boundary: bytecode bodies, runtime lifecycle, CLI behavior, and Java public compiler APIs belong to later phases.

## Follow-ups

- Later emission must implement the planned post-construction module-state import links and consume the validated immutable plans.
- No in-scope implementation follow-up remains.
