# Direct-call syntax and built-ins

## Topic
Do not equate :: syntax with user-defined ordinary functions only.

## Source References
- User correction during match-syntax discussion: built-ins may use :: syntax.
- lyra-compiler/src/main/resources/grammar_spec.md, expressions and accessors.
- lyra-compiler/src/main/java/io/mindspice/lyra/compiler/grammar/GrammarMatcher.java, parseUnqualifiedDirectCall.
- .internal-dev/specifications/backend-runtime.md, intrinsic std->io module.

## Key Takeaways
`::` is reserved for direct calls of resolved source function or method names in the
local or imported space. Compiler-recognized built-ins are not callable values and
never take `::`: `match`, `cond`, `iter` and `while` use their bare bracket spelling
(`match[...]`, `iter[...]`, `while[...]`), and the operators, primitive/`String`
conversions and `Array`/`Tuple` literal forms already use bare bracket application.
The `::match[...]`, `::iter[...]` and `::while[...]` spellings, including qualified
(`ns->::match[...]`) and receiver (`x::match[...]`) readings, are rejected with the
structured obsolete-special-form diagnostic (`LYC-PARSE-012`).
`cond` is a reserved parenthesized-only special form; `cond[...]` is invalid.

## Project Relevance
Surface notation tracks semantics: a built-in that is not an ordinary eager callable
must not look like one. Do not infer invocation authority from a matching name,
spelling or descriptor. Language contract version 2 settled this; see
language-core.md, grammar_spec.md and the last decisions.md entry. Note the earlier
recorded recommendation that `::match[...]` was acceptable is superseded: it was
reversed by the owner interview recorded in the issues-#6-#13 plan.

## Direct :: calls vs callable-value calls

`::f[args]` is not sugar for `(f args)` and the two take different compiler paths:

- `(f args)` is a callable-value call: TypeChecker.checkCallableCall ->
  TypedExpressionKind.CALLABLE_CALL -> IrNode.CallableCall ->
  JvmBytecodeEmitter.emitCallableCall.
- `::f[args]` is a direct named call: checkDirectCall -> DIRECT_CALL ->
  IrNode.DirectCall -> emitDirectCall.

Both end in invokeinterface on the generated Fn interface, but the callable path emits a
per-call `LyraSignature.parse(canonicalSpelling)` plus
`LyraClosureSupport.requireAuthenticatedForGeneratedInvocation` (token/thread checks,
sameArtifact/sameSession, structural signature equals) before every invoke. That is the
intended callable authentication boundary (backend-runtime.md rejects arbitrary Java SAMs
with LYR-LINK), but emitCallableCall applies it uniformly with no fast path for
provably compiler-owned immutable targets, so self-recursive `(fib ...)` forms can run two
orders of magnitude slower than `::fib[...]` (measured ~137x on fib(30); ~450 ns marginal
per call). emitCallableCall already computes targetDeclaration() but only uses it for
intrinsics. See bugs/callable-call-per-call-authentication-overhead.

## Open Questions
Advanced binding, destructuring, and type-pattern designs remain deferred. Value
`match` and `cond` syntax and semantics are settled by the current language
contract; see specifications/language-core.md.
