# Phase 06 Attachment Validation

## Date
2026-09-07

## Git Commit
f608914bd5d089ad38f795cd22ceb362ccf7beb6

## Change Summary
Validated and repaired Phase 06 attachable-root and persistent application-attachment behavior without reverting unrelated worktree changes. Borrowed transitive callable summaries now remain tied to exact producer certificates while published current-graph artifacts stay sealed. External root final-state contracts validate against resolved declarations, and root-backed scratch generations retain and retire at the application-root boundary.

## Files
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySet.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummarySolver.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowFactValidator.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ApplicationAttachment.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/AttachedValueLifetimeTest.java`
- `.internal-dev/knowledge/phase-06-attachment-validation.md`

## Behavioral Impact
- Transitive retained callable values can be validated and reused across attachment submissions without importing foreign summaries into the current phase artifact.
- Root-scope external writes retain their exact binding contracts through flow validation.
- Attachment service reset/close preserves root-held producer values, while root shutdown closes retained scratch generation resources.
- Structural interface identity, rather than closure implementation class identity, is asserted across reopened attachment generations.

## Specification Impact
None. The implementation repairs and validates behavior already required by the Phase 06 attachable-root and persistent-attachment specifications.

## Risks
- Root shutdown resource cleanup currently reports the first close failure; failure aggregation remains a future runtime-hardening question.
- The worktree contains unrelated existing modifications and untracked files; they were preserved.

## Follow-up Items
- Continue the planned Phase 06 review of imported-module persistence and reload only when its accepted specifications become in scope.
