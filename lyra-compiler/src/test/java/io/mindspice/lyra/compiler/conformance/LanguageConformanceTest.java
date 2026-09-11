package io.mindspice.lyra.compiler.conformance;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static io.mindspice.lyra.compiler.conformance.LanguageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Each report entry names a language feature, source fixture, and observable contract. */
class LanguageConformanceTest {
    @TestFactory
    Stream<DynamicTest> currentLanguageCorpus() throws Exception {
        return LanguageCorpus.read().stream().map(test -> DynamicTest.dynamicTest(
                test.feature() + "/" + test.name(), () -> {
                    try {
                        if (test.outcome().equals("reject")) {
                            var failure = assertInstanceOf(CompileResult.Failure.class,
                                    LyraCompiler.compile(CompileRequest.source("case.lyra", test.source())), test.source());
                            diagnostics(failure.diagnostics(), test.source().length());
                            assertEquals(test.expected(), failure.diagnostics().getFirst().code().value(), test.source());
                        } else {
                            try (var fixture = new Fixture(compile(test.source()))) {
                                if (test.outcome().equals("throws")) {
                                    Throwable failure = assertThrows(Throwable.class,
                                            () -> fixture.call("run", "Fn<;" + test.type() + ">"));
                                    runtimeFailure(failure, test.expected());
                                } else {
                                    Object value = fixture.call("run", "Fn<;" + test.type() + ">");
                                    assertEquals(test.expected(), String.valueOf(value), test.source());
                                }
                            }
                        }
                    } catch (Throwable failure) {
                        throw new AssertionError(test.name() + "\n" + test.source(), failure);
                    }
                }));
    }
}
