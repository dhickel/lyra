package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.ArtifactSource;
import io.mindspice.lyra.runtime.ExportHandle;
import io.mindspice.lyra.runtime.LyraArithmeticException;
import io.mindspice.lyra.runtime.LyraBoundsException;
import io.mindspice.lyra.runtime.LyraClosedException;
import io.mindspice.lyra.runtime.LyraCompatibilityException;
import io.mindspice.lyra.runtime.LyraConversionException;
import io.mindspice.lyra.runtime.LyraInitializationException;
import io.mindspice.lyra.runtime.LyraLinkException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeException;
import io.mindspice.lyra.runtime.LyraSignature;
import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.ModuleHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-22 end-to-end conformance matrix.  The earlier phase tests prove each
 * subsystem in isolation; these tests deliberately cross the public compiler,
 * artifact, generated-facade, and runtime boundaries.
 */
final class Phase22ConformanceTest {
    private static final String MATRIX_SOURCE = """
            let @pub @mut counter :I32 = 0
            let @pub values :Array<I32> = Array[1 2 3]
            let @pub pair :Tuple<I32,String> = Tuple[7 "A😀"]
            let helper :Fn<;I32> = (=> | | 4)
            let @pub signed :Fn<I8,I16;I16> = (=> |left right| (+ left right))
            let @pub unsigned :Fn<U8;String> = (=> |value| String[value])
            let @pub quotient :Fn<I32,I32;F32> = (=> |left right| (/ left right))
            let @pub remainder :Fn<I32,I32;I32> = (=> |left right| (% left right))
            let @pub power :Fn<I32,I32;I32> = (=> |left right| (^ left right))
            let @pub reciprocal :Fn<F64;F64> = (=> |value| (/ value))
            let @pub increment :Fn<U8;U8> = (=> |value| (++ value))
            let @pub decrement :Fn<I8;I8> = (=> |value| (-- value))
            let @pub compare :Fn<F64,F64;Bool> = (=> |left right| (<= left right))
            let @pub chain :Fn<I32,I32,I32;Bool> = (=> |left middle right| (< left middle right))
            let @pub inequality :Fn<I32,I32;Bool> = (=> |left right| (!= left right))
            let @pub exclusive :Fn<Bool,Bool;Bool> = (=> |left right| (xor left right))
            let @pub truth :Fn<String;Bool> = (=> |value| (not value))
            let @pub conjunction :Fn<Bool;Bool> = (=> |value| (and value #F))
            let @pub disjunction :Fn<Bool;Bool> = (=> |value| (or value #T))
            let @pub branch :Fn<Bool;I32> = (=> |value| (value -> 1 : 2))
            let @pub direct :Fn<;I32> = (=> | | ::helper[])
            let @pub inline :Fn<I32;I32> = (=> :I32 |value :I32| (+ value 1))
            let @pub applyCompact :Fn<Fn<I32;I32>,I32;I32> = (=> |function value| (function value))
            let @pub compactUse :Fn<;I32> = (=> | | ::applyCompact[|value| (+ value 2) 4])
            let @pub apply :Fn<Fn<;I32>;I32> = (=> |function| (function))
            let @pub thenOnly :Fn<Bool;Unit> = (=> |value| (value -> ()))
            let @pub narrow :Fn<@nil I32;I32> = (=> |value| (value present -> present : 7))
            let @pub coalesce :Fn<@nil I32;I32> = (=> |value| (value : 7))
            let @pub convert :Fn<I64;I32> = (=> |value| I32[value])
            let @pub boolText :Fn<Bool;String> = (=> |value| String[value])
            let @pub charText :Fn<Char;String> = (=> |value| String[value])
            let @pub floatText :Fn<F64;String> = (=> |value| String[value])
            let @pub nilString :Fn<@nil String;String> = (=> |value| (value : "none"))
            let @pub concat :Fn<String,String;String> = (=> |left right| (+ left right))
            let @pub stringLength :Fn<String;I32> = (=> |value| value:.length)
            let @pub stringAt :Fn<String,I32;Char> = (=> |value index| value[index])
            let @pub arrayLength :Fn<Array<I32>;I32> = (=> |value| value:.length)
            let @pub emptyLength :Fn<;I32> = (=> | | Array<I32>[]:.length)
            let @pub arrayAt :Fn<Array<I32>,I32;I32> = (=> |value index| value[index])
            let @pub arrayEqual :Fn<Array<I32>,Array<I32>;Bool> = (=> |left right| (== left right))
            let @pub arraySame :Fn<Array<I32>,Array<I32>;Bool> = (=> |left right| (eq? left right))
            let @pub tupleText :Fn<Tuple<I32,String>;String> = (=> |value| value:.1)
            let @pub tupleEqual :Fn<Tuple<I32,String>,Tuple<I32,String>;Bool> = (=> |left right| (== left right))
            let @pub unitText :Fn<;String> = (=> | | String[()])
            let @pub makeTuple :Fn<;Tuple<I32,String>> = (=> | | Tuple[9 "tuple"])
            let @pub maker :Fn<I32;Fn<;I32>> = (=> |value| (=> | | value))
            let @pub functionSame :Fn<Fn<;I32>,Fn<;I32>;Bool> = (=> |left right| (eq? left right))
            let @pub functionDifferent :Fn<Fn<;I32>,Fn<;I32>;Bool> = (=> |left right| (!eq? left right))
            let @pub setCounter :Fn<I32;Unit> = (=> |value| (counter := value))
            let @pub mutate :Fn<@mut Array<I32>,I32;Unit> = (=> |@mut value index| (value[index] := 9))
            let @pub readCounter :Fn<;I32> = (=> | | counter)
            let @pub readValues :Fn<;Array<I32>> = (=> | | values)
            let @pub readFirst :Fn<;I32> = (=> | | values[0])
            let @pub answer :I32 = 42
            """;

    @TempDir
    Path temp;

    @Test
    void completeLanguageMatrixReachesValidatedIrAndPublicRuntime() throws Throwable {
        CompiledArtifact artifact = compile("matrix.lyra", MATRIX_SOURCE);
        assertTrue(artifact.metadata().exports().size() >= 30);
        assertEquals(artifact.metadata(),
                io.mindspice.lyra.runtime.ArtifactMetadataReader.read(artifact.metadata().canonicalUtf8()));
        assertTrue(artifact.classes().keySet().stream().allMatch(name ->
                name.startsWith(artifact.metadata().javaPackage() + ".")));
        assertTrue(artifact.entries().containsKey("META-INF/lyra/debug-map.json"));

        int[] escaped;
        try (Fixture fixture = Fixture.open(artifact)) {
            ExportHandle exact = fixture.module.export("signed",
                    LyraSignature.parse("Fn<I8,I16;I16>"));
            assertEquals(MethodType.methodType(short.class, byte.class, short.class), exact.methodType());
            assertEquals(exact.methodType(), exact.methodHandle().type());
            short exactResult = (short) exact.methodHandle().invokeExact((byte) 1, (short) 2);
            assertEquals((short) 3, exactResult);
            assertEquals((short) 3, fixture.call("signed", "Fn<I8,I16;I16>", (byte) 1, (short) 2));
            assertEquals("255", fixture.call("unsigned", "Fn<U8;String>", (byte) -1));
            assertEquals(2.5f, (Float) fixture.call("quotient", "Fn<I32,I32;F32>", 5, 2), 0.0f);
            assertEquals(1, fixture.call("remainder", "Fn<I32,I32;I32>", 5, 2));
            assertEquals(8, fixture.call("power", "Fn<I32,I32;I32>", 2, 3));
            assertEquals(0.25, (Double) fixture.call("reciprocal", "Fn<F64;F64>", 4.0), 0.0);
            assertEquals((byte) 2, fixture.call("increment", "Fn<U8;U8>", (byte) 1));
            assertEquals((byte) 1, fixture.call("decrement", "Fn<I8;I8>", (byte) 2));
            assertEquals(true, fixture.call("compare", "Fn<F64,F64;Bool>", 1.0, 2.0));
            assertEquals(true, fixture.call("chain", "Fn<I32,I32,I32;Bool>", 1, 2, 3));
            assertEquals(true, fixture.call("inequality", "Fn<I32,I32;Bool>", 1, 2));
            assertEquals(true, fixture.call("exclusive", "Fn<Bool,Bool;Bool>", true, false));
            assertEquals(true, fixture.call("truth", "Fn<String;Bool>", ""));
            assertEquals(false, fixture.call("conjunction", "Fn<Bool;Bool>", true));
            assertEquals(true, fixture.call("disjunction", "Fn<Bool;Bool>", false));
            assertEquals(1, fixture.call("branch", "Fn<Bool;I32>", true));
            assertEquals(2, fixture.call("branch", "Fn<Bool;I32>", false));
            assertEquals(4, fixture.call("direct", "Fn<;I32>"));
            assertEquals(5, fixture.call("inline", "Fn<I32;I32>", 4));
            assertEquals(6, fixture.call("compactUse", "Fn<;I32>"));
            Object directClosure = fixture.call("maker", "Fn<I32;Fn<;I32>>", 4);
            assertEquals(4, fixture.call("apply", "Fn<Fn<;I32>;I32>", directClosure));
            assertEquals(null, fixture.call("thenOnly", "Fn<Bool;Unit>", false));
            assertEquals(null, fixture.call("thenOnly", "Fn<Bool;Unit>", true));
            assertEquals(7, fixture.call("narrow", "Fn<@nilI32;I32>", new Object[]{null}));
            assertEquals(11, fixture.call("narrow", "Fn<@nilI32;I32>", 11));
            assertEquals(7, fixture.call("coalesce", "Fn<@nilI32;I32>", new Object[]{null}));
            assertEquals(11, fixture.call("coalesce", "Fn<@nilI32;I32>", 11));
            assertEquals(12, fixture.call("convert", "Fn<I64;I32>", 12L));
            assertEquals("#T", fixture.call("boolText", "Fn<Bool;String>", true));
            assertEquals("x", fixture.call("charText", "Fn<Char;String>", 'x'));
            assertEquals("1.0e7", fixture.call("floatText", "Fn<F64;String>", 1.0e7));
            assertEquals("none", fixture.call("nilString", "Fn<@nilString;String>", new Object[]{null}));
            assertEquals("A😀", fixture.call("concat", "Fn<String,String;String>", "A", "😀"));
            assertEquals(3, fixture.call("stringLength", "Fn<String;I32>", "😀x"));
            assertEquals('\uD83D', fixture.call("stringAt", "Fn<String,I32;Char>", "😀x", 0));
            assertEquals(3, fixture.call("arrayLength", "Fn<Array<I32>;I32>", new int[]{1, 2, 3}));
            assertEquals(0, fixture.call("emptyLength", "Fn<;I32>"));
            assertEquals(2, fixture.call("arrayAt", "Fn<Array<I32>,I32;I32>", new int[]{1, 2, 3}, 1));
            assertEquals(true, fixture.call("arrayEqual", "Fn<Array<I32>,Array<I32>;Bool>",
                    new int[]{1, 2}, new int[]{1, 2}));
            escaped = (int[]) fixture.call("readValues", "Fn<;Array<I32>>");
            assertEquals(true, fixture.call("arraySame", "Fn<Array<I32>,Array<I32>;Bool>", escaped, escaped));
            assertEquals(false, fixture.call("arraySame", "Fn<Array<I32>,Array<I32>;Bool>",
                    escaped, new int[]{1, 2, 3}));
            Object tuple = fixture.call("makeTuple", "Fn<;Tuple<I32,String>>");
            assertEquals("tuple", fixture.call("tupleText", "Fn<Tuple<I32,String>;String>", tuple));
            assertEquals(true, fixture.call("tupleEqual",
                    "Fn<Tuple<I32,String>,Tuple<I32,String>;Bool>", tuple, tuple));
            assertEquals("()", fixture.call("unitText", "Fn<;String>"));
            Object first = fixture.call("maker", "Fn<I32;Fn<;I32>>", 4);
            Object second = fixture.call("maker", "Fn<I32;Fn<;I32>>", 4);
            assertNotSame(first, second);
            assertEquals(false, fixture.call("functionSame", "Fn<Fn<;I32>,Fn<;I32>;Bool>", first, second));
            assertEquals(true, fixture.call("functionSame", "Fn<Fn<;I32>,Fn<;I32>;Bool>", first, first));
            assertEquals(true, fixture.call("functionDifferent",
                    "Fn<Fn<;I32>,Fn<;I32>;Bool>", first, second));
            fixture.call("setCounter", "Fn<I32;Unit>", 12);
            assertEquals(12, fixture.call("readCounter", "Fn<;I32>"));
            fixture.call("mutate", "Fn<@mutArray<I32>,I32;Unit>", escaped, 0);
            assertEquals(9, fixture.call("readFirst", "Fn<;I32>"));
            escaped[1] = 8;
            assertEquals(8, fixture.call("arrayAt", "Fn<Array<I32>,I32;I32>", escaped, 1));
        }
        escaped[2] = 6;
        assertEquals(6, escaped[2]);
    }

    @Test
    void publicCompileRejectsMalformedAndDeferredSyntaxWithoutImplementationFailures() {
        List<String> malformed = List.of(
                "let =",
                "let value : I32 = 1",
                "let value = Array[1,]",
                "let value = (foo",
                "let value = (nor #T #F)",
                "let value = (try 1)",
                "let value = (match 1)",
                "let value = (for 1)",
                "let value = (<< 1 2)",
                "class Thing { missing :I32 }",
                "let value = Any[1]");
        for (String source : malformed) {
            CompileResult result = LyraCompiler.compile(CompileRequest.source("rejected.lyra", source));
            CompileResult.Failure failure = assertInstanceOf(CompileResult.Failure.class, result, source);
            assertFalse(failure.diagnostics().isEmpty(), source);
            assertTrue(failure.diagnostics().getFirst().code().value().startsWith("LYC-"), source);
            assertTrue(failure.diagnostics().stream().allMatch(diagnostic ->
                    diagnostic.primarySpan().endOffset() >= diagnostic.primarySpan().startOffset()), source);
        }
    }

    @Test
    void publicCompilePreservesSourceOrderAndNoPartialArtifactOnEagerCycle() throws Exception {
        Path root = temp.resolve("main.lyra");
        Path dependency = temp.resolve("dep.lyra");
        Files.writeString(root, "import dep let @pub value :I32 = dep->:.value\n");
        Files.writeString(dependency, "import main let @pub value :I32 = main->:.value\n");
        CompileResult result = LyraCompiler.compile(CompileRequest.path(root));
        CompileResult.Failure failure = assertInstanceOf(CompileResult.Failure.class, result);
        assertEquals("LYC-MODULE-004", failure.diagnostics().getFirst().code().value());
        assertTrue(failure.diagnostics().getFirst().relatedSpans().size() >= 1,
                failure.diagnostics().toString());
    }

    @Test
    void allResolversAreQueriedAndAmbiguityNeverUsesPrecedence() {
        LogicalModuleId rootId = LogicalModuleId.parse("demo->main");
        LogicalModuleId dependencyId = LogicalModuleId.parse("demo->dep");
        ResolvedSource root = ResolvedSource.memory(rootId, "memory:demo/main",
                "import demo->dep let @pub main :Fn<Array<String>;I32> = (=> |args| dep->:.value)\n");
        ResolvedSource first = ResolvedSource.memory(dependencyId, "memory:demo/dep-one",
                "let @pub value :I32 = 1\n");
        ResolvedSource second = ResolvedSource.memory(dependencyId, "memory:demo/dep-two",
                "let @pub value :I32 = 2\n");
        CompileRequest request = CompileRequest.builder().rootModule(rootId)
                .resolver(SourceResolver.inMemory(List.of(root, first)))
                .resolver(SourceResolver.inMemory(List.of(second))).build();
        CompileResult.Failure failure = assertInstanceOf(CompileResult.Failure.class,
                LyraCompiler.compile(request));
        assertEquals("LYC-RESOLVE-002", failure.diagnostics().getFirst().code().value(),
                failure.diagnostics().toString());
        assertTrue(failure.diagnostics().getFirst().relatedSpans().size() >= 1);
    }

    @Test
    void pathGraphCompilesDependencyFirstAndKeepsInstancesIndependent() throws Throwable {
        Path root = temp.resolve("main.lyra");
        Path dependency = temp.resolve("dep.lyra");
        Files.writeString(root, "import dep "
                + "let @pub read :Fn<;I32> = (=> | | dep->::read[]) "
                + "let @pub set :Fn<I32;Unit> = (=> |value| dep->::set[value])\n");
        Files.writeString(dependency, "let @pub @mut value :I32 = 7 "
                + "let @pub read :Fn<;I32> = (=> | | value) "
                + "let @pub set :Fn<I32;Unit> = (=> |next| (value := next))\n");
        CompiledArtifact artifact = compile(CompileRequest.path(root));
        assertEquals(List.of("path:dep.lyra", "path:main.lyra"), artifact.metadata().modules().stream()
                .map(module -> module.id().canonicalSpelling()).toList());
        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle first = loaded.instantiate();
            ModuleHandle second = loaded.instantiate();
            try {
                assertEquals(7, first.export("read", "Fn<;I32>").methodHandle().invokeWithArguments());
                first.export("set", "Fn<I32;Unit>").methodHandle().invokeWithArguments(19);
                assertEquals(19, first.export("read", "Fn<;I32>").methodHandle().invokeWithArguments());
                assertEquals(7, second.export("read", "Fn<;I32>").methodHandle().invokeWithArguments());
                assertThrows(io.mindspice.lyra.runtime.LyraLifecycleException.class, loaded::close);
            } finally {
                first.close();
                second.close();
            }
        }
    }

    @Test
    void functionOnlyImportCycleLinksAndRuntimeRecursionRemainsStructured() throws Throwable {
        Path root = temp.resolve("main.lyra");
        Path dependency = temp.resolve("dep.lyra");
        Files.writeString(root, "import dep->{g} let @pub f :Fn<;I32> = (=> | | ::g[])\n");
        Files.writeString(dependency, "import main->{f} let @pub g :Fn<;I32> = (=> | | ::f[])\n");
        CompiledArtifact artifact = compile(CompileRequest.path(root));
        try (Fixture fixture = Fixture.open(artifact)) {
            LyraRuntimeException failure = assertThrows(LyraRuntimeException.class,
                    () -> fixture.call("f", "Fn<;I32>"));
            assertEquals("LYR-STACK", failure.code());
        }
    }

    @Test
    void runtimeFailureMatrixIncludesFramesAndInitializationCause() throws Throwable {
        String source = "let zero :I32 = 0 "
                + "let @pub divide :Fn<I32;F64> = (=> |value| (/ value zero)) "
                + "let @pub at :Fn<Array<I32>,I32;I32> = (=> |values index| values[index]) "
                + "let @pub overflow :Fn<I8;I8> = (=> |value| (+ value 1I8)) "
                + "let @pub convert :Fn<F64;I32> = (=> |value| I32[value])";
        CompiledArtifact artifact = compile("failures.lyra", source);
        try (Fixture fixture = Fixture.open(artifact)) {
            LyraArithmeticException arithmetic = assertThrows(LyraArithmeticException.class,
                    () -> fixture.call("divide", "Fn<I32;F64>", 1));
            assertEquals("LYR-ARITH", arithmetic.code());
            assertTrue(arithmetic.frames().stream().anyMatch(frame -> frame.functionName().equals("divide")));
            LyraBoundsException bounds = assertThrows(LyraBoundsException.class,
                    () -> fixture.call("at", "Fn<Array<I32>,I32;I32>", new int[]{1}, 1));
            assertEquals("LYR-BOUNDS", bounds.code());
            assertThrows(LyraArithmeticException.class,
                    () -> fixture.call("overflow", "Fn<I8;I8>", (byte) 127));
            LyraConversionException conversion = assertThrows(LyraConversionException.class,
                    () -> fixture.call("convert", "Fn<F64;I32>", 2.5));
            assertEquals("LYR-CONVERT", conversion.code());
        }

        CompiledArtifact initializing = compile("initializing.lyra",
                "let zero :I32 = 0 let @pub value :F64 = (/ 1 zero)");
        try (LoadedArtifact loaded = LyraRuntime.load(initializing)) {
            LyraInitializationException failure = assertThrows(LyraInitializationException.class,
                    loaded::instantiate);
            assertEquals("LYR-INIT", failure.code());
            assertInstanceOf(LyraArithmeticException.class, failure.getCause());
        }
    }

    @Test
    void handlesAndClosuresAreOwnerConfinedAndInvalidatedAfterClose() throws Throwable {
        CompiledArtifact artifact = compile("lifecycle.lyra",
                "let @pub maker :Fn<I32;Fn<;I32>> = (=> |value| (=> | | value)) "
                        + "let @pub apply :Fn<Fn<;I32>;I32> = (=> |function| (function))");
        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle module = loaded.instantiate();
            var maker = module.export("maker", "Fn<I32;Fn<;I32>>");
            Object closure = maker.methodHandle().invokeWithArguments(4);
            AtomicReference<Throwable> wrongThread = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    module.export("maker", "Fn<I32;Fn<;I32>>");
                } catch (Throwable failure) {
                    wrongThread.set(failure);
                }
            });
            thread.start();
            thread.join();
            assertInstanceOf(io.mindspice.lyra.runtime.LyraThreadException.class, wrongThread.get());
            module.close();
            assertThrows(LyraClosedException.class,
                    () -> maker.methodHandle().invokeWithArguments(5));
            assertThrows(LyraClosedException.class,
                    () -> module.export("maker", "Fn<I32;Fn<;I32>>"));
            assertNotNull(closure);
            assertSame(LyraRuntime.sharedRuntimeLoader(), closure.getClass()
                    .getSuperclass().getClassLoader());
            assertNotSame(LyraRuntime.sharedRuntimeLoader(), closure.getClass().getClassLoader());
        }
    }

    @Test
    void corruptionAndCompatibilityAreRejectedBeforeUsableLoading() {
        CompiledArtifact artifact = compile("corrupt.lyra", "let @pub answer :I32 = 42");
        Map<String, byte[]> malformedEntries = new LinkedHashMap<>(artifact.entries());
        byte[] metadata = malformedEntries.get("META-INF/lyra/artifact.json").clone();
        metadata[0] = '[';
        malformedEntries.put("META-INF/lyra/artifact.json", metadata);
        LyraCompatibilityException malformed = assertThrows(LyraCompatibilityException.class,
                () -> LyraRuntime.load(ArtifactSource.fromEntries(artifact.metadata(), malformedEntries)));
        assertEquals("LYR-COMPAT", malformed.code());

        Map<String, byte[]> badDebugEntries = new LinkedHashMap<>(artifact.entries());
        byte[] debug = badDebugEntries.get("META-INF/lyra/debug-map.json").clone();
        debug[debug.length - 2] ^= 1;
        badDebugEntries.put("META-INF/lyra/debug-map.json", debug);
        LyraCompatibilityException badDebug = assertThrows(LyraCompatibilityException.class,
                () -> LyraRuntime.load(ArtifactSource.fromEntries(artifact.metadata(), badDebugEntries)));
        assertEquals("LYR-COMPAT", badDebug.code());

        LoadOptions incompatible = LoadOptions.defaults().withProfile(
                new io.mindspice.lyra.runtime.RuntimeProfile(
                        "java-24", 24, true, io.mindspice.lyra.runtime.RuntimeAbi.CURRENT));
        LyraRuntimeException profile = assertThrows(LyraRuntimeException.class,
                () -> LyraRuntime.load(artifact, incompatible));
        assertEquals("LYR-COMPAT", profile.code());
    }

    @Test
    void classesThinAndBundledArtifactsAreDeterministicAndExternallyVerifiable() throws Exception {
        CompiledArtifact artifact = compile("verify.lyra", MATRIX_SOURCE
                + "let @pub main :Fn<Array<String>;I32> = (=> |args| answer)\n");
        Path firstClasses = temp.resolve("first-classes");
        Path secondClasses = temp.resolve("second-classes");
        Path firstThin = temp.resolve("first-thin.jar");
        Path secondThin = temp.resolve("second-thin.jar");
        Path firstBundled = temp.resolve("first-bundled.jar");
        Path secondBundled = temp.resolve("second-bundled.jar");
        artifact.writeClasses(firstClasses);
        artifact.writeClasses(secondClasses);
        artifact.writeJar(firstThin, JarMode.THIN_JAR);
        artifact.writeJar(secondThin, JarMode.THIN_JAR);
        artifact.writeJar(firstBundled, JarMode.BUNDLED_JAR);
        artifact.writeJar(secondBundled, JarMode.BUNDLED_JAR);
        assertDirectoryBytesEqual(firstClasses, secondClasses);
        assertArrayEquals(Files.readAllBytes(firstThin), Files.readAllBytes(secondThin));
        assertArrayEquals(Files.readAllBytes(firstBundled), Files.readAllBytes(secondBundled));
        try (JarFile jar = new JarFile(firstBundled.toFile())) {
            assertEquals("io.mindspice.lyra.runtime.LyraLauncher",
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertTrue(jar.stream().noneMatch(entry -> entry.isDirectory()));
            assertTrue(jar.stream().allMatch(entry -> entry.getMethod() == java.util.zip.ZipEntry.STORED));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().contains("test-classes")
                    || entry.getName().startsWith("org/junit/")
                    || entry.getName().startsWith("org/opentest4j/")));
        }

        String facade = artifact.classes().keySet().stream()
                .filter(name -> name.contains(".$lyra$facade$")).findFirst().orElseThrow();
        ProcessResult classes = process(javaCommand(), "-Xverify:all", "-cp",
                System.getProperty("java.class.path"),
                Phase15Probe.class.getName(), firstClasses.toString(), facade);
        assertEquals(0, classes.exitValue(), classes.output());
        String runtime = Path.of(io.mindspice.lyra.runtime.LyraRuntime.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        ProcessResult thin = process(javaCommand(), "-Xverify:all", "-cp",
                firstThin + java.io.File.pathSeparator + runtime + java.io.File.pathSeparator
                        + System.getProperty("java.class.path"),
                JarProbe.class.getName(), facade, firstThin.toString());
        assertEquals(0, thin.exitValue(), thin.output());
    }

    @Test
    void generatedFacadeExposesExactJavaTypesAndCompilesAsAConsumer() throws Exception {
        CompiledArtifact artifact = compile("consumer.lyra",
                "let @pub value :I64 = 42 "
                        + "let @pub text :String = \"ok\" "
                        + "let @pub array :Array<I32> = Array[1 2] "
                        + "let @pub @mut mutable :I32 = 1 "
                        + "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))");
        String facade = artifact.classes().keySet().stream()
                .filter(name -> name.contains(".$lyra$facade$")).findFirst().orElseThrow();
        assertTrue(artifact.metadata().exports().stream()
                .filter(export -> export.name().equals("value")).findFirst().orElseThrow()
                .jvmDescriptor().endsWith("J"));
        Path generated = temp.resolve("generated");
        Path consumer = temp.resolve("consumer-classes");
        artifact.writeClasses(generated);
        Files.createDirectories(consumer);
        Path javaSource = temp.resolve("Consumer.java");
        Files.writeString(javaSource, "public final class Consumer {"
                + " public static int run() {"
                + "   " + facade + " module = " + facade + ".$lyra$create();"
                + "   int result = module.add(4, 5) + (int) module.get$value();"
                + "   if (!module.get$text().equals(\"ok\") || module.get$array()[1] != 2)"
                + "     throw new AssertionError();"
                + "   module.set$mutable(9); result += module.get$mutable();"
                + "   module.close(); return result;"
                + " }"
                + "}");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int exit = compiler.run(null, null, null, "--release", "25", "-classpath",
                generated + java.io.File.pathSeparator + System.getProperty("java.class.path"),
                "-d", consumer.toString(), javaSource.toString());
        assertEquals(0, exit);
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{consumer.toUri().toURL(), generated.toUri().toURL()},
                Phase22ConformanceTest.class.getClassLoader())) {
            assertEquals(60, Class.forName("Consumer", true, loader)
                    .getMethod("run").invoke(null));
        }
    }

    @Test
    void repeatedCleanCompilationsProduceByteIdenticalArtifacts() {
        CompileRequest request = CompileRequest.builder()
                .rootSource("repeat.lyra", MATRIX_SOURCE)
                .includeSources(true)
                .build();
        CompiledArtifact first = compile(request);
        CompiledArtifact second = compile(request);
        assertEquals(first.metadata(), second.metadata());
        assertByteMapsEqual(first.classes(), second.classes());
        assertByteMapsEqual(first.entries(), second.entries());
    }

    @Test
    void metadataAndDebugMapRemainCanonicalWithOptionalSources() {
        CompiledArtifact artifact = compile(CompileRequest.builder()
                .rootSource("unicode.lyra", "let @pub answer :I32 = 42\n")
                .includeSources(true)
                .build());
        ArtifactMetadata metadata = artifact.metadata();
        assertTrue(metadata.sources().stream().allMatch(source -> source.entryName().isPresent()));
        assertEquals(metadata.debugMapHash(),
                sha256(artifact.entries().get("META-INF/lyra/debug-map.json")));
        assertEquals(metadata.canonicalJson(),
                new String(artifact.entries().get("META-INF/lyra/artifact.json"), StandardCharsets.UTF_8));
        assertTrue(metadata.canonicalJson().indexOf("\"schemaVersion\"")
                < metadata.canonicalJson().indexOf("\"modules\""));
        assertTrue(artifact.entries().keySet().stream().noneMatch(name -> name.contains("/tmp/")));
    }

    private static CompiledArtifact compile(String sourceId, String source) {
        return compile(CompileRequest.source(sourceId, source));
    }

    private static CompiledArtifact compile(CompileRequest request) {
        CompileResult result = LyraCompiler.compile(request);
        if (result instanceof CompileResult.Success success) {
            return success.artifact();
        }
        throw new AssertionError(result.diagnostics().stream().map(Diagnostic::render).toList());
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertByteMapsEqual(Map<String, byte[]> first,
                                             Map<String, byte[]> second) {
        assertEquals(first.keySet(), second.keySet());
        for (String name : first.keySet()) {
            assertArrayEquals(first.get(name), second.get(name), name);
        }
    }

    private static void assertDirectoryBytesEqual(Path first, Path second) throws IOException {
        List<String> firstNames = files(first);
        List<String> secondNames = files(second);
        assertEquals(firstNames, secondNames);
        for (String name : firstNames) {
            assertArrayEquals(Files.readAllBytes(first.resolve(name)),
                    Files.readAllBytes(second.resolve(name)), name);
        }
    }

    private static List<String> files(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static ProcessResult process(String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        try {
            process.waitFor();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("subprocess interrupted", failure);
        }
        return new ProcessResult(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exitValue, String output) {
    }

    private static final class Fixture implements AutoCloseable {
        private final LoadedArtifact loaded;
        private final ModuleHandle module;

        private Fixture(LoadedArtifact loaded, ModuleHandle module) {
            this.loaded = loaded;
            this.module = module;
        }

        private static Fixture open(CompiledArtifact artifact) {
            LoadedArtifact loaded = LyraRuntime.load(artifact);
            return new Fixture(loaded, loaded.instantiate());
        }

        private Object call(String name, String signature, Object... arguments) throws Throwable {
            return module.export(name, LyraSignature.parse(signature)).methodHandle()
                    .invokeWithArguments(arguments);
        }

        @Override
        public void close() {
            module.close();
            loaded.close();
        }
    }

    /** External class-directory verifier/invoker used with -Xverify:all. */
    public static final class Phase15Probe {
        public static void main(String[] args) throws Exception {
            if (args.length != 2) {
                throw new IllegalArgumentException("expected classes and facade");
            }
            Map<String, byte[]> classes = new LinkedHashMap<>();
            Path root = Path.of(args[0]);
            try (var stream = Files.walk(root)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    String name = root.relativize(path).toString()
                            .replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
                    if (name.startsWith("META-INF.")) {
                        continue;
                    }
                    classes.put(name, Files.readAllBytes(path));
                }
            }
            ClassLoader loader = new ClassLoader(Phase22ConformanceTest.class.getClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name);
                    if (bytes == null) {
                        throw new ClassNotFoundException(name);
                    }
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            for (String name : classes.keySet()) {
                Class.forName(name, false, loader);
            }
            Class<?> facade = Class.forName(args[1], true, loader);
            Object instance = facade.getMethod("$lyra$create").invoke(null);
            if (!Integer.valueOf(42).equals(facade.getMethod("get$answer").invoke(instance))) {
                throw new AssertionError("answer mismatch");
            }
            facade.getMethod("close").invoke(instance);
        }
    }

    /** External thin-JAR verifier/invoker; runtime is supplied by the class path. */
    public static final class JarProbe {
        public static void main(String[] args) throws Exception {
            if (args.length != 2) {
                throw new IllegalArgumentException("expected facade and jar");
            }
            try (JarFile jar = new JarFile(args[1])) {
                for (var entry : jar.stream().filter(value -> value.getName().endsWith(".class")).toList()) {
                    String name = entry.getName().replace('/', '.').replaceAll("\\.class$", "");
                    Class.forName(name, false, JarProbe.class.getClassLoader());
                }
            }
            Class<?> facade = Class.forName(args[0]);
            Object instance = facade.getMethod("$lyra$create").invoke(null);
            if (!Integer.valueOf(42).equals(facade.getMethod("get$answer").invoke(instance))) {
                throw new AssertionError("answer mismatch");
            }
            facade.getMethod("close").invoke(instance);
        }
    }
}
