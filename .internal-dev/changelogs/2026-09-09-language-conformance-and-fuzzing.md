# Language Conformance and Runtime Fuzzing

## Date
2026-09-09

## Git Commit
4a865f666521923217a68b1f9a9ffc99656428a4

This records the existing HEAD; the implementation and unrelated pre-existing worktree changes remain uncommitted.

## Change Summary
- Added 211 readable source corpus fixtures: 122 successful executions, 81 exact-code rejections and eight source-mapped runtime failures. Each fixture is independently reported by JUnit and reused as a mutation seed.
- Added all-primitive scalar/array/nullable ABI assertions, all 100 explicit numeric conversion pairs, all 100 implicit acceptance/rejection pairs, numeric boundary cross-products and every integer index domain. U64-to-float regressions additionally cover rounding-tie parity, adjacent values and 2,048 deterministic random inputs per destination.
- Added exact primitive/operator/intrinsic-export inventory guards and an observed-versus-permitted sealed IR node inventory guard.
- Added nine deterministic compiler/runtime fuzz families: typed expressions, state, corpus mutations, grammar fragments, module graphs, arbitrary bytes, runtime authority/lifecycle actions, malformed artifacts and Unicode I/O. Normal Maven tests run four seeds with 180 cases each (720 cases); numerical programs have six argument pairs, two equivalent renderings and a randomized explicit numeric conversion each.
- Added isolated worker JVM limits, per-case and campaign deadlines, retained exact-source/argument/module/byte replay records, coverage summaries and bounded failure-preserving reduction. Infrastructure tests assert timeout cleanup, Unicode replay, incorrect-oracle detection, and text/byte reduction that preserves the original failure.
- Added three seeded persistent-session model campaigns with 48 actions each, retained submission/query/reset transcripts, per-step state observations, source-budget admission checks and reset recovery.
- Added `tools/fuzz-language.sh`, the detailed developer coverage/maintenance/replay guide, and mandatory update rules in AGENTS and both living specifications. All baseline tests are discovered by ordinary `mvn test`; no opt-in fuzz profile or extra dependency was introduced.

## Files
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/conformance/` and `lyra-compiler/src/test/resources/language/corpus/`.
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/ir/TypedIrTest.java`.
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/SessionStateFuzzTest.java`.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/backend/jvm/JvmBytecodeEmitter.java`.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`.
- `tools/fuzz-language.sh`, `docs/language-testing.md`, `README.md`, `AGENTS.md`.
- `.internal-dev/specifications/language-core.md`, `.internal-dev/specifications/backend-runtime.md` and `.internal-dev/knowledge/language-conformance-and-fuzzing.md`.

## Behavioral Impact
The new suite exposed and now guards five compiler defects:

1. U64 multiplication emitted `Long.divideUnsigned` with a comparison-shaped `(JJ)I` descriptor, producing invalid bytecode. Its descriptor is now `(JJ)J`.
2. U8/U16 division and reciprocal converted signed Java carriers directly to floating point. Their payloads are now normalized before conversion.
3. Then-only conditional expression lowering attempted to convert the selected result into Unit. It now discards the selected value and produces Unit; tail lowering preserves ordinary Unit self-tail calls. Regression fixtures cover scalar/reference/nullable discarded values, false branches and 100,000 Unit tail calls.
4. Flow analysis omitted fresh allocations referenced only by ownership requirements. Canonical allocation registration now includes those requirements, allowing valid local immutable-array aliases to mutate through an `@mut` symbol.
5. U64-to-float conversion rounded an intermediate value before adding 2^63, allowing an incorrect final rounding. It now preserves a sticky low bit before a single target-precision conversion and exact doubling. Seed 42's U64-to-F64 failure is retained as a source fixture, with independent runtime-argument rounding matrices for F32 and F64.

The persistent-session campaign initially reached the existing 256-record source-history cap because every observation is a submission. That is intentional admission control, not an array-identity defect. State campaigns reset at least every 24 steps and test deliberate capacity refusal separately. Production source-history limits remain unchanged.

## Specification Impact
Updated only the validation/maintenance contracts in `language-core.md` and `backend-runtime.md`: corpus, independent oracles, fuzz campaigns, inventory guards and feature-update duties are mandatory core tests. Compiler repairs implement existing language semantics. Runtime trust, authentication, raw-array escape behavior and public APIs are unchanged.

## Validation
- Full reactor `mvn test`: **1,363 tests, zero failures/errors, two opt-in graphical editor tests skipped; BUILD SUCCESS**. All runtime, compiler, REPL, CLI and editor modules passed.
- Final extended compiler/runtime campaign: the four default seeds plus 42 and 99, 540 cases each (3,240), passed with all nine families represented in every seed.
- Saved U64-rounding failure replay, all 211 corpus fixtures and all 30 numeric matrices, passed together (242 JUnit tests).
- Earlier extended campaigns of 2,160 and 1,800 cases and saved properties replay through `tools/fuzz-language.sh`, passed.
- The full suite also includes the five fuzzer infrastructure assertions and the default compiler/runtime and persistent-session campaigns.
- Explicit malformed and duplicate session-seed commands were rejected with a failing build, confirming that configuration cannot silently omit the campaign.
- `bash -n tools/fuzz-language.sh`, help output, testing-guide relative links, and `git diff --check`, passed.
- Environment: OpenJDK 25.0.4+7 (Red Hat), Maven 3.9.11, Linux amd64; worker execution uses `--enable-preview -Xverify:all -Xmx384m -XX:MaxMetaspaceSize=192m`.

## Risks
Fuzzing is finite regression evidence, not proof of vulnerability absence. This suite is generational/mutation/differential/state-model fuzzing and does not claim coverage-guided instrumentation. Disposable-worker budgets protect the test process; production hostile-code isolation and REPL authentication remain outside the current runtime contract. The new campaigns add intentional test runtime. Failure checkpoints/transcripts live under target and should be archived before cleaning.

## Follow-up Items
Keep fixtures, generators, independent oracles, inventory guards and `docs/language-testing.md` synchronized with every feature change. Promote future reduced failures to the permanent corpus or focused JUnit tests. Use the unchanged Phase 24 audit before release; performance benchmarks and graphical integration checks were not part of this language-suite implementation validation.
