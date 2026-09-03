package io.mindspice.lyra.runtime;

import java.util.EnumSet;
import java.util.Objects;

/** Small canonical-spelling parser kept private to the runtime type model. */
final class LyraTypeParser {
    private final String input;
    private int index;

    private LyraTypeParser(String input) {
        this.input = Objects.requireNonNull(input, "canonicalSpelling");
        for (int offset = 0; offset < input.length(); offset++) {
            if (Character.isWhitespace(input.charAt(offset))) {
                throw error("whitespace is not permitted in a canonical type");
            }
        }
    }

    static LyraType parse(String input) {
        LyraTypeParser parser = new LyraTypeParser(input);
        LyraType result = parser.type();
        if (!parser.atEnd()) {
            throw parser.error("unexpected trailing type text");
        }
        if (!result.canonicalSpelling().equals(input)) {
            throw parser.error("type is not in canonical spelling");
        }
        return result;
    }

    private LyraType type() {
        EnumSet<TypeQualifier> qualifiers = EnumSet.noneOf(TypeQualifier.class);
        while (peek('@')) {
            TypeQualifier qualifier = qualifier();
            if (!qualifiers.add(qualifier)) {
                throw error("duplicate type qualifier: " + qualifier.spelling());
            }
        }

        LyraType base;
        if (consumeWord("Array")) {
            expect('<');
            base = ArrayType.of(type());
            expect('>');
        } else if (consumeWord("Tuple")) {
            expect('<');
            if (peek('>')) {
                throw error("Tuple must contain at least one member type");
            }
            java.util.ArrayList<LyraType> members = new java.util.ArrayList<>();
            members.add(type());
            while (consume(',')) {
                members.add(type());
            }
            expect('>');
            base = TupleType.of(members);
        } else if (consumeWord("Fn")) {
            expect('<');
            java.util.ArrayList<LyraType> parameters = new java.util.ArrayList<>();
            if (!peek(';')) {
                parameters.add(type());
                while (consume(',')) {
                    parameters.add(type());
                }
            }
            expect(';');
            base = FunctionType.of(parameters, type());
            expect('>');
        } else {
            String word = identifier();
            base = PrimitiveType.fromSpelling(word)
                    .orElseThrow(() -> error("unknown Lyra type: " + word));
        }
        return qualifiers.isEmpty() ? base : QualifiedType.of(base, qualifiers);
    }

    private TypeQualifier qualifier() {
        if (consumeWord("@mut")) {
            return TypeQualifier.MUT;
        }
        if (consumeWord("@nil")) {
            return TypeQualifier.NIL;
        }
        throw error("unknown type qualifier");
    }

    private String identifier() {
        int start = index;
        while (!atEnd() && Character.isLetterOrDigit(input.charAt(index))) {
            index++;
        }
        if (start == index) {
            throw error("expected a Lyra type");
        }
        return input.substring(start, index);
    }

    private boolean consumeWord(String word) {
        if (input.startsWith(word, index)) {
            index += word.length();
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("expected '" + expected + "'");
        }
    }

    private boolean consume(char expected) {
        if (!atEnd() && input.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private boolean peek(char expected) {
        return !atEnd() && input.charAt(index) == expected;
    }

    private boolean atEnd() {
        return index == input.length();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + index + " in " + input);
    }
}
