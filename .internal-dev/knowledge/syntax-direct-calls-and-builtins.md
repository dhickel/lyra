# Direct-call syntax and built-ins

## Topic
Do not equate :: syntax with user-defined ordinary functions only.

## Source References
- User correction during match-syntax discussion: built-ins may use :: syntax.
- lyra-compiler/src/main/resources/grammar_spec.md, expressions and accessors.
- lyra-compiler/src/main/java/io/mindspice/lyra/compiler/grammar/GrammarMatcher.java, parseUnqualifiedDirectCall.
- .internal-dev/specifications/backend-runtime.md, intrinsic std->io module.

## Key Takeaways
The grammar recognizes :: followed by an identifier and bracket arguments without deciding whether the target is user-defined or built-in. A proposed compiler-recognized match form must preserve selective evaluation, but that implementation requirement does not prohibit the proposed ::match[...] spelling. The earlier recommendation to avoid :: solely because match is not an ordinary eager function was unjustified.

## Project Relevance
When discussing new forms, distinguish surface notation from evaluation semantics. The user subsequently authorized implementation of both match spellings; the accepted contract is now recorded in language-core.md and decisions.md. Implementation and validation status must be checked separately.

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
Advanced binding, destructuring, and type-pattern designs remain deferred. The initial value/conditional match syntax and semantics were settled by the user; see specifications/language-core.md.
