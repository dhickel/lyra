package io.mindspice.lyra.compiler.lex;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless, UTF-16 indexed lexer for the current Lyra lexical contract.
 *
 * <p>All mutable state is local to one invocation.  A successful invocation
 * publishes one immutable {@link LexedSource}; a source error publishes only
 * its structured diagnostic and never a partial token stream.</p>
 */
public final class Lexer {
    private static final BigInteger UNSIGNED_64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private static final Map<String, TokenKind> WORD_KINDS = Map.ofEntries(
            Map.entry("let", TokenKind.LET),
            Map.entry("import", TokenKind.IMPORT),
            Map.entry("as", TokenKind.AS),
            Map.entry("match", TokenKind.MATCH),
            Map.entry("iter", TokenKind.ITER),
            Map.entry("while", TokenKind.WHILE),
            Map.entry("struct", TokenKind.STRUCT),
            Map.entry("class", TokenKind.CLASS),
            Map.entry("when", TokenKind.WHEN),
            Map.entry("and", TokenKind.AND),
            Map.entry("or", TokenKind.OR),
            Map.entry("xor", TokenKind.XOR),
            Map.entry("not", TokenKind.NOT),
            Map.entry("I8", TokenKind.TYPE_NAME),
            Map.entry("I16", TokenKind.TYPE_NAME),
            Map.entry("I32", TokenKind.TYPE_NAME),
            Map.entry("I64", TokenKind.TYPE_NAME),
            Map.entry("U8", TokenKind.TYPE_NAME),
            Map.entry("U16", TokenKind.TYPE_NAME),
            Map.entry("U32", TokenKind.TYPE_NAME),
            Map.entry("U64", TokenKind.TYPE_NAME),
            Map.entry("F32", TokenKind.TYPE_NAME),
            Map.entry("F64", TokenKind.TYPE_NAME),
            Map.entry("Bool", TokenKind.TYPE_NAME),
            Map.entry("Char", TokenKind.TYPE_NAME),
            Map.entry("String", TokenKind.TYPE_NAME),
            Map.entry("Unit", TokenKind.TYPE_NAME),
            Map.entry("Array", TokenKind.TYPE_NAME),
            Map.entry("Range", TokenKind.TYPE_NAME),
            Map.entry("Tuple", TokenKind.TYPE_NAME),
            Map.entry("Fn", TokenKind.TYPE_NAME));

    private Lexer() {
    }

    /** Lexes one already captured, decoded source snapshot. */
    public static PhaseResult<LexedSource> lex(SourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new Scanner(snapshot).run();
    }

    /** Alias matching the older prototype's phase-operation naming. */
    public static PhaseResult<LexedSource> process(SourceSnapshot snapshot) {
        return lex(snapshot);
    }

    private static final class Scanner {
        private final SourceSnapshot snapshot;
        private final String source;
        private final List<Token> tokens = new ArrayList<>();
        private final List<Trivia> pendingTrivia = new ArrayList<>();
        private int index;
        private TokenKind previousKind;
        private boolean previousMinusWasUnary;
        private int typeArgumentDepth;

        private Scanner(SourceSnapshot snapshot) {
            this.snapshot = snapshot;
            this.source = snapshot.text();
        }

        private PhaseResult<LexedSource> run() {
            while (index < source.length()) {
                Diagnostic triviaError = readTrivia();
                if (triviaError != null) {
                    return PhaseResult.failure(triviaError);
                }
                if (index >= source.length()) {
                    break;
                }

                Diagnostic tokenError = readToken();
                if (tokenError != null) {
                    return PhaseResult.failure(tokenError);
                }
            }

            add(TokenKind.EOF, index, index, TokenValue.None.INSTANCE);
            LexedSource result = new LexedSource(snapshot, tokens, List.of());
            return PhaseResult.success(result, result.diagnostics());
        }

        private Diagnostic readTrivia() {
            while (index < source.length()) {
                char character = source.charAt(index);
                if (isWhitespace(character)) {
                    int start = index;
                    do {
                        index++;
                    } while (index < source.length() && isWhitespace(source.charAt(index)));
                    pendingTrivia.add(new Trivia(
                            TriviaKind.WHITESPACE,
                            source.substring(start, index),
                            span(start, index)));
                    continue;
                }

                if (startsWith("//")) {
                    int start = index;
                    index += 2;
                    while (index < source.length() && !isLineBreak(source.charAt(index))) {
                        index++;
                    }
                    pendingTrivia.add(new Trivia(
                            TriviaKind.LINE_COMMENT,
                            source.substring(start, index),
                            span(start, index)));
                    continue;
                }

                if (startsWith("/*")) {
                    int start = index;
                    index += 2;
                    int depth = 1;
                    while (index < source.length() && depth > 0) {
                        if (startsWith("/*")) {
                            depth++;
                            index += 2;
                        } else if (startsWith("*/")) {
                            depth--;
                            index += 2;
                        } else {
                            index++;
                        }
                    }
                    if (depth != 0) {
                        return error(
                                CompilerDiagnosticCodes.LEX_UNTERMINATED_BLOCK_COMMENT,
                                start,
                                source.length(),
                                "unterminated block comment");
                    }
                    pendingTrivia.add(new Trivia(
                            TriviaKind.BLOCK_COMMENT,
                            source.substring(start, index),
                            span(start, index)));
                    continue;
                }

                break;
            }
            return null;
        }

        private Diagnostic readToken() {
            int start = index;

            if (startsWith("..")) {
                boolean inclusive = startsWith("...");
                index += inclusive ? 3 : 2;
                add(inclusive ? TokenKind.RANGE_INCLUSIVE : TokenKind.RANGE_EXCLUSIVE,
                        start, index, TokenValue.None.INSTANCE);
                return null;
            }

            if (startsWith("!eq?")) {
                int end = index + 4;
                if (!hasTokenBoundary(end)) {
                    return invalidOperator(start, malformedOperatorEnd(end), "invalid identity operator spelling");
                }
                index = end;
                add(TokenKind.IDENTITY_NOT_EQUAL, start, index, TokenValue.None.INSTANCE);
                return null;
            }
            if (startsWith("!=")) {
                index += 2;
                add(TokenKind.NOT_EQUAL, start, index, TokenValue.None.INSTANCE);
                return null;
            }
            if (source.charAt(index) == '!') {
                index++;
                return invalidOperator(start, index, "unsupported or incomplete '!' operator");
            }

            if (startsWith("eq?")) {
                int end = index + 3;
                if (!hasTokenBoundary(end)) {
                    return invalidOperator(start, malformedOperatorEnd(end), "invalid identity operator spelling");
                }
                index = end;
                add(TokenKind.IDENTITY_EQUAL, start, index, TokenValue.None.INSTANCE);
                return null;
            }

            char character = source.charAt(index);
            if (character == '?') {
                if (startsWith("??")) {
                    index += 2;
                    add(TokenKind.DOUBLE_QUESTION, start, index, TokenValue.None.INSTANCE);
                    return null;
                }
                index++;
                return error(
                        CompilerDiagnosticCodes.LEX_INVALID_OPERATOR,
                        start,
                        index,
                        "'?' is only valid as part of ??, eq?, or !eq?");
            }
            if (character == '#') {
                return readHashLiteral();
            }
            if (character == '@') {
                return readModifier();
            }
            if (character == '"') {
                return readStringLiteral();
            }
            if (character == '\'') {
                return readCharLiteral();
            }
            if (isAsciiDigit(character)) {
                return readNumberLiteral();
            }
            if (isIdentifierStart(character)) {
                return readWord();
            }

            switch (character) {
                case '(' -> single(TokenKind.LEFT_PAREN);
                case ')' -> single(TokenKind.RIGHT_PAREN);
                case '{' -> single(TokenKind.LEFT_BRACE);
                case '}' -> single(TokenKind.RIGHT_BRACE);
                case '[' -> single(TokenKind.LEFT_BRACKET);
                case ']' -> single(TokenKind.RIGHT_BRACKET);
                case ',' -> single(TokenKind.COMMA);
                case ';' -> single(TokenKind.SEMICOLON);
                case '|' -> single(TokenKind.BAR);
                case '.' -> {
                    if (index + 1 < source.length() && isAsciiDigit(source.charAt(index + 1))) {
                        return invalidNumber(
                                start,
                                malformedNumberEnd(index + 1),
                                "a decimal literal must start with a digit");
                    }
                    single(TokenKind.PERIOD);
                }
                case ':' -> readColonOperator();
                case '+' -> readPlusOperator();
                case '-' -> readMinusOperator();
                case '*' -> single(TokenKind.ASTERISK);
                case '/' -> single(TokenKind.SLASH);
                case '^' -> single(TokenKind.CARET);
                case '%' -> single(TokenKind.PERCENT);
                case '<' -> readLessOperator();
                case '>' -> readGreaterOperator();
                case '=' -> readEqualOperator();
                default -> {
                    int codePoint = source.codePointAt(index);
                    index += Character.charCount(codePoint);
                    return error(
                            CompilerDiagnosticCodes.LEX_INVALID_CHARACTER,
                            start,
                            index,
                            "unsupported character U+"
                                    + String.format(Locale.ROOT, "%04X", codePoint));
                }
            }
            return null;
        }

        private Diagnostic readWord() {
            int start = index;
            index++;
            while (index < source.length() && isIdentifierPart(source.charAt(index))) {
                index++;
            }
            String word = source.substring(start, index);
            TokenKind kind = WORD_KINDS.get(word);
            if (kind == null) {
                add(TokenKind.IDENTIFIER, start, index, new TokenValue.Identifier(word));
            } else {
                add(kind, start, index, TokenValue.None.INSTANCE);
            }
            return null;
        }

        private Diagnostic readModifier() {
            int start = index;
            index++;
            if (index >= source.length() || !isIdentifierStart(source.charAt(index))) {
                return error(
                        CompilerDiagnosticCodes.LEX_INVALID_MODIFIER,
                        start,
                        index,
                        "a modifier must use one of @pub, @mut, or @nil");
            }
            index++;
            while (index < source.length() && isIdentifierPart(source.charAt(index))) {
                index++;
            }
            String spelling = source.substring(start, index);
            ModifierKind modifier = ModifierKind.fromSpelling(spelling).orElse(null);
            if (modifier == null) {
                return error(
                        CompilerDiagnosticCodes.LEX_INVALID_MODIFIER,
                        start,
                        index,
                        "unsupported modifier spelling '" + spelling + "'");
            }
            add(TokenKind.MODIFIER, start, index, new TokenValue.Modifier(modifier));
            return null;
        }

        private Diagnostic readHashLiteral() {
            int start = index;
            if (startsWith("#NIL") && hasTokenBoundary(index + 4)) {
                index += 4;
                add(TokenKind.NIL_LITERAL, start, index, LiteralValue.NilLiteral.INSTANCE);
                return null;
            }
            if (startsWith("#T") && hasTokenBoundary(index + 2)) {
                index += 2;
                add(TokenKind.BOOLEAN_LITERAL, start, index, new LiteralValue.BooleanLiteral(true));
                return null;
            }
            if (startsWith("#F") && hasTokenBoundary(index + 2)) {
                index += 2;
                add(TokenKind.BOOLEAN_LITERAL, start, index, new LiteralValue.BooleanLiteral(false));
                return null;
            }

            index = Math.min(source.length(), start + 1);
            int end = malformedLiteralEnd(index);
            return error(
                    CompilerDiagnosticCodes.LEX_INVALID_LITERAL,
                    start,
                    end,
                    "boolean and nil literals must be exactly #T, #F, or #NIL");
        }

        private Diagnostic readStringLiteral() {
            int start = index++;
            StringBuilder decoded = new StringBuilder();
            while (index < source.length()) {
                char character = source.charAt(index);
                if (character == '"') {
                    index++;
                    add(TokenKind.STRING_LITERAL, start, index,
                            new LiteralValue.StringLiteral(decoded.toString()));
                    return null;
                }
                if (character == '\\') {
                    EscapeResult escape = decodeEscape(index);
                    if (escape.error() != null) {
                        return escape.error();
                    }
                    decoded.append(escape.value());
                    index = escape.nextIndex();
                    continue;
                }
                if (isLineBreak(character)) {
                    return error(
                            CompilerDiagnosticCodes.LEX_UNTERMINATED_STRING,
                            start,
                            index,
                            "string literals may not contain an unescaped line break");
                }
                if (Character.isISOControl(character)) {
                    index++;
                    return error(
                            CompilerDiagnosticCodes.LEX_INVALID_LITERAL,
                            index - 1,
                            index,
                            "control characters in strings must use an allowed escape");
                }
                decoded.append(character);
                index++;
            }
            return error(
                    CompilerDiagnosticCodes.LEX_UNTERMINATED_STRING,
                    start,
                    source.length(),
                    "unterminated string literal");
        }

        private Diagnostic readCharLiteral() {
            int start = index++;
            int decodedCount = 0;
            char decoded = '\0';

            while (index < source.length()) {
                char character = source.charAt(index);
                if (character == '\'') {
                    index++;
                    if (decodedCount != 1) {
                        return error(
                                CompilerDiagnosticCodes.LEX_INVALID_CHAR_LENGTH,
                                start,
                                index,
                                "a character literal must contain exactly one UTF-16 code unit");
                    }
                    add(TokenKind.CHAR_LITERAL, start, index, new LiteralValue.CharLiteral(decoded));
                    return null;
                }
                if (character == '\\') {
                    EscapeResult escape = decodeEscape(index);
                    if (escape.error() != null) {
                        return escape.error();
                    }
                    if (decodedCount == 0) {
                        decoded = escape.value();
                    }
                    decodedCount++;
                    index = escape.nextIndex();
                    continue;
                }
                if (isLineBreak(character)) {
                    return error(
                            CompilerDiagnosticCodes.LEX_UNTERMINATED_CHAR,
                            start,
                            index,
                            "character literals may not contain an unescaped line break");
                }
                if (Character.isISOControl(character)) {
                    index++;
                    return error(
                            CompilerDiagnosticCodes.LEX_INVALID_LITERAL,
                            index - 1,
                            index,
                            "control characters in characters must use an allowed escape");
                }
                if (decodedCount == 0) {
                    decoded = character;
                }
                decodedCount++;
                index++;
            }

            return error(
                    CompilerDiagnosticCodes.LEX_UNTERMINATED_CHAR,
                    start,
                    source.length(),
                    "unterminated character literal");
        }

        private EscapeResult decodeEscape(int slashStart) {
            int escapeIndex = slashStart + 1;
            if (escapeIndex >= source.length()) {
                return EscapeResult.error(error(
                        CompilerDiagnosticCodes.LEX_INVALID_ESCAPE,
                        slashStart,
                        source.length(),
                        "incomplete escape sequence"));
            }

            char escaped = source.charAt(escapeIndex);
            switch (escaped) {
                case '\\' -> {
                    return EscapeResult.value('\\', escapeIndex + 1);
                }
                case '"' -> {
                    return EscapeResult.value('"', escapeIndex + 1);
                }
                case '\'' -> {
                    return EscapeResult.value('\'', escapeIndex + 1);
                }
                case 'n' -> {
                    return EscapeResult.value('\n', escapeIndex + 1);
                }
                case 'r' -> {
                    return EscapeResult.value('\r', escapeIndex + 1);
                }
                case 't' -> {
                    return EscapeResult.value('\t', escapeIndex + 1);
                }
                case 'b' -> {
                    return EscapeResult.value('\b', escapeIndex + 1);
                }
                case 'f' -> {
                    return EscapeResult.value('\f', escapeIndex + 1);
                }
                case '0' -> {
                    return EscapeResult.value('\0', escapeIndex + 1);
                }
                case 'u' -> {
                    int hexStart = escapeIndex + 1;
                    int hexEnd = hexStart + 4;
                    if (hexEnd > source.length()) {
                        return EscapeResult.error(error(
                                CompilerDiagnosticCodes.LEX_INVALID_ESCAPE,
                                slashStart,
                                source.length(),
                                "Unicode escapes require exactly four hexadecimal digits"));
                    }
                    for (int position = hexStart; position < hexEnd; position++) {
                        if (!isHexDigit(source.charAt(position))) {
                            return EscapeResult.error(error(
                                    CompilerDiagnosticCodes.LEX_INVALID_ESCAPE,
                                    slashStart,
                                    position + 1,
                                    "Unicode escapes require exactly four hexadecimal digits"));
                        }
                    }
                    if (hexEnd < source.length() && isHexDigit(source.charAt(hexEnd))) {
                        int end = hexEnd + 1;
                        while (end < source.length() && isHexDigit(source.charAt(end))) {
                            end++;
                        }
                        return EscapeResult.error(error(
                                CompilerDiagnosticCodes.LEX_INVALID_ESCAPE,
                                slashStart,
                                end,
                                "Unicode escapes require exactly four hexadecimal digits"));
                    }
                    int codeUnit = Integer.parseInt(source.substring(hexStart, hexEnd), 16);
                    return EscapeResult.value((char) codeUnit, hexEnd);
                }
                default -> {
                    return EscapeResult.error(error(
                            CompilerDiagnosticCodes.LEX_INVALID_ESCAPE,
                            slashStart,
                            escapeIndex + 1,
                            "unsupported escape sequence \\" + escaped));
                }
            }
        }

        private Diagnostic readNumberLiteral() {
            int start = index;
            while (index < source.length() && isAsciiDigit(source.charAt(index))) {
                index++;
            }

            boolean floating = false;
            if (index < source.length() && source.charAt(index) == '.' && !startsWith("..")) {
                if (index + 1 >= source.length() || !isAsciiDigit(source.charAt(index + 1))) {
                    return invalidNumber(
                            start,
                            malformedNumberEnd(index + 1),
                            "a decimal point must be followed by at least one digit");
                }
                floating = true;
                index++;
                while (index < source.length() && isAsciiDigit(source.charAt(index))) {
                    index++;
                }
            }

            if (!floating && index < source.length()
                    && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                return invalidNumber(
                        start,
                        malformedNumberEnd(index + 1),
                        "an exponent is supported only on a decimal literal with a point");
            }

            if (floating && index < source.length()
                    && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                index++;
                if (index < source.length()
                        && (source.charAt(index) == '+' || source.charAt(index) == '-')) {
                    index++;
                }
                if (index >= source.length() || !isAsciiDigit(source.charAt(index))) {
                    return invalidNumber(
                            start,
                            malformedNumberEnd(index),
                            "an exponent must contain at least one digit");
                }
                while (index < source.length() && isAsciiDigit(source.charAt(index))) {
                    index++;
                }
            }

            int suffixStart = index;
            NumericSuffix suffix = suffixAt(index);
            if (suffix != NumericSuffix.NONE) {
                index += suffix.spelling().length();
            }

            if (index < source.length()
                    && (isIdentifierPart(source.charAt(index)) || source.charAt(index) == '?')) {
                return invalidNumber(
                        start,
                        malformedNumberEnd(index),
                        "unsupported or malformed numeric suffix");
            }
            if (index < source.length() && source.charAt(index) == '.' && !startsWith("..")) {
                return invalidNumber(
                        start,
                        malformedNumberEnd(index + 1),
                        "a numeric literal cannot contain more than one decimal point");
            }

            String core = source.substring(start, suffixStart);
            if (floating) {
                if (suffix.isInteger()) {
                    return invalidNumber(
                            start,
                            index,
                            "integer suffixes cannot be applied to decimal literals");
                }
                return finishFloat(start, index, core, suffix);
            }

            if (suffix.isFloating()) {
                return invalidNumber(
                        start,
                        index,
                        "floating suffixes require a decimal point");
            }
            return finishInteger(start, index, core, suffix);
        }

        private Diagnostic finishInteger(int start, int end, String core, NumericSuffix suffix) {
            final BigInteger value;
            try {
                value = new BigInteger(core);
            } catch (NumberFormatException exception) {
                return invalidNumber(start, end, "malformed decimal integer literal");
            }

            BigInteger maximum;
            if (suffix == NumericSuffix.NONE) {
                maximum = UNSIGNED_64_MAX;
            } else if (suffix.isUnsignedInteger()) {
                maximum = BigInteger.ONE.shiftLeft(suffix.bitWidth()).subtract(BigInteger.ONE);
            } else {
                maximum = BigInteger.ONE.shiftLeft(suffix.bitWidth() - 1).subtract(BigInteger.ONE);
                BigInteger negativeMinimumMagnitude = BigInteger.ONE.shiftLeft(suffix.bitWidth() - 1);
                if (previousMinusWasUnary && value.equals(negativeMinimumMagnitude)) {
                    maximum = negativeMinimumMagnitude;
                }
            }
            if (value.compareTo(maximum) > 0) {
                return error(
                        CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE,
                        start,
                        end,
                        "integer literal is outside the representable "
                                + (suffix == NumericSuffix.NONE ? "U64" : suffix.spelling())
                                + " range");
            }

            add(TokenKind.INTEGER_LITERAL, start, end, new LiteralValue.IntegerLiteral(value, suffix));
            return null;
        }

        private Diagnostic finishFloat(int start, int end, String core, NumericSuffix suffix) {
            // A zero significand is exactly representable for every finite exponent, even when
            // the decimal scale is outside BigDecimal's implementation range.
            if (hasZeroSignificand(core)) {
                add(TokenKind.FLOAT_LITERAL, start, end,
                        new LiteralValue.FloatLiteral(BigDecimal.ZERO, suffix));
                return null;
            }

            final BigDecimal value;
            try {
                value = new BigDecimal(core);
            } catch (NumberFormatException exception) {
                // readNumberLiteral has already validated the complete decimal grammar. At this
                // point BigDecimal can fail only because its scale/exponent cannot represent the
                // syntactically valid exact value.
                return numericOutOfRange(start, end, suffix);
            }

            if (suffix == NumericSuffix.F32) {
                float rounded = Float.parseFloat(core);
                if (Float.isInfinite(rounded) || (rounded == 0.0f && value.signum() != 0)) {
                    return numericOutOfRange(start, end, suffix);
                }
            } else {
                double rounded = Double.parseDouble(core);
                if (Double.isInfinite(rounded) || (rounded == 0.0d && value.signum() != 0)) {
                    return numericOutOfRange(start, end, suffix);
                }
            }

            add(TokenKind.FLOAT_LITERAL, start, end, new LiteralValue.FloatLiteral(value, suffix));
            return null;
        }

        private NumericSuffix suffixAt(int at) {
            for (NumericSuffix suffix : NumericSuffix.values()) {
                if (suffix != NumericSuffix.NONE
                        && source.startsWith(suffix.spelling(), at)) {
                    return suffix;
                }
            }
            return NumericSuffix.NONE;
        }

        private void readColonOperator() {
            int start = index;
            if (startsWith(":=")) {
                index += 2;
                add(TokenKind.COLON_EQUAL, start, index, TokenValue.None.INSTANCE);
            } else if (startsWith(":.")) {
                index += 2;
                add(TokenKind.COLON_DOT, start, index, TokenValue.None.INSTANCE);
            } else if (startsWith("::")) {
                index += 2;
                add(TokenKind.DOUBLE_COLON, start, index, TokenValue.None.INSTANCE);
            } else {
                single(TokenKind.COLON);
            }
        }

        private void readPlusOperator() {
            int start = index;
            if (startsWith("++")) {
                index += 2;
                add(TokenKind.INCREMENT, start, index, TokenValue.None.INSTANCE);
            } else {
                single(TokenKind.PLUS);
            }
        }

        private void readMinusOperator() {
            int start = index;
            if (startsWith("--")) {
                index += 2;
                add(TokenKind.DECREMENT, start, index, TokenValue.None.INSTANCE);
            } else if (startsWith("->")) {
                index += 2;
                add(TokenKind.ARROW, start, index, TokenValue.None.INSTANCE);
            } else {
                boolean unary = canStartUnaryOperandAfter(previousKind);
                index++;
                add(TokenKind.MINUS, start, index, TokenValue.None.INSTANCE);
                previousMinusWasUnary = unary;
            }
        }

        private void readLessOperator() {
            int start = index;
            if (startsWith("<=")) {
                index += 2;
                add(TokenKind.LESS_EQUAL, start, index, TokenValue.None.INSTANCE);
            } else {
                if (previousTokenStartsTypeArguments()) {
                    typeArgumentDepth++;
                }
                single(TokenKind.LESS);
            }
        }

        private void readGreaterOperator() {
            int start = index;
            if (typeArgumentDepth > 0) {
                typeArgumentDepth--;
                index++;
                add(TokenKind.GREATER, start, index, TokenValue.None.INSTANCE);
                if (index < source.length() && source.charAt(index) == '=') {
                    int equalStart = index++;
                    add(TokenKind.EQUAL, equalStart, index, TokenValue.None.INSTANCE);
                }
            } else if (startsWith(">=")) {
                index += 2;
                add(TokenKind.GREATER_EQUAL, start, index, TokenValue.None.INSTANCE);
            } else {
                single(TokenKind.GREATER);
            }
        }

        private void readEqualOperator() {
            int start = index;
            if (startsWith("==")) {
                index += 2;
                add(TokenKind.EQUAL_EQUAL, start, index, TokenValue.None.INSTANCE);
            } else if (startsWith("=>")) {
                index += 2;
                add(TokenKind.LAMBDA_ARROW, start, index, TokenValue.None.INSTANCE);
            } else {
                single(TokenKind.EQUAL);
            }
        }

        private void single(TokenKind kind) {
            int start = index++;
            add(kind, start, index, TokenValue.None.INSTANCE);
        }

        private void add(TokenKind kind, int start, int end, TokenValue value) {
            tokens.add(new Token(
                    kind,
                    source.substring(start, end),
                    span(start, end),
                    pendingTrivia,
                    value));
            pendingTrivia.clear();
            previousKind = kind;
            previousMinusWasUnary = false;
        }

        private Diagnostic numericOutOfRange(int start, int end, NumericSuffix suffix) {
            String type = suffix == NumericSuffix.F32 ? "F32" : "F64";
            return error(
                    CompilerDiagnosticCodes.LEX_NUMERIC_OUT_OF_RANGE,
                    start,
                    end,
                    "decimal literal is outside the " + type + " range");
        }

        private Diagnostic invalidOperator(int start, int end, String message) {
            return error(CompilerDiagnosticCodes.LEX_INVALID_OPERATOR, start, end, message);
        }

        private Diagnostic invalidNumber(int start, int end, String message) {
            return error(CompilerDiagnosticCodes.LEX_INVALID_NUMBER, start, end, message);
        }

        private Diagnostic error(
                io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code,
                int start,
                int end,
                String message) {
            int safeStart = Math.max(0, Math.min(start, source.length()));
            int safeEnd = Math.max(safeStart, Math.min(end, source.length()));
            return Diagnostic.error(code, span(safeStart, safeEnd), message);
        }

        private SourceSpan span(int start, int end) {
            return SourceSpan.of(snapshot.sourceId(), start, end);
        }

        private boolean startsWith(String value) {
            return source.startsWith(value, index);
        }

        private boolean hasTokenBoundary(int after) {
            return after >= source.length()
                    || (!isIdentifierPart(source.charAt(after)) && source.charAt(after) != '?');
        }

        private int malformedOperatorEnd(int from) {
            int end = from;
            while (end < source.length()
                    && (isIdentifierPart(source.charAt(end)) || source.charAt(end) == '?')) {
                end++;
            }
            return Math.max(from, end);
        }

        private int malformedLiteralEnd(int from) {
            int end = from;
            while (end < source.length()
                    && (isIdentifierPart(source.charAt(end)) || source.charAt(end) == '?')) {
                end++;
            }
            return Math.max(from, end);
        }

        private int malformedNumberEnd(int from) {
            int end = Math.max(index, from);
            while (end < source.length()
                    && (isIdentifierPart(source.charAt(end))
                    || source.charAt(end) == '.'
                    || source.charAt(end) == '+'
                    || source.charAt(end) == '-'
                    || source.charAt(end) == '?')) {
                end++;
            }
            return Math.max(end, Math.min(source.length(), Math.max(index, from)));
        }

        private static boolean hasZeroSignificand(String core) {
            int exponent = Math.max(core.indexOf('e'), core.indexOf('E'));
            String mantissa = exponent >= 0 ? core.substring(0, exponent) : core;
            boolean digitSeen = false;
            for (int position = 0; position < mantissa.length(); position++) {
                char character = mantissa.charAt(position);
                if (character == '.') {
                    continue;
                }
                if (character < '0' || character > '9') {
                    return false;
                }
                digitSeen = true;
                if (character != '0') {
                    return false;
                }
            }
            return digitSeen;
        }

        private boolean previousTokenStartsTypeArguments() {
            if (tokens.isEmpty()) {
                return false;
            }
            Token previous = tokens.getLast();
            return previous.kind() == TokenKind.TYPE_NAME
                    && (previous.lexeme().equals("Array")
                    || previous.lexeme().equals("Range")
                    || previous.lexeme().equals("Tuple")
                    || previous.lexeme().equals("Fn"));
        }

        private static boolean canStartUnaryOperandAfter(TokenKind kind) {
            if (kind == null) {
                return true;
            }
            return switch (kind) {
                case IDENTIFIER, TYPE_NAME,
                        BOOLEAN_LITERAL, NIL_LITERAL, INTEGER_LITERAL, FLOAT_LITERAL,
                        STRING_LITERAL, CHAR_LITERAL,
                        RIGHT_PAREN, RIGHT_BRACE, RIGHT_BRACKET -> false;
                default -> true;
            };
        }

        private static boolean isWhitespace(char character) {
            return Character.isWhitespace(character) || Character.isSpaceChar(character);
        }

        private static boolean isLineBreak(char character) {
            return character == '\r' || character == '\n';
        }

        private static boolean isIdentifierStart(char character) {
            return (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || character == '_';
        }

        private static boolean isIdentifierPart(char character) {
            return isIdentifierStart(character) || isAsciiDigit(character);
        }

        private static boolean isAsciiDigit(char character) {
            return character >= '0' && character <= '9';
        }

        private static boolean isHexDigit(char character) {
            return (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F');
        }

        private record EscapeResult(char value, int nextIndex, Diagnostic error) {
            private static EscapeResult value(char value, int nextIndex) {
                return new EscapeResult(value, nextIndex, null);
            }

            private static EscapeResult error(Diagnostic error) {
                return new EscapeResult('\0', -1, Objects.requireNonNull(error, "error"));
            }
        }
    }
}
