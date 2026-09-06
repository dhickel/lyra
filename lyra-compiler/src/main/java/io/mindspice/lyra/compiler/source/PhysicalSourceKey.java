package io.mindspice.lyra.compiler.source;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Compile-local physical identity used for source deduplication.
 *
 * <p>Filesystem keys are absolute normalized paths. {@link #from(Path)} uses
 * {@link Path#toRealPath(java.nio.file.LinkOption...)} so symlink aliases of
 * an existing source receive the same key. This identity is intentionally
 * separate from the stable source identity.</p>
 */
public record PhysicalSourceKey(String value, boolean uri) {
    private static final Pattern WINDOWS_ABSOLUTE =
            Pattern.compile("^[A-Za-z]:[/\\\\].*");

    public PhysicalSourceKey {
        value = normalize(value, uri);
    }

    /** Creates a physical key while preserving the kind inferred from its spelling. */
    public PhysicalSourceKey(String value) {
        this(value, isUriSpelling(value));
    }

    public PhysicalSourceKey(Path path) {
        this(Objects.requireNonNull(path, "path").toAbsolutePath().normalize().toString(), false);
    }

    /** Creates a lexical, absolute filesystem key without touching the filesystem. */
    public static PhysicalSourceKey of(Path path) {
        Objects.requireNonNull(path, "path");
        return new PhysicalSourceKey(path.toAbsolutePath().normalize().toString(), false);
    }

    /** Creates a real filesystem key, resolving symlinks and requiring the path to exist. */
    public static PhysicalSourceKey from(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return new PhysicalSourceKey(path.toRealPath().toString(), false);
    }

    /** Creates a physical key supplied by a non-filesystem resolver. */
    public static PhysicalSourceKey uri(URI uri) {
        return new PhysicalSourceKey(SourceId.normalizeUri(uri), true);
    }

    public boolean isUri() {
        return uri;
    }

    public boolean isPath() {
        return !isUri();
    }

    public Path asPath() {
        if (isUri()) {
            throw new IllegalStateException("URI physical key has no filesystem path: " + value);
        }
        return Path.of(value);
    }

    public URI asUri() {
        if (!isUri()) {
            throw new IllegalStateException("path physical key has no URI: " + value);
        }
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            throw new AssertionError("canonical physical URI became invalid", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }

    private static String normalize(String candidate, boolean uri) {
        Objects.requireNonNull(candidate, "value");
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("physical source key must not be empty");
        }
        if (uri) {
            try {
                return SourceId.normalizeUri(new URI(candidate));
            } catch (URISyntaxException | IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid physical source URI: " + candidate, exception);
            }
        }
        if (WINDOWS_ABSOLUTE.matcher(candidate).matches()) {
            return normalizeWindowsPath(candidate);
        }
        return Path.of(candidate).toAbsolutePath().normalize().toString();
    }

    private static boolean isUriSpelling(String candidate) {
        return candidate != null
                && !WINDOWS_ABSOLUTE.matcher(candidate).matches()
                && SourceId.looksLikeUri(candidate);
    }

    private static String normalizeWindowsPath(String candidate) {
        String slashSeparated = candidate.replace('\\', '/');
        String drive = slashSeparated.substring(0, 2);
        java.util.ArrayDeque<String> components = new java.util.ArrayDeque<>();
        for (String component : slashSeparated.substring(2).split("/", -1)) {
            if (component.isEmpty() || component.equals(".")) {
                continue;
            }
            if (component.equals("..")) {
                if (!components.isEmpty()) {
                    components.removeLast();
                }
                continue;
            }
            components.addLast(component);
        }
        return drive + "/" + String.join("/", components);
    }
}
