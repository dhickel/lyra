package io.mindspice.lyra.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable artifact view understood by the runtime loader.
 *
 * <p>The compiler module implements this contract without making the runtime
 * depend on compiler classes.  Entries use the forward-slash names used by a
 * class directory or a JAR; generated class entries are therefore named
 * {@code binary/name/Here.class}.</p>
 */
public interface ArtifactSource {
    /** Compatibility metadata for this artifact. */
    ArtifactMetadata metadata();

    /**
     * Returns all artifact entries. Implementations must return defensive
     * byte-array copies. The loader copies them again at its boundary.
     */
    Map<String, byte[]> entries();

    /** Returns one entry, if present, with a defensive byte copy. */
    default Optional<byte[]> entry(String name) {
        Objects.requireNonNull(name, "name");
        byte[] value = entries().get(name);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    /** Returns generated classes keyed by binary name. */
    default Map<String, byte[]> classes() {
        TreeMap<String, byte[]> result = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries().entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "entry name");
            if (!name.endsWith(".class")) {
                continue;
            }
            String binaryName = name.substring(0, name.length() - ".class".length())
                    .replace('/', '.');
            result.put(binaryName, Objects.requireNonNull(entry.getValue(), "entry bytes").clone());
        }
        return Collections.unmodifiableMap(result);
    }

    /** Creates an in-memory artifact from binary-name class bytes and a debug map. */
    static ArtifactSource inMemory(ArtifactMetadata metadata,
                                   Map<String, byte[]> classes,
                                   DebugMapMetadata debugMap) {
        Objects.requireNonNull(debugMap, "debugMap");
        return inMemory(metadata, classes, debugMap.canonicalUtf8());
    }

    /** Creates an in-memory artifact from binary-name class bytes and debug-map bytes. */
    static ArtifactSource inMemory(ArtifactMetadata metadata,
                                   Map<String, byte[]> classes,
                                   byte[] debugMapUtf8) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(classes, "classes");
        Objects.requireNonNull(debugMapUtf8, "debugMapUtf8");
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String binaryName = Objects.requireNonNull(entry.getKey(), "class name");
            byte[] bytes = Objects.requireNonNull(entry.getValue(), "class bytes");
            String entryName = binaryName.replace('.', '/') + ".class";
            if (entries.put(entryName, bytes.clone()) != null) {
                throw new IllegalArgumentException("duplicate in-memory class: " + binaryName);
            }
        }
        put(entries, LyraRuntimeConstants.ARTIFACT_METADATA_PATH, metadata.canonicalUtf8());
        put(entries, LyraRuntimeConstants.DEBUG_MAP_PATH, debugMapUtf8);
        return new MemoryArtifactSource(metadata, entries);
    }

    /** Alias for {@link #inMemory(ArtifactMetadata, Map, byte[])}. */
    static ArtifactSource of(ArtifactMetadata metadata,
                             Map<String, byte[]> classes,
                             byte[] debugMapUtf8) {
        return inMemory(metadata, classes, debugMapUtf8);
    }

    /** Creates an in-memory artifact from already normalized artifact entries. */
    static ArtifactSource fromEntries(ArtifactMetadata metadata,
                                      Map<String, byte[]> entries) {
        return new MemoryArtifactSource(metadata, entries);
    }

    private static void put(Map<String, byte[]> entries, String name, byte[] bytes) {
        if (entries.put(name, bytes.clone()) != null) {
            throw new IllegalArgumentException("duplicate in-memory artifact entry: " + name);
        }
    }
}

final class MemoryArtifactSource implements ArtifactSource {
    private final ArtifactMetadata metadata;
    private final Map<String, byte[]> entries;

    MemoryArtifactSource(ArtifactMetadata metadata, Map<String, byte[]> entries) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(entries, "entries");
        LinkedHashMap<String, byte[]> copied = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "entry name");
            byte[] bytes = Objects.requireNonNull(entry.getValue(), "entry bytes");
            if (copied.put(name, bytes.clone()) != null) {
                throw new IllegalArgumentException("duplicate artifact entry: " + name);
            }
        }
        this.entries = Collections.unmodifiableMap(copied);
    }

    @Override
    public ArtifactMetadata metadata() {
        return metadata;
    }

    @Override
    public Map<String, byte[]> entries() {
        LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }
}
