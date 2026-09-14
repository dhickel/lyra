# Language conformance, runtime boundaries, and fuzzing

The language conformance corpus, numeric and ABI matrices, compiler/runtime fuzzing,
and persistent-session state model are mandatory parts of `mvn test`. They use JUnit
5 and the existing Java 25 build. No extra service, fuzzing dependency, native agent,
network access, or opt-in profile is required. Surefire reports assertions; a fuzz
timeout, compiler invariant failure, unexpected runtime exception, wrong result,
missing completion marker, or invalid campaign configuration fails the build.

These suites test the language implemented by the living
[language specification](../.internal-dev/specifications/language-core.md) and
[backend/runtime specification](../.internal-dev/specifications/backend-runtime.md).
They supplement the existing phase, CLI, artifact, REPL, protocol, and editor tests.
The Phase 24 release audit remains the release gate.

## Commands

```sh
# Required core suite, including the default fuzz campaigns and session model
mvn test

# All language-focused compiler tests and their runtime dependency
mvn -pl lyra-compiler -am test \
  '-Dtest=Language*Test,RangeIntegrationTest,FuzzInfrastructureTest,TypedIrTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# Extended campaign: 1,800 cases per seed across four compiler seeds plus the
# persistent-session model at 240 steps across three session seeds. One Maven
# reactor invocation; a failure in either model fails the whole command.
./tools/fuzz-language.sh

# Change seeds and budgets; the generator is deterministic for a fixed revision
./tools/fuzz-language.sh -Dlyra.fuzz.seeds=42,99 -Dlyra.fuzz.cases=9000
./tools/fuzz-language.sh -Dlyra.sessionFuzz.seeds=137 -Dlyra.sessionFuzz.steps=500

# Replay saved SOURCE and expectations, independent of later generator changes.
# The nominal matrix and the session model still run alongside the replay.
./tools/fuzz-language.sh -Dlyra.fuzz.replay=/absolute/path/current.properties

# Persistent-session campaign (include a compiler test for its fail-if-no-tests policy)
mvn -pl lyra-repl -am test \
  -Dtest=SessionStateFuzzTest,LanguageCoverageTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dlyra.sessionFuzz.seeds=137 -Dlyra.sessionFuzz.steps=96

# The session model has no file replay: replay a failed session by seed and
# step budget; its transcript is in lyra-repl/target/session-fuzz/seed-<seed>-*/
# worker.log (the failure output already names the exact command).
```

Run Java 25; Maven and child workers use the active Java installation. All arbitrary
source/artifact fuzzing and persistent-session execution run in disposable child
JVMs with `-Xverify:all`, a 384 MiB heap limit, and a 192 MiB metaspace limit. This
contains test crashes and resource exhaustion within a worker. No worker executes
arbitrary mutation results: those are compiled twice and class-loaded for verification
without module initialization. Only bounded generated programs with known execution
contracts are invoked.

## Maintained coverage matrix

The readable `.lyra` fixtures live in
[`lyra-compiler/src/test/resources/language/corpus`](../lyra-compiler/src/test/resources/language/corpus).
Each fixture is an independently named JUnit dynamic test and a permanent mutation
seed. The Java harness lives in
[`io.mindspice.lyra.compiler.conformance`](../lyra-compiler/src/test/java/io/mindspice/lyra/compiler/conformance).

| Concept | Deterministic assertions | Generated / existing complementary coverage |
|---|---|---|
| Source and lexical rules | ASCII names, nested comments, optional commas, annotation spacing, malformed literals/modifiers/escapes, numeric range errors | Raw UTF-8 versus a strict independent decoder, BOM, CRLF, supplementary characters, token spans/order/EOF, token insertion/deletion/replacement/duplication/truncation; existing `SourceFoundationTest`, `LexerTest`, `GrammarMatcherTest`, `ParserTest` |
| While | Reserved bracket/parenthesized calls, exact Bool predicate and Unit action, once-selected callbacks, shared mutable captures, zero-action/final-predicate effects and constant-stack execution | Grammar/parser generators plus `CallbackLoopIntegrationTest` and the source-fuzzer `loops` mode; predicate/action construction order, returned callable changes, nested loops, one million actions, failed callback effects and cancellation in `PersistentCallableTest` |
| All 14 primitives | Explicit text conversion; exact scalar/array/nilable Java carriers, Unit return versus value positions | `LanguageNumericTest`, `LanguageAbiTest`, all ten numeric kinds in generated expression trees; primitive inventory drift fails tests |
| Checked integers | Signed minima/maxima, unsigned high bits, zero/one/neighbour boundaries, overflow/underflow, arithmetic, power, remainder, comparisons, negation, increment/decrement, division and reciprocal | `BigInteger` model independent of compiler folding and runtime helpers; every operation runs on runtime arguments |
| Trapping floats | F32/F64 arithmetic at each precision, signed zero, minimum subnormal/maximum finite values, non-finite results, explicit conversions | Generated nested operations, independent IEEE arithmetic and finite-result checks; existing NaN-at-host-boundary tests remain in `Phase16SmokeTest` |
| Numeric contracts | All 100 explicit source/destination pairs and all 100 implicit acceptance/rejection pairs; U64-to-float even/odd rounding ties, adjacent values and 2,048 deterministic random inputs per destination | Independent widening-domain table and exact-integer rounding model; Java-visible raw unsigned payloads; generated explicit conversions; no compiler conversion predicate used as an oracle |
| Operators | Every operator in prefix and bracket form; missing-operand rejection in both forms; variadic folds/chains, truth and identity | Lexer operator inventory guard, mixed nested tree forms, runtime boundary matrices |
| Bindings and mutation | Immutable rejection, legal `@mut`, public signature/replacement restrictions, source order, private replacement, block shadowing, pure increment | Stateful arrays and scalar bindings, saved closure selections, shared cells, rebinding, aliases, two independent module instances |
| Functions | Contextual/inline signatures, compact argument lambdas, exact arity, non-callable rejection, direct/callable calls, self-tail/mutual/non-tail recursion, closure identity | Generated callable expressions and captured cells; `Phase16SmokeTest`, `Domain11*Test`, `CallableSummaryTest` cover recursive linking, flow and ownership failures |
| Ranges and iter | Enclosed exclusive/inclusive construction, computed bounds, mandatory nonzero step, signed domains, reusable values, zero/one-argument Unit callbacks and exact Java exports | `RangeIntegrationTest` and `CallbackLoopIntegrationTest`: independent traversal models at all four widths, empty/directional/terminal boundaries, fresh captured parameters, stored/computed/imported callbacks, imported-mutation rejection across repetition, sealed IR inventory, persistent range snapshots and cross-submission reuse. The source-fuzzer `loops` mode composes both loops with an independent result/effect model. |
| Control flow | Truthiness of scalars, empty/non-empty aggregates, functions, Char, Unit and nil; Bool equality against heterogeneous truth-testable values; branch narrowing, predicate once, then-only Unit including discarded primitive/reference/nilable results | Generated conditionals/coalescing; side-effect assertions for `and`, `or`, `xor`, strict argument/array/tuple order; executed nilable truth/equality corpus fixtures |
| Match and cond | Parenthesized `(match ...)` and bare `match[...]` provide subject-once value matching with marker-free arms, arbitrary value patterns, guards, mandatory final wildcards, reserved keywords, nil/structural equality, result widening, malformed arms and source-mapped failures; parenthesized-only `(cond ...)` provides lazy truth-tested chains without a subject or guards | Generated match and cond programs with independent effect/result/error models and equivalent accepted spellings; nested match/cond expression trees; `MatchBoundaryIntegrationTest` covers direct-call/conditional/namespace composition, fallback-only effects, contextual aggregate inference and source order; mixed-width unsigned/integer/floating probes exercise logical equality conversion; `Phase23StructuralBytecodeTest` checks switch dispatch, tail lowering and unboxed match paths; `MatchSessionTest` covers selected closures and failed-publication effects |
| Nilability | Nil context rejection; falsey non-nil values retained by coalescing; nil-only fallback, narrowing, nested nullable arrays, nullable Unit | Nullable ABI wrappers, generated coalescing, session nil/value transitions |
| Arrays/tuples | Fixed shape, inference/context, field/index access, aliases, structural equality versus identity, callable elements, mutation permissions, invalid fields/writes | Every scalar array carrier, every integer index domain including large U64 and negative indexes, failed stores leave data unchanged; generated aggregate trees and state histories |
| Strings/Char | Escapes, surrogate code units, UTF-16 length/indexing, concatenation, scalar/Unit text, no implicit stringification | Random Unicode, quotes, tabs, NUL and supplementary characters through all I/O exports; existing malformed-input/stream-failure tests in `Phase20IoTest` |
| Modules | Header placement, namespace misuse, missing imports | Generated dependency chains, namespace aliases, selective aliases, re-exports, resolver-order invariance and exact artifact bytes; existing `ModuleGraphDiscoveryTest`, `Phase22ConformanceTest`, `Phase16SmokeTest` cover cycles, ambiguity, visibility, eager order and instance sharing |
| Built-ins | Exact `std->io` export-name/signature inventory and executed `print`, `println`, `eprint`, `eprintln`, `readLine` contracts | Random Unicode stream payloads, CRLF/empty/final lines, EOF, exact stdout/stderr order and UTF-16 length; read/write/flush/interruption failures remain covered by `Phase20IoTest` |
| JVM operations | Typed method descriptors; class verification; every sealed IR node represented by supported source | `TypedIrTest` compares observed nodes against `IrNode.getPermittedSubclasses()`; existing `Phase23StructuralBytecodeTest` checks primitive bytecode and boxing/allocation constraints |
| Runtime authority/lifecycle | Existing exact handle, facade, callable authentication, initialization, owner and close assertions | Random action histories check wrong-thread refusal before mutation, forged SAM refusal before callback execution, retained live closure cells, failed-invocation effects, idempotent close and rejected stale handles |
| Artifact loading | Existing deterministic classes/thin/bundled packaging, CLI and direct Java consumers | Truncated metadata/debug maps, broken class magic, missing/truncated classes; failure must be `LYR-COMPAT`, `LYR-LINK` or `LYR-VERIFY`; a clean load must still succeed afterwards |
| Persistent sessions | Existing scalar/aggregate/callable/linkage/import/reload and protocol suites | `SessionStateFuzzTest`: Java model of original storage, array aliases/rebinding, closure captures, nil, failed publication versus completed effects, non-executing `\type`, reset and independent generations. Its 20-operation rotation also covers retained nominal declarations, cross-generation construction with exact instance values, shared aliases versus fresh identities, member replacement with saved-versus-current callables, ordered constructor/default effects, callable-bearing composites, runtime-failure publication (nothing staged, completed effects retained), recovery, and reset with full model re-initialization |
| Nominal types | `NominalTypeIdTest` and `NominalTypeTest`: identity, invariant types and recursive schemas. `NominalSyntaxTest`: grammar/replay and malformed source. `NominalSemanticsTest`: schemas, privacy, constructors, typed/IR field and method flow, immutable initialization, incomplete-self escape, saved-slot/alias behavior, transitive factory calls, ordered constructor effects, contextual replacement self and class-versus-struct identity typing. `TypedIrTest` retains exhaustive source-produced variant coverage including nominal declarations/construction. `RetainedNominalFlowCertificateTest` proves constructor-summary derivation for nested objects and tuple-routed arrays. `NominalBytecodeTest` executes construction, core member operations, receiver-bound replacements and cycle-safe equality. `NominalSessionTest` proves retained instances, methods, constructors, ordered arguments/defaults/bodies, captured and helper-returned closure installation, failure cleanup/source frames and snapshots across three submissions | Four grammar seeds × 240 cases, four semantic field/signature models × 100 cases, three independent branch-set initialization models × 36 cases, and three factory callable-slot ordering models × 24 cases (1,800 per seed in extended campaigns). The excluded class fixture tests inheritance; the older public-API deferred-class negative now checks an actually uninitialized class. |
| Retained nominal transfer/certification | `RetainedNominalFlowCertificateTest` and `NominalSessionTest` cover the closed initializer algebra, exact routes/provenance, per-construction fresh allocations, alias preservation, slot selection, failure publication and the pinned issue-#7 `LYR-LINK` Unit/intrinsic observation gap | The compiler fuzzer's retained family is a deterministic three-generation session model: a producer generation declares nominals with member initializers from every legal transfer variant (literals, immutable/mutable references, lambdas, direct/namespace/callable calls, arrays/tuples, operators/conversions/ranges, conditionals/coalesce/match with guards, blocks with declarations/rebinding/shadowing, projections/indexing/length, nested construction, iter/while loops); a construction generation instantiates the factories through the authenticated storage domain; observation generations read members, identities (`eq?`), saved-versus-current slots, shadowed factories, and expected failures. Independent plain-Java arithmetic supplies every expected value; forged routes, forged/mismatched inventories and mismatched link capabilities must be rejected; the fail-closed preflight guard must stay silent on legal source; the four pinned Unit/intrinsic shapes must keep failing with the structured `LYR-LINK` diagnostic. Ten profiles rotate deterministically, so the minimum 120-case budget per seed executes every profile, every operation name, and every pinned shape |
| Nominal runtime contracts | `NominalTypeContractTest`: explicit closed schema parsing, recursive fields, data restrictions, unknown/duplicate/forged contracts. `JvmAbiMapperTest`: exact compiler/runtime nominal descriptor parity, nullable/array positions and wrong-family/origin negatives | Two recursive-schema seeds × 80 cases (1,800 per seed in extended campaigns), complemented by generated-object execution and retained-constructor session tests. |
| Nominal artifact schemas | `NominalArtifactMetadataTest`: schema-2 recursive/export round-trips, identity/order/version/member corruption and full-schema revision binding; existing legacy/debug schema-1 tests remain unchanged | Two schema-publication seeds × 60 cases (1,800 per seed extended), varying recursive layouts and checking field-name tampering against an unchanged revision. Live-object authority is covered by construction, bytecode and session suites. |
| Compiler/runtime nominal projection | `GeneratedTypePlannerTest`: source-to-IR schema projection preserves recursive fields, nullable arrays, private/public fields and exact mutable method contracts | Two field-contract seeds × 24 cases (1,800 per seed extended). Expected field types, mutability and constructor parameter order come from an independent source model; no nominal emitter failure is accepted as execution. |
| Producer-scoped nominal signatures | `NominalArtifactMetadataTest`: schema-1/2 compatibility, exact producer environment, wrong-thread and closed-owner rejection, failed initialization, and shared-cache identity | Two independent producer-lifetime seeds × 60 cases (1,800 per seed extended). Retiring one producer must reject its cached lookups without retiring another producer sharing the same metadata; emitted execution is covered by `NominalBytecodeTest` and `NominalSessionTest`. |
| Nominal construction authority | `NominalConstructionTest`: exact receiver/type, one-time ticket binding, partial reads/publication, immutable and mutable slots, zero-field structs, failed constructors/producers, private/public and foreign-domain access, owner-thread confinement, and the active ticket's exact self-reference exception. `NominalBytecodeTest` verifies source factory success/failure and recovery | Two independent initialized-slot/value models × 80 cases (1,800 per seed extended). Fixtures independently validate the runtime capability while source integration proves the emitter uses it and preserves original runtime failures. |
| Emitted nominal classes and source factories | `GeneratedTypePlannerTest`: exact class/field/member/factory/linkage/equality inventories. `NominalBytecodeTest`: public compiler source input, deterministic final representation bytes, exact private primitive/reference fields, initialized/generated/public method bodies, owner/thread/lifecycle checks, same-class foreign-producer rejection, self links, nullable arrays, callable rejection, typed facade/function boundaries, ordered source factories, qualified/selective construction, source reads/writes, current method calls, saved references, contextual replacement capture, cycle-safe structural equality and structural proof that retained factories emit one begin/complete pair, defaults only, and no session-root initialization. `ApplicationAttachmentTest` invokes an attachable source factory from both a later Lyra evaluation and Java, and deterministically cancels a retained constructor after an observable root effect while checking publication, recovery and root lifetime | Two independent emitted-field seeds × 24 cases by default and 1,800 extended. Each seeded case constructs through Lyra source, checks independent primitive/default/required-field values and performs mutable writes through a source callable. Deterministic tests cover target-once replacement, existing-bound-callable preservation, fresh equality contexts, nullable/cyclic/nested structs, match equality, class leaves, class identity, complete packaging/Java construction and sessions. |
| Excluded syntax | Destructuring/type patterns, exception forms, bitwise syntax, `Any`, invalid calls/modifiers | Grammar/token mutations; excluded syntax never counts as a successful new language feature |

## Fuzzer architecture and oracles

`LanguageFuzzTest` runs four fixed seeds (`1`, `24301`, `8675309`, and
`9223372036854775807`), 180 cases each. Twelve families rotate, so the default
720 cases include every family, every numeric kind, and every retained nominal
profile/operation. Each numeric program is invoked
with six independently chosen argument pairs in both equivalent operator spellings.
The case count therefore differs from the much larger invocation/assertion count.

1. **Typed expressions:** bounded recursive trees of arithmetic, blocks, arrays,
   tuples, lambdas, conditionals, match expressions and nil coalescing. The test-only tree evaluates
   against `NumericModel`; production execution is compiled Java 25 bytecode.
   Integers use exact mathematical domains and per-operation range checks. F32
   rounds at every node. Both renderings must return the exact expected boxed value
   (including signed zero) or the expected source-mapped runtime error. Each program
   also checks a randomly selected explicit numeric conversion with the same six
   input values against independent range/finite/integrality checks.
2. **Stateful source:** random array mutations, aliases, scalar rebinding, compact
   functions, shared captures, shadowing and short-circuit effects. A Java state
   model predicts the result. Repeat invocations and independent instances detect
   accidental retained local state.
3. **Corpus mutation:** edit code points of accepted and rejected source fixtures.
   Rejection must be structured and deterministic; acceptance must produce
   byte-identical artifacts that load without initialization. An accepted mutation
   is not assumed semantically equivalent to its seed.
4. **Grammar fragments:** generate bounded nested forms, names, modifiers, types,
   delimiters, literals and comments; assert phase failure/compilation invariants.
5. **Module graphs:** randomized values and dependency depth, three import forms,
   reference result, and byte-identical artifacts despite reversed resolver order.
6. **Bytes/lexer:** arbitrary byte arrays and valid BOM/Unicode examples, differential
   UTF-8 decoding, exact token spans and EOF, then compiler invariants for valid text.
7. **Runtime actions:** mutate/invoke/fail/authenticate/check ownership/close against
   an explicit state model. Invalid authority must never execute a forged callback
   or mutate guarded state.
8. **Artifacts:** damage required metadata or class structure, require structured
   refusal before initialization, then verify recovery with the original artifact.
9. **I/O:** random Unicode payloads and a fixed independently modeled line sequence
   through every I/O intrinsic, checking each stream's exact content.
10. **Match:** value and conditional matching in both spellings, with independently
    modeled subject/pattern/guard effects, lazy result selection, falsey/nil conditions,
    checked numeric boundaries and selected runtime failures. Saved replay cases retain
    exact source, runtime arguments, expected values and error categories. The five
    nested-direct-match counterexamples under `language/replays/match-postfix/` are
    preserved byte-for-byte and replayed by `MatchFuzzRegressionTest` in the core suite.
11. **Loops:** generated reusable signed ranges, both endpoint and call spellings,
    zero/one-argument callbacks, and a while predicate that counts every test.
    Independent Java arithmetic predicts traversal sums and the final false test.
    Exact source and expected results are saved in the ordinary replay format.
12. **Retained nominal transfer/certification:** every case drives a real
    three-generation session through the authenticated storage domain: producer
    compile/certificate issuance/publication, retained construction, and
    observation. Ten deterministic profiles cover every closed transfer variant
    (values, references, lambdas, direct/namespace/callable calls, composites,
    applies, alternatives, sequences, projections, nested construction, loops),
    cross-generation identities (`eq?` alias preservation, shared producer
    storage, per-construction fresh composites, same-slot stability, distinct
    instances), block and factory shadowing, saved-versus-current callable slots,
    constructor/default effect ordering, runtime-failure publication and
    recovery, the four pinned issue-#7 `LYR-LINK` Unit/intrinsic observation
    shapes (which must keep failing structurally, never silently succeed), and
    negative certification evidence: forged object routes, forged/mismatched
    `RetainedNominal` inventories, and mismatched storage/factory link
    capabilities must all be rejected. Expected values, variant kinds, shapes
    and codes come only from the test-only model; every legal producer must also
    keep the fail-closed `retainedInitializerDiagnostic` preflight guard silent.
    The annotated nilable-member read gap (issue #8) stays a documented open
    limitation: annotated reads and nil-contract-consuming forms over member
    reads are rejected with the structured `LYC-IR-003`, while bare reads and
    the retained nilable-element index cases work.
    The balanced minimum campaign is now 120 cases (twelve modes × ten numeric
    kinds); the ten retained profiles rotate so the minimum budget already
    executes every profile and every operation name in each seed. The driver
    fails any run whose summary zeroes or drops a retained operation, profile,
    category or family count.

This is deterministic generational, mutation, differential, metamorphic and state
model fuzzing. It does not use coverage-guided instrumentation or claim exhaustive
path coverage. Fixed seeds provide a stable regression gate; extended runs should
also use new recorded seeds. The compiler's constant evaluator, arithmetic helpers,
type predicates and actual result must never become the reference expectation.

## Budgets, reproduction, and reduction

| Property | Default | Meaning |
|---|---|---|
| `lyra.fuzz.seeds` | Four seeds above | Comma-separated signed Java long values; decimal/hex accepted |
| `lyra.fuzz.cases` | `180` | Cases per seed; minimum `120` preserves all twelve families, every numeric kind, every retained profile and operation; maximum `1000000` |
| `lyra.fuzz.caseTimeoutSeconds` | `20` | Deadline between saved case checkpoints; allowed `1..300` |
| `lyra.fuzz.minimizeAttempts` | `12` | Maximum fresh-worker reduction attempts for mutation/grammar/byte failures; `0` disables reduction, never testing |
| `lyra.fuzz.replay` | unset | Absolute path to a self-contained replay properties file |
| `lyra.sessionFuzz.seeds` | `7,83,137` | Nonempty, unique persistent-session seeds; malformed lists fail testing |
| `lyra.sessionFuzz.steps` | `48` | Steps per session seed; `12..10000`, with observations after every step |

The compiler campaign also has a total deadline of `max(120, cases * 2)` seconds.
The parent redirects output to a file to avoid pipe deadlocks and forcibly retires
the worker and its descendants on timeout. Session workers have a total deadline
of `max(45, steps * 2)` seconds. None of these budgets changes production runtime
policy.

Before executing a case, the worker saves `current.lyra` and `current.properties`
under `lyra-compiler/target/language-fuzz/seed-*/`. The properties file includes exact
source, module sources or byte payload, arguments, oracle values/error codes, mode,
seed, index and Java version. `worker.log` retains the exception and
`summary.txt` records successful family counts. Failed cases remain on disk after
Maven exits; archive them before `mvn clean`. Paths and replay commands appear in the
test failure. Replay reads the saved input, not a regenerated approximation.

Mutation, grammar and byte failures receive bounded delta debugging in new JVMs.
A smaller candidate is retained only when it reproduces the same exception class,
normalized summary and stable diagnostic codes (or the same timeout). The original
is preserved, and the final candidate goes in `minimized/current.properties`.
Typed/state/module/I/O/artifact counterexamples preserve their complete oracle and
input for replay; automatic deletion would generally invalidate that oracle, so
they are not automatically reduced. Each reduction attempt has a five-second bound.

Session transcripts under `lyra-repl/target/session-fuzz/seed-*/worker.log` record
every submission, query and reset before execution. Failure output includes the
seed and step-budget command. Preserve the transcript and repository revision for
reproduction, and turn a failing sequence into an ordinary named regression test.
State histories reset every 20-step operation cycle because observation submissions
also consume the default 256 source-record budget. A separate small-budget scenario
asserts structured admission refusal without publication and capacity recovery
after reset. Its existing session `LYC-EMIT-001` budget diagnostic is distinct from
an emitter failure compiling valid source through the ordinary compiler API.

## Extending the suite with a language feature

Every feature change must update this suite in the same change:

1. Update the living specification and this coverage matrix to state the accepted
   syntax, static contract, evaluation order, JVM representation and failure behavior.
2. Add readable positive `.lyra` fixtures that compile, load and execute, and negative
   fixtures with exact diagnostic codes. Add boundary cases and a source-mapped
   runtime failure where relevant. Do not record a compiler invariant or an emitter
   failure as the expected outcome of valid source.
3. Extend the generator and its independent oracle or state model. Add legal
   compositions with existing features, malformed near misses, extreme values and
   short-circuit/side-effect interactions. Extend artifact, authority, session and
   I/O checks when the feature crosses those boundaries.
4. Update primitive/operator/built-in/IR inventory guards and the associated executed
   assertions. Do not weaken/remove an inventory guard to make a feature pass.
5. Preserve minimized failures as permanent corpus fixtures or focused JUnit tests.
   Fix their cause, rerun the saved case, then run `mvn test` and an extended campaign
   with the failing seed and additional seeds. Use the Phase 24 audit for release.

Corpus files start with one tab-separated comment line:

```text
// feature<TAB>outcome<TAB>return-type<TAB>expected<TAB>description
```

`outcome` is `value`, `reject`, or `throws`. Successful/runtime cases export
`run :Fn<;ReturnType>`. Value expectations use Java's scalar string representation
(the Unit-return MethodHandle result is `null`); explicit Lyra formatting should
return `String[value]`. Rejected fixtures use `-` as return type and an exact
`LYC-<PHASE>-<NUMBER>` expected code. Runtime fixtures use an exact `LYR-*` category.
Fixtures are discovered recursively and sorted; no hand-maintained filename list
can silently omit a new fixture.

For expression examples, remember that parentheses denote a callable application;
`(::f[])` tries to call the value returned by `f`, so parentheses cannot group a
direct call. Whitespace alone does not stop a postfix accessor. In a comma-capable
argument or operand list, adjacent sibling expressions that begin with `::` need
a comma so the second call does not attach to the first call's result:

```lyra
(+ ::left[], ::right[])
+[::left[], ::right[]]
```

Without the comma, the grammar reads the second call as
`(::left[])::right[]`, and the enclosing operator consequently has one operand.
Use `(f)` for a zero-argument callable-value call. Where commas are not available,
separate neighboring direct calls with declarations or block boundaries.

## Security boundary

The suite provides regression evidence for compiler robustness, type/bytecode
correctness, loader validation, lifecycle and authority checks. Fuzzing cannot prove
absence of vulnerabilities, and random mutations are not an adversarial sandbox
audit. Lyra still executes trusted code with the host process's authority; raw Java
arrays retain their documented live mutation behavior, and enabled loopback REPL
access remains unauthenticated. Production execution of untrusted programs needs a
separately specified process/capability/resource boundary. Test-worker memory limits
and timeouts are test infrastructure, not production isolation guarantees.
