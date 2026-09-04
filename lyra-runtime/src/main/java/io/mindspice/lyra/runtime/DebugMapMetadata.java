package io.mindspice.lyra.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable source-map metadata; it contains spans, not executable or IR data. */
public final class DebugMapMetadata {
    public static final int SCHEMA_VERSION = LyraRuntimeConstants.DEBUG_MAP_SCHEMA_VERSION;

    private final int schemaVersion;
    private final List<DebugMapEntry> entries;

    public DebugMapMetadata(int schemaVersion, List<? extends DebugMapEntry> entries) {
        if (schemaVersion != LyraRuntimeConstants.DEBUG_MAP_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported debug-map schema version: " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        Objects.requireNonNull(entries, "entries");
        ArrayList<DebugMapEntry> copy = new ArrayList<>(entries.size());
        for (DebugMapEntry entry : entries) copy.add(Objects.requireNonNull(entry, "entries must not contain null"));
        copy.sort(DebugMapEntry::compareTo);
        for (int index = 1; index < copy.size(); index++) {
            DebugMapEntry previous = copy.get(index - 1);
            DebugMapEntry current = copy.get(index);
            if (previous.className().equals(current.className())
                    && previous.methodName().equals(current.methodName())
                    && previous.methodDescriptor().equals(current.methodDescriptor())
                    && previous.endBci() > current.startBci()) {
                throw new IllegalArgumentException("overlapping debug-map BCI ranges");
            }
        }
        this.entries = List.copyOf(copy);
    }

    public static DebugMapMetadata of(int schemaVersion, List<? extends DebugMapEntry> entries) {
        return new DebugMapMetadata(schemaVersion, entries);
    }

    public int schemaVersion() { return schemaVersion; }
    public List<DebugMapEntry> entries() { return entries; }

    public Optional<DebugMapEntry> lookup(String className, String methodName, int bci) {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        if (bci < 0) throw new IllegalArgumentException("bci must be non-negative");
        return entries.stream().filter(entry -> entry.className().equals(className)
                && entry.methodName().equals(methodName) && entry.contains(bci)).findFirst();
    }

    public Optional<DebugMapEntry> lookup(String className, String methodName,
                                          String methodDescriptor, int bci) {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        JvmDescriptorValidator.requireMethodDescriptor(methodDescriptor);
        if (bci < 0) throw new IllegalArgumentException("bci must be non-negative");
        return entries.stream().filter(entry -> entry.className().equals(className)
                && entry.methodName().equals(methodName)
                && entry.methodDescriptor().equals(methodDescriptor)
                && entry.contains(bci)).findFirst();
    }

    public String canonicalJson() {
        StringBuilder result = new StringBuilder("{\"schemaVersion\":")
                .append(schemaVersion).append(",\"entries\":[");
        boolean first = true;
        for (DebugMapEntry entry : entries) {
            if (!first) result.append(',');
            first = false;
            SourceFrame frame = entry.frame();
            result.append('{');
            boolean[] fields = {true};
            field(result, fields, "className", CanonicalJson.quote(entry.className()));
            field(result, fields, "methodName", CanonicalJson.quote(entry.methodName()));
            field(result, fields, "methodDescriptor", CanonicalJson.quote(entry.methodDescriptor()));
            field(result, fields, "startBci", Integer.toString(entry.startBci()));
            field(result, fields, "endBci", Integer.toString(entry.endBci()));
            field(result, fields, "moduleId", CanonicalJson.quote(frame.moduleId().canonicalSpelling()));
            field(result, fields, "functionName", CanonicalJson.quote(frame.functionName()));
            field(result, fields, "sourceId", CanonicalJson.quote(frame.span().sourceId().value()));
            if (frame.sourceLabel().isPresent()) {
                field(result, fields, "sourceLabel", CanonicalJson.quote(frame.sourceLabel().orElseThrow()));
            }
            field(result, fields, "startOffset", Integer.toString(frame.span().startOffset()));
            field(result, fields, "endOffset", Integer.toString(frame.span().endOffset()));
            field(result, fields, "synthetic", Boolean.toString(frame.synthetic()));
            if (frame.origin().isPresent()) {
                SourceFrame origin = frame.origin().orElseThrow();
                field(result, fields, "origin", originJson(origin));
            }
            result.append('}');
        }
        return result.append("]}").toString();
    }

    public String toJson() { return canonicalJson(); }

    public byte[] canonicalUtf8() {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(canonicalJson()));
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("debug map is not valid UTF-8", exception);
        }
    }

    public String sha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalUtf8()));
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof DebugMapMetadata metadata
                && schemaVersion == metadata.schemaVersion && entries.equals(metadata.entries);
    }

    @Override
    public int hashCode() { return Objects.hash(schemaVersion, entries); }

    @Override
    public String toString() { return canonicalJson(); }

    private static String originJson(SourceFrame origin) {
        StringBuilder result = new StringBuilder("{");
        boolean[] fields = {true};
        field(result, fields, "moduleId", CanonicalJson.quote(origin.moduleId().canonicalSpelling()));
        field(result, fields, "functionName", CanonicalJson.quote(origin.functionName()));
        field(result, fields, "sourceId", CanonicalJson.quote(origin.span().sourceId().value()));
        if (origin.sourceLabel().isPresent()) {
            field(result, fields, "sourceLabel", CanonicalJson.quote(origin.sourceLabel().orElseThrow()));
        }
        field(result, fields, "startOffset", Integer.toString(origin.span().startOffset()));
        field(result, fields, "endOffset", Integer.toString(origin.span().endOffset()));
        return result.append('}').toString();
    }

    private static void field(StringBuilder result, boolean[] first, String name, String value) {
        CanonicalJson.comma(result, first);
        CanonicalJson.fieldName(result, name);
        result.append(value);
    }
}
