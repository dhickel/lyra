package io.mindspice.lyra.compiler.backend.jvm;

import java.util.Objects;
import java.util.Optional;

/**
 * One exact JVM value type.  This is deliberately not a Lyra type: a JVM
 * descriptor cannot carry signedness, qualifiers, or nilability.
 */
record JvmType(
        String descriptor,
        JvmTypeKind kind,
        Optional<String> binaryName,
        int slotWidth) {
    public JvmType {
        descriptor = JvmDescriptors.requireTypeDescriptorAllowVoid(descriptor);
        Objects.requireNonNull(kind, "kind");
        binaryName = Objects.requireNonNull(binaryName, "binaryName");
        if (slotWidth != 0 && slotWidth != 1 && slotWidth != 2) {
            throw new IllegalArgumentException("JVM slot width must be 0, 1, or 2");
        }
        boolean voidDescriptor = descriptor.equals("V");
        if (voidDescriptor != (kind == JvmTypeKind.VOID)) {
            throw new IllegalArgumentException("JVM kind and descriptor disagree");
        }
        if (kind == JvmTypeKind.VOID && slotWidth != 0) {
            throw new IllegalArgumentException("void has no JVM slots");
        }
        if (kind.isPrimitive() && (descriptor.length() != 1 || descriptor.equals("V"))) {
            throw new IllegalArgumentException("primitive JVM kind needs a primitive descriptor");
        }
        char descriptorTag = descriptor.charAt(0);
        if (kind.isReference() && descriptorTag != 'L' && descriptorTag != '[') {
            throw new IllegalArgumentException("reference JVM kind needs an object or array descriptor");
        }
        if (descriptorTag == '[' && kind != JvmTypeKind.ARRAY) {
            throw new IllegalArgumentException("array descriptor needs the ARRAY JVM kind");
        }
        if (descriptorTag == 'L' && kind == JvmTypeKind.ARRAY) {
            throw new IllegalArgumentException("object descriptor cannot use the ARRAY JVM kind");
        }
        if (kind.isPrimitive() && kindForPrimitiveDescriptor(descriptorTag) != kind) {
            throw new IllegalArgumentException("primitive JVM kind and descriptor tag disagree");
        }
        if (kind == JvmTypeKind.VOID && !binaryName.isEmpty()) {
            throw new IllegalArgumentException("void cannot have a binary name");
        }
        if (!kind.isReference() && kind != JvmTypeKind.VOID && !binaryName.isEmpty()) {
            throw new IllegalArgumentException("primitive JVM kind cannot have a binary name");
        }
        if (kind.isReference() && binaryName.isPresent() && descriptor.charAt(0) != 'L') {
            throw new IllegalArgumentException("array JVM kinds do not have a binary class name");
        }
        if ((kind == JvmTypeKind.WRAPPER || kind == JvmTypeKind.TUPLE
                || kind == JvmTypeKind.FUNCTION || kind == JvmTypeKind.UNIT)
                && binaryName.isEmpty()) {
            throw new IllegalArgumentException(kind + " needs a binary class name");
        }
        if (kind == JvmTypeKind.TUPLE || kind == JvmTypeKind.FUNCTION) {
            String simpleName = binaryName.orElseThrow().substring(
                    binaryName.orElseThrow().lastIndexOf('.') + 1);
            String requiredPrefix = kind == JvmTypeKind.TUPLE
                    ? "$lyra$tuple$" : "$lyra$fn$";
            if (!simpleName.startsWith(requiredPrefix)
                    || simpleName.length() == requiredPrefix.length()) {
                throw new IllegalArgumentException(
                        kind + " must use its exact generated class family: " + requiredPrefix);
            }
        }
        if (descriptor.charAt(0) == 'L' && binaryName.isEmpty()) {
            throw new IllegalArgumentException("object JVM types need a binary class name");
        }
        if (kind.isReference() && binaryName.isPresent()) {
            String binary = JvmNames.requireBinaryName(binaryName.orElseThrow(), "binaryName");
            String expected = "L" + binary.replace('.', '/') + ";";
            if (!expected.equals(descriptor)) {
                throw new IllegalArgumentException("binary name and descriptor disagree");
            }
        }
        if (kind == JvmTypeKind.WRAPPER && binaryName.isPresent()
                && !isNullablePrimitiveWrapper(binaryName.orElseThrow())) {
            throw new IllegalArgumentException("unsupported nullable primitive wrapper: "
                    + binaryName.orElseThrow());
        }
        if (kind == JvmTypeKind.UNIT && binaryName.isPresent()
                && !binaryName.orElseThrow().equals("io.mindspice.lyra.runtime.LyraUnit")) {
            throw new IllegalArgumentException("Unit must use LyraUnit");
        }
        int expectedSlots = kind == JvmTypeKind.VOID ? 0 : kind.isCategory2() ? 2 : 1;
        if (slotWidth != expectedSlots) {
            throw new IllegalArgumentException("JVM kind and slot width disagree");
        }
    }

    private static JvmTypeKind kindForPrimitiveDescriptor(char descriptor) {
        return switch (descriptor) {
            case 'B' -> JvmTypeKind.BYTE;
            case 'S' -> JvmTypeKind.SHORT;
            case 'I' -> JvmTypeKind.INT;
            case 'J' -> JvmTypeKind.LONG;
            case 'F' -> JvmTypeKind.FLOAT;
            case 'D' -> JvmTypeKind.DOUBLE;
            case 'Z' -> JvmTypeKind.BOOLEAN;
            case 'C' -> JvmTypeKind.CHAR;
            default -> null;
        };
    }

    /** Creates an exact primitive or void JVM type from its descriptor tag. */
    public static JvmType primitive(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.length() != 1) {
            throw new IllegalArgumentException("primitive descriptor must have one tag: " + descriptor);
        }
        JvmTypeKind kind = switch (descriptor.charAt(0)) {
            case 'V' -> JvmTypeKind.VOID;
            case 'B' -> JvmTypeKind.BYTE;
            case 'S' -> JvmTypeKind.SHORT;
            case 'I' -> JvmTypeKind.INT;
            case 'J' -> JvmTypeKind.LONG;
            case 'F' -> JvmTypeKind.FLOAT;
            case 'D' -> JvmTypeKind.DOUBLE;
            case 'Z' -> JvmTypeKind.BOOLEAN;
            case 'C' -> JvmTypeKind.CHAR;
            default -> throw new IllegalArgumentException("not a primitive descriptor: " + descriptor);
        };
        return new JvmType(descriptor, kind, Optional.empty(), kind == JvmTypeKind.VOID ? 0
                : kind.isCategory2() ? 2 : 1);
    }

    public static JvmType reference(String binaryName) {
        return reference(binaryName, JvmTypeKind.REFERENCE);
    }

    public static JvmType reference(String binaryName, JvmTypeKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.isReference() || kind == JvmTypeKind.ARRAY) {
            throw new IllegalArgumentException("binary class names require a reference kind");
        }
        String validatedName = JvmNames.requireBinaryName(binaryName, "binaryName");
        String internalName = validatedName.replace('.', '/');
        JvmDescriptors.requireTypeDescriptor("L" + internalName + ";");
        return new JvmType("L" + internalName + ";", kind,
                Optional.of(validatedName), 1);
    }

    public static JvmType unit() {
        return reference("io.mindspice.lyra.runtime.LyraUnit", JvmTypeKind.UNIT);
    }

    public static JvmType array(String componentDescriptor) {
        Objects.requireNonNull(componentDescriptor, "componentDescriptor");
        JvmDescriptors.requireTypeDescriptor(componentDescriptor);
        if (componentDescriptor.equals("V")) {
            throw new IllegalArgumentException("void cannot be an array component");
        }
        return new JvmType("[" + componentDescriptor, JvmTypeKind.ARRAY,
                Optional.empty(), 1);
    }

    public static JvmType wrapper(String binaryName) {
        return reference(binaryName, JvmTypeKind.WRAPPER);
    }

    private static boolean isNullablePrimitiveWrapper(String binaryName) {
        return switch (binaryName) {
            case "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                    "java.lang.Long", "java.lang.Float", "java.lang.Double",
                    "java.lang.Boolean", "java.lang.Character" -> true;
            default -> false;
        };
    }

    public String descriptorString() {
        return descriptor;
    }

    public String jvmDescriptor() {
        return descriptor;
    }

    public Optional<String> javaBinaryName() {
        return binaryName;
    }

    public Optional<String> internalName() {
        return binaryName.map(value -> value.replace('.', '/'));
    }

    public boolean isPrimitive() {
        return kind.isPrimitive();
    }

    public boolean isReference() {
        return kind.isReference();
    }

    public boolean isVoid() {
        return kind == JvmTypeKind.VOID;
    }

    @Override
    public String toString() {
        return descriptor;
    }
}
