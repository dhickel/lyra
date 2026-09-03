package io.mindspice.lyra.compiler.backend.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable explanation of a representation boundary.  Conversion is never
 * inferred from a descriptor alone; the context and stable reason remain in
 * the plan for parity checks and later lowering.
 */
record JvmMaterializationPlan(
        JvmMaterializationKind kind,
        JvmMappingContext context,
        String reason,
        List<String> physicalDescriptors) {
    public JvmMaterializationPlan {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(context, "context");
        if (Objects.requireNonNull(reason, "reason").isBlank()) {
            throw new IllegalArgumentException("materialization reason must not be blank");
        }
        Objects.requireNonNull(physicalDescriptors, "physicalDescriptors");
        ArrayList<String> copied = new ArrayList<>(physicalDescriptors.size());
        for (String descriptor : physicalDescriptors) {
            copied.add(kind == JvmMaterializationKind.LANGUAGE_UNIT_RETURN_VOID
                    ? JvmDescriptors.requireTypeDescriptorAllowVoid(descriptor)
                    : JvmDescriptors.requireTypeDescriptor(descriptor));
        }
        physicalDescriptors = List.copyOf(copied);
        if (physicalDescriptors.isEmpty()) {
            throw new IllegalArgumentException("materialization needs a physical descriptor");
        }
        switch (kind) {
            case DIRECT -> {
                if (physicalDescriptors.size() != 1) {
                    throw new IllegalArgumentException(
                            "direct materialization needs one JVM value");
                }
            }
            case NULLABLE_REFERENCE -> {
                if (physicalDescriptors.size() != 1
                        || !isReferenceDescriptor(physicalDescriptors.getFirst())
                        || isNullablePrimitiveWrapper(physicalDescriptors.getFirst())
                        || isWrapperArrayDescriptor(physicalDescriptors.getFirst())) {
                    throw new IllegalArgumentException(
                            "nullable-reference materialization needs one non-wrapper reference");
                }
            }
            case NULLABLE_PRIMITIVE_WRAPPER -> {
                if (physicalDescriptors.size() != 1
                        || !isNullablePrimitiveWrapper(physicalDescriptors.getFirst())) {
                    throw new IllegalArgumentException(
                            "nullable-primitive materialization needs its exact wrapper");
                }
            }
            case INTERNAL_PRESENCE_PAYLOAD -> {
                if (!context.permitsInternalPresencePayload()
                        || physicalDescriptors.size() != 2
                        || !physicalDescriptors.getFirst().equals("Z")
                        || !isNonVoidPrimitive(physicalDescriptors.get(1))) {
                    throw new IllegalArgumentException(
                            "presence-payload materialization needs an internal boolean and primitive payload");
                }
            }
            case UNIT_SINGLETON -> {
                if (context.position().isFunctionReturn()
                        || !physicalDescriptors.equals(List.of(
                        "Lio/mindspice/lyra/runtime/LyraUnit;"))) {
                    throw new IllegalArgumentException(
                            "Unit singleton materialization needs LyraUnit outside returns");
                }
            }
            case LANGUAGE_UNIT_RETURN_VOID -> {
                if (!context.position().isFunctionReturn()
                        || !physicalDescriptors.equals(List.of("V"))) {
                    throw new IllegalArgumentException(
                            "Unit void materialization needs a function-return V descriptor");
                }
            }
            case ARRAY_WRAPPER_ELEMENTS, NULLABLE_ARRAY_WRAPPER_ELEMENTS -> {
                if (physicalDescriptors.size() != 1
                        || !isWrapperArrayDescriptor(physicalDescriptors.getFirst())) {
                    throw new IllegalArgumentException(
                            "array-wrapper materialization needs one exact wrapper array");
                }
            }
        }
    }

    private static boolean isNonVoidPrimitive(String descriptor) {
        return descriptor.length() == 1 && "BCSIZJFD".indexOf(descriptor.charAt(0)) >= 0;
    }

    private static boolean isReferenceDescriptor(String descriptor) {
        return descriptor.startsWith("L") || descriptor.startsWith("[");
    }

    private static boolean isNullablePrimitiveWrapper(String descriptor) {
        return switch (descriptor) {
            case "Ljava/lang/Byte;", "Ljava/lang/Short;", "Ljava/lang/Integer;",
                    "Ljava/lang/Long;", "Ljava/lang/Float;", "Ljava/lang/Double;",
                    "Ljava/lang/Boolean;", "Ljava/lang/Character;" -> true;
            default -> false;
        };
    }

    private static boolean isWrapperArrayDescriptor(String descriptor) {
        return switch (descriptor) {
            case "[Ljava/lang/Byte;", "[Ljava/lang/Short;", "[Ljava/lang/Integer;",
                    "[Ljava/lang/Long;", "[Ljava/lang/Float;", "[Ljava/lang/Double;",
                    "[Ljava/lang/Boolean;", "[Ljava/lang/Character;" -> true;
            default -> false;
        };
    }

    public boolean isWrapper() {
        return kind == JvmMaterializationKind.NULLABLE_PRIMITIVE_WRAPPER
                || kind == JvmMaterializationKind.ARRAY_WRAPPER_ELEMENTS
                || kind == JvmMaterializationKind.NULLABLE_ARRAY_WRAPPER_ELEMENTS;
    }

    public boolean isPresencePayload() {
        return kind == JvmMaterializationKind.INTERNAL_PRESENCE_PAYLOAD;
    }

    public boolean isUnitSingleton() {
        return kind == JvmMaterializationKind.UNIT_SINGLETON;
    }

    public String canonicalSpelling() {
        return kind.name().toLowerCase(java.util.Locale.ROOT)
                + "@" + context.canonicalSpelling()
                + ":" + String.join(",", physicalDescriptors)
                + ":" + reason;
    }

    public String canonical() {
        return canonicalSpelling();
    }
}
