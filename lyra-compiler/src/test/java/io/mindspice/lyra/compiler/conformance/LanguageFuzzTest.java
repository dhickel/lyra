package io.mindspice.lyra.compiler.conformance;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Always selected by ordinary Surefire discovery; bigger campaigns only change the budget. */
class LanguageFuzzTest {
    @TestFactory
    Stream<DynamicTest> reproducibleCompilerAndRuntimeCampaigns() throws Exception {
        String replay = System.getProperty("lyra.fuzz.replay");
        if (replay != null) return Stream.of(DynamicTest.dynamicTest("replay " + replay, () -> {
            Path file = Path.of(replay).toAbsolutePath();
            Path output = Files.createTempDirectory(Path.of("target"), "fuzz-replay-");
            var result = FuzzProcess.run(List.of("--replay", file.toString()), output, Duration.ofSeconds(30), Duration.ofSeconds(60));
            assertTrue(result.passed(), () -> "Replay failed: " + file + "\n" + result.output());
        }));
        int count = integerProperty("lyra.fuzz.cases", 180, LanguageFuzzWorker.MINIMUM_CASES, 1_000_000);
        int seconds = integerProperty("lyra.fuzz.caseTimeoutSeconds", 20, 1, 300);
        int attempts = integerProperty("lyra.fuzz.minimizeAttempts", 12, 0, 1000);
        String seeds = System.getProperty("lyra.fuzz.seeds", "1,24301,8675309,9223372036854775807");
        List<Long> parsed = Arrays.stream(seeds.split(",", -1)).map(String::trim).map(Long::decode).toList();
        assertFalse(parsed.isEmpty(), "At least one seed is required");
        assertEquals(parsed.size(), parsed.stream().distinct().count(), "Duplicate seeds waste the test budget");
        return parsed.stream().map(seed -> DynamicTest.dynamicTest("seed=" + seed + "/cases=" + count, () -> {
            Path root = Path.of("target/language-fuzz").toAbsolutePath();
            Files.createDirectories(root);
            Path directory = Files.createTempDirectory(root, "seed-" + seed + "-");
            var result = FuzzProcess.run(List.of("--campaign", seed.toString(), Integer.toString(count), directory.toString()),
                    directory, Duration.ofSeconds(seconds), Duration.ofSeconds(Math.max(120, (long) count * 2)));
            if (!result.passed()) {
                Path original = directory.resolve("current.properties");
                Path reduced = Files.exists(original) ? FuzzProcess.minimize(original, result, attempts) : original;
                fail("Fuzz " + result.fingerprint() + ", seed=" + seed + "\nOriginal: " + original
                        + "\nReduced: " + reduced + "\nReplay from repository root:\n"
                        + "mvn -pl lyra-compiler -am test -Dtest=LanguageFuzzTest -Dsurefire.failIfNoSpecifiedTests=false"
                        + " -Dlyra.fuzz.replay=" + reduced + "\n" + result.output());
            }
            assertTrue(result.output().contains("FUZZ PASS"), "Worker exited without a completion marker");
            assertTrue(Files.isRegularFile(directory.resolve("summary.txt")), "Worker omitted coverage evidence");
        }));
    }

    static int integerProperty(String name, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " must be " + minimum + ".." + maximum);
        return value;
    }
}
