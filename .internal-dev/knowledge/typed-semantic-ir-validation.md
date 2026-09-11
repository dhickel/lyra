# Typed Semantic and IR Validation

## Topic

Reusable invariants for Lyra's bidirectional type checker and closed typed IR validator.

## Source References

- `.internal-dev/specifications/language-core.md`
- `.internal-dev/specifications/backend-runtime.md`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypeChecker.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java`
- `lyra-compiler/src/test/java/TypeCheckerTest.java`

## Key Takeaways

- `#NIL` obtains its base type only from an expected nilable contract. It may satisfy `@nil`, but must be typed without `@mut` so ordinary coercion rejects any attempt to invent mutation permission.
- Equality containing `#NIL` must infer one compatible type from every non-nil operand before applying the nil qualifier. Exact literals still participate contextually, so the first operand must not determine the result alone.
- Every implicit IR conversion node represents exactly one adjacent `TypeRules` step. A multi-step decision must lower as nested conversion nodes in decision order.
- Explicit numeric/text conversions must match their category exactly. Binding-local `@mut` is dropped on a preceding implicit edge rather than hidden inside the explicit conversion.
- Expected numeric result types must recurse through nested numeric operations using ordinary contextual literal typing and legal conversion edges. They must never retag a fixed nonliteral type to simulate narrowing; provenance must independently reconstruct the same contextual common type.
- Parent common-type selection may widen an already synthesized nonliteral operand or inferred conditional branch without contextually rechecking its source subtree. Provenance must validate that unwrapped subtree without an invented expected type and validate the explicit widening edge separately; only externally checked numeric operations and direct numeric or nil literals receive the contextual type.
- An inferred declaration contract is not an expected initializer context. Provenance must independently synthesize the initializer without context, drop only outer mutation permission, preserve or source-add outer nilability, and require that exact normalized type and binding mutability. A root numeric widening requires an explicit declared contract; an explicit source conversion remains part of context-free synthesis rather than an implicit authorization.
- Integer division's floating result type is operator-defined, not an operand context. Its integer operands must first select one canonical common integer type, retain explicit widening edges, and undergo exact constant zero checks before the F32/F64 result rule is applied.
- `and` and `or` are valid only as `IrNode.ShortCircuit`; eager `IrNode.Operator` remains valid for `xor` and other non-short-circuit operators.
- Call/access validation is a matrix over resolved reference kind, access kind, receiver presence, and module/export/declaration links. Checking only one field permits malformed nodes to masquerade as another source form.
- Constant integer operators must be evaluated with `BigInteger` after operand common-typing. Check each left-fold arithmetic step, zero divisors, negative integer exponents, and the final exact power against the selected integer domain; preserving runtime checks does not excuse publishing a known-invalid constant operation.
- A typed graph needs recursive syntax provenance, not just top-level form counts and spans. Freeze only expressions reachable from module roots, require identity-consistent expression/span/conversion indexes, and compare every source node kind, child position, type, resolved link, declaration/lambda ID, and lexical scope before publication.
- A full conditional without an external expected type must re-derive branch synthesis, numeric literal defaults, and canonical unification from source; validating branches against the already-published conditional type is circular and permits coherent type retagging.
- Context-free nil-branch detection must follow a block's final expression (including nested blocks), then apply the established non-nil branch as the expected nilable contract; it must not make a bare #NIL inferable outside that branch unification.
- Predicate-binding identity and truthy-branch scope are insufficient provenance. Its indexed binding contract must be immutable and exactly equal to the predicate type with outer mutation/nil qualifiers removed.
- Closed-IR validation can reconstruct the canonical lowering of each typed root and require exact immutable equality in addition to local semantic checks. This catches contained-but-broadened spans, substituted nodes, and valid-but-wrong scope IDs that containment/existence checks miss.
- `MEMBER_VALUE` is a static type matrix: only non-nil `String`/`Array` `length :I32` and in-shape tuple numeric fields with their exact member type are legal. Namespace value access remains a separate resolved-link shape.
- Function value equality remains identity-only except for value equality against an explicit `#NIL`. In that case every operand must retain one common nilable function contract and the non-nil operand must reach it through the ordinary nil-lift edge.
- Exact flow sealing cannot be reconstructed from compatible types, known spans, or mutually consistent facts/plans. The canonical flow producer must retain an immutable expected fact record bound by private object identity to the exact typed core instance it analyzed; structural `sameContent` equivalence is not certification and permits certificate reuse on clones. Sealing compares exact records and performs only explicit schema/topology audits, never a second semantic walk.
- Compilation-local `FlowSiteId` values distinguish typed expression, reference, and capture sites even when spans coincide. Event, summary-call, callable-creation, effect-path, nil-route, and allocation-origin records must retain those producer-issued links where source identity matters.
- Route-specific nil is part of a `ValueAlternative`, not an event-wide permission. Coalescing removes root-nil alternatives before joining the fallback; nested nil routes remain attached to their exact aggregate member.
- Scope ownership requires reciprocal indexes: each parameter belongs to exactly one lambda scope, each lambda scope names that lambda, each declaration initializer-lambda and lambda owner point to one another exactly once, capture lists and capture-reference links agree in both directions, and mutation roots stay in the mutating module. `ScopeId(0)` may be a legitimate allocated root but must never serve as a missing-owner sentinel.
- A reference's exact scope is the unique innermost lexical scope containing its source site, not any same-module scope with a matching lambda owner. Its exact local target is the declaration selected by walking that scope's ancestors under source-order replacement and function-predeclaration rules. Both decisions are retained in a resolver-owned, object-bound reference-topology authority and checked against declaration, scope, module, capture, and syntax-link indexes before type checking or sealing.
- Mutation authorization is site-specific, not merely target-compatible. The mutation record, source assignment target, root reference syntax link, resolved reference, typed root link, declaration, and typed mutation index must all name the same canonical `ReferenceId`; another reference to the same declaration is not interchangeable.
- Import and re-export sealing starts from source headers plus the graph's canonical logical-module mapping. Header-to-module edges must have the same source/logical/target/path-span multiplicity, where the canonical edge span is exactly `ImportDeclaration.path().span()`; every namespace/selection item must have one exact import binding and local declaration; every public declaration/re-export must have one module export; and each re-export chain must terminate at and retain one exact local origin.
- Capture/reference validation needs both producer-owned exact indexes and an independent lexical derivation. For each source reference, walk from its exact owning lambda toward the target declaration's owning scope under the resolver's self-recursion and module-linked-function exclusions. The resulting chain defines every required capture slot, the direct capture on the reference, ordered direct-reference membership, legal empty transitive captures, the first causative capture span, and immutable-versus-shared-cell mode. Typed references must reproduce the same reciprocal capture index before flow-site certification is trusted. Module/global membership alone cannot prove this topology.
- Source diagnostics that depend on call/capture flow belong to the canonical typed producer, before sealing. Summary-owned ownership requirements retain exact source sites and use ordinary formula substitution; the resolver treats call results as opaque rather than replaying lambda bodies. A temporarily deferred mutable-qualifier type mismatch is restored unchanged unless the canonical producer finds the historically prior imported-ownership violation, and neither path publishes a graph.

## Project Relevance

These invariants keep the typed semantic graph and closed IR conversion-complete, preserve source evaluation behavior, and ensure malformed internal graphs fail with structured `LYC-TYPE` or `LYC-IR` diagnostics before backend work begins.

## Open Questions

Member direct calls remain outside the initial typed subset. When member-bearing types are implemented, add a dedicated resolved reference kind and extend the call-shape matrix rather than reusing local direct-call identities.
