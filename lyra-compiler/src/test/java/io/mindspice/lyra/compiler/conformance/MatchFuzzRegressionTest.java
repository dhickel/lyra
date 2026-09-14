package io.mindspice.lyra.compiler.conformance;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact extended-campaign checkpoints, including inputs and independent numeric/trap oracles.
 *
 * <p>The saved pre-contract reproductions are retained byte-for-byte as old-syntax rejection
 * evidence, and each one has a migrated {@code -v2} twin that differs from it only in the
 * {@code source} key. The twins replay through the ordinary saved-expectation harness, so the
 * original counterexamples still exercise exactly the same semantics and results.</p>
 */
class MatchFuzzRegressionTest {
    private static final List<String> NAMES = List.of("seed-1-case-240", "seed-24301-case-470",
            "seed-8675309-case-990", "seed-9223372036854775807-case-280", "seed-42-case-360");

    @TempDir Path temp;

    @TestFactory
    Stream<DynamicTest> savedPreContractReproductionsAreRetainedAsRejectionEvidence() {
        return NAMES.stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            Path replay = replayPath(name);
            FuzzCase test = FuzzCase.read(replay);
            // The saved bytes are preserved exactly, never rewritten into the new syntax.
            assertEquals(Files.readString(replay.resolveSibling(name + ".lyra")), test.get("source"));
            assertTrue(test.get("source").contains("??") || test.get("source").contains("::match["),
                    "the retained reproduction no longer records the pre-contract spelling");
            CompileResult result = LyraCompiler.compile(
                    CompileRequest.source("replay.lyra", test.get("source")));
            CompileResult.Failure failure = assertInstanceOf(CompileResult.Failure.class, result);
            String code = failure.diagnostics().getFirst().code().value();
            assertTrue(code.equals(CompilerDiagnosticCodes.PARSE_OBSOLETE_ARM_MARKER.value())
                            || code.equals(CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM.value()),
                    "unexpected obsolete-form diagnostic: " + failure.diagnostics().getFirst());
        }));
    }

    @TestFactory
    Stream<DynamicTest> migratedTwinsReplayTheSameSavedExpectations() {
        return NAMES.stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            Path replay = replayPath(name + "-v2");
            FuzzCase test = FuzzCase.read(replay);
            assertEquals(Files.readString(replay.resolveSibling(name + "-v2.lyra")), test.get("source"));
            assertFalse(test.get("source").contains("??"));
            assertFalse(test.get("source").contains("::match["));
            assertEquals("numeric", test.get("mode"));
            assertEquals(name, "seed-" + test.get("seed") + "-case-" + test.get("index"));
            assertTrue(expectationsMatch(name), "migrated twin changed a saved expectation");
            // Replay uses the saved expectations, never regenerating source or asking the compiler for an oracle.
            var result = FuzzProcess.run(List.of("--replay", replay.toString()),
                    temp.resolve(name + "-v2"), Duration.ofSeconds(30), Duration.ofSeconds(60));
            assertTrue(result.passed(), result::output);
            assertTrue(result.output().contains("REPLAY PASS"), result::output);
        }));
    }

    /** Every key except the source is identical between the retained reproduction and its twin. */
    private boolean expectationsMatch(String name) throws Exception {
        Properties retained = load(replayPath(name));
        Properties twin = load(replayPath(name + "-v2"));
        twin.remove("source");
        retained.remove("source");
        return retained.equals(twin);
    }

    private static Properties load(Path path) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private Path replayPath(String name) throws Exception {
        return Path.of(getClass().getResource(
                "/language/replays/match-postfix/" + name + ".properties").toURI());
    }
}
