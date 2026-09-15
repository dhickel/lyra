package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.LyraRuntimeException;
import io.mindspice.lyra.runtime.RuntimeAbi;
import io.mindspice.lyra.runtime.RuntimeOptions;
import io.mindspice.lyra.runtime.RuntimeProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused Phase-17 coverage for generated Java facades and lifecycle seams. */
final class Phase17SmokeTest {
    @Test
    void facadeSurfaceIsTypedReservedAndOptionsAware() throws Exception {
        TypedIr ir = lower("let @pub close :Fn<;I32> = (=> | | 7) "
                + "let @pub equals :Fn<;I32> = (=> | | 9) "
                + "let @pub public :Fn<;I32> = (=> | | 8) "
                + "let @pub @mut answer :I32 = 1");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);

        assertTrue(Modifier.isPublic(facade.getModifiers()));
        assertTrue(Modifier.isFinal(facade.getModifiers()));
        assertTrue(Arrays.asList(facade.getInterfaces()).contains(AutoCloseable.class));
        assertTrue(Arrays.stream(facade.getDeclaredFields())
                .allMatch(field -> Modifier.isPrivate(field.getModifiers())));
        assertNotNull(facade.getMethod("invoke$close"));
        assertNotNull(facade.getMethod("lyra$public"));
        assertNotNull(facade.getMethod("get$answer"));
        assertNotNull(facade.getMethod("set$answer", int.class));
        assertThrows(NoSuchMethodException.class, () -> facade.getMethod("set$public", int.class));
        assertEquals(facade, facade.getMethod("$lyra$create").getReturnType());
        assertEquals(facade, facade.getMethod("$lyra$create", RuntimeOptions.class).getReturnType());

        Method withOptions = facade.getMethod("$lyra$create", RuntimeOptions.class);
        ArtifactMetadata metadata = (ArtifactMetadata) facade.getMethod("$lyra$metadata").invoke(null);
        assertEquals(4, metadata.exports().size());
        assertEquals(metadata, io.mindspice.lyra.runtime.ArtifactMetadataReader
                .read(metadata.canonicalUtf8()));
        assertEquals("lyra$public", metadata.exports().stream()
                .filter(export -> export.name().equals("public"))
                .findFirst().orElseThrow().javaName());

        Object instance = withOptions.invoke(null, RuntimeOptions.defaults());
        assertEquals(1, facade.getMethod("get$answer").invoke(instance));
        assertEquals(7, facade.getMethod("invoke$close").invoke(instance));
        assertEquals(9, facade.getMethod("invoke$equals").invoke(instance));
        assertEquals(8, facade.getMethod("lyra$public").invoke(instance));
        facade.getMethod("set$answer", int.class).invoke(instance, 9);
        assertEquals(9, facade.getMethod("get$answer").invoke(instance));
        facade.getMethod("close").invoke(instance);

        RuntimeAbi incompatibleAbi = RuntimeAbi.of(2, 0);
        RuntimeProfile incompatibleProfile = new RuntimeProfile(
                "java-25", 25, true, incompatibleAbi);
        RuntimeOptions incompatible = RuntimeOptions.builder()
                .profile(incompatibleProfile).runtimeAbi(incompatibleAbi).build();
        Throwable compatibility = failure(withOptions, null, incompatible);
        assertEquals("LYR-COMPAT", ((LyraRuntimeException) compatibility).code());
        RuntimeOptions foreignOwner = RuntimeOptions.builder().owner(new Thread()).build();
        Throwable owner = failure(withOptions, null, foreignOwner);
        assertEquals("LYR-THREAD", ((LyraRuntimeException) owner).code());
        Throwable nullOptions = failure(withOptions, null, new Object[]{null});
        assertTrue(nullOptions instanceof NullPointerException);
    }

    @Test
    void lifecycleIsOwnerConfinedIdempotentAndInvalidatesClosures() throws Exception {
        TypedIr ir = lower("let @pub @mut current :I32 = 1 "
                + "let @pub make :Fn<;Fn<;I32>> = (=> | | (=> | | current)) "
                + "let @pub apply :Fn<Fn<;I32>;I32> = (=> |fn| (fn))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object first = facade.getMethod("$lyra$create").invoke(null);
        Object second = facade.getMethod("$lyra$create").invoke(null);

        facade.getMethod("set$current", int.class).invoke(first, 9);
        assertEquals(9, facade.getMethod("get$current").invoke(first));
        assertEquals(1, facade.getMethod("get$current").invoke(second));

        Object firstClosure = facade.getMethod("make").invoke(first);
        Method closureInvoke = firstClosure.getClass().getMethod("invoke");
        closureInvoke.setAccessible(true);
        assertEquals(9, closureInvoke.invoke(firstClosure));
        Class<?> function = Class.forName(plan.functionInterfaces().get("Fn<;I32>"), true, loader);
        Method apply = facade.getMethod("apply", function);
        Throwable foreign = failure(apply, second, firstClosure);
        assertEquals("LYR-LINK", ((LyraRuntimeException) foreign).code());

        AtomicReference<Throwable> wrongThread = new AtomicReference<>();
        AtomicReference<Throwable> wrongClose = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                facade.getMethod("get$current").invoke(first);
            } catch (InvocationTargetException exception) {
                wrongThread.set(exception.getCause());
            } catch (Throwable throwable) {
                wrongThread.set(throwable);
            }
            try {
                facade.getMethod("close").invoke(first);
            } catch (InvocationTargetException exception) {
                wrongClose.set(exception.getCause());
            } catch (Throwable throwable) {
                wrongClose.set(throwable);
            }
        });
        thread.start();
        thread.join();
        assertEquals("LYR-THREAD", ((LyraRuntimeException) wrongThread.get()).code());
        assertEquals("LYR-THREAD", ((LyraRuntimeException) wrongClose.get()).code());

        var facadeState = facade.getDeclaredField("$lyra$state");
        facadeState.setAccessible(true);
        Object firstState = facadeState.get(first);
        facade.getMethod("close").invoke(first);
        facade.getMethod("close").invoke(first);
        assertEquals("LYR-CLOSED", code(failure(closureInvoke, firstClosure)));
        for (var field : firstState.getClass().getDeclaredFields()) {
            if (!field.getType().isPrimitive()
                    && !field.getName().equals("$lyra$lifecycle")
                    && !field.getName().startsWith("$lyra$signature$")) {
                field.setAccessible(true);
                assertEquals(null, field.get(firstState), field.getName());
            }
        }
        assertEquals("LYR-CLOSED", code(failure(facade.getMethod("get$current"), first)));
        assertEquals("LYR-CLOSED", code(failure(facade.getMethod("value$make"), first)));
        assertEquals(1, facade.getMethod("get$current").invoke(second));
        facade.getMethod("close").invoke(second);
    }

    @Test
    void mutableReexportsRouteSettersToTheOriginInstance() throws Exception {
        ModuleId main = ModuleId.path("phase17_reexport_main.lyra");
        ModuleId dependency = ModuleId.path("phase17_reexport_dep.lyra");
        ModuleGraph graph = moduleGraph(main,
                "import @pub phase17_reexport_dep->{value read plain} "
                        + "let @pub apply :Fn<Fn<;I32>;I32> = (=> |fn| (fn))",
                dependency,
                "let @pub @mut value :I32 = 7 "
                        + "let @pub @mut plain :I32 = 3 "
                        + "let @pub read :Fn<;I32> = (=> | | value)");
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object first = facade.getMethod("$lyra$create").invoke(null);
        Object second = facade.getMethod("$lyra$create").invoke(null);

        assertEquals(7, facade.getMethod("get$value").invoke(first));
        facade.getMethod("set$value", int.class).invoke(first, 13);
        assertEquals(13, facade.getMethod("get$value").invoke(first));
        assertEquals(13, facade.getMethod("read").invoke(first));
        assertEquals(7, facade.getMethod("get$value").invoke(second));
        assertEquals(3, facade.getMethod("get$plain").invoke(first));
        facade.getMethod("set$plain", int.class).invoke(first, 17);
        assertEquals(17, facade.getMethod("get$plain").invoke(first));
        assertEquals(3, facade.getMethod("get$plain").invoke(second));

        Object firstRead = facade.getMethod("value$read").invoke(first);
        Class<?> function = Class.forName(plan.functionInterfaces().get("Fn<;I32>"), true, loader);
        Throwable foreign = failure(facade.getMethod("apply", function), second, firstRead);
        assertEquals("LYR-LINK", ((LyraRuntimeException) foreign).code());
        facade.getMethod("close").invoke(first);
        facade.getMethod("close").invoke(second);
    }

    @Test
    void chainedMutableReexportsReachTheOriginalState() throws Exception {
        ModuleId main = ModuleId.path("phase17_chain_main.lyra");
        ModuleId middle = ModuleId.path("phase17_chain_middle.lyra");
        ModuleId dependency = ModuleId.path("phase17_chain_dep.lyra");
        ModuleGraph.Node mainNode = module(main,
                "import @pub phase17_chain_middle->{value}");
        ModuleGraph.Node middleNode = module(middle,
                "import @pub phase17_chain_dep->{value}");
        ModuleGraph.Node dependencyNode = module(dependency,
                "let @pub @mut value :I32 = 5");
        LogicalModuleId middleLogical = LogicalModuleId.fromSourceId(middle.sourceId());
        LogicalModuleId dependencyLogical = LogicalModuleId.fromSourceId(dependency.sourceId());
        ModuleGraph graph = new ModuleGraph(main,
                List.of(mainNode, middleNode, dependencyNode),
                List.of(new ModuleGraph.Edge(main, middleLogical, middle,
                                mainNode.program().imports().getFirst().path().span()),
                        new ModuleGraph.Edge(middle, dependencyLogical, dependency,
                                middleNode.program().imports().getFirst().path().span())),
                Map.of(middleLogical, middle, dependencyLogical, dependency));
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);

        assertEquals(5, facade.getMethod("get$value").invoke(instance));
        facade.getMethod("set$value", int.class).invoke(instance, 12);
        assertEquals(12, facade.getMethod("get$value").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void aliasedMutableReexportRoutesThroughItsExactExportIdentity() throws Exception {
        ModuleId main = ModuleId.path("phase17_alias_main.lyra");
        ModuleId dependency = ModuleId.path("phase17_alias_dep.lyra");
        ModuleGraph graph = moduleGraph(main,
                "import @pub phase17_alias_dep->{value as renamed}",
                dependency,
                "let @pub @mut value :I32 = 5");
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);

        assertEquals(5, facade.getMethod("get$renamed").invoke(instance));
        facade.getMethod("set$renamed", int.class).invoke(instance, 11);
        assertEquals(11, facade.getMethod("get$renamed").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void nullableFunctionReexportsAcceptNilAndAuthenticatedReplacement() throws Exception {
        ModuleId main = ModuleId.path("phase17_nullable_reexport_main.lyra");
        ModuleId dependency = ModuleId.path("phase17_nullable_reexport_dep.lyra");
        ModuleGraph graph = moduleGraph(main,
                "import @pub phase17_nullable_reexport_dep->{fn} "
                        + "let @pub replacement :Fn<;I32> = (=> | | 9)",
                dependency,
                "let @pub @mut fn :@nil Fn<;I32> = #NIL");
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Class<?> function = Class.forName(plan.functionInterfaces().get("Fn<;I32>"), true, loader);

        Method value = facade.getMethod("value$fn");
        assertEquals(null, value.invoke(instance));
        facade.getMethod("set$fn", function).invoke(instance, new Object[]{null});
        Object replacement = facade.getMethod("value$replacement").invoke(instance);
        facade.getMethod("set$fn", function).invoke(instance, replacement);
        assertEquals(9, facade.getMethod("fn").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void nullablePrimitiveReexportPreservesNilAndPresenceAcrossSetterBoundary() throws Exception {
        ModuleId main = ModuleId.path("phase17_nullable_value_main.lyra");
        ModuleId dependency = ModuleId.path("phase17_nullable_value_dep.lyra");
        ModuleGraph graph = moduleGraph(main,
                "import @pub phase17_nullable_value_dep->{value}",
                dependency,
                "let @pub @mut @nil value :I32 = #NIL");
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Method getter = facade.getMethod("get$value");
        Method setter = facade.getMethod("set$value", Integer.class);

        assertEquals(null, getter.invoke(instance));
        setter.invoke(instance, 31);
        assertEquals(31, getter.invoke(instance));
        setter.invoke(instance, new Object[]{null});
        assertEquals(null, getter.invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void functionSetterRejectsJavaSamImplementationWithStructuredLinkFailure() throws Exception {
        TypedIr ir = lower("let @pub @mut target :Fn<I32;I32> = (=> |value| value)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Class<?> function = Class.forName(plan.functionInterfaces().get("Fn<I32;I32>"), true, loader);
        Object javaSam = java.lang.reflect.Proxy.newProxyInstance(
                loader, new Class<?>[]{function}, (proxy, method, arguments) -> arguments[0]);
        Throwable failure = failure(facade.getMethod("set$target", function), instance, javaSam);
        assertEquals("LYR-LINK", ((LyraRuntimeException) failure).code());
        assertEquals(4, facade.getMethod("target", int.class).invoke(instance, 4));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void generatedFacadeCompilesAsAnOrdinaryJavaConsumer() throws Exception {
        TypedIr ir = lower("let @pub answer :I32 = 42 "
                + "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir, "consumer.generated");
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        Path directory = Files.createTempDirectory("lyra-phase17-consumer-");
        try {
            for (String name : artifact.classNames()) {
                Path classFile = directory.resolve(name.replace('.', '/') + ".class");
                Files.createDirectories(classFile.getParent());
                Files.write(classFile, artifact.bytes(name));
            }
            String facade = plan.moduleFacades().get(ir.rootModule().moduleId());
            String source = "public final class Consumer {\n"
                    + "  public static void main(String[] args) {\n"
                    + "    " + facade + " module = " + facade + ".$lyra$create();\n"
                    + "    if (module.get$answer() != 42 || module.add(20, 22) != 42) "
                    + "throw new AssertionError();\n"
                    + "    module.close(); module.close();\n"
                    + "  }\n"
                    + "}\n";
            Path javaSource = directory.resolve("Consumer.java");
            Files.writeString(javaSource, source, StandardCharsets.UTF_8);
            String runtime = Path.of(RuntimeOptions.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
            Process compile = new ProcessBuilder(javac(), "-cp", runtime + java.io.File.pathSeparator + directory,
                    "-d", directory.toString(), javaSource.toString())
                    .redirectErrorStream(true).start();
            String compileOutput = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, compile.waitFor(), compileOutput);
            Process run = new ProcessBuilder(javaCommand(), "-Xverify:all", "-cp",
                    runtime + java.io.File.pathSeparator + directory, "Consumer")
                    .redirectErrorStream(true).start();
            String runOutput = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, run.waitFor(), runOutput);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String code(Throwable failure) {
        return ((LyraRuntimeException) failure).code();
    }

    private static Throwable failure(Method method, Object receiver, Object... arguments) throws Exception {
        return assertThrows(InvocationTargetException.class,
                () -> method.invoke(receiver, arguments)).getCause();
    }

    private static String javac() {
        return Path.of(System.getProperty("java.home"), "bin", "javac").toString();
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static ClassLoader defineAll(JvmBytecodeArtifact artifact) throws Exception {
        ClassLoader loader = new ClassLoader(Phase17SmokeTest.class.getClassLoader()) {
            Class<?> define(String name, byte[] bytes) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Method define = loader.getClass().getDeclaredMethod("define", String.class, byte[].class);
        define.setAccessible(true);
        for (String name : artifact.classNames()) {
            define.invoke(loader, name, artifact.bytes(name));
        }
        return loader;
    }

    private static TypedIr lower(String source) {
        ModuleId id = ModuleId.path("phase17.lyra");
        return lower(new ModuleGraph(id, List.of(module(id, source)), List.of(), Map.of()));
    }

    private static TypedIr lower(ModuleGraph graph) {
        ResolvedSemanticGraph resolved = success(SemanticResolver.resolve(graph));
        TypedSemanticGraph typed = success(TypeChecker.check(resolved));
        return success(TypedIrBuilder.lower(typed));
    }

    private static ModuleGraph moduleGraph(ModuleId main, String mainSource,
                                           ModuleId dependency, String dependencySource) {
        LogicalModuleId logical = LogicalModuleId.fromSourceId(dependency.sourceId());
        ModuleGraph.Node mainNode = module(main, mainSource);
        ModuleGraph.Node dependencyNode = module(dependency, dependencySource);
        return new ModuleGraph(main,
                List.of(mainNode, dependencyNode),
                mainNode.program().imports().stream()
                        .map(importNode -> new ModuleGraph.Edge(main, logical, dependency,
                                importNode.path().span()))
                        .toList(),
                Map.of(logical, dependency));
    }

    private static ModuleGraph.Node module(ModuleId id, String source) {
        SourceSnapshot snapshot = success(SourceSnapshot.capture(id.sourceId(),
                PhysicalSourceKey.uri(URI.create("memory:" + id.value())),
                source.getBytes(StandardCharsets.UTF_8)));
        LexedSource lexed = success(Lexer.lex(snapshot));
        GrammarProgram grammar = success(GrammarMatcher.match(lexed));
        SyntaxProgram syntax = success(Parser.parse(lexed, grammar));
        return new ModuleGraph.Node(id,
                Optional.of(LogicalModuleId.fromSourceId(id.sourceId())), snapshot, syntax,
                ModuleRevision.compute(snapshot));
    }

    private static <T extends ImmutablePhaseArtifact> T success(PhaseResult<T> result) {
        if (result instanceof PhaseResult.Success<T> success) return success.value();
        throw new AssertionError(result.diagnostics());
    }
}
