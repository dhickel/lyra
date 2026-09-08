package io.mindspice.lyra.repl.remote;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded execution-host module/file listing beneath the configured source
 * roots. This is a fixed read-only lookup: it never compiles, pins,
 * initializes or executes source, and it exposes only relative path names.
 */
public final class RemoteFileCompletion {
    private static final int MAX_LISTED_ENTRIES = RemoteProtocol.MAX_COMPLETION_ITEMS;
    private static final int MAX_WALK_DIRECTORIES = 32;

    private RemoteFileCompletion() {
    }

    public static RemoteCompletion.Result moduleFiles(List<Path> roots, Optional<String> prefix) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(prefix, "prefix");
        String supplied = prefix.orElse("").replace('\\', '/');
        String query = supplied.contains("->")
                ? supplied.replace("->", "/") : supplied;
        if (!isRelativeQuery(query)) {
            return RemoteCompletion.Result.unavailable(
                    "completion prefix must remain beneath the configured source roots");
        }
        List<RemoteCompletion.Item> items = new ArrayList<>();
        for (Path root : roots) {
            if (items.size() >= MAX_LISTED_ENTRIES) {
                break;
            }
            Path normalized = root.toAbsolutePath().normalize();
            try {
                if (!Files.isDirectory(normalized)) {
                    continue;
                }
            } catch (RuntimeException ignored) {
                continue;
            }
            String base = query.contains("/")
                    ? query.substring(0, query.lastIndexOf('/') + 1)
                    : "";
            Path directory = base.isEmpty()
                    ? normalized : normalized.resolve(base).normalize();
            if (!directory.startsWith(normalized)) {
                continue;
            }
            String entryPrefix = query.contains("/")
                    ? query.substring(query.lastIndexOf('/') + 1) : query;
            listDirectory(items, normalized, directory, entryPrefix, base, 0);
        }
        items.sort(Comparator.comparing(RemoteCompletion.Item::name));
        Optional<String> detail = items.size() >= MAX_LISTED_ENTRIES
                ? Optional.of("file listing truncated") : Optional.empty();
        return new RemoteCompletion.Result(RemoteCompletion.Status.OK,
                items, detail);
    }

    private static void listDirectory(
            List<RemoteCompletion.Item> items,
            Path root,
            Path directory,
            String entryPrefix,
            String relativeBase,
            int depth) {
        if (items.size() >= MAX_LISTED_ENTRIES || depth > MAX_WALK_DIRECTORIES
                || !directory.startsWith(root)) {
            return;
        }
        try {
            if (!Files.isDirectory(directory)) {
                return;
            }
        } catch (RuntimeException ignored) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (items.size() >= MAX_LISTED_ENTRIES) {
                    return;
                }
                Path resolved = directory.resolve(entry.getFileName());
                String name = entry.getFileName().toString();
                String relative = relativeBase.isEmpty()
                        ? name : relativeBase + name;
                boolean directoryEntry;
                try {
                    directoryEntry = Files.isDirectory(resolved);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (!name.startsWith(entryPrefix)) {
                    continue;
                }
                if (directoryEntry) {
                    items.add(new RemoteCompletion.Item(relative + "/",
                            RemoteCompletion.ItemKind.DIRECTORY));
                    if (name.equals(entryPrefix) || depth + 1 <= MAX_WALK_DIRECTORIES) {
                        listDirectory(items, root, resolved, "", relative + "/", depth + 1);
                    }
                } else if (name.endsWith(".lyra")) {
                    String moduleName = relative.substring(0, relative.length() - 5)
                            .replace("/", "->");
                    items.add(new RemoteCompletion.Item(moduleName,
                            RemoteCompletion.ItemKind.MODULE));
                    items.add(new RemoteCompletion.Item(relative,
                            RemoteCompletion.ItemKind.FILE));
                } else {
                    items.add(new RemoteCompletion.Item(relative,
                            RemoteCompletion.ItemKind.FILE));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A bounded read-only lookup ignores unreadable directories.
        }
    }

    private static boolean isRelativeQuery(String query) {
        if (!query.isEmpty() && (query.startsWith("/") || query.equals("..")
                || query.startsWith("../") || query.contains("/../")
                || query.endsWith("/.."))) {
            return false;
        }
        try {
            return !Path.of(query).isAbsolute();
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
