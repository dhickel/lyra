import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.lex.LiteralValue;
import io.mindspice.lyra.compiler.lex.ModifierKind;
import io.mindspice.lyra.compiler.lex.NumericSuffix;
import io.mindspice.lyra.compiler.lex.Token;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.lex.TokenValue;
import io.mindspice.lyra.compiler.lex.Trivia;
import io.mindspice.lyra.compiler.lex.TriviaKind;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Assertion-grade, dependency-free tests for the complete lexical phase. */
public final class LexerTest {
    public LexerTest() {
    }

    @Test
    public void testCurrentVocabularyAndDeferredWords() {
        currentVocabularyAndDeferredWords();
    }

    @Test
    public void testCommentsAndTriviaAreRetained() {
        commentsAndTriviaAreRetained();
    }

    @Test
    public void testAnnotationSpacingRemainsVisibleToParser() {
        annotationSpacingRemainsVisibleToParser();
    }

    @Test
    public void testLiteralsAndUtf16Rules() {
        literalsAndUtf16Rules();
    }

    @Test
    public void testExactNumbersAndSigns() {
        exactNumbersAndSigns();
    }

    @Test
    public void testMalformedSourceReturnsStructuredFailure() {
        malformedSourceReturnsStructuredFailure();
    }

    @Test
    public void testBlockedLexerRegressions() {
        blockedLexerRegressions();
    }

    @Test
    public void testTypeCloseBeforeAssignmentBoundary() {
        typeCloseBeforeAssignmentBoundary();
    }

    @Test
    public void testEofSpansAndDefensiveImmutability() {
        eofSpansAndDefensiveImmutability();
    }

    private static void currentVocabularyAndDeferredWords() {
        String source = "let import as match iter when ?? I32 Array Tuple Fn Bool Char String Unit "
                + "@pub @mut @nil #T #F #NIL + - * / ^ % < <= > >= == != eq? !eq? "
                + "and or xor not ++ -- := -> :. :: => = ( ) { } [ ] : ; | , .";
        LexedSource lexed = success(source);
        List<TokenKind> actual = lexed.tokens().stream().map(Token::kind).toList();
        List<TokenKind> expected = List.of(
                TokenKind.LET, TokenKind.IMPORT, TokenKind.AS,
                TokenKind.MATCH, TokenKind.ITER, TokenKind.WHEN, TokenKind.DOUBLE_QUESTION,
                TokenKind.TYPE_NAME, TokenKind.TYPE_NAME, TokenKind.TYPE_NAME, TokenKind.TYPE_NAME,
                TokenKind.TYPE_NAME, TokenKind.TYPE_NAME, TokenKind.TYPE_NAME,
                TokenKind.TYPE_NAME,
                TokenKind.MODIFIER, TokenKind.MODIFIER, TokenKind.MODIFIER,
                TokenKind.BOOLEAN_LITERAL, TokenKind.BOOLEAN_LITERAL, TokenKind.NIL_LITERAL,
                TokenKind.PLUS, TokenKind.MINUS, TokenKind.ASTERISK, TokenKind.SLASH,
                TokenKind.CARET, TokenKind.PERCENT, TokenKind.LESS, TokenKind.LESS_EQUAL,
                TokenKind.GREATER, TokenKind.GREATER_EQUAL, TokenKind.EQUAL_EQUAL,
                TokenKind.NOT_EQUAL, TokenKind.IDENTITY_EQUAL, TokenKind.IDENTITY_NOT_EQUAL,
                TokenKind.AND, TokenKind.OR, TokenKind.XOR, TokenKind.NOT,
                TokenKind.INCREMENT, TokenKind.DECREMENT, TokenKind.COLON_EQUAL,
                TokenKind.ARROW, TokenKind.COLON_DOT, TokenKind.DOUBLE_COLON,
                TokenKind.LAMBDA_ARROW, TokenKind.EQUAL, TokenKind.LEFT_PAREN,
                TokenKind.RIGHT_PAREN, TokenKind.LEFT_BRACE, TokenKind.RIGHT_BRACE,
                TokenKind.LEFT_BRACKET, TokenKind.RIGHT_BRACKET, TokenKind.COLON,
                TokenKind.SEMICOLON, TokenKind.BAR, TokenKind.COMMA, TokenKind.PERIOD,
                TokenKind.EOF);
        check(actual.equals(expected), "every current token spelling is classified exactly");

        check(lexed.tokens().get(15).modifier().orElseThrow() == ModifierKind.PUBLIC,
                "@pub carries decoded modifier metadata");
        check(lexed.tokens().get(16).modifier().orElseThrow() == ModifierKind.MUTABLE,
                "@mut carries decoded modifier metadata");
        check(lexed.tokens().get(17).modifier().orElseThrow() == ModifierKind.NILABLE,
                "@nil carries decoded modifier metadata");
        check(lexed.tokens().stream().noneMatch(token -> token.kind().name().contains("UNIT")),
                "Unit is not invented as an empty-collection token");

        LexedSource deferredWords = success("class struct func nor nand xnor Match Iter");
        check(deferredWords.tokens().stream()
                        .filter(token -> token.kind() != TokenKind.EOF)
                        .allMatch(token -> token.kind() == TokenKind.IDENTIFIER),
                "deferred words remain ordinary identifiers rather than accepted productions/operators");
        expectFailure("@const", CompilerDiagnosticCodes.LEX_INVALID_MODIFIER);
        expectFailure("@opt", CompilerDiagnosticCodes.LEX_INVALID_MODIFIER);
        expectFailure("@static", CompilerDiagnosticCodes.LEX_INVALID_MODIFIER);
        expectFailure("eqt?", CompilerDiagnosticCodes.LEX_INVALID_OPERATOR);
        expectFailure("eqv?", CompilerDiagnosticCodes.LEX_INVALID_OPERATOR);
        expectFailure("?", CompilerDiagnosticCodes.LEX_INVALID_OPERATOR);
    }

    private static void commentsAndTriviaAreRetained() {
        String source = "  let/* outer /* nested */ still outer */  value // line\r\n:I32";
        LexedSource lexed = success(source);
        check(lexed.tokens().stream().map(Token::lexeme).toList()
                        .equals(List.of("let", "value", ":", "I32", "")),
                "comments and whitespace do not become tokens");
        check(lexed.trivia().stream().map(Trivia::kind).toList().equals(List.of(
                        TriviaKind.WHITESPACE,
                        TriviaKind.BLOCK_COMMENT,
                        TriviaKind.WHITESPACE,
                        TriviaKind.WHITESPACE,
                        TriviaKind.LINE_COMMENT,
                        TriviaKind.WHITESPACE)),
                "all trivia kinds are retained in source order");
        check(lexed.trivia().get(1).lexeme().equals("/* outer /* nested */ still outer */"),
                "nested block comment spelling is retained exactly");
        check(lexed.trivia().get(4).lexeme().equals("// line"),
                "line comment excludes but is adjacent to its line ending");
        check(lexed.tokens().get(2).leadingTrivia().getLast().lexeme().equals("\r\n"),
                "line ending before a token is retained as leading trivia");
        check(lexed.tokens().get(1).hasLeadingWhitespace(),
                "whitespace before a token is directly queryable");
        check(lexed.tokens().get(1).hasLeadingTrivia(),
                "comment-only and whitespace trivia are distinguishable from adjacency");
        for (Token token : lexed.tokens()) {
            check(token.sourceText(lexed.snapshot()).equals(token.lexeme()),
                    "every token spelling agrees with its authoritative span");
        }
        for (Trivia trivia : lexed.trivia()) {
            check(trivia.span().length() == trivia.lexeme().length(),
                    "trivia spans use UTF-16 code-unit lengths");
        }
        expectFailure("/* outer /* nested */", CompilerDiagnosticCodes.LEX_UNTERMINATED_BLOCK_COMMENT);
    }

    private static void annotationSpacingRemainsVisibleToParser() {
        LexedSource required = success("name :Type");
        Token requiredColon = required.tokens().get(1);
        Token requiredType = required.tokens().get(2);
        check(requiredColon.hasLeadingWhitespace(), "name :Type records required whitespace before colon");
        check(!requiredType.hasLeadingWhitespace(), "name :Type records no whitespace after colon");

        LexedSource missingBefore = success("name:Type");
        check(!missingBefore.tokens().get(1).hasLeadingWhitespace(),
                "name:Type remains lexically representable for a parser diagnostic");
        check(!missingBefore.tokens().get(2).hasLeadingWhitespace(),
                "the lexer does not hide adjacency from the parser");

        LexedSource extraAfter = success("name : Type");
        check(extraAfter.tokens().get(1).hasLeadingWhitespace(),
                "name : Type still records whitespace before colon");
        check(extraAfter.tokens().get(2).hasLeadingWhitespace(),
                "name : Type records forbidden whitespace after colon");

        LexedSource commentBetween = success("name/*comment*/:Type");
        check(!commentBetween.tokens().get(1).hasLeadingWhitespace()
                        && commentBetween.tokens().get(1).hasLeadingTrivia(),
                "comments are preserved separately from actual whitespace");

        LexedSource commas = success("[a,b, c]");
        check(commas.tokens().stream().filter(token -> token.kind() == TokenKind.COMMA).count() == 2,
                "commas are retained for later list-context legality checks");
    }

    private static void literalsAndUtf16Rules() {
        String unicodeEscape = "\\uD800";
        String escaped = "\""
                + "\\\\\\\"\\'\\n\\r\\t\\b\\f\\0"
                + unicodeEscape
                + "\"";
        LexedSource stringSource = success(escaped);
        LiteralValue.StringLiteral string = (LiteralValue.StringLiteral)
                stringSource.tokens().getFirst().value();
        String expected = "\\\"'\n\r\t\b\f\0" + "\uD800";
        check(string.value().equals(expected), "all allowed string escapes decode exactly");
        check(stringSource.tokens().getFirst().lexeme().equals(escaped),
                "string raw lexeme remains distinct from decoded content");

        LexedSource chars = success("'a' 'é' '\\n' '\\'' '\\uD800'");
        List<LiteralValue.CharLiteral> decoded = chars.tokens().stream()
                .filter(token -> token.kind() == TokenKind.CHAR_LITERAL)
                .map(token -> (LiteralValue.CharLiteral) token.value())
                .toList();
        check(decoded.equals(List.of(
                        new LiteralValue.CharLiteral('a'),
                        new LiteralValue.CharLiteral('é'),
                        new LiteralValue.CharLiteral('\n'),
                        new LiteralValue.CharLiteral('\''),
                        new LiteralValue.CharLiteral('\uD800'))),
                "characters decode to exactly one UTF-16 code unit, including an unpaired surrogate escape");
        expectFailure("'😀'", CompilerDiagnosticCodes.LEX_INVALID_CHAR_LENGTH);
        expectFailure("''", CompilerDiagnosticCodes.LEX_INVALID_CHAR_LENGTH);
        expectFailure("'ab'", CompilerDiagnosticCodes.LEX_INVALID_CHAR_LENGTH);
        expectFailure("\"bad\nstring\"", CompilerDiagnosticCodes.LEX_UNTERMINATED_STRING);
        expectFailure("'bad\nchar'", CompilerDiagnosticCodes.LEX_UNTERMINATED_CHAR);
        expectFailure("\"bad\\x\"", CompilerDiagnosticCodes.LEX_INVALID_ESCAPE);
        expectFailure("'\\u12'", CompilerDiagnosticCodes.LEX_INVALID_ESCAPE);
        expectFailure("'\\u12345'", CompilerDiagnosticCodes.LEX_INVALID_ESCAPE);

        LexedSource unit = success("()");
        check(unit.tokens().stream().map(Token::kind).toList().equals(List.of(
                        TokenKind.LEFT_PAREN, TokenKind.RIGHT_PAREN, TokenKind.EOF)),
                "Unit spelling is the ordinary empty parenthesized form");
    }

    private static void exactNumbersAndSigns() {
        LexedSource numbers = success("0 42I32 42U16 1.0 1.0e+3F32 -42 +1");
        List<Token> valueTokens = numbers.tokens().subList(0, numbers.tokens().size() - 1);
        check(valueTokens.get(0).value().equals(
                        new LiteralValue.IntegerLiteral(BigInteger.ZERO, NumericSuffix.NONE)),
                "unsuffixed integer values remain exact integers");
        check(valueTokens.get(1).value().equals(
                        new LiteralValue.IntegerLiteral(BigInteger.valueOf(42), NumericSuffix.I32)),
                "integer suffix metadata is preserved");
        check(valueTokens.get(2).value().equals(
                        new LiteralValue.IntegerLiteral(BigInteger.valueOf(42), NumericSuffix.U16)),
                "unsigned suffix metadata is preserved");
        LiteralValue.FloatLiteral decimal = (LiteralValue.FloatLiteral) valueTokens.get(3).value();
        check(decimal.value().compareTo(new BigDecimal("1.0")) == 0
                        && decimal.suffix() == NumericSuffix.NONE,
                "decimal values retain exact BigDecimal content and unsuffixed spelling");
        LiteralValue.FloatLiteral exponent = (LiteralValue.FloatLiteral) valueTokens.get(4).value();
        check(exponent.value().compareTo(new BigDecimal("1.0e+3")) == 0
                        && exponent.suffix() == NumericSuffix.F32,
                "exponents and floating suffixes remain lossless metadata");
        check(valueTokens.get(5).kind() == TokenKind.MINUS
                        && valueTokens.get(6).kind() == TokenKind.INTEGER_LITERAL
                        && valueTokens.get(7).kind() == TokenKind.PLUS,
                "leading signs are operators rather than numeric lexeme content");
        check(valueTokens.get(6).lexeme().equals("42"), "signed integer lexeme excludes its sign");

        LexedSource allSuffixes = success(
                "1I8 1I16 1I32 1I64 1U8 1U16 1U32 1U64 1.0F32 1.0F64");
        List<NumericSuffix> suffixes = allSuffixes.tokens().stream()
                .filter(token -> token.kind() == TokenKind.INTEGER_LITERAL
                        || token.kind() == TokenKind.FLOAT_LITERAL)
                .map(token -> token.value())
                .map(value -> value instanceof LiteralValue.IntegerLiteral integer
                        ? integer.suffix()
                        : ((LiteralValue.FloatLiteral) value).suffix())
                .toList();
        check(suffixes.equals(List.of(
                        NumericSuffix.I8, NumericSuffix.I16, NumericSuffix.I32, NumericSuffix.I64,
                        NumericSuffix.U8, NumericSuffix.U16, NumericSuffix.U32, NumericSuffix.U64,
                        NumericSuffix.F32, NumericSuffix.F64)),
                "all uppercase primitive suffixes are recognized");
        success("127I8 255U8 18446744073709551615U64");
        success("-128I8");
        success("3.4028235e38F32 1.7976931348623157e308F64");
        expectFailure("128I8", CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE);
        expectFailure("256U8", CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE);
        expectFailure("18446744073709551616", CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE);
        expectFailure("3.5e38F32", CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE);
        expectFailure("1.8e308F64", CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE);

        for (String malformed : List.of(
                "1e3", ".5", "3.", "1.0.0", "1.0e+", "1.0f32",
                "1.0I32", "42F32", "42I3", "1.0F128", "00x")) {
            expectFailure(malformed, CompilerDiagnosticCodes.LEX_INVALID_NUMBER);
        }
    }

    private static void malformedSourceReturnsStructuredFailure() {
        expectFailure("&", CompilerDiagnosticCodes.LEX_INVALID_CHARACTER);
        expectFailure("\"unterminated", CompilerDiagnosticCodes.LEX_UNTERMINATED_STRING);
        expectFailure("'unterminated", CompilerDiagnosticCodes.LEX_UNTERMINATED_CHAR);
        expectFailure("#True", CompilerDiagnosticCodes.LEX_INVALID_LITERAL);
        expectFailure("#nil", CompilerDiagnosticCodes.LEX_INVALID_LITERAL);
        expectFailure("@", CompilerDiagnosticCodes.LEX_INVALID_MODIFIER);
        for (String unsupported : List.of("@const", "@opt", "@static", "&", "~", "$", "`", "\\\\")) {
            io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code = unsupported.startsWith("@")
                    ? CompilerDiagnosticCodes.LEX_INVALID_MODIFIER
                    : CompilerDiagnosticCodes.LEX_INVALID_CHARACTER;
            expectFailure(unsupported, code);
        }
        expectFailure("!", CompilerDiagnosticCodes.LEX_INVALID_OPERATOR);
        expectFailure("!eq?x", CompilerDiagnosticCodes.LEX_INVALID_OPERATOR);

        PhaseResult<LexedSource> result = Lexer.lex(snapshot("?"));
        check(result instanceof PhaseResult.Failure<?>, "malformed source is a failed phase result");
        check(result.optionalValue().isEmpty(), "failed lexing never exposes a partial token artifact");
        check(result.diagnostics().size() == 1, "lexing fails fast with one blocking diagnostic");
        Diagnostic diagnostic = result.diagnostics().getFirst();
        check(diagnostic.code().phase().name().equals("LEX"), "lex diagnostics are phase-specific");
        check(diagnostic.primarySpan().equals(SourceSpan.of(SourceId.path("test.lyra"), 0, 1)),
                "invalid-character diagnostics have the exact primary span");
    }

    private static void blockedLexerRegressions() {
        expectFailure("x - 128I8", CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE);
        success("-128I8 (- 128I8) x * -128I8 x - 127I8");

        expectFailure("1.0e-46F32", CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE);
        expectFailure("1.0e-324F64", CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE);
        success("0.0e-46F32 1.4e-45F32 0.0e-324F64 4.9e-324F64");
        success("0.0e999999999999999999999F64");

        expectFailure("1.0e999999999999999999999F64",
                CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE);
        expectFailure("1.0e-999999999999999999999F32",
                CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE);
        expectFailure("1.0e+", CompilerDiagnosticCodes.LEX_INVALID_NUMBER);

        PhaseResult<LexedSource> supplementaryResult = Lexer.lex(snapshot("😀"));
        check(supplementaryResult instanceof PhaseResult.Failure<?>,
                "unsupported supplementary code points fail lexing");
        check(supplementaryResult.optionalValue().isEmpty(),
                "supplementary-character failure publishes no partial token stream");
        Diagnostic supplementary = supplementaryResult.diagnostics().getFirst();
        check(supplementary.code().equals(CompilerDiagnosticCodes.LEX_INVALID_CHARACTER),
                "supplementary characters use the invalid-character diagnostic");
        check(supplementary.primarySpan().equals(
                        SourceSpan.of(SourceId.path("test.lyra"), 0, 2)),
                "supplementary-character diagnostics span both UTF-16 code units");
        check(supplementary.message().contains("U+1F600"),
                "supplementary-character diagnostics report the complete Unicode code point");
    }

    private static void typeCloseBeforeAssignmentBoundary() {
        LexedSource simple = success("let f :Array<I32>=Array<I32>[1]");
        check(simple.tokens().stream().map(Token::kind).toList().equals(List.of(
                        TokenKind.LET, TokenKind.IDENTIFIER, TokenKind.COLON,
                        TokenKind.TYPE_NAME, TokenKind.LESS, TokenKind.TYPE_NAME,
                        TokenKind.GREATER, TokenKind.EQUAL, TokenKind.TYPE_NAME,
                        TokenKind.LESS, TokenKind.TYPE_NAME, TokenKind.GREATER,
                        TokenKind.LEFT_BRACKET, TokenKind.INTEGER_LITERAL,
                        TokenKind.RIGHT_BRACKET, TokenKind.EOF)),
                "a type close immediately before assignment remains two syntactic tokens");
        Token close = simple.tokens().get(6);
        Token assignment = simple.tokens().get(7);
        check(close.lexeme().equals(">") && assignment.lexeme().equals("="),
                "split boundary tokens retain their exact source spellings");
        check(close.span().endOffset() == assignment.span().startOffset()
                        && close.span().length() == 1 && assignment.span().length() == 1,
                "split boundary tokens retain adjacent one-code-unit source spans");
        check(assignment.isAdjacentToPrevious(),
                "the synthetic lexical boundary does not invent trivia");

        LexedSource nested = success("let f :Array<Tuple<I32,Array<String>>>=Array[]");
        List<TokenKind> nestedKinds = nested.tokens().stream().map(Token::kind).toList();
        int equal = nestedKinds.indexOf(TokenKind.EQUAL);
        check(equal > 1
                        && nestedKinds.get(equal - 1) == TokenKind.GREATER
                        && nestedKinds.get(equal - 2) == TokenKind.GREATER
                        && nestedKinds.get(equal - 3) == TokenKind.GREATER,
                "nested type closes are balanced before an adjacent assignment");
        check(nested.tokens().get(equal - 1).span().endOffset()
                        == nested.tokens().get(equal).span().startOffset(),
                "the outer nested close and assignment preserve their exact boundary");

        LexedSource comparison = success("a>=b");
        check(comparison.tokens().stream().map(Token::kind).toList().equals(List.of(
                        TokenKind.IDENTIFIER, TokenKind.GREATER_EQUAL,
                        TokenKind.IDENTIFIER, TokenKind.EOF)),
                "ordinary greater-than-or-equal remains one comparison token");
    }

    private static void eofSpansAndDefensiveImmutability() {
        LexedSource lexed = success("\"😀\" \n");
        Token string = lexed.tokens().getFirst();
        check(string.span().equals(SourceSpan.of(SourceId.path("test.lyra"), 0, 4)),
                "supplementary characters occupy two UTF-16 code units in token spans");
        check(lexed.eof().span().equals(SourceSpan.at(SourceId.path("test.lyra"), 6)),
                "EOF is zero-width at decoded UTF-16 source length");
        check(lexed.eof().lexeme().isEmpty(), "EOF has an empty exact lexeme");
        check(lexed.eof().hasLeadingWhitespace(), "EOF retains trailing whitespace trivia");

        List<Token> mutableTokens = new ArrayList<>(lexed.tokens());
        List<Diagnostic> mutableDiagnostics = new ArrayList<>();
        LexedSource copied = new LexedSource(lexed.snapshot(), mutableTokens, mutableDiagnostics);
        mutableTokens.clear();
        mutableDiagnostics.add(Diagnostic.error(
                CompilerDiagnosticCodes.LEX_INVALID_CHARACTER,
                SourceSpan.at(SourceId.path("test.lyra"), 0),
                "later mutation"));
        check(copied.tokens().size() == 2 && copied.diagnostics().isEmpty(),
                "LexedSource defensively copies published lists");
        expectUnsupported(() -> copied.tokens().clear());
        expectUnsupported(() -> copied.diagnostics().clear());
        expectUnsupported(() -> copied.trivia().clear());
        expectUnsupported(() -> copied.tokens().getFirst().leadingTrivia().clear());

        List<Trivia> leading = new ArrayList<>();
        leading.add(new Trivia(TriviaKind.WHITESPACE, " ",
                SourceSpan.of(SourceId.path("test.lyra"), 0, 1)));
        Token token = new Token(
                TokenKind.IDENTIFIER,
                "x",
                SourceSpan.of(SourceId.path("test.lyra"), 1, 2),
                leading,
                new TokenValue.Identifier("x"));
        leading.clear();
        check(token.leadingTrivia().size() == 1, "Token defensively copies leading trivia");
        expectUnsupported(() -> token.leadingTrivia().clear());
    }

    @SuppressWarnings("unchecked")
    private static LexedSource success(String text) {
        PhaseResult<LexedSource> result = Lexer.lex(snapshot(text));
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("expected lexing success for " + printable(text)
                    + ": " + result.diagnostics().stream().map(Diagnostic::render).toList());
        }
        return ((PhaseResult.Success<LexedSource>) success).value();
    }

    private static void expectFailure(String text, io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code) {
        PhaseResult<LexedSource> result = Lexer.lex(snapshot(text));
        check(result instanceof PhaseResult.Failure<?>,
                "expected lexing failure for " + printable(text));
        check(result.optionalValue().isEmpty(), "failed lexing has no partial value");
        check(result.diagnostics().getFirst().code().equals(code),
                "unexpected diagnostic for " + printable(text) + ": " + result.diagnostics().getFirst().render());
    }

    @SuppressWarnings("unchecked")
    private static SourceSnapshot snapshot(String text) {
        PhaseResult<SourceSnapshot> result = SourceSnapshot.capture(
                SourceId.path("test.lyra"),
                PhysicalSourceKey.uri(URI.create("memory:test")),
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("test source did not capture");
        }
        return ((PhaseResult.Success<SourceSnapshot>) success).value();
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("expected an unmodifiable collection");
    }

    private static String printable(String value) {
        return value.replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
