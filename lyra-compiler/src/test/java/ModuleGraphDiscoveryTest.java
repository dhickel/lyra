import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleGraphDiscovery;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.RevisionOptions;
import io.mindspice.lyra.compiler.source.SourceConfiguration;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceResolver;
import io.mindspice.lyra.compiler.source.SourceRoot;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Assertion-grade dependency-free tests for source resolution and graph discovery. */
public final class ModuleGraphDiscoveryTest {
    @Test
    public void testFileRootsMapLogicalPathsToStableRelativeModuleIds() throws Exception {
        fileRootsMapLogicalPathsToStableRelativeModuleIds();
    }

    @Test
    public void testMemoryResolversSupplyStableUris() throws Exception {
        memoryResolversSupplyStableUris();
    }

    @Test
    public void testMissingAndDuplicateMatchesAreStructuredAndHaveNoGraph() throws Exception {
        missingAndDuplicateMatchesAreStructuredAndHaveNoGraph();
    }

    @Test
    public void testSymlinkRootsAndRepeatedImportsReuseOneSource() throws Exception {
        symlinkRootsAndRepeatedImportsReuseOneSource();
    }

    @Test
    public void testSymlinkedFilesCannotClaimTwoLogicalIdentities() throws Exception {
        symlinkedFilesCannotClaimTwoLogicalIdentities();
    }

    @Test
    public void testReachableHeaderImportsAndCyclesBecomeGraphEdges() throws Exception {
        reachableHeaderImportsAndCyclesBecomeGraphEdges();
    }

    @Test
    public void testOrderingAndRevisionsIgnorePhysicalPaths() throws Exception {
        orderingAndRevisionsIgnorePhysicalPaths();
    }

    @Test
    public void testIntrinsicStdIoCannotBeShadowed() throws Exception {
        intrinsicStdIoCannotBeShadowed();
    }

    @Test
    public void testUriRootReceivesReachableLogicalAliases() throws Exception {
        uriRootReceivesReachableLogicalAliases();
    }

    @Test
    public void testEdgeOrderingAndRevisionRetainEndpointKinds() {
        edgeOrderingAndRevisionRetainEndpointKinds();
    }

    @Test
    public void testInvalidRootsAndRootProgramsFailStructurally() throws Exception {
        invalidRootsAndRootProgramsFailStructurally();
    }

    @Test
    public void testMalformedReachableSourceReturnsNoPartialGraph() throws Exception {
        malformedReachableSourceReturnsNoPartialGraph();
    }

    private static void fileRootsMapLogicalPathsToStableRelativeModuleIds() throws Exception {
        Path directory = Files.createTempDirectory("lyra-module-file-root");
        try {
            write(directory.resolve("main.lyra"),
                    "import game->math->vector let value = 1");
            write(directory.resolve("game/math/vector.lyra"),
                    "let value = 2");

            PhaseResult<ModuleGraph> result = ModuleGraphDiscovery.discover(
                    directory.resolve("main.lyra"),
                    SourceConfiguration.ofRoot(directory));
            ModuleGraph graph = graph(result);

            check(graph.rootModule().equals(ModuleId.path("main.lyra")),
                    "a path root receives a source-root-relative stable module ID");
            check(graph.moduleIds().equals(List.of(
                            ModuleId.path("game/math/vector.lyra"),
                            ModuleId.path("main.lyra"))),
                    "modules are sorted by stable ModuleId rather than discovery order");
            ModuleGraph.Edge edge = graph.edges().getFirst();
            check(edge.logicalTarget().equals(LogicalModuleId.parse("game->math->vector")),
                    "an edge retains the logical import identity");
            check(edge.target().equals(ModuleId.path("game/math/vector.lyra")),
                    "the logical import maps to the root-relative POSIX source identity");
            check(edge.importSpan().equals(graph.module(ModuleId.path("main.lyra"))
                            .orElseThrow().program().imports().getFirst().path().span()),
                    "the discovered edge retains the exact source import-path span");
            check(graph.module(ModuleId.path("game/math/vector.lyra")).orElseThrow()
                            .snapshot().text().equals("let value = 2"),
                    "the discovered module is parsed from its immutable snapshot");

            ModuleGraph fromSyntaxProgram = graph(ModuleGraphDiscovery.discover(
                    graph.module(ModuleId.path("main.lyra")).orElseThrow().program(),
                    graph.module(ModuleId.path("main.lyra")).orElseThrow().snapshot(),
                    SourceConfiguration.ofRoot(directory)));
            check(fromSyntaxProgram.revision().equals(graph.revision()),
                    "discovery accepts an already parsed root SyntaxProgram and its snapshot");
        } finally {
            deleteTree(directory);
        }
    }

    private static void memoryResolversSupplyStableUris() throws Exception {
        Path directory = Files.createTempDirectory("lyra-module-memory");
        try {
            write(directory.resolve("main.lyra"), "import virtual->math let value = 1");
            LogicalModuleId logical = LogicalModuleId.parse("virtual->math");
            URI stable = URI.create("memory://library/virtual/math.lyra");
            ResolvedSource source = ResolvedSource.memory(
                    logical,
                    stable,
                    "let value = 2".getBytes(StandardCharsets.UTF_8));
            SourceConfiguration configuration = SourceConfiguration.ofPaths(
                    List.of(directory), List.of(SourceResolver.single(source)));

            ModuleGraph graph = graph(ModuleGraphDiscovery.discover(
                    directory.resolve("main.lyra"), configuration));
            ModuleGraph.Node memoryNode = graph.module(ModuleId.uri(stable)).orElseThrow();
            check(memoryNode.sourceId().equals(SourceId.uri(stable)),
                    "a resolver controls the stable URI identity");
            check(memoryNode.snapshot().physicalKey().equals(PhysicalSourceKey.uri(stable)),
                    "the resolver physical identity is retained separately");
            check(!memoryNode.sourceId().equals(SourceId.path(logical.relativeSourcePath())),
                    "resolver URI identity is not silently converted into a path identity");
        } finally {
            deleteTree(directory);
        }
    }

    private static void missingAndDuplicateMatchesAreStructuredAndHaveNoGraph() throws Exception {
        Path root = Files.createTempDirectory("lyra-module-root");
        Path first = Files.createTempDirectory("lyra-module-first");
        Path second = Files.createTempDirectory("lyra-module-second");
        try {
            write(root.resolve("main.lyra"), "import shared->value let root = 1");
            write(first.resolve("shared/value.lyra"), "let value = 1");
            write(second.resolve("shared/value.lyra"), "let value = 2");

            PhaseResult<ModuleGraph> duplicate = ModuleGraphDiscovery.discover(
                    root.resolve("main.lyra"),
                    SourceConfiguration.of(
                            List.of(new SourceRoot(root), new SourceRoot(first), new SourceRoot(second)),
                            List.of()));
            expectFailure(duplicate, CompilerDiagnosticCodes.RESOLVE_DUPLICATE_MATCH);
            check(duplicate.optionalValue().isEmpty(),
                    "duplicate resolution never publishes a partial graph");

            Files.delete(second.resolve("shared/value.lyra"));
            Files.delete(first.resolve("shared/value.lyra"));
            PhaseResult<ModuleGraph> missing = ModuleGraphDiscovery.discover(
                    root.resolve("main.lyra"),
                    SourceConfiguration.ofRoot(root));
            expectFailure(missing, CompilerDiagnosticCodes.RESOLVE_MISSING_MODULE);
            check(missing.optionalValue().isEmpty(),
                    "missing resolution never publishes a partial graph");

            write(first.resolve("shared/value.lyra"), "let value = 1");
            write(second.resolve("shared/value.lyra"), "let value = 1");
            SourceResolver resolverOne = SourceResolver.single(ResolvedSource.memory(
                    "shared->value", URI.create("memory://one/value"),
                    "let value = 1".getBytes(StandardCharsets.UTF_8)));
            SourceResolver resolverTwo = SourceResolver.single(ResolvedSource.memory(
                    "shared->value", URI.create("memory://two/value"),
                    "let value = 1".getBytes(StandardCharsets.UTF_8)));
            PhaseResult<ModuleGraph> duplicateResolvers = ModuleGraphDiscovery.discover(
                    root.resolve("main.lyra"),
                    SourceConfiguration.ofPaths(
                            List.of(root), List.of(resolverOne, resolverTwo)));
            expectFailure(duplicateResolvers, CompilerDiagnosticCodes.RESOLVE_DUPLICATE_MATCH);
            check(duplicateResolvers.optionalValue().isEmpty(),
                    "resolver ambiguity never publishes a partial graph");
        } finally {
            deleteTree(root);
            deleteTree(first);
            deleteTree(second);
        }
    }

    private static void symlinkRootsAndRepeatedImportsReuseOneSource() throws Exception {
        Path root = Files.createTempDirectory("lyra-module-symlink-root");
        Path alias = root.resolveSibling(root.getFileName() + "-alias");
        try {
            write(root.resolve("main.lyra"),
                    "import dep as d import dep->{value as v} let value = 1");
            write(root.resolve("dep.lyra"), "let value = 2");
            try {
                Files.createSymbolicLink(alias, root);
            } catch (UnsupportedOperationException | FileSystemException | SecurityException unsupported) {
                return;
            }

            AtomicInteger resolverCalls = new AtomicInteger();
            SourceResolver resolver = logical -> {
                resolverCalls.incrementAndGet();
                return Optional.empty();
            };
            ModuleGraph graph = graph(ModuleGraphDiscovery.discover(
                    root.resolve("main.lyra"),
                    SourceConfiguration.ofPaths(
                            List.of(root, alias), List.of(resolver))));
            check(graph.modules().size() == 2,
                    "canonical source roots deduplicate a directory symlink alias");
            check(graph.edges().size() == 2,
                    "both header imports remain graph edges");
            check(resolverCalls.get() == 1,
                    "one logical module is queried once even when imported repeatedly");
            check(graph.edges().get(0).target().equals(graph.edges().get(1).target()),
                    "repeated imports point to the same graph module and snapshot");
            check(graph.module(ModuleId.path("dep.lyra")).orElseThrow().snapshot()
                            == graph.module(ModuleId.path("dep.lyra")).orElseThrow().sourceSnapshot(),
                    "the published node reuses one immutable snapshot object");
        } finally {
            Files.deleteIfExists(alias);
            deleteTree(root);
        }
    }

    private static void symlinkedFilesCannotClaimTwoLogicalIdentities() throws Exception {
        Path directory = Files.createTempDirectory("lyra-module-symlink-file");
        try {
            write(directory.resolve("main.lyra"), "import first import second let root = 1");
            write(directory.resolve("first.lyra"), "let value = 2");
            try {
                Files.createSymbolicLink(directory.resolve("second.lyra"), Path.of("first.lyra"));
            } catch (UnsupportedOperationException | FileSystemException | SecurityException unsupported) {
                return;
            }
            PhaseResult<ModuleGraph> result = ModuleGraphDiscovery.discover(
                    directory.resolve("main.lyra"), SourceConfiguration.ofRoot(directory));
            expectFailure(result, CompilerDiagnosticCodes.RESOLVE_DUPLICATE_PHYSICAL_SOURCE);
            check(result.optionalValue().isEmpty(),
                    "a symlink physical-identity collision publishes no partial graph");
        } finally {
            deleteTree(directory);
        }
    }

    private static void reachableHeaderImportsAndCyclesBecomeGraphEdges() throws Exception {
        Path directory = Files.createTempDirectory("lyra-module-cycle");
        try {
            write(directory.resolve("main.lyra"),
                    "import a let ignored = \"import missing\"");
            write(directory.resolve("a.lyra"), "import b let a = 1");
            write(directory.resolve("b.lyra"), "import a let b = 2");

            ModuleGraph graph = graph(ModuleGraphDiscovery.discover(
                    directory.resolve("main.lyra"), SourceConfiguration.ofRoot(directory)));
            check(graph.modules().size() == 3,
                    "discovery traverses every reachable header import");
            check(graph.edges().size() == 3,
                    "each header import becomes one graph edge");
            check(graph.hasCycles() && graph.cycles().size() == 1,
                    "an import cycle is graph data rather than a discovery failure");
            check(graph.cycles().getFirst().equals(List.of(
                            ModuleId.path("a.lyra"), ModuleId.path("b.lyra"))),
                    "cycle members have deterministic stable-ID order");
        } finally {
            deleteTree(directory);
        }
    }

    private static void orderingAndRevisionsIgnorePhysicalPaths() throws Exception {
        Path first = Files.createTempDirectory("lyra-module-revision-one");
        Path second = Files.createTempDirectory("lyra-module-revision-two");
        try {
            String main = "import z import a let main = 1 let other = 2";
            String z = "let z = 3";
            String a = "let a = 4";
            for (Path directory : List.of(first, second)) {
                write(directory.resolve("main.lyra"), main);
                write(directory.resolve("z.lyra"), z);
                write(directory.resolve("a.lyra"), a);
            }
            ModuleGraph firstGraph = graph(ModuleGraphDiscovery.discover(
                    first.resolve("main.lyra"), SourceConfiguration.ofRoot(first)));
            ModuleGraph secondGraph = graph(ModuleGraphDiscovery.discover(
                    second.resolve("main.lyra"), SourceConfiguration.ofRoot(second)));

            check(firstGraph.moduleIds().equals(secondGraph.moduleIds()),
                    "stable module ordering excludes absolute source-root paths");
            check(firstGraph.revision().equals(secondGraph.revision()),
                    "graph revision excludes absolute source-root paths");
            for (ModuleId moduleId : firstGraph.moduleIds()) {
                check(firstGraph.module(moduleId).orElseThrow().revision()
                                .equals(secondGraph.module(moduleId).orElseThrow().revision()),
                        "module revision excludes physical paths for " + moduleId);
            }

            SourceSnapshot snapshot = firstGraph.module(ModuleId.path("a.lyra")).orElseThrow().snapshot();
            check(ModuleRevision.compute(snapshot).equals(
                            ModuleRevision.compute(snapshot, RevisionOptions.empty())),
                    "empty revision options are canonical");
            check(!ModuleRevision.compute(snapshot, RevisionOptions.of(Map.of("checked", "on")))
                            .equals(ModuleRevision.compute(snapshot)),
                    "semantics-affecting options alter module revisions");
            check(!firstGraph.moduleIds().equals(List.of(
                            ModuleId.path("main.lyra"), ModuleId.path("z.lyra"), ModuleId.path("a.lyra"))),
                    "graph output is not the source import order");
        } finally {
            deleteTree(first);
            deleteTree(second);
        }
    }

    private static void intrinsicStdIoCannotBeShadowed() throws Exception {
        Path directory = Files.createTempDirectory("lyra-module-intrinsic");
        try {
            write(directory.resolve("main.lyra"), "import std->io let value = 1");
            write(directory.resolve("std/io.lyra"), "let print = 1");
            AtomicInteger calls = new AtomicInteger();
            SourceResolver shadow = logical -> {
                calls.incrementAndGet();
                return Optional.of(ResolvedSource.memory(
                        logical, URI.create("memory://shadow/std-io"),
                        "let print = 1".getBytes(StandardCharsets.UTF_8)));
            };
            SourceConfiguration configuration = SourceConfiguration.ofPaths(
                    List.of(directory), List.of(shadow));
            ModuleGraph graph = graph(ModuleGraphDiscovery.discover(
                    directory.resolve("main.lyra"), configuration));
            ModuleId intrinsicId = graph.moduleFor(LogicalModuleId.STD_IO).orElseThrow();

            check(calls.get() == 0,
                    "std->io is satisfied before user resolvers can shadow it");
            check(intrinsicId.isUri() && intrinsicId.value().equals("lyra:intrinsic/std/io"),
                    "std->io receives the stable compiler-owned intrinsic identity");
            check(graph.module(intrinsicId).orElseThrow().snapshot().text().isEmpty(),
                    "the intrinsic node uses syntax-valid empty source without invented exports");
            check(graph.modules().size() == 2 && graph.edges().size() == 1,
                    "a std->io import publishes a complete graph with one intrinsic node");
            check(graph.revision().equals(graph(ModuleGraphDiscovery.discover(
                            directory.resolve("main.lyra"), configuration)).revision()),
                    "the intrinsic source and graph revisions are deterministic");

            write(directory.resolve("identity-shadow.lyra"), "import app->value let root = 1");
            URI reservedIdentity = URI.create("lyra:intrinsic/std/io");
            SourceResolver identityShadow = logical -> Optional.of(ResolvedSource.of(
                    logical,
                    SourceId.uri(reservedIdentity),
                    PhysicalSourceKey.uri(reservedIdentity),
                    "let value = 1".getBytes(StandardCharsets.UTF_8)));
            PhaseResult<ModuleGraph> identityResult = ModuleGraphDiscovery.discover(
                    directory.resolve("identity-shadow.lyra"),
                    SourceConfiguration.ofPaths(List.of(directory), List.of(identityShadow)));
            expectFailure(identityResult, CompilerDiagnosticCodes.RESOLVE_INTRINSIC_RESERVED);
            check(identityResult.optionalValue().isEmpty(),
                    "a resolver cannot claim the reserved intrinsic identity under another logical name");
        } finally {
            deleteTree(directory);
        }
    }

    private static void uriRootReceivesReachableLogicalAliases() {
        URI stableUri = URI.create("memory://application/root.lyra");
        byte[] sourceBytes = ("import app->root import app->alias let value = 1")
                .getBytes(StandardCharsets.UTF_8);
        SourceSnapshot snapshot = snapshot(
                SourceId.uri(stableUri), PhysicalSourceKey.uri(stableUri), sourceBytes);
        SyntaxProgram program = parse(snapshot);
        SourceResolver aliases = logical -> Optional.of(new ResolvedSource(
                logical,
                snapshot.sourceId(),
                snapshot.physicalKey(),
                snapshot.capturedUtf8Bytes()));

        ModuleGraph graph = graph(ModuleGraphDiscovery.discover(
                program,
                snapshot,
                SourceConfiguration.of(List.of(), List.of(aliases))));
        ModuleId rootId = ModuleId.uri(stableUri);

        check(graph.rootModule().equals(rootId) && graph.modules().size() == 1,
                "an unclaimed URI root reuses one stable node when imported by logical name");
        check(graph.edges().size() == 2
                        && graph.edges().stream().allMatch(edge -> edge.target().equals(rootId)),
                "multiple aliases of the root retain self-edges without duplicate nodes");
        check(graph.moduleFor(LogicalModuleId.parse("app->root")).orElseThrow().equals(rootId)
                        && graph.moduleFor(LogicalModuleId.parse("app->alias")).orElseThrow()
                        .equals(rootId),
                "the immutable graph retains every logical alias for one stable source");
        check(graph.logicalModules().size() == 2,
                "logical alias publication does not collapse distinct logical names");
        try {
            graph.logicalModules().clear();
            throw new AssertionError("logical alias mapping must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Published graph state is immutable.
        }
    }

    private static void edgeOrderingAndRevisionRetainEndpointKinds() {
        ModuleId pathFrom = ModuleId.path("kind:from");
        ModuleId uriFrom = ModuleId.uri(URI.create("kind:from"));
        ModuleId pathTarget = ModuleId.path("kind:target");
        ModuleId uriTarget = ModuleId.uri(URI.create("kind:target"));
        ModuleGraph.Node pathFromNode = emptyNode(pathFrom, "memory:path-from");
        ModuleGraph.Node uriFromNode = emptyNode(uriFrom, "memory:uri-from");
        ModuleGraph.Node pathTargetNode = emptyNode(pathTarget, "memory:path-target");
        ModuleGraph.Node uriTargetNode = emptyNode(uriTarget, "memory:uri-target");
        LogicalModuleId logicalTarget = LogicalModuleId.parse("target");
        ModuleGraph.Edge pathFromEdge = new ModuleGraph.Edge(
                pathFrom, logicalTarget, pathTarget, SourceSpan.at(pathFrom.sourceId(), 0));
        ModuleGraph.Edge uriFromEdge = new ModuleGraph.Edge(
                uriFrom, logicalTarget, pathTarget, SourceSpan.at(uriFrom.sourceId(), 0));
        ModuleGraph.Edge pathTargetEdge = new ModuleGraph.Edge(
                pathFrom, logicalTarget, pathTarget, SourceSpan.at(pathFrom.sourceId(), 0));
        ModuleGraph.Edge uriTargetEdge = new ModuleGraph.Edge(
                pathFrom, logicalTarget, uriTarget, SourceSpan.at(pathFrom.sourceId(), 0));
        List<ModuleGraph.Node> nodes = List.of(
                pathFromNode, uriFromNode, pathTargetNode, uriTargetNode);

        ModuleGraph fromOrdered = new ModuleGraph(
                pathTarget, nodes, List.of(uriFromEdge, pathFromEdge));
        check(fromOrdered.edges().equals(List.of(pathFromEdge, uriFromEdge)),
                "edge ordering distinguishes path and URI source endpoints with equal text");
        ModuleGraph targetOrdered = new ModuleGraph(
                pathTarget, nodes, List.of(uriTargetEdge, pathTargetEdge));
        check(targetOrdered.edges().equals(List.of(pathTargetEdge, uriTargetEdge)),
                "edge ordering distinguishes path and URI target endpoints with equal text");

        ModuleGraph pathFromGraph = new ModuleGraph(pathTarget, nodes, List.of(pathFromEdge));
        ModuleGraph uriFromGraph = new ModuleGraph(pathTarget, nodes, List.of(uriFromEdge));
        check(!pathFromGraph.revision().equals(uriFromGraph.revision()),
                "graph revision encodes source endpoint kind when endpoint text is identical");
        ModuleGraph pathTargetGraph = new ModuleGraph(pathTarget, nodes, List.of(pathTargetEdge));
        ModuleGraph uriTargetGraph = new ModuleGraph(pathTarget, nodes, List.of(uriTargetEdge));
        check(!pathTargetGraph.revision().equals(uriTargetGraph.revision()),
                "graph revision encodes target endpoint kind when endpoint text is identical");
    }

    private static void malformedReachableSourceReturnsNoPartialGraph() throws Exception {
        Path directory = Files.createTempDirectory("lyra-module-malformed");
        try {
            write(directory.resolve("main.lyra"), "import broken let value = 1");
            Files.write(directory.resolve("broken.lyra"), new byte[]{'\'', 'b', 'a', 'd'});
            PhaseResult<ModuleGraph> result = ModuleGraphDiscovery.discover(
                    directory.resolve("main.lyra"), SourceConfiguration.ofRoot(directory));
            expectFailure(result, CompilerDiagnosticCodes.LEX_UNTERMINATED_CHAR);
            check(result.optionalValue().isEmpty(),
                    "a malformed reachable source publishes no partial graph");
        } finally {
            deleteTree(directory);
        }
    }

    private static void invalidRootsAndRootProgramsFailStructurally() throws Exception {
        Path root = Files.createTempDirectory("lyra-module-invalid");
        Path file = root.resolve("not-a-root-directory.lyra");
        write(file, "let value = 1");
        try {
            PhaseResult<ModuleGraph> invalidConfiguration = ModuleGraphDiscovery.discover(
                    file,
                    SourceConfiguration.ofRoot(file));
            expectFailure(invalidConfiguration, CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION);
            check(invalidConfiguration.optionalValue().isEmpty(),
                    "invalid source-root configuration has no graph artifact");

            PhaseResult<ModuleGraph> missingRoot = ModuleGraphDiscovery.discover(
                    root.resolve("missing.lyra"), SourceConfiguration.ofRoot(root));
            expectFailure(missingRoot, CompilerDiagnosticCodes.RESOLVE_INVALID_ROOT);
            check(missingRoot.optionalValue().isEmpty(),
                    "an invalid root path has no graph artifact");

            PhaseResult<ModuleGraph> invalidLogicalRoot = ModuleGraphDiscovery.discover(
                    "not/a->logical-root", SourceConfiguration.ofRoot(root));
            expectFailure(invalidLogicalRoot, CompilerDiagnosticCodes.MODULE_INVALID_IMPORT_PATH);
            check(invalidLogicalRoot.optionalValue().isEmpty(),
                    "an invalid logical root has no graph artifact");
        } finally {
            deleteTree(root);
        }
    }

    private static ModuleGraph.Node emptyNode(ModuleId moduleId, String physicalUri) {
        SourceSnapshot snapshot = snapshot(
                moduleId.sourceId(),
                PhysicalSourceKey.uri(URI.create(physicalUri)),
                new byte[0]);
        SyntaxProgram program = parse(snapshot);
        return new ModuleGraph.Node(
                moduleId, Optional.empty(), snapshot, program, ModuleRevision.compute(snapshot));
    }

    @SuppressWarnings("unchecked")
    private static SourceSnapshot snapshot(
            SourceId sourceId, PhysicalSourceKey physicalKey, byte[] bytes) {
        PhaseResult<SourceSnapshot> result = SourceSnapshot.capture(sourceId, physicalKey, bytes);
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("expected snapshot success: " + render(result));
        }
        return ((PhaseResult.Success<SourceSnapshot>) success).value();
    }

    @SuppressWarnings("unchecked")
    private static SyntaxProgram parse(SourceSnapshot snapshot) {
        PhaseResult<LexedSource> lexedResult = Lexer.lex(snapshot);
        if (!(lexedResult instanceof PhaseResult.Success<?> lexedSuccess)) {
            throw new AssertionError("expected lex success: " + render(lexedResult));
        }
        LexedSource lexed = ((PhaseResult.Success<LexedSource>) lexedSuccess).value();
        PhaseResult<GrammarProgram> grammarResult = GrammarMatcher.match(lexed);
        if (!(grammarResult instanceof PhaseResult.Success<?> grammarSuccess)) {
            throw new AssertionError("expected grammar success: " + render(grammarResult));
        }
        GrammarProgram grammar = ((PhaseResult.Success<GrammarProgram>) grammarSuccess).value();
        PhaseResult<SyntaxProgram> parseResult = Parser.parse(lexed, grammar);
        if (!(parseResult instanceof PhaseResult.Success<?> parseSuccess)) {
            throw new AssertionError("expected parse success: " + render(parseResult));
        }
        return ((PhaseResult.Success<SyntaxProgram>) parseSuccess).value();
    }

    @SuppressWarnings("unchecked")
    private static ModuleGraph graph(PhaseResult<ModuleGraph> result) {
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("expected graph success: " + render(result));
        }
        return ((PhaseResult.Success<ModuleGraph>) success).value();
    }

    private static void expectFailure(
            PhaseResult<ModuleGraph> result,
            io.mindspice.lyra.compiler.diagnostic.DiagnosticCode expected) {
        check(result instanceof PhaseResult.Failure<?>,
                "expected failure " + expected + ": " + render(result));
        check(result.diagnostics().getFirst().code().equals(expected),
                "unexpected diagnostic: " + render(result));
    }

    private static String render(PhaseResult<?> result) {
        return result.diagnostics().stream().map(Diagnostic::render).toList().toString();
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path directory) throws Exception {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception exception) {
                    throw new DeleteFailure(exception);
                }
            });
        } catch (DeleteFailure exception) {
            throw exception.cause;
        }
    }

    private static final class DeleteFailure extends RuntimeException {
        private final Exception cause;

        private DeleteFailure(Exception cause) {
            this.cause = cause;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
