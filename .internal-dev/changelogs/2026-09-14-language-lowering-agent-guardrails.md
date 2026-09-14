# Language and Lowering Agent Guardrails

## Date

2026-09-14

## Git Commit

43ccb06dcdd7218e0158d6595fc56fcc4cfeaf7f

## Change Summary

Strengthened repository guidance so compiler-recognized built-ins never use the `::` prefix, equivalent S-expression and F-expression calls share one proven execution path when no dispatch lookup is required, and future syntax/lowering changes receive complete semantic, IR, authority, bytecode, fuzz, and performance regression coverage. Retained the validated implementation plan for open GitHub issues #6–#13.

## Files

- `AGENTS.md`
- `.internal-dev/plans/20260914-005314-resolve-and-close-lyra-github-issues-6-13-in-dependency/plan.md`
- `.internal-dev/changelogs/2026-09-14-language-lowering-agent-guardrails.md`

## Behavioral Impact

No compiler or runtime behavior changes. Future coding agents must preserve bare built-in syntax, S/F call parity, complete phase integration, structured failures, exact runtime authority, initialization safety, and structural/performance regression evidence.

## Specification Impact

Specification Impact: none. This change strengthens repository engineering policy and records a validated implementation plan without changing the current living language or runtime contract.

## Risks

The guidance intentionally anticipates accepted issue resolutions that are not yet implemented; agents must still reconcile work with the validated plan and living specifications rather than treating the guidance as evidence that implementation is complete.

## Follow-up Items

- Execute the validated #6–#13 plan when authorized.
- Update the living specifications in the implementation phases defined by that plan.
