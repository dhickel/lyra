package io.mindspice.lyra.editor;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Bounded background traversal which does not follow directory symlinks. */
final class WorkspaceIndex {
    static final Set<String> GENERATED = Set.of(".git", ".lyra", "target", "build", "node_modules", ".idea");
    record SearchHit(Path file, int line, int start, int end, String excerpt) {
        String label(Path root) { return root.relativize(file) + ":" + line + "    " + excerpt.strip(); }
    }
    static List<Path> sources(Path root) throws IOException {
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 64, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                if (Thread.currentThread().isInterrupted() || paths.size() >= 10000) return FileVisitResult.TERMINATE;
                return !dir.equals(root) && GENERATED.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && file.toString().endsWith(".lyra") && attributes.size() <= WorkspaceFiles.MAX_FILE_BYTES) paths.add(file);
                return paths.size() >= 10000 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) { return FileVisitResult.CONTINUE; }
        });
        paths.sort(Comparator.naturalOrder());
        return List.copyOf(paths);
    }
    static List<SearchHit> search(Path root, String query, Map<Path, String> buffers) throws IOException {
        List<SearchHit> results = new ArrayList<>();
        for (Path file : sources(root)) {
            if (Thread.currentThread().isInterrupted()) break;
            String text = buffers.containsKey(file) ? buffers.get(file) : WorkspaceFiles.decode(WorkspaceFiles.readBytes(file));
            for (TextEdits.Match match : TextEdits.matches(text, query, false, false)) {
                int line = TextEdits.lineAt(text, match.start());
                int begin = TextEdits.offsetAt(text, line), end = text.indexOf('\n', begin);
                if (end < 0) end = text.length();
                results.add(new SearchHit(file, line, match.start(), match.end(), text.substring(begin, Math.min(end, begin + 240))));
                if (results.size() >= 1000) return List.copyOf(results);
            }
        }
        return List.copyOf(results);
    }
}
