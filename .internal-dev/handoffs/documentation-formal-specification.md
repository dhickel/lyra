## Context

Lyra is a standalone functional JVM scripting language. The living normative source is `.internal-dev/specifications/language-core.md`, with backend/runtime contracts in `.internal-dev/specifications/backend-runtime.md`, and a subordinate readable grammar in `lyra-compiler/src/main/resources/grammar_spec.md`. The user requests a release-quality formal specification comparable in purpose to R5RS, adapted to Lyra's syntax, static type system, nominal types, direct JVM implementation boundary, and current release scope.

Six read-only `gpt-5.6-luna:high` exploration agents surveyed the codebase, specifications, tests, resources, public docs, examples, and release inventories. Their findings are evidence maps, not authority. Code is logical truth, living specifications are intended truth, and public docs are explanatory history. Inspect cited files directly before stating exact claims.

## Objective

Produce a complete public formal language specification in a new document, preferably `docs/specification/language.md` or an equivalent clearly named path. It must be normative, self-contained, and readable without reconstructing behavior from source. It should use a consistent formal notation inspired by standards documents: terminology, conformance language, lexical grammar, syntactic grammar, static semantics, dynamic semantics, values, modules, failures, and exclusions. It must document all current language syntax, features, and compiler-recognized built-ins that belong to the language contract. It must not turn JVM implementation details, internal flow facts, or REPL protocol behavior into source-language semantics.

Distinguish normative language rules from implementation notes, syntax acceptance from semantic validity, compile-time diagnostics from invocation-fatal runtime failures, ordinary language semantics from backend/runtime representation, and current forms from deferred or excluded forms. Use a restrained, technically clear voice. Use normative `MUST`, `MUST NOT`, `SHOULD`, and `MAY` terms only after defining them. Do not use em dashes or invented claims.

## Settled Decisions

The current language includes:

- UTF-8 source with an optional initial BOM; authoritative source spans are UTF-16 code-unit offsets.
- Case-sensitive ASCII identifiers `[A-Za-z_][A-Za-z0-9_]*`; punctuation is not identifier text.
- Nestable block comments `/* ... */`, line comments `//`, whitespace rules, context-sensitive commas, and mandatory annotation spacing: `name :Type`, never `name:Type` or `name : Type`.
- Reserved words `let`, `struct`, `class`, `import`, `as`, `match`, `cond`, `iter`, `while`, `when`; `_` is contextual wildcard syntax in match/cond positions and otherwise an ordinary identifier.
- Literals `#T`, `#F`, `#NIL`, `()`, strings, UTF-16 code-unit characters, exact decimal integers and decimal floats, suffixes `I8/I16/I32/I64/U8/U16/U32/U64/F32/F64`, and adjacent bare negative literals. Signs are not part of numeric magnitudes; a trivia-separated `-` is the operator form.
- Primitive types `I8`, `I16`, `I32`, `I64`, `U8`, `U16`, `U32`, `U64`, `F32`, `F64`, `Bool`, `Char`, `String`, and `Unit`.
- Composite types `Array<T>`, signed-integer `Range<T>`, `Tuple<T1,T2,...>`, and positional `Fn<P1,P2,...;R>`.
- Qualifiers/modifiers `@pub`, `@mut`, `@nil`, including binding, parameter, return, and nested-contract restrictions. `@pub` is not a nested value qualifier; `@mut` does not qualify returns; `@nil` qualifies value contracts.
- Static typing with local/bidirectional inference, exact contextual literal typing, invariant composite types, permitted lossless numeric widening, explicit value/range-checked conversions, nil-only contracts, and no `Any` or dynamic type.
- `let` bindings, immutable-by-default scope, source-ordered private replacement, declaration/reference/capture identity, recursive function signature predeclaration, closure captures, shared mutable cells, `:=` rebinding, and aggregate mutation through `@mut` roots.
- Lambdas as the only function declaration mechanism. Full form `(=> ReturnModifier* [:ReturnType] |parameters| body)` and compact anonymous form `|parameters| body` only under complete expected `Fn` context. Exact positional calls only. No currying, defaults, named arguments, omitted arguments, or varargs.
- Callable application `(callee args)`, direct call `::name[args]`, receiver member direct call `receiver::method[args]`, namespace qualification with `->`, value/member access `:.`, bound method values, and their authority and evaluation distinctions. Parentheses are not general grouping.
- Compiler-recognized built-in operators and forms use bare heads, never `::`: operators, primitive/String conversions, `Array`/`Tuple` literals, `match`, `cond`, `iter`, and `while`.
- Strict eager left-to-right evaluation for targets, arguments, operators, collection elements, declarations, and block forms. Short-circuit `and`, `or`, conditionals, match/cond arm selection, and nil coalescing.
- Blocks `{ forms }` with lexical scope and final-expression result; empty block is `Unit`.
- Truthiness: false includes `#F`, `#NIL`, numeric zero, signed zero, empty string, empty array, and `Unit`; true includes `#T`, nonzero finite numbers, nonempty strings/arrays/tuples, functions, characters, and nonnil references. Boolean operators return `Bool`.
- Conditionals `(predicate [binding] -> then [: else])`, then-only conditionals, and nil-only coalescing `(nilable : fallback)`. Predicate evaluates once, branch evaluation is lazy, and optional binding narrows `@nil T` to `T` only in the truthy branch.
- Value `match` in parenthesized and bare bracket forms, with a real subject, arbitrary value patterns, optional `when` guards, source-order first match, final unguarded `_ -> fallback`, no pattern bindings, no automatic narrowing, and lazy results. `cond` is parenthesized-only, has no subject, accepts truth-tested conditions, forbids `when`, and requires the same final fallback.
- Operators `+`, `-`, `*`, `/`, `%`, `^`, `and`, `or`, `xor`, `not`, `++`, `--`, comparisons, value equality `==/!=`, and identity `eq?/!eq?`, with exact arities, numeric typing, checked integer arithmetic, trapping nonfinite floating arithmetic, string concatenation, structural equality, and identity rules.
- Homogeneous fixed-size identity-bearing arrays, structural tuples with zero-based compile-time fields, immutable UTF-16 strings, explicit scalar/String conversion, `.length` on strings/arrays, checked indexing, and aggregate mutation.
- Signed integer half-open/inclusive ranges `(start..end:step)` and `(start...end:step)`, exact one-time bound/step evaluation, fresh traversal, empty/directed-away rules, zero-step failure, and callback-based reserved forms `iter` and `while` with exact `Fn` contracts, selected callback values, constant-stack traversal, and no break/continue/return syntax.
- Nominal `struct` and `class` declarations, declaration-based type identity, explicit `:Type[args]` or `:module->Type[args]` construction, members, class constructor syntax, declaration-order defaults, definite initialization, visibility, mutable fields/method slots, contextual `self`, saved method references, class identity equality, struct structural equality, and recursive reference layouts. No inheritance, interfaces, overloading, custom struct constructors, static members, user operators, or user generics.
- Static path-identity modules and imports, aliases, selective imports, `@pub` selective re-export, module-owned mutation, eager initialization order, function-only cycles, and no wildcard imports.
- Structured compiler diagnostics and invocation-fatal failures for overflow, invalid conversion, bounds, division/remainder by zero, nonfinite floating results, and source-mapped runtime failure categories. No source-level exceptions or recovery forms.
- Compiler-owned intrinsic module `std->io` with `print`, `println`, `eprint`, `eprintln`, and `readLine` exact contracts. Specify language-visible behavior; keep stream/environment implementation detail in the runtime reference.

## Constraints

Do not silently alter a living contract when code and specification disagree. Record or qualify conflicts for root review. Do not describe compiler flow facts, callable certificates, typed IR, JVM descriptors, sessions, or attachment protocol as source features. Do not claim release readiness or a current Phase 24 audit pass without current evidence. Do not add syntax merely because a token or AST node exists.

## Scope

Include a complete grammar in EBNF or equivalent for compilation units, imports, nominal declarations, types, modifiers, bindings, assignments, blocks, lambdas, expression atoms/postfixes, calls/accessors, bracket forms, operators, conditionals, match/cond, arrays/tuples/conversions, ranges, and nominal construction. Explain context-sensitive commas and why parentheses are not general grouping.

Define notation and semantic domains for source text, tokens, spans, syntax forms, types, qualifiers, values, arrays, tuples, functions, nominal objects, lexical/module environments, mutable cells, closures, receiver/self, evaluation results, and failures. Include judgments such as `Gamma |- e : T`, declaration typing, compatibility, mutation permission, and evaluation. Formal rules need not model compiler-private provenance algorithms but must state their observable consequences: source order, target-before-arguments selection, capture behavior, visibility, nominal identity, initialization, and failures.

Explicitly identify as outside the current language contract: variants, destructuring/type patterns, match-arm bindings, automatic narrowing; inheritance, interfaces, custom struct constructors, static members, overriding, overloading; user generics/macros/quoting; dynamic `Any`; bitwise and shift operators; `throw`/`try`/`catch`/`finally`; default/named/omitted arguments, varargs, currying; unsigned/floating/general iterator protocols and dedicated loop statements; source edition directives; Lyra-to-Java calls, arbitrary Java member access, engine/Vulkan integration, sandboxing, and REPL authentication. Clarify that excluded corpus names such as `class` and `match` refer to excluded subfeatures where plain classes/structs and value matching are supported.

## Recommended Direction

Organize the document as:

1. Status, scope, conformance language, and source of authority.
2. Notation and semantic domains.
3. Source encoding, lexical conventions, tokens, comments, spans, and delimiters.
4. Concrete grammar.
5. Names, scopes, declarations, imports, and module environments.
6. Types, qualifiers, inference, numeric compatibility, and conversions.
7. Values, literals, aggregates, strings, ranges, functions, closures, and nominal values.
8. Expressions and evaluation order.
9. Calls, accessors, operators, blocks, conditions, match/cond, and loops.
10. Mutation, aliasing, captures, methods, constructors, and initialization.
11. Modules, exports, eager initialization, and failures.
12. Intrinsic standard module `std->io`.
13. Conformance obligations and diagnostic categories.
14. Deferred/excluded forms and compatibility boundary.
15. Appendices with grammar summary, operator/arity table, and examples.

Use exact examples from `grammar_spec.md`, `language-core.md`, and tested corpus fixtures. Every example must be checked for current syntax, especially bare built-in brackets, explicit nominal construction, marker-free match arms, `cond`, negative literal trivia, and narrow comma rules.

## Evidence

Read directly before finalizing:

- `.internal-dev/specifications/language-core.md` in full, especially Current scope, Lexical rules, Literals, Type regime, Numeric typing, Bindings, Structs/classes, Functions/lambdas, Calls/accessors, Evaluation, Conditionals, Match, Operators, Arrays, Ranges/iteration, While, Tuples, Strings, Modules/imports, Failures, Constraints, and Validation.
- `.internal-dev/specifications/deferred-features.md`, `.internal-dev/specifications/decisions.md`, `.internal-dev/specifications/index.md`.
- `lyra-compiler/src/main/resources/grammar_spec.md`.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/lex/{Lexer,TokenKind,ModifierKind,NumericSuffix,Token,LiteralValue}.java`.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/grammar/{ProductionKind,GrammarMatcher,GrammarProgram,GrammarDescriptor}.java`.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/parse/Parser.java` and `ast/{SyntaxNode,SyntaxVisitor}.java`.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/types/{PrimitiveType,ArrayType,TupleType,RangeType,FunctionType,NominalType,QualifiedType,TypeRules,TypeQualifier,TypePosition}.java`.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/{SemanticResolver,TypeChecker,SemanticFlowAnalyzer,CallbackLoop,AccessKind,TypedExpressionKind,NominalInitializationProof,InitializationAnalyzer}.java` and `semantic/flow/{CallableSummaryCompiler,CallableSummarySolver,ValueAlternative}.java`.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/{TypedIr,TypedIrBuilder,IrValidator,IrNode}.java`.
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/source/IntrinsicModule.java` and `diagnostic/CompilerDiagnosticCodes.java`.
- Corpus categories under `lyra-compiler/src/test/resources/language/corpus/`: `lexical`, `primitives`, `operators`, `operator-arity`, `evaluation`, `bindings`, `functions`, `nilability`, `conditionals`, `aggregates`, `strings`, `modules`, `match`, `ranges`, `nominal`, `excluded`, and `runtime`.
- Tests `LanguageCoverageTest`, `LanguageBuiltinTest`, `LanguageConformanceTest`, `LanguageNumericTest`, `LanguageAbiTest`, `LanguageIndexTest`, `MatchBoundaryIntegrationTest`, `CallbackLoopIntegrationTest`, `NominalSemanticsTest`, `NominalBytecodeTest`, and `CallableCallParityTest`.
- `tools/phase24-conformance-coverage.tsv`, `tools/phase24-requirement-matrix.tsv`, and `docs/language-testing.md` for evidence boundaries.

## Validation

Before any commit, a separate Luna verification pass must inspect the completed specification claim by claim against code, tests, and living specifications. Remove or qualify unsupported claims. Check every grammar example against corpus/tests. Run focused language tests and `mvn test`, then inspect the diff for accidental changes outside documentation. Record any unresolved spec/code conflict in the root response and an appropriate internal-dev review or bug artifact rather than hiding it.

## Open Questions

Presentation choices remain open: final public path, exact formal notation, and whether appendices are in one file or linked pages. These do not authorize behavior changes. The final writer should choose a stable navigable structure and link the language reference and learning docs without duplicating their full content.
