# Phase 06 Attachment Validation

## Topic
Attachable root contexts, persistent application attachment, borrowed callable flow, and root-lifetime retention validation.

## Source References
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/flow/CallableSummaryCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/SemanticFlowFactValidator.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`
- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ApplicationAttachment.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/AttachedValueLifetimeTest.java`

## Key Takeaways
- Session callable solving may need inherited summaries from retained application producers, including transitive borrowed lambdas that are not nodes in the current typed graph. The published `CallableSummarySet` must be narrowed back to the current graph before phase validation; inherited lambda formulas are admitted only through the exact producer certificate and their substituted capture formulas still require recursive validation.
- External root declarations can appear in final flow states even when `TypedSemanticGraph.contract(...)` has no entry for them. Final-state validation must fall back to the resolved declaration's effective contract rather than treating a valid external binding as foreign.
- Root-backed scratch producers and generation resources must use a retention class that survives attachment reset/close but retires when the application root closes. Treating them as ordinary borrowed producers leaves generation handles and loading contexts unretired; a root-owned retention plus a closeable generation record preserves escaped values and closes the module before its artifact context at root shutdown.
- Closure implementation classes are generation-local. Structural tuple and function interface classes are the identities that must be shared across attachment reopenings; tests must not require distinct closure instances to have the same implementation class. Accessors on a closed attachment or closed root registration are intentionally invalid, so tests should capture durable lifetime references before closing and use the reopened registration afterward.

## Project Relevance
These invariants prevent attachable sessions from rejecting valid higher-order application state, weakening semantic-flow validation, or leaking scratch generations across attachment lifetimes. They also keep the runtime's explicit owner and root-lifetime boundaries visible in assertion-grade tests.

## Open Questions
- Future work should decide whether root shutdown cleanup should aggregate multiple resource-close failures rather than stop at the first failure. This was not required to complete the current Phase 06 contract.
