package io.mindspice.lyra.compiler.backend.jvm;

import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.QualifiedType;
import io.mindspice.lyra.compiler.types.TupleType;
import io.mindspice.lyra.compiler.types.TypeQualifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Assertion-grade JVM-independent ABI mapping coverage for Phase 14. */
public final class JvmAbiMapperTest {
    private static final String UNIT_DESCRIPTOR =
            "Lio/mindspice/lyra/runtime/LyraUnit;";

    @Test
    void everyScalarAndUnsignedTypeUsesTheExactRawDescriptor() {
        JvmAbiMapper mapper = new JvmAbiMapper();
        Map<PrimitiveType, String> descriptors = Map.ofEntries(
                Map.entry(PrimitiveType.I8, "B"), Map.entry(PrimitiveType.I16, "S"),
                Map.entry(PrimitiveType.I32, "I"), Map.entry(PrimitiveType.I64, "J"),
                Map.entry(PrimitiveType.U8, "B"), Map.entry(PrimitiveType.U16, "S"),
                Map.entry(PrimitiveType.U32, "I"), Map.entry(PrimitiveType.U64, "J"),
                Map.entry(PrimitiveType.F32, "F"), Map.entry(PrimitiveType.F64, "D"),
                Map.entry(PrimitiveType.BOOL, "Z"), Map.entry(PrimitiveType.CHAR, "C"),
                Map.entry(PrimitiveType.STRING, "Ljava/lang/String;"),
                Map.entry(PrimitiveType.UNIT, UNIT_DESCRIPTOR));

        descriptors.forEach((type, descriptor) -> {
            JvmTypePlan plan = mapper.map(type, JvmMappingContext.INTERNAL_VALUE);
            assertEquals(descriptor, plan.descriptor(), type.toString());
            assertEquals(type.canonicalSpelling(), plan.canonicalLyraType());
            assertFalse(plan.isNilable());
        });
        assertEquals("B", mapper.map(PrimitiveType.U8, JvmMappingContext.JAVA_VALUE).descriptor());
        assertEquals("I", mapper.map(PrimitiveType.U32, JvmMappingContext.JAVA_VALUE).descriptor());
        Map<PrimitiveType, String> nullableWrappers = Map.ofEntries(
                Map.entry(PrimitiveType.I8, "Ljava/lang/Byte;"),
                Map.entry(PrimitiveType.U8, "Ljava/lang/Byte;"),
                Map.entry(PrimitiveType.I16, "Ljava/lang/Short;"),
                Map.entry(PrimitiveType.U16, "Ljava/lang/Short;"),
                Map.entry(PrimitiveType.I32, "Ljava/lang/Integer;"),
                Map.entry(PrimitiveType.U32, "Ljava/lang/Integer;"),
                Map.entry(PrimitiveType.I64, "Ljava/lang/Long;"),
                Map.entry(PrimitiveType.U64, "Ljava/lang/Long;"),
                Map.entry(PrimitiveType.F32, "Ljava/lang/Float;"),
                Map.entry(PrimitiveType.F64, "Ljava/lang/Double;"),
                Map.entry(PrimitiveType.BOOL, "Ljava/lang/Boolean;"),
                Map.entry(PrimitiveType.CHAR, "Ljava/lang/Character;"));
        nullableWrappers.forEach((type, wrapper) -> {
            JvmTypePlan exported = mapper.map(QualifiedType.nilable(type),
                    JvmMappingContext.EXPORTED_VALUE);
            assertEquals(wrapper, exported.descriptor(), type.toString());
            assertEquals(List.of("Z", descriptors.get(type)),
                    mapper.map(QualifiedType.nilable(type), JvmMappingContext.INTERNAL_VALUE)
                            .componentDescriptors());
        });
        assertEquals("V", mapper.map(PrimitiveType.UNIT,
                JvmMappingContext.INTERNAL_RETURN).descriptor());
        assertEquals(UNIT_DESCRIPTOR, mapper.map(PrimitiveType.UNIT,
                JvmMappingContext.INTERNAL_PARAMETER).descriptor());
        assertEquals(JvmMaterializationKind.UNIT_SINGLETON,
                mapper.map(PrimitiveType.UNIT, JvmMappingContext.INTERNAL_VALUE)
                        .materialization().kind());
    }

    @Test
    void nilabilityAndQualifierContextAreNeverInferredFromTheDescriptor() {
        JvmAbiMapper mapper = new JvmAbiMapper();
        var nilI32 = QualifiedType.nilable(PrimitiveType.I32);
        var nilString = QualifiedType.nilable(PrimitiveType.STRING);
        var nilUnit = QualifiedType.nilable(PrimitiveType.UNIT);

        JvmTypePlan internal = mapper.map(nilI32, JvmMappingContext.INTERNAL_VALUE);
        assertTrue(internal.isSplitValue());
        assertEquals(List.of("Z", "I"), internal.componentDescriptors());
        assertEquals(JvmMaterializationKind.INTERNAL_PRESENCE_PAYLOAD,
                internal.materialization().kind());

        JvmTypePlan exported = mapper.map(nilI32, JvmMappingContext.EXPORTED_VALUE);
        assertEquals("Ljava/lang/Integer;", exported.descriptor());
        assertEquals(JvmMaterializationKind.NULLABLE_PRIMITIVE_WRAPPER,
                exported.materialization().kind());
        assertEquals("@nilI32", exported.canonicalLyraType());

        JvmTypePlan capture = mapper.map(nilI32, JvmMappingContext.INTERNAL_CAPTURE);
        assertEquals(List.of("Z", "I"), capture.componentDescriptors());
        assertEquals("Ljava/lang/String;", mapper.map(nilString,
                JvmMappingContext.JAVA_VALUE).descriptor());
        assertEquals(UNIT_DESCRIPTOR, mapper.map(nilUnit,
                JvmMappingContext.JAVA_VALUE).descriptor());
        assertEquals(JvmMaterializationKind.NULLABLE_REFERENCE,
                mapper.map(nilUnit, JvmMappingContext.JAVA_VALUE).materialization().kind());

        var mutableArray = QualifiedType.of(ArrayType.of(PrimitiveType.I32), TypeQualifier.MUT);
        assertEquals("[I", mapper.map(mutableArray, JvmMappingContext.JAVA_PARAMETER).descriptor());
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(mutableArray, JvmMappingContext.EXPORTED_VALUE));
    }

    @Test
    void arraysTuplesAndFunctionsUseConcreteGeneratedReferences() {
        JvmAbiMapper mapper = new JvmAbiMapper("lyra.generated.test");
        assertEquals("[B", mapper.map(ArrayType.of(PrimitiveType.U8),
                JvmMappingContext.JAVA_VALUE).descriptor());
        assertEquals("[Ljava/lang/Integer;", mapper.map(ArrayType.of(
                QualifiedType.nilable(PrimitiveType.I32)), JvmMappingContext.JAVA_VALUE).descriptor());
        assertEquals(JvmMaterializationKind.ARRAY_WRAPPER_ELEMENTS,
                mapper.map(ArrayType.of(QualifiedType.nilable(PrimitiveType.I32)),
                        JvmMappingContext.INTERNAL_VALUE).materialization().kind());
        assertEquals(JvmMaterializationKind.NULLABLE_ARRAY_WRAPPER_ELEMENTS,
                mapper.map(QualifiedType.nilable(ArrayType.of(
                                QualifiedType.nilable(PrimitiveType.I32))),
                        JvmMappingContext.EXPORTED_VALUE).materialization().kind());
        assertEquals("[" + UNIT_DESCRIPTOR,
                mapper.map(ArrayType.of(PrimitiveType.UNIT), JvmMappingContext.JAVA_VALUE).descriptor());

        TupleType tuple = TupleType.of(List.of(PrimitiveType.I32,
                QualifiedType.nilable(PrimitiveType.STRING)));
        JvmTypePlan tuplePlan = mapper.map(tuple, JvmMappingContext.JAVA_VALUE);
        assertTrue(tuplePlan.descriptor().startsWith("Llyra/generated/test/$lyra$tuple$"));
        assertEquals(JvmTypeKind.TUPLE, tuplePlan.representation().single().kind());

        FunctionType function = FunctionType.of(List.of(PrimitiveType.I32),
                QualifiedType.nilable(PrimitiveType.STRING));
        JvmTypePlan functionPlan = mapper.map(function, JvmMappingContext.JAVA_FUNCTION_VALUE);
        assertTrue(functionPlan.descriptor().startsWith("Llyra/generated/test/$lyra$fn$"));
        assertEquals(JvmTypeKind.FUNCTION, functionPlan.representation().single().kind());
    }

    @Test
    void compilerAndRuntimeScalarModelsRemainInParityAtEveryAbiPosition() {
        JvmAbiMapper mapper = new JvmAbiMapper();
        List<JvmMappingContext> contexts = List.of(
                JvmMappingContext.INTERNAL_BINDING,
                JvmMappingContext.INTERNAL_VALUE,
                JvmMappingContext.INTERNAL_PARAMETER,
                JvmMappingContext.INTERNAL_RETURN,
                JvmMappingContext.INTERNAL_CAPTURE,
                JvmMappingContext.INTERNAL_CELL,
                JvmMappingContext.INTERNAL_FUNCTION_VALUE,
                JvmMappingContext.JAVA_VALUE,
                JvmMappingContext.EXPORTED_VALUE,
                JvmMappingContext.JAVA_PARAMETER,
                JvmMappingContext.JAVA_RETURN,
                JvmMappingContext.TUPLE_FIELD,
                JvmMappingContext.JAVA_ARRAY_ELEMENT,
                JvmMappingContext.INTERNAL_ARRAY_ELEMENT,
                JvmMappingContext.JAVA_FUNCTION_VALUE);
        for (PrimitiveType compilerType : PrimitiveType.values()) {
            io.mindspice.lyra.runtime.PrimitiveType runtimeType =
                    io.mindspice.lyra.runtime.PrimitiveType.valueOf(compilerType.name());
            for (JvmMappingContext context : contexts) {
                mapper.requireTypeParity(compilerType, runtimeType, context);
                mapper.requireTypeParity(QualifiedType.nilable(compilerType),
                        io.mindspice.lyra.runtime.QualifiedType.nilable(runtimeType), context);
            }
        }
    }

    @Test
    void signaturesPreserveLyraMetadataAndUseExactMethodDescriptors() {
        JvmAbiMapper mapper = new JvmAbiMapper();
        LyraSignature compiler = LyraSignature.of(List.of(
                QualifiedType.of(ArrayType.of(PrimitiveType.I32), TypeQualifier.MUT),
                QualifiedType.nilable(PrimitiveType.I32)), PrimitiveType.UNIT);
        JvmSignaturePlan plan = mapper.mapSignature(compiler, JvmAbiBoundary.JAVA_VISIBLE);
        assertEquals("([ILjava/lang/Integer;)V", plan.descriptor());
        assertEquals(compiler.canonicalSpelling(), plan.canonicalLyraSignature());
        assertEquals(List.of("[I", "Ljava/lang/Integer;"), plan.parameterDescriptors());
        assertEquals("V", plan.returnValue().descriptor());
        assertFalse(plan.descriptor().contains("Object"));

        io.mindspice.lyra.runtime.LyraSignature runtime =
                io.mindspice.lyra.runtime.LyraSignature.of(List.of(
                        io.mindspice.lyra.runtime.QualifiedType.of(
                                io.mindspice.lyra.runtime.ArrayType.of(
                                        io.mindspice.lyra.runtime.PrimitiveType.I32),
                                io.mindspice.lyra.runtime.TypeQualifier.MUT),
                        io.mindspice.lyra.runtime.QualifiedType.nilable(
                                io.mindspice.lyra.runtime.PrimitiveType.I32)),
                        io.mindspice.lyra.runtime.PrimitiveType.UNIT);
        JvmSignatureParity parity = mapper.compareSignatures(
                compiler, runtime, JvmAbiBoundary.JAVA_VISIBLE);
        assertTrue(parity.matches());
        assertEquals(plan.descriptor(), parity.compilerDescriptor());
        assertEquals(plan.descriptor(), parity.runtimeDescriptor());
        parity.requireMatch();
        io.mindspice.lyra.runtime.LyraSignature unsigned =
                io.mindspice.lyra.runtime.LyraSignature.of(List.of(
                        io.mindspice.lyra.runtime.PrimitiveType.U8),
                        io.mindspice.lyra.runtime.PrimitiveType.I32);
        JvmSignatureParity mismatch = mapper.compareSignatures(
                LyraSignature.of(List.of(PrimitiveType.I8), PrimitiveType.I32),
                unsigned, JvmAbiBoundary.JAVA_VISIBLE);
        assertFalse(mismatch.matches());
        assertTrue(mismatch.differences().contains("canonical Lyra signature differs"));
    }

    @Test
    void floatingNullableUnitAndNestedFunctionPositionsUseTheDeclaredAbi() {
        JvmAbiMapper mapper = new JvmAbiMapper();
        JvmTypePlan nilF32 = mapper.map(QualifiedType.nilable(PrimitiveType.F32),
                JvmMappingContext.INTERNAL_VALUE);
        assertEquals(List.of("Z", "F"), nilF32.componentDescriptors());
        assertEquals("Ljava/lang/Float;", mapper.map(QualifiedType.nilable(PrimitiveType.F32),
                JvmMappingContext.JAVA_PARAMETER).descriptor());
        assertEquals("V", mapper.map(PrimitiveType.UNIT,
                JvmMappingContext.JAVA_RETURN).descriptor());
        assertEquals(UNIT_DESCRIPTOR, mapper.map(QualifiedType.nilable(PrimitiveType.UNIT),
                JvmMappingContext.JAVA_RETURN).descriptor());

        FunctionType nested = FunctionType.of(List.of(
                QualifiedType.of(ArrayType.of(PrimitiveType.I32), TypeQualifier.MUT)),
                QualifiedType.nilable(PrimitiveType.F32));
        JvmSignaturePlan signature = mapper.mapSignature(nested,
                JvmAbiBoundary.JAVA_VISIBLE);
        assertEquals("([I)Ljava/lang/Float;", signature.descriptor());
        assertEquals("Fn<@mutArray<I32>;@nilF32>", signature.canonicalLyraSignature());
        JvmSignaturePlan internal = mapper.mapSignature(nested, JvmAbiBoundary.INTERNAL);
        assertEquals(signature.descriptor(), internal.descriptor());

        FunctionType unit = FunctionType.of(List.of(PrimitiveType.UNIT), PrimitiveType.UNIT);
        assertEquals("(" + UNIT_DESCRIPTOR + ")V",
                mapper.mapSignature(unit, JvmAbiBoundary.JAVA_VISIBLE).descriptor());
    }

    @Test
    void descriptorAndJvmNameValidationRejectsMalformedOrUnrepresentableValues() {
        assertThrows(IllegalArgumentException.class,
                () -> JvmDescriptors.requireTypeDescriptor("["));
        assertThrows(IllegalArgumentException.class,
                () -> JvmDescriptors.requireTypeDescriptor("[V"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmDescriptors.requireTypeDescriptor("Lfoo\\\\bar;"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmDescriptors.requireTypeDescriptor("Lfoo<>;"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmDescriptors.requireTypeDescriptor("[".repeat(256) + "I"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmType.reference("bad/name"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmType.reference("java.lang.class"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmType.reference("java.lang.String", JvmTypeKind.TUPLE));
        assertThrows(IllegalArgumentException.class,
                () -> JvmType.reference("lyra.generated.$lyra$fn$wrong", JvmTypeKind.TUPLE));
        assertThrows(IllegalArgumentException.class,
                () -> JvmType.reference("lyra.generated.$lyra$tuple$wrong", JvmTypeKind.FUNCTION));
        assertThrows(IllegalArgumentException.class,
                () -> JvmType.wrapper("java.lang.Object"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmTypeNameTable.of("lyra.generated",
                        Map.of("Tuple<I32>", "lyra.generated.$lyra$bad-name"), Map.of()));
    }

    @Test
    void mappingModelRejectsForgedDescriptorAndMaterializationSemantics() {
        assertThrows(IllegalArgumentException.class, () -> new JvmMaterializationPlan(
                JvmMaterializationKind.INTERNAL_PRESENCE_PAYLOAD,
                JvmMappingContext.INTERNAL_VALUE, "forged payload", List.of("I", "J")));
        assertThrows(IllegalArgumentException.class, () -> new JvmMaterializationPlan(
                JvmMaterializationKind.LANGUAGE_UNIT_RETURN_VOID,
                JvmMappingContext.INTERNAL_VALUE, "forged return", List.of("V")));
        assertThrows(IllegalArgumentException.class, () -> new JvmMaterializationPlan(
                JvmMaterializationKind.NULLABLE_PRIMITIVE_WRAPPER,
                JvmMappingContext.JAVA_VALUE, "forged wrapper", List.of("Ljava/lang/Object;")));
        assertThrows(IllegalArgumentException.class, () -> new JvmMaterializationPlan(
                JvmMaterializationKind.ARRAY_WRAPPER_ELEMENTS,
                JvmMappingContext.JAVA_VALUE, "forged array", List.of("[I")));
        assertThrows(IllegalArgumentException.class, () -> new JvmMaterializationPlan(
                JvmMaterializationKind.NULLABLE_REFERENCE,
                JvmMappingContext.JAVA_VALUE, "forged wrapper array", List.of(
                        "[Ljava/lang/Integer;")));

        JvmMaterializationPlan direct = new JvmMaterializationPlan(
                JvmMaterializationKind.DIRECT, JvmMappingContext.INTERNAL_VALUE,
                "forged", List.of("J"));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmTypePlan("I32", "I32", List.of(),
                        JvmMappingContext.INTERNAL_VALUE,
                        new JvmSingleValue(JvmType.primitive("J")), direct));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmTypePlan("@nilI32", "I32", List.of("@nil"),
                        JvmMappingContext.JAVA_VALUE,
                        new JvmSingleValue(JvmType.primitive("I")), direct));
        JvmMaterializationPlan arrayDirect = new JvmMaterializationPlan(
                JvmMaterializationKind.DIRECT, JvmMappingContext.INTERNAL_VALUE,
                "array", List.of("[I"));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmTypePlan("Array<", "Array<", List.of(),
                        JvmMappingContext.INTERNAL_VALUE,
                        new JvmSingleValue(JvmType.array("I")), arrayDirect));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmTypePlan("Array<@nilI32>", "Array<@nilI32>", List.of(),
                        JvmMappingContext.INTERNAL_VALUE,
                        new JvmSingleValue(JvmType.array("I")), arrayDirect));
    }

    @Test
    void invalidDescriptorsAndContextsAreRejectedDeterministically() {
        assertThrows(IllegalArgumentException.class,
                () -> JvmDescriptors.requireTypeDescriptor("V"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmDescriptors.requireMethodDescriptor("(V)I"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmDescriptors.requireMethodDescriptor("(I)"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmDescriptors.requireTypeDescriptor("Lbad.name;"));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmMappingContext(JvmAbiBoundary.INTERNAL,
                        JvmValuePosition.EXPORTED_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmMappingContext(JvmAbiBoundary.JAVA_VISIBLE,
                        JvmValuePosition.CAPTURE));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmMappingContext(JvmAbiBoundary.JAVA_VISIBLE,
                        JvmValuePosition.BINDING));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmAbiMapper().map(
                        QualifiedType.of(PrimitiveType.I32, TypeQualifier.MUT),
                        JvmMappingContext.JAVA_VALUE));
    }

    @Test
    void malformedCanonicalTypesAndDescriptorsCannotForgeAbiPlans() {
        JvmMaterializationPlan arrayDirect = new JvmMaterializationPlan(
                JvmMaterializationKind.DIRECT, JvmMappingContext.INTERNAL_VALUE,
                "array", List.of("[I"));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmTypePlan("Array<Unit>", "Array<Unit>", List.of(),
                        JvmMappingContext.INTERNAL_VALUE,
                        new JvmSingleValue(JvmType.array("I")), arrayDirect));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmTypePlan("String", "String", List.of(),
                        JvmMappingContext.INTERNAL_VALUE,
                        new JvmSingleValue(JvmType.reference("java.lang.String")),
                        new JvmMaterializationPlan(JvmMaterializationKind.NULLABLE_REFERENCE,
                                JvmMappingContext.INTERNAL_VALUE, "wrong", List.of("Ljava/lang/String;"))));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmTypePlan("Array<@mutI32>", "Array<@mutI32>", List.of(),
                        JvmMappingContext.INTERNAL_VALUE,
                        new JvmSingleValue(JvmType.array("[I")),
                        new JvmMaterializationPlan(JvmMaterializationKind.DIRECT,
                                JvmMappingContext.INTERNAL_VALUE, "wrong", List.of("[[I"))));
        assertThrows(IllegalArgumentException.class,
                () -> JvmTypeNameTable.of("lyra.generated",
                        Map.of("Tuple<>", "lyra.generated.$lyra$empty"), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmExportId(io.mindspice.lyra.compiler.source.ModuleId.path("x.lyra"),
                        "x", "not-a-Lyra-type"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmDescriptors.requireMethodDescriptor("(" + "I".repeat(256) + ")V"));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmDescriptors.MethodDescriptor(
                        "(" + "I".repeat(256) + ")V",
                        java.util.stream.Stream.generate(() -> "I").limit(256).toList(), "V"));
    }

    @Test
    void everyAggregatePositionPreservesUnitNilabilityAndExactArrayComponents() {
        JvmAbiMapper mapper = new JvmAbiMapper("lyra.generated.matrix");
        TupleType tuple = TupleType.of(List.of(
                PrimitiveType.UNIT,
                QualifiedType.nilable(PrimitiveType.UNIT),
                QualifiedType.nilable(PrimitiveType.U64),
                ArrayType.of(QualifiedType.nilable(PrimitiveType.BOOL)),
                ArrayType.of(TupleType.of(List.of(PrimitiveType.CHAR)))));

        assertEquals(UNIT_DESCRIPTOR,
                mapper.map(PrimitiveType.UNIT, JvmMappingContext.TUPLE_FIELD).descriptor());
        assertEquals(UNIT_DESCRIPTOR,
                mapper.map(QualifiedType.nilable(PrimitiveType.UNIT),
                        JvmMappingContext.INTERNAL_CAPTURE).descriptor());
        assertEquals(List.of("Z", "J"),
                mapper.map(QualifiedType.nilable(PrimitiveType.U64),
                        JvmMappingContext.INTERNAL_CELL).componentDescriptors());
        assertEquals("[Ljava/lang/Boolean;",
                mapper.map(ArrayType.of(QualifiedType.nilable(PrimitiveType.BOOL)),
                        JvmMappingContext.INTERNAL_VALUE).descriptor());
        assertTrue(mapper.map(ArrayType.of(tuple), JvmMappingContext.JAVA_PARAMETER)
                .descriptor().startsWith("[Llyra/generated/matrix/$lyra$tuple$"));

        LyraSignature signature = LyraSignature.of(List.of(
                PrimitiveType.UNIT,
                QualifiedType.nilable(PrimitiveType.UNIT),
                tuple), QualifiedType.nilable(PrimitiveType.UNIT));
        assertEquals("(" + UNIT_DESCRIPTOR + UNIT_DESCRIPTOR
                        + mapper.map(tuple, JvmMappingContext.JAVA_PARAMETER).descriptor() + ")"
                        + UNIT_DESCRIPTOR,
                mapper.mapSignature(signature, JvmAbiBoundary.JAVA_VISIBLE).descriptor());
    }

    @Test
    void instanceMemberDescriptorsReserveTheJvmReceiverSlot() {
        String twoHundredFiftyFourSlots = "(" + "I".repeat(254) + ")V";
        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_SET,
                "$lyra$set", twoHundredFiftyFourSlots, false);
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_SET,
                        "$lyra$set", "(" + "I".repeat(255) + ")V", false));
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_SET,
                        "$lyra$set", "(" + "D".repeat(127) + "I)V", false));
    }

    @Test
    void mappingPlansAreDeeplyImmutableAndCanonicalContextSensitive() {
        JvmTypePlan first = new JvmAbiMapper().map(
                QualifiedType.nilable(PrimitiveType.I16), JvmMappingContext.INTERNAL_VALUE);
        JvmTypePlan second = new JvmAbiMapper().map(
                QualifiedType.nilable(PrimitiveType.I16), JvmMappingContext.INTERNAL_VALUE);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.canonicalSpelling(), second.canonicalSpelling());
        assertThrows(UnsupportedOperationException.class,
                () -> first.componentDescriptors().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> first.physicalComponents().clear());
        assertTrue(first.canonicalSpelling().contains("internal:value"));
        assertTrue(first.canonicalSpelling().contains("@nilI16"));
    }

    @Test
    void internalNullablePrimitivePlansCannotBeReplacedWithBoundaryWrappers() {
        JvmType wrapper = JvmType.wrapper("java.lang.Integer");
        JvmMaterializationPlan materialization = new JvmMaterializationPlan(
                JvmMaterializationKind.NULLABLE_PRIMITIVE_WRAPPER,
                JvmMappingContext.INTERNAL_VALUE, "forged wrapper", List.of(wrapper.descriptor()));
        assertThrows(IllegalArgumentException.class, () -> new JvmTypePlan(
                "@nilI32", "I32", List.of("@nil"), JvmMappingContext.INTERNAL_VALUE,
                new JvmSingleValue(wrapper), materialization));
    }

    @Test
    void aggregateDescriptorsMustUseTheMatchingGeneratedReferenceFamily() {
        String wrongTupleDescriptor = "Llyra/generated/$lyra$fn$wrong;";
        JvmMaterializationPlan direct = new JvmMaterializationPlan(
                JvmMaterializationKind.DIRECT, JvmMappingContext.JAVA_VALUE,
                "forged aggregate", List.of(wrongTupleDescriptor));
        assertThrows(IllegalArgumentException.class, () -> new JvmTypePlan(
                "Tuple<I32>", "Tuple<I32>", List.of(), JvmMappingContext.JAVA_VALUE,
                new JvmSingleValue(JvmType.reference(
                        "lyra.generated.$lyra$fn$wrong", JvmTypeKind.TUPLE)), direct));

        String wrongArrayDescriptor = "[Llyra/generated/$lyra$tuple$wrong;";
        JvmMaterializationPlan array = new JvmMaterializationPlan(
                JvmMaterializationKind.DIRECT, JvmMappingContext.JAVA_VALUE,
                "forged aggregate array", List.of(wrongArrayDescriptor));
        assertThrows(IllegalArgumentException.class, () -> new JvmTypePlan(
                "Array<Fn<;I32>>", "Array<Fn<;I32>>", List.of(),
                JvmMappingContext.JAVA_VALUE,
                new JvmSingleValue(JvmType.array(
                        "Llyra/generated/$lyra$tuple$wrong;")), array));
    }

    @Test
    void functionSignaturesReserveTheInstanceReceiverSlot() {
        List<io.mindspice.lyra.compiler.types.LyraType> allowed =
                java.util.stream.Stream.<io.mindspice.lyra.compiler.types.LyraType>generate(
                        () -> PrimitiveType.I32).limit(254).toList();
        mapperForSignature(allowed);
        List<io.mindspice.lyra.compiler.types.LyraType> tooMany =
                java.util.stream.Stream.<io.mindspice.lyra.compiler.types.LyraType>generate(
                        () -> PrimitiveType.I32).limit(255).toList();
        assertThrows(IllegalArgumentException.class, () -> mapperForSignature(tooMany));
    }

    private static JvmSignaturePlan mapperForSignature(
            List<io.mindspice.lyra.compiler.types.LyraType> parameters) {
        return new JvmAbiMapper().mapSignature(
                LyraSignature.of(parameters, PrimitiveType.UNIT), JvmAbiBoundary.JAVA_VISIBLE);
    }

    @Test
    void Java25RestrictedNamesAreRejectedForBinaryNamesToo() {
        assertThrows(IllegalArgumentException.class,
                () -> JvmType.reference("java.lang.var"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmType.reference("java.lang.module"));
    }
}
