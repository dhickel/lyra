package io.mindspice.lyra.runtime;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable path-relative or resolver-supplied URI source identity. */
public final class SourceId implements Comparable<SourceId> {
    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*$");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[/\\\\].*");
    private static final Comparator<SourceId> CANONICAL_ORDER =
            Comparator.comparing(SourceId::kindTag).thenComparing(SourceId::value);

    private final String value;
    private final boolean uri;

    public SourceId(String value) {
        CanonicalJson.requireUtf8(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("source identity must not be empty");
        }
        this.uri = looksLikeUri(value) && !WINDOWS_ABSOLUTE.matcher(value).matches();
        this.value = uri ? normalizeUri(value) : normalizePath(value);
    }

    private SourceId(String value, boolean uri) {
        this.value = Objects.requireNonNull(value, "value");
        this.uri = uri;
    }

    public static SourceId of(String value) {
        return new SourceId(value);
    }

    public static SourceId path(String relativePath) {
        return new SourceId(normalizePath(relativePath), false);
    }

    public static SourceId fromPath(Path sourceRoot, Path source) {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(source, "source");
        Path root = sourceRoot.toAbsolutePath().normalize();
        Path absolute = source.isAbsolute()
                ? source.toAbsolutePath().normalize() : root.resolve(source).normalize();
        if (!absolute.startsWith(root)) {
            throw new IllegalArgumentException("source is outside source root: " + source);
        }
        return path(root.relativize(absolute).toString());
    }

    public static SourceId uri(URI uri) {
        return new SourceId(normalizeUri(Objects.requireNonNull(uri, "uri")), true);
    }

    public String value() {
        return value;
    }

    public boolean isUri() {
        return uri;
    }

    public boolean isPath() {
        return !uri;
    }

    /** Explicit schema tag used with {@link #value()} for canonical identity. */
    public String kindTag() {
        return uri ? "uri" : "path";
    }

    public String canonicalSpelling() {
        return value;
    }

    public String canonical() {
        return value;
    }

    public Path asPath() {
        if (uri) {
            throw new IllegalStateException("URI source identity has no path: " + value);
        }
        return Path.of(value);
    }

    public URI asUri() {
        if (!uri) {
            throw new IllegalStateException("path source identity has no URI: " + value);
        }
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            throw new AssertionError("canonical source URI became invalid", exception);
        }
    }

    @Override
    public int compareTo(SourceId other) {
        return CANONICAL_ORDER.compare(this, Objects.requireNonNull(other, "other"));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SourceId source
                && uri == source.uri && value.equals(source.value);
    }

    @Override
    public int hashCode() {
        return 31 * value.hashCode() + Boolean.hashCode(uri);
    }

    @Override
    public String toString() {
        return value;
    }

    private static boolean looksLikeUri(String value) {
        return URI_SCHEME.matcher(value).matches();
    }

    private static String normalizeUri(String value) {
        try {
            return normalizeUri(new URI(value));
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid source URI: " + value, exception);
        }
    }

    private static String normalizeUri(URI uri) {
        if (!uri.isAbsolute() || uri.getScheme() == null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("source URI must be absolute and fragment-free: " + uri);
        }
        URI normalized = uri.normalize();
        String text = CanonicalJson.requireUtf8(normalized.toString(), "source URI");
        int colon = text.indexOf(':');
        return normalized.getScheme().toLowerCase(Locale.ROOT) + text.substring(colon);
    }

    private static String normalizePath(String value) {
        Objects.requireNonNull(value, "relativePath");
        String slash = value.replace('\\', '/');
        if (slash.isEmpty() || slash.startsWith("/") || WINDOWS_ABSOLUTE.matcher(slash).matches()) {
            throw new IllegalArgumentException("stable source path must be relative: " + value);
        }
        for (int index = 0; index < slash.length(); index++) {
            if (Character.isISOControl(slash.charAt(index))) {
                throw new IllegalArgumentException("source path contains a control character");
            }
        }
        Deque<String> components = new ArrayDeque<>();
        for (String component : slash.split("/", -1)) {
            if (component.isEmpty() || component.equals(".")) {
                continue;
            }
            if (component.equals("..")) {
                if (components.isEmpty()) {
                    throw new IllegalArgumentException("source path escapes its source root: " + value);
                }
                components.removeLast();
            } else {
                components.addLast(component);
            }
        }
        if (components.isEmpty()) {
            throw new IllegalArgumentException("stable source path must not be empty");
        }
        String normalized = String.join("/", components);
        if (WINDOWS_ABSOLUTE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("stable source path must be relative: " + value);
        }
        return normalized;
    }
}
