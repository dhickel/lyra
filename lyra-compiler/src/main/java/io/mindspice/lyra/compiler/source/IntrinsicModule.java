package io.mindspice.lyra.compiler.source;

import io.mindspice.lyra.runtime.LyraRuntimeConstants;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Compiler-owned source identity and syntax-valid source for an intrinsic module. */
final class IntrinsicModule {
    private static final URI STD_IO_URI = URI.create("lyra:intrinsic/std/io");
    private static final byte[] EMPTY_SOURCE = "".getBytes(StandardCharsets.UTF_8);
    /**
     * The revision is tied to this complete compiler/runtime contract, not to
     * the empty syntax placeholder used for graph parsing.
     */
    private static final String STD_IO_CONTRACT = String.join("\n",
            "uri:lyra:intrinsic/std/io",
            "runtime-abi:" + LyraRuntimeConstants.RUNTIME_ABI.canonicalSpelling(),
            "export:print:immutable:Fn<String;Unit>",
            "export:println:immutable:Fn<String;Unit>",
            "export:eprint:immutable:Fn<String;Unit>",
            "export:eprintln:immutable:Fn<String;Unit>",
            "export:readLine:immutable:Fn<;@nilString>");
    private static final String STD_IO_REVISION = ModuleRevision.compute(
            STD_IO_CONTRACT.getBytes(StandardCharsets.UTF_8), RevisionOptions.empty());
    private static final IntrinsicModule STD_IO = new IntrinsicModule(
            LogicalModuleId.STD_IO,
            SourceId.uri(STD_IO_URI),
            PhysicalSourceKey.uri(STD_IO_URI),
            EMPTY_SOURCE);

    private final LogicalModuleId logicalModule;
    private final SourceId sourceId;
    private final PhysicalSourceKey physicalKey;
    private final byte[] sourceBytes;

    private IntrinsicModule(
            LogicalModuleId logicalModule,
            SourceId sourceId,
            PhysicalSourceKey physicalKey,
            byte[] sourceBytes) {
        this.logicalModule = Objects.requireNonNull(logicalModule, "logicalModule");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.physicalKey = Objects.requireNonNull(physicalKey, "physicalKey");
        this.sourceBytes = Objects.requireNonNull(sourceBytes, "sourceBytes").clone();
    }

    static IntrinsicModule stdIo() {
        return STD_IO;
    }

    static String revision() {
        return STD_IO_REVISION;
    }

    static boolean claimsReservedIdentity(SourceId sourceId, PhysicalSourceKey physicalKey) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(physicalKey, "physicalKey");
        return STD_IO.sourceId.equals(sourceId) || STD_IO.physicalKey.equals(physicalKey);
    }

    ResolvedSource resolvedSource() {
        return new ResolvedSource(logicalModule, sourceId, physicalKey, sourceBytes);
    }
}
