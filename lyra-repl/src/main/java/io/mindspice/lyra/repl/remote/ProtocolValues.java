package io.mindspice.lyra.repl.remote;

import java.net.URI;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Package-private bounded schema helpers. */
final class ProtocolValues {
    private ProtocolValues() {
    }

    static String text(String value, String field, int maxCharacters) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxCharacters) {
            throw new IllegalArgumentException(field + " exceeds maximum length");
        }
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
            if (Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t') {
                throw new IllegalArgumentException(field + " contains a control character");
            }
        }
        return value;
    }

    static String scalarText(String value, String field, int maxCharacters) {
        Objects.requireNonNull(value, field);
        if (value.length() > maxCharacters) {
            throw new IllegalArgumentException(field + " exceeds maximum length");
        }
        return value;
    }

    static String token(String value, String field, int maxCharacters) {
        String result = text(value, field, maxCharacters);
        if (result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0 || result.indexOf('\t') >= 0) {
            throw new IllegalArgumentException(field + " must not contain whitespace controls");
        }
        return result;
    }

    static long nonNegativeLong(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    static long positiveLong(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    static UUID uuid(String value, String field) {
        token(value, field, 64);
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException(field + " is not canonically formatted");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " is not a UUID", exception);
        }
    }

    static String uuid(UUID value, String field) {
        return Objects.requireNonNull(value, field).toString();
    }

    static URI uri(String value) {
        token(value, "source URI", 4096);
        URI uri;
        try {
            uri = URI.create(value).normalize();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("source URI is invalid", exception);
        }
        if (!uri.isAbsolute() || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("source URI must be absolute and fragment-free");
        }
        return uri;
    }

    static String base64(byte[] bytes, String field, int exactLength) {
        Objects.requireNonNull(bytes, field);
        if (bytes.length != exactLength) {
            throw new IllegalArgumentException(field + " has an invalid length");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] decodeBase64(String value, String field, int exactLength) {
        token(value, field, 4096);
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " is not valid base64", exception);
        }
        if (decoded.length != exactLength
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
            java.util.Arrays.fill(decoded, (byte) 0);
            throw new IllegalArgumentException(field + " has an invalid encoding");
        }
        return decoded;
    }

    static String optionalText(java.util.Optional<String> value, String field, int max) {
        Objects.requireNonNull(value, field);
        return value.map(item -> text(item, field, max)).orElse(null);
    }
}
