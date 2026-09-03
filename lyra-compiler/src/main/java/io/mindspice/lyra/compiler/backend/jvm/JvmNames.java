package io.mindspice.lyra.compiler.backend.jvm;

import java.util.Objects;
import java.util.Set;

/** Strict Java/JVM names used by generated classes and members. */
final class JvmNames {
    private static final Set<String> JAVA_MEMBER_RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null", "_",
            "record", "sealed", "permits", "non-sealed", "var", "yield",
            "module", "open", "opens", "requires", "transitive", "exports", "to",
            "uses", "provides", "with", "when");
    private static final Set<String> JAVA_BINARY_RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null", "_",
            "record", "sealed", "permits", "non-sealed", "var", "yield",
            "module", "open", "opens", "requires", "transitive", "exports", "to",
            "uses", "provides", "with", "when");

    private JvmNames() {
    }

    static String requireBinaryName(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank: " + value);
        }
        String[] parts = value.split("\\.", -1);
        for (String part : parts) {
            if (!isIdentifier(part) || JAVA_BINARY_RESERVED.contains(part)) {
                throw new IllegalArgumentException("invalid Java binary name for "
                        + label + ": " + value);
            }
        }
        return value;
    }

    static String requireMemberName(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!isIdentifier(value) || JAVA_MEMBER_RESERVED.contains(value)) {
            throw new IllegalArgumentException("invalid JVM member name for "
                    + label + ": " + value);
        }
        return value;
    }

    static String requireConstructorOrMemberName(String value, String label,
            boolean constructor) {
        Objects.requireNonNull(value, label);
        if (constructor) {
            if (!value.equals("<init>")) {
                throw new IllegalArgumentException("constructor member must be <init>: " + value);
            }
            return value;
        }
        if (value.equals("<init>") || value.equals("<clinit>")) {
            throw new IllegalArgumentException("special JVM name is not a normal member: " + value);
        }
        return requireMemberName(value, label);
    }

    private static boolean isIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.codePointAt(0))) {
            return false;
        }
        for (int offset = Character.charCount(value.codePointAt(0));
                offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isJavaIdentifierPart(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
