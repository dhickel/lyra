package io.mindspice.lyra.editor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Recovery copies never replace source files, and are only restored by an explicit UI action. */
final class RecoveryStore {
    private final Path directory;
    RecoveryStore(Path root) { directory = root.resolve(".lyra/recovery"); }
    void write(Path file, String text) throws IOException {
        Properties values = new Properties();
        values.setProperty("file", file.toString()); values.setProperty("text", text);
        StringWriter output = new StringWriter(); values.store(output, "Unsaved Lyra Editor buffer");
        WorkspaceFiles.atomicWrite(path(file), output.toString().getBytes(StandardCharsets.UTF_8));
    }
    Optional<String> read(Path file) throws IOException {
        Path stored = path(file);
        if (!Files.isRegularFile(stored)) return Optional.empty();
        Properties values = new Properties();
        // Escaped properties can be larger than the UTF-8 source limit.
        try (Reader reader = Files.newBufferedReader(stored, StandardCharsets.UTF_8)) { values.load(reader); }
        return file.toString().equals(values.getProperty("file")) ? Optional.ofNullable(values.getProperty("text")) : Optional.empty();
    }
    void remove(Path file) throws IOException { Files.deleteIfExists(path(file)); }
    private Path path(Path file) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.toString().getBytes(StandardCharsets.UTF_8)));
            return directory.resolve(hash + ".properties");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
