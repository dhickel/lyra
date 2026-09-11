package io.mindspice.lyra.compiler.conformance;

import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static io.mindspice.lyra.compiler.conformance.LanguageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class LanguageBuiltinTest {
    @Test void everyIntrinsicExportHasAnExecutedContractAndAnExactInventory() throws Throwable {
        String source = """
                import std->io
                import std->io->{print as p eprintln as err readLine as read}
                let @pub run :Fn<;String> = (=> | | {
                    io->::print["a"] io->::println["😀"]
                    io->::eprint["error:"] io->::eprintln["x"]
                    (p "alias") (err "alias")
                    let first = (read)
                    let second = (read)
                    let third = (read)
                    let eof = (read)
                    (+ (first : "EOF") "|" (second : "EOF") "|" (third : "EOF") "|" (eof : "EOF"))
                })
                """;
        var artifact = compile(source);
        Map<String, String> intrinsics = artifact.metadata().exports().stream()
                .filter(export -> export.moduleId().canonicalSpelling().contains("intrinsic/std/io"))
                .collect(Collectors.toMap(export -> export.name(), export -> export.signature().canonicalSpelling()));
        assertEquals(Map.of("print", "Fn<String;Unit>", "println", "Fn<String;Unit>",
                "eprint", "Fn<String;Unit>", "eprintln", "Fn<String;Unit>", "readLine", "Fn<;@nilString>"), intrinsics,
                "New intrinsic exports require conformance and fuzz coverage");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        try (var fixture = new Fixture(artifact, new LoadOptions(new RuntimeIoEnvironment(
                new ByteArrayInputStream("first\r\n\nlast".getBytes(StandardCharsets.UTF_8)), output, error, StandardCharsets.UTF_8)))) {
            assertEquals("first||last|EOF", fixture.call("run", "Fn<;String>"));
        }
        assertEquals("a😀\nalias", output.toString(StandardCharsets.UTF_8));
        assertEquals("error:x\nalias\n", error.toString(StandardCharsets.UTF_8));
    }
}
