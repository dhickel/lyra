# Current language syntax and grammar familiarization

## Scope
Read-only familiarization requested by the user, including direct root review of the complete living language-core specification, resource grammar EBNF, and GrammarMatcher.java, plus representative positive/negative GrammarMatcherTest assertions. A read-only exploration agent additionally inspected lexer/parser/AST and representative corpus/module tests. No compiler changes or test runs.

## Findings
- Current syntax uses let for every named declaration, contextual or inline fully typed lambdas, Fn<P;R>, prefix/bracket operators, callable-value S-expressions, and :: bracket direct calls.
- Exact named annotation spelling is name :Type. The repository AGENTS examples using name : Type and saying :: is required for all function calls are not accurate descriptions of the living contract or matcher; use the living specification and matcher instead.
- Parentheses are not ordinary grouping: (f) is a zero-argument callable call. Array[] and Tuple[] are Unit, unlike Array<T>[]. Bare nonempty Array[...] requires an expected complete array type at semantic checking.
- Imports are header-only; selected names are whitespace-separated, not comma-separated. Namespace value access ends in :. and direct calls in :: with brackets.
- The matcher recognizes structure and operator arity; semantic phases still determine complete typing, callable/member legality, nilability, mutation authorization, and aggregate shape.
- Resource EBNF is a synchronization aid, not an exact formal transcription: its namespace-suffix identifier repetition omits intervening arrows handled by parseNamespaceSuffix; its parenthesized alternatives omit the implemented (target := value) case; its callable-call alternative does not show argument commas handled by parseParenthesized. These are documentation observations, not demonstrated runtime defects.

## Risk Assessment
No runtime correctness claim is made. Syntax acceptance alone does not establish a well-typed executable program. In particular, a typed empty tuple expression may match grammar but fail shape checking. Existing worktree changes were preserved.

## Recommendations
Use .internal-dev/specifications/language-core.md and GrammarMatcher.java for subsequent syntax discussions. Keep syntax recognition separate from semantic validity. If editing grammar documentation later, reconcile the shorthand EBNF and top-level AGENTS examples with these sources.

## Follow-ups
No implementation requested. No tests run. Documentation discrepancies remain unchanged and are tracked here.
