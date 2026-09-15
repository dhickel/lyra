# Lyra Language Specification

## 1. Status and scope

This document specifies Lyra language contract version 2. It defines the source language: source text, lexical structure, grammar, types, static semantics, evaluation, values, modules, intrinsic operations, failures, and conformance requirements.

This specification is normative for Lyra source programs. JVM descriptors, generated class layouts, compiler intermediate representations, persistent-session certificates, attachment protocols, and editor behavior are implementation matters. Section 16 identifies the limited implementation requirements that affect observable language behavior.

A source file contains one module. It has no module declaration or source edition directive. Host configuration assigns the file its canonical module identity and maps logical import paths to source files.

### 1.1 Normative terms

The key words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** state conformance requirements:

- **MUST** and **MUST NOT** impose unconditional requirements.
- **SHOULD** and **SHOULD NOT** permit a deviation only when an implementation documents a specific reason and preserves all observable semantics.
- **MAY** permits an implementation choice.

A program is *well-formed* when it satisfies the lexical, grammatical, and static requirements of this specification. An implementation MUST reject an ill-formed program with a structured compile-time diagnostic. It MUST NOT assign execution semantics to rejected syntax.

### 1.2 Current language boundary

The current language includes:

- fixed-width scalar types, arrays, ranges, tuples, functions, structs, and classes;
- static typing with local and contextual inference;
- immutable-by-default bindings and explicit mutation;
- lexical closures and exact positional calls;
- expression blocks, conditionals, value matching, callback iteration, and callback-controlled repetition;
- static modules, selective imports, aliases, and explicit exports; and
- the compiler-owned `std->io` intrinsic module.

Section 17 lists excluded capabilities. Their omission is normative. A token, syntax-tree class, runtime facility, or JVM feature does not add source-language behavior unless this specification defines it.

## 2. Notation and semantic domains

### 2.1 Grammar notation

The grammar uses extended Backus-Naur form (EBNF):

- `A ::= B` defines `A` as `B`.
- `A | B` denotes alternatives.
- `[ A ]` denotes an optional occurrence.
- `{ A }` denotes zero or more occurrences.
- `( A )` groups grammar terms.
- Quoted text denotes literal source spelling.
- `ε` denotes an empty sequence.

Lexical trivia may occur between tokens unless a production or lexical rule requires adjacency. The metavariable `comma-list(X)` expands to `X { [","] X }`. It permits one optional comma between adjacent values, but no leading, repeated, or trailing comma.

### 2.2 Static judgments

The principal static judgment is:

```text
Σ ; Γ ; Π ⊢ e : T
```

It states that expression `e` has type `T` under module environment `Σ`, lexical environment `Γ`, and mutation permissions `Π`.

Other judgments used in this document are:

```text
Σ ; Γ ⊢ d ok           declaration d is valid
T₁ ≤ T₂                T₁ is implicitly compatible with T₂
T₁ ≡ T₂                T₁ and T₂ are identical Lyra types
Π ⊢ l writable         location l may be mutated
Σ ⊢ p visible          path or member p is visible
Γ ⊢ e truth-testable   e may be used as a truth test
```

Type identity includes qualifiers and nominal declaration identity. Consequently, equality of printed field shapes does not establish `T₁ ≡ T₂` for nominal types.

### 2.3 Dynamic judgments

The principal evaluation judgment is:

```text
Σ ; ρ ; μ ⊢ e ⇓ v ; μ′
```

It states that expression `e`, evaluated in module environment `Σ`, lexical environment `ρ`, and store `μ`, produces value `v` and store `μ′`.

Invocation failure is written:

```text
Σ ; ρ ; μ ⊢ e ⇑ f ; μ′
```

The store in a failure judgment records effects completed before failure. Lyra does not roll those effects back.

### 2.4 Semantic domains

The following domains are used throughout the specification:

- `Name`: a source identifier.
- `ModuleId`: a canonical static module identity.
- `DeclId`: the identity of one declaration occurrence.
- `Cell`: mutable storage associated with an `@mut` binding.
- `Location`: a binding cell, array element, or mutable nominal member slot.
- `Closure`: a lambda body, exact function contract, and captured environment.
- `ArrayValue`: an identity-bearing fixed-length sequence of homogeneous elements.
- `TupleValue`: a fixed-shape sequence of heterogeneous values.
- `RangeValue`: immutable start, end, step, endpoint mode, and signed integer element type.
- `StructValue`: an instance of one struct declaration with current field values.
- `ClassValue`: an identity-bearing instance of one class declaration with current field and method-slot values.
- `Unit`: the single Unit value.
- `Nil`: the single nil value admitted only by an `@nil` contract.

## 3. Source text and lexical structure

### 3.1 Encoding and positions

A source file MUST be UTF-8. One initial UTF-8 byte-order mark is ignored. Malformed UTF-8 is a source diagnostic.

Authoritative source offsets are zero-based and end-exclusive. They count UTF-16 code units in the decoded text. Rendered line and column numbers are one-based and count UTF-16 code units. LF, CR, and CRLF each terminate a line; CRLF is one line ending. Supplementary Unicode characters occupy two source offsets and two columns.

### 3.2 Whitespace and comments

Whitespace is recognized according to Unicode whitespace and space-character classification. Except where adjacency is required, whitespace separates tokens and has no semantic effect.

A line comment begins with `//` and continues to, but does not include, the next line break. A block comment begins with `/*` and ends at the matching `*/`. Block comments nest.

Comments count as trivia. A comment between `-` and a numeric token prevents the adjacent negative-literal form. A comment also prevents any other token adjacency required by this specification.

### 3.3 Identifiers and reserved words

```ebnf
ascii-letter     ::= "A" | ... | "Z" | "a" | ... | "z" ;
ascii-digit      ::= "0" | ... | "9" ;
identifier       ::= (ascii-letter | "_") { ascii-letter | ascii-digit | "_" } ;
capitalized-name ::= ("A" | ... | "Z") { ascii-letter | ascii-digit | "_" } ;
```

Identifiers are case-sensitive. Non-ASCII letters are not identifier characters. Punctuation, including `?`, is not identifier text.

The reserved words are:

```text
let  struct  class  import  as  match  cond  iter  while  when
```

The word operators `and`, `or`, `xor`, and `not` are also lexical tokens. The built-in type names in Section 6 are reserved as well. None can be declared as an identifier.

`_` is an ordinary identifier except in a match or `cond` arm-head position, where the exact token `_` denotes a wildcard. It is not a value, a type, or a general discard form.

### 3.4 Modifiers

The complete modifier vocabulary is:

```text
@pub  @mut  @nil
```

An unknown modifier is a lexical error. Duplicate modifiers and modifiers used in a forbidden position are static errors.

### 3.5 Delimiters and punctuation

Lyra uses the following punctuation tokens:

```text
( )  { }  [ ]  < >  |  ,  ;  :  =
:=  ->  :.  ::  =>  ..  ...
```

`??` is tokenized only so an implementation can issue the migration diagnostic required by Section 17. It is not current syntax. A standalone period is not a current expression operator.

Commas are not globally insignificant. They are accepted only in the list contexts and narrow sibling boundary defined in Sections 4.1 and 4.6.

### 3.6 Annotation and construction spacing

A named annotation is written with whitespace before the colon and no trivia after it:

```lyra
name :I32
value :@nil String
```

`name:I32` and `name : I32` are invalid. A return annotation has no preceding name and is written `:Type`. Explicit construction likewise requires the colon to be adjacent to the first type-path segment, as in `:Counter[]`.

Qualifiers that are part of a type contract may follow the colon. No trivia may intervene between the colon and the first qualifier or type token. Ordinary token separation applies after a qualifier, as in `:@nil String`.

### 3.7 Literals

```ebnf
boolean-literal ::= "#T" | "#F" ;
nil-literal     ::= "#NIL" ;
unit-literal    ::= "(" ")" | "Array" "[" "]" | "Tuple" "[" "]" ;
integer         ::= ascii-digit { ascii-digit } [integer-suffix] ;
decimal         ::= ascii-digit { ascii-digit } "." ascii-digit { ascii-digit }
                    [("e" | "E") ["+" | "-"] ascii-digit { ascii-digit }]
                    [float-suffix] ;
integer-suffix  ::= "I8" | "I16" | "I32" | "I64"
                  | "U8" | "U16" | "U32" | "U64" ;
float-suffix    ::= "F32" | "F64" ;
negative-literal ::= "-" integer | "-" decimal ;
```

The `-` in `negative-literal` MUST be adjacent to the numeric token and occur where an expression may begin. The numeric magnitude is lexed as nonnegative, then unary negation is applied. This preserves signed minima, exact decimal parsing, contextual typing, suffix checking, unsigned rejection, negative floating zero, and arithmetic failures. `- 1`, `-/*comment*/1`, and `--1` are not negative literals.

An exponent is permitted only on a decimal containing a point. Integer and decimal magnitudes are retained exactly through static analysis. A suffixed literal must be representable by its suffix type. A decimal that rounds to infinity or underflows from a nonzero exact value to zero in its selected floating type is invalid.

Strings are delimited by double quotes. Characters are delimited by single quotes. Their escapes are:

```text
\\  \"  \'  \n  \r  \t  \b  \f  \0  \uXXXX
```

`XXXX` is exactly four hexadecimal digits. An unescaped line break or control character is invalid. A character literal must decode to exactly one UTF-16 code unit. Unpaired surrogate code units are permitted.

## 4. Concrete grammar

### 4.1 Compilation units and imports

```ebnf
program             ::= { import-declaration }
                        { nominal-declaration | form } EOF ;

form                ::= let-binding | reassignment | expression ;

import-declaration  ::= "import" ["@pub"] import-path import-suffix ;
import-suffix       ::= ε
                      | "as" identifier
                      | "->" "{" import-item { import-item } "}" ;
import-path         ::= identifier { "->" identifier } ;
import-item         ::= identifier ["as" identifier] ;
```

A selective import contains at least one item. All imports precede every non-import form. `import @pub` is grammatical only with a selective import.

At module or block level, forms require no terminator. A comma may occur between sibling forms only when the next form begins with unqualified `::`. No leading, repeated, trailing, or other sequence comma is valid.

### 4.2 Nominal declarations

```ebnf
nominal-declaration ::= struct-declaration | class-declaration ;
struct-declaration  ::= "struct" ["@pub"] capitalized-name
                        "{" { member-declaration } "}" ;
class-declaration   ::= "class" ["@pub"] capitalized-name
                        "{" { member-declaration | constructor-declaration } "}" ;
member-declaration  ::= "let" { modifier } identifier named-annotation
                        ["=" expression] ;
constructor-declaration ::= capitalized-name "=" lambda ;
```

Nominal declarations occur only at module level. A constructor name must equal its enclosing class name. A class has at most one constructor. A struct has none.

### 4.3 Bindings, assignment, blocks, and lambdas

```ebnf
let-binding         ::= "let" { modifier } identifier [named-annotation]
                        "=" expression ;
reassignment        ::= expression ":=" expression ;
prefix-assignment   ::= "(" ":=" expression expression ")" ;
block               ::= "{" { form } "}" ;

lambda              ::= "(" "=>" { return-modifier } [return-annotation]
                        parameter-list expression ")" ;
compact-lambda      ::= parameter-list expression ;
parameter-list      ::= "|" [comma-list(parameter)] "|" ;
parameter           ::= { modifier } identifier [named-annotation] ;
return-modifier     ::= "@nil" ;
named-annotation    ::= ":" type ;
return-annotation   ::= ":" type ;
```

The optional comma rule applies to every `comma-list` production below.

### 4.4 Types

```ebnf
type                ::= { type-qualifier } type-base ;
type-qualifier      ::= "@mut" | "@nil" ;
type-base           ::= primitive-type | array-type | range-type
                      | tuple-type | function-type | named-type ;
primitive-type      ::= "I8" | "I16" | "I32" | "I64"
                      | "U8" | "U16" | "U32" | "U64"
                      | "F32" | "F64" | "Bool" | "Char"
                      | "String" | "Unit" ;
array-type          ::= "Array" "<" type ">" ;
range-type          ::= "Range" "<" type ">" ;
tuple-type          ::= "Tuple" "<" comma-list(type) ">" ;
function-type       ::= "Fn" "<" function-parameters ";" type ">" ;
function-parameters ::= ε | comma-list(type) ;
named-type          ::= identifier { "->" identifier } ;
```

A tuple type has at least one member. `Fn<;R>` is a zero-parameter function type. Syntactic acceptance of a qualifier does not establish that the qualifier is legal in that type position.

### 4.5 Expressions

```ebnf
expression          ::= atom { postfix } ;
atom                ::= literal
                      | negative-literal
                      | identifier
                      | parenthesized-expression
                      | block
                      | compact-lambda
                      | unqualified-direct-call
                      | match-bracket
                      | iter-bracket
                      | while-bracket
                      | operator-bracket
                      | explicit-construction
                      | typed-application ;

literal             ::= boolean-literal | nil-literal | unit-literal
                      | integer | decimal | string-literal | character-literal ;

postfix             ::= argument-list
                      | ":." member-name
                      | "::" identifier argument-list
                      | namespace-suffix ;
namespace-suffix    ::= "->" [identifier { "->" identifier } ["->"]]
                        (":." member-name | "::" identifier argument-list) ;
member-name         ::= identifier | unsigned-decimal-integer ;

argument-list       ::= "[" [comma-list(expression)] "]" ;
unqualified-direct-call ::= "::" identifier argument-list ;
```

Postfix parsing is greedy and whitespace does not terminate an expression. `value [index]`, `value[index]`, and a line break before `[` have the same postfix structure.

Parentheses do not group an arbitrary expression. They introduce one of the forms in Section 4.6. The exact form `(::name[arguments])` preserves that direct call as one expression. `(::name[arguments] extra)` instead calls the value produced by the direct call.

### 4.6 Parenthesized and reserved forms

```ebnf
parenthesized-expression ::=
    "(" ")"
  | "(" "match" match-content ")"
  | "(" "cond" cond-content ")"
  | "(" "iter" expression-list ")"
  | "(" "while" expression-list ")"
  | "(" "::" identifier argument-list ")"
  | lambda
  | prefix-assignment
  | "(" operator expression-list ")"
  | "(" expression [identifier] "->" expression [":" expression] ")"
  | "(" expression ":" expression ")"
  | "(" expression range-token expression ":" expression ")"
  | "(" expression { expression } ")" ;

expression-list     ::= [comma-list(expression)] ;
range-token         ::= ".." | "..." ;

match-bracket       ::= "match" "[" match-content "]" ;
cond-form           ::= "(" "cond" cond-content ")"
                       | "cond" "[" cond-content "]" ;
match-content       ::= expression { match-arm } fallback-arm ;
match-arm           ::= arm-head ["when" expression] "->" expression ;
arm-head            ::= expression | "_" ;
fallback-arm        ::= "_" "->" expression ;

cond-content        ::= { cond-arm } fallback-arm ;
cond-arm            ::= expression "->" expression ;

iter-bracket        ::= "iter" "[" expression-list "]" ;
while-bracket       ::= "while" "[" expression-list "]" ;
```

Match and `cond` arms are normally separated only by their syntactic structure and trivia. A comma may precede an arm only when that arm begins with unqualified `::`. This is the same narrow ambiguity boundary used for module and block siblings.

In an argument, parameter, type, array, tuple, or operator operand list, commas are optional separators. One is needed when a following unqualified `::` would otherwise be parsed as a postfix receiver call on the preceding expression:

```lyra
(+ ::left[], ::right[])
+[::left[], ::right[]]
```

Without the comma, the second call attaches to the result of `::left[]`.

### 4.7 Operators, aggregates, conversions, and construction

```ebnf
operator            ::= "+" | "-" | "*" | "/" | "%" | "^"
                      | "<" | "<=" | ">" | ">="
                      | "==" | "!=" | "eq?" | "!eq?"
                      | "and" | "or" | "xor" | "not" | "++" | "--" ;
operator-bracket    ::= operator argument-list ;

array-literal       ::= "Array" "[" comma-list(expression) "]"
                      | array-type argument-list ;
tuple-literal       ::= "Tuple" "[" comma-list(expression) "]"
                      | tuple-type argument-list ;
primitive-conversion ::= primitive-type argument-list ;
typed-application   ::= array-literal | tuple-literal | primitive-conversion ;

explicit-construction ::= ":" named-type argument-list ;
```

Operator arity is a grammar requirement:

- `+`, `*`, comparisons, equality, identity, `and`, `or`, and `xor` require at least two operands.
- `-` and `/` require at least one operand.
- `%` and `^` require exactly two operands.
- `not`, `++`, and `--` require exactly one operand.

Assignment has infix and parenthesized prefix forms. It has no bracket form.

The compiler-recognized bare forms are the operators, primitive and `String` conversions, `Array` and `Tuple` literals, `match`, `cond`, `iter`, and `while`. They are syntax rather than resolved callable names. Prefixing any of them with `::`, or selecting one through a receiver or namespace, is invalid. `cond` has only its parenthesized special form; the other forms use exactly the spellings defined above.

## 5. Names, declarations, and scopes

### 5.1 Single namespace

Types and values occupy one namespace. A declaration occurrence has an identity independent of its spelling. An import alias changes the local spelling but not the imported declaration or module identity.

Modules, blocks, and lambdas create lexical scopes. A reference denotes the most recent visible declaration selected by lexical nesting and source order.

### 5.2 Bindings

Every named value, including a named function, is declared with `let`:

```lyra
let count :I32 = 0
let @mut total :I64 = 0
let @pub transform :Fn<I32;I32> = (=> |value| (+ value 1))
let @nil label :String = #NIL
```

An ordinary `let` requires an initializer. A private declaration may reuse a name in the same scope. The later declaration creates a new identity visible from its source position onward. Earlier references and closures continue to denote the earlier identity, and the replacement may have a different type.

A public top-level name and a re-exported name cannot be redeclared in the same module. Public value declarations and public callable or member signatures require complete explicit types.

### 5.3 Source order and recursive functions

Ordinary values are not hoisted and cannot be read before initialization. A `let` whose initializer is a lambda with a complete function contract is signature-predeclared. Signature predeclaration permits self recursion, forward function references, and mutual recursion. It does not evaluate any function body or make an eager value available early.

A cycle consisting only of linked function declarations is permitted. A cycle that requires an eager value before its initialization completes is a compile-time module-initialization error.

### 5.4 Parameters and captures

Parameters are immutable unless marked `@mut`. Parameter names are unique within one lambda.

A closure captures each referenced lexical binding. An immutable capture retains the selected value or reference. Every capture of one `@mut` binding refers to the same mutable cell. Rebinding that cell is visible to all closures that captured it.

## 6. Type system

### 6.1 Types

Every expression has one compile-time type. Lyra has no `Any` type, dynamic type, implicit union, implicit member lookup, or untyped callable.

The primitive types are:

| Kind | Types |
|---|---|
| Signed integers | `I8`, `I16`, `I32`, `I64` |
| Unsigned integers | `U8`, `U16`, `U32`, `U64` |
| Floating point | `F32`, `F64` |
| Other scalars | `Bool`, `Char`, `String`, `Unit` |

The built-in composite types are:

- `Array<T>`, a homogeneous fixed-size array;
- `Range<T>`, where `T` is one of `I8`, `I16`, `I32`, or `I64`;
- `Tuple<T1,...,Tn>`, where `n ≥ 1`; and
- `Fn<P1,...,Pn;R>`, an exact positional function contract.

Struct and class declarations introduce nominal types. Two nominal types are identical only when they originate from the same declaration identity and module revision. Composite types are invariant.

### 6.2 Qualifiers and modifiers

`@pub` controls module or member visibility. It is not part of a value type.

`@mut` grants mutation permission at a binding or parameter boundary. It may qualify a binding or function parameter contract. It is forbidden on a function return and in an array or tuple element contract. In `Fn<@mut Array<I32>;Unit>`, it qualifies the parameter contract rather than the array element.

`@nil` admits `#NIL` in addition to the unqualified base type. It may qualify a binding, parameter, return, nominal member, or nested value contract. These types differ:

```text
@nil Array<String>    the array reference may be nil
Array<@nil String>    each element may be nil
```

The same qualifier may be written as a declaration modifier or within the annotated type where the grammar permits it. Its semantic effect is one type qualifier. Repeating it is invalid.

`@pub` is valid on top-level `let` declarations, nominal declarations in the position specified by Section 4.2, class members, and selective imports. Struct fields are public by default, so `@pub` is redundant there and does not alter type identity. `@pub` is not valid on local bindings, parameters, returns, or nested types.

### 6.3 Inference and contextual typing

A local binding may omit its annotation when the initializer and expected context determine one complete type. Public contracts cannot use this omission.

Inference is local and bidirectional. Context may determine:

- the type of an unsuffixed numeric literal;
- the base type of `#NIL`;
- the element contract of a nonempty untyped `Array[...]`; an empty array still requires `Array<T>[]`, and an all-`#NIL` array requires context;
- omitted lambda parameter and return annotations;
- branch and match-result types; and
- whether an `iter` compact callback has zero or one parameter.

Inference does not use unconstrained recursive calls, dynamic call sites, or runtime values to invent a type.

### 6.4 Nilability

For every value type `T`, `@nil T` contains every value of `T` and `#NIL`.

```text
Σ ; Γ ; Π ⊢ e : T
────────────────────────  nil lift
Σ ; Γ ; Π ⊢ e : @nil T
```

`#NIL` requires an expected `@nil T`; it cannot infer `T` alone. An `@nil T` value cannot be used as `T` for arithmetic, indexing, member access, construction, or an ordinary non-nil parameter. It must first be narrowed by a predicate binding or consumed by nil coalescing.

A nilable value may be passed to an identical nilable contract, compared with `#NIL`, or used as a truth test. Identity comparison requires non-nil operands.

### 6.5 Implicit numeric compatibility

Let `D(T)` denote the complete mathematical value domain of numeric type `T`. An implicit numeric conversion exists only when `D(S) ⊆ D(T)` under the following closed rules:

| Source | Permitted implicit destinations, including identity |
|---|---|
| `I8` | `I8`, `I16`, `I32`, `I64`, `F32`, `F64` |
| `I16` | `I16`, `I32`, `I64`, `F32`, `F64` |
| `I32` | `I32`, `I64`, `F64` |
| `I64` | `I64` |
| `U8` | `U8`, `U16`, `U32`, `U64`, `I16`, `I32`, `I64`, `F32`, `F64` |
| `U16` | `U16`, `U32`, `U64`, `I32`, `I64`, `F32`, `F64` |
| `U32` | `U32`, `U64`, `I64`, `F64` |
| `U64` | `U64` |
| `F32` | `F32`, `F64` |
| `F64` | `F64` |

No signed integer converts implicitly to an unsigned integer. `I64` and `U64` do not convert implicitly to floating point.

A mixed numeric operation selects the first type in the implementation-independent widening order

```text
I8, U8, I16, U16, I32, U32, I64, U64, F32, F64
```

that can contain every statically typed operand domain after contextual literal typing. A complete expected numeric type is selected when it is a valid common type. If no common type exists, explicit conversion is required.

Unsuffixed integers default to `I64`; unsuffixed decimals default to `F64` when no context selects another type.

### 6.6 Explicit conversions

A primitive conversion is written `Target[value]` and has exactly one argument. Numeric-to-numeric conversion is explicit when no implicit conversion applies. It is a value conversion, not a bit reinterpretation.

A numeric conversion MUST reject a source value that is outside the destination domain, non-finite, non-integral for an integer destination, or nonzero but underflows to zero in the destination floating type. A statically known invalid conversion is a compile-time error. Otherwise failure is invocation-fatal with category `LYR-CONVERT`.

`String[value]` accepts `String`, every numeric type, `Bool`, `Char`, and `Unit`. It does not accept arrays, tuples, ranges, functions, structs, classes, or nil. Section 7.5 defines the resulting text.

No other primitive conversion is defined.

### 6.7 Assignment compatibility

Ignoring mutation permission, `S ≤ T` holds when:

1. `S ≡ T`;
2. `S` is non-nil `T` and the destination is `@nil T`; or
3. both unqualified types are primitive numeric types and Section 6.5 permits widening.

Dropping `@mut` permission is allowed when a value enters an immutable contract. Acquiring `@mut` permission through assignment or argument passing is not allowed unless the source expression denotes a binding with that permission. Composite and nominal types do not vary through their component types.

## 7. Values and equality

### 7.1 Unit, Boolean, nil, and characters

`()` is the Unit value. `Array[]` and `Tuple[]` are alternative spellings of that same value, not empty aggregate values.

`#T` and `#F` are the Boolean values. `#NIL` is the nil value. A `Char` is exactly one UTF-16 code unit, including an unpaired surrogate or `U+0000`.

### 7.2 Numeric values

Signed and unsigned integers denote mathematical integers in their declared fixed-width domains. Arithmetic is checked and never wraps as a language operation. Floating values are finite IEEE 754 binary32 or binary64 values. Ordinary Lyra evaluation does not produce NaN or infinity.

### 7.3 Arrays

An array is a fresh identity-bearing value each time an array literal evaluates. Its length is fixed. Elements are homogeneous under the exact `Array<T>` contract.

```lyra
let values :Array<I32> = Array[1 2 3]
let empty :Array<I32> = Array<I32>[]
```

A nonempty `Array[...]` may infer one element contract from its elements, while a complete expected `Array<T>` may provide that contract contextually. `Array<T>[...]` supplies it explicitly. An empty array requires `Array<T>[]`, and an all-`#NIL` array requires contextual typing. Elements evaluate once, left-to-right.

`array[index]` returns the selected element. Every integer type is accepted as an index. The mathematical index must satisfy `0 ≤ index < length`; checking occurs before narrowing an unsigned or wide value to an implementation index. Failure is `LYR-BOUNDS`.

`array:.length` returns the number of elements as `I32`.

`==` compares array contents recursively. `eq?` compares array identity. Copying an array reference does not copy its storage.

### 7.4 Tuples

A tuple contains one or more positionally typed values. `Tuple[a b]` infers its member types; `Tuple<A,B>[a b]` states them. Elements evaluate once, left-to-right.

Tuple fields are immutable and are selected by zero-based compile-time positions:

```lyra
let pair :Tuple<I32,String> = Tuple[7 "x"]
pair:.0
pair:.1
```

An invalid tuple position is a compile-time error. Tuples have structural value equality and no identity equality.

### 7.5 Strings

A string is an immutable sequence of UTF-16 code units. `string[index]` returns the selected code unit as `Char`. `string:.length` returns the code-unit count as `I32`. Index failures use `LYR-BOUNDS`.

String `==` and `!=` compare UTF-16 content. Relational ordering is not defined. Strings do not support identity comparison.

`String[value]` produces:

- base-10 mathematical integer text without leading zeroes;
- shortest round-tripping finite decimal text for `F32` or `F64`, using lowercase `e`, no redundant exponent sign or zeroes, and preserving `-0.0`;
- `#T` or `#F` for Boolean values;
- a one-code-unit string for a character;
- the original content for a string; and
- `()` for Unit.

No operation implicitly converts another value to String.

### 7.6 Functions

A function value has identity. Every dynamic lambda evaluation creates a distinct function identity. Re-reading one unchanged binding or unchanged method slot returns the same selected identity. Function values do not support structural `==`; use `eq?` or `!eq?`.

### 7.7 Nominal values

Struct and class instances are reference values with declaration-based type identity. Copying either kind copies the reference and does not clone storage.

Struct `==` compares current fields structurally and recursively. Class `==` compares instance identity. Nested class fields therefore compare by identity. Equality over cyclic struct data MUST terminate by tracking already compared object pairs. Equality does not invoke user code.

Classes are identity-bearing under `eq?`. Structs are not.

### 7.8 Value and identity relations

For an n-ary relation `R`, Lyra applies a chained comparison:

```text
R(v₁,...,vₙ) = R(v₁,v₂) ∧ R(v₂,v₃) ∧ ... ∧ R(vₙ₋₁,vₙ)
```

This rule applies to relational, value-equality, value-inequality, identity-equality, and identity-inequality operators. Thus `!=[1 2 3]` is true because each adjacent pair differs.

When at least one operand of `==` or `!=` has static base type `Bool`, all operands must be truth-testable and the operation compares their truth values. This permits, for example, a contextually typed nil value to compare equal to `#F`. Otherwise `==` and `!=` use compatible typed value equality.

Identity operands must have the same non-nil identity-bearing static type.

## 8. Evaluation model

### 8.1 Strict order

Lyra evaluation is strict and eager. The following evaluate left-to-right:

- call target or receiver, then arguments;
- operator operands;
- array and tuple elements;
- range start, end, and step;
- nominal construction arguments;
- declarations and expressions in a block or module; and
- callback expressions passed to `iter` and `while`.

The selected call target is fixed before any argument evaluates. If argument evaluation mutates the binding or method slot from which the target was selected, the current call still invokes the selected value.

Only `and`, `or`, conditionals, nil coalescing, match-arm selection, and `cond` selection suppress evaluation of unselected expressions.

### 8.2 Blocks

A block creates a lexical scope, evaluates its forms in source order, and returns the value of its final expression. An empty block returns Unit. A declaration form itself contributes Unit when its value is needed as an intermediate block form.

### 8.3 Truth tests

The following values are false:

- `#F` and `#NIL`;
- every signed or unsigned numeric zero;
- floating `+0.0` and `-0.0`;
- the empty string;
- an empty array; and
- Unit.

The following values are true:

- `#T`;
- every nonzero finite number;
- every nonempty string or array;
- every tuple;
- every function; and
- every character, including `U+0000`.

A non-nil `Range<T>` is not truth-testable. Boolean operators return `Bool`, never an operand.

Every nilable contract is truth-testable. A nilable value is false when it is `#NIL`. A present value follows the rules above when its base type is otherwise truth-testable. A present value whose base type is not otherwise truth-testable is true.

Every non-nil nominal reference is true. The compiler and backend apply this rule to `struct` and `class` values.

## 9. Operators

Every compiler-recognized operator other than assignment has equivalent S-expression and bare bracket spellings:

```lyra
(+ a b c)
+[a b c]
```

Assignment uses infix `:=` and its parenthesized prefix form; it has no bracket spelling. A compiler-recognized operator is not a callable value and MUST NOT be prefixed with `::`.

### 9.1 Arithmetic

| Operator | Arity | Operand contract | Result |
|---|---:|---|---|
| `+` | at least 2 | all numeric, or all `String` | common numeric type, or `String` |
| `-` | at least 1 | numeric | common numeric type |
| `*` | at least 2 | numeric | common numeric type |
| `/` | at least 1 | numeric | Section 9.2 |
| `%` | exactly 2 | integer, one common integer type | common integer type |
| `^` | exactly 2 | numeric | resolved numeric type |
| `++` | exactly 1 | numeric | operand type |
| `--` | exactly 1 | numeric | operand type |

Multi-operand `-`, `/`, `+`, and `*` fold left. Unary `-` negates. Unary `/` computes the reciprocal. `++` and `--` add or subtract one without mutating their operand.

Integer addition, subtraction, multiplication, negation, increment, decrement, remainder, and exponentiation are checked against the result type. Signed overflow and unsigned overflow or underflow fail with `LYR-ARITH`. A constant failure is diagnosed at compile time.

Integer exponentiation requires a nonnegative integer exponent. Floating exponentiation accepts the resolved numeric operands. Any non-finite floating result fails with `LYR-ARITH`.

String `+` concatenates all operands in order. Numeric and string operands cannot be mixed.

### 9.2 Division

Integer-only `/` produces `F64`, except that a complete expected `F32` context selects `F32`. This operator-defined conversion may round even when an ordinary implicit integer-to-floating conversion is forbidden.

Division and remainder by zero fail with `LYR-ARITH`. Floating operations fail when they produce NaN or infinity.

### 9.3 Boolean operators

`and` and `or` accept at least two truth-testable operands and short-circuit left-to-right. `and` stops at the first false operand; `or` stops at the first true operand. Their result is the resulting truth value as `Bool`.

`xor` accepts at least two truth-testable operands, evaluates every operand, and returns true exactly when an odd number are true. `not` returns the Boolean negation of one truth test.

### 9.4 Comparison

`<`, `<=`, `>`, and `>=` accept at least two numeric operands. They choose one common numeric type and compare adjacent operands as a chain.

`==` and `!=` implement Section 7.8. `eq?` and `!eq?` compare identity for arrays, functions, and class instances of one exact static type.

## 10. Functions, calls, and accessors

### 10.1 Lambda contracts

The full lambda form is:

```lyra
(=> ReturnModifier* [:ReturnType] |parameters| body)
```

Every lambda requires one complete `Fn` contract. Context may provide it:

```lyra
let transform :Fn<I32;I32> = (=> |value| (+ value 1))
```

Without a complete expected function type, all parameters and the return must be annotated inline:

```lyra
let transform = (=> :I32 |value :I32| (+ value 1))
let lookup = (=> @nil :String |key :String| #NIL)
```

Redundant inline annotations are valid only when they agree with context. `@mut` is valid on parameters but not returns. `@nil` is valid on either.

The compact form `|parameters| body` is accepted only where a complete expected `Fn` type exists. It cannot directly initialize any named `let` declaration.

A call requires exact positional arity. Lyra has no default, named, omitted, or variable arguments, no currying, and no partial application.

### 10.2 Call forms

Lyra distinguishes these operations:

```lyra
(callee arg1 arg2)              // call a callable value
::callee[arg1 arg2]             // direct call of a resolved name
receiver::method[arg1 arg2]     // direct call of a selected member slot
receiver:.field                 // read a field or value member
receiver:.method                // read a receiver-bound callable value
module->::function[arg]         // namespace direct call
module->:.value                 // namespace value access
```

`::` always includes brackets. Bare `::name` is invalid. `:.` never invokes a callable.

If `(name args)` and `::name[args]` resolve to the same declaration and storage route, they have identical target selection, argument order, result, failures, source behavior, and tail position. Syntax, a matching function type, or a matching name does not by itself establish that route.

A computed target, including an indexed function or returned closure, is called through the callable-value form. `(receiver:.method args)` calls the value obtained by member access.

### 10.3 Member selection

Strings and arrays expose read-only `:.length`. Tuples expose zero-based numeric members. Nominal members are determined by the exact declared nominal type and visibility rules.

Reading a class method slot selects its current callable and binds the receiver. The resulting value retains that receiver and slot selection. Later replacement of the slot changes later reads, but does not retarget a saved method reference.

A direct member call evaluates the receiver once and selects the current slot before explicit arguments. It does not add an explicit receiver to the declared function arity.

### 10.4 Tail recursion

A conforming implementation MUST execute a proven direct self-tail call with constant stack. This applies to either equivalent named-call spelling and to tail positions in conditional, match, and `cond` results.

For a mutable self function slot, loop execution is valid only while the selected slot still contains the executing closure. If the slot was rebound or cannot be proven identical, evaluation performs an ordinary function invocation. Mutual and non-tail recursion use ordinary invocation and may fail with `LYR-STACK`.

## 11. Conditional expressions and matching

### 11.1 Full conditionals

```lyra
(predicate -> then_expression : else_expression)
(predicate binding -> then_expression : else_expression)
```

The predicate evaluates once. Only the selected branch evaluates. The optional binding is an unannotated identifier scoped to the true branch. If the predicate has type `@nil T`, the binding has type `T`; otherwise it has the predicate type with mutation permission removed.

The branch types must be identical or admit one common type through nil lifting and permitted numeric widening. The expression has that common type.

### 11.2 Then-only conditionals

```lyra
(predicate -> expression)
(predicate binding -> expression)
```

The branch evaluates only when the predicate is true. The conditional always returns Unit, regardless of the branch expression's type.

### 11.3 Nil coalescing

```lyra
(nilable_value : fallback)
```

The first expression must have type `@nil T`. It evaluates once. If it is present, the expression returns that `T` value even when it is false under truth testing. If it is `#NIL`, only then does the fallback evaluate. The fallback must be compatible with `T`, and the result type is `T` or the contextually established compatible type.

### 11.4 Value match

A value match always has a real subject:

```lyra
(match value
  10 -> "ten"
  _ when (> value 20) -> "greater"
  _ -> "other")

match[value
  10 -> "ten"
  _ -> "other"
]
```

The subject evaluates exactly once. Arms are attempted in source order. A non-wildcard pattern is an arbitrary expression evaluated only when its arm is reached. It is compared with the subject using the static compatibility and value equality rules of `==`. Numeric compatibility is chosen independently for each pattern.

A wildcard passes without evaluating a pattern. An optional `when` guard evaluates only after its pattern succeeds and uses ordinary truth testing. A false guard continues to the next arm. Only the selected result evaluates.

Every match ends with an unguarded `_ -> fallback`. A fallback-only match is valid and still evaluates its subject. No arm follows the fallback. Match introduces no bindings, performs no destructuring or type test, and does not narrow a nilable subject.

All result expressions must have one common type under the same rules as full conditional branches.

### 11.5 `cond`

`cond` has no subject and equivalent parenthesized and direct-bracket spellings:

```lyra
(cond
  (< value 0) -> 0
  (> value 128) -> 128
  _ -> -1)

cond[
  (< value 0) -> 0
  (> value 128) -> 128
  _ -> -1
]
```

Each non-wildcard arm contains one condition expression. Conditions evaluate once each, in source order, until one is true. Only that arm's result evaluates. `when` is forbidden. A final `_ -> fallback` is mandatory and may be the only arm. Result typing is identical to value-match result typing.

`(match _ ...)` is obsolete and invalid. The parenthesized and direct-bracket `cond` spellings have identical lazy semantics.

## 12. Mutation and aliasing

### 12.1 Binding permission

Bindings are immutable unless marked `@mut`. Rebinding uses either form:

```lyra
count := (+ count 1)
(:= count (+ count 1))
```

Both forms return Unit. The target is evaluated before the replacement value where target evaluation is meaningful.

Mutation permission belongs to a binding, not transitively to a value. An immutable array reference may initialize a new `@mut` binding. Mutation through the latter changes the shared array observed through every alias.

A function parameter through which the function mutates must be marked `@mut`, and the function type records that qualifier. Calling such a function requires an argument expression with corresponding mutation permission.

### 12.2 Aggregate mutation

Array element replacement uses the assignment operator:

```lyra
values[index] := replacement
```

The root binding through which the array is reached must be `@mut`. The index is checked before the store. A failed index or conversion leaves the selected element unchanged.

Tuples and strings cannot be mutated. No assignment expression implicitly grows or copies an aggregate.

### 12.3 Imported values

A module importing an exported `@mut` binding may read it but cannot rebind it or mutate an aggregate reached through it. Rebinding an imported aggregate into a local `@mut` name does not transfer ownership. The declaring module may expose mutation through functions whose contracts authorize it.

## 13. Ranges and callback loops

### 13.1 Range construction

A range is enclosed and always has an explicit step:

```lyra
let ascending :Range<I32> = (0..100:1)
let descending :Range<I32> = (100...0:-1)
```

`..` excludes the endpoint. `...` includes the endpoint only when traversal reaches it. `(0...5:2)` therefore visits `0`, `2`, and `4`.

Start, end, and step evaluate exactly once, left-to-right. They must resolve to one of `I8`, `I16`, `I32`, or `I64`. A complete expected `Range<T>` may provide that type. Range values are immutable, reusable, storable, passable, returnable, and capturable. Each traversal starts from the saved start.

A zero step is invalid at compile time when constant and fails with `LYR-ARITH` otherwise. A step directed away from the endpoint produces an empty traversal. Equal exclusive endpoints produce no element; equal inclusive endpoints produce one. Completion MUST NOT perform an overflowing increment after the final element.

### 13.2 `iter`

`iter` has two equivalent special-form spellings:

```lyra
iter[(0..100:1) |x| ::consume[x]]
(iter (0..100:1) || ::tick[])
```

It takes exactly two expressions: a `Range<T>` and either `Fn<T;Unit>` or `Fn<;Unit>`. It returns Unit. A compact anonymous callback's parameter count selects between these two expected contracts. This selection is specific to `iter` and is not general overloading.

The range expression and callback expression evaluate once, in that order. Traversal invokes the selected callback synchronously once per element, with either the current element or no arguments. An empty range invokes no callback. Every callback parameter is a fresh immutable binding for that invocation. Failure aborts traversal immediately.

### 13.3 `while`

`while` also has bracket and parenthesized special-form spellings:

```lyra
while[|| (< count 10) || { count := (++ count) }]
(while || (< count 20) || { count := (++ count) })
```

It takes exactly `Fn<;Bool>` and `Fn<;Unit>`, in that order, and returns Unit. General truthiness is not accepted for the predicate return. Neither callback receives an implicit argument.

The predicate expression and action expression evaluate once, left-to-right, before the first predicate invocation. Each iteration invokes the retained predicate first. False completes the loop; true invokes the retained action and repeats. Even a zero-action traversal invokes the predicate once, and that invocation's effects remain observable.

Rebinding the original callback binding after selection does not replace the selected callback. Captured mutable cells remain live. Failure in either callback aborts the loop.

Both callback loops require constant-stack repetition. Lyra has no `break`, `continue`, `return`, `do while`, or dedicated loop-statement syntax.

## 14. Structs and classes

### 14.1 Declaration and identity

A struct or class declaration introduces one concrete nominal type. Names begin with an uppercase ASCII letter. Type identity derives from the declaration and originating module, not capitalization, field shape, or import alias.

```lyra
struct Vec2 {
    let @mut x :F64
    let @mut y :F64
}

class Counter {
    let @mut value :I32

    Counter = (=> |start :I32| {
        self:.value := start
    })

    let @pub @mut increment :Fn<;Unit> = (=> || {
        self:.value := (++ self:.value)
    })

    let @pub current :Fn<;I32> = (=> || self:.value)
}
```

A nominal type is private unless the declaration contains `@pub`. Every member has an explicit complete type. Member names are unique within the type.

Struct fields are public by default. Structs contain data only and cannot contain a function contract, including one nested in an array, tuple, or struct field. Class fields and function-valued method slots are private unless marked `@pub`.

Privacy is lexical. Code in the declaring class may access a private member through any instance of that class. Supplying contextual `self` to a replacement lambda does not grant private access to code defined outside the class.

### 14.2 Construction syntax

Construction is explicit:

```lyra
let position :Vec2 = :Vec2[10.0 20.0]
let counter :Counter = :Counter[0]
let imported :model->Counter = :model->Counter[0]
```

The arguments evaluate exactly once, left-to-right, before initialization begins. Primitive conversions, `String[value]`, and `Array` or `Tuple` literals retain their unprefixed forms.

Unprefixed `Type[arguments]` does not construct a nominal value. When it resolves to a nominal type, it receives a migration diagnostic. Capitalization never changes an ordinary bracket access into construction, so `Values[1]` indexes a value named `Values`.

### 14.3 Struct initialization

A struct field without an initializer is a positional construction parameter. Such parameters follow declaration order. A field with an initializer is not an optional parameter.

Required fields receive their argument values before field initializers run. Remaining field initializers run in declaration order. Construction returns the new struct instance.

### 14.4 Class initialization

A class may declare one constructor:

```lyra
ClassName = (=> |typed parameters| body)
```

Its parameters are completely typed. Its required return contract is Unit. A redundant inline return annotation must agree. The constructor body returns Unit, while the construction expression returns the initialized class instance.

Without an explicit constructor, zero-argument construction is valid only when every field has an initializer. There is no implicit positional class constructor.

Class field initializers run in declaration order before the constructor body. Method slots may capture the constructing receiver when installed. That capture alone does not publish the receiver.

### 14.5 Definite initialization

Every field must be initialized on every completing constructor path. An immutable field is initialized exactly once. The following are compile-time errors:

- reading a field before initialization;
- possibly or definitely initializing an immutable field more than once;
- completing without every field initialized;
- invoking a method while the receiver is incomplete; and
- allowing an incomplete `self` to escape.

Passing, returning, externally storing, or invoking a closure that exposes incomplete `self` counts as escape. A loop does not by itself prove that an assignment executes.

If initialization fails at runtime, no instance is published. Effects already completed on other initialized state remain.

### 14.6 Members, methods, and `self`

`object:.field` reads data. `object::method[arguments]` invokes the current function-valued member. `object:.method` reads its current receiver-bound function value.

A mutable member slot has `@mut`. For a data field, this permits field replacement. For a method slot, it permits replacing the callable. It does not mark a method as effectful and does not change visibility.

Direct member assignment requires a mutable member and ordinary root mutation permission. In contrast, a method may mutate its receiver's mutable fields even when the caller holds the receiver in an immutable binding. Contextual `self` grants receiver-member mutation, not permission to rebind the caller's variable.

A lambda that directly initializes or replaces a method slot receives contextual `self`, bound to the target instance selected once. Assigning an existing callable preserves that callable's original captures and receiver. It does not rewrite `self`. A forwarding lambda explicitly performs a new member lookup on each call.

Reading a method slot snapshots the callable selection, not object state. A saved method continues to observe the receiver's current fields but is not retargeted by later slot replacement.

Aggregate mutation through `self` or an alias of `self`, when it is not direct member replacement, is permitted only in the exact constructor for that nominal type. A conforming implementation may conservatively reject an alias chain it cannot prove within its bounded static analysis, but it MUST NOT grant this authority to an ordinary method or escaped alias.

### 14.7 Recursive nominal data

Nominal fields may refer recursively to nominal class references or finite reference layouts. A declaration is invalid if it requires infinite inline expansion. Recursive values remain subject to definite initialization and cycle-safe structural equality.

## 15. Modules and initialization

### 15.1 Module identity and discovery

A source file's canonical file or resolver identity defines its module. Source contains no module header. A logical path such as `game->math->vector` conventionally maps to `game/math/vector.lyra` beneath a configured source root.

All configured resolvers that can satisfy an import are considered. Exactly one source may satisfy a logical import. Missing or ambiguous matches are compile-time diagnostics. An implementation MUST NOT choose a match by undocumented resolver precedence.

### 15.2 Import forms

```lyra
import game->math->vector
import game->math->vector as vec
import game->math->vector->{length normalize as norm}
import @pub game->math->vector->{length}
```

A direct import binds an immutable module namespace under the final path segment unless `as` supplies another local name. A namespace alias is not a first-class value.

A selective import binds only the listed public names. An item alias changes the local name. `import @pub` is valid only for a selective import and re-exports each selected name under its local alias. Imports are otherwise private. Re-export is never transitive by default.

Two imports cannot bind the same local name. Re-exported names are unique. A later private top-level declaration may source-order replace a private imported selected name, but no declaration may replace a re-export. Wildcard imports do not exist.

### 15.3 Access and mutation

Namespace values and calls use `->:.` and `->::`, respectively:

```lyra
vec->:.origin
vec->::normalize[value]
```

A namespace cannot be passed or stored as a value. Only public names are visible through imports. An imported public mutable binding is read-only in the importing module.

### 15.4 Eager initialization

After imports and function signatures are linked, module values initialize eagerly. Within one module, initializers execute in source order.

For a reachable module graph, a module dependency must initialize before an initializer that may read or invoke its eager state. Unconstrained modules use stable canonical module order. Function-only import cycles are valid because signatures and closure slots link before eager values. Any cycle that may require partially initialized eager state is rejected at compile time.

Top-level effects are allowed. Failed initialization does not roll back completed effects and does not publish a usable module instance.

## 16. Intrinsic module and failures

### 16.1 `std->io`

`std->io` is a compiler-owned intrinsic module with reserved identity. A source resolver cannot replace it. It exports exactly:

| Export | Type |
|---|---|
| `print` | `Fn<String;Unit>` |
| `println` | `Fn<String;Unit>` |
| `eprint` | `Fn<String;Unit>` |
| `eprintln` | `Fn<String;Unit>` |
| `readLine` | `Fn<;@nil String>` |

`print` and `println` write to standard output. `eprint` and `eprintln` write to standard error. Each call writes its argument as one contiguous sequence and flushes before returning. The `ln` forms append exactly `\n`.

`readLine` reads one line, strips a terminal `\n` and an immediately preceding `\r`, and returns the remaining string. It returns `#NIL` only when end-of-file occurs before any code unit is read. Malformed input, interruption, or an I/O error fails with `LYR-IO`. An interrupted read preserves host interruption status.

The default runtime encoding is UTF-8. A host embedding may provide another coherent input, output, error, and charset environment. Modules never close process streams.

### 16.2 Compile-time diagnostics

A compile-time diagnostic is structured data containing at least:

- a stable code in the form `LYC-PHASE-NNN`;
- phase and severity;
- a complete primary source span; and
- related spans when required to explain a declaration, import, or cycle.

Compilation is fail-fast at each attempted phase. The first blocking diagnostic prevents publication of that phase's semantic artifact, and dependent phases do not run. Expected malformed source MUST NOT escape as an implementation exception.

The relevant phase families are source decoding, lexing, parsing, module discovery, resolution, typing, typed-language validation, emission, packaging, and session linkage. Exact numeric suffix errors, invalid annotation spacing, obsolete syntax, visibility, mutation authority, type mismatch, and eager cycles remain distinguishable by stable codes.

### 16.3 Invocation-fatal failures

Lyra has no source-level exception handling. A runtime failure aborts the current top-level invocation and preserves prior effects.

Language-visible failure categories include:

| Category | Cause |
|---|---|
| `LYR-ARITH` | checked overflow or underflow, zero division or remainder, zero dynamic range step, non-finite floating result |
| `LYR-BOUNDS` | invalid string or array index |
| `LYR-CONVERT` | invalid explicit value conversion |
| `LYR-STACK` | exhausted stack in ordinary recursion |
| `LYR-IO` | intrinsic input or output failure |
| `LYR-INIT` | module or nominal initialization failure at the initialization boundary |
| `LYR-THREAD` | access from a non-owner thread |
| `LYR-CLOSED` | access after module close |
| `LYR-LIFECYCLE` | invalid module or loading-context lifecycle operation |
| `LYR-LINK` | incompatible or unauthorized live callable or value linkage |
| `LYR-VERIFY` | generated artifact verification failure |
| `LYR-COMPAT` | incompatible artifact contract or runtime profile |
| `LYR-CANCEL` | host-requested cancellation at a generated safe point |
| `LYR-INTERNAL` | runtime invariant failure not attributable to valid source |

A failure carries its category and ordered Lyra source frames. Runtime transport may retain a host cause. `LYR-CANCEL` can originate only from an explicitly enabled host or session boundary; Lyra source has no cancellation operation. Lyra source cannot catch or recover from these failures within the current language.

### 16.4 Observable runtime boundary

Generated module instances are explicit, eagerly initialized, owner-thread confined, and closeable. Independent instances have independent module state. Closing an instance invalidates its callable handles and later source-level entry through that instance.

These lifecycle checks are observable when Lyra is invoked from a host, but they do not add source syntax. Lyra creates no threads and provides no synchronization or hidden thread hop.

## 17. Excluded and deferred forms

The following are outside language contract version 2:

- variants, records as a separate type category, destructuring patterns, type patterns, match-arm bindings, automatic match narrowing, and exhaustiveness inference;
- inheritance, interfaces, overriding, overloading, custom struct constructors, static members, and user-defined operators;
- user-defined generics, macros, quoting, hygiene, and compile-time metaprogramming;
- `Any`, dynamic values, runtime member lookup, and implicit dynamic dispatch;
- bitwise and shift operators, including `<<`, `>>`, and unsigned shifts;
- `throw`, `try`, `catch`, `finally`, and source-level recovery;
- default, named, omitted, or variable arguments, currying, and partial application;
- unsigned or floating ranges, a general iterator protocol, dedicated loop statements, `break`, `continue`, and source `return`;
- `nor`, `nand`, `xnor`, `eqt?`, and `eqv?` operators;
- source edition directives and simultaneous selectable language editions;
- Lyra source calls to Java, arbitrary Java member access, Java callback construction, engine or Vulkan integration;
- hostile-code sandboxing, process isolation, resource quotas, and forced termination; and
- REPL authentication, encryption, authorization, or hostile-client isolation.

The following obsolete spellings are specifically invalid:

```text
?? pattern -> result
(match _ condition -> result _ -> fallback)
::match[...]  ::cond[...]  ::iter[...]  ::while[...]
namespace->::match[...] and corresponding receiver forms
Type[arguments] for nominal construction
module->:.Type[arguments] for qualified nominal construction
```

Plain structs, classes, and value matching are current features. An excluded test or corpus category named `class` or `match` refers to an excluded subfeature such as inheritance or destructuring, not to the entire current feature.

## 18. Conformance

### 18.1 Front-end conformance

A conforming implementation MUST:

1. decode source and report spans according to Section 3.1;
2. accept every lexical and grammatical form defined here;
3. reject obsolete and excluded forms without placeholder execution behavior;
4. preserve exact literals, modifiers, accessor distinctions, declaration identities, and source order through static analysis;
5. enforce every qualifier, visibility, mutation, initialization, and type rule; and
6. issue structured diagnostics for expected source errors.

### 18.2 Execution conformance

A conforming implementation MUST preserve:

- strict target-before-arguments and left-to-right evaluation;
- short-circuit and lazy branch behavior;
- checked integer and finite floating semantics;
- exact array identity, tuple structure, closure identity, nominal identity, and aliasing;
- closure capture and shared-cell behavior;
- eager module initialization and rejection of eager cycles;
- constant-stack proven self-tail calls, `iter`, and `while`; and
- source-mapped invocation failures with the categories in Section 16.3.

An implementation may specialize bytecode, dispatch, equality, or matching only when the specialization leaves all values, effects, failures, source positions, and evaluation order unchanged.

### 18.3 Required validation domains

Conformance evidence SHOULD include positive, negative, boundary, and runtime-failure cases for:

- UTF-8, UTF-16 spans, comments, escapes, annotation spacing, commas, and negative-literal adjacency;
- every primitive and numeric conversion pair;
- every operator in both source spellings and every arity boundary;
- declarations, replacement, recursion, captures, and mutation authority;
- calls, accessors, selected mutable slots, and tail positions;
- truth testing, conditionals, nil coalescing, match, and `cond`;
- arrays, tuples, strings, ranges, callback loops, and nominal values;
- imports, aliases, re-exports, visibility, initialization order, and cycles;
- every `std->io` export; and
- every invocation-fatal category attributable to valid source.

Absence of an implementation crash is not semantic evidence. Tests must assert values, types, diagnostics, effects, identities, or failures.

### 18.4 Nominal truthiness conformance

Non-nil `struct` and `class` references are truth-testable and evaluate as true. A nilable nominal value is false only when it is `#NIL`; a present value is true. This rule applies consistently to conditional predicates, `and`, `or`, `xor`, `not`, `cond` conditions, and `match` guards.

The compiler's static truth-test admission, typed semantic validation, retained-session validation, and JVM emission all accept this rule. The conformance suite covers conditional, boolean-operator, `cond`, and match-guard uses of nominal values.

## Appendix A. Operator summary

| Operator | Arity | Evaluation | Result |
|---|---:|---|---|
| `+` | 2 or more | eager, left fold | numeric common type or String |
| `-` | 1 or more | eager, unary or left fold | numeric common type |
| `*` | 2 or more | eager, left fold | numeric common type |
| `/` | 1 or more | eager, reciprocal or left fold | floating for integer-only input, otherwise common numeric type |
| `%` | 2 | eager | common integer type |
| `^` | 2 | eager | resolved numeric type |
| `and` | 2 or more | short-circuit | `Bool` |
| `or` | 2 or more | short-circuit | `Bool` |
| `xor` | 2 or more | eager parity | `Bool` |
| `not` | 1 | eager | `Bool` |
| `++`, `--` | 1 | eager, pure | operand numeric type |
| `<`, `<=`, `>`, `>=` | 2 or more | eager chained relation | `Bool` |
| `==`, `!=` | 2 or more | eager chained value relation | `Bool` |
| `eq?`, `!eq?` | 2 or more | eager chained identity relation | `Bool` |
| `:=` | 2 | target then value | `Unit` |

## Appendix B. Complete program example

```lyra
import std->io->{println}

struct Point {
    let x :I32
    let y :I32
}

class Counter {
    let @mut value :I32

    Counter = (=> |start :I32| {
        self:.value := start
    })

    let @pub increment :Fn<;Unit> = (=> || {
        self:.value := (++ self:.value)
    })

    let @pub current :Fn<;I32> = (=> || self:.value)
}

let sumRange :Fn<Range<I32>;I32> = (=> |range| {
    let @mut total :I32 = 0
    iter[range |n| { total := (+ total n) }]
    total
})

let @pub main :Fn<Array<String>;I32> = (=> |args| {
    let point :Point = :Point[3 4]
    let counter :Counter = :Counter[(+ point:.x point:.y)]
    counter::increment[],
    ::println[String[counter::current[]]]
    (cond
      (== args:.length 0) -> ::sumRange[(0...3:1)]
      _ -> 0)
})
```

The program constructs nominal values explicitly, invokes a selected method, converts a scalar to text, traverses a signed range through a Unit callback, and returns an `I32` result from `main`.
