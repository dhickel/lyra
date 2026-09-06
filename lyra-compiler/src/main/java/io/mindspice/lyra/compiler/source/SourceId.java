package io.mindspice.lyra.compiler.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable source identity.
 *
 * <p>Path identities are source-root-relative POSIX paths. Resolver identities
 * are absolute, fragment-free URIs. Neither form is allowed to retain an
 * absolute checkout path as a stable identity.</p>
 */
public final class SourceId {
    private static final Pattern URI_SCHEME =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*$");
    private static final Pattern WINDOWS_ABSOLUTE =
            Pattern.compile("^[A-Za-z]:[/\\\\].*");

    private final String value;
    private final boolean uri;

    /** Creates an identity from a canonical path or resolver URI string. */
    public SourceId(String value) {
        Objects.requireNonNull(value, "value");
        this.value = normalize(value);
        this.uri = looksLikeUri(value) && !WINDOWS_ABSOLUTE.matcher(value).matches();
    }

    private SourceId(String canonicalValue, boolean uri) {
        this.value = Objects.requireNonNull(canonicalValue, "canonicalValue");
        this.uri = uri;
    }

    /** Creates an identity from a canonical path or resolver URI string. */
    public static SourceId of(String value) {
        return new SourceId(value);
    }

    /** Creates a source-root-relative POSIX path identity. */
    public static SourceId path(String relativePath) {
        return new SourceId(normalizePath(relativePath), false);
    }

    /**
     * Derives a source-root-relative identity without resolving symlinks.
     * Symlink-resolved identity belongs to {@link PhysicalSourceKey}.
     */
    public static SourceId fromPath(Path sourceRoot, Path source) {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(source, "source");

        Path absoluteRoot = sourceRoot.toAbsolutePath().normalize();
        Path absoluteSource = source.isAbsolute()
                ? source.toAbsolutePath().normalize()
                : absoluteRoot.resolve(source).normalize();
        if (!absoluteSource.startsWith(absoluteRoot)) {
            throw new IllegalArgumentException(
                    "source is outside the source root: " + source);
        }
        Path relative = absoluteRoot.relativize(absoluteSource);
        return path(relative.toString());
    }

    /** Creates a resolver-supplied URI identity. */
    public static SourceId uri(URI uri) {
        return new SourceId(normalizeUri(uri), true);
    }

    public String value() {
        return value;
    }

    /** Returns whether this identity is represented by a resolver URI. */
    public boolean isUri() {
        return uri;
    }

    /** Returns whether this identity is represented by a relative POSIX path. */
    public boolean isPath() {
        return !uri;
    }

    /** Converts a path identity to a platform path. */
    public Path asPath() {
        if (isUri()) {
            throw new IllegalStateException("URI source identity has no path: " + value);
        }
        return Path.of(value);
    }

    /** Converts a URI identity to a URI. */
    public URI asUri() {
        if (!isUri()) {
            throw new IllegalStateException("path source identity has no URI: " + value);
        }
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            // The canonical constructor already parsed this value.
            throw new AssertionError("canonical source URI became invalid", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SourceId sourceId)) {
            return false;
        }
        return uri == sourceId.uri && value.equals(sourceId.value);
    }

    @Override
    public int hashCode() {
        return 31 * value.hashCode() + Boolean.hashCode(uri);
    }

    @Override
    public String toString() {
        return value;
    }

    static boolean looksLikeUri(String candidate) {
        return URI_SCHEME.matcher(candidate).matches();
    }

    static String normalizeUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new IllegalArgumentException("source URI must be absolute: " + uri);
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "source URI must not contain a fragment: " + uri);
        }

        URI normalized = uri.normalize();
        String scheme = normalized.getScheme().toLowerCase(Locale.ROOT);
        String text = normalized.toString();
        int colon = text.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("source URI has no scheme: " + uri);
        }
        return scheme + text.substring(colon);
    }

    private static String normalize(String candidate) {
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("source identity must not be empty");
        }
        if (containsControl(candidate)) {
            throw new IllegalArgumentException("source identity contains a control character");
        }
        if (looksLikeUri(candidate) && !WINDOWS_ABSOLUTE.matcher(candidate).matches()) {
            try {
                return normalizeUri(new URI(candidate));
            } catch (URISyntaxException | IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid source URI: " + candidate, exception);
            }
        }
        return normalizePath(candidate);
    }

    private static String normalizePath(String candidate) {
        Objects.requireNonNull(candidate, "candidate");
        String slashSeparated = candidate.replace('\\', '/');
        if (slashSeparated.isEmpty()
                || slashSeparated.startsWith("/")
                || WINDOWS_ABSOLUTE.matcher(slashSeparated).matches()) {
            throw new IllegalArgumentException(
                    "stable source path must be relative: " + candidate);
        }
        if (containsControl(slashSeparated)) {
            throw new IllegalArgumentException("source path contains a control character");
        }

        Deque<String> components = new ArrayDeque<>();
        for (String component : slashSeparated.split("/", -1)) {
            if (component.isEmpty() || component.equals(".")) {
                continue;
            }
            if (component.equals("..")) {
                if (components.isEmpty()) {
                    throw new IllegalArgumentException(
                            "stable source path escapes its source root: " + candidate);
                }
                components.removeLast();
                continue;
            }
            components.addLast(component);
        }
        if (components.isEmpty()) {
            throw new IllegalArgumentException("stable source path must not be empty");
        }
        String normalized = String.join("/", components);
        if (WINDOWS_ABSOLUTE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "stable source path must be relative: " + candidate);
        }
        return normalized;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
