package io.mindspice.lyra.compiler.source;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

/**
 * One immutable source candidate returned for a logical module query.
 *
 * <p>The bytes are retained until discovery materializes a
 * {@link SourceSnapshot}.  Keeping this candidate separate from the snapshot
 * lets discovery query every root/resolver, deduplicate physical aliases, and
 * capture a source exactly once without giving any provider precedence.</p>
 */
public final class ResolvedSource {
    private final LogicalModuleId logicalModule;
    private final SourceId sourceId;
    private final PhysicalSourceKey physicalKey;
    private final byte[] capturedUtf8Bytes;

    public ResolvedSource(
            LogicalModuleId logicalModule,
            SourceId sourceId,
            PhysicalSourceKey physicalKey,
            byte[] capturedUtf8Bytes) {
        this.logicalModule = Objects.requireNonNull(logicalModule, "logicalModule");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.physicalKey = Objects.requireNonNull(physicalKey, "physicalKey");
        this.capturedUtf8Bytes = Objects.requireNonNull(capturedUtf8Bytes, "capturedUtf8Bytes").clone();
    }

    public ResolvedSource(
            LogicalModuleId logicalModule,
            ModuleId moduleId,
            PhysicalSourceKey physicalKey,
            byte[] capturedUtf8Bytes) {
        this(logicalModule, Objects.requireNonNull(moduleId, "moduleId").sourceId(),
                physicalKey, capturedUtf8Bytes);
    }

    public ResolvedSource(
            LogicalModuleId logicalModule,
            URI stableUri,
            byte[] capturedUtf8Bytes) {
        this(logicalModule, SourceId.uri(stableUri), PhysicalSourceKey.uri(stableUri), capturedUtf8Bytes);
    }

    public static ResolvedSource of(
            LogicalModuleId logicalModule,
            SourceId sourceId,
            PhysicalSourceKey physicalKey,
            byte[] capturedUtf8Bytes) {
        return new ResolvedSource(logicalModule, sourceId, physicalKey, capturedUtf8Bytes);
    }

    public static ResolvedSource of(
            LogicalModuleId logicalModule,
            ModuleId moduleId,
            PhysicalSourceKey physicalKey,
            byte[] capturedUtf8Bytes) {
        return new ResolvedSource(logicalModule, moduleId, physicalKey, capturedUtf8Bytes);
    }

    public static ResolvedSource of(
            LogicalModuleId logicalModule,
            URI stableUri,
            byte[] capturedUtf8Bytes) {
        return new ResolvedSource(logicalModule, stableUri, capturedUtf8Bytes);
    }

    public static ResolvedSource memory(
            LogicalModuleId logicalModule,
            URI stableUri,
            byte[] capturedUtf8Bytes) {
        Objects.requireNonNull(stableUri, "stableUri");
        return new ResolvedSource(
                logicalModule,
                SourceId.uri(stableUri),
                PhysicalSourceKey.uri(stableUri),
                capturedUtf8Bytes);
    }

    public static ResolvedSource memory(
            String logicalModule,
            URI stableUri,
            byte[] capturedUtf8Bytes) {
        return memory(LogicalModuleId.parse(logicalModule), stableUri, capturedUtf8Bytes);
    }

    public static ResolvedSource memory(
            LogicalModuleId logicalModule,
            URI stableUri,
            String sourceText) {
        Objects.requireNonNull(sourceText, "sourceText");
        return memory(logicalModule, stableUri, sourceText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static ResolvedSource memory(
            String logicalModule,
            URI stableUri,
            String sourceText) {
        return memory(LogicalModuleId.parse(logicalModule), stableUri, sourceText);
    }

    public static ResolvedSource memory(
            LogicalModuleId logicalModule,
            String stableUri,
            String sourceText) {
        return memory(logicalModule, URI.create(stableUri), sourceText);
    }

    public static ResolvedSource memory(
            String logicalModule,
            String stableUri,
            String sourceText) {
        return memory(LogicalModuleId.parse(logicalModule), stableUri, sourceText);
    }

    public static ResolvedSource fromSnapshot(
            LogicalModuleId logicalModule, SourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new ResolvedSource(
                logicalModule,
                snapshot.sourceId(),
                snapshot.physicalKey(),
                snapshot.capturedUtf8Bytes());
    }

    public LogicalModuleId logicalModule() {
        return logicalModule;
    }

    public LogicalModuleId logicalModuleId() {
        return logicalModule;
    }

    public SourceId sourceId() {
        return sourceId;
    }

    public ModuleId moduleId() {
        return ModuleId.fromSourceId(sourceId);
    }

    public SourceId stableSourceId() {
        return sourceId;
    }

    public PhysicalSourceKey physicalKey() {
        return physicalKey;
    }

    /** Returns the exact provider bytes, including a possible initial BOM. */
    public byte[] capturedUtf8Bytes() {
        return capturedUtf8Bytes.clone();
    }

    public byte[] utf8Bytes() {
        return capturedUtf8Bytes();
    }

    public byte[] bytes() {
        return capturedUtf8Bytes();
    }

    public byte[] sourceBytes() {
        return capturedUtf8Bytes();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ResolvedSource source)) {
            return false;
        }
        return logicalModule.equals(source.logicalModule)
                && sourceId.equals(source.sourceId)
                && physicalKey.equals(source.physicalKey)
                && Arrays.equals(capturedUtf8Bytes, source.capturedUtf8Bytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(logicalModule, sourceId, physicalKey);
        return 31 * result + Arrays.hashCode(capturedUtf8Bytes);
    }

    @Override
    public String toString() {
        return "ResolvedSource["
                + logicalModule
                + " -> "
                + sourceId
                + ", physical="
                + physicalKey
                + "]";
    }
}
