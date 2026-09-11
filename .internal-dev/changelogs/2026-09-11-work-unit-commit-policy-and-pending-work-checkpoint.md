# Commit pending repository work and require work-unit commits

## Date
2026-09-11

## Git Commit

4a865f666521923217a68b1f9a9ffc99656428a4

## Change Summary
Owner explicitly requested committing the completed match feature and all other pending repository work. Added a mandatory per-change/phase/work-unit Git commit policy to AGENTS.md, including honest unfinished checkpoints, validation/diff review, scope preservation, and no implicit push authority. Added a narrow ignore for generated Java output under external/generated/; the existing generated class remains on disk and is not source work.

The commit also checkpoints the previously pending editor, conformance/fuzz infrastructure, CLI/REPL fixes, development records, examples/tooling, legacy prototype removals, and existing empty main file. This checkpoint does not newly certify every historical or unfinished record as complete; the match feature's separate changelog records its completed scope.

## Files
- AGENTS.md
- .gitignore
- .internal-dev/specifications/decisions.md
- .internal-dev/changelogs/2026-09-11-work-unit-commit-policy-and-pending-work-checkpoint.md
- All previously pending tracked/untracked repository work, as expressly requested, excluding ignored generated build output.

## Behavioral Impact
Future completed work units must be committed before moving on or reporting completion. Unfinished checkpoints must describe their actual status. No source-language or runtime behavior is changed by the commit policy itself; pending code changes retain their existing implementation records.

## Specification Impact
Workflow contract updated in AGENTS.md and decisions.md. No additional language/runtime contract changes beyond the already documented match feature and pending editor work.

## Risks
This is an explicitly authorized combined checkpoint of accumulated changes, not a narrowly isolated match-only commit. Existing development plans may describe unfinished work and remain historical/intent records rather than new completion claims. Generated output is excluded, and the staged credential-signature scan found no matching signatures. The owner subsequently explicitly requested pushing this checkpoint.

## Follow-up Items
The match implementation already passed the complete Maven suite and 9,000 extended fuzz cases. Staged whitespace checks passed; two trailing blank lines in pending development records were removed. A redundant pre-commit Maven rerun was interrupted at the owner's direction; no new validation result is claimed. Commit-policy edits are documentation/ignore-only. Commit and push the checkpoint as explicitly requested; future units follow the new AGENTS.md policy.
