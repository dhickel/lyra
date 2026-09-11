package io.mindspice.lyra.compiler.conformance;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Exact extended-campaign checkpoints, including inputs and independent numeric/trap oracles. */
class MatchFuzzRegressionTest {
    @TempDir Path temp;

    @TestFactory
    Stream<DynamicTest> bracketMatchPostfixCounterexamples() {
        return Stream.of("seed-1-case-240", "seed-24301-case-470", "seed-8675309-case-990",
                "seed-9223372036854775807-case-280", "seed-42-case-360")
                .map(name -> DynamicTest.dynamicTest(name, () -> {
                    Path replay = Path.of(getClass().getResource(
                            "/language/replays/match-postfix/" + name + ".properties").toURI());
                    FuzzCase test = FuzzCase.read(replay);
                    assertEquals(Files.readString(replay.resolveSibling(name + ".lyra")), test.get("source"));
                    assertEquals("numeric", test.get("mode"));
                    assertEquals(name, "seed-" + test.get("seed") + "-case-" + test.get("index"));
                    // Replay uses the saved expectations, never regenerating source or asking the compiler for an oracle.
                    var result = FuzzProcess.run(List.of("--replay", replay.toString()),
                            temp.resolve(name), Duration.ofSeconds(30), Duration.ofSeconds(60));
                    assertTrue(result.passed(), result::output);
                    assertTrue(result.output().contains("REPLAY PASS"), result::output);
                }));
    }
}
