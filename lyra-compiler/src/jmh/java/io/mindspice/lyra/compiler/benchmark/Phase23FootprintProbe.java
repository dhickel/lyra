package io.mindspice.lyra.compiler.benchmark;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Captures artifact class-count and whole-JVM Metaspace observations outside
 * the JMH timing score.  Generated Lyra and a direct-Java class are measured
 * in separate JVM invocations so the observations form an explicit pair.
 * Artifact class count is a selected gate input. Whole-process class-loading
 * and Metaspace deltas remain evidence-only.
 */
public final class Phase23FootprintProbe {
    private static final String DIRECT_BASELINE_OPTION = "--direct-java";
    private static final String DIRECT_BASELINE_NAME =
            Phase23FootprintProbe.class.getName() + "$DirectJavaBaseline";

    private Phase23FootprintProbe() {
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 1 || args.length > 2
                || (args.length == 2 && !DIRECT_BASELINE_OPTION.equals(args[1]))) {
            throw new IllegalArgumentException(
                    "expected output path and optional " + DIRECT_BASELINE_OPTION);
        }
        boolean directJava = args.length == 2;
        Observation observation = directJava ? observeDirectJava() : observeGeneratedLyra();
        Files.writeString(Path.of(args[0]), observation.json(), StandardCharsets.UTF_8);
    }

    private static Observation observeGeneratedLyra() throws Throwable {
        CompileResult result = LyraCompiler.compile(
                CompileRequest.source(Phase23Benchmark.SOURCE_FILE, Phase23Benchmark.SOURCE));
        if (!(result instanceof CompileResult.Success success)) {
            throw new IllegalStateException("Phase 23 footprint fixture failed to compile: " + result);
        }
        CompiledArtifact artifact = success.artifact();
        int artifactClassCount = artifact.classes().size();
        int loadedClassCountBefore = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        long metaspaceBefore = metaspaceUsedBytes();

        int invocationResult;
        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle module = loaded.instantiate();
            try {
                MethodHandle add = module.export("add", "Fn<I32,I32;I32>").methodHandle();
                invocationResult = (int) add.invokeExact(19, 23);
            } finally {
                module.close();
            }
        }

        return new Observation("generated-lyra", artifactClassCount, null,
                loadedClassCountBefore, loadedClassCount(), metaspaceBefore,
                metaspaceUsedBytes(), invocationResult,
                "production source -> validated IR -> Java 25 Class-File artifact -> runtime load/invoke",
                "artifact class count is exact; loaded-class and Metaspace samples are whole-process observations");
    }

    private static Observation observeDirectJava() throws Throwable {
        byte[] baselineBytes = readDirectBaselineBytes();
        ClassLoader loader = new DirectBaselineLoader(
                Phase23FootprintProbe.class.getClassLoader(), baselineBytes);
        int loadedClassCountBefore = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        long metaspaceBefore = metaspaceUsedBytes();

        Class<?> baseline = loader.loadClass(DIRECT_BASELINE_NAME);
        MethodHandle add = MethodHandles.publicLookup().findStatic(
                baseline, "add", MethodType.methodType(int.class, int.class, int.class));
        int invocationResult = (int) add.invokeExact(19, 23);

        return new Observation("direct-java", null, 1,
                loadedClassCountBefore, loadedClassCount(), metaspaceBefore,
                metaspaceUsedBytes(), invocationResult,
                "one compiled direct-Java baseline class loaded and invoked in an isolated child loader",
                "baseline class count is exact; loaded-class and Metaspace samples are whole-process observations");
    }

    private static byte[] readDirectBaselineBytes() throws IOException {
        String resource = DIRECT_BASELINE_NAME.replace('.', '/') + ".class";
        ClassLoader loader = Phase23FootprintProbe.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("direct-Java baseline class is not on the test class path: " + resource);
            }
            return input.readAllBytes();
        }
    }

    private static int loadedClassCount() {
        return ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
    }

    private static long metaspaceUsedBytes() {
        long total = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (!pool.getName().toLowerCase(Locale.ROOT).contains("metaspace")) {
                continue;
            }
            MemoryUsage usage = pool.getUsage();
            if (usage != null) {
                total += usage.getUsed();
            }
        }
        return total;
    }

    /** A class-file-backed direct-Java baseline with no Lyra/runtime references. */
    public static final class DirectJavaBaseline {
        private DirectJavaBaseline() {
        }

        public static int add(int left, int right) {
            return left + right;
        }
    }

    private static final class DirectBaselineLoader extends ClassLoader {
        private final byte[] bytes;

        private DirectBaselineLoader(ClassLoader parent, byte[] bytes) {
            super(parent);
            this.bytes = bytes.clone();
        }

        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!DIRECT_BASELINE_NAME.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    private record Observation(
            String mode,
            Integer artifactClassCount,
            Integer directBaselineClassCount,
            int loadedClassCountBefore,
            int loadedClassCountAfter,
            long metaspaceUsedBytesBefore,
            long metaspaceUsedBytesAfter,
            int fixtureInvocationResult,
            String executionPath,
            String scope) {
        private String json() {
            return "{\n"
                    + "  \"schema\": \"lyra.phase23.footprint.v2\",\n"
                    + "  \"mode\": \"" + mode + "\",\n"
                    + "  \"artifactClassCount\": " + nullable(artifactClassCount) + ",\n"
                    + "  \"directBaselineClassCount\": " + nullable(directBaselineClassCount) + ",\n"
                    + "  \"loadedClassCountBefore\": " + loadedClassCountBefore + ",\n"
                    + "  \"loadedClassCountAfter\": " + loadedClassCountAfter + ",\n"
                    + "  \"loadedClassCountDelta\": "
                    + (loadedClassCountAfter - loadedClassCountBefore) + ",\n"
                    + "  \"metaspaceUsedBytesBefore\": " + metaspaceUsedBytesBefore + ",\n"
                    + "  \"metaspaceUsedBytesAfter\": " + metaspaceUsedBytesAfter + ",\n"
                    + "  \"metaspaceUsedBytesDelta\": "
                    + (metaspaceUsedBytesAfter - metaspaceUsedBytesBefore) + ",\n"
                    + "  \"fixtureInvocationResult\": " + fixtureInvocationResult + ",\n"
                    + "  \"methodology\": {\n"
                    + "    \"executionPath\": \"" + executionPath + "\",\n"
                    + "    \"scope\": \"" + scope + "\",\n"
                    + "    \"comparison\": \"generated and direct-Java observations are collected in separate JVM processes\",\n"
                    + "    \"thresholds\": \"artifact class count is evaluated by tools/phase23_gate.py; process-wide class loading and Metaspace are evidence-only\"\n"
                    + "  }\n"
                    + "}\n";
        }

        private static String nullable(Integer value) {
            return value == null ? "null" : value.toString();
        }
    }
}
