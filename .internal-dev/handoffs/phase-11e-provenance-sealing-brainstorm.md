# Phase 11E Provenance and Graph-Sealing Brainstorm Handoff

## Context

Lyra is implementing Phase 11 of the semantic-flow restructuring plan. Gates 11-0 through 11D.2 have passed, including the canonical `SemanticFlowAnalyzer`, immutable `SemanticFlowFacts`, callable-summary transfer/SCC solving, eager-effect analysis, and facts-only initialization planning. Phase 12 has not started.

Gate 11E is intended to publish one complete, immutable `TypedSemanticGraph` containing a package-owned `TypedSemanticCore`, canonical `SemanticFlowFacts`, and a validated `InitializationPlan`. The ordinary compiler suite is green (`mvn -pl lyra-compiler clean test`: 229 tests), the reactor verification is green (`mvn clean verify`), and `git diff --check` is clean. The remaining problem was found by independent adversarial validators, not by ordinary compilation or positive-path tests.

The current sealer/validator can reject many forged artifacts, but repeated independent validation rounds still found cases where a forged fact has a compatible type, existing ID, or known source span yet is not the fact produced by the corresponding typed source site. This has become a possible architecture/provenance-boundary problem rather than a sequence of isolated missing checks.

## Objective

Use a later brainstorming session to determine the smallest sound architecture that satisfies the existing Gate 11E acceptance criteria without beginning Phase 12. The session must decide whether exact provenance can be proved from the current graph/fact model, or whether the fact schema and its producers must be extended before sealing can be accepted.

The desired outcome is an accepted architectural direction or an explicitly revised Phase 11 gate, followed by a bounded implementation plan. Do not treat another round of ad hoc validator predicates as a solution unless it is backed by a complete provenance invariant.

## Settled Decisions

- `SemanticFlowAnalyzer` is the canonical owner of semantic flow/eager evaluation facts.
- `InitializationAnalyzer` is facts-only planning logic; it must not reconstruct flow or invoke the canonical evaluator.
- There is one package-owned publication path: `TypedSemanticGraph.seal(TypedSemanticCore, SemanticFlowFacts, InitializationPlan)`.
- The published facts and plan must be deeply immutable, JVM-independent, compiler-internal, non-serialized, and included in graph equality/hash behavior as specified.
- Sealing must not rerun `SemanticFlowAnalyzer`, publish bootstrap/empty-plan artifacts, or expose a broad public graph reconstruction API.
- Expected source errors must not publish a partial loadable artifact.
- Phase 12 typed-IR/backend/runtime/CLI work remains out of scope.
- The current Gate 11E acceptance language requires complete exact event/summary/site/ID/span/route/capture/dependency coverage and rejection of missing, foreign, wrong, or forged data. That criterion has not been relaxed.

## Constraints

- Preserve unrelated dirty, deleted, and untracked worktree content.
- Do not silently weaken the acceptance criteria or change language semantics, evaluation order, ownership rules, nil behavior, or module visibility.
- Avoid cross-build/persistent flow IDs, serialization formats, stable external flow APIs, theorem proving, or Phase 12 IR redesign.
- Any change to a completed producer gate, especially `SemanticFlowAnalyzer`, must be justified as a provenance correction and checked for regression against Gates 11A–11D.2.
- The previous repair attempts were restricted primarily to the sealer, validator, graph topology, and sealing tests. That restriction may itself be part of the problem and requires an explicit decision.
- Use independent read-only validation after an architectural repair; ordinary green tests are necessary but not sufficient.

## Scope

### Current implementation boundary

Relevant production files include:

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowFactValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticGraph.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticCore.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ResolvedSemanticGraph.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/ScopeTree.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/SemanticFlowFacts.java`
- Related flow records under `.../semantic/flow/`, including `ValueAlternative`, `ValueFormula`, `CallableSummary`, `SemanticFlowEvent`, `EagerEffectFact`, `EagerEffectWitness`, `OwnershipWitness`, routes, and capture records.

Relevant tests include:

- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/semantic/Domain11SealingTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/semantic/SemanticTestSupport.java`
- Existing semantic, callable-summary, initialization-flow, ownership, and contextual-typing suites.

### Gate 11E requirement in conflict

The active plan states that sealing tests must prove facts are immutable; every relevant declaration/lambda/call/mutation/capture/effect site is covered; missing/foreign/wrong route, summary, scope, span, conversion, dependency, or schedule data is rejected; and valid repeated compilation produces equal facts and graph output. It also requires a complete final graph, no duplicate canonical evaluator runs, and no obsolete parallel semantic models.

The implementation currently stores compact boundary events, summaries, eager-effect facts/witnesses, cycles, declaration value alternatives, and normalized expressions. The validator then indexes typed expressions, scopes, IDs, and spans and attempts to prove that each compact fact corresponds exactly to the typed topology.

## Problem Evidence

The following findings came from independent validation rounds. Line ranges are the locations reported against the latest repair and should be rechecked after any edits.

| Finding | Reported location | Adversarial case accepted | Why it matters |
|---|---|---|---|
| Route-specific nil provenance | `SemanticFlowFactValidator.java:1493-1542` | An identityless array/function route is accepted when an unrelated sibling alternative contains `#NIL`. | Nil permission is being inferred at the event/value-set level instead of proved for the specific route. |
| Callable summary/result identity | `SemanticFlowFactValidator.java:1380-1453`, `2051-2078` | Same-typed callable targets/results and nested capture snapshots can be substituted in a summary. | Type/signature compatibility does not establish that the fact came from the exact lambda, call site, route, or capture event. |
| Eager effect completeness/provenance | `SemanticFlowFactValidator.java:762-805`, `1082-1220` | Missing effects, forged callable effect targets, and duplicate/extra witness paths can be made mutually consistent by forging the event/plan data. | The planner can accept a false dependency because the sealer has no authoritative one-to-one effect-site proof. |
| Allocation/use-site witness | `SemanticFlowFactValidator.java:1569-1619`, `1864-1912` | A local aggregate witness uses a later alias-reference span instead of the canonical allocation site. | A globally known span is not an allocation origin or ownership proof. |
| Scope and graph ownership | `ResolvedSemanticGraph.java:193-313`, `ScopeTree.java:54-91` | A lambda parameter can be indexed as belonging to another lambda; inconsistent ownership/index relationships survive basic root/reachability checks. | Existence, reachability, and link shape do not prove exact owner/module/capture/mutation membership. |

Earlier rounds found the same pattern in simpler form:

- Ownership witnesses accepted an unrelated but globally known mutation span.
- A declaration could be supplied a same-signature sibling lambda identity.
- Type/capture shape checks accepted facts whose source identity was wrong.
- Cross-module scope checks verified that a scope existed but not that it was the canonical scope for the site.

The second repair round added stricter source-site and identity checks, expanded sealing tests from 7 to 12, and kept all 229 compiler tests green. The subsequent independent validator still found the deeper cases above. The current senior repair also reported a bounded `ScopeId(0)` sentinel for one synthetic transfer path; the validator accepts it only for a narrow synthetic shape, but replacing it with the actual owner scope would require revisiting the producer path.

## Failed or Insufficient Resolution Attempts

1. **Global known-span validation.** The validator collected spans from typed expressions, declarations, references, lambdas, captures, mutations, and modules and accepted a witness when its span was present. This rejects unknown spans but cannot distinguish an allocation span from an unrelated reference or mutation span.
2. **Type and shape compatibility.** Callable signatures, capture keys/types, aggregate types, routes, and formulas were compared structurally. Same-signature lambdas and same-shaped aggregates remain substitutable without canonical source identity.
3. **Derived identity/index reconstruction in the sealer.** The validator derives array allocation IDs from expression ordering and synthetic summary allocations from sorted sites. This creates a second identity-allocation mechanism after the canonical producer and risks disagreement or acceptance of forged but well-shaped data.
4. **Event-wide nil allowance.** Allowing an identityless alternative when any event-level value contained `#NIL` reduced false rejections but allowed an unrelated sibling to authorize a route that had no nil provenance.
5. **Mutual consistency of forged effects and plans.** Checking that effects, events, and initialization plans agree with each other is insufficient when all three are forged consistently. There must be an authoritative source-derived expected effect set or producer-issued proof link.
6. **Existence/reachability scope checks.** Root, cycle, parent/child, and module reachability checks caught basic malformed forests but not cross-index owner mismatches such as a lambda parameter belonging to a different lambda.
7. **Adding more local validator predicates.** This has produced green ordinary tests and better rejection coverage, but each independent review has found another relation that cannot be established from the available fields. Continuing this approach risks a brittle validator that approximates a semantic interpreter and may reject valid flow cases.

## Architectural Diagnosis to Explore

The likely fault line is the boundary between a compact flow artifact and a post-hoc sealer. `TypedSemanticCore` preserves the typed graph, while `SemanticFlowFacts` preserves compact values/events/summaries/effects. However, exact proof edges are not uniformly explicit for every alternative and event. The sealer therefore reconstructs relationships from types, IDs, routes, source spans, expression traversal order, and set membership.

This creates three distinct identity problems:

1. **Value identity:** which exact allocation, lambda, declaration, nil source, or call result produced an alternative at a particular route?
2. **Event identity:** which exact typed expression and owner produced this declaration/mutation/capture/call/effect event, and what is its canonical ordinal/site key?
3. **Topology identity:** which exact module, scope, lambda, parameter, capture, mutation, import/export, and reference indexes own one another?

A sound solution likely needs producer-owned, immutable provenance links or canonical site keys in the facts and/or typed graph, rather than allowing the sealer to infer those links from broad sets. The brainstorming session should determine the minimum representation and which phase owns creation and validation of each link.

## Recommended Direction

Treat this as a provenance-model and phase-boundary review before authorizing further validator repairs.

The session should evaluate, at minimum:

- A canonical source-site identity model distinct from raw spans, with deterministic per-owner ordinals where spans can collide.
- Producer-attached origin links for value alternatives, aggregate routes, callable values, call results, capture snapshots, mutation writes, eager effects, and initialization dependencies.
- Route-specific nil provenance rather than event-wide or sibling-wide inference.
- One-to-one expected/actual coverage maps generated from the typed topology and consumed by the sealer.
- Exact callable target/result/capture bindings for summaries, including higher-order and recursive cases.
- Exact effect-site, target, reference, route, and path provenance, with explicit duplicate/extra/missing detection.
- A single authority for scope/module/lambda ownership indexes and a complete topology invariant.
- Elimination or formalization of synthetic sentinels such as `ScopeId(0)`; determine whether they are valid bounded internal identities or evidence of missing producer ownership.
- Whether facts should carry proof links directly, whether the typed core should carry canonical site registries, or whether a separate package-private provenance registry should be created and frozen with the core.
- Whether `SemanticFlowAnalyzer` must be amended to emit these links, and if so whether that is a correction to Gate 11D.2, an extension of Gate 11E, or a new preliminary gate.
- A rejection strategy that does not rerun the evaluator during sealing and does not retain a second semantic walker as a fallback.

Possible architectural options to compare rather than pre-accept:

### Option A: Extend flow facts with canonical producer provenance

Add immutable origin/site IDs and exact relation fields to the fact records. `SemanticFlowAnalyzer` emits them; the sealer checks bijection against the typed topology. This preserves compact facts but may require reopening the completed producer gate.

### Option B: Freeze a package-private provenance registry alongside the typed core

Build a canonical registry during typing/resolution, pass it into flow analysis and sealing, and ensure every fact references registry entries. This centralizes topology ownership but adds another internal artifact and must avoid becoming a parallel semantic model.

### Option C: Make the sealer consume source-derived expected records from the producer

Have the canonical analyzer publish complete expected site/effect/identity indexes in addition to compact facts. The sealer compares supplied facts against those indexes without interpreting semantics. This may be the narrowest change if the analyzer already knows the relationships, but it expands the definition of the canonical fact artifact.

### Option D: Revise the gate boundary or acceptance criteria

Split provenance foundation from graph sealing, or explicitly limit 11E to structural immutability and topology while moving exact adversarial provenance to a new gate. This is only acceptable if the project owner approves the change because it weakens or moves an existing criterion.

## Validation

Any selected direction should require:

- Focused adversarial tests for every finding above, including same-signature sibling lambdas, route-swapped tuples, route-specific nil, forged/missing/duplicate/extra effects, wrong allocation/use spans, nested capture substitutions, and cross-owner scope indexes.
- Positive coverage for exact/wildcard route depths, local/imported aliases, tuple-contained shared arrays, whole/selected/unknown replacement, branch/coalesce joins, captures, known/higher-order calls, recursive summaries, and eager cycles.
- Proof that `SemanticFlowAnalyzer` runs exactly once, `InitializationAnalyzer` consumes facts only, and sealing does not reconstruct or rerun semantic flow.
- Deep immutability, deterministic equality/hash, repeated-compilation equality, and no partial publication on rejection.
- `mvn -pl lyra-compiler clean test` and `mvn clean verify`.
- Independent read-only Gate 11E validation with a PASS verdict after the architecture is implemented.
- Final diff review for Phase 12 leakage, duplicate walkers, generated/synthetic identity shortcuts, placeholder logic, accidental public APIs, and unrelated worktree changes.

## Open Questions

1. Does the current Gate 11E plan authorize changing `SemanticFlowAnalyzer` and the flow record schemas, or was 11E intended to be sealer-only after 11D.2?
2. What is the minimal canonical identity for a value alternative: declaration/lambda/call site plus route, or a dedicated immutable flow-site ID?
3. Can raw `SourceSpan` ever be authoritative, given nested expressions and repeated/same-span source constructs, or must every compact fact use a per-owner site ordinal/ID?
4. How is route-specific nil provenance represented so an unrelated sibling cannot authorize an identityless array/function alternative?
5. What exact producer relation binds a summary call to its target, arguments, result, capture snapshot, and source call site across higher-order and recursive calls?
6. What is the authoritative expected set for eager effects, and how are duplicate/extra path segments rejected without evaluating again?
7. Which component owns scope/module/lambda/parameter/capture/mutation/reference membership, and how is that ownership frozen and cross-checked once rather than reconstructed in multiple places?
8. Is `ScopeId(0)` a legitimate documented sentinel for synthetic transfer facts? If so, what exact invariant limits it; if not, which producer should issue the real owner scope?
9. Should validation compare complete canonical records for equality, or validate a set of explicit proof edges with per-field invariants?
10. If the existing criteria cannot be met without producer/schema changes, should Phase 11E be revised, split, or paused pending a new architecture decision?
11. Which new durable specification/decision should record the chosen provenance architecture before implementation resumes?

## Owner Decision After Handoff

The owner elected to defer the resolver-issued producer-certificate requirement for reconstructed same-package topology as defense-in-depth. This is not treated as an external security boundary or a current source-language behavior failure. The remaining source-derived lexical, scope, capture, import, export, reference, and mutation validation remains required. The exception is recorded in `specifications/decisions.md`; it must not be silently presented as satisfaction of the original producer-certificate criterion.

The owner separately required the bounded resolver projection/call-authority path to be corrected. The predicate-bound callable and namespace-direct-call collection repairs were implemented and independently validated.

## Recommended Next Step

Complete the final scoped Phase-11 integration validation with the deferred same-package topology certificate explicitly recorded as an exception. If the scoped gate passes, close Phase 11 with a changelog/review record and return to the original 24-phase plan at Phase 12. No Phase 12 work should begin until that final scoped Phase-11 validation is complete.
