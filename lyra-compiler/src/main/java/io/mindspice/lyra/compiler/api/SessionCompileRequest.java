package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.session.SessionRevision;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.SourceConfiguration;
import io.mindspice.lyra.runtime.LyraRuntimeConstants;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable input for compiling one source submission against a session snapshot. */
public final class SessionCompileRequest {
    private final EvaluationSource source;
    private final SessionSnapshot snapshot;
    private final SessionRevision baseRevision;
    private final List<Path> sourceRoots;
    private final List<SourceResolver> resolvers;
    private final String javaBasePackage;
    private final int javaTarget;
    private final boolean previewEnabled;
    private final boolean includeSources;
    private final Map<String, String> semanticOptions;

    private SessionCompileRequest(Builder builder) {
        source = Objects.requireNonNull(builder.source, "source");
        snapshot = Objects.requireNonNull(builder.snapshot, "snapshot");
        baseRevision = Objects.requireNonNull(builder.baseRevision, "baseRevision");
        if (!baseRevision.equals(snapshot.revision())) {
            throw new IllegalArgumentException(
                    "base revision must equal the supplied session snapshot revision");
        }
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
    }

    public SessionCompileRequest(EvaluationSource source, SessionSnapshot snapshot) {
        this(builder().source(source).snapshot(snapshot));
    }

    public SessionCompileRequest(String label, String text, SessionSnapshot snapshot) {
        this(EvaluationSource.of(label, text), snapshot);
    }

    public static Builder builder() {
        return new Builder();
    }

    public EvaluationSource source() {
        return source;
    }

    public SessionSnapshot snapshot() {
        return snapshot;
    }

    public SessionRevision baseRevision() {
        return baseRevision;
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

    public boolean includeSources() {
        return includeSources;
    }

    public Map<String, String> semanticOptions() {
        return semanticOptions;
    }

    public SourceConfiguration sourceConfiguration() {
        return SourceConfiguration.ofPaths(sourceRoots, new ArrayList<>(resolvers), semanticOptions);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SessionCompileRequest request
                && source.equals(request.source)
                && snapshot.equals(request.snapshot)
                && baseRevision.equals(request.baseRevision)
                && sourceRoots.equals(request.sourceRoots)
                && resolvers.equals(request.resolvers)
                && javaBasePackage.equals(request.javaBasePackage)
                && javaTarget == request.javaTarget
                && previewEnabled == request.previewEnabled
                && includeSources == request.includeSources
                && semanticOptions.equals(request.semanticOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, snapshot, baseRevision, sourceRoots, resolvers,
                javaBasePackage, javaTarget, previewEnabled, includeSources, semanticOptions);
    }

    public static final class Builder {
        private EvaluationSource source;
        private SessionSnapshot snapshot = SessionSnapshot.empty();
        private SessionRevision baseRevision = snapshot.revision();
        private boolean baseRevisionSet;
        private List<Path> sourceRoots = List.of();
        private List<SourceResolver> resolvers = List.of();
        private String javaBasePackage = "lyra.generated.session";
        private int javaTarget = LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET;
        private boolean previewEnabled;
        private boolean includeSources;
        private Map<String, String> semanticOptions = Map.of();

        public Builder source(EvaluationSource value) {
            source = Objects.requireNonNull(value, "source");
            return this;
        }

        public Builder source(String label, String text) {
            return source(EvaluationSource.of(label, text));
        }

        public Builder snapshot(SessionSnapshot value) {
            snapshot = Objects.requireNonNull(value, "snapshot");
            if (!baseRevisionSet) {
                baseRevision = snapshot.revision();
            }
            return this;
        }

        public Builder baseRevision(SessionRevision value) {
            baseRevision = Objects.requireNonNull(value, "baseRevision");
            baseRevisionSet = true;
            return this;
        }

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

        public Builder resolvers(List<? extends io.mindspice.lyra.compiler.source.SourceResolver> values) {
            resolvers = copyResolvers(values);
            return this;
        }

        public Builder resolver(io.mindspice.lyra.compiler.source.SourceResolver value) {
            ArrayList<SourceResolver> next = new ArrayList<>(resolvers);
            next.add(adaptResolver(Objects.requireNonNull(value, "resolver")));
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

        public SessionCompileRequest build() {
            return new SessionCompileRequest(this);
        }
    }

    private static List<Path> copyPaths(List<Path> values) {
        Objects.requireNonNull(values, "sourceRoots");
        ArrayList<Path> result = new ArrayList<>(values.size());
        for (Path value : values) {
            result.add(Objects.requireNonNull(value, "sourceRoots must not contain null"));
        }
        return List.copyOf(result);
    }

    private static List<SourceResolver> copyResolvers(
            List<? extends io.mindspice.lyra.compiler.source.SourceResolver> values) {
        Objects.requireNonNull(values, "resolvers");
        ArrayList<SourceResolver> result = new ArrayList<>(values.size());
        for (io.mindspice.lyra.compiler.source.SourceResolver value : values) {
            result.add(adaptResolver(Objects.requireNonNull(value, "resolver")));
        }
        return List.copyOf(result);
    }

    private static SourceResolver adaptResolver(io.mindspice.lyra.compiler.source.SourceResolver value) {
        if (value instanceof SourceResolver resolver) {
            return resolver;
        }
        return value::resolve;
    }

    private static Map<String, String> copyOptions(Map<String, String> values) {
        Objects.requireNonNull(values, "semanticOptions");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "semantic option key");
            String value = Objects.requireNonNull(entry.getValue(), "semantic option value");
            if (key.isBlank() || containsControl(key) || containsControl(value)) {
                throw new IllegalArgumentException("invalid semantic option");
            }
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate semantic option: " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
