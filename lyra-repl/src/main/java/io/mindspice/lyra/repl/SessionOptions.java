package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.runtime.LyraRuntimeConstants;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for a standalone Lyra session.
 *
 * <p>The options describe compiler inputs and bounded retained source data.
 * They do not provide an execution hook, a value store, or a replacement for
 * the compiler/runtime ABI.</p>
 */
public final class SessionOptions {
    public static final int DEFAULT_MAX_SOURCE_RECORDS = 256;
    public static final int DEFAULT_MAX_SOURCE_CHARACTERS = 1024 * 1024;
    public static final SessionOptions DEFAULT = builder().build();

    private final List<Path> sourceRoots;
    private final List<SourceResolver> resolvers;
    private final String javaBasePackage;
    private final int javaTarget;
    private final boolean previewEnabled;
    private final boolean includeSources;
    private final Map<String, String> semanticOptions;
    private final SnapshotLimits snapshotLimits;
    private final int maxSourceRecords;
    private final int maxSourceCharacters;
    private final RuntimeIoEnvironment ioEnvironment;

    private SessionOptions(Builder builder) {
        sourceRoots = copyPaths(builder.sourceRoots);
        resolvers = copyResolvers(builder.resolvers);
        javaBasePackage = text(builder.javaBasePackage, "javaBasePackage");
        javaTarget = builder.javaTarget;
        if (javaTarget < 1) {
            throw new IllegalArgumentException("javaTarget must be positive");
        }
        previewEnabled = builder.previewEnabled;
        includeSources = builder.includeSources;
        semanticOptions = copyOptions(builder.semanticOptions);
        snapshotLimits = Objects.requireNonNull(builder.snapshotLimits, "snapshotLimits");
        maxSourceRecords = positive(builder.maxSourceRecords, "maxSourceRecords");
        maxSourceCharacters = positive(builder.maxSourceCharacters, "maxSourceCharacters");
        ioEnvironment = Objects.requireNonNull(builder.ioEnvironment, "ioEnvironment");
    }

    public static SessionOptions defaults() {
        return DEFAULT;
    }

    public static SessionOptions defaultOptions() {
        return defaults();
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Path> sourceRoots() {
        return sourceRoots;
    }

    public List<SourceResolver> resolvers() {
        return resolvers;
    }

    public String javaBasePackage() {
        return javaBasePackage;
    }

    public int javaTarget() {
        return javaTarget;
    }

    public boolean previewEnabled() {
        return previewEnabled;
    }

    public boolean preview() {
        return previewEnabled;
    }

    public boolean includeSources() {
        return includeSources;
    }

    public Map<String, String> semanticOptions() {
        return semanticOptions;
    }

    public Map<String, String> revisionOptions() {
        return semanticOptions;
    }

    public SnapshotLimits snapshotLimits() {
        return snapshotLimits;
    }

    public int maxSourceRecords() {
        return maxSourceRecords;
    }

    public int maxSourceCharacters() {
        return maxSourceCharacters;
    }

    public RuntimeIoEnvironment ioEnvironment() {
        return ioEnvironment;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SessionOptions options
                && sourceRoots.equals(options.sourceRoots)
                && resolvers.equals(options.resolvers)
                && javaBasePackage.equals(options.javaBasePackage)
                && javaTarget == options.javaTarget
                && previewEnabled == options.previewEnabled
                && includeSources == options.includeSources
                && semanticOptions.equals(options.semanticOptions)
                && snapshotLimits.equals(options.snapshotLimits)
                && maxSourceRecords == options.maxSourceRecords
                && maxSourceCharacters == options.maxSourceCharacters
                && ioEnvironment.equals(options.ioEnvironment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceRoots, resolvers, javaBasePackage, javaTarget,
                previewEnabled, includeSources, semanticOptions, snapshotLimits,
                maxSourceRecords, maxSourceCharacters, ioEnvironment);
    }

    @Override
    public String toString() {
        return "SessionOptions[roots=" + sourceRoots.size()
                + ",javaTarget=" + javaTarget
                + ",maxSourceRecords=" + maxSourceRecords
                + ",maxSourceCharacters=" + maxSourceCharacters + "]";
    }

    public static final class Builder {
        private List<Path> sourceRoots = List.of();
        private List<SourceResolver> resolvers = List.of();
        private String javaBasePackage = "lyra.generated.session";
        private int javaTarget = LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET;
        private boolean previewEnabled;
        private boolean includeSources;
        private Map<String, String> semanticOptions = Map.of();
        private SnapshotLimits snapshotLimits = SnapshotLimits.DEFAULT;
        private int maxSourceRecords = DEFAULT_MAX_SOURCE_RECORDS;
        private int maxSourceCharacters = DEFAULT_MAX_SOURCE_CHARACTERS;
        private RuntimeIoEnvironment ioEnvironment = RuntimeIoEnvironment.defaults();

        public Builder sourceRoots(List<Path> values) {
            sourceRoots = copyPaths(values);
            return this;
        }

        public Builder sourceRoot(Path value) {
            ArrayList<Path> next = new ArrayList<>(sourceRoots);
            next.add(Objects.requireNonNull(value, "sourceRoot"));
            sourceRoots = List.copyOf(next);
            return this;
        }

        public Builder resolvers(List<? extends SourceResolver> values) {
            resolvers = copyResolvers(values);
            return this;
        }

        public Builder resolver(SourceResolver value) {
            ArrayList<SourceResolver> next = new ArrayList<>(resolvers);
            next.add(Objects.requireNonNull(value, "resolver"));
            resolvers = List.copyOf(next);
            return this;
        }

        public Builder javaBasePackage(String value) {
            javaBasePackage = text(value, "javaBasePackage");
            return this;
        }

        public Builder javaTarget(int value) {
            javaTarget = value;
            return this;
        }

        public Builder previewEnabled(boolean value) {
            previewEnabled = value;
            return this;
        }

        public Builder preview(boolean value) {
            return previewEnabled(value);
        }

        public Builder includeSources(boolean value) {
            includeSources = value;
            return this;
        }

        public Builder semanticOptions(Map<String, String> values) {
            semanticOptions = copyOptions(values);
            return this;
        }

        public Builder revisionOptions(Map<String, String> values) {
            return semanticOptions(values);
        }

        public Builder snapshotLimits(SnapshotLimits value) {
            snapshotLimits = Objects.requireNonNull(value, "snapshotLimits");
            return this;
        }

        public Builder maxSourceRecords(int value) {
            maxSourceRecords = value;
            return this;
        }

        public Builder maxSourceCharacters(int value) {
            maxSourceCharacters = value;
            return this;
        }

        public Builder ioEnvironment(RuntimeIoEnvironment value) {
            ioEnvironment = Objects.requireNonNull(value, "ioEnvironment");
            return this;
        }

        public SessionOptions build() {
            return new SessionOptions(this);
        }
    }

    private static List<Path> copyPaths(List<Path> values) {
        Objects.requireNonNull(values, "sourceRoots");
        ArrayList<Path> copy = new ArrayList<>(values.size());
        for (Path value : values) {
            copy.add(Objects.requireNonNull(value, "sourceRoots must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static List<SourceResolver> copyResolvers(List<? extends SourceResolver> values) {
        Objects.requireNonNull(values, "resolvers");
        ArrayList<SourceResolver> copy = new ArrayList<>(values.size());
        for (SourceResolver value : values) {
            copy.add(Objects.requireNonNull(value, "resolvers must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static Map<String, String> copyOptions(Map<String, String> values) {
        Objects.requireNonNull(values, "semanticOptions");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "semantic option key");
            String value = Objects.requireNonNull(entry.getValue(), "semantic option value");
            if (key.isBlank() || containsControl(key) || containsControl(value)) {
                throw new IllegalArgumentException("invalid semantic option");
            }
            if (copy.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate semantic option: " + key);
            }
        }
        return Map.copyOf(copy);
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (containsControl(value)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return value;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static int positive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
