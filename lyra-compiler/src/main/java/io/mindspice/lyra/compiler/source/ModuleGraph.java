package io.mindspice.lyra.compiler.source;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;

/**
 * Complete immutable reachable module graph published by source discovery.
 * Cycles are represented by {@link #cycles()} and are not diagnostics at this
 * phase.
 */
public final class ModuleGraph implements ImmutablePhaseArtifact {
    private static final Comparator<ModuleId> MODULE_ORDER =
            Comparator.comparing(ModuleId::value)
                    .thenComparing(module -> module.isUri() ? 1 : 0);
    private static final Comparator<Edge> EDGE_ORDER = Comparator
            .comparing(Edge::from, MODULE_ORDER)
            .thenComparing(edge -> edge.logicalTarget().value())
            .thenComparing(Edge::target, MODULE_ORDER)
            .thenComparingInt(edge -> edge.importSpan().startOffset())
            .thenComparingInt(edge -> edge.importSpan().endOffset());

    /** One parsed source module in the graph. */
    public record Node(
            ModuleId moduleId,
            Optional<LogicalModuleId> logicalModule,
            SourceSnapshot snapshot,
            SyntaxProgram program,
            String revision) {
        public Node {
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(logicalModule, "logicalModule");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(revision, "revision");
            if (!moduleId.sourceId().equals(snapshot.sourceId())) {
                throw new IllegalArgumentException("module identity does not match its source snapshot");
            }
            if (!snapshot.sourceId().equals(program.sourceId())) {
                throw new IllegalArgumentException("syntax program does not match its source snapshot");
            }
            if (!program.sourceRevision().isEmpty()
                    && !program.sourceRevision().equals(snapshot.sha256())) {
                throw new IllegalArgumentException("syntax program revision does not match its source snapshot");
            }
            snapshot.validateSpan(program.span());
            if (!ModuleRevision.isRevision(revision)) {
                throw new IllegalArgumentException("module revision must be a SHA-256 hexadecimal value");
            }
        }

        public SourceId sourceId() {
            return moduleId.sourceId();
        }

        public SourceSnapshot sourceSnapshot() {
            return snapshot;
        }

        public SyntaxProgram syntax() {
            return program;
        }

        public LogicalModuleId logicalModuleId() {
            return logicalModule.orElseThrow(() -> new IllegalStateException(
                    "root or resolver URI module has no implicit logical identity"));
        }

        public String moduleRevision() {
            return revision;
        }
    }

    /**
     * One header import edge. Aliases and selections are intentionally absent;
     * {@code importSpan} is the exact parsed logical import-path span.
     */
    public record Edge(
            ModuleId from,
            LogicalModuleId logicalTarget,
            ModuleId target,
            io.mindspice.lyra.compiler.source.SourceSpan importSpan) {
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(logicalTarget, "logicalTarget");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(importSpan, "importSpan");
            if (!from.sourceId().equals(importSpan.sourceId())) {
                throw new IllegalArgumentException("import edge span belongs to a different source");
            }
        }

        public ModuleId source() {
            return from;
        }

        public LogicalModuleId requestedModule() {
            return logicalTarget;
        }

        public ModuleId sourceModule() {
            return from;
        }

        public ModuleId targetModule() {
            return target;
        }

        public LogicalModuleId logicalModule() {
            return logicalTarget;
        }
    }

    private final ModuleId rootModule;
    private final List<Node> modules;
    private final List<Edge> edges;
    private final List<List<ModuleId>> cycles;
    private final String revision;
    private final Map<ModuleId, Node> byId;
    private final Map<LogicalModuleId, ModuleId> modulesByLogical;

    public ModuleGraph(ModuleId rootModule, List<Node> modules, List<Edge> edges) {
        this(rootModule, modules, edges, primaryLogicalModules(modules));
    }

    public ModuleGraph(
            ModuleId rootModule,
            List<Node> modules,
            List<Edge> edges,
            Map<LogicalModuleId, ModuleId> modulesByLogical) {
        this.rootModule = Objects.requireNonNull(rootModule, "rootModule");
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(modulesByLogical, "modulesByLogical");

        List<Node> sortedModules = new ArrayList<>();
        for (Node node : modules) {
            sortedModules.add(Objects.requireNonNull(node, "modules must not contain null"));
        }
        sortedModules.sort((left, right) -> MODULE_ORDER.compare(left.moduleId(), right.moduleId()));

        HashMap<ModuleId, Node> indexed = new HashMap<>();
        HashMap<PhysicalSourceKey, Node> physical = new HashMap<>();
        HashMap<LogicalModuleId, Node> logical = new HashMap<>();
        for (Node node : sortedModules) {
            if (indexed.put(node.moduleId(), node) != null) {
                throw new IllegalArgumentException("duplicate module identity: " + node.moduleId());
            }
            Node physicalPrevious = physical.put(node.snapshot().physicalKey(), node);
            if (physicalPrevious != null
                    && !physicalPrevious.moduleId().equals(node.moduleId())) {
                throw new IllegalArgumentException(
                        "physical source is claimed by multiple module identities: "
                                + node.snapshot().physicalKey());
            }
            node.logicalModule().ifPresent(logicalId -> {
                Node logicalPrevious = logical.put(logicalId, node);
                if (logicalPrevious != null
                        && !logicalPrevious.moduleId().equals(node.moduleId())) {
                    throw new IllegalArgumentException(
                            "logical module is claimed by multiple identities: " + logicalId);
                }
            });
        }
        if (!indexed.containsKey(rootModule)) {
            throw new IllegalArgumentException("root module is not present in the graph: " + rootModule);
        }

        List<Map.Entry<LogicalModuleId, ModuleId>> sortedLogicalModules =
                new ArrayList<>(modulesByLogical.entrySet());
        sortedLogicalModules.sort(Map.Entry.comparingByKey());
        LinkedHashMap<LogicalModuleId, ModuleId> logicalIndex = new LinkedHashMap<>();
        for (Map.Entry<LogicalModuleId, ModuleId> entry : sortedLogicalModules) {
            LogicalModuleId logicalId = Objects.requireNonNull(entry.getKey(),
                    "logical module map must not contain null keys");
            ModuleId moduleId = Objects.requireNonNull(entry.getValue(),
                    "logical module map must not contain null values");
            if (!indexed.containsKey(moduleId)) {
                throw new IllegalArgumentException(
                        "logical module references a module outside the graph: " + logicalId);
            }
            logicalIndex.put(logicalId, moduleId);
        }
        for (Node node : sortedModules) {
            node.logicalModule().ifPresent(logicalId -> {
                ModuleId mapped = logicalIndex.putIfAbsent(logicalId, node.moduleId());
                if (mapped != null && !mapped.equals(node.moduleId())) {
                    throw new IllegalArgumentException(
                            "logical module is claimed by multiple identities: " + logicalId);
                }
            });
        }

        List<Edge> sortedEdges = new ArrayList<>();
        for (Edge edge : edges) {
            Objects.requireNonNull(edge, "edges must not contain null");
            if (!indexed.containsKey(edge.from()) || !indexed.containsKey(edge.target())) {
                throw new IllegalArgumentException("module edge references a module outside the graph");
            }
            indexed.get(edge.from()).snapshot().validateSpan(edge.importSpan());
            sortedEdges.add(edge);
        }
        sortedEdges.sort(EDGE_ORDER);

        this.modules = List.copyOf(sortedModules);
        this.edges = List.copyOf(sortedEdges);
        this.byId = Map.copyOf(indexed);
        this.modulesByLogical = java.util.Collections.unmodifiableMap(logicalIndex);
        this.cycles = computeCycles(this.modules, this.edges);
        this.revision = computeGraphRevision(
                rootModule, this.modules, this.edges, this.modulesByLogical);
    }

    public ModuleId rootModule() {
        return rootModule;
    }

    public ModuleId root() {
        return rootModule;
    }

    /** Modules in stable {@link ModuleId} order, independent of discovery order. */
    public List<Node> modules() {
        return modules;
    }

    public List<Node> orderedModules() {
        return modules;
    }

    public List<ModuleId> moduleIds() {
        return modules.stream().map(Node::moduleId).toList();
    }

    public List<ModuleId> traversalOrder() {
        return moduleIds();
    }

    public Optional<Node> module(ModuleId moduleId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(moduleId, "moduleId")));
    }

    public Optional<Node> node(ModuleId moduleId) {
        return module(moduleId);
    }

    public Optional<ModuleId> moduleFor(LogicalModuleId logicalModule) {
        return Optional.ofNullable(modulesByLogical.get(
                Objects.requireNonNull(logicalModule, "logicalModule")));
    }

    /** Complete immutable logical-name-to-stable-module mapping, including aliases. */
    public Map<LogicalModuleId, ModuleId> logicalModules() {
        return modulesByLogical;
    }

    public Optional<ModuleId> moduleIdFor(LogicalModuleId logicalModule) {
        return moduleFor(logicalModule);
    }

    /** All header import edges in stable source/target order. */
    public List<Edge> edges() {
        return edges;
    }

    public List<Edge> imports() {
        return edges;
    }

    public List<Edge> importsFrom(ModuleId moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        return edges.stream().filter(edge -> edge.from().equals(moduleId)).toList();
    }

    public List<Edge> edgesFrom(ModuleId moduleId) {
        return importsFrom(moduleId);
    }

    /** Strongly connected components that contain a real cycle. */
    public List<List<ModuleId>> cycles() {
        return cycles;
    }

    public List<List<ModuleId>> cycleComponents() {
        return cycles;
    }

    public boolean hasCycles() {
        return !cycles.isEmpty();
    }

    /** Stable graph revision derived only from stable IDs, module revisions, and import paths. */
    public String revision() {
        return revision;
    }

    public String graphRevision() {
        return revision;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModuleGraph graph)) {
            return false;
        }
        return rootModule.equals(graph.rootModule)
                && modules.equals(graph.modules)
                && edges.equals(graph.edges)
                && modulesByLogical.equals(graph.modulesByLogical)
                && cycles.equals(graph.cycles)
                && revision.equals(graph.revision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rootModule, modules, edges, modulesByLogical, cycles, revision);
    }

    @Override
    public String toString() {
        return "ModuleGraph[root=" + rootModule
                + ", modules=" + modules.size()
                + ", edges=" + edges.size()
                + ", revision=" + revision + "]";
    }

    private static List<List<ModuleId>> computeCycles(List<Node> modules, List<Edge> edges) {
        Map<ModuleId, List<ModuleId>> adjacency = new HashMap<>();
        for (Node node : modules) {
            adjacency.put(node.moduleId(), new ArrayList<>());
        }
        for (Edge edge : edges) {
            adjacency.get(edge.from()).add(edge.target());
        }
        for (List<ModuleId> targets : adjacency.values()) {
            targets.sort(MODULE_ORDER);
        }

        Map<ModuleId, Integer> index = new HashMap<>();
        Map<ModuleId, Integer> lowLink = new HashMap<>();
        Set<ModuleId> onStack = new HashSet<>();
        List<ModuleId> stack = new ArrayList<>();
        List<List<ModuleId>> components = new ArrayList<>();
        int[] nextIndex = {0};
        for (Node node : modules) {
            if (!index.containsKey(node.moduleId())) {
                strongConnect(node.moduleId(), adjacency, index, lowLink, onStack, stack,
                        nextIndex, components);
            }
        }

        List<List<ModuleId>> cycles = new ArrayList<>();
        for (List<ModuleId> component : components) {
            boolean selfCycle = component.size() == 1
                    && adjacency.get(component.getFirst()).contains(component.getFirst());
            if (component.size() > 1 || selfCycle) {
                component.sort(MODULE_ORDER);
                cycles.add(List.copyOf(component));
            }
        }
        cycles.sort((left, right) -> MODULE_ORDER.compare(left.getFirst(), right.getFirst()));
        return List.copyOf(cycles);
    }

    private static void strongConnect(
            ModuleId current,
            Map<ModuleId, List<ModuleId>> adjacency,
            Map<ModuleId, Integer> index,
            Map<ModuleId, Integer> lowLink,
            Set<ModuleId> onStack,
            List<ModuleId> stack,
            int[] nextIndex,
            List<List<ModuleId>> components) {
        index.put(current, nextIndex[0]);
        lowLink.put(current, nextIndex[0]);
        nextIndex[0]++;
        stack.add(current);
        onStack.add(current);

        for (ModuleId target : adjacency.get(current)) {
            if (!index.containsKey(target)) {
                strongConnect(target, adjacency, index, lowLink, onStack, stack,
                        nextIndex, components);
                lowLink.put(current, Math.min(lowLink.get(current), lowLink.get(target)));
            } else if (onStack.contains(target)) {
                lowLink.put(current, Math.min(lowLink.get(current), index.get(target)));
            }
        }

        if (lowLink.get(current).equals(index.get(current))) {
            List<ModuleId> component = new ArrayList<>();
            ModuleId member;
            do {
                member = stack.removeLast();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(current));
            components.add(component);
        }
    }

    private static Map<LogicalModuleId, ModuleId> primaryLogicalModules(List<Node> modules) {
        Objects.requireNonNull(modules, "modules");
        LinkedHashMap<LogicalModuleId, ModuleId> logicalModules = new LinkedHashMap<>();
        for (Node node : modules) {
            Objects.requireNonNull(node, "modules must not contain null");
            node.logicalModule().ifPresent(logical -> logicalModules.put(logical, node.moduleId()));
        }
        return logicalModules;
    }

    private static String computeGraphRevision(
            ModuleId root,
            List<Node> modules,
            List<Edge> edges,
            Map<LogicalModuleId, ModuleId> modulesByLogical) {
        MessageDigest digest = sha256();
        putBytes(digest, "LYRA-MODULE-GRAPH-REVISION".getBytes(StandardCharsets.UTF_8));
        putInt(digest, ModuleRevision.LANGUAGE_CONTRACT_VERSION);
        putString(digest, root.value());
        putInt(digest, root.isUri() ? 1 : 0);
        putInt(digest, modules.size());
        for (Node node : modules) {
            putString(digest, node.moduleId().value());
            putInt(digest, node.moduleId().isUri() ? 1 : 0);
            putString(digest, node.logicalModule().map(LogicalModuleId::value).orElse(""));
            putString(digest, node.revision());
        }
        putInt(digest, modulesByLogical.size());
        for (Map.Entry<LogicalModuleId, ModuleId> entry : modulesByLogical.entrySet()) {
            putString(digest, entry.getKey().value());
            putString(digest, entry.getValue().value());
            putInt(digest, entry.getValue().isUri() ? 1 : 0);
        }
        putInt(digest, edges.size());
        for (Edge edge : edges) {
            putString(digest, edge.from().value());
            putInt(digest, edge.from().isUri() ? 1 : 0);
            putString(digest, edge.logicalTarget().value());
            putString(digest, edge.target().value());
            putInt(digest, edge.target().isUri() ? 1 : 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void putString(MessageDigest digest, String value) {
        putBytes(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void putBytes(MessageDigest digest, byte[] value) {
        putInt(digest, value.length);
        digest.update(value);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(value)
                .array());
    }
}
