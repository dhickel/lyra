# Phase 03 Validation Review

## Scope

Validated the existing Phase 03 JVM/session worktree at `/home/dhickel/Code/Java/lyra` without committing or spawning agents. The review covered prepared graph emission, exact producer-qualified session links, module lifecycle cleanup, source-capacity preflight, staged publication, retry behavior, imported aggregate/callable execution, intrinsic `std->io`, and ordinary AOT behavior. Unrelated worktree edits and deletions were preserved.

## Findings

- Ordinary AOT factory initialization had been emitted by lexical module-state order instead of the canonical initialization order. The emitter now uses the validated order while retaining slot lookup by module identity.
- Prepared session artifacts allocate and register one graph of state shells, defer source execution until the guarded submission entry point, link only emitted new modules, and use exact external storage accessors for retained modules.
- Session graph source capacity is checked before loading or execution, and retained source revisions are keyed by source identity plus SHA-256 so changed retry revisions consume bounded capacity without replacing prior evidence.
- Generated cleanup now retires initializing or failed prepared states without weakening the ordinary public lifecycle close contract.
- Imported callable aggregate mutation was incorrectly diagnosed as caller-side imported ownership even when the callable captured state owned by its own producer module. Flow validation now qualifies that exception by the target callable producer; mutable caller-owned aggregate arguments remain rejected.
- Runtime failure retains attempted producer/source context without publishing staged names or registering uninitialized storage. Identical failed imports retry their initializer rather than reusing a failed resident.
- A focused session integration test executes intrinsic `readLine`, then `println` and a following submission through one configured `RuntimeIoEnvironment`.

## Risk Assessment

The validated Phase 03 path is green under the focused and full Maven suites. The worktree still contains unrelated pre-existing edits/deletions and untracked internal-development material; those were not normalized. Later Phase 04+ features such as reload, application attachment, and safe-point service remain outside this validation.

## Recommendations

Keep the graph-wide preflight and producer-qualified ownership checks coupled to new integration tests. Do not reintroduce source-local or module-count guards as a shortcut for runtime linkage.

## Follow-ups

- Preserve the exact producer/lifetime evidence when implementing reload and application-root borrowing in later phases.
- Reconcile any historical backend-runtime wording that still describes imported graphs as outside the certified profile with the current Phase 03 trusted prepared-graph contract.
