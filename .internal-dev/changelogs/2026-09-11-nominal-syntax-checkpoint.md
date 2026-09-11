# WIP — Struct/class syntax and replay

## Date

2026-09-11

## Git Commit

`cd5af1b82ecc9205bb824dba8a09d0f4c4c73bbd` (baseline).

## Change Summary

Implemented the lexical, grammar and immutable AST forms for module-level structs/
classes, explicitly typed members with optional initializers, one same-name class
constructor, qualified named type annotations, and non-unary bracket applications.
Added exact descriptor-role validation and exhaustive parser/visitor operations.
This advances the owner's implementation request but does not complete it.

## Files

- Compiler lexer, grammar matcher/program/production inventory, SyntaxNode/Visitor,
  Parser, SemanticResolver and CompilerDiagnosticCodes.
- NominalSyntaxTest; LexerTest, GrammarMatcherTest, ParserTest and Phase17SmokeTest;
  excluded/class.lyra conformance fixture; tools/fuzz-language.sh.
- Editor LanguageService keyword styling and its test; REPL ConsoleParsingTest.
- Readable grammar, language-core specification, active nominal plan, knowledge,
  language-testing coverage and the associated self-review.

## Behavioral Impact

- `class`/`struct` are reserved. Java-keyword mangling coverage uses `public` instead
  of the now-reserved `class`, retaining all facade/descriptor/invocation assertions.
- Member declarations require explicit types; missing initializers remain illegal
  for ordinary let declarations. Constructor names/count and declaration scope are
  grammar-checked; member contracts/privacy/initialization still require semantics.
- Named/qualified types retain their source path. Unknown types fail resolution.
- Unary brackets retain IndexAccess; non-unary brackets retain BracketApplication.
  Name capitalization does not decide constructor versus value. Invalid value-index
  arity remains a compile failure, now checked during resolution.
- Nominal declarations fail closed with explicit LYC-RESOLVE-026 until their semantic
  implementation exists. No typed graph, IR, executable artifact or partial runtime
  object is published for them. This is not positive feature conformance.

## Specification Impact

Updated syntax status and reserved vocabulary, qualified construction via the existing
namespace value accessor, and type-versus-value bracket resolution. Plans continue
to mark semantic/type/IR/JVM/session work unfinished. The old excluded-class fixture
now checks deferred inheritance; empty classes have positive grammar coverage only.

## Validation

- Focused `NominalSyntaxTest,LexerTest,GrammarMatcherTest,ParserTest,Phase17SmokeTest`
  passed through Maven with the required reactor dependencies.
- Final `mvn -q test` passed across the full reactor, including all six nominal
  syntax tests, editor keyword styling, console completeness and bounded fuzz.
- `tools/fuzz-language.sh -q` passed with NominalSyntaxTest added to its fixed test
  inventory: four seeds, 1,800 generated declarations each plus truncation negatives,
  alongside the existing extended language/range/loop campaigns.
- `git diff --check` passed. No release audit or graphical qualification was run.
- Initial failures exposed obsolete parse-time index/type expectations and the old
  blanket class exclusion; assertions were migrated to the new semantic phase
  boundary or still-excluded syntax. No valid nominal runtime test was changed to
  accept unsupported emission. Nominal executable conformance remains missing.

## Risks

The owner's full-feature request remains incomplete. Nominal identity is not yet
issued by semantic declaration collection. Constructor definite initialization,
member privacy/mutation, callable provenance, value types, JVM layouts, Java ABI,
artifact schemas and persistent objects are not implemented by this checkpoint.

## Follow-up Items

Continue phases 1–4 in plans/nominal-types. Remove the temporary nominal resolver
boundary only when complete semantic/IR/runtime evidence exists. Add real nominal
source-to-JVM/session conformance, not merely parser success. Release/graphical/
independent audit qualification is not claimed.
