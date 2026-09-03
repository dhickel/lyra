package io.mindspice.lyra.compiler.backend.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Complete immutable mapping of one Lyra contract at one ABI position.
 * Descriptor data and Lyra metadata intentionally live side by side.
 */
record JvmTypePlan(
        String canonicalLyraType,
        String baseCanonicalLyraType,
        List<String> qualifierSpellings,
        JvmMappingContext context,
        JvmValueRepresentation representation,
        JvmMaterializationPlan materialization) {
    public JvmTypePlan {
        if (Objects.requireNonNull(canonicalLyraType, "canonicalLyraType").isBlank()) {
            throw new IllegalArgumentException("Lyra type spelling must not be blank");
        }
        if (Objects.requireNonNull(baseCanonicalLyraType, "baseCanonicalLyraType").isBlank()) {
            throw new IllegalArgumentException("base Lyra type spelling must not be blank");
        }
        rejectNonCanonicalCharacters(canonicalLyraType, "canonicalLyraType");
        rejectNonCanonicalCharacters(baseCanonicalLyraType, "baseCanonicalLyraType");
        Objects.requireNonNull(qualifierSpellings, "qualifierSpellings");
        ArrayList<String> qualifiers = new ArrayList<>(qualifierSpellings.size());
        for (String qualifier : qualifierSpellings) {
            Objects.requireNonNull(qualifier, "qualifierSpellings must not contain null");
            if (!qualifier.equals("@mut") && !qualifier.equals("@nil")) {
                throw new IllegalArgumentException("unknown Lyra qualifier: " + qualifier);
            }
            if (qualifiers.contains(qualifier)) {
                throw new IllegalArgumentException("duplicate Lyra qualifier: " + qualifier);
            }
            qualifiers.add(qualifier);
        }
        if (!qualifiers.equals(List.of("@mut", "@nil").stream()
                .filter(qualifiers::contains).toList())) {
            throw new IllegalArgumentException("Lyra qualifiers must be in @mut, @nil order");
        }
        qualifierSpellings = List.copyOf(qualifiers);
        if (baseCanonicalLyraType.startsWith("@mut") || baseCanonicalLyraType.startsWith("@nil")
                || !canonicalLyraType.equals(String.join("", qualifiers) + baseCanonicalLyraType)) {
            throw new IllegalArgumentException("Lyra type spelling disagrees with its base/qualifiers");
        }
        Objects.requireNonNull(context, "context");
        CanonicalType parsed = parseCanonicalType(canonicalLyraType,
                context.permitsMutableQualifier());
        if (!parsed.baseCanonical().equals(baseCanonicalLyraType)
                || !parsed.qualifiers().equals(qualifiers)) {
            throw new IllegalArgumentException("Lyra canonical type parser disagrees with its metadata");
        }
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(materialization, "materialization");
        if (qualifiers.contains("@mut") && !context.permitsMutableQualifier()) {
            throw new IllegalArgumentException("@mut is invalid at " + context.position());
        }
        if (!context.equals(materialization.context())) {
            throw new IllegalArgumentException("materialization context disagrees with type context");
        }
        if (!representation.components().stream().map(JvmType::descriptor).toList()
                .equals(materialization.physicalDescriptors())) {
            throw new IllegalArgumentException("materialization components disagree with representation");
        }
        if (representation.components().stream().anyMatch(JvmType::isVoid)
                && (context.position() != JvmValuePosition.FUNCTION_RETURN
                || materialization.kind() != JvmMaterializationKind.LANGUAGE_UNIT_RETURN_VOID)) {
            throw new IllegalArgumentException("void is legal only for a language function return");
        }
        switch (materialization.kind()) {
            case INTERNAL_PRESENCE_PAYLOAD -> {
                if (!context.permitsInternalPresencePayload()
                        || !qualifiers.contains("@nil") || !baseCanonicalLyraType.matches(
                        "(I8|I16|I32|I64|U8|U16|U32|U64|F32|F64|Bool|Char)")) {
                    throw new IllegalArgumentException("presence-payload materialization needs a nilable primitive");
                }
                if (!(representation instanceof JvmPresencePayload)) {
                    throw new IllegalArgumentException("presence-payload materialization needs split representation");
                }
            }
            case NULLABLE_PRIMITIVE_WRAPPER -> {
                if (!qualifiers.contains("@nil") || !representation.isSingle()
                        || representation.single().kind() != JvmTypeKind.WRAPPER) {
                    throw new IllegalArgumentException("nullable primitive wrapper materialization needs a wrapper");
                }
            }
            case ARRAY_WRAPPER_ELEMENTS, NULLABLE_ARRAY_WRAPPER_ELEMENTS -> {
                boolean nullableArray = materialization.kind()
                        == JvmMaterializationKind.NULLABLE_ARRAY_WRAPPER_ELEMENTS;
                if (!baseCanonicalLyraType.startsWith("Array<")
                        || nullableArray != qualifiers.contains("@nil")
                        || !representation.isSingle()
                        || representation.single().kind() != JvmTypeKind.ARRAY
                        || !isWrapperArrayDescriptor(representation.single().descriptor())) {
                    throw new IllegalArgumentException(
                            "array wrapper materialization needs the matching primitive-wrapper array");
                }
            }
            case NULLABLE_REFERENCE -> {
                if (!qualifiers.contains("@nil") || !representation.isSingle() || !representation.single().isReference()) {
                    throw new IllegalArgumentException("nullable reference materialization needs a reference");
                }
            }
            case UNIT_SINGLETON -> {
                if (qualifiers.contains("@nil") || !baseCanonicalLyraType.equals("Unit")
                        || context.position() == JvmValuePosition.FUNCTION_RETURN
                        || !representation.isSingle()
                        || representation.single().kind() != JvmTypeKind.UNIT) {
                    throw new IllegalArgumentException("Unit singleton materialization needs non-nil Unit");
                }
            }
            case LANGUAGE_UNIT_RETURN_VOID -> {
                if (qualifiers.contains("@nil") || !baseCanonicalLyraType.equals("Unit")
                        || context.position() != JvmValuePosition.FUNCTION_RETURN
                        || !representation.isSingle() || !representation.single().isVoid()) {
                    throw new IllegalArgumentException("Unit void materialization needs a non-nil function return");
                }
            }
            case DIRECT -> {
                if (representation.components().stream().anyMatch(JvmType::isVoid)) {
                    throw new IllegalArgumentException("direct value materialization cannot use void");
                }
            }
        }
        if (representation.components().stream().anyMatch(JvmType::isVoid)
                && !baseCanonicalLyraType.equals("Unit")) {
            throw new IllegalArgumentException("only Unit can map to JVM void");
        }
        validateLogicalMapping(baseCanonicalLyraType, qualifierSpellings, context,
                representation, materialization);
        validatePhysicalMapping(parsed, context, representation);
    }

    public boolean isNilable() {
        return qualifierSpellings.contains("@nil");
    }

    public boolean isMutable() {
        return qualifierSpellings.contains("@mut");
    }

    public boolean isSingleValue() {
        return representation.isSingle();
    }

    public boolean isSplitValue() {
        return !isSingleValue();
    }

    /** Returns the one legal descriptor for a single JVM value. */
    public String descriptor() {
        return representation.descriptor();
    }

    public String jvmDescriptor() {
        return descriptor();
    }

    public String descriptorString() {
        return descriptor();
    }

    public List<String> componentDescriptors() {
        return representation.descriptors();
    }

    public List<String> descriptors() {
        return componentDescriptors();
    }

    public java.util.Optional<String> singleDescriptor() {
        return isSingleValue() ? java.util.Optional.of(descriptor()) : java.util.Optional.empty();
    }

    public List<JvmType> physicalComponents() {
        return representation.components();
    }

    /** Canonical form includes the full Lyra contract and every ABI decision. */
    public String canonicalSpelling() {
        return canonicalLyraType + "|base=" + baseCanonicalLyraType
                + "|qualifiers=" + String.join("", qualifierSpellings)
                + "|context=" + context.canonicalSpelling()
                + "|representation=" + representation.canonicalSpelling()
                + "|materialization=" + materialization.canonicalSpelling();
    }

    public String canonical() {
        return canonicalSpelling();
    }

    @Override
    public String toString() {
        return canonicalSpelling();
    }

    private static void validateLogicalMapping(
            String baseCanonical,
            List<String> qualifiers,
            JvmMappingContext context,
            JvmValueRepresentation representation,
            JvmMaterializationPlan materialization) {
        boolean nilable = qualifiers.contains("@nil");
        switch (baseCanonical) {
            case "I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64",
                    "F32", "F64", "Bool", "Char" -> validatePrimitiveMapping(
                    baseCanonical, nilable, context, representation, materialization);
            case "String" -> {
                validateReferenceMapping(nilable, representation, materialization,
                        "Ljava/lang/String;", JvmTypeKind.REFERENCE);
                if (!nilable) {
                    requireMaterialization(materialization, JvmMaterializationKind.DIRECT,
                            baseCanonical);
                }
            }
            case "Unit" -> validateUnitMapping(baseCanonical, nilable, context,
                    representation, materialization);
            default -> {
                if (baseCanonical.startsWith("Array<")) {
                    String elementCanonical = compositeInner(baseCanonical, "Array<", false);
                    requireSingleKind(representation, JvmTypeKind.ARRAY, "array");
                    boolean nullablePrimitiveElement = isNullablePrimitive(elementCanonical);
                    JvmMaterializationKind expected = nullablePrimitiveElement
                            ? nilable ? JvmMaterializationKind.NULLABLE_ARRAY_WRAPPER_ELEMENTS
                            : JvmMaterializationKind.ARRAY_WRAPPER_ELEMENTS
                            : nilable ? JvmMaterializationKind.NULLABLE_REFERENCE
                            : JvmMaterializationKind.DIRECT;
                    requireMaterialization(materialization, expected, baseCanonical);
                } else if (baseCanonical.startsWith("Tuple<")) {
                    compositeInner(baseCanonical, "Tuple<", false);
                    if (nilable) {
                        validateReferenceMapping(true, representation, materialization,
                                null, JvmTypeKind.TUPLE);
                    } else {
                        requireSingleKind(representation, JvmTypeKind.TUPLE, "tuple");
                        requireMaterialization(materialization, JvmMaterializationKind.DIRECT,
                                baseCanonical);
                    }
                } else if (baseCanonical.startsWith("Fn<")) {
                    String signatureInner = compositeInner(baseCanonical, "Fn<", false);
                    if (!hasTopLevelSeparator(signatureInner, ';')) {
                        throw new IllegalArgumentException("function type has no return separator: "
                                + baseCanonical);
                    }
                    if (nilable) {
                        validateReferenceMapping(true, representation, materialization,
                                null, JvmTypeKind.FUNCTION);
                    } else {
                        requireSingleKind(representation, JvmTypeKind.FUNCTION, "function");
                        requireMaterialization(materialization, JvmMaterializationKind.DIRECT,
                                baseCanonical);
                    }
                } else {
                    throw new IllegalArgumentException("unsupported Lyra base type: "
                            + baseCanonical);
                }
            }
        }
    }

    private static void validatePrimitiveMapping(
            String baseCanonical,
            boolean nilable,
            JvmMappingContext context,
            JvmValueRepresentation representation,
            JvmMaterializationPlan materialization) {
        String descriptor = primitiveDescriptor(baseCanonical);
        if (!nilable) {
            requireSingleDescriptor(representation, descriptor, "non-nil primitive");
            requireMaterialization(materialization, JvmMaterializationKind.DIRECT, baseCanonical);
            return;
        }
        if (representation instanceof JvmPresencePayload payload) {
            if (!materialization.isPresencePayload()
                    || !payload.payload().descriptor().equals(descriptor)) {
                throw new IllegalArgumentException("nullable primitive payload does not match its Lyra type");
            }
            return;
        }
        if (context.permitsInternalPresencePayload()) {
            throw new IllegalArgumentException(
                    "an internal nilable primitive must use presence-payload storage");
        }
        requireSingleDescriptor(representation, wrapperDescriptor(baseCanonical),
                "nullable primitive wrapper");
        requireMaterialization(materialization,
                JvmMaterializationKind.NULLABLE_PRIMITIVE_WRAPPER, baseCanonical);
    }

    private static void validateUnitMapping(
            String baseCanonical,
            boolean nilable,
            JvmMappingContext context,
            JvmValueRepresentation representation,
            JvmMaterializationPlan materialization) {
        if (!nilable && context.position().isFunctionReturn()) {
            requireSingleDescriptor(representation, "V", "language Unit return");
            requireMaterialization(materialization,
                    JvmMaterializationKind.LANGUAGE_UNIT_RETURN_VOID, baseCanonical);
        } else if (nilable) {
            validateReferenceMapping(true, representation, materialization,
                    null, JvmTypeKind.UNIT);
        } else {
            validateReferenceMapping(false, representation, materialization,
                    null, JvmTypeKind.UNIT);
            requireMaterialization(materialization, JvmMaterializationKind.UNIT_SINGLETON,
                    baseCanonical);
        }
    }

    private static void validateReferenceMapping(
            boolean nilable,
            JvmValueRepresentation representation,
            JvmMaterializationPlan materialization,
            String expectedDescriptor,
            JvmTypeKind expectedKind) {
        requireSingleKind(representation, expectedKind, "reference");
        if (expectedDescriptor != null && !representation.descriptor().equals(expectedDescriptor)) {
            throw new IllegalArgumentException("reference descriptor does not match its Lyra type");
        }
        if (nilable) {
            requireMaterialization(materialization, JvmMaterializationKind.NULLABLE_REFERENCE,
                    expectedDescriptor == null ? expectedKind.name() : expectedDescriptor);
        }
    }

    private static void requireSingleKind(
            JvmValueRepresentation representation, JvmTypeKind expected, String label) {
        if (!representation.isSingle() || representation.single().kind() != expected) {
            throw new IllegalArgumentException(label + " mapping must be one " + expected + " JVM value");
        }
    }

    private static void requireSingleDescriptor(
            JvmValueRepresentation representation, String expected, String label) {
        if (!representation.isSingle() || !representation.descriptor().equals(expected)) {
            throw new IllegalArgumentException(label + " descriptor does not match its Lyra type");
        }
        if (expected.equals("V") && representation.single().kind() != JvmTypeKind.VOID) {
            throw new IllegalArgumentException(label + " must use the JVM void kind");
        }
    }

    private static void requireMaterialization(
            JvmMaterializationPlan materialization,
            JvmMaterializationKind expected,
            String canonicalType) {
        if (materialization.kind() != expected) {
            throw new IllegalArgumentException("expected " + expected
                    + " materialization for " + canonicalType);
        }
    }

    /**
     * Independently validates the descriptor implied by every shape for which
     * the descriptor is knowable without a generated class-name table.  The
     * tuple/function class name is supplied by that table, but primitive,
     * String, Unit, and nested-array components must still be exact here.
     */
    private static void validatePhysicalMapping(
            CanonicalType type,
            JvmMappingContext context,
            JvmValueRepresentation representation) {
        List<String> expected = knownPhysicalDescriptors(type, context);
        if (expected != null && !expected.equals(representation.descriptors())) {
            throw new IllegalArgumentException("JVM descriptor does not match its Lyra type: "
                    + type.canonical());
        }
        if (representation.isSingle()) {
            validateGeneratedReferenceFamilies(type, representation.descriptor());
        } else if (type.shape() != CanonicalShape.PRIMITIVE) {
            throw new IllegalArgumentException(
                    "only a nullable primitive may use split JVM storage");
        }
    }

    private static void validateGeneratedReferenceFamilies(
            CanonicalType type, String descriptor) {
        switch (type.shape()) {
            case ARRAY -> {
                if (!descriptor.startsWith("[")) {
                    throw new IllegalArgumentException("array mapping is not an array descriptor");
                }
                validateGeneratedReferenceFamilies(type.children().getFirst(), descriptor.substring(1));
            }
            case TUPLE -> requireGeneratedReferenceFamily(descriptor, "$lyra$tuple$", "tuple");
            case FUNCTION -> requireGeneratedReferenceFamily(
                    descriptor, "$lyra$fn$", "function");
            case PRIMITIVE -> {
                // Primitive, String, and Unit descriptors are validated by the
                // logical/physical mapping checks above.
            }
        }
    }

    private static void requireGeneratedReferenceFamily(
            String descriptor, String expectedPrefix, String role) {
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
            throw new IllegalArgumentException(role + " mapping must be an object descriptor");
        }
        String internalName = descriptor.substring(1, descriptor.length() - 1);
        String simpleName = internalName.substring(internalName.lastIndexOf('/') + 1);
        if (!simpleName.startsWith(expectedPrefix)
                || simpleName.length() == expectedPrefix.length()) {
            throw new IllegalArgumentException(role + " mapping uses the wrong generated class family");
        }
    }

    /** Returns null only when a generated tuple/function name is required. */
    private static List<String> knownPhysicalDescriptors(
            CanonicalType type,
            JvmMappingContext context) {
        if (type.shape() == CanonicalShape.PRIMITIVE) {
            String base = type.baseCanonical();
            boolean nilable = type.qualifiers().contains("@nil");
            if (base.equals("String")) {
                return List.of("Ljava/lang/String;");
            }
            if (base.equals("Unit")) {
                return List.of(!nilable && context.position().isFunctionReturn()
                        ? "V" : "Lio/mindspice/lyra/runtime/LyraUnit;");
            }
            String primitive = primitiveDescriptor(base);
            if (!nilable) {
                return List.of(primitive);
            }
            if (context.permitsInternalPresencePayload()) {
                return List.of("Z", primitive);
            }
            return List.of(wrapperDescriptor(base));
        }
        if (type.shape() == CanonicalShape.ARRAY) {
            JvmMappingContext elementContext = JvmMappingContext.of(
                    context.boundary(), JvmValuePosition.ARRAY_ELEMENT);
            List<String> element = knownPhysicalDescriptors(type.children().getFirst(), elementContext);
            return element == null ? null : List.of("[" + element.getFirst());
        }
        return null;
    }

    /** Package-private validation used by other Phase-14 plans. */
    static void requireCanonicalTypeSpelling(String spelling, JvmMappingContext context) {
        parseCanonicalType(Objects.requireNonNull(spelling, "spelling"),
                Objects.requireNonNull(context, "context").permitsMutableQualifier());
    }

    /** Package-private validation used by the deterministic generated-name table. */
    static void requireCanonicalCompositeSpelling(String spelling, String expectedPrefix) {
        Objects.requireNonNull(expectedPrefix, "expectedPrefix");
        CanonicalType parsed = parseCanonicalType(Objects.requireNonNull(spelling, "spelling"), false);
        if (!parsed.qualifiers().isEmpty()
                || !parsed.baseCanonical().startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("canonical type is not an unqualified "
                    + expectedPrefix + " type: " + spelling);
        }
    }

    private static CanonicalType parseCanonicalType(String spelling, boolean allowMutable) {
        Objects.requireNonNull(spelling, "spelling");
        CanonicalParser parser = new CanonicalParser(spelling);
        CanonicalType result = parser.parseType(allowMutable);
        if (!parser.atEnd()) {
            throw parser.invalid();
        }
        return result;
    }

    private enum CanonicalShape {
        PRIMITIVE,
        ARRAY,
        TUPLE,
        FUNCTION
    }

    private record CanonicalType(
            String canonical,
            String baseCanonical,
            List<String> qualifiers,
            CanonicalShape shape,
            List<CanonicalType> children) {
        private CanonicalType {
            Objects.requireNonNull(canonical, "canonical");
            Objects.requireNonNull(baseCanonical, "baseCanonical");
            qualifiers = List.copyOf(qualifiers);
            Objects.requireNonNull(shape, "shape");
            children = List.copyOf(children);
        }
    }

    /** Small grammar for the canonical no-whitespace Lyra type spelling. */
    private static final class CanonicalParser {
        private static final List<String> PRIMITIVES = List.of(
                "I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64",
                "F32", "F64", "Bool", "Char", "String", "Unit");

        private final String input;
        private int position;

        private CanonicalParser(String input) {
            this.input = input;
        }

        private CanonicalType parseType(boolean allowMutable) {
            int start = position;
            ArrayList<String> qualifiers = new ArrayList<>(2);
            if (consume("@mut")) {
                if (!allowMutable) {
                    throw invalid();
                }
                qualifiers.add("@mut");
                if (startsWith("@mut")) {
                    throw invalid();
                }
            }
            if (consume("@nil")) {
                qualifiers.add("@nil");
                if (startsWith("@mut") || startsWith("@nil")) {
                    throw invalid();
                }
            }

            int baseStart = position;
            CanonicalShape shape;
            List<CanonicalType> children;
            if (consume("Array<")) {
                shape = CanonicalShape.ARRAY;
                CanonicalType element = parseType(false);
                require('>');
                children = List.of(element);
            } else if (consume("Tuple<")) {
                shape = CanonicalShape.TUPLE;
                ArrayList<CanonicalType> members = new ArrayList<>();
                if (peek('>')) {
                    throw invalid();
                }
                members.add(parseType(false));
                while (consume(",")) {
                    members.add(parseType(false));
                }
                require('>');
                children = members;
            } else if (consume("Fn<")) {
                shape = CanonicalShape.FUNCTION;
                ArrayList<CanonicalType> parameters = new ArrayList<>();
                if (!peek(';')) {
                    parameters.add(parseType(true));
                    while (consume(",")) {
                        parameters.add(parseType(true));
                    }
                }
                require(';');
                parameters.add(parseType(false));
                require('>');
                children = parameters;
            } else {
                String primitive = readName();
                if (!PRIMITIVES.contains(primitive)) {
                    throw invalid();
                }
                position = baseStart + primitive.length();
                shape = CanonicalShape.PRIMITIVE;
                children = List.of();
            }
            String base = input.substring(baseStart, position);
            return new CanonicalType(input.substring(start, position), base,
                    qualifiers, shape, children);
        }

        private String readName() {
            int start = position;
            while (position < input.length()) {
                char character = input.charAt(position);
                if (character == '<' || character == '>' || character == ','
                        || character == ';' || character == '@') {
                    break;
                }
                position++;
            }
            return input.substring(start, position);
        }

        private boolean consume(String value) {
            if (startsWith(value)) {
                position += value.length();
                return true;
            }
            return false;
        }

        private boolean startsWith(String value) {
            return input.startsWith(value, position);
        }

        private boolean peek(char expected) {
            return position < input.length() && input.charAt(position) == expected;
        }

        private void require(char expected) {
            if (!peek(expected)) {
                throw invalid();
            }
            position++;
        }

        private boolean atEnd() {
            return position == input.length();
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("malformed canonical Lyra type: " + input);
        }
    }

    private static boolean isWrapperArrayDescriptor(String descriptor) {
        return descriptor.equals("[Ljava/lang/Byte;")
                || descriptor.equals("[Ljava/lang/Short;")
                || descriptor.equals("[Ljava/lang/Integer;")
                || descriptor.equals("[Ljava/lang/Long;")
                || descriptor.equals("[Ljava/lang/Float;")
                || descriptor.equals("[Ljava/lang/Double;")
                || descriptor.equals("[Ljava/lang/Boolean;")
                || descriptor.equals("[Ljava/lang/Character;");
    }

    private static void rejectNonCanonicalCharacters(String value, String label) {
        if (value.chars().anyMatch(character -> Character.isWhitespace(character)
                || Character.isISOControl(character))) {
            throw new IllegalArgumentException(label + " contains whitespace/control characters");
        }
    }

    private static String compositeInner(String base, String prefix, boolean allowEmpty) {
        if (!base.startsWith(prefix) || !base.endsWith(">")) {
            throw new IllegalArgumentException("malformed Lyra composite type: " + base);
        }
        int depth = 0;
        for (int index = prefix.length() - 1; index < base.length(); index++) {
            char character = base.charAt(index);
            if (character == '<') {
                depth++;
            } else if (character == '>' && --depth < 0) {
                throw new IllegalArgumentException("malformed Lyra composite type: " + base);
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("malformed Lyra composite type: " + base);
        }
        String inner = base.substring(prefix.length(), base.length() - 1);
        if (!allowEmpty && inner.isEmpty()) {
            throw new IllegalArgumentException("empty Lyra composite type: " + base);
        }
        return inner;
    }

    private static boolean hasTopLevelSeparator(String value, char separator) {
        int depth = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '<') {
                depth++;
            } else if (character == '>') {
                depth--;
            } else if (character == separator && depth == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNullablePrimitive(String canonical) {
        if (!canonical.startsWith("@nil")) {
            return false;
        }
        return switch (canonical.substring("@nil".length())) {
            case "I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64",
                    "F32", "F64", "Bool", "Char" -> true;
            default -> false;
        };
    }

    private static String primitiveDescriptor(String spelling) {
        return switch (spelling) {
            case "I8", "U8" -> "B";
            case "I16", "U16" -> "S";
            case "I32", "U32" -> "I";
            case "I64", "U64" -> "J";
            case "F32" -> "F";
            case "F64" -> "D";
            case "Bool" -> "Z";
            case "Char" -> "C";
            default -> throw new IllegalArgumentException("not a primitive Lyra type: " + spelling);
        };
    }

    private static String wrapperDescriptor(String spelling) {
        return switch (spelling) {
            case "I8", "U8" -> "Ljava/lang/Byte;";
            case "I16", "U16" -> "Ljava/lang/Short;";
            case "I32", "U32" -> "Ljava/lang/Integer;";
            case "I64", "U64" -> "Ljava/lang/Long;";
            case "F32" -> "Ljava/lang/Float;";
            case "F64" -> "Ljava/lang/Double;";
            case "Bool" -> "Ljava/lang/Boolean;";
            case "Char" -> "Ljava/lang/Character;";
            default -> throw new IllegalArgumentException("not a nullable primitive: " + spelling);
        };
    }
}
