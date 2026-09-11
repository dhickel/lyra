# Match expressions independent implementation review

## Scope
Read-only independent Luna high review of match compiler integration, followed by root-created executable regression tests. Existing unrelated worktree edits were excluded. Reviewed grammar/parser, typed result/equality inference, semantic provenance/flow, IR, JVM emission, tail paths, source maps, and match test coverage.

## Findings
1. **P1, reproduced:** JvmBytecodeEmitter.emitMatchCondition adapted stored subjects physically rather than applying logical unsigned widening. U8 255 compared with 255I16 selected fallback. Cross-width U16/U32 integer/floating comparisons also need coverage. Same-width unsigned switch cases passed.
2. **P2, reproduced:** TypeChecker.synthesizeStructuralMatch folded defaulted branch types rather than applying contextual numeric inference. A tuple containing `(match 0I32 ?? 0I32 -> 1 ?? _ -> 2U8)` inferred I64 instead of U8, failing use as U8. Preserve independent provenance validation while fixing synthesis.
3. No other concrete defect reported by the independent reviewer. This is not proof of completeness.

## Risk Assessment
Root full `mvn test` initially passed, showing these paths were coverage gaps. Root subsequently added two permanent tests in MatchBoundaryIntegrationTest; a focused run produced exactly the two failures above. The implementation senior hit its 300-turn limit without a final report; existing changes were retained and a second configured Sol xhigh senior repair was launched.

## Recommendations
Use logical conversion helpers for per-arm stored-subject conversion. Align structural match type synthesis with conditional-style contextual numeric rules without weakening sealing. Extend mixed-width/boundary assertions and fuzz coverage.

## Follow-ups
The two review defects were repaired by the second Sol xhigh pass. Root reran `mvn test` successfully after the repair, including the permanent regressions and new match no-boxing bytecode fixtures; log `/tmp/lyra-match-mvn-test-final.log`.

Extended fuzz (five seeds, 1,800 cases each) then exposed a distinct nested direct-match argument parsing defect: `((=> :U32 |value :U32| value) ::match[...])` produced `LYC-PARSE-008` as the postfix parser treated reserved `match` as a receiver method name. All five failing replay directories were preserved under `/tmp/lyra-match-fuzz-counterexamples-k1o3f78m`; source logs remain in `lyra-compiler/target/language-fuzz/`. Third senior pass (Astra xhigh) is repairing and validating this path. Extended failure log: `/tmp/lyra-match-extended-fuzz.log`.

Original focused review failure log: `/tmp/lyra-match-boundary-review.log`; first full-suite success log: `/tmp/lyra-match-mvn-test.log`. Final validation is complete. The third pass corrected reserved direct-match expression boundaries, conditional-versus-namespace lookahead, and optional terminal namespace-arrow handling across matcher/descriptors/AST/replay. Root's additional composition regressions pass. Five original failing `.lyra`/`.properties` pairs are preserved byte-for-byte under `lyra-compiler/src/test/resources/language/replays/match-postfix/` and exercised by `MatchFuzzRegressionTest`.

Root independently reran the final implementation:
- `mvn test`: PASS, 1,445 tests, zero failures/errors, two opt-in graphical editor tests skipped. Log: `/tmp/lyra-match-root-final-suite.log`.
- `tools/fuzz-language.sh -Dlyra.fuzz.seeds=1,24301,8675309,9223372036854775807,42`: PASS, 1,800 cases per seed / 9,000 total, all ten generator families and all ten numeric kinds; no reduced budgets or suppressed invariants. Log: `/tmp/lyra-match-root-final-fuzz.log`.
- `git diff --check`: PASS.

No remaining blocker for the requested match feature. Graphical integration and the release audit were not run; this was feature implementation, not a release certification. No throughput benchmark claim is made; performance assertions inspect actual switch/tail/primitive bytecode and execute the generated code.
