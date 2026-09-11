package io.mindspice.lyra.editor;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Watches source directories without following directory links or generated build trees. */
final class WorkspaceWatcher implements AutoCloseable {
    private final WatchService watcher;
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final Consumer<Set<Path>> changed;
    WorkspaceWatcher(Path root, Consumer<Set<Path>> changed) throws IOException {
        watcher = root.getFileSystem().newWatchService(); this.changed = changed;
        try { register(root); }
        catch (IOException failure) { watcher.close(); throw failure; }
        Thread.ofPlatform().daemon().name("lyra-editor-file-watch").start(this::watch);
    }
    private void register(Path root) throws IOException {
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 64, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if (directories.size() >= 4096) return FileVisitResult.TERMINATE;
                if (!path.equals(root) && WorkspaceIndex.GENERATED.contains(path.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                directories.put(path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY), path);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException failure) { return FileVisitResult.CONTINUE; }
        });
    }
    private void watch() {
        Set<Path> pending = new HashSet<>();
        try {
            while (true) {
                WatchKey key = watcher.poll(250, TimeUnit.MILLISECONDS);
                if (key == null) { if (!pending.isEmpty()) { changed.accept(Set.copyOf(pending)); pending.clear(); } continue; }
                Path directory = directories.get(key);
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (directory == null) continue;
                    if (!(event.context() instanceof Path relative)) { pending.add(directory); continue; }
                    Path path = directory.resolve(relative);
                    if (WorkspaceIndex.GENERATED.contains(path.getFileName().toString()) || path.getFileName().toString().startsWith(".lyra-save-")) continue;
                    pending.add(path);
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) register(path);
                }
                if (!key.reset()) directories.remove(key);
                if (pending.size() > 1000) { changed.accept(Set.copyOf(pending)); pending.clear(); }
            }
        } catch (ClosedWatchServiceException ignored) {
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();
        } catch (IOException failure) { changed.accept(Set.copyOf(pending)); }
    }
    @Override public void close() throws IOException { watcher.close(); }
}
