# Date

2026-09-10

# Git Commit

f6e9190fd0a572dd34973ad46289a0f5f86937d2 (baseline)

# Change Summary

WIP: implemented the owner's reserved iter name decision in lexical and grammar
boundaries, parser replay and editor keyword highlighting. Preserved ordinary
call syntax/AST for subsequent semantic specialization. Grammar validation only
allows the reserved token as an unqualified call target, not a binding or value.

# Files

Compiler lexer, grammar validator/matcher, parser, lexical/grammar/parser tests;
editor LanguageService; readable grammar, language-core, decisions, active plan,
range knowledge and coverage documentation.

# Behavioral Impact

`let r = (0..10:1) ::iter[r || ()]` now parses as a range declaration followed
by an unqualified built-in call, with the same boundary across newlines/comments.
The reserved name cannot be shadowed or used as a namespace member. Names such
as iterator and iterate remain ordinary identifiers.

# Specification Impact

Records the owner's confirmed name policy and removes the corresponding open
decision. It does not claim executable iter, callbacks or persistence complete.

# Risks

Execution remains unfinished: semantic specialization and repeated callback
effect certification, loop emission, safe points and range storage still need
implementation. No release audit or executable-iteration claim is made.

# Follow-up Items

Complete the active range-iter phase plan. Validation results are recorded below.

## Validation

- `mvn -q test`: passed (exit 0), including the mandatory bounded fuzz baseline.
- Extended syntax campaign: `mvn -q -pl lyra-compiler -am test
  -Dtest=ParserTest,LexerTest,GrammarMatcherTest
  -Dsurefire.failIfNoSpecifiedTests=false -Dlyra.fuzz.cases=1800`: passed
  (exit 0), including 1,800 generated iter boundaries at seed 8675309.
- `git diff --check`: passed. Self-review is recorded in
  `reviews/2026-09-10-reserved-iter-review.md`.
- The initial focused run found shifted hard-coded lexer vocabulary indices;
  indices were corrected and all assertions retained in the passing runs above.
- No release audit was run; this is an explicitly unfinished checkpoint.
