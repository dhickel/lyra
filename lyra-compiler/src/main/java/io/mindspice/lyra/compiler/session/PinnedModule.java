package io.mindspice.lyra.compiler.session;

import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.RevisionOptions;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.SourceSnapshot;

import java.util.Objects;

/** Immutable source/revision pair retained by a session for pinned imports. */
public record PinnedModule(
        LogicalModuleId logicalModule,
        SourceSnapshot snapshot,
        String revision,
        RevisionOptions revisionOptions) {
    public PinnedModule {
        logicalModule = Objects.requireNonNull(logicalModule, "logicalModule");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
        revisionOptions = Objects.requireNonNull(revisionOptions, "revisionOptions");
        if (!revision.equals(ModuleRevision.compute(snapshot, revisionOptions))) {
            throw new IllegalArgumentException("pinned revision does not match captured source and options");
        }
        if (logicalModule.isStdIo()) {
            throw new IllegalArgumentException("the intrinsic std/io module cannot be pinned");
        }
    }

    public PinnedModule(LogicalModuleId logicalModule, SourceSnapshot snapshot, String revision) {
        this(logicalModule, snapshot, revision, RevisionOptions.empty());
    }

    public PinnedModule(LogicalModuleId logicalModule, SourceSnapshot snapshot) {
        this(logicalModule, snapshot, ModuleRevision.compute(snapshot), RevisionOptions.empty());
    }

    public ModuleId moduleId() {
        return ModuleId.fromSourceId(snapshot.sourceId());
    }

    public ResolvedSource resolvedSource() {
        return ResolvedSource.fromSnapshot(logicalModule, snapshot);
    }

    public boolean revisionMatchesDefault() {
        return revision.equals(ModuleRevision.compute(snapshot));
    }
}
