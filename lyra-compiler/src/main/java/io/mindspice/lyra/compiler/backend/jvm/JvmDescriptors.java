package io.mindspice.lyra.compiler.backend.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict JVM descriptor syntax used by the planning model, without emitting bytecode. */
final class JvmDescriptors {
    private JvmDescriptors() {
    }

    /** Validates and returns one non-void field/value descriptor. */
    public static String requireTypeDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        int end = typeEnd(descriptor, 0, false);
        if (end != descriptor.length()) {
            throw invalid(descriptor);
        }
        return descriptor;
    }

    /** Alias for callers that use the JVM field-descriptor terminology. */
    public static String requireFieldDescriptor(String descriptor) {
        return requireTypeDescriptor(descriptor);
    }

    /** Package-level validator used for the one JVM type that is return-only. */
    static String requireTypeDescriptorAllowVoid(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        int end = typeEnd(descriptor, 0, true);
        if (end != descriptor.length()) {
            throw invalid(descriptor);
        }
        return descriptor;
    }

    /** Validates and returns one complete JVM method descriptor. */
    public static String requireMethodDescriptor(String descriptor) {
        parseMethodDescriptor(descriptor);
        return descriptor;
    }

    public static boolean isTypeDescriptor(String descriptor) {
        try {
            requireTypeDescriptor(descriptor);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static boolean isMethodDescriptor(String descriptor) {
        try {
            requireMethodDescriptor(descriptor);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static MethodDescriptor parseMethodDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            throw invalid(descriptor);
        }
        ArrayList<String> parameters = new ArrayList<>();
        int parameterSlots = 0;
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            int end = typeEnd(descriptor, index, false);
            String parameter = descriptor.substring(index, end);
            parameterSlots += parameter.startsWith("J") || parameter.startsWith("D") ? 2 : 1;
            if (parameterSlots > 255) {
                throw invalid(descriptor);
            }
            parameters.add(parameter);
            index = end;
        }
        if (index >= descriptor.length() || descriptor.charAt(index) != ')') {
            throw invalid(descriptor);
        }
        int returnStart = index + 1;
        int returnEnd = typeEnd(descriptor, returnStart, true);
        if (returnEnd != descriptor.length()) {
            throw invalid(descriptor);
        }
        return new MethodDescriptor(descriptor, parameters, descriptor.substring(returnStart, returnEnd));
    }

    private static int typeEnd(String descriptor, int start, boolean allowVoid) {
        if (start < 0 || start >= descriptor.length()) {
            throw invalid(descriptor);
        }
        int index = start;
        int dimensions = 0;
        while (index < descriptor.length() && descriptor.charAt(index) == '[') {
            if (++dimensions > 255) {
                throw invalid(descriptor);
            }
            index++;
        }
        if (index >= descriptor.length()) {
            throw invalid(descriptor);
        }
        char tag = descriptor.charAt(index);
        if (isPrimitiveTag(tag)) {
            return index + 1;
        }
        if (tag == 'V') {
            // V is legal only as a bare method return, never as an array
            // component or any field/value descriptor.
            if (!allowVoid || dimensions != 0) {
                throw invalid(descriptor);
            }
            return index + 1;
        }
        if (tag != 'L') {
            throw invalid(descriptor);
        }
        int semicolon = descriptor.indexOf(';', index + 1);
        if (semicolon < 0 || semicolon == index + 1) {
            throw invalid(descriptor);
        }
        String internalName = descriptor.substring(index + 1, semicolon);
        if (internalName.charAt(0) == '/' || internalName.charAt(internalName.length() - 1) == '/') {
            throw invalid(descriptor);
        }
        char previous = 0;
        for (int nameIndex = 0; nameIndex < internalName.length(); nameIndex++) {
            char character = internalName.charAt(nameIndex);
            if (character == '.' || character == ';' || character == '['
                    || character == ':' || character == '(' || character == ')'
                    || character == '<' || character == '>' || character == '\\'
                    || Character.isWhitespace(character) || Character.isISOControl(character)
                    || (character == '/' && previous == '/')) {
                throw invalid(descriptor);
            }
            previous = character;
        }
        return semicolon + 1;
    }

    private static boolean isPrimitiveTag(char tag) {
        return "BCSIZJFD".indexOf(tag) >= 0;
    }

    private static IllegalArgumentException invalid(String descriptor) {
        return new IllegalArgumentException("invalid JVM descriptor: " + descriptor);
    }

    /** Immutable parsed method descriptor. */
    record MethodDescriptor(
            String descriptor,
            List<String> parameterDescriptors,
            String returnDescriptor) {
        MethodDescriptor {
            Objects.requireNonNull(descriptor, "descriptor");
            parameterDescriptors = List.copyOf(parameterDescriptors);
            Objects.requireNonNull(returnDescriptor, "returnDescriptor");
            int parameterSlots = 0;
            for (String parameter : parameterDescriptors) {
                requireTypeDescriptor(parameter);
                parameterSlots += parameter.startsWith("J") || parameter.startsWith("D") ? 2 : 1;
                if (parameterSlots > 255) {
                    throw new IllegalArgumentException("method descriptor exceeds JVM parameter slots");
                }
            }
            requireTypeDescriptorAllowVoid(returnDescriptor);
            if (returnDescriptor.equals("V") && descriptor.endsWith(")")) {
                throw new IllegalArgumentException("method descriptor has no return type");
            }
            if (!descriptor.equals("(" + String.join("", parameterDescriptors) + ")"
                    + returnDescriptor)) {
                throw new IllegalArgumentException("method descriptor components disagree");
            }
        }

        public String descriptorString() {
            return descriptor;
        }

        public List<String> parameterTypes() {
            return parameterDescriptors;
        }

        public String returnType() {
            return returnDescriptor;
        }

        public int parameterSlots() {
            return parameterDescriptors.stream()
                    .mapToInt(parameter -> parameter.equals("J") || parameter.equals("D") ? 2 : 1)
                    .sum();
        }
    }
}
