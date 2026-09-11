package io.mindspice.lyra.compiler.conformance;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.LyraUnit;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.mindspice.lyra.compiler.conformance.LanguageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class LanguageAbiTest {
    record Scalar(String type, Class<?> carrier, Class<?> boxed, Object value) { }

    static List<Scalar> scalars() {
        List<Scalar> scalars = new ArrayList<>();
        Map<Class<?>, Class<?>> boxed = Map.of(byte.class, Byte.class, short.class, Short.class,
                int.class, Integer.class, long.class, Long.class, float.class, Float.class, double.class, Double.class);
        for (var number : NumericModel.values()) scalars.add(new Scalar(number.name(), number.carrier,
                boxed.get(number.carrier), number.boundaries().getLast()));
        scalars.addAll(List.of(new Scalar("Bool", boolean.class, Boolean.class, false),
                new Scalar("Char", char.class, Character.class, '\uD800'),
                new Scalar("String", String.class, String.class, "😀\u0000"),
                new Scalar("Unit", LyraUnit.class, LyraUnit.class, LyraUnit.INSTANCE)));
        return List.copyOf(scalars);
    }

    @TestFactory Stream<DynamicTest> everyPrimitiveInScalarArrayAndNullableAbiPositions() {
        return scalars().stream().map(scalar -> DynamicTest.dynamicTest(scalar.type + "/JVM ABI", () -> {
            String t = scalar.type;
            String source = """
                    let @pub scalar :Fn<%1$s;%1$s> = (=> |x| x)
                    let @pub nullable :Fn<@nil %1$s;@nil %1$s> = (=> |x| x)
                    let @pub array :Fn<Array<%1$s>;Array<%1$s>> = (=> |x| x)
                    let @pub nilArray :Fn<Array<@nil %1$s>;Array<@nil %1$s>> = (=> |x| x)
                    let @pub at :Fn<Array<%1$s>,I32;%1$s> = (=> |x index| x[index])
                    let @pub write :Fn<@mut Array<%1$s>,I32,%1$s;Unit> = (=> |@mut x index value| (x[index] := value))
                    """.formatted(t);
            Object array = Array.newInstance(scalar.carrier, 2);
            Array.set(array, 0, scalar.value); Array.set(array, 1, scalar.value);
            Object nullableArray = Array.newInstance(scalar.boxed, 2);
            Array.set(nullableArray, 1, scalar.value);
            try (var fixture = new Fixture(compile(source))) {
                Class<?> returnType = t.equals("Unit") ? void.class : scalar.carrier;
                assertEquals(MethodType.methodType(returnType, scalar.carrier), fixture.module.export("scalar", "Fn<" + t + ";" + t + ">").methodType());
                assertEquals(t.equals("Unit") ? null : scalar.value, fixture.call("scalar", "Fn<" + t + ";" + t + ">", scalar.value));
                String nilSignature = "Fn<@nil" + t + ";@nil" + t + ">";
                assertEquals(MethodType.methodType(scalar.boxed, scalar.boxed), fixture.module.export("nullable", nilSignature).methodType());
                assertNull(fixture.call("nullable", nilSignature, new Object[]{null}));
                assertEquals(scalar.value, fixture.call("nullable", nilSignature, scalar.value));
                assertSame(array, fixture.call("array", "Fn<Array<" + t + ">;Array<" + t + ">>", array));
                assertSame(nullableArray, fixture.call("nilArray", "Fn<Array<@nil" + t + ">;Array<@nil" + t + ">>", nullableArray));
                assertNull(Array.get(nullableArray, 0));
                assertEquals(scalar.value, Array.get(nullableArray, 1));
                fixture.call("write", "Fn<@mutArray<" + t + ">,I32," + t + ";Unit>", array, 1, scalar.value);
                assertEquals(t.equals("Unit") ? null : scalar.value, fixture.call("at", "Fn<Array<" + t + ">,I32;" + t + ">", array, 1));
                for (int index : new int[]{-1, 2, Integer.MAX_VALUE}) runtimeFailure(assertThrows(Throwable.class,
                        () -> fixture.call("at", "Fn<Array<" + t + ">,I32;" + t + ">", array, index)), "LYR-BOUNDS");
            }
            // Trusted Java retains the live raw array after close, per the explicit ABI contract.
            assertEquals(scalar.value, Array.get(array, 0));
        }));
    }

    // Independent normative domain table: no compiler conversion predicate is used as the oracle.
    private static final Map<String, List<String>> WIDENINGS = Map.of(
            "I8", List.of("I8", "I16", "I32", "I64", "F32", "F64"),
            "I16", List.of("I16", "I32", "I64", "F32", "F64"),
            "I32", List.of("I32", "I64", "F64"), "I64", List.of("I64"),
            "U8", List.of("U8", "U16", "U32", "U64", "I16", "I32", "I64", "F32", "F64"),
            "U16", List.of("U16", "U32", "U64", "I32", "I64", "F32", "F64"),
            "U32", List.of("U32", "U64", "I64", "F64"), "U64", List.of("U64"),
            "F32", List.of("F32", "F64"), "F64", List.of("F64"));

    @TestFactory Stream<DynamicTest> allImplicitNumericConversionPairs() {
        return Stream.of(NumericModel.values()).flatMap(from -> Stream.of(NumericModel.values()).map(to ->
                DynamicTest.dynamicTest(from + " -> " + to + "/implicit contract", () -> {
                    String source = "let @pub run :Fn<" + from + ";" + to + "> = (=> |x| x)";
                    if (WIDENINGS.get(from.name()).contains(to.name())) {
                        try (var fixture = new Fixture(compile(source))) {
                            for (Object value : from.boundaries()) assertEquals(to.convert(from, value),
                                    fixture.call("run", "Fn<" + from + ";" + to + ">", value));
                        }
                    } else {
                        var result = assertInstanceOf(CompileResult.Failure.class,
                                LyraCompiler.compile(CompileRequest.source("case.lyra", source)), source);
                        assertEquals("LYC-TYPE-001", result.diagnostics().getFirst().code().value());
                    }
                })));
    }
}
