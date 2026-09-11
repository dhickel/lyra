package io.mindspice.lyra.compiler.conformance;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.math.BigInteger;
import java.util.stream.Stream;

import static io.mindspice.lyra.compiler.conformance.LanguageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class LanguageIndexTest {
    @TestFactory Stream<DynamicTest> everyIntegerIndexDomainChecksBeforeNarrowingOrMutation() {
        return Stream.of(NumericModel.values()).filter(type -> !type.floating()).map(type -> DynamicTest.dynamicTest(
                type + "/bounds and mutation", () -> {
                    String source = """
                            let @mut data :Array<I32> = Array[10 20 30]
                            let @pub at :Fn<%1$s;I32> = (=> |index| data[index])
                            let @pub text :Fn<%1$s;Char> = (=> |index| "abc"[index])
                            let @pub write :Fn<%1$s;Unit> = (=> |index| (data[index] := 99))
                            let @pub first :Fn<;I32> = (=> | | data[0])
                            """.formatted(type);
                    try (var fixture = new Fixture(compile(source))) {
                        for (Object index : type.boundaries()) {
                            BigInteger mathematical = type.integer(index);
                            if (mathematical.signum() >= 0 && mathematical.compareTo(BigInteger.valueOf(3)) < 0) {
                                int at = mathematical.intValueExact();
                                assertEquals((at + 1) * 10, fixture.call("at", "Fn<" + type + ";I32>", index));
                                assertEquals("abc".charAt(at), fixture.call("text", "Fn<" + type + ";Char>", index));
                            } else {
                                for (String name : new String[]{"at", "text", "write"}) {
                                    String result = name.equals("at") ? "I32" : name.equals("text") ? "Char" : "Unit";
                                    runtimeFailure(assertThrows(Throwable.class,
                                            () -> fixture.call(name, "Fn<" + type + ";" + result + ">", index)), "LYR-BOUNDS");
                                }
                                assertEquals(10, fixture.call("first", "Fn<;I32>"), "Rejected store changed the array");
                            }
                        }
                    }
                }));
    }
}
