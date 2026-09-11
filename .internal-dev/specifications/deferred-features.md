# Deferred Features

These capabilities are accepted as future work but are not valid current language syntax. Until promoted into a living normative specification, their spellings produce ordinary syntax errors.

## User-declared data and object types

- User classes, records/struct-like data, and variants are deferred to the next dedicated language-design sprint.
- Direction already accepted for later refinement: fields use the same `let` declaration model; `@mut` marks mutable fields; types may eventually contain instance methods and shared bindings; `@static` is the preferred future spelling for shared type-level storage and may combine with `@mut`.
- No current behavior, grammar, inheritance, construction, equality, layout, visibility, or `@static` legality is implied. The later specification must resolve those contracts before implementation.

## Advanced patterns and iteration

- Basic value and conditional `match` expressions are defined in `language-core.md`.
- Destructuring patterns, type patterns, match-arm bindings and automatic match narrowing remain deferred.
- First-class signed-integer ranges and callback-based `iter` and `while` are specified in
  `language-core.md`. General iterator protocols, dedicated loop statements,
  unsigned/floating ranges, exhaustiveness analysis and binding patterns remain deferred.

## Generics and macros

- User-defined generics, macro systems, quoting, hygiene, and compile-time metaprogramming are deferred.
- Built-in `Array<T>`, `Range<T>`, `Tuple<...>`, and `Fn<...;...>` syntax does not establish user-generic semantics.

## Bitwise operations

- Fixed-width integer bitwise and shift operations are deferred.
- A later contract should preserve checked invalid-shift behavior if shifts are accepted, but spelling and complete semantics remain undecided.

## Exceptions and recovery

- Lyra-level `throw`, `try`, `catch`, and `finally` are deferred.
- Multi-error compiler recovery is deferred; the current contract requires structured fail-fast phase diagnostics.

## Dynamic and convenience features

- `Any`/dynamic values and runtime member lookup are deferred.
- Optional/default/omitted/named arguments, varargs, automatic currying, and partial application are deferred.
- Derived boolean operators `nor`, `nand`, and `xnor` are excluded from the core and may be library functions rather than future syntax.

## Source compatibility/version mechanisms

- Source edition directives and multiple simultaneously selectable language editions are deferred.
- The backend's required artifact manifest records one language-contract/runtime ABI version for clear loading and Java use; this does not add source syntax or promise multi-edition compatibility.

## REPL security hardening and automatic local roots

- Authentication, credential files, encryption, authorization frameworks, hostile-client isolation, signatures, anti-forgery guarantees and security-specific filesystem policy are deferred. The current REPL attachment is an explicitly enabled trusted localhost development interface with no authentication.
- Automatic initialization of a local project root at `lyra repl ROOT` is deferred. Local REPL startup is an empty workspace with source search directories; users explicitly import or load modules. Explicit registration of a REPL-capable application root remains a separate current attachment feature.
