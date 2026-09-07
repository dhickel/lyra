# Phase 04 Reload Review

## Scope

Root-owned completion and review of the uncommitted Phase04 implementation against the accepted plan's Phase04 and applicable V07/V10/V13 contracts. Baseline: `7245d1402c14b9094d5187b80cbc2f0a04656684`. No commit and no senior escalation. Two read-only exploration reports informed direct root inspection; neither was treated as a formal validator verdict.

Application registration, root-held values across detach/reopen, application safe points, managed consoles, protocol/CLI integration and debug packaging were explicitly outside this pass. The application boundary was tested with real compiler-issued producer records, not an implemented attachment service.

## Findings

**Verdict: PASS for the requested Phase04 standalone reload scope.** No known unresolved defect remains in that path after repair and executed validation.

1. **Repaired: old/new source identity collapse.** Reload previously kept one SourceId/ModuleId for distinct revisions and bypassed historical environment coverage. Fresh reloads and failed-attempt retries now receive distinct compiler source identities while retaining the resolver origin separately. Historical producer records remain independently indexed; every retained source/dependency and old import contract is checked against its exact producer.
2. **Repaired: dependency retargeting through current defaults.** Reused producers are discovery boundaries. Their sealed dependency graphs remain producer-bound instead of being reconstructed through a new logical-default index. Fresh closure discovery still reads each logical candidate once, including diamonds and topology changes, and stops at borrowed application graphs.
3. **Repaired: hidden dependency effect evidence.** EFFECT events are owned by their executing initializer through witness.fromModule, although event.moduleId identifies the effect-site source. Copying eventsAt(module) omitted exact dependency-site events under some fresh-ID orderings. Restoration now carries matching events/effect indexes and validates exact historical semantic/IR site paths.
4. **Repaired: missing dependency cancellation boundaries and incomplete progress.** All session-emitted dependency functions/forms use the active session lease; normal AOT output does not gain these hooks. Scheduled-but-unattempted compile/preflight failures and scratch initializer declarations are reported. Invalid string reload targets now obey busy/closed admission too.
5. **Repaired: source retention and display origins.** SourceRegistry retains actual imported snapshots rather than only hashes. Same-file old/new failures retain distinct text and exact UTF-16 excerpts while mapping public spans to the original URI. Compiler failure diagnostics also map fresh compiler identities back to original source identities.
6. **Repaired: source-capacity accounting after BOM normalization.** Preflight and record append now use the same reserved caller text; a BOM-normalized compiler snapshot cannot consume a second source slot after effects/publication.
7. **Repaired: exact external callable class planning.** A new closure calling a retained imported function uses its exact external access instead of requiring a nonexistent newly emitted producer closure class.
8. **Repaired: compiler reload request shape.** A reload request must contain exactly one namespace import of its selected target and no executable source forms. Missing, multiple or mismatched targets are rejected before discovery. The workspace's baseline revision-conflict guard was restored; the unused reachableFrom API added by an earlier validator was removed.

### Executed evidence

- Focused reactor selection: **51 tests**, zero failures/errors/skips. Includes ModuleReloadTest (19), SessionGenerationLifecycleTest (6), SessionPinnedModuleCompilerTest (14), SessionImportedFlowTest (5), SessionModuleRuntimeTest (6), and PersistentImportTest (1).
- Reload/lifecycle subset: **25 tests**.
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk mvn -q test`: PASS.
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk mvn -q clean verify`: **780 tests**, zero failures/errors/skips. Runtime 15; compiler 575; REPL 146; CLI 44.
- `git diff --check`: PASS.
- Logs and assertion XML: `/tmp/lyra-phase04-review.2LE1eD/`, especially `clean-verify-final.log`, `final-counts.json`, `final-reports/`, and `focused-counts.json`.
- Maven emitted only its existing Guice/sun.misc.Unsafe deprecation warnings.

## Risk Assessment

Retention is deliberately conservative until standalone reset/close; there is no total metaspace bound or forced unloading guarantee. Cancellation remains cooperative and host blocking I/O can delay observation. Failed attempts never become initialized defaults; retry repeats actual initializer effects and reports the attempted/completed prefix.

Whole-application V10 attachment lifetime is not established by these standalone tests and was not claimed. Some repository overview/history text still describes the pre-import or authenticated baseline. The relevant living REPL/backend status paragraphs were corrected; optional remote replacement and broader overview reconciliation remain later accepted plan work.

## Recommendations

Use the fresh compiler source identity for executable/source indexes and the original resolver identity only for display/origin mapping. Keep namespace defaults, historical producer context and runtime initialization/publication separate. Do not reintroduce weakened historical coverage to accommodate same-source revisions.

## Follow-ups

Continue the accepted later attachment, safe-point, console/protocol and deployment phases. Preserve their separate gates; this verdict is not a claim that the entire trusted persistent REPL or root attachment is complete.
