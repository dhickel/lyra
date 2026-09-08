package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.ExportHandle;
import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.LyraCompatibilityException;
import io.mindspice.lyra.runtime.LyraLinkException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeException;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.PackagingMode;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Objects;

/**
 * Fixed entry point embedded in debug-capable bundled Lyra artifacts.
 *
 * <p>The launcher locates the packaged artifact, performs the profile-aware
 * debug preflight (capability declaration, embedded source context, and the
 * exact compiler/REPL closure), then runs the ordinary exact {@code main}
 * contract.  A bundled debug artifact locates itself from its own code
 * source; external classes/thin layouts pass the artifact location as the
 * first explicit argument and must run with the lyra-compiler, lyra-repl and
 * lyra-runtime jars on the class path.  This class owns package launch
 * composition only; it starts no listener and performs no activation.</p>
 */
public final class ReplLauncher {
    public static final String CLASS_NAME = "io.mindspice.lyra.repl.ReplLauncher";
    static final String COMPILER_ANCHOR = "io.mindspice.lyra.compiler.api.LyraCompiler";
    static final String COMPILER_ENTRY = COMPILER_ANCHOR.replace('.', '/') + ".class";
    private static final String MAIN_NAME = "main";
    private static final String MAIN_SIGNATURE = "Fn<Array<String>;I32>";
    /** Fixed closure probe; compilation executes no Lyra source. */
    private static final String PREFLIGHT_SOURCE = "let preflight :I32 = 1\n";

    private ReplLauncher() {
    }

    /** JVM entry point for a debug-capable bundled artifact. */
    public static void main(String[] args) {
        int status = execute(args);
        System.exit(status);
    }

    /** Runs one launcher invocation without terminating the JVM. */
    public static int execute(String[] args) {
        Objects.requireNonNull(args, "args");
        LaunchTarget target;
        try {
            target = locate(args);
        } catch (VirtualMachineError | ThreadDeath failure) {
            throw failure;
        } catch (RuntimeException failure) {
            write(System.err, "LYR-INTERNAL: cannot locate the debug artifact: "
                    + message(failure) + "\n");
            return 2;
        }

        LoadedArtifact loaded = null;
        ModuleHandle module = null;
        Throwable primary = null;
        boolean invocationFailure = false;
        int status = 0;
        try {
            LoadOptions options = LoadOptions.defaults()
                    .withPreviewEnabled(true)
                    .withIoEnvironment(new RuntimeIoEnvironment(
                            System.in, System.out, System.err, StandardCharsets.UTF_8));
            loaded = LyraRuntime.load(target.artifactPath(), options);
            preflight(loaded.metadata(), target);
            requireExactMain(loaded.metadata());
            module = loaded.instantiate();
            ExportHandle main = exactMain(module);
            status = (int) main.methodHandle().invokeExact(target.programArguments());
        } catch (VirtualMachineError | ThreadDeath failure) {
            throw failure;
        } catch (LinkageError failure) {
            if (isMissingProductionClosure(failure)) {
                primary = closureFailure(target, failure);
            } else {
                throw failure;
            }
            invocationFailure = true;
        } catch (Throwable failure) {
            primary = failure;
            invocationFailure = true;
        } finally {
            if (module != null) {
                try {
                    module.close();
                } catch (Throwable failure) {
                    if (failure instanceof VirtualMachineError
                            || failure instanceof ThreadDeath) {
                        throw failure;
                    }
                    primary = suppress(primary, failure);
                }
            }
            if (loaded != null) {
                try {
                    loaded.close();
                } catch (Throwable failure) {
                    if (failure instanceof VirtualMachineError
                            || failure instanceof ThreadDeath) {
                        throw failure;
                    }
                    primary = suppress(primary, failure);
                }
            }
        }

        if (primary != null) {
            write(System.err, renderFailure(primary));
            return primary instanceof LyraRuntimeException || !invocationFailure ? 1 : 2;
        }
        return status;
    }

    /**
     * Locates the artifact.  A launcher packaged inside the debug artifact
     * resolves its own code source; otherwise the first argument is the
     * documented explicit artifact location and the remaining arguments are
     * the program vector.
     */
    private static LaunchTarget locate(String[] args) {
        Path codeSource = ownCodeSource();
        if (codeSource != null && containsArtifactMetadata(codeSource)) {
            return new LaunchTarget(codeSource, args.clone(), true);
        }
        if (args.length == 0 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException("external debug layouts require an explicit "
                    + "artifact-location argument: java -cp lyra-runtime.jar:lyra-compiler.jar:"
                    + "lyra-repl.jar:<artifact> " + CLASS_NAME + " <artifact-location> [args...]");
        }
        Path artifact = Path.of(args[0]).toAbsolutePath().normalize();
        String[] programArguments = new String[args.length - 1];
        System.arraycopy(args, 1, programArguments, 0, programArguments.length);
        return new LaunchTarget(artifact, programArguments, false);
    }

    private static Path ownCodeSource() {
        CodeSource source = ReplLauncher.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        try {
            URI location = source.getLocation().toURI();
            if (!"file".equalsIgnoreCase(location.getScheme())) {
                return null;
            }
            Path path = Path.of(location).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) && !Files.isDirectory(path)) {
                return null;
            }
            return path;
        } catch (java.net.URISyntaxException | IllegalArgumentException failure) {
            return null;
        }
    }

    private static boolean containsArtifactMetadata(Path path) {
        try {
            if (Files.isDirectory(path)) {
                return Files.isRegularFile(path.resolve("META-INF/lyra/artifact.json"));
            }
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path.toFile(),
                    StandardCharsets.UTF_8)) {
                return zip.getEntry("META-INF/lyra/artifact.json") != null;
            }
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * Profile-aware debug preflight.  Ordinary loading already validated the
     * canonical metadata, manifest, entry inventory, embedded source hashes,
     * and class entries; this boundary rejects layouts the ordinary loader
     * cannot know and proves the declared closure genuinely works in
     * process.  The fixed compile probe never executes Lyra source.
     */
    private static void preflight(ArtifactMetadata metadata, LaunchTarget target) {
        if (metadata.replCapability().isEmpty()) {
            throw new LyraCompatibilityException("artifact is not a debug-capable REPL "
                    + "publication; rebuild it with debug capability enabled");
        }
        if (target.selfLocated() && metadata.packagingMode() != PackagingMode.BUNDLED_JAR) {
            throw new LyraCompatibilityException("self-located debug launcher requires "
                    + "bundled-jar packaging, found " + metadata.packagingMode().canonicalSpelling());
        }
        if (metadata.packagingMode() == PackagingMode.BUNDLED_JAR) {
            requireClosureEntry(target.artifactPath(),
                    ReplLauncher.class.getName().replace('.', '/') + ".class", "lyra-repl");
            requireClosureEntry(target.artifactPath(), COMPILER_ENTRY, "lyra-compiler");
        }
        try {
            CompileResult result = LyraCompiler.compile(CompileRequest.builder()
                    .source("lyra-launcher-preflight", PREFLIGHT_SOURCE)
                    .build());
            if (result instanceof CompileResult.Failure failure) {
                throw new LyraCompatibilityException("debug closure preflight could not "
                        + "compile a fixed trivial source: " + failure.diagnostics());
            }
        } catch (LinkageError failure) {
            if (isMissingProductionClosure(failure)) {
                throw closureFailure(target, failure);
            }
            throw failure;
        }
    }

    private static void requireClosureEntry(Path artifactPath, String entryName,
                                            String module) {
        try {
            boolean present;
            if (Files.isDirectory(artifactPath)) {
                present = Files.isRegularFile(artifactPath.resolve(entryName));
            } else {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(
                        artifactPath.toFile(), StandardCharsets.UTF_8)) {
                    present = zip.getEntry(entryName) != null;
                }
            }
            if (!present) {
                throw new LyraCompatibilityException("bundled debug artifact is incomplete: "
                        + "missing the production " + module + " closure entry " + entryName
                        + "; rebuild the debug bundle from the full CLI distribution");
            }
        } catch (LyraCompatibilityException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new LyraCompatibilityException("bundled debug artifact has an unreadable "
                    + "entry inventory", List.of(), List.of(), failure);
        }
    }

    private static boolean isMissingProductionClosure(LinkageError failure) {
        String name = failure instanceof NoClassDefFoundError error
                && error.getMessage() != null ? error.getMessage() : null;
        return name != null && (name.startsWith("io/mindspice/lyra/")
                || name.startsWith("io.mindspice.lyra."));
    }

    private static LyraCompatibilityException closureFailure(LaunchTarget target,
                                                              Throwable cause) {
        String layout = target.selfLocated()
                ? "bundled debug artifact is incomplete; rebuild it from the full CLI distribution"
                : "external debug layouts require the production closure on the class path: "
                + "java -cp lyra-runtime.jar:lyra-compiler.jar:lyra-repl.jar:<artifact> "
                + CLASS_NAME + " <artifact-location> [args...]";
        return new LyraCompatibilityException("debug artifact requires the declared "
                + "lyra-compiler/lyra-repl/lyra-runtime closure: " + layout,
                List.of(), List.of(), cause);
    }

    private static void requireExactMain(ArtifactMetadata metadata) {
        List<io.mindspice.lyra.runtime.ExportMetadata> mains = metadata.exports().stream()
                .filter(export -> export.moduleId().equals(metadata.rootModuleId()))
                .filter(export -> export.name().equals(MAIN_NAME))
                .toList();
        boolean valid = mains.size() == 1
                && mains.getFirst().isFunction()
                && mains.getFirst().canonicalContract().equals(MAIN_SIGNATURE);
        if (!valid) {
            throw new LyraLinkException(
                    "bundled artifact root must export exactly main :" + MAIN_SIGNATURE);
        }
    }

    private static ExportHandle exactMain(ModuleHandle module) {
        try {
            return module.export(MAIN_NAME, MAIN_SIGNATURE);
        } catch (IllegalArgumentException failure) {
            throw new LyraLinkException(
                    "bundled artifact root must export exactly main :" + MAIN_SIGNATURE,
                    List.of(), failure);
        }
    }

    private static Throwable suppress(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }

    private static String renderFailure(Throwable failure) {
        StringBuilder result = new StringBuilder();
        if (failure instanceof LyraRuntimeException lyra) {
            result.append(lyra.render());
        } else if (failure instanceof IOException io) {
            result.append("LYR-INTERNAL: cannot load the debug artifact: ")
                    .append(message(io));
        } else {
            result.append("LYR-INTERNAL: debug launcher failed: ").append(message(failure));
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            result.append("\n  suppressed: ");
            if (suppressed instanceof LyraRuntimeException lyra) {
                result.append(lyra.code()).append(": ").append(lyra.summary());
            } else {
                result.append(message(suppressed));
            }
        }
        return result.append('\n').toString();
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value;
    }

    private static void write(OutputStream output, String text) {
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ignored) {
            // No alternate diagnostic channel remains if stderr itself fails.
        }
    }

    private record LaunchTarget(Path artifactPath, String[] programArguments,
                                boolean selfLocated) {
        private LaunchTarget {
            programArguments = programArguments == null ? null : programArguments.clone();
        }

        @Override
        public String[] programArguments() {
            return programArguments.clone();
        }
    }
}
