package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.runtime.ArtifactMetadataReader;
import io.mindspice.lyra.runtime.LyraCompatibilityException;
import io.mindspice.lyra.runtime.LyraRuntimeConstants;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-1 normal metadata encoding compatibility for the current language contract.
 *
 * <p>{@code artifact-v2-normal.json} is the frozen current-version encoding of one fixed
 * source. The retained {@code legacy-artifact-v1-normal.json} fixture predates the
 * source-breaking language contract version 2 and is now incompatible-artifact evidence:
 * it must be rejected with the explicit compatibility diagnostic rather than decoded.</p>
 */
final class LegacySchema1EncodingTest {
    private static final String SOURCE = "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n"
            + "let @pub answer :I32 = 42\n";

    @Test
    void normalMetadataIsByteIdenticalToTheCurrentFixture() throws Exception {
        String current = compileMetadataJson();
        String fixture = new String(getClass().getClassLoader().getResourceAsStream(
                "artifact-v2-normal.json").readAllBytes(), StandardCharsets.UTF_8);
        if (!fixture.equals(current)) {
            System.out.println("FIXTURE: " + fixture);
            System.out.println("CURRENT: " + current);
            throw new AssertionError("normal metadata encoding changed");
        }
    }

    @Test
    void languageContractV1FixtureIsRejectedAsIncompatible() throws Exception {
        byte[] legacy = getClass().getClassLoader().getResourceAsStream(
                "legacy-artifact-v1-normal.json").readAllBytes();
        LyraCompatibilityException failure = assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(legacy));
        assertTrue(failure.getMessage().contains("unsupported language contract version: 1"),
                "unexpected compatibility diagnostic: " + failure.getMessage());
    }

    @Test
    void currentEncodingDeclaresTheVersionTwoLanguageContract() throws Exception {
        assertEquals(2, LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION);
        String current = compileMetadataJson();
        assertTrue(current.startsWith("{\"schemaVersion\":1,\"languageContractVersion\":2,"),
                current.substring(0, Math.min(80, current.length())));
        assertTrue(current.contains("\"runtimeAbi\":{\"major\":1,\"minor\":1}"), current);
        String legacy = new String(getClass().getClassLoader().getResourceAsStream(
                "legacy-artifact-v1-normal.json").readAllBytes(), StandardCharsets.UTF_8);
        assertNotEquals(legacy, current);
    }

    private static String compileMetadataJson() {
        CompileResult result = LyraCompiler.compile(
                CompileRequest.builder().source("fixture-root.lyra", SOURCE).build());
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("compile failed: " + result);
        }
        return success.artifact().metadata().canonicalJson();
    }
}
