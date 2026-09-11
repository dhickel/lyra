# Value and conditional match expressions

## Date

2026-09-11



## Git Commit

4a865f666521923217a68b1f9a9ffc99656428a4



## Change Summary

- Implemented `(match subject ?? pattern [when guard] -> result ... ?? _ -> fallback)` and equivalent `::match[...]`.
- Added conditional `match _` arms with existing truthiness; both modes require a final unguarded wildcard and preserve lazy source-ordered selection.
- Integrated explicit match data through lexical/grammar/AST, resolution, type and structural-context inference, callable/ownership flow, source provenance, sealed IR/evaluation metadata, and Java 25 bytecode.
- Added allocation-free primitive branch paths, safe constant integral lookup switches, and direct self-tail lowering through match results. No runtime matcher ABI or optimizer settings.
- Repaired cross-width unsigned comparison conversion, aggregate contextual result inference, reserved direct-match expression boundaries, and namespace-path composition. Preserved all five extended-fuzz counterexamples byte-for-byte as permanent replay tests.
- Root independently passed `mvn test` (1,445 tests, zero failures/errors, two opt-in GUI skips), five-seed extended fuzz (9,000 cases), and `git diff --check`.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/lex/{Lexer,TokenKind}.java`
- Compiler `grammar/{GrammarMatcher,GrammarProgram,ProductionKind}.java`, `parse/Parser.java`, and `ast/{SyntaxNode,SyntaxVisitor}.java`.
- Compiler `semantic/{SemanticResolver,ResolvedTopologyValidator,TypeChecker,StructuralContextPlan,TypedMatch,TypedExpression,TypedExpressionKind,TypedSemanticGraph,TypedSemanticProvenance,SemanticFlowAnalyzer,SyntaxLinkKind}.java` and `semantic/flow/{CallableSummaryCompiler,NormalizedExpression,TypedExpressionNormalizer}.java`.
- Compiler `ir/{IrNode,IrVisitor,IrEvaluationOrder,TypedIrBuilder,IrValidator}.java` and `backend/jvm/JvmBytecodeEmitter.java`.
- `lyra-cli/src/main/java/io/mindspice/lyra/cli/LyraHighlighter.java` and `lyra-editor/src/main/java/io/mindspice/lyra/editor/LanguageService.java`: keyword highlighting.
- Focused lexer/grammar/parser/type/callable/IR tests, `backend/jvm/{MatchBoundaryIntegrationTest,Phase23StructuralBytecodeTest}.java`, conformance generators/oracles/inventory/replay tests, and `src/test/resources/language/{corpus/match,replays/match-postfix}/`.
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/MatchSessionTest.java`.
- `README.md`, `docs/language-testing.md`, compiler resource `grammar_spec.md`, and living language/backend/deferred/decision specifications.
- Match implementation plan archived under `plans/.archive/match-expressions/`; review and reusable knowledge records updated.

## Behavioral Impact

Subject evaluation happens once. Arbitrary value patterns use typed equality; guards and conditional arms use existing truthiness. Only reached patterns/guards and the selected result execute. Nil equality does not introduce narrowing or bindings. `match` and `when` are now reserved words; existing identifiers with those spellings must be renamed. `_` remains contextual, not an Any value.

Compiler AST consumers must account for match nodes/visitor cases and optional namespace `terminalArrowSpan`, reflecting already-supported qualified-access spellings with or without a terminal arrow. Generated Java/runtime ABI is unchanged. Existing unrelated worktree changes were preserved; the Git hash above is the baseline, not a commit containing this work.

## Specification Impact

Updated `language-core.md` with the accepted syntax, static rules, evaluation and fallback contract; `backend-runtime.md` with direct match control flow and tail/performance requirements; `deferred-features.md` to retain only advanced patterns/bindings and iteration as deferred; `decisions.md` with owner choices and compatibility tradeoffs. Updated grammar and coverage documentation in the same change.

## Risks

Reserved keywords and compiler AST shape changes are intentional source/API compatibility considerations. Primitive performance is backed by bytecode descriptor/instruction/boxing checks and executed tests, not a new throughput benchmark. Two graphical editor tests remain opt-in; graphical integration and the release audit were not run and are not claimed.

## Follow-up Items

No remaining requested implementation blocker. Advanced destructuring/type patterns, match bindings/narrowing, and iteration remain explicitly out of scope. Final evidence is in `reviews/match-expressions-independent-review.md`; root logs are `/tmp/lyra-match-root-final-suite.log` and `/tmp/lyra-match-root-final-fuzz.log`.
