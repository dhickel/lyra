package io.mindspice.lyra.editor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit, portable project settings; opening a directory never executes its contents. */
public record WorkspaceSettings(Path root, List<Path> sourceRoots, Optional<RunTarget> entry) {
    public WorkspaceSettings {
        root = root.toAbsolutePath().normalize();
        sourceRoots = List.copyOf(sourceRoots);
        entry = Objects.requireNonNull(entry);
    }
    public record RunTarget(Path file, String function, String arguments) {
        public RunTarget {
            file = file.toAbsolutePath().normalize();
            Objects.requireNonNull(function);
            Objects.requireNonNull(arguments);
            if (function.isBlank()) throw new IllegalArgumentException("Choose a function.");
        }
        public String invocation() { return "::" + function + "[" + arguments + "]"; }
    }

    public static WorkspaceSettings open(Path directory) throws IOException {
        Path root = directory.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Choose a directory: " + root);
        Path config = root.resolve(".lyra/editor.properties");
        Properties properties = new Properties();
        if (Files.exists(config)) properties.load(new StringReader(WorkspaceFiles.decode(WorkspaceFiles.readBytes(config))));
        String roots = properties.getProperty("source.roots", ".");
        List<Path> paths = new ArrayList<>();
        for (String line : roots.split("\n")) {
            if (!line.isBlank()) {
                Path path = root.resolve(line.strip()).normalize();
                if (!Files.isDirectory(path)) throw new IOException("Source root does not exist: " + path);
                paths.add(path.toRealPath());
            }
        }
        if (paths.isEmpty()) paths.add(root);
        Optional<RunTarget> target = Optional.empty();
        if (properties.containsKey("entry.file")) {
            target = Optional.of(new RunTarget(root.resolve(properties.getProperty("entry.file")),
                    properties.getProperty("entry.function", "main"), properties.getProperty("entry.arguments", "Array<String>[]")));
        }
        return new WorkspaceSettings(root, paths.stream().distinct().toList(), target);
    }

    public WorkspaceSettings withEntry(RunTarget value) { return new WorkspaceSettings(root, sourceRoots, Optional.of(value)); }
    public WorkspaceSettings withRoots(List<Path> value) { return new WorkspaceSettings(root, value, entry); }
    public void save() throws IOException {
        Properties values = new Properties();
        values.setProperty("source.roots", String.join("\n", sourceRoots.stream().map(this::portable).toList()));
        entry.ifPresent(target -> {
            values.setProperty("entry.file", portable(target.file()));
            values.setProperty("entry.function", target.function());
            values.setProperty("entry.arguments", target.arguments());
        });
        StringWriter output = new StringWriter();
        values.store(output, "Lyra Editor project settings");
        WorkspaceFiles.atomicWrite(root.resolve(".lyra/editor.properties"), output.toString().getBytes(StandardCharsets.UTF_8));
    }
    private String portable(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root) ? root.relativize(normalized).toString().replace('\\', '/') : normalized.toString();
    }
}
