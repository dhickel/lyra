# Deferred Submission Publication Repair

## Date

2026-09-06

## Git Commit

`c26357b87db5c25cb9b7d44f6fb5f4e278160395` (dirty-worktree baseline; no commit created).

## Change Summary

Fixed the confirmed Phase 01 defect where a deferred submission could initialize an early binding, fail later, and still register/publish the early binding. The runtime-owned module handle now records successful deferred execution only after the complete generated entry point returns normally. Registration and commit both require that publication eligibility.

The producer lifecycle remains OPEN after failed or cancelled deferred execution. It retains completed effects, initialized storage needed by escaped values, and existing closure authority until the normal session/root lifetime retires it. No compiler-emitter, module-graph, remote, CLI, reflection or private-field workaround was needed.

The previous `PreparedSubmissionTest` assertion that registered an early binding after a later runtime failure contradicted the existing staged-publication contract. It now asserts rejection while retaining the original checks for usable initialized escaped values and ordinary AOT initialization failure.

## Files

- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntime.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/runtime/SessionRetentionTest.java` (preserved and extended the already-untracked Phase 01 test)
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/PreparedSubmissionTest.java`
- `.internal-dev/knowledge/repl-callable-linkage.md`
- This changelog.

`ModuleLifecycle.java` and `JvmBytecodeEmitter.java` were inspected but not changed by this repair. All pre-existing dirty/deleted/untracked work was preserved.

## Behavioral Impact

- An early initialized binding from a failed or cancelled deferred submission cannot become a new `SessionStorageDomain.Binding`, regardless of retention kind.
- Commit rechecks whole-submission publication eligibility before mutating the workspace map or revision.
- Completed writes to prior cells, source-local escaped closures and their original captured cells remain usable; rejected registration does not close the failed producer.
- Root-retained failed producers survive workspace reset/detach/reopen without acquiring publication authority, and root close still retires their closure authority.
- Uninitialized bindings remain guarded independently. Successful submission publication, rejected duplicate execution, exact typed access, owner checks, producer lifetimes and ordinary AOT behavior remain intact.
- `RuntimeException`, cancellation and host `Error` unwinding never set successful publication eligibility. `Error` objects remain unwrapped and unchanged.

## Specification Impact

Specification Impact: none. This repair enforces the existing failure/cancellation publication and escaped-value contracts in `specifications/repl.md` without changing ordinary backend/runtime semantics or the public API.

## Validation

Java/Maven used OpenJDK 25.0.4 on Linux, `/usr/lib/jvm/java-25-openjdk`.

- Red regression: `mvn -pl lyra-compiler -am test -Dtest=SessionRetentionTest#failedAttemptDoesNotExposeAnUninitializedStorageLocation -Dsurefire.failIfNoSpecifiedTests=false` failed before the repair because registration of the earlier `ready` binding threw nothing.
- Focused reactor: `mvn -pl lyra-repl -am test '-Dtest=Session*Test,PreparedSubmissionTest,RuntimeFoundationTest,RuntimeControlTest,PersistentCallableTest,PersistentAggregateTest' -Dsurefire.failIfNoSpecifiedTests=false -Dlyra.preview.jvm.args=-Xverify:all` passed 94 tests with zero failures/errors/skips.
- Full reactor: `mvn test` passed 729 tests with zero failures/errors/skips, including AOT, callable/aggregate linkage, external Java consumption, and existing CLI/remote tests.
- New regressions cover early initialized bindings, later uninitialized bindings, completed old-cell writes and escaped captured-cell mutations, cancellation after generated loop entry, unsuccessful result/retry rejection, success followed by rejected retry, root retention across workspace reopening, and unchanged injected `AssertionError`, `OutOfMemoryError` and `LinkageError` propagation.
- `git diff --check` passed; task diffs were reviewed against pre-repair copies rather than treating unrelated dirty work as part of this repair.

## Risks

Error tests inject existing Error instances through a test-only, exactly typed authenticated closure; they deliberately do not exhaust actual host memory or damage JVM integrity. No known unfinished in-scope defect remains. Imported graph semantics, application attachment and other Phase 01/REPL completion work are not claimed complete by this repair.

## Follow-up Items

None for this defect. No commit was made. The current session is a first-generation senior: a control-enabled nested senior launch was denied by the harness, so the repair was completed directly with one bounded read-only helper, not deferred to another senior.
