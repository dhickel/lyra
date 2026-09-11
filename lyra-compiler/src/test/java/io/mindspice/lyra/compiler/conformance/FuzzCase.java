package io.mindspice.lyra.compiler.conformance;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Self-contained replay format. The seed is explanatory; replay never regenerates the input. */
final class FuzzCase {
    final Properties data = new Properties();

    FuzzCase(String mode, String source) {
        put("format", "1"); put("mode", mode); put("source", source);
    }

    String get(String key) { return data.getProperty(key); }
    void put(String key, Object value) { data.setProperty(key, String.valueOf(value)); }

    void save(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("current.lyra"), get("source"), StandardCharsets.UTF_8);
        // The parent watches this file as the per-case deadline heartbeat.
        try (Writer writer = Files.newBufferedWriter(directory.resolve("current.properties"), StandardCharsets.UTF_8)) {
            data.store(writer, "Lyra fuzz reproducer: source, modules, arguments, and independent expectation");
        }
    }

    static FuzzCase read(Path path) throws IOException {
        FuzzCase result = new FuzzCase("", "");
        result.data.clear();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { result.data.load(reader); }
        LanguageTestSupport.equal("1", result.get("format"), "Unsupported fuzz replay format");
        return result;
    }
}
