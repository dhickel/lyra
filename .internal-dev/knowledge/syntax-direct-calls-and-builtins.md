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

## Open Questions
Advanced binding, destructuring, and type-pattern designs remain deferred. The initial value/conditional match syntax and semantics were settled by the user; see specifications/language-core.md.
