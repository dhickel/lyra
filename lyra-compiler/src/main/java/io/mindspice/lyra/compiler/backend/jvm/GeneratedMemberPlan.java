package io.mindspice.lyra.compiler.backend.jvm;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable member shape.  There is intentionally no code/body field: Phase
 * 14 plans names and descriptors only, and Phase 15+ owns emission.
 */
record GeneratedMemberPlan(
        GeneratedMemberKind kind,
        String name,
        String descriptor,
        boolean staticMember,
        Optional<String> exportId,
        Optional<String> sourceName,
        Optional<String> canonicalLyraContract,
        Optional<JvmSignaturePlan> signature,
        Optional<JvmTypePlan> valueType,
        int componentIndex) {
    public GeneratedMemberPlan {
        Objects.requireNonNull(kind, "kind");
        boolean constructor = isConstructorKind(kind);
        name = JvmNames.requireConstructorOrMemberName(name, "generated member name", constructor);
        descriptor = kind.isMethod()
                ? JvmDescriptors.requireMethodDescriptor(Objects.requireNonNull(descriptor, "descriptor"))
                : JvmDescriptors.requireTypeDescriptor(Objects.requireNonNull(descriptor, "descriptor"));
        validateStaticness(kind, staticMember);
        if (kind.isMethod() && !staticMember
                && JvmDescriptors.parseMethodDescriptor(descriptor).parameterSlots() >= 255) {
            throw new IllegalArgumentException(
                    "instance method descriptor exceeds JVM parameter slots including receiver");
        }
        if (constructor && !descriptor.endsWith(")V")) {
            throw new IllegalArgumentException("constructor must return void: " + descriptor);
        }
        if (constructor && staticMember) {
            throw new IllegalArgumentException("constructors cannot be static");
        }
        if (!constructor && name.equals("<init>")) {
            throw new IllegalArgumentException("only constructor members may use <init>");
        }
        exportId = Objects.requireNonNull(exportId, "exportId");
        sourceName = Objects.requireNonNull(sourceName, "sourceName");
        canonicalLyraContract = Objects.requireNonNull(canonicalLyraContract, "canonicalLyraContract");
        signature = Objects.requireNonNull(signature, "signature");
        valueType = Objects.requireNonNull(valueType, "valueType");
        if (componentIndex < -1) {
            throw new IllegalArgumentException("member component index must not be below -1");
        }
        if (kind.isField() && valueType.isPresent()) {
            requireFieldValueContext(kind, valueType.orElseThrow());
            JvmTypePlan plannedValue = valueType.orElseThrow();
            if (componentIndex < 0) {
                throw new IllegalArgumentException("field component index does not match its value plan");
            }
            String expected;
            if (kind == GeneratedMemberKind.TUPLE_FIELD || kind == GeneratedMemberKind.NOMINAL_FIELD) {
                if (!plannedValue.isSingleValue()) {
                    throw new IllegalArgumentException("tuple fields cannot use split value plans");
                }
                expected = plannedValue.descriptor();
            } else {
                if (componentIndex >= plannedValue.physicalComponents().size()) {
                    throw new IllegalArgumentException("field component index does not match its value plan");
                }
                expected = plannedValue.physicalComponents().get(componentIndex).descriptor();
            }
            if (!descriptor.equals(expected)) {
                throw new IllegalArgumentException("field descriptor does not match its value component");
            }
        } else if (kind.isField()) {
            if (componentIndex != -1) {
                throw new IllegalArgumentException("raw fields cannot carry a value component index");
            }
        } else if (componentIndex != -1) {
            throw new IllegalArgumentException("methods cannot carry a value component index");
        }
        if (signature.isPresent() && !descriptor.equals(signature.orElseThrow().descriptor())) {
            throw new IllegalArgumentException("member descriptor does not match its signature");
        }
        if (kind == GeneratedMemberKind.FUNCTION_INVOKE
                || kind == GeneratedMemberKind.FUNCTION_INVOCATION
                || kind == GeneratedMemberKind.CLOSURE_INVOKE
                || kind == GeneratedMemberKind.NOMINAL_MEMBER_DELEGATE_INVOKE) {
            if (signature.isEmpty()) {
                throw new IllegalArgumentException(kind + " needs a Lyra signature plan");
            }
            if (signature.orElseThrow().boundary() != JvmAbiBoundary.JAVA_VISIBLE) {
                throw new IllegalArgumentException(kind + " needs the Java-visible signature boundary");
            }
        } else if (signature.isPresent()) {
            throw new IllegalArgumentException(kind + " cannot carry a method signature plan");
        }
        if (kind == GeneratedMemberKind.VALUE_GETTER
                || kind == GeneratedMemberKind.FUNCTION_VALUE_GETTER
                || kind == GeneratedMemberKind.SETTER
                || kind == GeneratedMemberKind.TUPLE_FIELD
                || kind == GeneratedMemberKind.NOMINAL_FIELD
                || kind == GeneratedMemberKind.CLOSURE_CAPTURE_PRESENCE_FIELD
                || kind == GeneratedMemberKind.CLOSURE_CAPTURE_PAYLOAD_FIELD
                || kind == GeneratedMemberKind.CELL_VALUE_FIELD
                || kind == GeneratedMemberKind.CELL_PRESENCE_FIELD
                || kind == GeneratedMemberKind.STATE_BINDING_FIELD
                || kind == GeneratedMemberKind.STATE_PRESENCE_FIELD
                || kind == GeneratedMemberKind.STATE_PAYLOAD_FIELD) {
            if (valueType.isEmpty()) {
                throw new IllegalArgumentException(kind + " needs a value type plan");
            }
        }
        if (kind.isField() && signature.isPresent()) {
            throw new IllegalArgumentException("fields cannot carry method signatures");
        }
        if (signature.isPresent()) {
            requireCanonicalContract(canonicalLyraContract,
                    signature.orElseThrow().canonicalLyraSignature(), kind);
        }
        if (valueType.isPresent()) {
            requireCanonicalContract(canonicalLyraContract,
                    valueType.orElseThrow().canonicalLyraType(), kind);
        }
        if (kind.isMethod() && valueType.isPresent()
                && kind != GeneratedMemberKind.VALUE_GETTER
                && kind != GeneratedMemberKind.FUNCTION_VALUE_GETTER
                && kind != GeneratedMemberKind.SETTER) {
            // A raw method may still carry a value type only when a factory
            // caller explicitly chooses it; the planner never does so.
            throw new IllegalArgumentException("unexpected value type on generated method: " + kind);
        }
    }

    public static GeneratedMemberPlan method(
            GeneratedMemberKind kind,
            String name,
            JvmSignaturePlan signature,
            boolean staticMember,
            Optional<String> exportId,
            Optional<String> sourceName) {
        Objects.requireNonNull(signature, "signature");
        return new GeneratedMemberPlan(kind, name, signature.descriptor(), staticMember,
                exportId, sourceName, Optional.of(signature.canonicalLyraSignature()),
                Optional.of(signature), Optional.empty(), -1);
    }

    public static GeneratedMemberPlan valueMethod(
            GeneratedMemberKind kind,
            String name,
            JvmTypePlan value,
            boolean staticMember,
            Optional<String> exportId,
            Optional<String> sourceName) {
        Objects.requireNonNull(value, "value");
        if (!value.isSingleValue() || value.descriptor().equals("V")) {
            throw new IllegalArgumentException("Java member values need one non-void JVM descriptor");
        }
        requireValueMethodContext(kind, value);
        String descriptor = switch (kind) {
            case VALUE_GETTER, FUNCTION_VALUE_GETTER -> "()" + value.descriptor();
            case SETTER -> "(" + value.descriptor() + ")V";
            default -> throw new IllegalArgumentException("not a value-method kind: " + kind);
        };
        return new GeneratedMemberPlan(kind, name, descriptor, staticMember, exportId, sourceName,
                Optional.of(value.canonicalLyraType()), Optional.empty(), Optional.of(value), -1);
    }

    public static GeneratedMemberPlan field(
            GeneratedMemberKind kind,
            String name,
            JvmTypePlan value,
            String descriptor,
            int componentIndex,
            Optional<String> exportId,
            Optional<String> sourceName) {
        Objects.requireNonNull(value, "value");
        if (!kind.isField()) {
            throw new IllegalArgumentException("field factory needs a field member kind: " + kind);
        }
        return new GeneratedMemberPlan(kind, name, descriptor, false, exportId, sourceName,
                Optional.of(value.canonicalLyraType()), Optional.empty(), Optional.of(value), componentIndex);
    }

    public static GeneratedMemberPlan rawField(
            GeneratedMemberKind kind,
            String name,
            String descriptor) {
        if (!kind.isField()) {
            throw new IllegalArgumentException("raw field factory needs a field member kind: " + kind);
        }
        return new GeneratedMemberPlan(kind, name, descriptor, false, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), -1);
    }

    public static GeneratedMemberPlan rawMethod(
            GeneratedMemberKind kind,
            String name,
            String descriptor,
            boolean staticMember) {
        if (!kind.isMethod()) {
            throw new IllegalArgumentException("raw method factory needs a method kind: " + kind);
        }
        return new GeneratedMemberPlan(kind, name, descriptor, staticMember, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), -1);
    }

    public String jvmDescriptor() {
        return descriptor;
    }

    public String descriptorString() {
        return descriptor;
    }

    public boolean isStatic() {
        return staticMember;
    }

    public boolean isField() {
        return kind.isField();
    }

    public boolean isMethod() {
        return kind.isMethod();
    }

    /** Whether the later emitter must make this storage member final. */
    public GeneratedMemberVisibility visibility() {
        return switch (kind) {
            case NOMINAL_CONSTRUCTOR, NOMINAL_INITIALIZE, NOMINAL_INITIALIZATION_GET,
                    NOMINAL_GET, NOMINAL_SET, NOMINAL_PUBLIC_GET, NOMINAL_PUBLIC_SET,
                    NOMINAL_STRUCTURAL_EQUAL, NOMINAL_MEMBER_DELEGATE_INVOKE ->
                    GeneratedMemberVisibility.PUBLIC;
            case NOMINAL_MEMBER_DELEGATE_CONSTRUCTOR -> GeneratedMemberVisibility.PACKAGE;
            case FUNCTION_INVOKE, CLOSURE_INVOKE, FUNCTION_INVOCATION,
                    VALUE_GETTER, FUNCTION_VALUE_GETTER, SETTER, FACTORY,
                    FACTORY_WITH_OPTIONS, METADATA, CLOSE, TUPLE_CONSTRUCTOR,
                    FACADE_SESSION_RESULT_GET, FACADE_SESSION_EXECUTE,
                    FACADE_MODULE_STATE, FACADE_VIEW_FACTORY, ATTACHMENT_LIFECYCLE,
                    SESSION_BINDING_GET, SESSION_BINDING_SET, SESSION_TUPLE_COMPONENT_GET ->
                    GeneratedMemberVisibility.PUBLIC;
            case TUPLE_COMPONENT_GET, CELL_CONSTRUCTOR, CELL_GET, CELL_SET,
                    CELL_PRESENCE_GET, CELL_PAYLOAD_GET, CLOSURE_CONSTRUCTOR,
                    STATE_IMPORT_LINK, STATE_COMPONENT_GET, STATE_COMPONENT_SET,
                    STATE_NOMINAL_FACTORY,
                    STATE_CONSTRUCTOR, STATE_AUTHORITY_GET, STATE_CHECK_OPEN,
                    STATE_CLOSE, STATE_FACTORY_CHECK_OPEN, SESSION_RESULT_GET, SESSION_EXECUTE,
                    STATE_SESSION_ACCESSOR, STATE_SESSION_NOMINAL_FACTORY,
                    STATE_MODULE_STATE_LOOKUP, SESSION_SAFE_POINT,
                    ATTACHMENT_SAFE_POINT, STATE_ATTACHMENT_LIFECYCLE -> GeneratedMemberVisibility.PACKAGE;
            default -> GeneratedMemberVisibility.PRIVATE;
        };
    }

    public boolean isPublic() {
        return visibility().isPublic();
    }

    public boolean isPrivate() {
        return visibility().isPrivate();
    }

    public boolean finalMember() {
        return switch (kind) {
            case TUPLE_FIELD, CLOSURE_AUTHORITY_FIELD, CLOSURE_STATE_FIELD,
                    CLOSURE_CAPTURE_FIELD, CLOSURE_CAPTURE_PRESENCE_FIELD,
                    CLOSURE_CAPTURE_PAYLOAD_FIELD,
                    STATE_LIFECYCLE_FIELD, FACADE_STATE_FIELD -> true;
            default -> false;
        };
    }

    public boolean isFinal() {
        return finalMember();
    }

    /** JVM declaration key; a class cannot contain the same name/descriptor twice. */
    public String declarationKey() {
        return name + descriptor;
    }

    private static void validateStaticness(GeneratedMemberKind kind, boolean staticMember) {
        boolean expected = kind == GeneratedMemberKind.FACTORY
                || kind == GeneratedMemberKind.FACTORY_WITH_OPTIONS
                || kind == GeneratedMemberKind.METADATA
                || kind == GeneratedMemberKind.FACADE_VIEW_FACTORY;
        if (staticMember != expected) {
            throw new IllegalArgumentException("generated member has invalid staticness: " + kind);
        }
    }

    private static void requireValueMethodContext(
            GeneratedMemberKind kind, JvmTypePlan value) {
        JvmMappingContext expected = switch (kind) {
            case VALUE_GETTER -> JvmMappingContext.EXPORTED_VALUE;
            case FUNCTION_VALUE_GETTER -> JvmMappingContext.JAVA_FUNCTION_VALUE;
            case SETTER -> value.baseCanonicalLyraType().startsWith("Fn<")
                    ? JvmMappingContext.JAVA_FUNCTION_VALUE
                    : JvmMappingContext.EXPORTED_VALUE;
            default -> throw new IllegalArgumentException("not a value-method kind: " + kind);
        };
        if (!value.context().equals(expected)) {
            throw new IllegalArgumentException("value method context disagrees with member kind: " + kind);
        }
        boolean functionValue = value.baseCanonicalLyraType().startsWith("Fn<");
        if ((kind == GeneratedMemberKind.VALUE_GETTER && functionValue)
                || (kind == GeneratedMemberKind.FUNCTION_VALUE_GETTER && !functionValue)) {
            throw new IllegalArgumentException("value getter kind disagrees with its Lyra type: " + kind);
        }
    }

    private static void requireFieldValueContext(
            GeneratedMemberKind kind, JvmTypePlan value) {
        JvmMappingContext expected = switch (kind) {
            case TUPLE_FIELD -> JvmMappingContext.TUPLE_FIELD;
            case NOMINAL_FIELD -> JvmMappingContext.NOMINAL_FIELD;
            case CELL_VALUE_FIELD, CELL_PRESENCE_FIELD -> JvmMappingContext.INTERNAL_CELL;
            case CLOSURE_CAPTURE_PRESENCE_FIELD, CLOSURE_CAPTURE_PAYLOAD_FIELD ->
                    JvmMappingContext.INTERNAL_CAPTURE;
            case STATE_BINDING_FIELD, STATE_PRESENCE_FIELD, STATE_PAYLOAD_FIELD ->
                    JvmMappingContext.INTERNAL_BINDING;
            default -> null;
        };
        if (expected != null && !value.context().equals(expected)) {
            throw new IllegalArgumentException("field value context disagrees with member kind: " + kind);
        }
    }

    private static void requireCanonicalContract(
            Optional<String> contract, String expected, GeneratedMemberKind kind) {
        if (contract.isEmpty() || !contract.orElseThrow().equals(expected)) {
            throw new IllegalArgumentException("member contract metadata disagrees with " + kind);
        }
    }

    private static boolean isConstructorKind(GeneratedMemberKind kind) {
        return switch (kind) {
            case NOMINAL_CONSTRUCTOR, TUPLE_CONSTRUCTOR, CLOSURE_CONSTRUCTOR, CELL_CONSTRUCTOR,
                    STATE_CONSTRUCTOR, FACADE_CONSTRUCTOR,
                    NOMINAL_MEMBER_DELEGATE_CONSTRUCTOR -> true;
            default -> false;
        };
    }
}
