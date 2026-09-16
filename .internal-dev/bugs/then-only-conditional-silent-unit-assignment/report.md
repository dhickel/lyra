# Then-only conditional silently binds Unit, so `let y = (predicate -> expr)` is accepted as an assignment

## Summary

A then-only (non-exhaustive) conditional, `(predicate -> expression)` — an `if`
with no `else` — is typed `Unit` and may currently be used anywhere a value is
produced, bound or consumed. `let y = (x -> "true")` therefore compiles, binds
`y : Unit`, silently drops the branch result, and reports no diagnostic:

```lyra
let x :I32 = 1I32
let y = (x -> "then-branch-ran")
```

Running an equivalent program prints `y is ()` while the branch expression's
value is discarded.

The exhaustive forms are not affected and must stay assignable: the full
conditional `(predicate -> then : else)`, `cond[...]` and `match[...]` all
require both branches / a final `_ -> fallback` and always produce a real result
type.

## Scope

- `lyra-compiler` type checking of then-only conditionals: the
  `conditional.elseExpression().isEmpty()` path of `TypeChecker.checkConditional`
  returns `PrimitiveType.UNIT` for every enclosing position, and the enclosing
  position signal needed to separate "discarded effect" from "bound/consumed
  value" does not exist yet (`checkLet`, `checkBlock`, `checkForm`,
  `checkExpression`, argument/element/return positions).
- Every mirrored phase validator must learn the same rule so no phase accepts a
  value-position then-only conditional: `IrValidator.validateBranch`,
  `TypedSemanticProvenance` ("then-only typed conditional is not Unit"),
  `SemanticFlowAnalyzer`/`SelfAliasProvenance` effect positions, and the
  `JvmBytecodeEmitter.emitTailBranch` discard path.
- Specification and documentation currently endorse the reported behavior and
  must change in the same work unit: `language-core.md` §"Conditionals and nil
  coalescing", `docs/reference/control-flow.md`,
  `docs/learn/tutorials/05-control-flow.md`, `docs/language-testing.md`, and the
  Phase 24 requirement matrices.
- Related closed GitHub issues were checked: #11 (dedicated `cond`, `match` arm
  simplification) is about the exhaustive forms and does not cover this. #9, #10
  and #12 are about bracket spellings, negative literals and nominal
  construction. This report is not a duplicate of any of them.

## Reproduction

Compile with the installed CLI (`lyra compile <root>`) or
`LyraCompiler.compile(CompileRequest.source(...))`:

```lyra
let x :I32 = 1I32
let y = (x -> "true")
let @pub main :Fn<Array<String>;I32> = (=> |args| 0)
```

`lyra compile` succeeds with no diagnostic. Measured positions on `master` at
`82ea611` ("Release version 0.1.1"):

| Form | Result today |
| --- | --- |
| `let y = (x -> "true")` (unannotated, inferred) | compiles; `y : Unit` |
| `let y :Unit = (x -> "true")` | compiles |
| `let @mut y :Unit = ()` then `y := (x -> "true")` | compiles |
| `let arr = Array<Unit>[(x -> "true")]` | compiles |
| `Tuple<Unit,I32>[y 1I32]` where `let y :Unit = (x -> "true")` | compiles |
| `(g (x -> "true"))` where `g :Fn<Unit;Unit>` | compiles |
| `let f :Fn<;Unit> = (=> || (x -> "true"))` | compiles |
| `let y :I32 = (x -> "true")` | rejected: `LYC-TYPE-001 cannot use Unit where I32 is required` |
| full `(p -> a : b)`, `cond[...]`, `match[...]` | legal value-producing forms; unchanged |
| `{ (x -> <effect>) 0 }` (non-final block form) | legal; effect runs, value discarded |
| root-level `(x -> <effect>)` (discarded module form) | legal; effect runs |

The only existing rejection is incidental: it fires because a `:I32`
annotation happened to be present, not because the conditional supplied a value
in a value position.

## Expected

A then-only conditional must not supply an assigned or consumed value. It must be
rejected with a structured, source-mapped diagnostic when it is:

- the initializer of a `let`/`@mut` binding, annotated or inferred;
- the value of an assignment/rebind (`:=`) or of a member/element/aggregate
  write;
- a call argument;
- an array or tuple element; or
- a function/lambda result (tail position).

It stays legal where its `Unit` result is discarded: a non-final form in a block,
a discarded module/top-level form, or a directly discarded expression statement.
Evaluation order, laziness, predicate-once semantics, predicate binding,
predicate narrowing, effect recording, source spans, and the existing
constant-stack lowering for the discarded case must not change.

## Actual

`TypeChecker.checkConditional` checks the then-only branch with
`Optional.empty()` and returns `PrimitiveType.UNIT` without consulting the
enclosing position, and never reports a diagnostic. All later phases accept a
`Unit`-typed conditional consistently, so nothing rejects the binding
afterwards.

## Evidence

- `TypeChecker.checkConditional` (`lyra-compiler/.../semantic/TypeChecker.java`,
  then-only branch): checks the branch with an empty expectation and returns
  `PrimitiveType.UNIT`.
- `TypeChecker.checkLet`: passes `expected = Optional.empty()` for an
  unannotated `let`, so the inferred contract becomes `Unit` with no diagnostic.
- `SemanticResolver.resolveExpression`: "A then-only conditional is Unit-valued.
  Its branch is an effect position, not the result position of the enclosing
  expression".
- `IrValidator.validateBranch`: "then-only branch must have Unit type".
- `TypedSemanticProvenance`: "then-only typed conditional is not Unit".
- `JvmBytecodeEmitter.emitTailBranch`: discards a non-Unit branch and returns
  `Unit`, retaining ordinary tail lowering only for Unit branches.
- Spec: `.internal-dev/specifications/language-core.md` §"Conditionals and nil
  coalescing": "It evaluates the expression only when truthy and always has Unit
  type." Docs: `docs/reference/control-flow.md`, `docs/learn/tutorials/05-control-flow.md`.
- Reproduced 2026-09-16 on `master` at `82ea611` with the installed `lyra` CLI;
  probe sources lived under `/tmp/lyraprobe`, and no repository file was changed.

## Spec decision still required: function tail position

The reported rule ("reject in value positions, including return") conflicts with
existing, specification-endorsed, tested behavior. The tail case is currently a
load-bearing language feature:

- `lyra-compiler/src/test/resources/language/corpus/conditionals/then-only-unit-tail-recursion.lyra`:
  `let loop :Fn<I32;Unit> = (=> |n| ((> n 0) -> ::loop[(- n 1)]))`. The
  conditional is the lambda body and exists specifically to prove constant-stack
  tail recursion; its `Unit` result is even consumed
  (`String[::loop[100000]]`).
- `Phase22ConformanceTest.thenOnly` (`(=> |value| (value -> ()))`),
  `Phase15SmokeTest`, `Domain11ContextualTypingTest`,
  `Domain11SemanticTest`, and the remaining
  `conditionals/then-only-{unit,false,reference,nullable,tail}.lyra` fixtures.
- `docs/learn/tutorials/05-control-flow.md`: "The final then-only conditional
  executes its body because `#T` is truthy and has `Unit` type."

The specification decision must therefore be one of:

1. Confirm the strict reading: the tail position is also rejected, and all the
   fixtures/docs above are updated. This removes the `if`-statement idiom from
   the tail of `Unit`-returning functions and makes
   `then-only-unit-tail-recursion` inexpressible as written.
2. Keep an explicit exception for the final expression of an already-`Unit`
   function/block, while still rejecting binding, assignment, argument, element
   and member-write positions. This preserves the documented loop idiom and the
   conformance corpus.

This issue must not be closed until the chosen option is recorded in
`language-core.md`.

## Impact

- A single-armed `if` used as an expression silently yields `()`; the branch's
  value is dropped with no warning. Because `Unit` is a legal binding type, no
  later phase can catch the mistake.
- Code that reads as a value-producing conditional, which is the expectation
  carried over from `if`/`else` expression languages, compiles and then
  misbehaves at runtime instead of failing with a source-mapped diagnostic.

## Status

Open. Reported 2026-09-16 and verified against `master` at `82ea611`. The
behavior is currently specification-endorsed, so closing this is a language
contract change plus implementation plus mirrored-validation enforcement.

## Next Action

1. Record the tail-position decision in
   `.internal-dev/specifications/language-core.md` (then-only conditionals are
   effect-only in binding/value positions) and update
   `docs/reference/control-flow.md` and the control-flow tutorial accordingly.
2. Carry an explicit position signal into `TypeChecker.checkConditional` and
   reject the then-only form in value positions with a dedicated structured
   diagnostic (new `TYPE_*` code) mapped to the conditional span; mirror the rule
   in `IrValidator`, `TypedSemanticProvenance`, the semantic-flow and self-alias
   analyses, and the typed-IR validator so no phase admits the value-position
   form.
3. Add positive, negative, boundary, conformance-corpus and fuzz coverage for
   the rejected binding, `:=`, member/element write, argument, element and return
   positions, and for the still-legal discarded statement forms in both the
   S-expression and block spellings; update the then-only fixtures,
   `Phase22ConformanceTest`, `Phase15SmokeTest`, `Domain11*Test`,
   `docs/language-testing.md` and the Phase 24 requirement matrices.
4. Close the GitHub issue only after the implementation commit and grouped
   validation evidence are posted.

## Mirrored issue

Mirrored to https://github.com/dhickel/lyra/issues/16 on 2026-09-16. No related
issue was closed: #11 (dedicated `cond`, `match` arm simplification) covers the
exhaustive forms only and this restriction is not part of it.
