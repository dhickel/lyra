# Diagnostics and runtime failures

Compiler diagnostics are immutable result data. Runtime failures are unchecked `LyraRuntimeException` values. Lyra has no source-level exception handling, so a runtime failure aborts the current top-level invocation.

## Source locations

Compiler and runtime spans are zero-based, end-exclusive UTF-16 code-unit offsets in decoded source. Rendered line and column numbers are one-based. Diagnostics carry a primary span and may carry related spans. Runtime failures carry ordered Lyra frames; synthetic frames are hidden by default and map to a nearest source origin.

## Compiler diagnostic families

Compiler codes have the form `LYC-<PHASE>-<NUMBER>`. The current inventory is:

| Phase | Codes | Purpose |
| --- | --- | --- |
| SOURCE | `001` to `006` | malformed UTF-8, invalid source/physical identities or spans, invalid diagnostic code/span |
| LEX | `001` to `011` | character/operator/modifier/literal/escape errors, unterminated strings/chars/comments, invalid number or numeric range |
| PARSE | `001` to `014` | unexpected token, missing delimiter, invalid form/comma/spacing/modifier/arity/accessor/import header/type form, and migration syntax |
| MODULE | `001` to `004` | invalid configuration/import path, duplicate identity, eager initialization cycle |
| RESOLVE | `001` to `027` | roots and source discovery, duplicate or missing names/modules, visibility, signatures, access, mutation, imports, exports, linkage, and obsolete construction |
| TYPE | `001` to `018` | mismatch, unresolved or untyped expressions, literals, nil context, arity/callability, operators, conversions, rebinding/access/branches/lambdas/truth tests, numeric common type |
| IR | `001` to `008` | missing type/span, unresolved link, missing conversion, unsupported node, evaluation order, arity, graph validity |
| EMIT | `001` to `002` | unsupported emission or invalid emission plan |
| PACKAGE | `001` | invalid executable `main` contract |
| SESSION | `001` to `002` | unsupported external binding linkage or session name conflict |

The compiler stops at the first blocking error in an attempted phase, publishes no partial artifact from that phase, and does not run dependent phases.

Important migration diagnostics are exact:

| Code | Meaning |
| --- | --- |
| `LYC-PARSE-011` | Obsolete `??` match-arm marker |
| `LYC-PARSE-012` | Obsolete `::match[...]`, `::cond[...]`, `::iter[...]`, or `::while[...]` special-form spelling |
| `LYC-PARSE-013` | Obsolete conditional `(match _ ...)` form |
| `LYC-PARSE-014` | Invalid explicit construction marker |
| `LYC-RESOLVE-027` | Obsolete qualified `module->:.Type[...]` nominal construction; use `module->Type[...]` |

`LYC-RESOLVE-026` is retired and retained only to prevent code reuse. Deferred syntax normally receives the ordinary lexical, parse, resolution, or type diagnostic appropriate to what was written.

## Runtime failure codes

| Code | Category | Typical boundary |
| --- | --- | --- |
| `LYR-ARITH` | arithmetic | checked overflow/underflow, zero division or step, non-finite float result |
| `LYR-BOUNDS` | bounds | invalid array or string index |
| `LYR-CONVERT` | conversion | dynamic explicit conversion outside its target domain |
| `LYR-STACK` | stack | `StackOverflowError` crossing a generated source-bearing call boundary |
| `LYR-IO` | I/O | `std->io` input/output, malformed input, or interruption |
| `LYR-INIT` | initialization | module initialization failed |
| `LYR-THREAD` | owner thread | module, closure, or handle used from another thread |
| `LYR-CLOSED` | closed resource | operation after module or retained authority closure |
| `LYR-LIFECYCLE` | lifecycle | invalid load/module close ordering or state transition |
| `LYR-LINK` | linkage/authority | wrong signature, unauthenticated callable, or controlled class linkage failure |
| `LYR-VERIFY` | verification | generated class verification failure during controlled loading |
| `LYR-COMPAT` | compatibility | metadata, target, language, profile, schema, or runtime ABI mismatch |
| `LYR-CANCEL` | cancellation | cooperative evaluation cancellation at a runtime boundary |
| `LYR-INTERNAL` | internal | runtime or launcher invariant/infrastructure failure |

A failure exposes its code, category, summary, frames, related sources, and optional Java cause. Controlled runtime loading translates `VerifyError` to `LYR-VERIFY` and other class-definition or resolution `LinkageError` values to `LYR-LINK` before initialization. Direct class-path loading can surface native JVM class-version, preview, or linkage errors first. Other `VirtualMachineError` values, `ThreadDeath`, and failures outside controlled boundaries are not converted.

## REPL publication

Compilation or linkage failure executes nothing and publishes no names. Runtime failure or cancellation publishes no staged names, but completed mutations, output, and other effects remain. This is nontransactional. See [REPL](repl.md) and [runtime lifecycle](artifacts-and-java.md#loading-instances-and-lifecycle).
