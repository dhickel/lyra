package io.mindspice.lyra.compiler.identity;

import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraSignature;

import java.util.Objects;

/** Stable identity entry points shared by later graph and semantic phases. */
public final class StableIdentity {
    private StableIdentity() {
    }

    public static ModuleIdentity module(ModuleId moduleId) {
        return ModuleIdentity.of(moduleId);
    }

    public static ModuleIdentity module(SourceSpan sourceSpan) {
        return ModuleIdentity.from(sourceSpan);
    }

    /** Logical names are import keys, never replacements for stable module IDs. */
    public static String logicalModuleKey(LogicalModuleId logicalModule) {
        return ModuleIdentity.logicalKey(Objects.requireNonNull(logicalModule, "logicalModule"));
    }

    public static ExportId export(
            ModuleId moduleId, String exportName, LyraSignature signature) {
        return ExportId.of(moduleId, exportName, signature);
    }

    public static ExportId export(
            SourceSpan declarationSpan, String exportName, LyraSignature signature) {
        return ExportId.from(declarationSpan, exportName, signature);
    }
}
