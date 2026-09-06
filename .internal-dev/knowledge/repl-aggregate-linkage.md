# REPL Aggregate Linkage

## Topic

Source-local non-callable array/tuple persistence through exact typed bytecode and a session structural loading domain.

## Source References

- `.internal-dev/specifications/repl.md`
- `.internal-dev/specifications/backend-runtime.md`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionTypeLoader.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowAnalyzer.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/api/SessionAggregateLinkTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/PersistentAggregateTest.java`

## Key Takeaways

- Equal generated binary names in sibling loaders are different JVM types. Sharing only structural tuple/function-interface classes preserves their ABI identity without sharing generation state, cells or closure authority.
- Package-private tuple getters cannot cross parent/child loaders even when the package spelling matches. Session-specific public component getters solve that boundary; ordinary AOT getters stay package-private.
- Structural type bytes originally depended on the submitting SourceFile and line table. Session structural classes now have a fixed synthetic source marker and no source-operation line table. First admission checks structural inventory and class-file validity; repeated names require byte-identical definitions. New names are checked as a batch before being recorded.
- `MethodHandle.invokeExact` can fail after earlier source effects if physical type parity is checked only when the binding is first read. Generated constructors now check every linked accessor MethodType before any source executes, including package/loader mismatch.
- Type-only scalar flow is not valid aggregate provenance. Semantic and IR sealing require an array identity. `ArrayIdentity.SessionOrigin` explicitly names the external declaration and contract route without pretending it is a fresh source allocation. Allocation provenance still requires its exact producer FlowSiteId.
- Distinct compatible external origins are may-alias, not must-not-alias. Uncertain routed writes must join the old and replacement state. Tests cover imported ownership introduced through one external alias and mutation attempted through another.
- A callable returning an external tuple must substitute the current routed declaration facts. A single tuple-typed scalar formula drops nested array provenance. The summary declaration resolver substitutes external data aggregates, and the callable-summary extension applies the same current-fact discipline to retained callable targets, captures and shared cells.
- Data-only arrays and tuples can survive a failed/cancelled generation through completed assignment into old storage even after the failed generation closes. This is not true of closures: they retain their producer module lifecycle. Never use this data result to justify closing escaped callable generations.
- The current data authority requires a source-local producing generation in the same session domain. Multi-module consumers of aggregate capabilities are rejected before execution, and imported/partial aggregate contracts remain unsupported. Removing that guard requires retained imported ownership, not merely preserving an artifact loader.
- Reset retires even empty pending link tables by a separate epoch, since namespace revision need not advance on reset. Returned snapshots contain no live generated classes or values.
- `:type` source must not pass through the command path tokenizer. That tokenizer removes quotes and escapes, changing string/character literals and comments. Only file/path commands use shell-like argument tokenization; type queries preserve source text.
- A read-only helper shares the worktree and may report edits made by the root while it was exploring. Its late report is not an independent pre-edit baseline or validation of those edits.

## Project Relevance

The supported aggregate profile recursively admits scalars, arrays and tuples, including callable-bearing arrays/tuples when their callable elements carry producer-certified summaries and exact generation authority. Exact original storage, array identity, shared tuple types, callable identity, ordinary mutation, lexical replacement, failed/cancelled effects, non-executing queries and owner/reset boundaries are exercised by compiler/runtime/API/console tests and a forked Java consumer with `-Xverify:all`.

`mvn test` and `mvn clean verify` passed on Java 25.0.4/Linux. The latter ran 678 unit tests and 3 distribution integration tests, including Linux JLine PTY coverage. Phase 23 measurements and the full Phase 24 audit were not rerun for this partial implementation; REPL release rows remain BLOCKED. Native macOS/Windows validation was not performed.

## Open Questions

The source-local session path now retains callable summaries, per-binding initialization and escaped callable-generation evidence, and preserves producer source frames through the session API. The runtime-only initialized-generation bridge documented in `repl-callable-linkage.md` complements this compiler path; sharing function interfaces alone still does not prove source-level persistence. Imported module/reload, configured/application roots, owner execution/input coordination and debug packaging remain unfinished.
