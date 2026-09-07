package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.session.ExternalBinding;
import io.mindspice.lyra.compiler.session.PinnedModule;
import io.mindspice.lyra.compiler.session.SessionModuleEnvironment;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceRoot;
import io.mindspice.lyra.runtime.AttachmentContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Source-independent compiler context for one attachable root.
 *
 * <p>The context contains the sealed module/semantic/flow facts, the exact
 * original source snapshots, reproducible revision options, the public
 * root-scope binding contracts, and a compiler-issued flow certificate that
 * treats public root aggregates as conservative imported/boundary values
 * rather than initializer allocations.  It deliberately contains no AST
 * re-execution path, no resolver instance, and no live runtime object; a
 * host may transport it only within one process, while packaged
 * reconstruction (Phase 11) rebuilds the same facts from embedded sources.</p>
 */
public final class AttachableRootContext {
    private final ModuleGraph moduleGraph;
    private final ResolvedSemanticGraph resolvedGraph;
    private final TypedSemanticGraph typedGraph;
    private final AttachmentContext attachmentContext;
    private final List<SourceRoot> sourceRoots;
    private final Map<String, String> revisionOptions;
    private final List<ExternalBinding> rootBindings;
    private final Map<LogicalModuleId, PinnedModule> pinnedModules;
    private final SessionModuleEnvironment moduleEnvironment;
    private final SessionFlowCertificate rootCertificate;
    private final SessionSnapshot initialSnapshot;
    private final IdentityAllocator baseAllocator;

    AttachableRootContext(
            ModuleGraph moduleGraph,
            ResolvedSemanticGraph resolvedGraph,
            TypedSemanticGraph typedGraph,
            AttachmentContext attachmentContext,
            List<SourceRoot> sourceRoots,
            Map<String, String> revisionOptions,
            List<ExternalBinding> rootBindings,
            Map<LogicalModuleId, PinnedModule> pinnedModules,
            SessionModuleEnvironment moduleEnvironment,
            SessionFlowCertificate rootCertificate,
            SessionSnapshot initialSnapshot,
            IdentityAllocator baseAllocator) {
        this.moduleGraph = Objects.requireNonNull(moduleGraph, "moduleGraph");
        this.resolvedGraph = Objects.requireNonNull(resolvedGraph, "resolvedGraph");
        this.typedGraph = Objects.requireNonNull(typedGraph, "typedGraph");
        if (typedGraph.resolvedGraph() != resolvedGraph
                || resolvedGraph.moduleGraph() != moduleGraph) {
            throw new IllegalArgumentException(
                    "attachable context mixes unrelated compiler phases");
        }
        this.attachmentContext = Objects.requireNonNull(attachmentContext, "attachmentContext");
        this.sourceRoots = List.copyOf(Objects.requireNonNull(sourceRoots, "sourceRoots"));
        this.revisionOptions = Map.copyOf(Objects.requireNonNull(revisionOptions, "revisionOptions"));
        this.rootBindings = List.copyOf(Objects.requireNonNull(rootBindings, "rootBindings"));
        this.pinnedModules = Map.copyOf(Objects.requireNonNull(pinnedModules, "pinnedModules"));
        this.moduleEnvironment = Objects.requireNonNull(moduleEnvironment, "moduleEnvironment");
        this.rootCertificate = Objects.requireNonNull(rootCertificate, "rootCertificate");
        this.initialSnapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        this.baseAllocator = Objects.requireNonNull(baseAllocator, "baseAllocator");
        if (!initialSnapshot.moduleEnvironment().equals(moduleEnvironment)
                || !initialSnapshot.pinnedModules().equals(pinnedModules)
                || initialSnapshot.flowCertificate().isEmpty()
                || initialSnapshot.flowCertificate().orElseThrow() != rootCertificate
                || !initialSnapshot.orderedBindings().equals(rootBindings)) {
            throw new IllegalArgumentException(
                    "attachable snapshot disagrees with its context components");
        }
        if (!attachmentContext.rootModule().equals(runtimeModuleId(rootModule()))) {
            throw new IllegalArgumentException(
                    "attachable context root disagrees with the artifact context");
        }
    }

    /** The sealed reachable module graph of the attachable compilation. */
    public ModuleGraph moduleGraph() {
        return moduleGraph;
    }

    /** The sealed resolution graph of the attachable compilation. */
    public ResolvedSemanticGraph resolvedGraph() {
        return resolvedGraph;
    }

    /** The sealed typed graph of the attachable compilation. */
    public TypedSemanticGraph typedGraph() {
        return typedGraph;
    }

    /** The artifact-compatibility context this root was compiled against. */
    public AttachmentContext attachmentContext() {
        return attachmentContext;
    }

    /** Compiler root module identity. */
    public ModuleId rootModule() {
        return moduleGraph.rootModule();
    }

    /** Original captured source roots; never re-queried through resolver objects. */
    public List<SourceRoot> sourceRoots() {
        return sourceRoots;
    }

    /** Exact reproducible revision options of the original compilation. */
    public Map<String, String> revisionOptions() {
        return revisionOptions;
    }

    /** Public root-scope bindings in deterministic name order. */
    public List<ExternalBinding> rootBindings() {
        return rootBindings;
    }

    /** Pinned sources for every application-owned module. */
    public Map<LogicalModuleId, PinnedModule> pinnedModules() {
        return pinnedModules;
    }

    /** Retained APPLICATION-owned module environment for session imports. */
    public SessionModuleEnvironment moduleEnvironment() {
        return moduleEnvironment;
    }

    /** Compiler-issued conservative proof for the public root scope. */
    public SessionFlowCertificate rootCertificate() {
        return rootCertificate;
    }

    /** The immutable session snapshot to start an attached workspace from. */
    public SessionSnapshot initialSnapshot() {
        return initialSnapshot;
    }

    /** Identity base for new scratch allocations on this attachment. */
    public IdentityAllocator baseAllocator() {
        return baseAllocator;
    }

    private static io.mindspice.lyra.runtime.ModuleId runtimeModuleId(ModuleId module) {
        return module.isUri()
                ? io.mindspice.lyra.runtime.ModuleId.uri(module.asUri())
                : io.mindspice.lyra.runtime.ModuleId.path(module.value());
    }
}
