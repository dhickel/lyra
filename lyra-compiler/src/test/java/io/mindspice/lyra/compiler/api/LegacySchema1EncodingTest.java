package io.mindspice.lyra.compiler.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/** Schema-1 normal metadata encoding compatibility: the frozen pre-Phase-11 fixture must remain byte-identical. */
final class LegacySchema1EncodingTest {
    @Test
    void normalMetadataIsByteIdenticalToTheLegacyFixture() throws Exception {
        CompileResult result = LyraCompiler.compile(CompileRequest.builder()
                .source("fixture-root.lyra", "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n"
                        + "let @pub answer :I32 = 42\n")
                .build());
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("compile failed: " + result);
        }
        String legacy = new String(getClass().getClassLoader().getResourceAsStream(
                "legacy-artifact-v1-normal.json").readAllBytes(), StandardCharsets.UTF_8);
        String current = success.artifact().metadata().canonicalJson();
        if (!legacy.equals(current)) {
            System.out.println("LEGACY: " + legacy);
            System.out.println("CURRENT: " + current);
            throw new AssertionError("normal metadata encoding changed");
        }
    }
}
