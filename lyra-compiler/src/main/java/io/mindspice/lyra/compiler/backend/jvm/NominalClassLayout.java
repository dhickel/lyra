package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.NominalSchema;
import io.mindspice.lyra.compiler.types.TupleType;

import java.util.List;
import java.util.Objects;

/** Exact typed storage and initialization/access ABI for one nominal declaration. */
record NominalClassLayout(String binaryName, NominalSchema schema, List<Field> fields,
                          JvmSignaturePlan factorySignature) {
    static final String CONSTRUCTION = "Lio/mindspice/lyra/runtime/LyraNominalConstruction;";
    static final String AUTHORITY = "Lio/mindspice/lyra/runtime/LyraClosureAuthority;";

    record Field(int index, NominalSchema.Member member, JvmTypePlan value) {
        Field {
            if (index < 0) throw new IllegalArgumentException("negative nominal field index");
            Objects.requireNonNull(member, "member");
            Objects.requireNonNull(value, "value");
            if (!value.context().equals(JvmMappingContext.NOMINAL_FIELD)
                    || !value.canonicalLyraType().equals(member.type().canonicalSpelling())
                    || !value.isSingleValue() || value.descriptor().equals("V")) {
                throw new IllegalArgumentException("nominal field does not have its exact single-value storage contract");
            }
        }

        String storageName() { return "$lyra$field$" + index; }
        String initializationGetterDescriptor() { return "(" + CONSTRUCTION + ")" + value.descriptor(); }
        String initializationSetterDescriptor() { return "(" + CONSTRUCTION + value.descriptor() + ")V"; }
        String generatedGetterDescriptor() { return "(" + AUTHORITY + ")" + value.descriptor(); }
        String generatedSetterDescriptor() { return "(" + AUTHORITY + value.descriptor() + ")V"; }
        String publicGetterDescriptor() { return "()" + value.descriptor(); }
        String publicSetterDescriptor() { return "(" + value.descriptor() + ")V"; }
    }

    NominalClassLayout {
        JvmNames.requireBinaryName(binaryName, "nominal class name");
        Objects.requireNonNull(schema, "schema");
        fields = List.copyOf(fields);
        Objects.requireNonNull(factorySignature, "factorySignature");
        String expectedSuffix = ".$lyra$nominal$" + schema.type().id().stableHash();
        if (!binaryName.endsWith(expectedSuffix)) throw new IllegalArgumentException("nominal class name has the wrong origin");
        if (fields.size() != schema.members().size()) throw new IllegalArgumentException("incomplete nominal field layout");
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            if (field.index() != index || !field.member().equals(schema.members().get(index))) {
                throw new IllegalArgumentException("nominal layout differs from declaration-order fields");
            }
        }
        var expected = LyraSignature.of(schema.constructorParameters(), schema.type());
        if (!factorySignature.canonicalLyraSignature().equals(expected.canonicalSpelling())
                || factorySignature.boundary() != JvmAbiBoundary.JAVA_VISIBLE
                || !factorySignature.descriptor().endsWith(")L" + binaryName.replace('.', '/') + ";")) {
            throw new IllegalArgumentException("nominal factory differs from its exact constructor contract");
        }
    }

    static NominalClassLayout plan(NominalSchema schema, JvmAbiMapper mapper) {
        var fields = new java.util.ArrayList<Field>();
        for (int index = 0; index < schema.members().size(); index++) {
            var member = schema.members().get(index);
            fields.add(new Field(index, member, mapper.map(member.type(), JvmMappingContext.NOMINAL_FIELD)));
        }
        return new NominalClassLayout(mapper.names().nominalBinaryName(schema.type().canonicalSpelling()), schema, fields,
                mapper.mapSignature(LyraSignature.of(schema.constructorParameters(), schema.type()), JvmAbiBoundary.JAVA_VISIBLE));
    }

    List<GeneratedMemberPlan> members() {
        var result = new java.util.ArrayList<GeneratedMemberPlan>();
        result.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.NOMINAL_CONSTRUCTOR,
                "<init>", "(" + CONSTRUCTION + ")V", false));
        if (schema.kind() == NominalSchema.Kind.STRUCT) {
            result.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.NOMINAL_STRUCTURAL_EQUAL,
                    "$lyra$structuralEquals", "(" + AUTHORITY + "L"
                            + binaryName.replace('.', '/') + ";Lio/mindspice/lyra/runtime/"
                            + "LyraStructuralEquality;)Z", false));
        }
        for (var field : fields) {
            result.add(GeneratedMemberPlan.field(GeneratedMemberKind.NOMINAL_FIELD, field.storageName(),
                    field.value(), field.value().descriptor(), field.index(), java.util.Optional.empty(),
                    java.util.Optional.of(field.member().name())));
            result.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.NOMINAL_INITIALIZE,
                    "$lyra$initialize$" + field.index(), field.initializationSetterDescriptor(), false));
            result.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.NOMINAL_INITIALIZATION_GET,
                    "$lyra$initialization$get$" + field.index(), field.initializationGetterDescriptor(), false));
            result.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.NOMINAL_GET,
                    "$lyra$get$" + field.index(), field.generatedGetterDescriptor(), false));
            if (field.member().mutability().isMutable()) result.add(GeneratedMemberPlan.rawMethod(
                    GeneratedMemberKind.NOMINAL_SET, "$lyra$set$" + field.index(), field.generatedSetterDescriptor(), false));
            if (field.member().publicAccess()) {
                result.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.NOMINAL_PUBLIC_GET,
                        "$lyra$public$get$" + field.index(), field.publicGetterDescriptor(), false));
                if (field.member().mutability().isMutable()) result.add(GeneratedMemberPlan.rawMethod(
                        GeneratedMemberKind.NOMINAL_PUBLIC_SET, "$lyra$public$set$" + field.index(), field.publicSetterDescriptor(), false));
            }
        }
        // Deterministic producer-scoped expected callable signature metadata.
        // One private final field per distinct callable signature the exact
        // member-value authentication traverses; the generated constructor
        // resolves each exactly once from the bound producer authority and
        // every getter/route boundary reads the field instead of resolving
        // or parsing per read.
        for (String canonical : callableFieldSignatureSpellings()) {
            result.add(GeneratedMemberPlan.rawField(
                    GeneratedMemberKind.INSTANCE_SIGNATURE_FIELD,
                    GeneratedTypePlanner.instanceSignatureFieldName(canonical),
                    "Lio/mindspice/lyra/runtime/LyraSignature;",
                    java.util.Optional.of(canonical)));
        }
        return List.copyOf(result);
    }

    /**
     * Exactly the callable signatures {@code authenticateNominalFieldValue}
     * checks on this layout: the function leaf itself plus function leaves in
     * array/tuple positions. Nested nominal/range/primitive leaves and deeper
     * function parameter/return positions are not authenticated and need no
     * metadata.
     */
    private java.util.TreeSet<String> callableFieldSignatureSpellings() {
        java.util.TreeSet<String> signatures = new java.util.TreeSet<>();
        for (Field field : fields) {
            collectCallableFieldLeaves(field.member().type(), signatures);
        }
        return signatures;
    }

    private static void collectCallableFieldLeaves(
            LyraType type, java.util.Set<String> signatures) {
        LyraType base = type.withoutQualifiers();
        if (base instanceof FunctionType function) {
            signatures.add(function.signature().canonicalSpelling());
        } else if (base instanceof ArrayType array) {
            collectCallableFieldLeaves(array.elementType(), signatures);
        } else if (base instanceof TupleType tuple) {
            tuple.memberTypes().forEach(member -> collectCallableFieldLeaves(member, signatures));
        }
    }
}
