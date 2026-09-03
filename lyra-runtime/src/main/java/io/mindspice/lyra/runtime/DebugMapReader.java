package io.mindspice.lyra.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict canonical reader for {@code META-INF/lyra/debug-map.json}. */
public final class DebugMapReader {
    public static final String RESOURCE_PATH = LyraRuntimeConstants.DEBUG_MAP_PATH;

    private DebugMapReader() {
    }

    public static DebugMapMetadata read(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw compat("debug map is not valid UTF-8", exception);
        }
        if (json.startsWith("\ufeff")) throw compat("debug map must not contain a UTF-8 BOM", null);
        try {
            Map<String, Object> root = new Parser(json).objectRoot();
            keys(root, List.of("schemaVersion", "entries"), Set.of("schemaVersion", "entries"), "debug map");
            int schema = integer(root, "schemaVersion");
            if (schema != LyraRuntimeConstants.DEBUG_MAP_SCHEMA_VERSION) {
                throw compat("unsupported debug-map schema version: " + schema, null);
            }
            List<Object> rawEntries = array(root, "entries");
            ArrayList<DebugMapEntry> entries = new ArrayList<>(rawEntries.size());
            DebugMapEntry previous = null;
            for (Object raw : rawEntries) {
                Map<String, Object> object = object(raw, "debug-map entry");
                keys(object, List.of("className", "methodName", "startBci", "endBci", "moduleId",
                                "functionName", "sourceId", "sourceLabel", "startOffset", "endOffset", "synthetic", "origin"),
                        Set.of("className", "methodName", "startBci", "endBci", "moduleId",
                                "functionName", "sourceId", "startOffset", "endOffset", "synthetic"),
                        "debug-map entry");
                ModuleId module = new ModuleId(string(object, "moduleId"));
                if (!module.canonicalSpelling().equals(string(object, "moduleId"))) {
                    throw new IllegalArgumentException("debug-map module ID is not canonical");
                }
                String sourceSpelling = string(object, "sourceId");
                SourceId source = sourceIdForModule(module, sourceSpelling);
                if (!source.value().equals(sourceSpelling)) {
                    throw new IllegalArgumentException("debug-map source ID is not canonical");
                }
                java.util.Optional<SourceFrame> origin = object.containsKey("origin")
                        ? java.util.Optional.of(decodeOrigin(object.get("origin")))
                        : java.util.Optional.empty();
                java.util.Optional<String> sourceLabel = object.containsKey("sourceLabel")
                        ? java.util.Optional.of(string(object, "sourceLabel"))
                        : java.util.Optional.empty();
                SourceFrame frame = new SourceFrame(module, string(object, "functionName"),
                        new SourceSpan(source, integer(object, "startOffset"), integer(object, "endOffset")),
                        java.util.Optional.empty(), sourceLabel, bool(object, "synthetic"), origin);
                DebugMapEntry entry = new DebugMapEntry(string(object, "className"),
                        string(object, "methodName"), integer(object, "startBci"), integer(object, "endBci"), frame);
                if (previous != null && previous.compareTo(entry) >= 0) {
                    throw new IllegalArgumentException("debug-map entries are not sorted or duplicated");
                }
                previous = entry;
                entries.add(entry);
            }
            return new DebugMapMetadata(schema, entries);
        } catch (LyraCompatibilityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw compat("malformed or inconsistent debug map: " + message(exception), exception);
        }
    }

    public static DebugMapMetadata read(byte[] bytes, ArtifactMetadata artifact) {
        Objects.requireNonNull(artifact, "artifact");
        DebugMapMetadata map = read(bytes);
        if (map.schemaVersion() != artifact.debugMapVersion()) {
            throw compat("debug-map schema does not match artifact metadata", null);
        }
        String actual = map.sha256();
        if (!actual.equals(artifact.debugMapHash())) {
            throw compat("debug-map hash does not match artifact metadata", null);
        }
        return map;
    }

    public static DebugMapMetadata read(String json) {
        Objects.requireNonNull(json, "json");
        try {
            java.nio.ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(json));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return read(bytes);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw compat("debug-map JSON is not valid UTF-8", exception);
        }
    }

    public static DebugMapMetadata read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        return read(input.readAllBytes());
    }

    public static DebugMapMetadata read(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return read(Files.readAllBytes(path));
    }

    private static SourceFrame decodeOrigin(Object value) {
        Map<String, Object> object = object(value, "debug-map origin");
        keys(object, List.of("moduleId", "functionName", "sourceId", "sourceLabel", "startOffset", "endOffset"),
                Set.of("moduleId", "functionName", "sourceId", "startOffset", "endOffset"),
                "debug-map origin");
        String moduleSpelling = string(object, "moduleId");
        ModuleId module = new ModuleId(moduleSpelling);
        if (!module.canonicalSpelling().equals(moduleSpelling)) {
            throw new IllegalArgumentException("debug-map origin module ID is not canonical");
        }
        String sourceSpelling = string(object, "sourceId");
        SourceId source = sourceIdForModule(module, sourceSpelling);
        if (!source.value().equals(sourceSpelling)) {
            throw new IllegalArgumentException("debug-map origin source ID is not canonical");
        }
        java.util.Optional<String> label = object.containsKey("sourceLabel")
                ? java.util.Optional.of(string(object, "sourceLabel")) : java.util.Optional.empty();
        return new SourceFrame(module, string(object, "functionName"),
                new SourceSpan(source, integer(object, "startOffset"), integer(object, "endOffset")),
                java.util.Optional.empty(), label, false, java.util.Optional.empty());
    }

    private static SourceId sourceIdForModule(ModuleId module, String spelling) {
        try {
            return module.isPath() ? SourceId.path(spelling)
                    : SourceId.uri(URI.create(spelling));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid debug-map source ID: " + spelling,
                    exception);
        }
    }

    private static void keys(Map<String, Object> object, List<String> order, Set<String> required,
                             String context) {
        for (String requiredKey : required.stream().sorted().toList()) {
            if (!object.containsKey(requiredKey)) throw new IllegalArgumentException(
                    context + " is missing required field: " + requiredKey);
        }
        int prior = -1;
        for (String key : object.keySet()) {
            int position = order.indexOf(key);
            if (position < 0) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.contains("required") || key.startsWith("!")) {
                    throw new IllegalArgumentException(context + " contains unknown required field: " + key);
                }
                continue;
            }
            if (position < prior) throw new IllegalArgumentException(context + " fields are not canonical");
            prior = position;
        }
    }

    private static Map<String, Object> object(Object value, String context) {
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException(context + " must be an object");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException(context + " has a non-string key");
            result.put(key, entry.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof List<?>)) throw new IllegalArgumentException(name + " must be an array");
        return (List<Object>) value;
    }

    private static String string(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof String result)) throw new IllegalArgumentException(name + " must be a string");
        return result;
    }

    private static int integer(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof BigInteger result) || result.bitLength() > 31) {
            throw new IllegalArgumentException(name + " must be a 32-bit integer");
        }
        return result.intValue();
    }

    private static boolean bool(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(name + " must be boolean");
        return result;
    }

    private static LyraCompatibilityException compat(String message, Throwable cause) {
        return cause == null ? new LyraCompatibilityException(message)
                : new LyraCompatibilityException(message, List.of(), List.of(), cause);
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static final class ParseException extends RuntimeException {
        private ParseException(String message) { super(message); }
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) { this.input = input; }

        private Map<String, Object> objectRoot() {
            Object value = value();
            if (!(value instanceof Map<?, ?>)) throw error("root must be an object");
            if (index != input.length()) throw error("trailing JSON data");
            return object(value, "root");
        }

        private Object value() {
            if (index >= input.length()) throw error("unexpected end of JSON");
            return switch (input.charAt(index)) {
                case '{' -> objectValue();
                case '[' -> arrayValue();
                case '"' -> stringValue();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> objectValue() {
            index++;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            if (consume('}')) return result;
            while (true) {
                if (index >= input.length() || input.charAt(index) != '"') throw error("object key must be a string");
                String key = stringValue();
                if (result.containsKey(key)) throw error("duplicate object field: " + key);
                expect(':');
                result.put(key, value());
                if (consume('}')) return result;
                expect(',');
            }
        }

        private List<Object> arrayValue() {
            index++;
            ArrayList<Object> result = new ArrayList<>();
            if (consume(']')) return result;
            while (true) {
                result.add(value());
                if (consume(']')) return result;
                expect(',');
            }
        }

        private String stringValue() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < input.length()) {
                char c = input.charAt(index++);
                if (c == '"') return result.toString();
                if (c < 0x20) throw error("unescaped control character");
                if (c != '\\') { result.append(c); continue; }
                if (index >= input.length()) throw error("truncated escape");
                char escape = input.charAt(index++);
                switch (escape) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicode());
                    default -> throw error("invalid escape");
                }
            }
            throw error("unterminated string");
        }

        private char unicode() {
            if (index + 4 > input.length()) throw error("truncated Unicode escape");
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                char hexadecimal = input.charAt(index++);
                if (hexadecimal >= 'A' && hexadecimal <= 'F') {
                    throw error("non-minimal Unicode escape");
                }
                int digit = Character.digit(hexadecimal, 16);
                if (digit < 0) throw error("invalid Unicode escape");
                value = (value << 4) | digit;
            }
            char c = (char) value;
            if (c >= 0x20 || c == '\b' || c == '\f' || c == '\n' || c == '\r'
                    || c == '\t' || c == '"' || c == '\\') throw error("non-minimal Unicode escape");
            return c;
        }

        private Object literal(String spelling, Object value) {
            if (!input.startsWith(spelling, index)) throw error("invalid literal");
            index += spelling.length();
            return value;
        }

        private Object number() {
            int start = index;
            if (consume('-') && index >= input.length()) throw error("truncated number");
            if (consume('0')) {
                if (index < input.length() && asciiDigit(input.charAt(index))) throw error("leading zero");
            } else {
                if (index >= input.length() || input.charAt(index) < '1' || input.charAt(index) > '9') throw error("invalid number");
                while (index < input.length() && asciiDigit(input.charAt(index))) index++;
            }
            boolean fractional = false;
            if (consume('.')) {
                fractional = true;
                int fractionStart = index;
                while (index < input.length() && asciiDigit(input.charAt(index))) index++;
                if (fractionStart == index) throw error("fraction requires digits");
            }
            if (consume('e')) {
                fractional = true;
                consume('+');
                consume('-');
                int exponentStart = index;
                while (index < input.length() && asciiDigit(input.charAt(index))) index++;
                if (exponentStart == index) throw error("exponent requires digits");
            } else if (index < input.length() && input.charAt(index) == 'E') {
                throw error("uppercase exponent is not canonical");
            }
            String spelling = input.substring(start, index);
            if (spelling.startsWith("-") && fractional && new BigDecimal(spelling).signum() == 0
                    || spelling.equals("-0")) throw error("non-canonical negative zero");
            return fractional ? new BigDecimal(spelling) : new BigInteger(spelling);
        }

        private boolean asciiDigit(char character) {
            return character >= '0' && character <= '9';
        }

        private void expect(char c) { if (!consume(c)) throw error("expected '" + c + "'"); }
        private boolean consume(char c) {
            if (index < input.length() && input.charAt(index) == c) { index++; return true; }
            if (index < input.length() && Character.isWhitespace(input.charAt(index))) throw error("whitespace is not canonical");
            return false;
        }
        private ParseException error(String message) { return new ParseException(message + " at offset " + index); }
    }
}
