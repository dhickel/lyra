package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Internal canonical JSON string escaping used by the runtime metadata model. */
final class CanonicalJson {
    private CanonicalJson() {
    }

    static String requireUtf8(String value, String field) {
        Objects.requireNonNull(value, field);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(field + " contains an unpaired surrogate");
            }
        }
        return value;
    }

    static String quote(String value) {
        requireUtf8(value, "value");
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append("\\u");
                        String hex = Integer.toHexString(character);
                        result.append("0".repeat(4 - hex.length())).append(hex);
                    } else if (Character.isHighSurrogate(character)) {
                        if (index + 1 >= value.length()
                                || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new IllegalArgumentException("JSON value contains an unpaired surrogate");
                        }
                        result.append(character).append(value.charAt(++index));
                    } else if (Character.isLowSurrogate(character)) {
                        throw new IllegalArgumentException("JSON value contains an unpaired surrogate");
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    static void fieldName(StringBuilder result, String name) {
        result.append(quote(name)).append(':');
    }

    static void stringField(StringBuilder result, String name, String value) {
        fieldName(result, name);
        result.append(quote(value));
    }

    static void comma(StringBuilder result, boolean[] first) {
        if (!first[0]) {
            result.append(',');
        }
        first[0] = false;
    }
}
