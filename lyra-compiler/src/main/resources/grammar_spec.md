# Lyra syntax grammar (human-readable EBNF)

This file is a readable synchronization aid for the grammar matcher.  The
living specifications in `.internal-dev/specifications/language-core.md` and
`backend-runtime.md` are authoritative; this file is subordinate to them.
Static typing, name/member legality, modifier legality that depends on a
binding context, and all runtime behavior belong to later phases.

The grammar uses the following conventions:

* `ε` is empty and `*` means zero or more.
* Whitespace and comments may occur between tokens except where the lexical
  annotation rule below says otherwise.
* A comma is optional between items only in the lists explicitly marked
  `comma-list`.  Leading, trailing, and repeated commas are invalid.
* All listed delimiters are required and balanced.
* The matcher emits replay descriptors for these productions; it does not
  construct an AST or resolve a name.

## Lexical terminals

```ebnf
identifier       ::= ASCII-identifier ;
type-name        ::= I8 | I16 | I32 | I64 | U8 | U16 | U32 | U64
                   | F32 | F64 | Bool | Char | String | Unit
                   | Array | Range | Tuple | Fn ;
modifier         ::= '@pub' | '@mut' | '@nil' ;
literal          ::= '#T' | '#F' | '#NIL' | integer | decimal
                   | string | character ;
match-keyword    ::= 'match' ;
guard-keyword    ::= 'when' ;
match-arm-marker ::= '??' ;
operator         ::= '+' | '-' | '*' | '/' | '^' | '%'
                   | '<' | '<=' | '>' | '>=' | '==' | '!='
                   | 'eq?' | '!eq?' | 'and' | 'or' | 'xor' | 'not'
                   | '++' | '--' ;
```

`+` and `-` signs are operators, not part of numeric literals.  A named
annotation is exactly `name :Type`: whitespace is required before `:` and
forbidden after it.  A return annotation has no name before its colon and is
written `:Type`; whitespace remains forbidden after the colon.

## Compilation units and imports

```ebnf
program            ::= import-declaration* (nominal-declaration | form)* EOF ;
form               ::= let-binding | reassignment | expression ;

import-declaration ::= 'import' ['@pub'] import-path
                       [ 'as' identifier
                       | '->' '{' import-item* '}' ] ;
import-path        ::= identifier ('->' identifier)* ;
import-item        ::= identifier [ 'as' identifier ] ;
```

Imports must form the initial header.  `@pub` is accepted only on the
selective-import alternative.  A selective import has at least one item.
The grammar does not decide whether a module, export, alias, or member exists.

## Nominal declarations (syntax implemented; execution incomplete)

```ebnf
nominal-declaration ::= 'struct' ['@pub'] capitalized-identifier
                        '{' member-declaration* '}'
                      | 'class' ['@pub'] capitalized-identifier
                        '{' (member-declaration | constructor-declaration)* '}' ;
member-declaration  ::= 'let' modifier* identifier named-annotation ['=' expression] ;
constructor-declaration ::= enclosing-class-name '=' lambda ;
named-type          ::= identifier ('->' identifier)* ;
```

`struct` and `class` are reserved; declarations are module-level only. Members
require explicit types; ordinary let bindings still require initializers. A class
has at most one same-name constructor. Structs have none. Constructor parameter/
return contracts, member uniqueness, privacy, data-only restrictions and definite
initialization belong to semantics. Initial declaration/member resolution exists;
complete initialization/flow, typed IR, JVM and session execution remain in progress.
Syntax or resolver success is not an executable feature claim.

## Bindings, assignment, blocks, and lambdas

```ebnf
let-binding        ::= 'let' modifier* identifier [named-annotation]
                       '=' expression ;
reassignment       ::= expression ':=' expression ;
prefix-assignment  ::= '(' ':=' expression expression ')' ;

block              ::= '{' form* '}' ;

lambda             ::= '(' '=>' return-modifier* [return-annotation]
                       parameter-list expression ')' ;
compact-lambda     ::= parameter-list expression ;
parameter-list     ::= '|' (parameter (comma-list parameter)*)? '|' ;
parameter          ::= modifier* identifier [named-annotation] ;
return-annotation  ::= ':' type ;
return-modifier    ::= '@nil' ;
named-annotation   ::= ':' type ;
```

The complete lambda contract may come from an expected `Fn` type, so parameter
and return annotations can be omitted in the source form.  Semantic analysis
requires a complete contract.  The matcher retains every modifier and
annotation range but does not infer that contract.

## Expressions and accessors

```ebnf
expression         ::= atom postfix* ;
atom              ::= literal
                     | identifier
                     | parenthesized-expression
                     | block
                     | compact-lambda
                     | '::' identifier argument-list
                     | match-bracket
                     | iter-bracket
                     | while-bracket
                     | operator-bracket
                     | typed-expression ;

parenthesized-expression
                   ::= '('
                     ( ')'
                     | 'match' match-content ')'
                     | 'iter' expression-list ')'
                     | 'while' expression-list ')'
                     | '=>' return-modifier* [return-annotation]
                       parameter-list expression ')'
                     | ':=' expression expression ')'
                     | operator expression-list ')'
                     | expression [identifier] '->' expression [':' expression] ')'
                     | expression ':' expression ')'
                     | expression ('..' | '...') expression ':' expression ')'
                     | expression expression* ')' ) ;

postfix            ::= argument-list
                     | ':.' member-name
                     | '::' identifier argument-list
                     | namespace-suffix ;
namespace-suffix   ::= '->' [identifier ('->' identifier)* ['->']]
                     (':.' member-name | '::' identifier argument-list) ;
member-name        ::= identifier | unsigned-decimal-field-position ;

argument-list      ::= '[' (argument (comma-list argument)*)? ']' ;
argument           ::= expression ;
expression-list    ::= (argument (comma-list argument)*)? ;
operator-bracket   ::= operator argument-list ;
```

The conditional alternatives are, in source notation,
`(predicate -> then : else)` and `(predicate -> then)`, with an optional
unannotated predicate binding immediately before `->`.  The other colon form,
`(nilable : fallback)`, is the nil-only coalescing shape.  A `::` accessor
always has bracket arguments; `:.` is a value/member accessor.  A namespace
qualification ends in `:.` or `::`.

Operators have the following grammar-level arities: `+`, `*`, comparisons,
equality, identity, `and`, `or`, and `xor` have at least two operands; `-` and
`/` have at least one; `%` and `^` have exactly two; and `not`, `++`, and `--`
have exactly one.  Their operand types and all member legality are semantic.

## Match expressions

```ebnf
match-bracket      ::= '::' 'match' '[' match-content ']' ;
iter-bracket       ::= '::' 'iter' '[' expression-list ']' ;
while-bracket      ::= '::' 'while' '[' expression-list ']' ;
match-content      ::= value-match | conditional-match ;
value-match        ::= expression value-arm* fallback-arm ;
conditional-match  ::= '_' condition-arm* fallback-arm ;
value-arm          ::= '??' expression ['when' expression] '->' expression
                     | '??' '_' 'when' expression '->' expression ;
condition-arm      ::= '??' expression '->' expression ;
fallback-arm       ::= '??' '_' '->' expression ;
```

`iter`, `while`, `match` and `when` are reserved words, not user identifiers.
`iter` and `while` are accepted only as unqualified call targets. Iter requires
a range and a callback; while requires a `Fn<;Bool>` predicate and a `Fn<;Unit>`
action. Arity and types belong to semantic checking. `::iter` and `::while` begin
fresh expressions, never receiver suffixes. Callback-loop execution remains
under implementation.
The exact `_` in the
subject position selects conditional mode; the exact `_` in a pattern/condition
position selects the wildcard alternative rather than an identifier expression.
Other occurrences of `_` remain ordinary identifiers. These contextual exclusions
apply to the `expression` alternatives above.

Every match requires a final unguarded wildcard; it may be the only arm. No arm may
follow it. Conditional mode does not allow `when`. Arms are not comma-separated.
Traditional patterns may be arbitrary value expressions; later semantic phases
check typed equality compatibility, guard/condition truthiness, and result type
unification. No arm bindings, type patterns, or destructuring are provided.

The parenthesized and bracketed forms produce the same match structure. They are
special forms, not ordinary eager calls. The matcher preserves each arm's pattern,
optional guard, result, wildcard role, separators, and source spans.

## Arrays, tuples, conversions, and types

```ebnf
array-expression    ::= 'Array' argument-list
                       | array-type argument-list ;
tuple-expression    ::= 'Tuple' argument-list
                       | tuple-type argument-list ;
typed-expression    ::= array-expression
                       | tuple-expression
                       | primitive-type argument-list ;

array-type          ::= 'Array' '<' type '>' ;
range-type          ::= 'Range' '<' type '>' ;
tuple-type          ::= 'Tuple' '<' type (comma-list type)* '>' ;
function-type       ::= 'Fn' '<' function-parameter-types ';' type '>' ;
function-parameter-types ::= ε | type (comma-list type)* ;
primitive-type      ::= type-name-except-Array-Range-Tuple-Fn ;

type                ::= modifier* type-base ;
type-base           ::= primitive-type
                       | array-type
                       | range-type
                       | tuple-type
                       | function-type
                       | named-type ;
```

`Array[]` and `Tuple[]` are the Unit spellings.  `Array<T>[]` is an empty
array, and `Tuple<T1,...>[]` is an explicitly typed tuple form whose shape is
checked later.  `I32[value]`, `String[value]`, and the other primitive type
applications are explicit conversions; their value/type legality is semantic.
`Fn<;R>` is the zero-parameter function signature.  `@mut` and `@nil` may
occur in nested contracts exactly as specified by the living language
contract; `@pub` is not a nested contract modifier.

Unary postfix brackets retain an index-shaped syntax node until type/value
resolution; zero/multiple arguments retain a bracket-application node. Capitalization
does not distinguish construction from indexing. Qualified construction uses the
existing namespace value accessor, e.g. `model->:.Counter[0]`, while a named type
annotation uses `:model->Counter`. Unknown type names are resolution failures.

## Excluded syntax

There are no grammar productions for variants, destructuring
or type patterns, dedicated iteration statements, user generics, macros, `throw`, `try`, `catch`, `finally`,
varargs/default/named arguments, bitwise or shift operators, or `nor`/`nand`/
`xnor`.  Their current lexical spellings either remain ordinary identifiers or
produce the ordinary lexical/syntax diagnostic required by the living
specifications; they are never represented by a deferred-production
placeholder. `Any` has no special meaning; as with any undeclared named type,
attempting to use it as a type fails resolution.
