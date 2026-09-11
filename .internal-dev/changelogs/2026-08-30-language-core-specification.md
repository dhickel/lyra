# Lyra core language specification established

## Date

2026-08-30

## Git Commit

c02851d4a3a56ac652079724664e7de2224c6295

## Change Summary

Established Lyra's first living source-language contract after an iterative project-owner decision session. Recorded durable rationale, current/deferred boundaries, and specification ownership.

## Files

- `.internal-dev/specifications/language-core.md`
- `.internal-dev/specifications/decisions.md`
- `.internal-dev/specifications/deferred-features.md`
- `.internal-dev/specifications/index.md`
- `.internal-dev/changelogs/2026-08-30-language-core-specification.md`

## Behavioral Impact

No compiler/runtime code changed. Intended language behavior is now explicit for typing, modifiers, nilability, functions, calls/accessors, evaluation, operators, arrays, tuples, modules, diagnostics, and invocation failures.

## Specification Impact

Created `language-core.md` as the normative current-scope language specification. Added supporting durable-decision and deferred-feature records and indexed their ownership boundaries.

## Risks

- The current lexer, grammar, parser, AST, and smoke tests do not conform to much of the new contract.
- Older `grammar_spec.md` and `notes.md` remain stale prototype evidence and may mislead readers unless implementation/documentation work clearly points to the living specification.
- Backend, host interop, lifecycle, and engine behavior remain intentionally unspecified.

## Follow-up Items

- Run the separate backend/internals owner-decision session.
- Reconcile lexer/grammar/parser/AST behavior with `language-core.md` in small tested slices.
- Design user-declared classes/records/variants in their planned later language sprint before promoting deferred syntax.
