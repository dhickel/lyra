package io.mindspice.lyra.runtime;

/** One owner-thread-confined instance of a loaded Lyra module facade. */
public interface ModuleHandle extends AutoCloseable {
    /** Stable identity of the facade instance's module. */
    ModuleId moduleId();

    /** Artifact metadata from which this instance was created. */
    ArtifactMetadata metadata();

    /** Looks up and binds one exact callable export to this module instance. */
    ExportHandle export(String name, LyraSignature signature);

    /** Convenience overload for a canonical {@code Fn<...>} spelling. */
    default ExportHandle export(String name, String signature) {
        return export(name, LyraSignature.parse(signature, metadata().nominalSchemas()));
    }

    /** Looks up a callable export by its stable identity. */
    default ExportHandle export(ExportId exportId) {
        if (exportId == null) {
            throw new NullPointerException("exportId");
        }
        if (!moduleId().equals(exportId.moduleId())) {
            throw new IllegalArgumentException("export belongs to another module: " + exportId);
        }
        return export(exportId.exportName(), exportId.signature());
    }

    /** Returns whether this instance has been closed. */
    default boolean isClosed() {
        return false;
    }

    /** Closes this instance on its owner thread. */
    @Override
    void close();
}
