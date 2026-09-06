import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds manual semantic-test graphs with the source import path as each edge span. */
final class CanonicalModuleGraph {
    private CanonicalModuleGraph() {
    }

    static ModuleGraph create(
            ModuleId root,
            List<ModuleGraph.Node> modules,
            List<ModuleGraph.Edge> edges,
            Map<LogicalModuleId, ModuleId> logicalModules) {
        Map<EdgeKey, ArrayDeque<SourceSpan>> sourceSpans = new LinkedHashMap<>();
        for (ModuleGraph.Node module : modules) {
            for (SyntaxNode.ImportDeclaration declaration : module.program().imports()) {
                LogicalModuleId logical = LogicalModuleId.fromImportPath(declaration.path());
                ModuleId target = logicalModules.get(logical);
                if (target != null) {
                    sourceSpans.computeIfAbsent(
                                    new EdgeKey(module.moduleId(), logical, target),
                                    ignored -> new ArrayDeque<>())
                            .addLast(declaration.path().span());
                }
            }
        }

        List<ModuleGraph.Edge> canonical = new ArrayList<>();
        for (ModuleGraph.Edge edge : edges) {
            EdgeKey key = new EdgeKey(edge.from(), edge.logicalTarget(), edge.target());
            ArrayDeque<SourceSpan> candidates = sourceSpans.get(key);
            if (candidates == null || candidates.isEmpty()) {
                throw new AssertionError(
                        "manual module edge has no matching source import header: " + edge);
            }
            canonical.add(new ModuleGraph.Edge(
                    edge.from(), edge.logicalTarget(), edge.target(),
                    candidates.removeFirst()));
        }
        if (sourceSpans.values().stream().anyMatch(spans -> !spans.isEmpty())) {
            throw new AssertionError("manual module graph omits a source import edge");
        }
        return new ModuleGraph(root, modules, canonical, logicalModules);
    }

    private record EdgeKey(
            ModuleId source,
            LogicalModuleId logical,
            ModuleId target) {
    }
}
