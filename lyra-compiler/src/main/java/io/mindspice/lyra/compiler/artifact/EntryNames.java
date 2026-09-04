package io.mindspice.lyra.compiler.artifact;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical ZIP/class-directory entry-name policy. */
final class EntryNames {
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[/\\\\].*");

    private EntryNames() {
    }

    static final Comparator<String> UTF8_COMPARATOR = (left, right) -> {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(leftBytes[index] & 0xff, rightBytes[index] & 0xff);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    };

    static String classEntry(String binaryName) {
        Objects.requireNonNull(binaryName, "binaryName");
        if (!isBinaryName(binaryName) || binaryName.indexOf('/') >= 0
                || binaryName.indexOf('\\') >= 0 || binaryName.endsWith(".")) {
            throw new ArtifactAssemblyException("invalid generated binary name: " + binaryName);
        }
        String entry = binaryName.replace('.', '/') + ".class";
        require(entry);
        return entry;
    }

    static void require(String name) {
        Objects.requireNonNull(name, "entry name");
        if (name.isEmpty() || name.startsWith("/") || WINDOWS_ABSOLUTE.matcher(name).matches()
                || name.indexOf('\\') >= 0
                || name.contains("//") || name.startsWith("./") || name.contains("../")
                || name.endsWith("/") || name.indexOf('\0') >= 0) {
            throw new ArtifactAssemblyException("invalid artifact entry name: " + name);
        }
        try {
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(name));
        } catch (CharacterCodingException exception) {
            throw new ArtifactAssemblyException("artifact entry name is not UTF-8: " + name, exception);
        }
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new ArtifactAssemblyException("invalid artifact entry name: " + name);
            }
        }
    }

    static Comparator<String> utf8Comparator() {
        return UTF8_COMPARATOR;
    }

    private static boolean isBinaryName(String value) {
        if (value.isBlank() || value.startsWith(".") || value.endsWith(".")) {
            return false;
        }
        String[] parts = value.split("[.]", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            int first = part.codePointAt(0);
            if (!Character.isJavaIdentifierStart(first)) {
                return false;
            }
            for (int offset = Character.charCount(first); offset < part.length();) {
                int codePoint = part.codePointAt(offset);
                if (!Character.isJavaIdentifierPart(codePoint)) {
                    return false;
                }
                offset += Character.charCount(codePoint);
            }
        }
        return true;
    }
}
