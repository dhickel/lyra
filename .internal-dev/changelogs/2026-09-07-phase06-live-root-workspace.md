# Phase 06 live root workspace and retained context

## Date

2026-09-07

## Git Commit

f608914bd5d089ad38f795cd22ceb362ccf7beb6

## Change Summary

Implemented owner-thread `ApplicationAttachment` over an existing open attachable root. The attachment uses exact typed accessors and the root structural domain, preserves live public-root reads and writes, borrows application dependencies, and retains root-held values across scratch reset/service close/reopen until root close.

Added conservative attachable producer-backed flow facts for root/session transfers, exact retained callable summaries and capture validation, failure/cancellation publication behavior, duplicate-service rejection, independent-root isolation, and root-close invalidation.

## Files

- `lyra-repl/src/main/java/io/mindspice/lyra/repl/ApplicationAttachment.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/ApplicationAttachmentTest.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/AttachedValueLifetimeTest.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/AttachableCompileResult.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/AttachableRootContext.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/LyraCompiler.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionCompileRequest.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/LyraRuntime.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`

## Behavioral Impact

Public mutable root state is accessed through the original live root instance. Scratch state is reset independently, while root-backed values and structural types remain valid through attachment service close and reopen. Borrowed application dependencies cannot be reloaded or closed by scratch work.

## Specification Impact

None. This implements the accepted Phase 06 contracts without changing ordinary language or AOT semantics.

## Risks

Retention is deliberately conservative and cancellation remains cooperative. Generated application polling and safe-point dispatch remain later Phase 07 work.

## Follow-up Items

Continue Phase 07 generated application polling, admission and cancellation, preserving exact root/session producer links and root lifetime rules.
