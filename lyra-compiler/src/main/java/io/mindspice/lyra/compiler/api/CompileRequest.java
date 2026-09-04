package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.RevisionOptions;
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

/** Immutable input to the public Lyra compiler. */
public final class CompileRequest {
    private final Optional<Path> rootPath;
    private final Optional<LogicalModuleId> rootModule;
    private final Optional<SourceInput> rootSource;
    private final List<Path> sourceRoots;
    private final List<SourceResolver> resolvers;
    private final String javaBasePackage;
    private final int javaTarget;
    private final boolean previewEnabled;
    private final boolean includeSources;
    private final Map<String, String> semanticOptions;

    private CompileRequest(Builder builder) {
        int roots = (builder.rootPath == null ? 0 : 1)
                + (builder.rootModule == null ? 0 : 1)
                + (builder.rootSource == null ? 0 : 1);
        if (roots != 1) {
            throw new IllegalArgumentException("a compile request needs exactly one root path, module, or source");
        }
        rootPath = Optional.ofNullable(builder.rootPath);
        rootModule = Optional.ofNullable(builder.rootModule);
        rootSource = Optional.ofNullable(builder.rootSource);
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

    public CompileRequest(Path root) {
        this(builder().root(root));
    }

    public CompileRequest(LogicalModuleId root) {
        this(builder().rootModule(root));
    }

    public CompileRequest(SourceInput root) {
        this(builder().rootSource(root));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static CompileRequest path(Path root) {
        return builder().root(root).build();
    }

    public static CompileRequest path(String root) {
        return builder().root(root).build();
    }

    public static CompileRequest logical(LogicalModuleId root) {
        return builder().rootModule(root).build();
    }

    public static CompileRequest logical(String root) {
        return builder().rootModule(root).build();
    }

    public static CompileRequest source(SourceId sourceId, String text) {
        return builder().rootSource(sourceId, text).build();
    }

    public static CompileRequest source(String sourceId, String text) {
        return builder().rootSource(sourceId, text).build();
    }

    public static CompileRequest inMemory(SourceId sourceId, String text) {
        return source(sourceId, text);
    }

    public static CompileRequest inMemory(String sourceId, String text) {
        return source(sourceId, text);
    }

    public Optional<Path> rootPath() {
        return rootPath;
    }

    public Optional<Path> pathRoot() {
        return rootPath;
    }

    public Optional<LogicalModuleId> rootModule() {
        return rootModule;
    }

    public Optional<LogicalModuleId> logicalRoot() {
        return rootModule;
    }

    public Optional<SourceInput> rootSource() {
        return rootSource;
    }

    public Optional<SourceInput> source() {
        return rootSource;
    }

    public List<Path> sourceRoots() {
        return sourceRoots;
    }

    public List<SourceResolver> resolvers() {
        return resolvers;
    }

    public List<SourceResolver> sourceResolvers() {
        return resolvers;
    }

    public String javaBasePackage() {
        return javaBasePackage;
    }

    public String basePackage() {
        return javaBasePackage;
    }

    public int javaTarget() {
        return javaTarget;
    }

    public int target() {
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

    /** Converts the public source settings to the graph-discovery contract. */
    public SourceConfiguration sourceConfiguration() {
        List<io.mindspice.lyra.compiler.source.SourceResolver> internalResolvers =
                new ArrayList<>(resolvers);
        return SourceConfiguration.ofPaths(
                sourceRoots, internalResolvers, semanticOptions);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CompileRequest request
                && rootPath.equals(request.rootPath)
                && rootModule.equals(request.rootModule)
                && rootSource.equals(request.rootSource)
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
        return Objects.hash(rootPath, rootModule, rootSource, sourceRoots, resolvers,
                javaBasePackage, javaTarget, previewEnabled, includeSources, semanticOptions);
    }

    @Override
    public String toString() {
        return "CompileRequest[root=" + rootDescription() + ",package=" + javaBasePackage
                + ",target=" + javaTarget + "]";
    }

    private String rootDescription() {
        return rootPath.map(Path::toString)
                .orElseGet(() -> rootModule.map(Object::toString)
                        .orElseGet(() -> rootSource.map(value -> value.sourceId().toString()).orElse("<none>")));
    }

    private static List<Path> copyPaths(List<Path> values) {
        Objects.requireNonNull(values, "sourceRoots");
        ArrayList<Path> copy = new ArrayList<>(values.size());
        for (Path value : values) {
            copy.add(Objects.requireNonNull(value, "sourceRoots must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static List<SourceResolver> copyResolvers(
            List<? extends io.mindspice.lyra.compiler.source.SourceResolver> values) {
        Objects.requireNonNull(values, "resolvers");
        ArrayList<SourceResolver> copy = new ArrayList<>(values.size());
        for (io.mindspice.lyra.compiler.source.SourceResolver value : values) {
            copy.add(adaptResolver(Objects.requireNonNull(value,
                    "resolvers must not contain null")));
        }
        return List.copyOf(copy);
    }

    private static SourceResolver adaptResolver(
            io.mindspice.lyra.compiler.source.SourceResolver value) {
        if (value instanceof SourceResolver resolver) {
            return resolver;
        }
        return value::resolve;
    }

    private static Map<String, String> copyOptions(Map<String, String> values) {
        Objects.requireNonNull(values, "semanticOptions");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "semantic option key");
            String value = Objects.requireNonNull(entry.getValue(), "semantic option value");
            if (key.isBlank()) {
                throw new IllegalArgumentException("semantic option keys must not be blank");
            }
            if (containsControl(key) || containsControl(value)) {
                throw new IllegalArgumentException(
                        "semantic option keys and values must not contain control characters");
            }
            if (copy.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate semantic option: " + key);
            }
        }
        return Map.copyOf(copy);
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

    /** Mutable only while constructing one immutable request. */
    public static final class Builder {
        private Path rootPath;
        private LogicalModuleId rootModule;
        private SourceInput rootSource;
        private List<Path> sourceRoots = List.of();
        private List<SourceResolver> resolvers = List.of();
        private String javaBasePackage = "lyra.generated";
        private int javaTarget = LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET;
        private boolean previewEnabled;
        private boolean includeSources;
        private Map<String, String> semanticOptions = Map.of();

        public Builder root(Path value) {
            clearRoot();
            rootPath = Objects.requireNonNull(value, "root");
            return this;
        }

        public Builder root(String value) {
            return root(Path.of(Objects.requireNonNull(value, "root")));
        }

        public Builder rootPath(Path value) {
            return root(value);
        }

        public Builder root(SourceInput value) {
            return rootSource(value);
        }

        public Builder rootModule(LogicalModuleId value) {
            clearRoot();
            rootModule = Objects.requireNonNull(value, "rootModule");
            return this;
        }

        public Builder rootModule(String value) {
            return rootModule(LogicalModuleId.parse(value));
        }

        public Builder logicalRoot(LogicalModuleId value) {
            return rootModule(value);
        }

        public Builder logicalRoot(String value) {
            return rootModule(value);
        }

        public Builder rootSource(SourceInput value) {
            clearRoot();
            rootSource = Objects.requireNonNull(value, "rootSource");
            return this;
        }

        public Builder rootSource(SourceId sourceId, String text) {
            return rootSource(new SourceInput(sourceId, text));
        }

        public Builder rootSource(String sourceId, String text) {
            return rootSource(new SourceInput(sourceId, text));
        }

        public Builder source(SourceId sourceId, String text) {
            return rootSource(sourceId, text);
        }

        public Builder source(String sourceId, String text) {
            return rootSource(sourceId, text);
        }

        public Builder sourceText(String sourceId, String text) {
            return rootSource(sourceId, text);
        }

        public Builder sourceConfiguration(SourceConfiguration value) {
            SourceConfiguration configuration = Objects.requireNonNull(value, "sourceConfiguration");
            sourceRoots = configuration.rootPaths();
            resolvers = copyResolvers(configuration.resolvers());
            semanticOptions = configuration.revisionOptions().values();
            return this;
        }

        public Builder configuration(SourceConfiguration value) {
            return sourceConfiguration(value);
        }

        public Builder sourceRoots(List<Path> values) {
            sourceRoots = copyPaths(values);
            return this;
        }

        public Builder sourceRoots(Path... values) {
            Objects.requireNonNull(values, "sourceRoots");
            return sourceRoots(List.of(values));
        }

        public Builder sourceRoot(Path value) {
            ArrayList<Path> copy = new ArrayList<>(sourceRoots);
            copy.add(Objects.requireNonNull(value, "sourceRoot"));
            sourceRoots = List.copyOf(copy);
            return this;
        }

        public Builder resolvers(
                List<? extends io.mindspice.lyra.compiler.source.SourceResolver> values) {
            resolvers = copyResolvers(values);
            return this;
        }

        public Builder resolver(io.mindspice.lyra.compiler.source.SourceResolver value) {
            ArrayList<SourceResolver> copy = new ArrayList<>(resolvers);
            copy.add(adaptResolver(Objects.requireNonNull(value, "resolver")));
            resolvers = List.copyOf(copy);
            return this;
        }

        public Builder sourceResolver(io.mindspice.lyra.compiler.source.SourceResolver value) {
            return resolver(value);
        }

        public Builder javaBasePackage(String value) {
            javaBasePackage = Objects.requireNonNull(value, "javaBasePackage");
            return this;
        }

        public Builder basePackage(String value) {
            return javaBasePackage(value);
        }

        public Builder javaTarget(int value) {
            javaTarget = value;
            return this;
        }

        public Builder target(int value) {
            return javaTarget(value);
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

        public Builder embedSources(boolean value) {
            return includeSources(value);
        }

        public Builder semanticOptions(Map<String, String> values) {
            semanticOptions = copyOptions(values);
            return this;
        }

        public Builder revisionOptions(Map<String, String> values) {
            return semanticOptions(values);
        }

        public Builder revisionOptions(RevisionOptions values) {
            return semanticOptions(Objects.requireNonNull(values, "revisionOptions").values());
        }

        public CompileRequest build() {
            return new CompileRequest(this);
        }

        private void clearRoot() {
            rootPath = null;
            rootModule = null;
            rootSource = null;
        }
    }
}
