package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.session.SessionRevision;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.SourceConfiguration;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.runtime.LyraRuntimeConstants;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable input for compiling one source submission against a session snapshot. */
public final class SessionCompileRequest {
    private final EvaluationSource source;
    private final SourceId sourceId;
    private final SessionSnapshot snapshot;
    private final SessionRevision baseRevision;
    private final List<Path> sourceRoots;
    private final List<SourceResolver> resolvers;
    private final String javaBasePackage;
    private final int javaTarget;
    private final boolean previewEnabled;
    private final boolean includeSources;
    private final Map<String, String> semanticOptions;
    private final Optional<LogicalModuleId> reloadModule;
    private final Optional<String> reloadImportAlias;

    private SessionCompileRequest(Builder builder) {
        source = Objects.requireNonNull(builder.source, "source");
        snapshot = Objects.requireNonNull(builder.snapshot, "snapshot");
        sourceId = builder.sourceId != null
                ? builder.sourceId : defaultSourceId(source, snapshot);
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
        reloadModule = Optional.ofNullable(builder.reloadModule);
        reloadImportAlias = Optional.ofNullable(builder.reloadImportAlias);
        if (reloadImportAlias.isPresent() && reloadModule.isEmpty()) {
            throw new IllegalArgumentException("reload import alias requires a reload module");
        }
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

    /** Compiler identity, independent of the passive caller origin. */
    public SourceId sourceId() {
        return sourceId;
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

    /** Logical module whose REPL-owned producer graph is being rebuilt. */
    public Optional<LogicalModuleId> reloadModule() {
        return reloadModule;
    }

    /** Synthetic namespace alias used only to make a reload graph reachable. */
    public Optional<String> reloadImportAlias() {
        return reloadImportAlias;
    }

    public SourceConfiguration sourceConfiguration() {
        Map<String, String> revisionOptions = new LinkedHashMap<>(semanticOptions);
        revisionOptions.put("lyra.execution-profile", "submission-result-1");
        return SourceConfiguration.ofPaths(sourceRoots, new ArrayList<>(resolvers), revisionOptions);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SessionCompileRequest request
                && source.equals(request.source)
                && sourceId.equals(request.sourceId)
                && snapshot.equals(request.snapshot)
                && baseRevision.equals(request.baseRevision)
                && sourceRoots.equals(request.sourceRoots)
                && resolvers.equals(request.resolvers)
                && javaBasePackage.equals(request.javaBasePackage)
                && javaTarget == request.javaTarget
                && previewEnabled == request.previewEnabled
                && includeSources == request.includeSources
                && semanticOptions.equals(request.semanticOptions)
                && reloadModule.equals(request.reloadModule)
                && reloadImportAlias.equals(request.reloadImportAlias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, sourceId, snapshot, baseRevision, sourceRoots, resolvers,
                javaBasePackage, javaTarget, previewEnabled, includeSources, semanticOptions,
                reloadModule, reloadImportAlias);
    }

    public static final class Builder {
        private EvaluationSource source;
        private SourceId sourceId;
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
        private LogicalModuleId reloadModule;
        private String reloadImportAlias;

        public Builder source(EvaluationSource value) {
            source = Objects.requireNonNull(value, "source");
            return this;
        }

        public Builder source(String label, String text) {
            return source(EvaluationSource.of(label, text));
        }

        /**
         * Sets the compiler identity reserved by the session owner. Distinct
         * submissions should use distinct identities even when origins match.
         * If omitted, the origin URI or label supplies the legacy identity
         * (an unrepresentable label uses {@code repl/submission-anonymous.lyra}).
         */
        public Builder sourceId(SourceId value) {
            sourceId = Objects.requireNonNull(value, "sourceId");
            return this;
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

        /** Enables the explicit reload graph mode for one logical module. */
        public Builder reloadModule(LogicalModuleId value) {
            reloadModule = Objects.requireNonNull(value, "reloadModule");
            return this;
        }

        /** Supplies the private synthetic alias used by the reload root source. */
        public Builder reloadImportAlias(String value) {
            reloadImportAlias = text(value, "reloadImportAlias");
            return this;
        }

        public SessionCompileRequest build() {
            return new SessionCompileRequest(this);
        }
    }

    private static SourceId defaultSourceId(
            EvaluationSource source, SessionSnapshot snapshot) {
        if (snapshot.revision().equals(SessionRevision.initial())) {
            if (source.origin().uri().isPresent()) {
                return SourceId.uri(source.origin().uri().orElseThrow());
            }
            try {
                return SourceId.of(source.origin().label());
            } catch (IllegalArgumentException ignored) {
                return SourceId.path("repl/submission-anonymous.lyra");
            }
        }
        SourceId candidate;
        if (source.origin().uri().isPresent()) {
            candidate = SourceId.uri(source.origin().uri().orElseThrow());
        } else {
            try {
                candidate = SourceId.of(source.origin().label());
            } catch (IllegalArgumentException ignored) {
                candidate = SourceId.path("repl/submission-anonymous.lyra");
            }
        }
        // A legacy request has no session-owned source identity. Only avoid a
        // predecessor collision; ordinary later submissions retain their
        // caller-facing label/URI for diagnostics.
        SourceId requested = candidate;
        if (snapshot.flowCertificate().map(certificate ->
                certificate.containsSourceId(requested)).orElse(false)) {
            return SourceId.path("repl/submission-" + snapshot.revision().value() + ".lyra");
        }
        return candidate;
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
