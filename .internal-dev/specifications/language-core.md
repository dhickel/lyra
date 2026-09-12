# Lyra Core Language Specification

## Status

Living normative contract for Lyra's current language scope. It supersedes contradictory intent in `src/main/resources/grammar_spec.md` and `src/main/resources/notes.md` where those files disagree with this specification.

Lyra has no source-level language-edition/version directive and source cannot select among editions. Compiled artifacts record the language-contract version required by `backend-runtime.md`. Features outside this document are not valid syntax unless another living specification adds them.

## Purpose

Define Lyra's source syntax, static semantics, evaluation behavior, modules, and failures before compiler/backend implementation. JVM lowering, host linkage, lifecycle, and engine behavior are deliberately excluded.

## Intended Contract

### Current scope

The current language includes static typing with local inference; immutable-by-default lexical bindings; `@pub`, `@mut`, and `@nil`; first-class typed lambdas; primitives, arrays, tuples, strings, and characters; blocks, conditionals, value and conditional matching, operators, assignment, modules, imports, exports, and the `->`, `:.`, and `::` accessors.

Structs and classes are an accepted extension with implementation in progress, specified below. Their presence in this intended contract is not evidence of executable support. The existing range, `iter`, and `while` sections also belong to the current language scope.

Outside current scope are variants, destructuring and type patterns, inheritance, interfaces, user generics, macros/quoting, catchable exceptions, omitted/default arguments, dynamic typing, and bitwise operators. See `deferred-features.md`.

### Lexical rules

- Identifiers are case-sensitive ASCII names matching `[A-Za-z_][A-Za-z0-9_]*`.
- Punctuation is never part of an identifier.
- `iter`, `while`, `match`, `when`, `struct` and `class` are reserved keywords. `_` remains an ordinary identifier except in the explicit match wildcard positions below; it is not a value or an `Any` type in those positions.
- Whitespace separates tokens and is otherwise insignificant except for type annotations.
- Commas are optional separators only inside delimited parameter, argument, type-argument, tuple, and array lists. They are not globally ignored.
- `//` begins a line comment.
- `/* ... */` is a nestable block comment.

A named type annotation must use `name :Type`: whitespace is required before `:`, and whitespace is forbidden after it. `name:Type` and `name : Type` are syntax errors. A return annotation uses `:Type` because no name precedes it.

### Literals

- Boolean: `#T`, `#F`.
- Nil: `#NIL`.
- Unit: `()`. Bare `Array[]` and `Tuple[]` are equivalent Unit spellings, not empty collection values.
- String: immutable double-quoted UTF-16 content with standard escaped control characters and Unicode escapes.
- Character: single-quoted escaped UTF-16 code unit of primitive type `Char`.
- Numeric literals support decimal integers `[0-9]+` and decimal floats with a decimal point and optional `e`/`E` exponent. A leading sign is an operator, not part of the literal. Uppercase suffixes such as `42I32`, `42U16`, and `1.0F32` force a primitive type.
- String/character escapes are `\\`, `\"`, `\'`, `\n`, `\r`, `\t`, `\b`, `\f`, `\0`, and exactly four-hex-digit `\uXXXX`. A `Char` literal must decode to exactly one UTF-16 code unit; unpaired surrogate code units are representable.

### Type regime

Lyra is statically typed. Every expression has a compile-time type. Local value types may be inferred from an unambiguous initializer and expected context. There is no `Any`, dynamic type, implicit dynamic member lookup, or truthiness escape from static checking.

All `@pub` declarations and public signatures require explicit complete types.

Primitive types are:

- `I8`, `I16`, `I32`, `I64`;
- `U8`, `U16`, `U32`, `U64`;
- `F32`, `F64`;
- `Bool`, `Char`, `String`, `Unit`.

Composite types are:

- `Array<T>`: homogeneous fixed-size array;
- `Range<T>`: immutable signed-integer range (`I8`, `I16`, `I32`, or `I64`);
- `Tuple<T1,T2,...>`: heterogeneous fixed-shape tuple;
- `Fn<P1,P2,...;R>`: positional function type.

`@mut` and `@nil` are part of function parameter/return contracts when present, for example `Fn<@mut Array<I32>,@nil String;@nil I32>`. `@nil` may qualify any value contract, including nested collection/tuple positions. Composite types are invariant.

### Nilability

`@nil T` admits a `T` value or `#NIL`.

- `#NIL` is legal only under an expected `@nil` contract and cannot infer its base type alone.
- A non-nil `T` may initialize, be passed to, or be returned from `@nil T`.
- An `@nil T` cannot be used as `T` for member access, arithmetic, indexing, or an ordinary `T` argument until explicitly narrowed or coalesced.
- It may be assigned/passed to a matching `@nil T`, compared with `#NIL`, tested by a conditional, or consumed by nil-only coalescing.

### Numeric typing and arithmetic

- An exact unsuffixed literal adopts an expected numeric type when representable.
- Without context, integers default to `I64` and decimals to `F64`.
- Literal values remain exact through parsing and are range-checked.
- Only statically lossless widening conversions are implicit. Same-signed integers widen by width. Unsigned-to-signed widening is legal only when the signed destination contains the complete unsigned source domain (`U8 -> I16`, `U16 -> I32`, `U32 -> I64` and wider variants); signed-to-unsigned is never implicit. `I8`/`I16`/`U8`/`U16` may widen to `F32`, `I32`/`U32` and narrower integers may widen to `F64`, and `F32` widens to `F64`. `I64`/`U64` never implicitly become floating point.
- Mixed numeric operators choose the least type that losslessly contains every operand type domain after contextual literal typing. If none exists, the program requires explicit conversion; for example `I16` with `U16` selects `I32`, while `I64` with `U64` has no implicit common type.
- Narrowing, other signed/unsigned changes, other integer/floating changes, and potentially lossy conversions are explicit.
- Explicit conversion uses type-bracket application, such as `I32[value]`, and is value/range checked. Signed/unsigned conversion is not a raw bit reinterpretation. Invalid/out-of-range input aborts; a constant-invalid conversion is a compile error.
- Integer arithmetic is checked against the resolved result type. Signed overflow and unsigned overflow/underflow abort at runtime; constant failures are compile errors. Unsigned comparison, division, remainder, and decimal text conversion use mathematical values in `0..2^N-1`, not Java signed interpretation.
- Integer `/` produces `F64` by default or `F32` under a complete expected `F32` context. This operator-defined result conversion may round even where an ordinary implicit integer-to-float conversion is forbidden.
- Integer remainder stays integer-typed. Division/remainder by zero aborts.
- Floating operations trap instead of exposing NaN or infinity. Any non-finite result aborts.
- `^` is exponentiation. Integer power accepts a non-negative integer exponent and is checked; floating power traps non-finite results.

### Bindings and modifiers

All named values, including functions, use `let`:

```lyra
let count :I32 = 0
let @mut total :I64 = 0
let @pub transform :Fn<I32;I32> = (=> |value| (+ value 1))
let @nil label :String = #NIL
```

The current modifier set is closed:

- `@pub`: module export or selected import re-export;
- `@mut`: rebinding/mutation permission through that symbol;
- `@nil`: value contract admits `#NIL`.

Modifier placement is part of the grammar:

- binding: `let Modifier* name [ :Type] = expression`;
- parameter: `|Modifier* name :Type|`;
- inline lambda return: `(=> ReturnModifier* :Type |...| body)`;
- nested contract: `Array<@nil String>` or `Fn<@mut Array<I32>;@nil String>`.

`@pub` is legal only on top-level bindings and selected imports. `@mut` is legal on bindings and parameters, but not returns. `@nil` is legal on bindings, parameters, returns, and nested value contracts. In `@nil Array<String>`, the array itself may be nil; in `Array<@nil String>`, each element may be nil. Unknown, duplicate, or context-invalid modifiers are compile errors. `@const`, `@opt`, `@nilable`, and `@static` are not current modifiers.

Bindings are immutable unless `@mut`. Rebinding uses `target := value` or `(:= target value)`. Aggregate updates use the same operator, such as `array[index] := value`.

A mutation root must be an `@mut` symbol. A parameter through which a function mutates must be `@mut`, and its `Fn` contract records that qualifier. Permission is binding-local: an immutable array binding may initialize a new `@mut` binding; mutations through the latter affect the shared array and are visible through every alias.

An importing module may read an exported `@mut` binding but cannot mutate it directly. The declaring module owns mutation and exposes it through functions when desired.

### Scope, replacement, and captures

Modules, lambdas, and blocks create lexical scopes.

- A later private declaration may reuse a name in the same scope.
- It creates a new symbol identity visible from that source position onward.
- Earlier references and closures retain the old binding.
- The replacement may have a different type.
- Type and value names occupy one namespace.
- A public top-level binding or re-exported name cannot be redeclared later in that module.

Ordinary value declarations are source-ordered and cannot be read before initialization. Every `let` initialized by a completely typed lambda is signature-predeclared, permitting self recursion, forward references, and mutual recursion. Non-function eager initialization cycles are compile errors.

Closures capture bindings. Immutable captures retain their selected value/reference. A captured `@mut` binding is one shared mutable cell visible to every capturing closure.

### Structs and classes (accepted; implementation in progress)

The owner accepted the following extension on 2026-09-11. Completion requires the
corresponding backend and session gates; parsing alone does not complete it.

Implementation status: lexical/grammar/AST, resolution, typed construction/member
operations, initial definite-initialization certification, field-sensitive heap/
callable transfer and nominal IR are implemented. Source declarations now emit
deterministic final JVM representation classes with private typed fields and checked
initialization/generated/public accessors. Host-driven integration tests load and
exercise those source-produced classes. General constructor summaries, Lyra-side
factory/construction and member execution, complete repeated/imported heap transfer,
contextual replacement receivers, equality and persistent sessions remain in
progress; emitted representation support alone is not full nominal conformance.

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

let position :Vec2 = Vec2[10.0 20.0]
let counter :Counter = Counter[0]
counter::increment[]
let saved :Fn<;Unit> = counter:.increment
```

Declarations and identity:

- `struct` and `class` introduce concrete nominal types, not tuple aliases. Type
  identity belongs to the originating declaration/module revision, not its field
  shape or import alias. No inheritance, interfaces, overriding, overloading,
  user-defined operators, static members, or custom struct constructors are added.
- Initial declarations are module-level: `struct [@pub] Name { members }` and
  `class [@pub] Name { members }`. Names begin with an uppercase ASCII letter.
  Type visibility is private unless `@pub`; this is separate from member visibility.
  Types and values retain one namespace. Member names must be unique in a type.
- Members use `let Modifier* name :Type [= expression]`. Every member contract
  is explicit and complete. Missing initializers are legal only for these members,
  not ordinary lexical bindings. `@nil` retains its existing value-contract meaning.
- Struct fields are public by default. Class fields and methods are private unless
  `@pub`. Access is checked lexically against the declaring class, including when
  taking a method reference. Access through another instance of the same class
  does not change that lexical access check.
- Structs contain data and have no methods or `Fn`-typed data positions, including
  functions nested in array/tuple/struct data contracts. Mutable fields and nested
  mutable data are allowed. Nominal class references are reference-valued data;
  their private implementation is not exposed by storage inside a struct.

Construction and initialization:

- `Type[arguments]` constructs a new instance. Arguments are exact positional
  arguments, evaluated once left-to-right before instance initialization.
  A qualified constructor uses existing namespace value access, e.g.
  `model->:.Counter[0]`; a qualified type annotation uses `:model->Counter`.
  Syntax alone does not distinguish a unary constructor application from indexing,
  or determine whether a type name/alias exists. Invalid value-index arity and
  unknown named types are resolution errors, not capitalization-based parse errors.
- A struct's uninitialized fields are constructor parameters in declaration order.
  Fields with initializers initialize themselves and are not optional arguments.
- A class may contain one `Name = (=> |typed parameters| body)` constructor, using
  the exact enclosing class name, without `let`. The parameter types are complete;
  the expected return is `Unit`. A redundant inline return annotation must agree.
  The construction expression returns the new instance, not the constructor body.
- Without an explicit constructor, a class permits zero-argument construction only
  if every field has an initializer. There is no implicit positional class constructor.
- Field initializers run in declaration order before the constructor body. Required
  struct arguments initialize their fields before the remaining initializers run.
  Class methods may capture the constructing receiver when their slots are installed;
  that installation alone is not publication of the receiver.
- Definite initialization must prove all fields initialized on every completing
  path. Immutable fields receive exactly one initialization. Reads before
  initialization, duplicate immutable initialization, method invocation during
  incomplete initialization, and escape of an incomplete receiver are errors.
  Passing, returning, storing externally, or invoking a closure that exposes an
  incomplete `self` counts as escape. A loop alone cannot prove an assignment
  happens at least once. Constructor failure publishes no instance and does not
  roll back effects already performed on other initialized state.
- Recursive nominal references must have finite JVM reference layouts and still
  satisfy initialization; no recursive inline expansion or infinite value layout
  is permitted.

Mutation, methods, and references:

- `object:.field` accesses field data; `object::method[arguments]` invokes a callable
  member; `object:.method` reads its current bound function value. There is no new
  function declaration syntax. Class lambda-valued members use ordinary exact `Fn`
  contracts whose explicit parameters exclude the implicit receiver `self`.
- `@mut` permits replacement of a field or method slot. It does not classify a
  method as effectful and does not grant extra member visibility. A replacement
  must satisfy the exact declared contract. Immutable method slots cannot be replaced.
- Direct member assignments retain ordinary mutation-root and imported-ownership
  checks and additionally require an `@mut` member. Immutable receiver bindings
  do not freeze instances: methods may mutate their receiver's `@mut` fields even
  when called through an immutable binding. The implicit `self` supplies receiver
  mutation permission, not permission to rebind the caller's variable.
- Reading a method slot retains its current callable and receiver. It snapshots
  the implementation selection, not object state. Replacing the slot affects later
  lookups but does not retarget saved references, callbacks, or existing captures.
  Repeated reads of an unchanged slot retain function identity.
- A lambda directly initializing/replacing a method slot receives contextual
  `self`, bound to the selected target instance evaluated once. That contextual
  receiver does not grant lexical private access: a replacement written outside
  the declaring class can use only accessible members. Nested closures retain
  normal lexical capture and visibility rules.
- Assigning an existing callable copies that callable value, including its existing
  captures/bound receiver. It does not rewrite captured `self`. A forwarding lambda
  such as `(=> || counter::increment[])` explicitly requests a fresh lookup on each
  call. Neither direct invocation nor extraction adds an extra receiver argument to
  an already selected callable.
- Class equality is instance identity. Struct equality is nominally typed structural
  equality over current field values; copying a reference does not clone mutable
  storage. Nested class references compare by identity. Cyclic data traversal must
  terminate using visited object pairs. Structs do not add source identity operators
  beyond the existing identity-bearing type rules. Mutable structural values cannot
  be assumed to have stable content hashes.

These rules extend the earlier `@pub` placement, member-bearing types, constructor,
and mutation descriptions only where expressly stated. Ordinary arrays, lexical
bindings, imports, callable authentication, and module lifecycle remain unchanged.

### Functions and lambdas

There is no dedicated `fn` declaration. Named functions are lambda-valued `let` bindings.

The general lambda form is `(=> ReturnModifier* [:ReturnType] |Parameter*| body)`. It may appear as a `let` initializer or anonymously wherever a lambda expression is accepted. Every lambda receives one complete `Fn` signature either from context:

```lyra
let transform :Fn<I32;I32> = (=> |value| (+ value 1))
```

or from complete inline annotations:

```lyra
let transform = (=> :I32 |value :I32| (+ value 1))
let lookup = (=> @nil :String |key :String| ...)
```

When context supplies the signature, the inline return and parameter annotations may be omitted. Without a complete expected `Fn`, every parameter and the return must be annotated inline. Redundant annotations are legal only when they agree. Types are not inferred from recursive call sites or an unconstrained body.

The separate compact anonymous form is `|Parameter*| body`, without `=>` or surrounding lambda parentheses. It is legal only where a complete expected `Fn` type exists and cannot directly serve as a named declaration initializer. It creates the same closure kind as `(=> ...)`.

Calls have exact positional arity. There is no partial application, automatic currying, named/default/omitted arguments, or varargs.

### Calls and accessors

- `(callee arg1 arg2)` calls a callable value.
- `::callee[arg1 arg2]` promotes a local/name as a direct call target.
- `receiver::method[arg1 arg2]` directly calls a method with an implicit receiver.
- `receiver:.field` reads a value/field.
- `receiver:.method` obtains a receiver-bound callable value and may be assigned or invoked as `(receiver:.method args)`.
- `::` without a following bracket call is invalid and never produces a method value.
- `->` qualifies modules/namespaces; a qualified access ends with `::` for direct call or `:.` for value access.
- Static/member legality follows static type information. These distinctions must survive parsing and semantic analysis.

The current built-in value members are tuple numeric fields plus the read-only `:.length` member on strings and arrays. Additional member-bearing types and their accessible members require a later user-type or interop specification; this section fixes the accessor syntax and value-versus-call distinction.

### Evaluation and blocks

Evaluation is strict, eager, and left-to-right for call targets, arguments, operators, collection elements, declarations, and blocks. `and`, `or`, conditionals, match arms, and coalescing short-circuit.

`{ ... }` creates a lexical scope, evaluates forms in order, and returns its final expression. An empty block returns Unit.

Lyra encourages functional composition and immutability but does not enforce purity. Functions may mutate through `@mut`; later interop specifications may add other effectful operations without changing evaluation order.

### Truthiness

False values are `#F`, `#NIL`, signed/unsigned numeric zero, floating `+0.0`/`-0.0`, empty string, empty array, and Unit. True values are `#T`, non-zero finite numbers, non-empty strings/arrays/tuples, functions, and characters. A later member-bearing reference type is truthy whenever non-nil. Non-finite floats cannot occur normally because they trap.

Boolean operators always return `Bool`, not selected operands.

### Conditionals and nil coalescing

Full conditional:

```lyra
(predicate -> then_expression : else_expression)
(predicate binding -> then_expression : else_expression)
```

The optional binding is one unannotated identifier placed immediately before `->`, as in `(candidate value -> value : fallback)`. The predicate evaluates once. The binding exists only in the truthy branch. For `@nil T`, it has unqualified type `T`; otherwise it has the predicate type. Only the selected branch evaluates. Branch types must unify by exact typing or permitted lossless numeric widening.

Then-only conditional:

```lyra
(predicate -> expression)
(predicate binding -> expression)
```

It evaluates the expression only when truthy and always has Unit type.

Nil-only coalescing:

```lyra
(nilable_value : fallback)
```

It accepts only `@nil T`, returns the original non-nil `T` even when otherwise falsey, and evaluates fallback only for `#NIL`.

### Match expressions

Match is a compiler-recognized expression with equivalent parenthesized and direct-bracket spellings:

```lyra
(match value
  ?? 10 -> "ten"
  ?? _ when (> value 20) -> "greater than twenty"
  ?? _ -> "other")

::match[value
  ?? 10 -> "ten"
  ?? _ when (> value 20) -> "greater than twenty"
  ?? _ -> "other"
]
```

Traditional value matching evaluates the subject exactly once. Each arm begins with `??`, followed by a value expression or the exact wildcard `_`, an optional `when` guard expression, `->`, and a result expression. Value patterns use the same compatible static typing and value equality as `==`, including lossless numeric widening and structural aggregate equality. Patterns may be arbitrary expressions, not just literals; they introduce no names or destructuring. A `#NIL` pattern is checked under the subject's nilable contract. Match does not itself narrow a nilable subject.

Arms are attempted in source order. Only reached pattern expressions are evaluated. A guard is evaluated only after its pattern matches; a wildcard always passes the pattern test. Guards use ordinary Lyra truthiness. The first arm whose pattern matches and whose guard (if present) is truthy selects its result; no later patterns, guards, or results execute. Pattern equality is performed separately for each arm, so no all-pattern common numeric type or eager pattern computation is implied.

The exact wildcard subject selects conditional mode:

```lyra
(match _
  ?? isAdmin -> "full access"
  ?? isOwner -> "owner access"
  ?? _ -> "no access")

::match[_
  ?? (< value 0) -> "negative"
  ?? (== value 0) -> "zero"
  ?? _ -> "positive"
]
```

Conditional mode has no evaluated subject. Each non-wildcard arm contains one condition expression, tested using ordinary truthiness. Conditions are evaluated once each, in source order, until one is truthy. `when` is invalid in this mode. The wildcard arm is unconditional.

Both modes require a final unguarded `?? _ -> fallback` arm, even when preceding arms appear exhaustive. A fallback-only match is legal; traditional mode still evaluates its subject. No arms may follow an unguarded wildcard. Bare fallback expressions and comma-separated arms are not accepted. Results must unify under the same contextual typing, nilability, and permitted lossless numeric widening rules as full conditionals. Match always has the resulting value type; there is no implicit Unit result for an unmatched input. Blocks can supply multi-form results, and Unit-valued branches are supported.

There are no match-specific binding, type-test, destructuring, or automatic narrowing forms. `_` is syntax, not a dynamically typed value. `match` is not a first-class callable; `::match[...]` is a special-form spelling and preserves lazy arm evaluation rather than ordinary eager argument evaluation.

### Operators

Core operators support equivalent prefix S-expression and bracket forms:

```lyra
(+ a b c)
+[a b c]
(== a b c)
==[a b c]
```

Assignment alone additionally has infix form and does not have bracket form.

- Numeric `+` and `*` accept at least two operands. They select one common numeric type using only permitted lossless widening and return that type. String `+` accepts at least two `String` operands and returns `String`; numeric/string mixing is invalid.
- `-` accepts one or more numeric operands. One operand negates; multiple operands fold left after selecting one common numeric type.
- `/` accepts one or more numeric operands. One operand computes its reciprocal; multiple operands fold left. Integer-only inputs follow the floating-result rule above.
- `%` and `^` accept exactly two numeric operands. `%` requires integer operands of one resolved common integer type.
- `and`, `or`, and `xor` accept at least two operands of any truth-testable types and return `Bool`. `and`/`or` short-circuit left-to-right; `xor` evaluates all operands and is true for odd truthy parity.
- `not`, `++`, and `--` accept exactly one operand.
- `==`, `!=`, `eq?`, and `!eq?` accept at least two operands. Value equality selects compatible operand types using exact typing or permitted lossless numeric widening. When at least one operand is `Bool`, `==` and `!=` compare the truthiness of all truth-testable operands; this makes `#NIL` consistently equivalent to false in an explicitly typed nilable context. Identity requires the same identity-bearing static type.
- `<`, `<=`, `>`, and `>=` accept at least two numeric operands, select one common numeric type using permitted lossless widening, and chain adjacent comparisons.
- `nor`, `nand`, and `xnor` are not core operators.

`==`/`!=` perform typed value/structural equality. `eq?`/`!eq?` perform reference identity where identity exists. `eqt?`, `eqv?`, and their negations do not exist.

Tuples compare structurally and expose no identity. Arrays compare structurally under `==`; `eq?` tests shared array identity. Function values are identity-bearing: each lambda evaluation creates a distinct identity, while repeated reads of one binding retain its identity. Strings compare UTF-16 content and have no relational ordering.

`++` and `--` are pure unary operators over every numeric type. They add/subtract one under checked/trapping arithmetic and never mutate implicitly:

```lyra
count := (++ count)
count := ++[count]
```

### Arrays

- Arrays are homogeneous, fixed-size, identity-bearing values.
- Without expected type, use `Array<I32>[1 2 3]`.
- Under a complete expected `Array<T>`, `Array[...]` is allowed and checked against `T`.
- `Array<T>[]` is an empty array. Bare `Array[]` is Unit.
- `array[index]` reads an element. Index must be a valid non-negative integer position.
- Bounds failure aborts the invocation.
- `array[index] := value` requires an `@mut` root binding.
- `array:.length` returns the fixed element count as `I32`.

### Ranges and iteration

Range construction is an enclosed expression with an explicit step:

```lyra
let ascending :Range<I32> = (0..100:1)
let descending :Range<I32> = (100...0:(- 1))
```

`..` excludes the endpoint. `...` includes it only when the step reaches it;
`(0...5:2)` visits 0, 2, 4. A range evaluates its start, end and step exactly
once, left-to-right, at construction. Those immutable values may be stored,
passed, returned or captured, and each traversal starts afresh. Construction
does not allocate or evaluate a collection of elements.

The bounds and step have the same signed integer type, selected under a complete
expected `Range<T>` or the ordinary common numeric/contextual-literal rules.
Unsigned and floating ranges are not part of this initial contract. Zero step
is a compile error when constant and an invocation-fatal arithmetic error
otherwise. A step directed away from the endpoint produces an empty traversal.
Exclusive equal-endpoint ranges are empty; inclusive equal-endpoint ranges have
one element. Completion must not attempt an overflowing terminal increment.
Negative steps use existing unary expressions, such as `(- 1)` or `-[1]`.

`iter` is a reserved built-in, like `match`, not a shadowable binding or a bare
first-class function value. Its callback is an ordinary function value.
`::iter[...]` starts a new expression rather than attaching as a receiver method
to the preceding expression, regardless of whitespace or newlines.
`iter` takes a range and a Unit-returning callback, and returns Unit:

```lyra
::iter[(0..100:1) |x| ::consume[x]]
::iter[(0..100:1) || ::tick[]]
(iter (0..100:1) |x| (consume x))
```

The callback contract is either `Fn<T;Unit>` for the exact range element type or
`Fn<;Unit>`. Compact and full lambdas and existing function values are accepted.
An anonymous callback's parameter count selects its expected contract; this does
not introduce general overloading, user generics, omitted arguments or varargs.
The range and callback expressions evaluate once in source order. Traversal then
invokes the callback synchronously, once per element, with exactly one argument
or exactly zero arguments. Empty ranges invoke neither form. The callback
parameter is an ordinary immutable parameter and each invocation has its own
binding, including when captured by a returned/stored closure. Nested iteration
uses existing lexical captures. Runtime failure aborts traversal immediately.
Generated traversal backedges honor existing application/session safe points.

### Condition-controlled iteration

`while` follows the same reserved, unqualified callback-call model as `iter`:

```lyra
let @mut count :I32 = 0
::while[
  || (< count 10)
  || { count := (+ count 1) }
]

(while || (< count 20) || { count := (+ count 1) })
```

Its exact arguments are a predicate `Fn<;Bool>` and an action `Fn<;Unit>`.
It returns `Unit`. Compact/full lambdas and stored or computed function values
are accepted under these contextual contracts. The predicate must return
non-nilable `Bool`; general truthiness and implicit action-result dropping are
not part of this contract. Neither callback receives an implicit index or state
argument. Ordinary captures provide shared mutable state when needed.

The predicate expression and action expression evaluate once, left-to-right,
before the first predicate invocation. The selected callback values are retained
for that traversal. The predicate is then invoked before every action invocation,
including the first: false completes the loop; true invokes the action and then
repeats the test. Even a zero-action traversal invokes the predicate once, and
its side effects remain observable. Reassigning a binding that originally held
a selected callback does not replace that selected callback; shared captured
bindings remain live as usual. Failure in either callback aborts the loop.

Loop backedges honor existing application/session safe points and execution must
use constant JVM stack for repetition. An enclosing block still returns its final
expression. Callback completion does not mean break or return from an enclosing
function. No `return`, `break`, `continue`, or separate `do while` form is added.
`::while[...]` has the same fresh-expression boundary as `::iter[...]`; `while`
cannot be shadowed, referenced as a bare value or used as a qualified member.

Implementation status: both callback loops have contextual type checking,
repeated-effect certification, explicit typed IR and direct JVM execution.
Signed ranges also support live session storage and bounded value snapshots.

### Tuples

- `Tuple[a b c]` infers `Tuple<A,B,C>` positionally.
- `Tuple<A,B,C>[a b c]` is an allowed explicit form.
- `Tuple[]` is Unit.
- Fields are immutable and accessed by zero-based compile-time positions: `tuple:.0`, `tuple:.1`, etc.
- An out-of-range tuple field is a compile error.

### Strings and characters

- Strings are immutable UTF-16 sequences.
- `string[index]` selects a UTF-16 code unit and returns `Char`.
- `string:.length` returns the UTF-16 code-unit count as `I32`.
- Bounds failure aborts.
- Variadic `+` concatenates strings only and never implicitly stringifies other values.
- `String[value]` explicitly produces deterministic text for primitive scalar values and Unit. Integers use base-10 mathematical values with no leading zeroes; unsigned output is never Java's signed bit interpretation. Floats use the shortest round-tripping finite decimal, lowercase `e` when exponential notation is needed, no redundant exponent sign/zeroes, and preserve `-0.0`. Booleans use `#T`/`#F`, characters produce one-code-unit strings, and Unit produces `()`.
- `==`/`!=` compare string content; ordering operators are undefined for strings.

### Modules and imports

A source module's canonical identity comes from canonical file/path identity. Source files contain no module declaration. Imports use logical chains such as `game->math->vector`; project/host configuration maps those chains to files. Canonicalization and embedded-source handling are backend/module-loader concerns.

Imports are static and occur only in a module header before executable declarations:

```lyra
import game->math->vector
import game->math->vector as vec
import game->math->vector->{length normalize as norm}
import @pub game->math->vector->{length}
```

A direct import binds an immutable module namespace under its final segment unless `as` supplies another local name. The alias is not a first-class value; access uses forms such as `vec->:.length` or `vec->::normalize[value]`. A selective import binds only the listed public names into the local single namespace; `normalize as norm` binds and, when re-exported, exports `norm`.

Two header imports may not bind the same local name, and a re-exported name must be unique. A later private top-level `let` may source-order replace a private imported name under the ordinary replacement rule, but no declaration may replace a re-export. Wildcards do not exist. Imports are private by default. `import @pub` is valid only for a selective import and explicitly re-exports its selected names; transitive exposure is never implicit.

Top-level `let` declarations are private unless marked `@pub`. Public signatures are explicit. Module values initialize eagerly in source order after import and function signatures link. Cyclic dependencies are allowed only through function declarations that do not require cyclic eager value initialization; eager initialization cycles are errors rather than partially initialized states.

### Compile and runtime failures

Compilation uses structured, phase-specific diagnostics. The first blocking error in an attempted phase includes a stable code, phase, severity, complete primary span, and related spans when needed. Failed phases publish no partial semantic artifact and dependent phases do not run.

Deferred-feature syntax is not reserved by the current grammar and produces an ordinary lexical/syntax error.

Lyra has no `throw`, `try`, `catch`, `finally`, or implicit `Result` convention. Checked overflow, invalid conversion, bounds failure, division by zero, or non-finite floating result aborts the current top-level invocation. `backend-runtime.md` defines transport to the CLI and Java; later interop may add separately specified failure kinds.

## Constraints

- Source semantics must not depend on a particular AST, IR, interpreter, bytecode library, or JVM representation.
- Parser and semantic phases must preserve exact literal values, modifiers, accessor distinctions, source spans, and symbol identities required by this contract.
- Unsupported syntax must not silently parse into placeholders or throw implementation exceptions.
- No implementation may claim execution, Java interop, engine integration, isolation, or reload support solely from this language contract.
- Existing code and resource grammar are prototype evidence, not authority where they conflict with this specification.

## Decisions

The owner selected: static typing with local inference; immutable-by-default bindings; binding-local `@mut`; `@nil` contracts with `#NIL`; one identifier namespace; source-ordered private replacement; fully typed lambdas and recursive function signature predeclaration; strict left-to-right evaluation; truthiness; expression blocks; fixed arrays and tuples; checked integers; trapping floats; path-identity modules with logical namespace imports; module-owned exported mutation; fail-fast structured phase diagnostics; and invocation-fatal runtime failures.

Durable rationale and alternatives are recorded in `decisions.md`.

## Validation

A conforming implementation must add assertion-grade tests for at least:

- accepted/rejected lexical spelling, comments, commas, and mandatory type spacing;
- every literal and exact numeric boundary;
- modifier legality and mutation authorization;
- same-scope replacement, closure capture, recursion, and eager cycles;
- complete lambda typing, exact arity, both call forms, and accessor distinctions;
- left-to-right side effects and every short-circuit path;
- truthiness, predicate binding, then-only Unit, and nil-only coalescing;
- both match spellings/modes, expression patterns and guards, subject-once and lazy arm order, fallback requirements, branch typing, nil/equality boundaries, reserved keywords, and malformed arms;
- operator arity, checked/trapping arithmetic, equality, and identity;
- array/tuple construction, access, mutation, aliases, equality, length, and bounds failures;
- explicit primitive/Unit string conversion and UTF-16 string length;
- module imports, aliases, re-exports, visibility, and cycles;
- structured compile diagnostics and invocation-aborting runtime failures.

Tests must assert produced structures, types, values, diagnostics, and runtime outcomes rather than merely print output or check that no exception escaped.

The maintained language conformance corpus, numeric/ABI matrices and bounded seeded fuzz campaigns are mandatory parts of the core `mvn test` suite. Every new or changed language feature updates accepted/rejected source fixtures, boundary and runtime-failure assertions, the relevant generator and independent oracle/model, and the coverage documentation in `docs/language-testing.md` in the same change. Primitive, operator, intrinsic-export and sealed-IR inventories must not silently outgrow their tests. Fuzz counterexamples become permanent regressions; compiler invariant failures and unsupported emission of valid source are failures, never accepted outcomes.

## Open Questions

No unresolved current-scope language decision blocks this specification. `backend-runtime.md` owns module resolution, JVM execution, standalone artifacts, Java consumption, lifecycle, artifact metadata, and source maps. Lyra-to-Java interop, engine integration, Vulkan affinity, user-declared types, and other deferred capabilities require later dedicated specifications before entering this contract.
