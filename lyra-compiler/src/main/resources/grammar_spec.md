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
  `comma-list`, and between sibling forms of a module/block sequence or
  marker-free match/cond arm sequence when the following sibling begins with
  `::`.  Leading, trailing, and repeated commas are invalid.
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
negative-literal ::= adjacent '-' (integer | decimal) ;
match-keyword    ::= 'match' ;
cond-keyword     ::= 'cond' ;
guard-keyword    ::= 'when' ;
operator         ::= '+' | '-' | '*' | '/' | '^' | '%'
                   | '<' | '<=' | '>' | '>=' | '==' | '!='
                   | 'eq?' | '!eq?' | 'and' | 'or' | 'xor' | 'not'
                   | '++' | '--' ;
```

`+` and `-` signs are operators, not part of numeric literal magnitudes, except
that a `-` written immediately adjacent to a numeric literal in an expression
position is a bare negative literal normalized to the unary-minus operation.
`match` and `cond` are reserved.  A named annotation is exactly `name :Type`:
whitespace is required before `:` and forbidden after it.  A return annotation
has no name before its colon and is written `:Type`; whitespace remains forbidden
after the colon.  An explicit construction `:Type[...]` likewise forbids
whitespace after its colon.

## Compilation units and imports

```ebnf
program            ::= import-declaration* (nominal-declaration | form)* EOF ;
form               ::= let-binding | reassignment | expression ;
sibling-separator  ::= ε | ','   (* only before a sibling beginning with '::' *) ;

import-declaration ::= 'import' ['@pub'] import-path
                       [ 'as' identifier
                       | '->' '{' import-item* '}' ] ;
import-path        ::= identifier ('->' identifier)* ;
import-item        ::= identifier [ 'as' identifier ] ;
```

Imports must form the initial header.  `@pub` is accepted only on the
selective-import alternative.  A selective import has at least one item.
The grammar does not decide whether a module, export, alias, or member exists.

## Nominal declarations

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
initialization belong to semantics. Resolution, initialization/flow certification,
typed IR, JVM emission and persistent-session execution are implemented in their
own phases; this grammar remains only their syntax input.

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
                     | negative-literal
                     | identifier
                     | parenthesized-expression
                     | block
                     | compact-lambda
                     | '::' identifier argument-list
                     | match-bracket
                     | iter-bracket
                     | while-bracket
                     | operator-bracket
                     | explicit-construction
                     | typed-expression ;

parenthesized-expression
                   ::= '('
                     ( ')'
                     | 'match' match-content ')'
                     | 'cond' cond-content ')'
                     | 'iter' expression-list ')'
                     | 'while' expression-list ')'
                     | '::' identifier argument-list ')'
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

Postfix parsing is greedy and whitespace is not an expression boundary. If one
item in an `argument-list` or `expression-list` ends in an ordinary direct call
and the next item begins with `::`, the `::` is otherwise parsed as a postfix
receiver call on the preceding item. Such adjacent direct-call items require the
optional comma to become explicit, for example `(+ ::left[], ::right[])` or
`+[::left[], ::right[]]`. The same narrow separator is accepted between sibling
forms of a `program`/`block` sequence and between marker-free `match`/`cond` arms
when, and only when, the following sibling begins with `::`. Parentheses are not
general expression grouping, but the exact parenthesized direct call
`(::left[])` preserves that direct call instead of applying its result.

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

## Match and conditional expressions

```ebnf
match-bracket      ::= 'match' '[' match-content ']' ;
cond-form          ::= '(' 'cond' cond-content ')' ;
iter-bracket       ::= 'iter' '[' expression-list ']' ;
while-bracket      ::= 'while' '[' expression-list ']' ;
match-content      ::= expression match-arm* fallback-arm ;
cond-content       ::= cond-arm* fallback-arm ;
match-arm          ::= arm-head ['when' expression] '->' expression ;
arm-head           ::= expression | '_' ;
cond-arm           ::= expression '->' expression ;
fallback-arm       ::= '_' '->' expression ;
```

The obsolete `::match[...]`, `::iter[...]` and `::while[...]` bracket spellings,
the obsolete `??` arm marker, and the obsolete conditional `(match _ ...)` form
are rejected with structured source-mapped diagnostics. Arms may be separated by
a comma only when the following arm begins with `::`.

`iter`, `while`, `match`, `cond` and `when` are reserved words, not user
identifiers. `iter` and `while` are accepted only as unqualified call targets.
Iter requires a range and a callback; while requires a `Fn<;Bool>` predicate and a
`Fn<;Unit>` action. Arity and types belong to semantic checking. Every `iter`
and `while` spelling begins a fresh expression. The exact `_` in a
pattern/condition position selects the wildcard alternative rather than an
identifier expression; other occurrences of `_` remain ordinary identifiers.
These contextual exclusions apply to the `expression` alternatives above.

Every match requires a real subject and a final unguarded wildcard fallback; the
fallback may be the only arm, and no arm may follow it. `cond` has no subject, no
`when`, and the same mandatory unconditional fallback. Arms are whitespace
separated. Patterns may be arbitrary value expressions; later semantic phases
check typed equality compatibility, guard/condition truthiness, and result type
unification. No arm bindings, type patterns, or destructuring are provided.

The parenthesized and bracketed match forms produce the same match structure, and
`cond` shares their lazy arm structure without a subject. All are special forms,
not ordinary eager calls. The matcher preserves each arm's pattern, optional
guard, result, wildcard role, arrow, and source spans.

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
does not distinguish construction from indexing. Nominal construction is explicit
and separately produced:

```ebnf
explicit-construction ::= ':' named-type argument-list ;
```

An explicit construction is the only nominal-construction spelling; the obsolete
unprefixed `Type[args]` and qualified `model->:.Counter[0]` forms are rejected when
their bracket target resolves to a nominal type, while primitive/`String`
conversions and `Array`/`Tuple` literals keep their unprefixed spelling. A named
type annotation uses `:model->Counter`. Unknown type names are resolution
failures.

## Excluded syntax

There are no grammar productions for variants, destructuring
or type patterns, dedicated iteration statements, user generics, macros, `throw`, `try`, `catch`, `finally`,
varargs/default/named arguments, bitwise or shift operators, or `nor`/`nand`/
`xnor`.  Their current lexical spellings either remain ordinary identifiers or
produce the ordinary lexical/syntax diagnostic required by the living
specifications; they are never represented by a deferred-production
placeholder. `Any` has no special meaning; as with any undeclared named type,
attempting to use it as a type fails resolution.
