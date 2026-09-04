package io.mindspice.lyra.compiler.artifact;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;

/** Minimal class-file reader for version/source-line metadata only. */
final class ClassFileReader {
    private ClassFileReader() {
    }

    static ParsedClass read(String binaryName, byte[] bytes) {
        Objects.requireNonNull(binaryName, "binaryName");
        Objects.requireNonNull(bytes, "bytes");
        try {
            Cursor cursor = new Cursor(bytes);
            if (cursor.u4() != 0xCAFEBABEL) {
                throw new ArtifactAssemblyException("class entry is not a JVM class file: " + binaryName);
            }
            int minor = cursor.u2();
            int major = cursor.u2();
            if (major != 69 || (minor != 0 && minor != 65535)) {
                throw new ArtifactAssemblyException("class entry does not target Java 25: " + binaryName);
            }
            int constantPoolCount = cursor.u2();
            if (constantPoolCount < 1) {
                throw new ArtifactAssemblyException("class entry has an empty constant pool: " + binaryName);
            }
            Object[] pool = new Object[constantPoolCount];
            int[] tags = new int[constantPoolCount];
            for (int index = 1; index < constantPoolCount; index++) {
                int tag = cursor.u1();
                tags[index] = tag;
                switch (tag) {
                    case 1 -> pool[index] = cursor.modifiedUtf8();
                    case 3, 4 -> cursor.skip(4);
                    case 5, 6 -> {
                        cursor.skip(8);
                        if (++index >= constantPoolCount) {
                            throw new ArtifactAssemblyException(
                                    "long/double constant has no reserved pool slot: " + binaryName);
                        }
                        tags[index] = -1;
                    }
                    case 7, 8, 16, 19, 20 -> pool[index] = new Ref(tag, cursor.u2());
                    case 9, 10, 11, 12, 17, 18 -> pool[index] = new Ref2(tag,
                            cursor.u2(), cursor.u2());
                    case 15 -> pool[index] = new MethodHandle(cursor.u1(), cursor.u2());
                    default -> throw new ArtifactAssemblyException(
                            "invalid constant-pool tag in class " + binaryName + ": " + tag);
                }
            }
            validateConstantPool(tags, pool, binaryName);
            cursor.skip(2); // access flags
            int thisClass = cursor.u2();
            int superClass = cursor.u2();
            String internalName = className(pool, thisClass, binaryName);
            if (!internalName.equals(binaryName.replace('.', '/'))) {
                throw new ArtifactAssemblyException("class entry name disagrees with its path: "
                        + binaryName + " versus " + internalName);
            }
            if (superClass != 0) {
                className(pool, superClass, binaryName);
            }
            int interfaces = cursor.u2();
            for (int index = 0; index < interfaces; index++) {
                className(pool, cursor.u2(), binaryName);
            }
            List<ParsedField> fields = readFields(cursor, pool, binaryName);
            List<ParsedMethod> methods = readMethods(cursor, pool, binaryName);
            skipAttributes(cursor, pool, binaryName);
            if (cursor.remaining() != 0) {
                throw new ArtifactAssemblyException("trailing data in class entry: " + binaryName);
            }
            return new ParsedClass(binaryName, minor == 65535, fields, methods);
        } catch (ArtifactAssemblyException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ArtifactAssemblyException("malformed class entry: " + binaryName, exception);
        }
    }

    private static List<ParsedMethod> readMethods(Cursor cursor, Object[] pool, String className) {
        int count = cursor.u2();
        ArrayList<ParsedMethod> methods = new ArrayList<>(count);
        Set<String> declarations = new HashSet<>();
        for (int index = 0; index < count; index++) {
            cursor.skip(2); // access flags
            String name = utf8(pool, cursor.u2(), className);
            String descriptor = utf8(pool, cursor.u2(), className);
            if (!validMethodDescriptor(descriptor)) {
                throw new ArtifactAssemblyException("invalid method descriptor in class: "
                        + className + ": " + name + descriptor);
            }
            int attributes = cursor.u2();
            int codeLength = -1;
            ArrayList<Line> lines = new ArrayList<>();
            for (int attribute = 0; attribute < attributes; attribute++) {
                String attributeName = utf8(pool, cursor.u2(), className);
                long attributeLength = cursor.u4();
                if (attributeLength > Integer.MAX_VALUE || attributeLength > cursor.remaining()) {
                    throw new ArtifactAssemblyException("class attribute exceeds its class file: " + className);
                }
                int end = cursor.position() + (int) attributeLength;
                if (attributeName.equals("Code")) {
                    if (codeLength >= 0) {
                        throw new ArtifactAssemblyException("method has duplicate Code attributes: " + name);
                    }
                    cursor.skip(2); // max stack
                    cursor.skip(2); // max locals
                    long length = cursor.u4();
                    if (length > Integer.MAX_VALUE || length > cursor.remaining()) {
                        throw new ArtifactAssemblyException("method code exceeds its class file: " + name);
                    }
                    codeLength = (int) length;
                    cursor.skip(codeLength);
                    int exceptionCount = cursor.u2();
                    cursor.skip(exceptionCount * 8);
                    int nested = cursor.u2();
                    for (int nestedAttribute = 0; nestedAttribute < nested; nestedAttribute++) {
                        String nestedName = utf8(pool, cursor.u2(), className);
                        long nestedLength = cursor.u4();
                        if (nestedLength > Integer.MAX_VALUE || nestedLength > cursor.remaining()) {
                            throw new ArtifactAssemblyException("nested code attribute exceeds its class file: " + name);
                        }
                        int nestedEnd = cursor.position() + (int) nestedLength;
                        if (nestedName.equals("LineNumberTable")) {
                            int lineCount = cursor.u2();
                            for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
                                int start = cursor.u2();
                                int line = cursor.u2();
                                if (start > codeLength || line < 1) {
                                    throw new ArtifactAssemblyException("invalid line table in method: " + name);
                                }
                                lines.add(new Line(start, line));
                            }
                        }
                        if (cursor.position() > nestedEnd) {
                            throw new ArtifactAssemblyException("nested code attribute is truncated: " + name);
                        }
                        cursor.position(nestedEnd);
                    }
                    if (cursor.position() != end) {
                        throw new ArtifactAssemblyException("Code attribute has trailing data: " + name);
                    }
                }
                if (cursor.position() > end) {
                    throw new ArtifactAssemblyException("method attribute is truncated: " + name);
                }
                cursor.position(end);
            }
            lines.sort(Comparator.comparingInt(Line::startBci).thenComparingInt(Line::line));
            if (!declarations.add(name + descriptor)) {
                throw new ArtifactAssemblyException("duplicate method declaration in class: " + className);
            }
            methods.add(new ParsedMethod(name, descriptor, codeLength, normalizeLines(lines)));
        }
        return List.copyOf(methods);
    }

    private static List<Line> normalizeLines(List<Line> values) {
        ArrayList<Line> result = new ArrayList<>();
        int previous = -1;
        for (Line value : values) {
            if (value.startBci() == previous) {
                // Multiple line entries at one BCI have no distinct range.  A
                // stable first line is sufficient and avoids overlapping maps.
                continue;
            }
            result.add(value);
            previous = value.startBci();
        }
        return List.copyOf(result);
    }

    private static List<ParsedField> readFields(Cursor cursor, Object[] pool, String className) {
        int count = cursor.u2();
        ArrayList<ParsedField> fields = new ArrayList<>(count);
        Set<String> declarations = new HashSet<>();
        for (int index = 0; index < count; index++) {
            cursor.skip(2); // access flags
            String name = utf8(pool, cursor.u2(), className);
            String descriptor = utf8(pool, cursor.u2(), className);
            if (!validFieldDescriptor(descriptor)) {
                throw new ArtifactAssemblyException("invalid field descriptor in class: "
                        + className + ": " + name + descriptor);
            }
            skipAttributes(cursor, pool, className);
            if (!declarations.add(name + descriptor)) {
                throw new ArtifactAssemblyException("duplicate field declaration in class: " + className);
            }
            fields.add(new ParsedField(name, descriptor));
        }
        return List.copyOf(fields);
    }

    private static void skipAttributes(Cursor cursor, Object[] pool, String className) {
        int count = cursor.u2();
        for (int index = 0; index < count; index++) {
            utf8(pool, cursor.u2(), className);
            long length = cursor.u4();
            if (length > Integer.MAX_VALUE || length > cursor.remaining()) {
                throw new ArtifactAssemblyException("class attribute exceeds its class file: " + className);
            }
            cursor.skip((int) length);
        }
    }

    private static String className(Object[] pool, int index, String className) {
        if (index <= 0 || index >= pool.length || !(pool[index] instanceof Ref reference)
                || reference.tag() != 7) {
            throw new ArtifactAssemblyException("invalid class constant in class: " + className);
        }
        return utf8(pool, reference.first(), className);
    }

    private static String utf8(Object[] pool, int index, String className) {
        if (index <= 0 || index >= pool.length || !(pool[index] instanceof String value)) {
            throw new ArtifactAssemblyException("invalid UTF-8 constant in class: " + className);
        }
        return value;
    }

    private static void validateConstantPool(int[] tags, Object[] pool, String className) {
        for (int index = 1; index < tags.length; index++) {
            switch (tags[index]) {
                case -1 -> {
                    if (index == 1 || (tags[index - 1] != 5 && tags[index - 1] != 6)) {
                        throw new ArtifactAssemblyException("invalid reserved constant-pool slot: " + className);
                    }
                }
                case 1, 3, 4 -> {
                    // The UTF-8 payload was decoded while reading; primitive
                    // constants have no cross-pool references.
                }
                case 5, 6 -> {
                    if (index + 1 >= tags.length || tags[index + 1] != -1) {
                        throw new ArtifactAssemblyException("wide constant lacks a reserved pool slot: " + className);
                    }
                }
                case 7, 8, 16, 19, 20 -> {
                    Ref reference = ref(pool[index], tags[index], className);
                    requireTag(tags, reference.first(), 1, className);
                }
                case 9, 10, 11 -> {
                    Ref2 reference = ref2(pool[index], tags[index], className);
                    requireTag(tags, reference.first(), 7, className);
                    requireTag(tags, reference.second(), 12, className);
                }
                case 12 -> {
                    Ref2 reference = ref2(pool[index], tags[index], className);
                    requireTag(tags, reference.first(), 1, className);
                    requireTag(tags, reference.second(), 1, className);
                }
                case 15 -> {
                    MethodHandle handle = pool[index] instanceof MethodHandle value
                            ? value : invalidHandle(className);
                    if (handle.kind() < 1 || handle.kind() > 9) {
                        throw new ArtifactAssemblyException("invalid method-handle kind: " + className);
                    }
                    if (handle.kind() <= 4) {
                        requireTag(tags, handle.reference(), 9, className);
                    } else if (handle.kind() == 6 || handle.kind() == 7) {
                        requireOneOfTags(tags, handle.reference(), className, 10, 11);
                    } else {
                        requireTag(tags, handle.reference(), handle.kind() == 9 ? 11 : 10,
                                className);
                    }
                }
                case 17, 18 -> {
                    Ref2 reference = ref2(pool[index], tags[index], className);
                    requireTag(tags, reference.second(), 12, className);
                }
                default -> throw new ArtifactAssemblyException("invalid constant-pool entry: " + className);
            }
        }
    }

    private static Ref ref(Object value, int tag, String className) {
        if (!(value instanceof Ref reference) || reference.tag() != tag) {
            throw new ArtifactAssemblyException("invalid constant-pool reference: " + className);
        }
        return reference;
    }

    private static Ref2 ref2(Object value, int tag, String className) {
        if (!(value instanceof Ref2 reference) || reference.tag() != tag) {
            throw new ArtifactAssemblyException("invalid constant-pool pair: " + className);
        }
        return reference;
    }

    private static MethodHandle invalidHandle(String className) {
        throw new ArtifactAssemblyException("invalid method-handle constant: " + className);
    }

    private static void requireTag(int[] tags, int index, int expected, String className) {
        if (index <= 0 || index >= tags.length || tags[index] != expected) {
            throw new ArtifactAssemblyException("invalid constant-pool reference in class: " + className);
        }
    }

    private static void requireOneOfTags(int[] tags, int index, String className,
                                         int first, int second) {
        if (index <= 0 || index >= tags.length
                || (tags[index] != first && tags[index] != second)) {
            throw new ArtifactAssemblyException("invalid constant-pool reference in class: " + className);
        }
    }

    private static boolean validFieldDescriptor(String descriptor) {
        return descriptor != null && !descriptor.isEmpty()
                && typeEnd(descriptor, 0, false) == descriptor.length();
    }

    private static boolean validMethodDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            return false;
        }
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            index = typeEnd(descriptor, index, false);
            if (index < 0) {
                return false;
            }
        }
        if (index >= descriptor.length()) {
            return false;
        }
        int end = typeEnd(descriptor, index + 1, true);
        return end == descriptor.length();
    }

    private static int typeEnd(String descriptor, int start, boolean returnType) {
        if (start >= descriptor.length()) {
            return -1;
        }
        char value = descriptor.charAt(start);
        if ("BCSIZJFD".indexOf(value) >= 0) {
            return start + 1;
        }
        if (value == 'V') {
            return returnType ? start + 1 : -1;
        }
        if (value == '[') {
            return typeEnd(descriptor, start + 1, false);
        }
        if (value != 'L') {
            return -1;
        }
        int end = descriptor.indexOf(';', start + 1);
        if (end <= start + 1) {
            return -1;
        }
        if (descriptor.charAt(start + 1) == '/' || descriptor.charAt(end - 1) == '/') {
            return -1;
        }
        for (int index = start + 1; index < end; index++) {
            char character = descriptor.charAt(index);
            if (character == '.' || character == '[' || character == ';'
                    || character == '(' || character == ')' || character == ':'
                    || Character.isISOControl(character) || Character.isWhitespace(character)
                    || (character == '/' && index + 1 < end && descriptor.charAt(index + 1) == '/')) {
                return -1;
            }
        }
        return end + 1;
    }

    private record Ref(int tag, int first) {
    }

    private record Ref2(int tag, int first, int second) {
    }

    private record MethodHandle(int kind, int reference) {
    }

    record ParsedClass(String binaryName, boolean preview, List<ParsedField> fields,
                       List<ParsedMethod> methods) {
        ParsedClass {
            Objects.requireNonNull(binaryName, "binaryName");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
            methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
        }
    }

    record ParsedField(String name, String descriptor) {
        ParsedField {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    record ParsedMethod(String name, String descriptor, int codeLength, List<Line> lines) {
        ParsedMethod {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        }
    }

    record Line(int startBci, int line) {
        Line {
            if (startBci < 0 || line < 1) {
                throw new IllegalArgumentException("invalid class line record");
            }
        }
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int position;

        private Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        int position() {
            return position;
        }

        void position(int value) {
            if (value < 0 || value > bytes.length) {
                throw new ArtifactAssemblyException("class cursor moved outside class file");
            }
            position = value;
        }

        int remaining() {
            return bytes.length - position;
        }

        int u1() {
            require(1);
            return bytes[position++] & 0xff;
        }

        int u2() {
            require(2);
            int value = ((bytes[position] & 0xff) << 8) | (bytes[position + 1] & 0xff);
            position += 2;
            return value;
        }

        long u4() {
            require(4);
            long value = ((long) (bytes[position] & 0xff) << 24)
                    | ((long) (bytes[position + 1] & 0xff) << 16)
                    | ((long) (bytes[position + 2] & 0xff) << 8)
                    | (bytes[position + 3] & 0xffL);
            position += 4;
            return value;
        }

        String modifiedUtf8() {
            int length = u2();
            require(length);
            try {
                byte[] value = new byte[length + 2];
                value[0] = (byte) (length >>> 8);
                value[1] = (byte) length;
                System.arraycopy(bytes, position, value, 2, length);
                position += length;
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value))) {
                    return input.readUTF();
                }
            } catch (IOException exception) {
                throw new ArtifactAssemblyException("invalid modified UTF-8 class constant", exception);
            }
        }

        void skip(int length) {
            if (length < 0) {
                throw new ArtifactAssemblyException("negative class-file length");
            }
            require(length);
            position += length;
        }

        private void require(int length) {
            if (length < 0 || position > bytes.length - length) {
                throw new ArtifactAssemblyException("truncated class file");
            }
        }
    }
}
