package io.mindspice.lyra.repl.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small strict JSON value/parser used instead of reflection or a generic RPC codec. */
final class StrictJson {
    private StrictJson() {
    }

    sealed interface Value permits ObjectValue, ArrayValue, StringValue, NumberValue,
            BooleanValue, NullValue {
    }

    record ObjectValue(Map<String, Value> members) implements Value {
        ObjectValue {
            Objects.requireNonNull(members, "members");
            LinkedHashMap<String, Value> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Value> entry : members.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("JSON objects must not contain null entries");
                }
                copy.put(entry.getKey(), entry.getValue());
            }
            members = Collections.unmodifiableMap(copy);
        }
    }

    record ArrayValue(List<Value> values) implements Value {
        ArrayValue {
            Objects.requireNonNull(values, "values");
            values = List.copyOf(values);
        }
    }

    record StringValue(String value) implements Value {
        StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record NumberValue(String value) implements Value {
        NumberValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record BooleanValue(boolean value) implements Value {
    }

    record NullValue() implements Value {
    }

    static Value parse(String text) throws ProtocolException {
        Objects.requireNonNull(text, "text");
        Parser parser = new Parser(text);
        Value value = parser.value(0);
        parser.whitespace();
        if (!parser.atEnd()) {
            throw parser.error("trailing JSON data");
        }
        return value;
    }

    static String write(Value value) {
        Objects.requireNonNull(value, "value");
        StringBuilder output = new StringBuilder();
        write(value, output, 0);
        return output.toString();
    }

    private static void write(Value value, StringBuilder output, int depth) {
        if (depth > RemoteProtocol.MAX_JSON_DEPTH) {
            throw new IllegalArgumentException("JSON value exceeds maximum depth");
        }
        switch (value) {
            case ObjectValue object -> {
                output.append('{');
                boolean first = true;
                for (Map.Entry<String, Value> entry : object.members().entrySet()) {
                    if (!first) {
                        output.append(',');
                    }
                    first = false;
                    writeString(entry.getKey(), output);
                    output.append(':');
                    write(entry.getValue(), output, depth + 1);
                }
                output.append('}');
            }
            case ArrayValue array -> {
                output.append('[');
                for (int index = 0; index < array.values().size(); index++) {
                    if (index > 0) {
                        output.append(',');
                    }
                    write(array.values().get(index), output, depth + 1);
                }
                output.append(']');
            }
            case StringValue string -> writeString(string.value(), output);
            case NumberValue number -> output.append(number.value());
            case BooleanValue bool -> output.append(bool.value() ? "true" : "false");
            case NullValue ignored -> output.append("null");
        }
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20 || character == 0x7f
                            || Character.isSurrogate(character)) {
                        output.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private Value value(int depth) throws ProtocolException {
            if (depth > RemoteProtocol.MAX_JSON_DEPTH) {
                throw error("JSON nesting exceeds maximum depth");
            }
            whitespace();
            if (atEnd()) {
                throw error("expected JSON value");
            }
            return switch (text.charAt(index)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> new StringValue(string());
                case 't' -> literal("true", new BooleanValue(true));
                case 'f' -> literal("false", new BooleanValue(false));
                case 'n' -> literal("null", new NullValue());
                default -> number();
            };
        }

        private ObjectValue object(int depth) throws ProtocolException {
            index++;
            whitespace();
            LinkedHashMap<String, Value> members = new LinkedHashMap<>();
            if (consume('}')) {
                return new ObjectValue(members);
            }
            while (true) {
                if (members.size() >= RemoteProtocol.MAX_JSON_MEMBERS) {
                    throw error("JSON object has too many members");
                }
                whitespace();
                if (atEnd() || text.charAt(index) != '"') {
                    throw error("JSON object key must be a string");
                }
                String key = string();
                if (members.containsKey(key)) {
                    throw error("duplicate JSON object key");
                }
                whitespace();
                if (!consume(':')) {
                    throw error("JSON object key must be followed by ':'");
                }
                Value value = value(depth);
                members.put(key, value);
                whitespace();
                if (consume('}')) {
                    return new ObjectValue(members);
                }
                if (!consume(',')) {
                    throw error("JSON object member must be followed by ',' or '}'");
                }
            }
        }

        private ArrayValue array(int depth) throws ProtocolException {
            index++;
            whitespace();
            ArrayList<Value> values = new ArrayList<>();
            if (consume(']')) {
                return new ArrayValue(values);
            }
            while (true) {
                if (values.size() >= RemoteProtocol.MAX_JSON_ARRAY_ITEMS) {
                    throw error("JSON array has too many items");
                }
                values.add(value(depth));
                whitespace();
                if (consume(']')) {
                    return new ArrayValue(values);
                }
                if (!consume(',')) {
                    throw error("JSON array item must be followed by ',' or ']'");
                }
            }
        }

        private Value literal(String literal, Value value) throws ProtocolException {
            if (!text.startsWith(literal, index)) {
                throw error("invalid JSON literal");
            }
            index += literal.length();
            return value;
        }

        private Value number() throws ProtocolException {
            int start = index;
            if (consume('-')) {
                if (atEnd()) {
                    throw error("invalid JSON number");
                }
            }
            if (consume('0')) {
                if (!atEnd() && isDigit(text.charAt(index))) {
                    throw error("JSON numbers must not have leading zeroes");
                }
            } else {
                if (atEnd() || !isDigitOneToNine(text.charAt(index))) {
                    throw error("invalid JSON number");
                }
                while (!atEnd() && isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (consume('.')) {
                if (atEnd() || !isDigit(text.charAt(index))) {
                    throw error("invalid JSON fraction");
                }
                while (!atEnd() && isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (!atEnd() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (!atEnd() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                if (atEnd() || !isDigit(text.charAt(index))) {
                    throw error("invalid JSON exponent");
                }
                while (!atEnd() && isDigit(text.charAt(index))) {
                    index++;
                }
            }
            return new NumberValue(text.substring(start, index));
        }

        private String string() throws ProtocolException {
            if (!consume('"')) {
                throw error("expected JSON string");
            }
            StringBuilder value = new StringBuilder();
            while (!atEnd()) {
                char character = text.charAt(index++);
                if (character == '"') {
                    if (value.length() > RemoteProtocol.MAX_JSON_STRING_CHARS) {
                        throw error("JSON string exceeds maximum length");
                    }
                    return value.toString();
                }
                if (character < 0x20) {
                    throw error("JSON string contains an unescaped control character");
                }
                if (character != '\\') {
                    value.append(character);
                } else {
                    if (atEnd()) {
                        throw error("truncated JSON escape");
                    }
                    char escape = text.charAt(index++);
                    switch (escape) {
                        case '"' -> value.append('"');
                        case '\\' -> value.append('\\');
                        case '/' -> value.append('/');
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append(unicodeEscape());
                        default -> throw error("invalid JSON escape");
                    }
                }
                if (value.length() > RemoteProtocol.MAX_JSON_STRING_CHARS) {
                    throw error("JSON string exceeds maximum length");
                }
            }
            throw error("unterminated JSON string");
        }

        private char unicodeEscape() throws ProtocolException {
            if (index + 4 > text.length()) {
                throw error("truncated JSON unicode escape");
            }
            int codePoint = 0;
            for (int count = 0; count < 4; count++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) {
                    throw error("invalid JSON unicode escape");
                }
                codePoint = (codePoint << 4) | digit;
            }
            return (char) codePoint;
        }

        private void whitespace() {
            while (!atEnd()) {
                char character = text.charAt(index);
                if (character == ' ' || character == '\t'
                        || character == '\n' || character == '\r') {
                    index++;
                } else {
                    return;
                }
            }
        }

        private boolean consume(char expected) {
            if (!atEnd() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private boolean atEnd() {
            return index >= text.length();
        }

        private ProtocolException error(String message) {
            return new ProtocolException(ProtocolException.Reason.MALFORMED_JSON,
                    message + " at JSON offset " + index);
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private static boolean isDigitOneToNine(char value) {
            return value >= '1' && value <= '9';
        }
    }
}
