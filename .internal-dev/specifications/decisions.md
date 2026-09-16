# Durable Decisions

## 2026-09-11 — While as a reserved callback loop (implementation in progress)

- **Source:** owner requested the design most consistent with iter after discussing
  predicates, Unit actions, mutable captures and final-expression returns.
- **Decision:** `while` is reserved like iter, with both unqualified call spellings.
  Arguments have exact contracts `Fn<;Bool>` and `Fn<;Unit>`; result is Unit.
  Evaluate both callback values once in source order, then invoke the predicate
  before each action, stopping at false. Preserve the final false predicate's
  effects, shared captures, failure propagation and cooperative safe points.
- **Rationale:** no hidden state source, implicit argument, result dropping or
  new return syntax is required. Existing callback signatures provide context
  for compact lambdas and ordinary function values.
- **Tradeoffs:** strict Bool rather than general truthiness keeps this predicate
  contract exact. A flag can govern while termination, but this does not provide
  early exit from iter or nonlocal callback returns.
- **Affected specifications:** language-core.md, deferred-features.md and readable
  grammar; shared callback-effect implementation is tracked in range-iter.
- **Review timing:** before claiming executable loop integration complete.

## 2026-09-10 — First-class range values and callback iteration (implementation in progress)

- **Source:** owner accepted enclosed range syntax and ordinary compact/full
  callback lambdas, requested both callback arities, authorized implementation,
  then renamed the built-in from `each` to `iter`.
- **Decision:** `(start..end:step)` excludes the end; `(start..=end:step)` includes
  a reached end. Range construction is eager once; traversal is reusable and
  synchronous. `iter` takes a range and `Fn<T;Unit>` or `Fn<;Unit>` and returns Unit.
- **Rationale:** range data remains first-class while iteration reuses existing
  function, lambda and closure semantics. The `..=` marker makes inclusive ranges
  visually distinct while matching established Rust notation. Explicit existing
  unary negation avoids a new signed-literal syntax. Safe terminal arithmetic
  avoids an overflowing increment after the final element.
- **Initial scope assumption:** signed integer widths I8–I64, as announced while
  asking the owner about unsigned support. Unsigned descending ranges need a
  separate signed-step contract.
- **Name decision resolved:** the owner subsequently confirmed iter is reserved;
  see the 2026-09-10 reserved-iter decision below.
- **Affected specifications:** language-core.md, backend-runtime.md,
  deferred-features.md and the human-readable grammar resource.
- **Validation status:** range construction has Java execution tests. Full iter
  behavior, repeated callback flow and persistence remain incomplete; see the
  active range-iter plan before treating this decision as implemented.

## 2026-09-11 — Commit every repository work unit

- **Source:** owner request to commit the match implementation and all pending work, and to require commits for every subsequent phase/unit of repository work.
- **Decision:** completed code, tests, documentation, and development-record work must be validated, reviewed, and committed before proceeding or reporting completion. Explicit unfinished checkpoints are committed with accurate WIP/checkpoint status rather than represented as completed work.
- **Scope:** stage the current work unit; unrelated changes require explicit inclusion authority. Secrets and generated build output remain excluded. Committing does not authorize pushing.
- **Justification:** make completed progress durable and avoid accumulated uncommitted phases.
- **Affected contract:** repository `AGENTS.md`, Git Commit Policy. Language semantics and generated runtime ABI are unchanged.
- **Review timing:** revisit only on an explicit owner workflow change.

## 2026-09-10 — Value and conditional match expressions

- **Source:** project-owner match design and implementation request, with interactive decisions on pattern expressions, fallback, bindings, and reserved keywords.
- **Decision:** support `(match subject ?? pattern [when guard] -> result ... ?? _ -> fallback)` and equivalent `::match[...]`. `match _` selects condition-only arms using existing truthiness and no `when`. Subject evaluates once; first successful arm wins; unreached patterns/guards/results do not execute. Traditional patterns are arbitrary expressions using typed `==`. Every form requires a final unguarded wildcard.
- **Justification:** one expression form supports both value dispatch and if/else-if chains without introducing runtime polymorphism, an `Any` value, or another conditional keyword.
- **Alternatives rejected:** literals-only matching, Bool-specific exhaustiveness inference, subjectless conditional syntax, bare fallback expressions, and replacing the direct-call-style spelling with `match[...]`.
- **Binding scope:** the owner considered a non-nil binding then explicitly deferred it. No new bindings, destructuring/type patterns, or implicit narrowing are included.
- **Compatibility:** `match` and `when` become reserved keywords by explicit owner choice; `_` remains contextual. Existing code using the newly reserved words as identifiers must be renamed.
- **Performance:** separate typed conditional/value paths may use proven safe specialized dispatch, but may not change effects, equality, laziness, source maps, or tail-call behavior. No optimizer configuration or runtime match framework.
- **Affected specifications:** `language-core.md`, `backend-runtime.md`, `deferred-features.md`.
- **Review timing:** revisit advanced bindings/patterns only in a separately authorized language-design change; validate present behavior through syntax, JVM, flow/provenance, and fuzz tests.

## 2026-08-30 — Current Language Contract

### Source and review

- **Source:** project-owner interactive language-specification session following the 2026-08-30 Lyra brainstorm and red-team review.
- **Affected specification:** `language-core.md`.
- **Review timing:** revisit a decision only when implementing its slice reveals a contradiction, or when a separately approved user-type/backend specification requires a source-visible change.

### Static core and explicit function contracts

- **Decision:** Lyra is statically typed with local value inference. Every lambda has a complete `Fn` signature from context or complete inline annotations. Public contracts are explicit. There is no dynamic/`Any` escape.
- **Justification:** predictable compilation and host calls while retaining concise local declarations.
- **Alternatives rejected:** gradual typing, dynamic typing with hints, unconstrained lambda inference.
- **Caveat:** function values are identity-bearing; each lambda evaluation creates a distinct identity. User-defined types and host type mappings remain separate work.

### Binding-centered modifiers

- **Decision:** current modifiers are `@pub`, `@mut`, and `@nil`. Bindings are immutable by default. `@mut` is a binding-local permission and captured mutable bindings use one shared cell. `@nil T` admits `T` or `#NIL` and requires explicit narrowing/coalescing before use as `T`.
- **Justification:** one modifier mechanism applies consistently to declarations, parameters, returns, and nested contracts while keeping mutation and absence explicit.
- **Alternatives rejected:** mutable-by-default bindings, deep `@const`, `@opt`, `@nilable`, unrestricted nullable values, implicit nil runtime checks.
- **Caveat:** an immutable array reference may be copied into an `@mut` binding; aliases observe mutation. This is permission on a symbol, not deep immutability or ownership.

### Uniform declarations and source-ordered identity

- **Decision:** all values/functions use `let`; there is no `fn` declaration. Private same-scope redeclaration creates a new source-ordered identity, while prior references retain the old binding. Types and values share one namespace. Completely typed lambda lets are signature-predeclared for recursion and forward references; ordinary values are not.
- **Justification:** homogeneous declarations without sacrificing mutual recursion or stable symbol identity.
- **Alternatives rejected:** dedicated function declarations, broad value hoisting, separate type/value namespaces, retroactive replacement.
- **Caveat:** public module names cannot be redeclared.

### Strict expression semantics

- **Decision:** evaluation is eager and left-to-right. Blocks create lexical scopes and return the final expression. Functional style is encouraged but effects are allowed. Conditionals use truthiness, optional true-branch binding, and nil-only coalescing.
- **Justification:** deterministic side-effect and host-call order with expression-oriented composition.
- **Alternatives rejected:** lazy evaluation, unspecified operand order, mandatory purity/effect typing, Bool-only predicates.

### Numeric and operator behavior

- **Decision:** fixed signed/unsigned widths and F32/F64 are core. Unsuffixed literals are contextual then default to I64/F64. Only lossless widening is implicit. Integers are checked; floating operations trap non-finite results. Integer division produces floating point. Core operators support S-expression and bracket forms except assignment, which supports infix and S-expression forms only. `++`/`--` are pure numeric operators.
- **Justification:** exact native/JVM-facing widths and deterministic failure rather than silent wrapping/non-finite propagation.
- **Alternatives rejected:** wrapping arithmetic, Java-like broad conversion, dynamic numeric types, mutating increment/decrement.
- **Caveat:** bitwise operations are deferred.

### Accessor and call model

- **Decision:** retain `->` namespace qualification, `:.` value/bound-method access, and bracketed `::` direct invocation. Callable values also use S-expression calls. Bare `::method` without brackets is invalid.
- **Justification:** preserves Lyra's one-namespace distinction between treating a function/member as a value and promoting it for direct invocation.
- **Alternatives rejected:** conventional dot/call syntax, raw unbound `::method` values, erasing accessor distinctions during parsing.

### Core aggregate values

- **Decision:** current aggregates are fixed-size homogeneous arrays and heterogeneous immutable-field tuples. Arrays have identity and binding-authorized element mutation; both aggregates use structural value equality. Strings are immutable UTF-16 and index to `Char`.
- **Justification:** useful engine-facing data without committing to user-defined object/type semantics.
- **Alternatives rejected:** persistent arrays, growable arrays, heterogeneous dynamic arrays, UTF-16-hidden code-point indexing.
- **Caveat:** bare `Array[]` and `Tuple[]` are Unit; typed empty arrays require `Array<T>[]`. Arrays and strings expose read-only `:.length :I32`, and `String[value]` is the explicit primitive/Unit text conversion needed by the standalone console boundary.

### Modules and visibility

- **Decision:** canonical file/path identity defines modules; logical identifier chains name imports. Imports are header-only, support aliases and explicit selections, and are private unless explicitly re-exported with `import @pub`. Public mutable state is read-only outside its owning module. Declaration-only dependency cycles are allowed; eager value cycles are rejected.
- **Justification:** deterministic static dependency and initialization behavior with explicit APIs.
- **Alternatives rejected:** source module headers, wildcard imports, dynamic imports, public-by-default declarations, direct external mutation.
- **Caveat:** mapping logical chains to canonical files is a later module-loader/backend contract.

### Failures and compatibility

- **Decision:** compilation reports a structured first blocking diagnostic per attempted phase and publishes no partial failed-phase artifact. Runtime faults abort the current invocation; Lyra has no catch syntax. The language currently has no edition/version mechanism, and deferred syntax is an ordinary syntax error.
- **Justification:** small, explicit initial failure model without promising recovery, exception semantics, or multi-edition compatibility prematurely.
- **Alternatives rejected:** rendered-text-only diagnostics, catchable first-edition exceptions, implicit Result values, reserved placeholder AST forms, source edition directives.

## 2026-08-30 — JVM Backend and Standalone Runtime

### Source and review

- **Source:** project-owner interactive backend/internals session following ratification of `language-core.md`.
- **Affected specifications:** `backend-runtime.md`, `language-core.md`, `deferred-features.md`, and repository `AGENTS.md`.
- **Review timing:** revisit only when implementation evidence exposes a contradiction, the Java deployment floor changes, or a later Java-interop/engine specification requires an explicit compatible extension.

### Standalone-first product boundary

- **Decision:** Lyra is a standalone compiled JVM language first, with future Java/game-engine embedding kept in mind. The first backend supports CLI run/compile, reusable classes/JARs, direct typed Java use, and Java runtime compilation. Lyra source calling Java and engine/Vulkan integration are later contracts.
- **Justification:** delivers a coherent language independently of an absent engine while preserving practical JVM integration.
- **Alternatives rejected:** defining Lyra primarily as an engine-only DSL, requiring full engine interop before standalone execution, or omitting a Java consumption path.
- **Caveat:** `AGENTS.md` previously overemphasized embedding as the immediate mission and must now state this ordering clearly.

### Production backend and compiler boundary

- **Decision:** direct Java 25 bytecode emitted with the Java 25 Class-File API is the sole production backend. A closed immutable typed IR separates complete semantic analysis from emission; mutable construction is phase-local. Preview features are permitted and declared in metadata when required.
- **Justification:** matches the JVM mission, avoids source generation and boxed interpretation, and gives one inspectable lowering contract.
- **Alternatives rejected:** interpreter-first shipping, equal production backends, source-to-Java, ASM by default, whole-program MethodHandle graphs, Truffle, and direct AST-to-bytecode coupling.
- **Caveat:** a bounded evaluator may be test-only if it provides useful differential evidence.

### Specialized ABI and generated Java API

- **Decision:** known Lyra scalars use JVM primitives, aggregates/functions use concrete generated types, and boxing occurs only at unavoidable nilable/generic/tooling boundaries. Generated modules are instantiable facades with typed function methods, value getters, and setters only for `@pub @mut` state. Runtime dynamic lookup yields exact MethodHandles; direct Java calls are preferred.
- **Justification:** keeps the common path easy to use from Java and avoids generic invocation overhead.
- **Alternatives rejected:** universal boxed values, `Object...` hot paths, public representation fields, static singleton module state, and result-wrapped every-call ABIs.
- **Caveat:** same-width unsigned values appear to Java as raw primitive bits; nullable primitives box at the Java boundary.

### Whole-graph modules and deterministic artifacts

- **Decision:** one request snapshots/compiles the whole reachable module graph. Outputs are deterministic class directories or thin/bundled JARs, reusable across JVM starts, with a small readable compatibility manifest. Persistent incremental caches and serialized IR are deferred.
- **Justification:** whole-graph correctness is simpler initially while reusable deterministic JVM output satisfies standalone and Java workflows.
- **Alternatives rejected:** per-module serialized interfaces in the first release, best-effort silent linkage, nondeterministic packaging, and a public persistent IR format.
- **Caveat:** compatibility checks are intentionally small: language contract, runtime ABI, JVM target, and preview requirement; compiler patch identity is informational unless contracts change.

### Module lifecycle, execution, and failures

- **Decision:** module facades are instantiable, eager, thread-confined to their creation thread, and `AutoCloseable`. Top-level effects are allowed and are not rolled back after failed initialization. Runtime faults become unchecked source-mapped Lyra exceptions. Reload creates an unrelated new instance.
- **Justification:** explicit state instances and owner-thread behavior are easy to reason about and preserve a future embedding path without hidden scheduling.
- **Alternatives rejected:** global static singleton state, implicit synchronization, concurrent shared-state semantics, checked exceptions/result wrappers, hidden thread hops, and transparent state migration.
- **Caveat:** independent instances may run on different threads; no hostile-code quotas or forced cancellation are promised.

### Standalone entry, library, recursion, and performance

- **Decision:** executable roots export `main :Fn<Array<String>;I32>`. CLI families are `run` and `compile`. The initial source-visible library is minimal console I/O plus core runtime utilities. Direct self-tail recursion is loop-lowered. One predictable compiler mode emits source maps and relies on the JVM JIT. Numerical performance gates are ratified from direct-Java benchmarks; avoidable primitive boxing/allocation is forbidden structurally.
- **Justification:** gives a useful, measurable standalone system without prematurely adding optimizer levels or a broad standard library.
- **Alternatives rejected:** no CLI runner, several ambiguous main signatures, ordinary-stack self-tail recursion, proper-tail-call trampolines across all functions, multiple optimization levels, and invented latency thresholds.
- **Caveat:** later standard-library domains and mutual-tail-call guarantees require dedicated evidence/specification work.

## 2026-08-31 — Phase 11 Semantic Flow Recovery

### Source and review

- **Source:** owner decision round following the focused phase-11 semantic-flow architecture brainstorm, including fallback architecture, subdomain, and red-team findings.
- **Affected specification:** `language-core.md` and phase-11 implementation plan; phase-12 IR work consumes these decisions.
- **Review timing:** revisit only if implementation evidence exposes a contradiction with the language contract or if a later specification requires a compatible semantic-flow extension.

### Shared flow kernel and staged implementation

- **Decision:** phase 11 will add a shared internal JVM-independent route/identity/flow algebra and one bounded typed flow/effect evaluator, while preserving separate resolver, bidirectional type checker, skeptical provenance verifier, and eager-cycle/SCC boundaries. Implementation proceeds through sequential gates: aggregate ownership; binding/cell/call flow; contextual nil/Unit; higher-order eager initialization; final sealing.
- **Justification:** repeated repairs showed that the same hidden abstract-interpreter state was being independently reimplemented across `SemanticResolver`, `InitializationAnalyzer`, `TypeChecker`, and `TypedSemanticProvenance`. A shared kernel removes representation drift without replacing the established compiler phases with a monolithic global solver.
- **Alternatives rejected:** continuing independent map/set patches as the primary design, or replacing semantic phases with one broad global interpreter.
- **Caveat:** flow facts remain internal and phase 12 must consume frozen facts rather than reconstructing ownership, callable flow, nil context, or eager effects from syntax.

### Imported aggregate ownership

- **Decision:** every aggregate identity crossing a module boundary remains protected from mutation in the importing module, including when rebound through a local `@mut` symbol. Local binding mutability remains distinct from imported identity ownership.
- **Justification:** this is the conservative interpretation that prevents ownership laundering through aliases, narrowing, coalescing, containment, identity-preserving calls, higher-order parameters/results, and aggregate projections.
- **Alternatives rejected:** protecting only identities originating from exported `@mut` bindings.
- **Caveat:** this resolves an ambiguity between the binding-local mutation rule and the explicit imported `@mut` restriction; any future relaxation requires an explicit language-specification decision.

### Flow precision and nil inference

- **Decision:** exact known tuple/array routes are preserved; unknown selectors/callables conservatively use wildcard/may-state behavior. Whole-binding replacement is strong, selected replacement is route-local, and branch/coalesce joins retain all reachable alternatives. Recursive structural nil context is supported through final block expressions, branches, arrays, and tuples when an expected or peer shape supplies the base type.
- **Justification:** preserves sibling precision and valid source-order replacement while remaining sound for uncertainty; prevents endless ad hoc depth-specific fixes.
- **Alternatives rejected:** global imported taint, requiring exact knowledge for every selector/callable, or unbounded unconstrained nil inference.
- **Caveat:** legal function-only recursion remains distinct from eager value cycles; unknown effects may conservatively reject under the phase-11 evaluator policy.

## 2026-09-02 — Producer-Certified Phase-11 Provenance and Sealing

### Source and review

- **Source:** owner-authorized Gate 11E architecture repair following repeated independent adversarial findings recorded in `handoffs/phase-11e-provenance-sealing-brainstorm.md`.
- **Affected specification:** phase-11 implementation plan and the internal typed-semantic/flow boundary; source-language behavior is unchanged.
- **Review timing:** require independent read-only Gate 11E validation before treating the gate as passed. Revisit only if a later compiler phase needs additional internal provenance kinds, not to weaken exact sealing.

### Canonical provenance authority

- **Decision:** `SemanticFlowAnalyzer` is the sole semantic-flow authority and certifies its exact immutable `SemanticFlowFacts` against the exact immutable `TypedSemanticCore` it consumed. `TypedSemanticGraph.seal(core, facts, plan)` accepts only a certified core and facts exactly equal to that producer-owned expected record. The package-private certification is compilation-local, nonserialized, excluded from product compatibility, and does not replace facts in graph equality/hash behavior.
- **Justification:** exact eager effects, callable transfer results, capture snapshots, and alternative identities are semantic results. A sealer cannot independently prove them from spans, types, and topology without becoming a second evaluator. Retaining the producer's immutable expected record lets sealing reject missing, extra, foreign, or mutually forged facts by exact equality while preserving one evaluator invocation.
- **Alternatives rejected:** continued post-hoc predicate accumulation; a sealer-side abstract interpreter; rerunning `SemanticFlowAnalyzer`; hashes or forgeable public tokens; persistent/cross-build IDs; and weakening Gate 11E.
- **Caveat:** package-owned compiler code is the trust boundary. Producer defects remain analyzer defects and are covered by 11A–11D.2 semantic tests; the sealer proves that the supplied publication data is exactly what that canonical producer emitted.

### Explicit site and route evidence

- **Decision:** the typed core allocates deterministic `FlowSiteId` values for typed expressions, references, and captures. Published events, summary calls, callable creation, effect sites/paths, nil alternatives, and aggregate allocation origins retain exact producer-issued site links. `#NIL` provenance is alternative- and route-specific. Aggregate ownership separates its canonical origin span/site from its current use span. Resolver scope/lambda/parameter/capture/reference indexes are checked reciprocally, and no missing-owner path may use a synthetic `ScopeId(0)` fallback. Each module edge retains the exact source `ImportPath.span`; source-header coverage compares edge multiplicity, endpoints, logical target, and that path span as one canonical record.
- **Justification:** raw spans can collide and types/shapes can match unrelated values. Exact compilation-local site and ownership links preserve the proof edges the producer already knows without serialization or Phase-12 IR design. Treating the import-path span as part of an edge prevents a different valid source span or sibling import from being substituted under the same endpoints.
- **Alternatives rejected:** event-wide nil permission; globally known-span evidence; type/signature compatibility as identity; sealer-derived allocation/call ordinals; existence/reachability-only topology checks; and endpoint-only import-edge coverage.
- **Caveat:** `FlowSiteId` is internal and deterministic only for one compilation artifact. It is not a stable Java API, cache key, runtime identity, or serialized format. Source offsets remain provenance rather than stable external module identity.

### Resolver-owned reference and capture topology

- **Decision:** `SemanticResolver` freezes a compilation-local, immutable topology authority containing every producer-selected reference and capture record plus exact declaration, scope, capture-to-reference, and reference-to-capture indexes. Structural validation independently derives each reference's innermost source scope and lexical/source-order target, then derives the complete lambda-ancestry capture chain. It requires the exact capture owner, declaration, lambda scope, mode/shared cell, first causative source site, ordered direct-reference index, and reciprocal typed index.
- **Justification:** a `SyntaxLink` changed with a forged `ResolvedReference` is only mutually consistent metadata. Likewise, module/lambda-owner equality cannot distinguish sibling or ancestor scopes, and coordinated removal of both ends of a capture/reference relation can remain internally consistent. Preserving the resolver's topology while independently deriving lexical and capture ancestry rejects same-name retargeting, compatible-scope substitution, removed capture links, and substituted source sites without rerunning resolution or semantic flow.
- **Alternatives rejected:** name/type/module compatibility, syntax-link agreement alone, scope-span containment alone, lambda-owner membership alone, requiring every capture to own a direct reference, and rerunning `SemanticResolver` during type checking or sealing.
- **Caveat:** the authority remains package-owned, nonserialized, and excluded from graph equality/hash behavior. An intermediate lambda may carry a valid transitive capture with no direct references; its exact first causative descendant reference still owns the capture span and proves the lambda-ancestry link. A resolver-issued certificate proving that a topology object was created by the canonical resolver is intentionally deferred as defense-in-depth: package-private compiler code is trusted, while source-derived topology validation remains mandatory.

## 2026-09-05 — Persistent REPL and Opt-in Application Attachment

### Source and review

- **Source:** owner-approved `/job` plan for persistent Lyra REPL, JLine console, evaluation API, and opt-in application attachment.
- **Affected specifications:** `repl.md`, `backend-runtime.md`, `language-core.md`, `horizon-ideas.md`.
- **Review timing:** revisit only if implementation evidence contradicts persistent typed linkage, owner-thread lifecycle, or normal AOT contracts.

### Decision

- **Decision:** add one optional `lyra-repl` module for transport-independent persistent sessions and bounded loopback protocol; keep compiler session integration in `lyra-compiler`, dependency-free control hooks in `lyra-runtime`, and JLine/console adapters in `lyra-cli`.
- **Decision:** submissions execute newly generated direct bytecode against real typed live bindings, use staged namespace publication with nontransactional effects, preserve lexical identities/cells/aggregate/function identity, and expose only root public exports in attached contexts. Ordinary assignment is the sole public mutable-root write surface.
- **Decision:** application control is opt-in, authenticated, loopback-only, owner-thread confined, cooperative, and non-reentrant. Normal artifacts remain uninstrumented and free of REPL/JLine/compiler dependencies.
- **Justification:** this provides persistent development evaluation without introducing an interpreter, replay semantics, a general debugger, unsafe authority expansion, or a second production execution ABI.
- **Alternatives rejected:** transcript replay, generic object/value handles, a separate debug setter, LAN/TLS listeners, forced cancellation, broad root/private visibility, and placing JLine or compiler dependencies in the runtime.
- **Caveat:** the feature is a trusted owner-controlled execution interface, not a sandbox; blocking host I/O can delay cancellation and old generations may remain reachable through values.

### Source-local data linkage implementation, 2026-09-06

- **Decision:** share structural tuple/function-interface definitions, not generation state or closure classes. Give session tuple getters explicit cross-loader visibility while preserving AOT visibility. Authenticate data-storage access with exact typed handles and same-domain, source-local generation capabilities.
- **Decision:** represent retained arrays as producer-certified external contract origins, without fabricating source allocation sites. Compatible distinct external origins may alias; propagate uncertain writes by joining, not strong replacement. Ordinary allocation provenance keeps its existing mandatory site evidence.
- **Justification:** same binary names in different loaders are not the same JVM type, and type-only scalar flow cannot certify aggregate identity. Non-callable data can survive a failed generation without retaining its module lifecycle, unlike escaped closures.
- **Alternatives rejected:** copying aggregate values, an Object execution ABI, widening ordinary closure authentication, and claiming prior initialization or distinctness from external types alone.
- **Caveat:** this is an incremental delivery boundary, not a reduction of the accepted REPL scope. Callable-bearing values, partial/imported aggregate authority and persistent imported graphs remain guarded until their provenance and lifetime contracts are implemented.
- **Source / affected specifications:** accepted persistent REPL plan and execution tests; `repl.md`, `backend-runtime.md`. Revisit at callable or imported-graph linkage implementation.


### Initialized-generation callable runtime interchange, 2026-09-06

- **Decision:** allow existing exact generated callable parameters to accept Lyra closures from another successfully initialized source-local generation in the same authenticated session domain/epoch. Keep artifact keys distinct and ordinary AOT authentication unchanged. Retire invocation/creation/I/O authority with the session epoch.
- **Justification:** structural interface identity and original generation-owned capture/cell objects already provide the physical ABI. A separate runtime authority can safely certify this narrow boundary without pretending to transport compiler callable summaries or initialized binding proofs.
- **Alternatives rejected:** equating artifact keys globally; admitting other sessions or imported graphs; granting cross-generation INITIALIZING/FAILED producer authority; removing compiler/storage callable guards before flow and escaped-generation lifetime are proven.
- **Caveat:** Java/compiler runtime tests pass values through existing generated signatures. This is not named REPL callable persistence, and failed-initializer escapes remain unsupported. No new public callable handle API is introduced.
- **Source / affected specifications:** owner-authorized xhigh senior pass; `repl.md`, `backend-runtime.md`, `SessionCallableRuntimeTest`, `SessionClosureAuthorityTest`. This runtime-only decision is superseded for named source-local persistence by the compiler certificate decision below; its imported-graph and lifecycle restrictions remain.

### Compiler-certified named callable persistence, 2026-09-06

- **Decision:** retain named source-local callable values across session submissions only when `SessionFlowCertificate` and callable summaries certify exact targets, captures, shared mutable-cell snapshots, writes, allocation provenance, operation sites, initialization and generation authority. Apply the same proof to callable-bearing arrays/tuples and returned callables.
- **Justification:** callable values are executable generated code with captured state; names, types or structural interfaces alone cannot prove that later bytecode may invoke them. A producer-issued certificate preserves the real direct-bytecode ABI while keeping source-local identity and fail-closed lifecycle checks.
- **Alternatives rejected:** copying callable values, source replay, type-only external bindings, globally widening artifact authentication, or admitting imported-module callables without carried ownership evidence.
- **Caveat:** imported/pinned module linkage, configured/application roots, reload and broader escaped-generation ownership remain outside this implemented source-local boundary and must continue to produce structured unsupported outcomes.
- **Source / affected specifications:** callable-summary and session-flow implementation, `PersistentCallableTest`, `SessionCallableRuntimeTest`, `SessionCapturedInstanceFlowTest`, `repl.md`, and `backend-runtime.md`.

### Source and review

- **Source:** owner-authorized targeted Gate 11E repair of the remaining `SemanticResolver` source-flow adapter divergence.
- **Affected specification:** phase-11 implementation plan and internal semantic-flow ownership; source-language behavior and diagnostic contracts are unchanged.
- **Review timing:** require an independent read-only validator focused on the removed adapter before treating this repair as accepted.

### Callable and capture authority

- **Decision:** `SemanticResolver` never evaluates callable bodies or transfers callable results, captures, shared cells, higher-order alternatives, recursion, or call effects. Calls make its source projection non-authoritative. `CallableSummaryCompiler`, `CallableSummarySolver`, `CallableSummarySet`, and `SemanticFlowAnalyzer` are the one canonical route for those operations.
- **Justification:** source replay lacked typed sites, solved summaries, identity-wide alias propagation, route-specific nil, and canonical short-circuit continuation. Keeping it could reject valid source or miss imported ownership after calls and alias updates.
- **Alternatives rejected:** renaming or retaining the replay walker; duplicating canonical predicates; and sharing an end-to-end evaluator across incomplete source syntax and complete typed expressions.
- **Caveat:** resolution may still issue an early ownership diagnostic from a strictly bounded projection of source-local aggregate construction, direct aliases, whole replacement, and branch/coalesce joins. It stops claiming authority after every call or selected aggregate write; all deferred cases are decided before publication by canonical typed flow.

### Symbolic ownership requirements

- **Decision:** callable summaries carry source-ordered, flow-site-bound `OwnershipRequirement` values for aggregate-mutation containers and every `@mut` argument. Requirements use the same formula substitution, higher-order transfer, capture environment, SCC solving, and finite-domain limits as return values and writes. Lambda creation checks capture-materialized requirements without executing the body; actual invocation checks fully substituted requirements.
- **Justification:** imported ownership is a static language obligation even for uninvoked bodies and for `@mut` calls whose callee happens not to write. Explicit summary obligations preserve that rule without a source-level interpreter.
- **Alternatives rejected:** inferring ownership only from observed writes; executing lambda bodies during creation; publishing per-expression states; or moving the diagnostic after sealing.
- **Caveat:** type checking may temporarily defer only an outer-mutation-qualifier mismatch long enough for canonical ownership to preserve the established `LYC-RESOLVE-022` precedence. If no ownership violation exists, the original type diagnostic is returned and no graph is published.

### Trusted REPL completion scope, 2026-09-06

- **Decision:** complete the REPL as a trusted development interface with persistent imported modules, explicit REPL-owned reload, and explicitly enabled localhost application attachment. Remove authentication, credential files, hostile-client isolation and security-only filesystem policy from the current completion scope. Keep loopback binding, explicit activation and finite protocol bounds as operational behavior.
- **Justification:** the delivered direct-bytecode session/compiler foundation already requires exact types, initialization, owner-thread execution and producer lifetime for correctness. The rejected security layer added conflicting requirements without being needed for the expected single-developer workflow. Removing it narrows the product boundary while preserving language and AOT semantics.
- **Tradeoff:** any process able to reach an enabled listener receives application execution authority. Automatic local-root initialization is also deferred; local REPL users explicitly import/load modules, while only explicitly registered REPL-capable application roots are attached.
- **Affected specifications:** `repl.md`, `backend-runtime.md`, `deferred-features.md`, CLI/remote protocol documentation and the Phase 24 REPL coverage matrix. Revisit only if a multi-user or hostile-code deployment requirement is accepted separately.

### Trusted typed module linking and conservative retention, 2026-09-06

- **Decision:** imported and attached values use exact typed links to actual initialized module instances. Namespace publication, resident initialized modules, attempted generations and producer lifetime are separate records. Conservative session/root-lifetime retention is preferred over a heap reachability collector or forced class unloading.
- **Justification:** imported closures, arrays and mutable cells must retain their real generated identity and storage while reload/reset changes what new name lookup sees. Reinstantiating a whole graph or copying values would change semantics.
- **Caveat:** current mutable root state is not assumed to equal initializer state. Attachable compilation must model safe-point mutation as an explicit effect boundary and derive conservative facts for subsequent evaluations.
- **Affected specifications:** `repl.md`, `backend-runtime.md`, session compiler/runtime flow, module reload and application attachment tests.

### Attachable safe-point effect boundary, 2026-09-07
- **Decision:** attachable compilation models every potential dispatch safe point as an explicit effect boundary on the root module's public `@mut` value bindings. Reads of those bindings carry conservative `AttachableBoundary` aggregate identities (not initializer-allocation facts), and both direct and callable-summary mutation checks treat the current contents as potentially foreign-owned. Element mutation through such a binding is an ordinary `LYC-RESOLVE-022` diagnostic in attachable mode; scalar writes, whole-binding replacement, private-state mutation, callable replacement and higher-order transfers remain allowed. Normal compilation is unchanged.
- **Justification:** a trusted evaluation may replace a public `@mut` root binding with an externally owned aggregate between safe points. Arrays have no runtime ownership guard, so a precompiled consumer certified against initializer-only ownership would silently mutate foreign state.
- **Alternatives rejected:** runtime array-ownership guards on the raw-array ABI; blanket rejection of all mutable behavior; treating reads of such bindings as definitely local until Phase 07 dispatch exists.
- **Caveat:** initialization is never dispatchable, so initializer-time reads keep exact local facts. The conservative boundary is represented as a distinct identity kind so same-module callable ownership checks cannot conflate it with genuinely root-owned aggregates.
- **Affected specifications:** `repl.md`, `backend-runtime.md`; attachable profile emission, root registration and ReplProfileEmissionTest/RootTypeRegistrationTest.

### Generated application polling, admission and cancellation, 2026-09-07

- **Decision:** a live attachment shares its registered root controller with the storage workspace so synchronous submission, owner-dispatched evaluation and generated application safe points observe exactly one active evaluation lease. Owner-dispatched evaluation reuses the poll's admitted lease; a second lease is never begun. `ModuleLifecycle.applicationSafePoint()` polls through a contained adapter that records expected dispatched failures and cancellations on the `Dispatch` instead of unwinding into the application frame, while ordinary `LyraOwnerController.poll()` keeps its rethrowing contract. Cross-thread dispatch publication uses safely published control atomics and maps the terminal-publication/lease-release race to a truthful Busy outcome.
- **Justification:** two controllers would let generated root boundaries dispatch nested work during a synchronous evaluation and would split cancellation observation. Containment keeps main resumable without changing the controller's ordinary semantics.
- **Alternatives rejected:** moving the evaluation lease between controllers; suppressing busy outcomes during the terminal-publication race; forced interruption of blocking host I/O or main.
- **Caveat:** cancellation remains cooperative. A root function or blocking I/O that never reaches a generated boundary can delay the terminal cancellation result; this is never a justification for interruption.
- **Affected specifications:** `repl.md`, `backend-runtime.md`; `LyraOwnerController`, `SessionStorageDomain`, `ApplicationAttachment`, attachable emitter safe points, ApplicationSafePointTest/AttachmentCancellationTest.

## 2026-09-08 — Deterministic Debug Artifact Packaging (Phase 11)

### Source and review

- **Source:** accepted REPL completion plan phase 11 (deterministic debug artifact packaging) executed on the Phase-10 baseline.
- **Affected specifications:** `repl.md` (artifacts and compatibility), `backend-runtime.md` (artifact metadata).
- **Review timing:** revisit when Phase 12 launcher/activation composition or a later packaging requirement needs the wire.

### Decision

- **Decision:** debug capability is a versioned canonical metadata declaration (`replCapability`, schema 1) orthogonal to the generated-code execution profile. Debug publications always embed the complete reachable source snapshots, canonical import resolution topology, and reproducible scalar options, and declare the exact fixed closure: `io.mindspice:lyra-compiler`, `io.mindspice:lyra-repl`, `io.mindspice:lyra-runtime` at the artifact's own profile. The capability input enters the artifact revision only when declared, so ordinary schema-1 encodings, revisions, and runtime-only bundles stay byte-identical (proven by a frozen pre-Phase-11 fixture).
- **Decision:** packaged reconstruction rebuilds the recorded graph through the ordinary compiler pipeline from embedded sources only; it never touches resolver objects, original files, initializers, or serialized IR. A rebuilt mismatch is a structured compatibility error, not a silently accepted context.
- **Decision:** `io.mindspice.lyra.repl.ReplLauncher` is the fixed profile-aware Main-Class of debug bundled JARs. It locates its artifact from its own code source (bundled) or an explicit documented artifact-location argument (classes/thin), preflights the declared closure with a fixed source compile that executes no Lyra code, and preserves the ordinary exact `main`/exit contract. Ordinary bundles keep the dependency-free runtime `LyraLauncher`; the runtime compares launcher spellings only.
- **Decision:** bundled closure collection reads fixed production code sources by package prefix (runtime, compiler, and the REPL anchor discovered without a static compiler->REPL dependency) and never scans arbitrary class-loader or Surefire resources. CLI, JLine, JMH, JUnit, test, and credential material is excluded by construction; missing or conflicting inventories are actionable packaging errors.
- **Justification:** the declared-layout approach preserves schema-1 compatibility and ordinary AOT isolation while giving debug deployments a verified, reconstructible source context and a genuinely self-contained compiler/REPL closure.
- **Alternatives rejected:** equating debug capability with the attachable execution profile (would change ordinary generated code), arbitrary classpath scanning for closure collection (would leak test/CLI/JLine material), and launcher-side resolver or IR serialization (would violate the no-live-objects reconstruction boundary).
- **Caveat:** the launcher owns package launch composition only; run activation, listener bootstrap, and wait behavior belong to Phase 12.



### Source and review

- **Source:** owner-authorized Phase 09 execution of the accepted REPL completion plan.
- **Affected specification:** `repl.md` (remote protocol section); no language or AOT contract changed.
- **Review timing:** revisit when Phase 12 bootstrap/launcher composition or a later protocol version needs the wire.

### Decision

- **Decision:** the v2 ready hello carries session identity, revision, mutation sequence, the request-sequence watermark and the active request. Freshness on every stateful operation is the pair (revision, mutationSequence); successful evaluations advance revision, reset advances only the mutation sequence, and stale pairs are rejected before owner work (`REVISION_CONFLICT`/`STALE`).
- **Decision:** LOAD/RELOAD are sequence-bearing request identities answered by the ordinary `Result` with real initializer progress; a LOAD file is read exactly once into a file-URI source with source/label/envelope bounds preflighted before effects, and bound violations terminate `REJECTED`. Completion is two fixed operations (module files under configured roots, binding members from a fixed core-type metadata table) with no compilation, pinning, expression execution or generic file/reflection RPC.
- **Decision:** the attachment remote adapter uses the synchronous owner-thread submission path with a separate remote-server controller; owner-dispatched reset/query/completion keep their existing admission semantics. The dispatched-handle composition is left to the launcher phase.
- **Justification:** mutation-sequence freshness is the only reset-safe coordination that preserves the local revision contract; request-identity LOAD/RELOAD keeps duplicate/sequence/cancel correlation uniform; synchronous owner routing avoids nested-dispatch deadlocks while socket threads stay control-only.
- **Alternatives rejected:** dropping the sequence watermark from the hello (broke reconnect reconciliation), owner-queuing stale rejections (unnecessary owner work), a value-losing managed-console adapter, and generic completion/RPC surfaces.
- **Caveat:** large dynamic result payloads are truncated to the frame bound while terminal status is preserved; attachment reload remains an explicit structured unavailable outcome until scratch-module reload has a root-lifetime surface.

## 2026-09-09 — Run/Compile/Host Activation and Shutdown (Phase 12)

### Source and review

- **Source:** accepted REPL completion plan phase 12 (run/compile/host activation and shutdown) executed on the Phase-11 baseline `736e29e`.
- **Affected specifications:** `repl.md` (run/host activation surface), `backend-runtime.md` unchanged in scope.
- **Review timing:** revisit when a later phase changes the dispatch composition, shutdown ordering, or the attachment-context wire.

### Decision

- **Decision:** `run --repl` compiles an attachable debug-capable root and bootstraps the bounded loopback v2 listener before the root module is published; `compile --repl` records the same capability and never listens. `--repl-port` (0..65535, default 0) and `--repl-wait` are run-only and require `--repl`. Compiled artifacts activate only with `lyra.repl.enabled=true` (plus optional `lyra.repl.port` and `lyra.repl.wait`), default disabled.
- **Decision:** the bootstrap dispatcher rejects every live dispatch until the attachment registers, so a client during initialization receives a truthful busy/unavailable outcome and never touches a partial root. The optional wait observes only handshake-complete v2 controllers (`controllerCount`), never raw accepted sockets, and ends on readiness, orderly shutdown, or interruption.
- **Decision:** after registration the remote transport dispatches on the root's shared controller and the adapter reuses the poll's admitted lease (`submitAdmitted`/`resetAdmitted`) instead of beginning a second evaluation or being rejected as busy. A separate pump controller remains a supported composition for synchronous adapters.
- **Decision:** shutdown order is listener/control retirement first (queued requests get an explicit CANCELLED terminal frame and a bounded writer drain), then the service surface, then the caller-owned root (retiring root-lifetime generations), then the loading context; the primary failure or exit request is preserved and cleanup failures are suppressed. Service close never closes an externally owned root, which may reopen attachment in the retained domain.
- **Decision:** the attachable `optionsRevision` no longer covers packaging mode or include-sources. Both are deployment/presentation choices recorded elsewhere in metadata, and packaged activation reconstructs the context through the always-classes assembly, so the revision must be reproducible across packaging modes.
- **Justification:** the listener must be observable during slow initialization without exposing partial state; the shared-controller composition is the only way generated safe points can service remote work with exactly one lease; and terminal results must reach the controller without letting a broken peer keep main alive.
- **Caveat:** a submit racing the bootstrap gate observes a truthful BUSY and must retry; a wire write racing the service close can only be retired as a disconnect, which the client models as a terminal DISCONNECTED request status without replay.

## 2026-09-08 — JavaFX Development Editor

### Source and review

- **Source:** owner request for a complete JavaFX language editor with project files, function navigation, entry targets, compiler checks, a persistent REPL and source stepping.
- **Affected specifications:** `editor.md` owns the new desktop boundary; `language-core.md`, `backend-runtime.md`, and `repl.md` retain their existing semantics and ABI ownership.
- **Review timing:** revisit for compiler local-variable metadata, a new protocol capability, or target-platform desktop release qualification.

### Decision

- **Decision:** add an optional `lyra-editor` module using OpenJFX and RichTextFX. Highlighting, definitions, diagnostics and reference navigation consume existing compiler phases and immutable source snapshots, including unsaved imported buffers. The editor does not create another grammar, interpreter, language server or execution ABI.
- **Decision:** editor-owned runs use a disposable child JVM with the actual owner-confined session and loopback protocol v2. Explicit load initializes a file once; later evaluations use initialized storage. Run is fresh, evaluation is persistent, and configured editor targets do not replace the standalone public main signature. Program stdin is independent of REPL source.
- **Decision:** use JDI and emitted source/line metadata for actual breakpoints and stepping. Read-only running buffers preserve source correspondence; argument/field inspection never invokes target methods. Existing external REPL attachment remains a single-controller connection and does not implicitly attach a JVM debugger.
- **Decision:** source saves are atomic and external conflicts require explicit resolution. Renames refuse existing destinations. Separate recovery copies preserve unsaved text, and completion of a save must not discard edits made while that save was in progress. Generation checks and synchronized runtime publication prevent Stop/disposal from leaking a newly started worker.
- **Decision:** package a platform-specific launcher ZIP and provide a jpackage application-image build that retains the Java executable and JDI/JDWP modules needed by child workers. Add graphical and packaged-worker checks alongside the existing backend release audit; extend that audit's module layout and target preservation without weakening its backend coverage requirements.
- **Justification:** these boundaries keep the JavaFX event thread responsive, preserve initialized session state and existing compiler contracts, make blocked programs stoppable, and allow the original compiler/runtime/CLI artifacts to remain free of desktop dependencies.
- **Alternatives/tradeoffs:** in-process execution complicates UI responsiveness and owner lifecycle; source replay changes observable initialization; debugger simulation would not prove emitted bytecode behavior. Separate workers add startup cost, while JDI uses real line metadata and retains the compiler's optimization behavior.
- **Caveats:** arbitrary body-local inspection awaits compiler local-variable tables. Linux validation does not qualify macOS/Windows installers or signing. Loopback development transport retains the project's existing trusted-local, unauthenticated boundary.

## 2026-09-10 — Reserved iter built-in

- **Source:** owner confirmation: "yes it is a reserved built in like match".
- **Decision:** reserve `iter` and accept it only as an unqualified call head in
  `(iter range callback)` or `::iter[range callback]`. The bracket form begins
  a fresh expression, never a receiver suffix on a preceding value.
- **Justification:** preserves the requested whitespace-insensitive range/loop
  examples without changing ordinary method-call parsing.
- **Tradeoff:** user declarations, bare function-value references and qualified
  member names cannot use `iter`. Callback values remain ordinary functions.
- **Affected specifications:** language-core.md and the readable grammar.
- **Caveat:** the original keyword/grammar change was a checkpoint; executable
  iteration is completed by the 2026-09-11 backend implementation below.
- **Review timing:** reviewed during the range-iter implementation closeout.

## 2026-09-11 — Certified callback-loop execution

- **Source:** owner authorization to complete iter and while backend execution
  and apply low-risk performance improvements.
- **Decision:** represent both reserved forms explicitly in typed semantics/IR.
  Repeated-call summaries retain selected callback identities, solve capture and
  aggregate effects to the existing bounded fixed point, and expose a symbolic
  post-loop binding snapshot to subsequent expressions. While includes predicate
  effects on its zero-action and terminal paths.
- **Decision:** emit constant-stack JVM loops, primitive range cursors and
  successor-before-addition checks. Evaluate arguments once in source order,
  authenticate callbacks outside the backedge, and retain generated invocation
  lifecycle checks and cooperative safe points.
- **Decision:** persist immutable ranges as exact-width data and render bounded
  RANGE scalar snapshots rather than enumerating them.
- **Justification:** a single abstract callback invocation loses later-iteration
  ownership/callable changes; recursive rewriting complicates provenance and
  stack guarantees. The symbolic snapshot allocates no runtime tuple. Primitive
  traversal avoids iterator/counter allocation without changing callback meaning.
- **Tradeoffs/caveats:** conservative joins may retain unreachable alternatives;
  fixed-point budgets still reject unsupported analysis complexity. No implicit
  callback result dropping, unsigned ranges or additional control forms are added.
  Existing legacy artifact compatibility tests remain intact; release ABI and
  packaging qualification still require the release audit. No benchmark speedup
  is claimed from these structural optimizations.
- **Affected specifications:** language-core.md, backend-runtime.md and repl.md.
- **Review timing:** on extending iterable domains, control flow or summary limits.

## 2026-09-11 — Mutable nominal structs and classes

- **Source:** owner design discussion and authorization to implement syntax and
  full backend support; final confirmation that method replacements obey lexical
  privacy even when contextual `self` is supplied.
- **Decision:** structs are public-field data containers with optional mutable
  fields and nested mutable data. Classes contain private-by-default fields and
  lambda-valued methods. `@pub` exposes members; `@mut` permits slot replacement,
  including method replacement, and is not a method-effect annotation.
- **Decision:** constructors use the enclosing capitalized class name without
  `let`, as `Counter = (=> |start :I32| ...)`. Construction uses `Counter[args]`;
  constructor bodies produce Unit while construction produces the instance.
  Structs retain positional data construction. No inheritance/interfaces are added.
- **Decision:** `:.` reads fields/current bound method values; `::` executes the
  selected method. Saved references retain callable selection and receiver, but
  see current receiver state. Replacement affects future lookups. Existing function
  captures are not rewritten when a function value is assigned to another slot.
- **Decision:** methods may mutate their receiver's `@mut` fields when invoked
  through immutable bindings. Direct assignment retains ordinary root/ownership
  checks. A directly assigned replacement lambda receives the target as `self`
  but receives no new private-access privilege. Privacy follows definition scope.
- **Justification:** these preserve first-class function-value behavior and the
  once-selected callback model already used by `iter` and `while`. Public-by-default
  structs avoid private data that cannot be used through any struct method.
- **Alternatives rejected:** immutable-only structs; forbidding method replacement;
  `@mut` as a method-effect marker; automatic retargeting of saved method references;
  assigning external replacement lambdas private class privileges.
- **Tradeoffs:** mutable structs alias reference storage and have content equality
  that changes with mutation. Ordinary final generated JVM classes, not Java
  records, permit mutable fields. Nominal loading, cycle handling, provenance and
  constructor initialization require explicit implementation and tests.
- **Affected specifications:** language-core.md, backend-runtime.md, repl.md,
  deferred-features.md.
- **Review timing:** at each implementation gate in plans/nominal-types/plan.md.
  This decision records intended behavior, not completed executable support.

## 2026-09-13 — Retained nominal closed transfer, fresh provenance, proof/runtime separation, and immutable-self authority

### Source and review

- **Source:** owner-approved plan `20260912-180414-complete-retained-nominal-struct-class-factory-semantics` executed in phases 1-4 (commits `432121b`, `c6f4274`, `df6e1d5`, `2c3602a`, `2b49f7c`). The two recorded owner answers superseded the handoff's conservative same-site aliasing option and settled immediate closed-algebra completion.
- **Affected specifications:** `language-core.md` (nominal initializer/constructor contracts, immutable-self authority), `backend-runtime.md` (retained-factory runtime authority, session-execute-only root initialization, consumer-scoped fresh provenance, route-exact certification), `repl.md` (cross-generation retained-nominal behavior and the two open limitations).
- **Review timing:** revisit when issue #7's session-root ownership authority lands, when issue #8's nilable member-read contract derivation is added, when a new initializer syntax requires an algebra extension, or when the eight-pass provenance bound must change.

### Immediate closed-algebra completion

- **Decision:** complete the closed retained initializer-transfer algebra now, covering every currently legal member-initializer composition, instead of stopping at isolated direct-call regressions or a conservative type-shaped fallback.
- **Justification:** retained factories must reconstruct flow facts equivalent in meaning to producer execution for any legal initializer. Per-form repair left valid source rejected with `LYC-SESSION-001` or, worse, scalar placeholders that silently dropped nested calls, writes, failures and laziness.
- **Alternatives rejected:** continuing the disconnected template/call inventories; retaining arbitrary producer `TypedExpression` argument trees; fabricated type-shaped array identities for aggregate defaults; weakening the validator.
- **Caveat:** the algebra is immutable, bounded compiler proof, never an executable retained source tree or a second callable-summary interpreter. Any future source-admissible initializer form must extend the algebra and its coverage inventory together.

### Exact summary-derived fresh provenance

- **Decision:** instantiate genuinely fresh summary/default allocations with deterministic consumer-scoped provenance derived by `RetainedAllocationDerivation` from the consumer construction/invocation context, the intervening `SummaryCallId` path and the exact producer allocation site, using tagged identity domains disjoint from ordinary source and summary identities.
- **Justification:** two constructions of one retained factory must not share one abstract allocation identity, while aliases inside one constructed value and genuinely shared returned storage must be preserved, and certification must be recomputable from exact consumer evidence rather than caller-supplied values.
- **Alternatives rejected:** conservative same-site aliasing across retained factory invocations (the handoff's provisional option, superseded by the owner answers); consumer-minted identities detached from producer evidence; infinite per-iteration identity precision.
- **Caveat (finite-site):** the derivation is finite abstract provenance, not a distinct static identity for every runtime iteration. Repeated dynamic execution at one finite analysis site remains sound through the existing repetition/join semantics. Producer-allocated storage stays a certified cross-module view, so the imported-mutation diagnostic still fires.

### Runtime keeps the authenticated producer factory

- **Decision:** runtime construction continues to invoke the authenticated producer-bound original `$lyra$new$<id>` typed factory MethodHandle. The compiler transfer algebra never reaches runtime, and no source/IR replay, schema-only construction, or interpretation exists on any path.
- **Justification:** the producer factory is the sole capability that can initialize the exact generated representation against its embedded nominal contract and real initialized module state. Replaying producer source or executing transfer nodes would duplicate initialization, fabricate authority, or diverge from the direct-bytecode ABI.
- **Alternatives rejected:** executing compiler transfer nodes at runtime; constructing from schemas alone; a generic `Object` execution ABI; replaying producer source.
- **Caveat:** transfer metadata reconstructs static flow facts only; runtime failure evidence remains the producer's own generated source frames.

### Proof/runtime separation and session-execute-only root initialization

- **Decision:** semantic-flow certification (certificate/transfer/summary proofs) and runtime capability authentication remain separate boundaries. `JvmBytecodeEmitter` emits root-binding initialization only from the generated `SESSION_EXECUTE` member of the root module's own declarations; nominal factories and ordinary state methods load already-initialized root storage.
- **Justification:** the compiler proves flow facts for the consumer graph; the runtime authenticates the live capability. Re-executing a producer root initializer from a nominal factory broke session submission ordering; the emission-side restriction keeps producer roots initialized exactly once and defaults running exactly once per actual construction.
- **Alternatives rejected:** letting factories re-run binding initializers (out-of-order submission failures); merging certificate evidence into runtime authority checks.
- **Caveat:** `certifiesLinkedCallable` is deliberately route-blind IR-link evidence (identity/capture); the semantic flow validator remains the authoritative route-exactness gate.

### Conservative flow-insensitive immutable-self provenance

- **Decision:** a non-field aggregate mutation through `self` or any alias of `self` requires the exact nominal constructor lambda, enforced by one bounded, monotone, flow-insensitive provenance lattice (`SelfAliasProvenance`) shared by the resolver closing sweep, topology validation and IR validation. The eight-pass fixed point fails closed with the structured `LYC-RESOLVE-021` diagnostic instead of publishing partial provenance.
- **Justification:** soundness matters more than precision at a mutation-authority boundary; every under-approximating variant admitted a demonstrated escape (branch-merge rebinds, multi-level callable forwarding, identity-returning calls, closure results carrying captured `self`, rebound member slots, aggregate-position loss).
- **Alternatives rejected:** per-site exactness proofs without a bound; route-by-route engines that lost member identity through aggregates; instance-local slot tracking (the actual bypass); raising the pass bound instead of failing closed.
- **Tradeoffs (accepted precision cost):** aggregate sibling positions collapse, member taint is declaration-wide and instance-insensitive, rebinds never clear prior may-alias facts, opaque retained callable bodies are not walked, and alias chains deeper than the eight-pass bound are rejected conservatively. The over-approximation only rejects mutation sites; it cannot erase a route to `self`.

### Rejected sourceLocal relaxation for issue #7

- **Decision:** do not relax `SessionStorageDomain.Linkage.sourceLocal` to ignore the intrinsic module and admit importing graphs. Keep the pinned `SessionClosureAuthorityTest` posture that an imported graph's authority is not certified. Issue #7's correct direction anchors the generated nominal instance's ownership to the session root identity (or authenticates same-session cross-generation reads through an authority independent of the importing-graph rule), designed across generated member access and runtime closure authentication.
- **Justification:** the one-line relaxation made the four Unit/intrinsic inventory cases pass but turned the pinned closure-authority assertion red. A nominal-only same-workspace predicate fixed Unit-valued reads but then failed the separately correct callable boundary with `LYR-LINK`; broadly admitting that closure would erase the distinction the same test pins.
- **Caveat:** a complete fix must carry route-scoped delegation from an authenticated nominal field (or construct the nominal under an authority that also owns its installed field values) while still rejecting the same imported closure when presented directly.
- **Review timing:** at issue #7 design and implementation; the four pinned `LYR-LINK` inventory cases are the acceptance signal and must flip to value assertions together.

## 2026-09-14 — Primitive constants are owned by PrimitiveType (issue #13)

### Source and review

- **Source:** GitHub issue #13 and owner answer on 2026-09-14 selecting removal of both alias sets. Baseline commit `58a8a19`.
- **Affected specifications:** `backend-runtime.md` (Public Java API: primitive constant ownership and the compatibility break).
- **Review timing:** revisit only if a future public type API needs primitive constants exposed on the `LyraType` interface (it must not) or if a Java source/binary compatibility guarantee is introduced.

### Decision

- **Decision:** `io.mindspice.lyra.compiler.types.LyraType` and `io.mindspice.lyra.runtime.LyraType` declare no constant fields. The fourteen primitive constants live only on the corresponding `PrimitiveType` enum, and the unused `PrimitiveType.Bool/Char/String/Unit` aliases are removed as well. Every repository consumer reads `PrimitiveType` directly.
- **Justification:** a field on the interface initialized from an enum that implements that interface is unsafe. JLS 12.4.2 initializes a class's superinterfaces that declare at least one default method before the class, so an interface with default methods is initialized while the enum's constants are still null. Measured before the change: the runtime shape (default methods) observed `LyraType.I32 == null` when `PrimitiveType` initialized first, while the compiler shape (all-abstract methods) never produced a null in either order, because enum-first initialization does not initialize an all-abstract-method interface at all. The four capitalized enum aliases were duplicate public surface with zero callers, and the compiler-side fields are removed as well because their safety depended solely on the interface's method shape.
- **Alternatives rejected:** keeping the interface fields behind deprecation or a holder class (retains the hazard and the ambiguity); retaining the capitalized enum aliases as harmless convenience (duplicates primitive constant surface for no current caller).
- **Caveat (accepted break):** this is an intentional Java source and binary compatibility break with no shim, alias, or compatibility adaptation. It is not a source-language, artifact-schema, or runtime-ABI change: generated artifacts reference no `LyraType` or `PrimitiveType` static field, so `RuntimeAbi.CURRENT`, `LANGUAGE_CONTRACT_VERSION`, and `ARTIFACT_SCHEMA_VERSION` are unchanged. Isolated initialization-order tests (a parent-first `URLClassLoader` whose only parent is the platform class loader, so delegation cannot resolve project types) initialize each type first in both modules and assert all fourteen constants and their operations are correct.

## 2026-09-14 — Language contract version 2: marker-free match, `cond`, bare built-in brackets, bare negative literals, explicit nominal construction

### Source and review

- **Source:** owner-accepted plan `20260914-005314-resolve-and-close-lyra-github-issues-6-13-in-dependency` (Tranche 2) implementing GitHub issues #9, #10, #11 and #12 in one end-to-end source migration; baseline commit `58a8a19`, first tranche `de93506`. The plan's settled decisions and recorded owner answers supersede the earlier `??`/match-conditional/`::`-built-in/nominal-spelling choices.
- **Affected specifications:** `language-core.md` (lexical rules, literals, calls and accessors, match, new conditional section, ranges/iteration, condition-controlled iteration, structs/classes construction, validation list), `grammar_spec.md`, `repl.md` (developer commands), `editor.md`, `backend-runtime.md` (language-contract versioning).
- **Review timing:** at each remaining Tranche-2/3/4 validation gate, and whenever a further source-breaking change is proposed (it must take the next language-contract version).

### Decision

- **Decision (issue #11):** value `match` always takes a real subject and uses marker-free arms in both spellings. The former wildcard-subject conditional mode is removed and replaced by a dedicated `(cond condition -> result ... _ -> fallback)` special form with equivalent parenthesized and direct-bracket spellings, and the same lazy arm, truthiness, unification, nilability, fallback and tail behavior. `??` and `(match _ ...)` are rejected with structured source-mapped diagnostics.
- **Decision (issue #9):** `match`, `cond`, `iter` and `while` are reserved forms that use their bare bracket spelling (`match[...]`, `iter[...]`, `while[...]`). `::` remains reserved for direct calls of resolved local or imported function and method names; `::match[...]`, `::cond[...]`, `::iter[...]` and `::while[...]` — including qualified and receiver readings — are rejected.
- **Decision (issue #10):** a `-` immediately adjacent to a numeric literal in an expression position is a bare negative literal normalized to the existing unary-minus operation, so `-1`, `-128I8`, `-0.0` and `-1.0e3` are accepted with unchanged exactness, suffix, range, underflow, nonfinite and unsigned rejection. A trivia-separated `-` remains the bracket-required operator, and `--1` is never a literal.
- **Decision (issue #12):** nominal construction is explicit, `:Type[args]` or `:module->Type[args]`, with no whitespace after the colon. Resolution, not capitalization, decides: the obsolete unprefixed `Type[args]` and qualified `model->:.Counter[0]` are rejected when their bracket target resolves to a nominal type, while uppercase value indexing, primitive/`String` conversions and `Array`/`Tuple` literal spellings are preserved unchanged. Construction continues to lower to the existing declared factory semantics.
- **Decision (grammar boundary):** a single optional comma may separate sibling forms of a module/block sequence and sibling marker-free match/cond arms, but only when the following sibling begins with `::`; the exact parenthesized direct call `(::name[args])` preserves that direct call. No general grouping, statement punctuation or arm-separator feature is added.

### Justification

- Naming the conditional form separately removes the dual meaning of a wildcard subject, and removing the arm marker removes a token that carried no structural information.
- Reserving `::` for resolved source calls keeps the authority boundary honest: compiler-recognized built-ins are not callable values and must not look like imported functions.
- A bare negative literal is conventional surface syntax; normalizing it to the existing unary-minus operation keeps one semantic and IR path, so every numeric boundary rule is unchanged.
- An explicit construction prefix makes the type-versus-value distinction visible at the syntax level instead of relying on later resolution of a bracket application, without changing runtime semantics.
- The narrow comma exception is required because `::name[...]` is also a postfix receiver call; it is deliberately limited so no general comma or statement punctuation is introduced.

### Alternatives rejected

- Keeping `??` as an optional marker or adding an edition/legacy mode (the accepted change is immediate and has no alias, edition selector or second parsing mode).
- Keeping conditional mode inside `match` as a second subject form.
- Accepting `::match`/`::iter`/`::while` as aliases or deprecating them gradually.
- Treating any leading `-` as part of a literal regardless of trivia adjacency.
- Requiring `:Type` for primitive conversions or `Array`/`Tuple` literals, or making uppercase identifiers construct.
- General comma separation in blocks or arms, or general parenthesized grouping.

### Amendment: direct-bracket `cond` spelling

The initial parenthesized-only boundary was removed after implementation review. `cond[...]` is now accepted as the direct-bracket equivalent of `(cond ...)`; it remains a reserved special form, not an ordinary callable or a `::cond[...]` alias. The parser, replay descriptors, syntax tree, semantic pipeline, typed IR, lowering, diagnostics, conformance corpus, and documentation preserve one conditional meaning across both spellings.

### Caveat (accepted break) and versioning

- This is an immediate source-breaking contract. It takes **language contract version 2**, applied coherently to `LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION` and the compiler's `ModuleRevision.LANGUAGE_CONTRACT_VERSION`, so module/export identity, `LYRA-EXPORT-ID` derivation, embedded facade metadata publication and artifact revision all move together. `RuntimeAbi` stays `1.0` and the artifact schema version stays `1`/`2`: this change alters the accepted source language, not the generated-class/runtime member ABI. Artifacts published under language contract 1 are rejected by the runtime with the explicit `LYR-COMPAT` diagnostic `unsupported language contract version: 1`, which `ArtifactMetadataReader` now reports before any version-derived identity check; the frozen `legacy-artifact-v1-normal.json` fixtures are retained as incompatible-artifact evidence rather than migrated.
- Active fixtures, generators, oracles, models, examples and coverage documents are migrated in the same completed tranche; saved fuzz replays keep their original bytes and gain migrated semantic twins so the original counterexamples stay exercised.

### Amendment: canonical unprefixed nominal construction

- **Source:** owner request to remove the required `:` before user-defined type construction.
- **Decision:** `Type[args]` and `module->Type[args]` are the canonical nominal construction spellings. Resolution distinguishes these targets from ordinary value indexing, so uppercase value names remain indexable and built-in forms remain unchanged. The existing `:Type[args]` and `:module->Type[args]` spellings remain accepted for source compatibility. The qualified namespace-value spelling `module->:.Type[args]` remains invalid.
- **Justification:** removes a redundant marker without relying on capitalization or changing factory, initialization, flow, IR, bytecode, or runtime authority semantics.
- **Versioning:** this is additive because the former colon-prefixed spellings remain valid; language contract version 2 is unchanged.
- **Affected specifications:** `language-core.md`, `repl.md`, `grammar_spec.md`, and the reference/tutorial documentation.

## 2026-09-14 — Retained nominal member routes use opaque occurrence evidence (issue #7)

### Source and review

- **Source:** owner-accepted plan `20260914-005314-resolve-and-close-lyra-github-issues-6-13-in-dependency`, Tranche 3 issue #7, plus the incomplete implementation audit.
- **Affected specifications:** `language-core.md`, `backend-runtime.md`, and `repl.md`.
- **Review timing:** independent Tranche 3 validation and any future change to retained callable or nominal member transport.

### Decision

- A session-created nominal object anchors ownership to its exact standalone domain/epoch or explicit root lifetime without changing `Linkage.sourceLocal` or general callable authentication.
- A callable member crossing an importing-generation boundary is represented by a generated occurrence delegate. Its constructor accepts only opaque, single-use evidence issued after an exact generated field boundary authenticates the object, schema field, producer, signature and selected value. The evidence normalizes to one raw selected closure plus a flat immutable identity-deduplicated list of source/index/signature dependencies; repeated or alternating routes extend and validate the list iteratively instead of nesting delegates. Saved evidence never rereads a mutable slot and never authorizes the same raw closure occurrence.
- A generated mutable callable-field setter first checks the exact writable route, then authenticates a replacement under the actual generated caller (or the exact nominal producer already owning that raw value) and stores the field's generated delegate. The runtime rechecks every source object's OPEN producer, exact schema field/signature, caller domain/root, epoch/root activity and selected replacement lifecycle at authentication or direct-invocation boundaries. Private access remains an issuance-time lexical check. Aggregate projections do not acquire field-route evidence.
- Route wrappers are occurrence-specific authority but not new Lyra function identities. Function `eq?` compares their raw selected `LyraClosureIdentity` through a boolean-only, non-authorizing runtime helper. Repeated reads of an unchanged slot and routed/raw occurrences of one selection compare identical; a replacement changes new-read identity while saved selections remain old.
- Runtime ABI minor version 1.1 identifies the added generated/runtime delegate members. Runtime 1.1 accepts older 1.0 artifacts; runtime 1.0 must reject 1.1 artifacts at metadata preflight.

### Justification and rejected alternatives

- Opaque occurrence evidence makes the compiler-certified selection a real capability boundary; a public/protected `(source,target,index)` constructor was rejected because caller-minted objects could authorize an unrelated same-signature closure.
- Session-wide or closure-identity authorization, heap scans, copied values, field rereads at invocation, source replay, schema-only evidence and a `sourceLocal` relaxation were rejected because they either launder authority or change saved-selection semantics.
- Delegate classes remain session-only structural classes. Ordinary AOT class inventories contain no delegates, and no protocol-v2 or JavaFX boundary changes.

## 2026-09-14 — Named-call parity uses sealed storage-route proof and instance signature metadata (issue #6)

### Source and review

- **Source:** owner-accepted plan `20260914-005314-resolve-and-close-lyra-github-issues-6-13-in-dependency`, Tranche 4 issue #6, and the callable-call overhead/form-homogeneity report.
- **Affected specifications:** `language-core.md` and `backend-runtime.md`.
- **Review timing:** whenever a new callable storage route, generated call adapter, or callable authentication boundary is added; recheck the Phase 23 S/F ratio gate after JVM/runtime changes.

### Decision

- S-expression and direct-name calls converge after resolution only when a producer-issued IR fact identifies the same exact non-nil declaration/member storage route and complete callable signature. Both IR call forms carry that fact. The independent IR validator recomputes local, parameter, capture, shared-cell, exact import, retained producer/generation, compiler-certified external-binding/accessor, intrinsic and nominal member-index/receiver-occurrence facts from resolved topology and the exact target site before a backend can use them. Syntax, declaration name, descriptor, generated class and structural function type are never proof; an uncertified retained direct name remains on the dynamic authenticated path.
- Proven routes omit only redundant call-site authentication. Their storage/member entry and write boundaries authenticate the complete callable contract. Computed/aggregate targets remain dynamic and re-authenticate the candidate and all owner, route, lifecycle, thread and safe-point constraints for every call.
- Each generated state or closure instance resolves each distinct expected dynamic signature once through its own producer authority into a deterministic private final field. No static/global signature or nominal cache is introduced, and cached metadata grants no live-value authority.
- Proven S-expression self calls use the existing tail loop. Mutable self calls first select the current slot and compare it with the executing closure; failed identity falls back to an ordinary call. Generated source-bearing call regions translate only `StackOverflowError`, covering target/authentication, arguments and invocation, and append source frames in callee-to-caller order. The typed Java facade is a host entry rather than a nested source-level Lyra call, so an ordinary structured user-function failure crosses it unchanged; its source-bearing stack boundary independently translates an unhandled `StackOverflowError` and appends the module frame once to both newly translated and already structured `LYR-STACK` failures. Source-independent shared nominal delegates retain generation-stable class definitions and rely on the caller boundary for source mapping.

### Justification and rejected alternatives

- Reusing the direct path based on IR shape or signature compatibility would allow an unauthenticated value to inherit authority. Retaining unconditional per-call parsing/authentication would preserve safety but not language-form homogeneity. Global signature caches would cross producer/schema environments and were rejected.
- Re-reading a mutable target after argument evaluation, unconditional mutable self looping, catching `VirtualMachineError` broadly, or treating the host facade as an ordinary nested Lyra frame would change observable semantics, the pinned Java failure contract, or host-failure behavior. Recreating every ordinary failure merely to append a facade module frame also violates the owner-ratified expected-failure allocation gate. Embedding source-specific handlers in shared structural delegate classes was rejected because it changes one stable binary name across session generations.

### Caveats and compatibility

- This changes private generated implementation members and bytecode but adds no public compiler/runtime API and does not relax issue #7/#8 occurrence, schema, `sourceLocal`, lifecycle or ABI 1.1 authority. Runtime ABI and language-contract versions are unchanged.
- Numerical completion still requires a full-protocol Phase 23 run with finite positive equivalent S/F scores and `score(S)/score(F) <= 1.10`; reduced smoke evidence is diagnostic only.

## 2026-09-14 — SemVer releases and versioned AOT-enabled CLI installation

### Source and review

- **Source:** owner decision during the CLI installation and Java 25 startup discussion.
- **Affected specifications:** `backend-runtime.md` deployment surface, repository `AGENTS.md`, `README.md`, and the CLI installation workflow.
- **Review timing:** when publishing a new release, changing the supported Java profile, or changing the installed launcher/cache layout.

### Decision

- Lyra release and Maven coordinates use Semantic Versioning. The current release is `0.1.0`; installed and released artifacts do not use Maven `SNAPSHOT` coordinates.
- Before `1.0.0`, new features and breaking contract changes advance the minor version, while compatible fixes and documentation-only changes advance the patch version. From `1.0.0` onward, standard SemVer major/minor/patch meanings apply. Prereleases use explicit SemVer prerelease identifiers.
- The installer keeps immutable releases under a versioned directory, exposes one stable `lyra` launcher through an atomic `current` pointer, and preserves older releases for rollback. It does not overwrite an active release as the normal upgrade path.
- Java 25 AOT caches are generated during installation for the exact CLI JAR, Java runtime, operating system and architecture. A cache is optional at launch and is ignored when absent or incompatible; the normal CLI/REPL behavior remains available without it.
- An installed `lyra` invocation with no arguments enters the REPL. Explicit `repl`, `run`, `compile`, and `attach` subcommands retain the existing CLI surface.

### Justification and rejected alternatives

- Versioned release directories and a stable pointer make upgrades atomic, preserve rollback, and prevent stale AOT cache reuse after a version change. In-place replacement was rejected as the default because it weakens rollback and risks partial upgrades.
- AOT improves JVM class loading/linking startup without changing Lyra execution semantics. GC tuning, heap sizing and native-image conversion were intentionally excluded from this installation change.
