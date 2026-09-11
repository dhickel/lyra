# Implement value and conditional match expressions

## Context
User authorized full syntax/compiler/JVM implementation, proper tests, and safe low-hanging performance optimizations. This promotes simple value/conditional matching out of deferred scope. Existing worktree changes must be preserved.

## Goal
Deliver equivalent (match ...) and ::match[...] forms through every compiler phase and generated execution.

## In Scope
- Traditional match evaluates subject once and compares arbitrary arm value expressions using typed ==. Optional when guards and guarded wildcard arms.
- match _ tests conditions with existing truthiness, without when.
- First successful arm wins; only reached patterns/guards and selected results execute.
- Mandatory final unguarded ?? _ -> fallback; no arms after it.
- Result contextual typing and compatible branch unification.
- match and when reserved globally by user decision; _ wildcard only in match positions.
- Nil equality but no new bindings, narrowing, type patterns, or destructuring.
- Complete flow/provenance/IR sealing, JVM lowering, tail recursion, source maps, and safe specialized optimization.
- Focused frontend/backend tests, conformance corpus, fuzz generator and independent oracle, docs/spec updates, full tests and extended fuzz.

## Out of Scope
New pattern bindings/narrowing (explicitly withdrawn by user), destructuring/type patterns, user types, runtime Any, public optimization settings, unrelated changes.

## Implementation Steps
1. Root updates living contracts and developer documentation.
2. Senior implementation agent owns production/compiler integration and focused tests. Architecture must preserve source identity, lazy control flow, and selector single evaluation.
3. Separate conformance agent owns corpus and test generator/oracle updates only.
4. Root reviews integration, runs mvn test and extended seeded fuzz, obtains focused independent review and repairs any defects through senior escalation.
5. Record implementation/test evidence and changelog; report remaining blockers honestly.

## Validation
Positive/negative syntax and typing; both modes/spellings; mandatory fallback; reserved names; subject once; lazy side effects/patterns/guards/results; nil/falsey behavior; numeric/aggregate equality; closures/captures/mutation; tail calls; source-mapped failures; bytecode structural performance checks; inventory guards; bounded default and extended fuzz; mvn test.

## Exit Criteria
All in-scope behavior implemented and validated without relaxing invariants or suppressing unsupported-emission failures. Changes and specification impact recorded. No completeness claim before validation.

## Completion Evidence
Completed. Both match modes/spellings have explicit syntax, semantic/flow/provenance, IR, and JVM support. Constant integral dispatch uses safe lookup switches; primitive match paths avoid boxing and tail results preserve constant-stack self calls. Independent review defects (logical unsigned widening and contextual structural inference) and extended-fuzz/parser composition counterexamples were fixed and preserved as permanent tests.

Root validation: `mvn test` PASS (1,445 tests, zero failures/errors, two opt-in GUI skips); five-seed extended fuzz PASS (9,000 cases); `git diff --check` PASS. Full details: `reviews/match-expressions-independent-review.md`. Existing unrelated worktree edits preserved; no commit created. Release audit/graphical integration are not claimed.
