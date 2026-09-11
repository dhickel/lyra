# Nominal resolution progress (implementation continues)

## Date

2026-09-11

## Git Commit

`8aaf3dc4df8d51cf99810c45217b74de932d1d05` (baseline).

## Change Summary

Added compiler nominal types, immutable ordered schemas and a closed recursive
schema environment. Resolver collection issues source/module-revision identities,
collects member/constructor contracts and publishes reciprocal nominal declaration,
receiver, member and lambda links. This is a tested implementation slice, not full
feature completion; work continues through typing, flow, JVM and sessions.

## Files

- Compiler types: NominalType, NominalSchema, NominalTypeEnvironment and LyraType.
- SemanticResolver, ResolvedNominal, ResolvedSemanticGraph, topology and lexical
  lookup validation, declaration/scope/mutation inventories.
- NominalSemanticsTest, NominalTypeTest, CallableSummaryTest and extended fuzz tool.
- Language/grammar status, active nominal plan, knowledge and testing coverage.

## Behavioral Impact

Resolution recognizes unary/non-unary construction, named/qualified type contracts,
required struct parameters, class constructor signatures, recursive struct data,
private class access, field mutability and receiver captures. Self is an immutable
binding with receiver mutation permission, not a rebindable shared cell. Nominal
redefinitions retain distinct identities. The earlier blanket resolver rejection
is removed; resolver-only success is not source-to-JVM execution evidence.

## Specification Impact

Accepted language semantics are unchanged. Implementation status now distinguishes
initial declaration/member resolution from pending definite initialization,
contextual replacement receivers, field/callable flow and executable support.
`Nominal<identity-hash>` is the compiler canonical reference spelling; runtime ABI
schema authentication/versioning remains part of the subsequent backend work.

## Validation

Focused nominal syntax/type/resolution and callable summary tests passed. The full
`mvn -q test` reactor passed, including default bounded fuzz, REPL, CLI and editor
tests. `tools/fuzz-language.sh -q` passed with 1,800 cases per seed, including the
four independent nominal field/constructor models and existing source/runtime
campaigns. `git diff --check` passed. No release audit or graphical tests were run.

Initial full runs found a tuple-write classification regression, also reduced by
fuzz seed 8675309, case seed 1995688059202936941, index 46, to
`let @mut x = Tuple[1 2] x:.0 := 3`. It retains its existing TYPE012 conformance
regression and original/minimized transcripts under target/language-fuzz. A second
failure exposed an order-dependent test helper selecting an import alias rather
than the original LET callable; origin selection is now explicit and all effect
assertions remain intact.

## Risks

Full classes/structs are still unfinished: constructor definite initialization,
contextual replacement, exact field/callable provenance, typed operations, direct
JVM emission, artifact ABI and persistent objects remain required. No executable,
release, graphical, benchmark or independent-audit claim is made.

## Follow-up Items

Continue implementation immediately after the validated commit, through all active
plan gates. Checkpoints are not stopping points for the owner's finalization goal.
