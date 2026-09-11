# Phase 1 — Syntax and nominal identity

## Context

The grammar currently recognizes only built-in type names and let declarations.
Parser replay and syntax visitors deliberately enforce a closed node inventory.

## Goal

Progress: `NominalTypeId` now validates revision/name/occurrence inputs and provides
length-prefixed canonical identity, deterministic hashes and consistent ordering.
Fixed-vector, negative and four-seed independent identity models cover it. It is
not wired into declaration collection or either compiler/runtime value-type model.
Lexical/grammar/AST support now includes nominal/member/constructor declarations,
named and qualified type references, and non-unary bracket applications. Descriptor
roles, source spans and exhaustive visitor dispatch are covered by NominalSyntaxTest;
its independent source generator runs by default and in tools/fuzz-language.sh.
Module-level syntax restriction, same-name constructor count, mandatory member types,
reserved keyword highlighting and console delimiter completeness are covered.
Semantic declaration collection/identity issuance remains unfinished, so Phase 1 is
not complete as a whole. Do not turn the temporary resolver boundary into conformance.

Represent nominal declarations, named types, members and constructors explicitly
without confusing names with runtime values or introducing dynamic member lookup.

## In Scope

Reserved struct/class spellings; module-level declaration modifiers; named/qualified
type references; member declarations with optional initializers; same-name constructor
lambdas; construction versus indexing; immutable declaration/schema identity models.

## Out of Scope

Inheritance, nested type declarations, generics, interfaces, static members,
constructor overloads/defaults, and claims of executable feature completion.

## Implementation Steps

1. Extend lexer keywords, grammar production inventory and exact descriptor role
   validation; keep annotation whitespace and delimiter diagnostics authoritative.
2. Add immutable syntax nodes and exhaustive visitor/replay operations. Constructor
   recognition must not reinterpret ordinary assignment in method bodies.
3. Resolve the type/value namespace using explicit nominal declaration identities;
   retain source qualification and source spans, not JVM descriptors in syntax.
4. Define recursive schema references without recursively expanding type equality
   or hash computation; distinguish same-shaped declarations/revisions.
5. Add positive/negative/span/corrupted-descriptor tests, source fuzz generation and
   an independent grammar/identity oracle; update grammar and coverage documentation.

## Validation

Test empty types, nested composite member contracts, modifiers, initializer-less
members versus illegal initializer-less locals, constructor-name mismatch, multiple
constructors, malformed delimiters and annotations, reserved names, duplicate
members, qualified names and array-index ambiguity. Test identity equality and
inequality across modules/declarations/revisions independently of member shape.

## Exit Criteria

Immutable syntax and identity artifacts validated; full suite and applicable fuzz
pass; diff reviewed and checkpoint committed. This phase alone is not executable
struct/class support and does not satisfy the owner's full-feature request.
