package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PersistentImportTest {
    @Test
    void importedModuleInitializesOnceAndKeepsLiveStateAcrossSubmissions() {
        var source = ResolvedSource.memory("counter", URI.create("memory://counter.lyra"),
                "import std->io io->::println[\"init\"]\n"
                        + "let @pub @mut count :I32 = 1\n"
                        + "let @pub bump :Fn<;I32> = (=> || { count := (+ count 1) count })");
        var output = new ByteArrayOutputStream();
        var resolverCalls = new AtomicInteger();
        var options = SessionOptions.builder()
                .resolver(logical -> {
                    resolverCalls.incrementAndGet();
                    return logical.equals(source.logicalModule()) ? java.util.Optional.of(source)
                            : java.util.Optional.empty();
                })
                .ioEnvironment(new RuntimeIoEnvironment(java.io.InputStream.nullInputStream(), output, output,
                        StandardCharsets.UTF_8))
                .build();
        try (var session = LyraSession.open(options)) {
            assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("import.lyra", "import counter"));
            var first = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("call.lyra", "counter->::bump[]"));
            assertEquals("2", scalar(first));
            var second = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("call-again.lyra", "counter->::bump[]"));
            assertEquals("3", scalar(second));
            var read = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("read.lyra", "counter->:.count"));
            assertEquals("3", scalar(read));
            assertTrue(session.workspaceState().moduleRevisions().keySet().stream()
                    .anyMatch(value -> value.contains("memory://counter.lyra")));
            assertEquals(1, resolverCalls.get(), "the pinned module must not be rediscovered");
            assertEquals("init" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
        }
    }

    private static String scalar(EvaluationResult.Success result) {
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                result.value().orElseThrow().data()).value();
    }
}
