package io.mindspice.lyra.compiler.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Readable source fixtures are also the mutation fuzzer's permanent seed corpus. */
public final class LanguageCorpus {
    public record Case(String name, String feature, String outcome, String type, String expected, String source) { }

    private LanguageCorpus() { }

    public static List<Case> read() throws Exception {
        Path root = Path.of(LanguageCorpus.class.getResource("/language/corpus").toURI());
        List<Case> cases = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".lyra")).sorted().toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                String[] header = source.lines().findFirst().orElseThrow().split("\\t", -1);
                if (header.length != 5 || !header[0].startsWith("// ")) {
                    throw new IOException("Expected // feature<TAB>outcome<TAB>type<TAB>expected<TAB>description: " + path);
                }
                if (!List.of("value", "reject", "throws").contains(header[1])) {
                    throw new IOException("Unknown corpus outcome in " + path);
                }
                cases.add(new Case(root.relativize(path) + " — " + header[4], header[0].substring(3),
                        header[1], header[2], header[3], source));
            }
        }
        LanguageTestSupport.require(!cases.isEmpty(), "Empty language corpus");
        return List.copyOf(cases);
    }
}
