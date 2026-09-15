## Context

Lyra currently has strong internal specifications, implementation, tests, and operational pages, but no public complete language reference. The requested reference must cover every user-visible syntax form, type, feature, compiler-recognized built-in, failure class, CLI operation, Java API boundary, REPL surface, and editor surface that is ready for release. The formal specification is a separate deliverable and owns normative semantics. This reference should be optimized for lookup: exact syntax, signatures, arities, examples, constraints, diagnostics, and links to the formal specification.

Read-only exploration by six `gpt-5.6-luna:high` agents found approximately 277 readable `.lyra` corpus fixtures, extensive semantic/backend/runtime tests, five Maven modules, existing REPL/editor docs, and several documentation gaps. Treat the findings as a map. Verify exact claims against code, living specifications, tests, and current examples.

## Objective

Create a complete public reference, preferably as a navigable `docs/reference/` tree with an index, or as a clearly linked set of pages if that is more usable. At minimum cover the language and all current product-facing reference surfaces. Do not leave details only in `.internal-dev` records. Use dry, precise, practical prose with terse examples. Avoid em dashes, generic filler, duplicated claims, unsupported release assertions, and invented APIs.

The reference should distinguish:

- language syntax and semantics;
- compiler and artifact behavior;
- runtime/Java ABI;
- CLI commands and launcher behavior;
- local REPL versus attached application sessions;
- editor workflows and known limits;
- implementation evidence versus normative promises.

## Settled Decisions

### Language inventory

Reference all current forms:

- source encoding, comments, ASCII identifiers, reserved words, `_`, delimiters, whitespace and context-sensitive comma rules;
- literals: booleans, nil, unit, strings, UTF-16 characters, exact integer/float forms, suffixes, adjacent negative literals and escaped content;
- primitive types, `Array<T>`, `Range<T>`, `Tuple<...>`, `Fn<...;...>`, `@pub`, `@mut`, `@nil`, nilability, inference, numeric widening and explicit conversions;
- immutable-by-default `let`, mutable binding/parameter/field rules, source-ordered private replacement, `:=`, captures, shared mutable cells;
- full and compact lambdas, callable application, direct calls, namespace access, member value access, member calls, bound method values, target-before-arguments evaluation, direct/F-expression parity, and no grouping/currying/defaults/varargs;
- strict left-to-right evaluation, blocks, truthiness, conditionals, coalescing, `match` and `cond` including final wildcard and guards;
- all operators and exact arities: `+`, `-`, `*`, `/`, `%`, `^`, comparisons, equality, identity, `and`, `or`, `xor`, `not`, `++`, `--`;
- arrays, tuples, indexing, string/array `.length`, structural versus identity equality, strings and explicit `String[value]` conversion;
- signed ranges, `iter`, `while`, callback contracts, lazy/repeated evaluation, empty/directed-away ranges, zero-step failure, and loop restrictions;
- `struct` and `class`, explicit `:Type[...]` construction, qualified construction, fields, methods, constructors, defaults, visibility, definite initialization, `self`, saved/replaced method slots, nominal identity, structural/class equality;
- imports, aliases, selective imports, re-exports, module identity, eager initialization and cycles;
- compiler-owned `std->io` and exact signatures:
  - `print : Fn<String;Unit>`
  - `println : Fn<String;Unit>`
  - `eprint : Fn<String;Unit>`
  - `eprintln : Fn<String;Unit>`
  - `readLine : Fn<;@nil String>`
- compile-time diagnostic families, runtime failure codes, source span conventions, and deferred features.

### Product inventory

Reference the five modules and boundaries:

- `lyra-runtime`: loading, metadata, lifecycle, runtime values/types, I/O, launchers;
- `lyra-compiler`: source resolution through direct Java 25 Class-File API emission and deterministic artifacts;
- `lyra-repl`: persistent sessions, consoles, attachment, loopback protocol v2;
- `lyra-cli`: `repl`, `attach`, `run`, `compile`, launchers and JLine/plain behavior;
- `lyra-editor`: optional JavaFX workspace, compiler-backed analysis, child-process execution, REPL, JDI debugging.

Reference Java 25 requirements, Maven setup, version `0.1.0` only where verified from current POM/README, deterministic classes/thin/bundled JARs, metadata/debug maps, generated Java facades, exact export handles, runtime loading, owner-thread lifecycle, failure categories, and trusted-Java live array escape. Do not expose compiler-private typed IR/provenance as a public API.

Reference CLI forms and restrictions:

```text
lyra
lyra repl [DIR] [--source-root DIR]* [--history PATH] [--keymap emacs|vi] [--plain]
lyra attach HOST:PORT
lyra run ROOT [--source-root DIR]* [--repl] [--repl-port PORT] [--repl-wait] [-- ARGS...]
lyra compile ROOT [--source-root DIR]* [--output PATH] [--format classes|thin-jar|bundled-jar] [--java-package PACKAGE] [--include-sources] [--force] [--repl]
lyra --help
lyra --version
```

Be exact about local REPL empty startup, explicit import/load, `\help`, `\bindings`, `\type`, `\load`, `\reload MODULE`, `\reset`, `\history`, and `\quit`; command boundary rules; persistent state; pinned imports; explicit reload; failure/cancellation publication; bounded snapshots; source-root behavior; loopback-only unauthenticated attachment; controller/safe-point/cancellation constraints; protocol v2; no v1 downgrade; host filesystem and I/O routing; and editor child-process versus external application attachment.

## Constraints

Do not use stale snippets without checking them. Known exploration warnings include `docs/repl.md` examples using obsolete `SourceSubmission.of(...)` or an outdated `ApplicationAttachment.open` signature, and the `examples/repl/README.md` relative CLI path. Correct or replace such examples using `EvaluationSource.of(...)`, actual APIs, and the canonical `examples/repl/HostExample.java`. Do not copy presentation annotations such as `; 1` into copy/paste Lyra unless clearly marked comments.

Do not claim attachment authentication, sandboxing, hostile-code isolation, automatic local-root initialization, watcher reload, state migration, old-reference retargeting, arbitrary Java callbacks, or engine/Vulkan integration. Do not describe a current audit as passing unless current `tools/phase24-release-audit.sh` evidence exists. Do not treat historical test counts or historical changelogs as current verification.

Do not claim editor native installers or cross-platform behavior beyond verified evidence. Keep platform qualifications explicit. Keep internal implementation data such as declaration ordinals, storage domains, route certificates, and producer-generation tables out of the public reference except for a short conceptual explanation where it helps users understand a documented boundary.

## Scope

Use a navigable reference structure such as:

- `docs/reference/index.md`: scope, notation, status, version/compatibility dimensions, navigation;
- `docs/reference/lexical-structure.md`: encoding, spans, comments, identifiers, reserved words, punctuation, commas;
- `docs/reference/literals-and-types.md`: literals, primitive/composite/nominal types, qualifiers, nilability, numeric rules;
- `docs/reference/bindings-and-functions.md`: let, scope, assignment, captures, lambdas, calls, accessors;
- `docs/reference/control-flow.md`: blocks, truthiness, conditionals, coalescing, match, cond, loops;
- `docs/reference/operators.md`: operator table, arities, typing, evaluation, equality/identity;
- `docs/reference/collections-and-strings.md`: arrays, tuples, ranges, strings, indexing, conversion;
- `docs/reference/structs-and-classes.md`: declarations, construction, fields, methods, initialization, equality;
- `docs/reference/modules-and-io.md`: imports/exports and `std->io` signatures/behavior;
- `docs/reference/diagnostics.md`: compiler/runtime categories, spans, migration errors, failure handling;
- `docs/reference/cli.md`: commands/options/exit statuses/output formats/launcher behavior;
- `docs/reference/artifacts-and-java.md`: compilation API, artifacts, ABI, generated facades, loading/lifecycle;
- `docs/reference/repl.md`: local session, commands, persistence, reload, attachment/protocol limits;
- `docs/reference/editor.md`: workspace, analysis, run/debug, settings, limits and platform qualifications;
- `docs/reference/deferred-features.md`: explicit non-scope and rejected spellings.

If a smaller page set is chosen, retain these distinct lookup topics and an index. Tables are appropriate for operator arity, type mapping, CLI options, built-in signatures, diagnostic codes, and lifecycle statuses.

## Recommended Direction

Start each page with a direct definition and a compact syntax/signature table. Provide minimal valid examples and a “not valid” or boundary subsection where users are likely to infer the wrong behavior. Link to `docs/specification/language.md` for formal semantics and to existing operational pages where a page remains authoritative for a detailed procedure. Do not make the reference a copy of internal specifications. The reference should expose user-visible contracts and show how to select among forms.

Use terminology consistently. Prefer “parenthesized form” and “bracket form” for the two surface spellings, while noting that project guidance sometimes calls the latter an F-expression. Define the distinct version dimensions: language-contract version, artifact schema, runtime ABI, Java target, and product SemVer. Define “module instance,” “producer,” “session generation,” “snapshot,” “attachable artifact,” and “trusted interface” once.

Include cross-reference links to `docs/repl.md`, `docs/editor.md`, `docs/language-testing.md`, examples, and the formal specification. Preserve the security warning at the start of REPL/attachment material.

## Evidence

Inspect directly before writing exact details:

- `.internal-dev/specifications/language-core.md`, `.internal-dev/specifications/backend-runtime.md`, `.internal-dev/specifications/repl.md`, `.internal-dev/specifications/editor.md`, `.internal-dev/specifications/deferred-features.md`, `.internal-dev/specifications/index.md`, `.internal-dev/specifications/decisions.md`.
- `lyra-compiler/src/main/resources/grammar_spec.md`.
- `README.md`, `docs/repl.md`, `docs/editor.md`, `docs/language-testing.md`.
- Compiler sources: `Lexer`, `TokenKind`, `ProductionKind`, `Parser`, `SyntaxNode`, `TypeRules`, `CallbackLoop`, `IntrinsicModule`, `CompilerDiagnosticCodes`.
- Semantic/IR sources: `SemanticResolver`, `TypeChecker`, `SemanticFlowAnalyzer`, `InitializationAnalyzer`, `NominalInitializationProof`, `TypedIrBuilder`, `IrValidator`.
- Backend/runtime sources: `LyraCompiler`, `CompileRequest`, `CompileResult`, `CompiledArtifact`, `JvmAbiMapper`, `JvmBytecodeEmitter`, `ArtifactAssembly`, `ArtifactOutputWriter`, `LyraRuntime`, `ArtifactMetadataReader`, `LoadedArtifact`, `ModuleHandle`, `ExportHandle`, `ModuleLifecycle`, `RuntimeIoEnvironment`, `LyraIo`, `LyraRuntimeException`, `LyraLauncher`.
- CLI/REPL/editor sources: `LyraCli`, `ConsoleCommandParser`, `PlainConsole`, `ManagedConsoleSession`, `LyraSession`, `ApplicationAttachment`, `ReplActivation`, `ReplLauncher`, `RemoteClient`, `RemoteServer`, `RemoteConsoleSession`, `EditorWindow`, `EditorRuntime`, `EditorWorker`, `LanguageService`, `Debugger`, `WorkspaceSettings`.
- Examples: `examples/repl/HostExample.java`, `examples/repl/README.md`, `examples/editor/README.md`, `examples/editor/main.lyra`.
- Evidence tests named in `tools/phase24-conformance-coverage.tsv`, `tools/phase24-repl-coverage.tsv`, `tools/phase24-requirement-matrix.tsv`, especially `LanguageCoverageTest`, `LanguageBuiltinTest`, `LanguageAbiTest`, `MatchBoundaryIntegrationTest`, `CallbackLoopIntegrationTest`, `NominalSemanticsTest`, `NominalBytecodeTest`, `Phase19PublicApiTest`, `Phase21CliTest`, `Phase22CliConformanceTest`, persistent REPL tests, protocol tests, and editor tests.

## Validation

Before commit, a separate `gpt-5.6-luna:high` verification agent must inspect the completed reference claim by claim. It must identify each unsupported, stale, ambiguous, or incorrectly scoped statement and suggest corrections. The root agent must integrate corrections, then run focused docs/link/example checks if present, `mvn test`, and the current release audit if claiming release verification. Check all code snippets for actual APIs and syntax. Run the write-like-me style checker on changed prose and eliminate every em dash.

## Open Questions

Choose one-file versus multi-page organization based on navigation and maintainability. Do not add a public protocol-wire schema or compiler-internal semantic reference unless the inspected implementation supports a stable user-facing contract. Keep release status factual and qualified where historical evidence conflicts with current worktree status.
