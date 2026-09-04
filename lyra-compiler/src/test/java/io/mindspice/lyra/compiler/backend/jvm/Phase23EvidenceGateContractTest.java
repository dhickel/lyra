package io.mindspice.lyra.compiler.backend.jvm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic contract checks for the non-Maven timing gate. */
public final class Phase23EvidenceGateContractTest {
    @Test
    public void ratifiedThresholdsAreExplicitAndEvaluatorSchemaIsStable() throws Exception {
        Path root = repositoryRoot();
        String thresholds = Files.readString(root.resolve("tools/phase23-gates.json"));
        assertTrue(thresholds.contains("\"coreEquivalentMaximumRatio\": 3.0"));
        assertTrue(thresholds.contains("\"selfTailEquivalentMaximumRatio\": 5.0"));
        assertTrue(thresholds.contains("\"exactHandleEquivalentMaximumRatio\": 15.0"));
        assertTrue(thresholds.contains("\"facadeEquivalentMaximumRatio\": 15.0"));
        assertTrue(thresholds.contains("\"nonAllocatingMaximumBytesPerOperation\": 1.0"));
        assertTrue(thresholds.contains("\"semanticAggregateEquivalentMaximumRatio\": 4.0"));
        assertTrue(thresholds.contains("\"closureMaximumBytesPerOperation\": 2048.0"));
        assertTrue(thresholds.contains("\"expectedFailureMaximumBytesPerOperation\": 4096.0"));
        assertTrue(thresholds.contains("\"maximumEmittedFixtureClasses\": 32"));
        assertTrue(thresholds.contains("\"coldGeneratedLoadAndCallMaximumNanoseconds\": 10000000.0"));

        String evaluator = Files.readString(root.resolve("tools/phase23_gate.py"));
        assertTrue(evaluator.contains("lyra.phase23.gate-result.v1"));
        assertTrue(evaluator.contains("\"allSelectedGatesPass\""));
        assertTrue(evaluator.contains("\"checks\""));
        assertTrue(evaluator.contains("return 1 if args.mode == \"gate\""),
                "gate mode must return failure when a selected threshold fails");
        String runner = Files.readString(root.resolve("tools/phase23-evidence.sh"));
        assertTrue(runner.contains("if (( GATE_EXIT != 0 ))"),
                "the evidence runner must propagate a failed selected gate");
    }

    private static Path repositoryRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        try (Stream<Path> candidates = Stream.iterate(current, path -> path != null, Path::getParent)) {
            return candidates.filter(path -> Files.isRegularFile(path.resolve("tools/phase23-gates.json")))
                    .findFirst()
                    .orElseThrow(() -> new IOException("cannot locate Phase 23 gate configuration from " + current));
        }
    }
}
