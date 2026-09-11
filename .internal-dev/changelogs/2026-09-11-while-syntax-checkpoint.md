# Date

2026-09-11

# Git Commit

28eceddd9c8f356bec6cbd0085193c322df91583 (baseline)

# Change Summary

WIP: selected and documented while's predicate/action contract under owner
authorization, and added reserved syntax alongside iter. This is not executable
loop support. Shared semantic callback analysis and lowering remain outstanding.

# Files

Compiler TokenKind/Lexer, GrammarMatcher/GrammarProgram, Parser and associated
JUnit suites; editor LanguageService; grammar resource, language-core, decisions,
deferred-features, range-iter plan and knowledge, developer testing coverage.

# Behavioral Impact

Both `(while predicate action)` and `::while[predicate action]` parse with the
same expression-boundary and reserved-name rules as iter. The chosen intended
contract is Fn<;Bool> plus Fn<;Unit>, returning Unit. Callback values are evaluated
once before testing; the predicate executes before every action. No new return,
break, implicit argument or discarded-result conversion is introduced.

# Specification Impact

Added the normative while contract and explicit incomplete execution status.
Corrected the older stale pending-iter-name decision to reference owner approval.

# Risks

Neither iter nor while has complete semantic specialization or executable loop
lowering. While flow must retain effects of the initial and final predicate tests,
as well as repeated action/capture changes. No release readiness is claimed.

# Follow-up Items

Complete phase-02-iter and phase-03-while under the active range-iter plan.

## Validation

- Extended focused syntax suite passed with `-Dlyra.fuzz.cases=1800`:
  LexerTest, GrammarMatcherTest and ParserTest, including 1,800 generated while
  cases at seed 24301 and 1,800 existing iter cases at seed 8675309.
- `mvn -q test`: passed (exit 0), including the mandatory bounded fuzz baseline.
- `git diff --check`: passed. Self-review is recorded in
  `reviews/2026-09-11-while-syntax-review.md`. No release audit was run.
