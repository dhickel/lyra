package parse;

import lang.ast.ASTNode;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;

public sealed interface TokenType {

    TokenType LEFT_PAREN = TokenType.Syntactic.LeftParen;
    TokenType RIGHT_PAREN = TokenType.Syntactic.RightParen;

    TokenType LEFT_BRACE = TokenType.Syntactic.LeftBrace;
    TokenType RIGHT_BRACE = TokenType.Syntactic.RightBrace;

    TokenType LEFT_BRACKET = TokenType.Syntactic.LeftBracket;
    TokenType RIGHT_BRACKET = TokenType.Syntactic.RightBracket;

    TokenType LEFT_ANGLE_BRACKET = TokenType.Operation.Less;
    TokenType RIGHT_ANGLE_BRACKET = TokenType.Operation.Greater;

    TokenType FN = TokenType.BuiltIn.Fn;
    TokenType LAMBDA_ARROW = TokenType.BuiltIn.Lambda;

    TokenType REASSIGNMENT = TokenType.Syntactic.ColonEqual;
    TokenType IDENTIFIER = TokenType.Literal.Identifier;
    TokenType NAME_SPACE_ACCESS = TokenType.Syntactic.Arrow;
    TokenType FUNCTION_ACCESS = Syntactic.DoubleColon;
    TokenType IDENTIFIER_ACCESS = TokenType.Syntactic.ColonDot;
    TokenType RIGHT_ARROW = TokenType.Syntactic.Arrow;
    TokenType BAR = TokenType.Syntactic.Bar;
    TokenType IMPORT = TokenType.Definition.Import;
    TokenType AS = TokenType.Syntactic.As;

    enum Internal implements TokenType {
        EOF("EOF");

        public final String stringValue;

        Internal(String stringValue) { this.stringValue = stringValue; }

        @Override
        public String asString() {
            return stringValue;
        }
    }


    enum Syntactic implements TokenType {
        LeftParen("("),
        RightParen(")"),
        LeftBrace("{"),
        RightBrace("}"),
        LeftBracket("["),
        RightBracket("]"),
        Comma(","),
        Backslash("\\"),
        SingleQuote("'"),
        DoubleQuote("\""),
        Period("."),
        Ampersand("&"),
        Grave("`"),
        Colon(":"),
        SemiColon(";"),
        //Pound("#"),
        Cache("$"),
        At("@"),
        Bar("|"),
        Tilde("~"),
        Equal("="),
        DoubleColon("::"),
        ColonDot(":."),
        ColonEqual(":="),
        Arrow("->"),
        As("as");

        public final String stringValue;

        Syntactic(String stringValue) { this.stringValue = stringValue; }

        @Override
        public String asString() {
            return stringValue;
        }
    }


    enum Operation implements TokenType {
        Plus("+", ASTNode.Operation.Plus),
        Minus("-", ASTNode.Operation.Minus),
        Asterisk("*", ASTNode.Operation.Asterisk),
        Slash("/", ASTNode.Operation.Slash),
        Caret("^", ASTNode.Operation.Caret),
        Percent("%", ASTNode.Operation.Percent),
        Greater(">", ASTNode.Operation.Greater),
        Less("<", ASTNode.Operation.Less),
        PlusPlus("++", ASTNode.Operation.PlusPlus),
        MinusMinus("--", ASTNode.Operation.MinusMinus),
        GreaterEqual(">=", ASTNode.Operation.GreaterEqual),
        LessEqual("<=", ASTNode.Operation.LessEqual),
        BangEqual("!=", ASTNode.Operation.BangEqual),
        EqualEqual("==", ASTNode.Operation.EqualEqual),
        And("and", ASTNode.Operation.And),
        Or("or", ASTNode.Operation.Or),
        Nor("nor", ASTNode.Operation.Nor),
        xor("xor", ASTNode.Operation.Xor),
        xnor("xnor", ASTNode.Operation.Xnor),
        Nand("nand", ASTNode.Operation.Nand),
        Not("not", ASTNode.Operation.Negate);

        public final String stringValue;
        public final ASTNode.Operation astOpValue;


        Operation(String stringValue, ASTNode.Operation astOpValue) {
            this.stringValue = stringValue;
            this.astOpValue = astOpValue;
        }

        @Override
        public String asString() {
            return stringValue;
        }
    }

    enum Literal implements TokenType {
        True("#T"),
        False("#F"),
        Float(null),
        Integer(null),
        Identifier(null),
        String(null),
        Nil("#NIL");

        public final String stringValue;

        Literal(String stringValue) { this.stringValue = stringValue; }

        @Override
        public String asString() {
            return stringValue;
        }
    }

    enum Definition implements TokenType {
        Let("let"),
        Func("func"),
        Class("class"),
        Struct("struct"),
        Import("import");

        public final String stringValue;

        Definition(String stringValue) { this.stringValue = stringValue; }

        @Override
        public String asString() {
            return stringValue;
        }
    }

    enum BuiltIn implements TokenType {
        Match(null),
        Array("Array"),
        Fn("Fn"),
        Lambda("=>");


        public final String stringValue;

        BuiltIn(String stringValue) { this.stringValue = stringValue; }

        @Override
        public String asString() {
            return stringValue;
        }
    }


    enum Modifier implements TokenType {
        Mutable("@mut", ASTNode.Modifier.MUTABLE),
        Public("@pub", ASTNode.Modifier.PUBLIC),
        Constant("@const", ASTNode.Modifier.CONST),
        Optional("@opt", ASTNode.Modifier.OPTIONAL);

        public final String stringValue;
        public final ASTNode.Modifier astModValue;

        Modifier(String stringValue, ASTNode.Modifier astModValue) {
            this.stringValue = stringValue;
            this.astModValue = astModValue;
        }


        @Override
        public String asString() {
            return stringValue;
        }
    }


    // Utility

    String asString();

    record SingleToken(char chr, TokenType tokenType) { }

    record DoubleToken(char chr1, char chr2, TokenType tokenType) {
        public boolean matches(char c1, char c2) {
            return chr1 == c1 && chr2 == c2;
        }

    }

    record KeyWordToken(String keyword, TokenType tokenType) { }

    record ModifierToken(String modifierLexeme, TokenType tokenType) { }

    private static Stream<TokenType> getAllStream() {
        return Stream.of(
                Arrays.stream(Syntactic.values()),
                Arrays.stream(Operation.values()),
                Arrays.stream(Literal.values()),
                Arrays.stream(Definition.values()),
                Arrays.stream(Modifier.values()),
                Arrays.stream(BuiltIn.values())
        ).flatMap(Function.identity());

    }

    static SingleToken[] getSingleTokens() {
        return getAllStream().filter(t -> t.asString() != null && t.asString().length() == 1)
                .map(t -> new SingleToken(t.asString().charAt(0), t))
                .toArray(SingleToken[]::new);
    }

    static DoubleToken[] getDoubleTokens() {
        return getAllStream()
                .filter(t -> t.asString() != null && t.asString().length() == 2)
                .map(t -> new DoubleToken(t.asString().charAt(0), t.asString().charAt(1), t))
                .toArray(DoubleToken[]::new);
    }

    static KeyWordToken[] getKeyWordTokens() {
        return getAllStream()
                .filter(t -> t.asString() != null && t.asString().length() > 2)
                .map(t -> new KeyWordToken(t.asString(), t))
                .toArray(KeyWordToken[]::new);

    }

    static ModifierToken[] getModifierTokens() {
        return Arrays.stream(Modifier.values())
                .map(t -> new ModifierToken(t.asString(), t))
                .toArray(ModifierToken[]::new);
    }


}
