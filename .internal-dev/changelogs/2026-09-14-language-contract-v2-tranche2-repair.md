# Language Contract v2 Tranche 2 Repair

## Date

2026-09-14

## Git Commit

`ac6c84a0ff7284ad8679d0e18d48a6c3bc779950` (current HEAD before the Tranche-2 commit)

## Change Summary

Repaired incomplete cross-phase integration in the existing language-contract v2 migration. Unary signed-minimum magnitude permission now survives the F-expression opening bracket. The dedicated `cond` kind now follows match-like lowering, evaluation-order validation, nominal initialization, callable identity, retained initializer transfer/route derivation, provenance sealing, callback discovery, and structural contextual typing while retaining subjectless ordered lazy semantics.

Aligned editor source history with console behavior: successful reset clears history, failed/busy reset does not, and protocol-v2 `\load` commands never add their command/path because the remote adapter intentionally returns no source text. Reconciled current nominal status, retired-diagnostic wording, backslash commands, REPL/editor history contracts, and Phase 24 coverage wording. Historical records were not rewritten.

A follow-up review pass hardened the CLI test harness: the PTY driver now kills the REPL process group and sets a parent-death signal so an interrupted or timed-out PTY test cannot orphan a live console REPL, the Java-side teardown force-kills driver descendants, and `JLineDistributionIT` gained a fail-fast packaged-jar probe that runs the real distribution REPL with `\help`/`\quit` so a stale jar fails with its actual output instead of a PTY timeout.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/lex/Lexer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/{api/SessionFlowCertificate.java,ir/TypedIrBuilder.java,ir/IrValidator.java}`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/{CallbackLoop.java,NominalInitializationProof.java,StructuralContextPlan.java,TypeChecker.java,TypedSemanticProvenance.java}`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-editor/src/main/java/io/mindspice/lyra/editor/EditorWindow.java`
- `lyra-cli/src/test/resources/jline_pty.py`; `lyra-cli/src/test/java/io/mindspice/lyra/cli/{JLinePtyTest,JLineDistributionIT}.java`
- Focused compiler/editor tests in `LexerTest`, `GrammarMatcherTest`, `Domain11ContextualTypingTest`, `CallableSummaryTest`, `TypedIrTest`, `RetainedNominalFlowCertificateTest`, `NominalBytecodeTest`, and `EditorWindowTest`
- `.internal-dev/specifications/{language-core,repl,editor}.md`, `lyra-compiler/src/main/resources/grammar_spec.md`, `docs/{repl,editor}.md`, and `tools/phase24-repl-coverage.tsv`

## Validation

- Focused compiler set: 231 tests passed.
- Non-fuzz language conformance corpus: 276 tests passed.
- Focused REPL/console/session set: 54 tests passed.
- Focused editor non-GUI set: 22 tests passed.
- Editor non-GUI tests: 22 passed; graphical editor UI tests were not run, per execution constraints.
- Static Phase 24 evidence validation: 191 rows and 711 references resolved.
- Harness hardening: `mvn -pl lyra-cli test -Dtest=JLinePtyTest` — 9 tests passed; `mvn -pl lyra-cli verify -Dit.test=JLineDistributionIT` — 4 ITs passed (including the new packaged-jar backslash-command probe against a freshly built shaded jar); the same verification run also executed 66 CLI surefire tests with 0 failures.
- `git diff --check`: passed.

The repository guidance's `/home/hickelpickle/.jdks/openjdk-ea-25+36-3489` path was unavailable in this environment; focused Maven checks used the system OpenJDK 25.0.4 runtime.

No fuzz test/script, Phase 23 evidence run, or Phase 24 release audit was executed, as explicitly constrained for this tranche. The non-fuzz full reactor qualification passed separately: 1,714 tests passed with 2 display-gated UI tests skipped.

## Behavioral Impact

The compiler now accepts the language-contract-v2 spellings and rejects the removed spellings with structured diagnostics. REPL, CLI, JLine, attach, and editor surfaces recognize only the eight backslash commands; colon-leading units remain Lyra source. Interrupted PTY tests cannot leave live CLI REPL descendants, and stale packaged distributions fail through a bounded command probe.

## Specification Impact

No new language behavior was invented. The repair completes existing language-contract v2 intent: `cond` is the parenthesized subjectless conditional match form; backslash introduces developer commands; nominal declarations are implemented; source-only history records source rather than command text.

## Risks

Broader fuzz, performance, graphical-editor, and release-audit evidence remains intentionally unrun under the tranche constraints. The worktree contains substantial coordinated Tranche-2 migration edits, which remain uncommitted until this closeout.

## Follow-up Items

- Run the final fuzz campaigns, Phase 23 evidence, Phase 24 release audit, and graphical editor qualification during final qualification.
- Continue with Tranche 3 retained nominal member contracts (#7 and #8) and Tranche 4 call homogeneity/performance (#6).
- Keep the callback-loop module-level mutable-write invariant as the separately tracked out-of-scope issue #14.
