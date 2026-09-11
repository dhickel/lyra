# Grammar/Lexer Boundary Validation

## Topic
Context-sensitive token boundaries for composite type closures and grammar descriptor replay invariants.

## Source References
- `.internal-dev/specifications/language-core.md`
- `.internal-dev/specifications/backend-runtime.md`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/lex/Lexer.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/grammar/GrammarProgram.java`
- `lyra-compiler/src/test/java/LexerTest.java`
- `lyra-compiler/src/test/java/GrammarMatcherTest.java`

## Key Takeaways
- Maximal-munch `>=` conflicts with a composite type close immediately followed by binding assignment, as in `Array<I32>=...`; whitespace is not a valid workaround.
- The lexer can preserve ordinary `>=` comparisons while splitting only inside a narrowly tracked built-in composite type-argument depth. Each resulting `>` and `=` token must retain its own exact one-code-unit source span and no invented trivia.
- Nested composite types require depth tracking rather than a one-token lookbehind at the closing boundary.
- Grammar replay validation must check production-specific token roles and exact metadata correspondence, not only index ranges and broad token categories. In particular, a `LET_BINDING` primary token is the production's opening `let`, not merely any token in its range.
- Reassignment enclosure belongs to the reassignment descriptor's delimiter metadata. The first child may independently begin with `(`, so source-start token inspection cannot distinguish `(target := value)` from `(target) := value`.
- Primary accessor/operator validation must derive the exact token index from delimiters and child boundaries; checking only token kind permits metadata to point at a nested accessor of the same kind.
- Metadata collections are production roles, not optional hints: roleless productions carry empty lists, while import and namespace arrow lists correspond exactly to the gaps between path/access children.
- Aggregate descriptor validation includes the exact `Array`/`Tuple` prefix, prefix kind, and child ranges. Numeric tuple members are unsuffixed nonnegative ASCII-decimal integer positions.

## Project Relevance
These invariants keep the immutable lexical artifact deterministic at an otherwise ambiguous boundary and make malformed constructed grammar descriptors fail before parser replay.

## Open Questions
None for the repaired domain-4 boundary.
