package io.mindspice.lyra.runtime;

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
 * The fixed entry point embedded in bundled Lyra artifacts.
 *
 * <p>The launcher deliberately owns no execution path of its own.  It locates
 * the artifact that supplied this class, lets {@link LyraRuntime} perform the
 * normal compatibility and definition checks, and invokes the exact generated
 * root export contract.</p>
 */
public final class LyraLauncher {
    private static final String MAIN_NAME = "main";
    private static final String MAIN_SIGNATURE = "Fn<Array<String>;I32>";

    private LyraLauncher() {
    }

    /** JVM entry point for a bundled artifact. */
    public static void main(String[] args) {
        int status = execute(args);
        System.exit(status);
    }

    private static int execute(String[] rawArgs) {
        Objects.requireNonNull(rawArgs, "args");
        String[] programArgs;
        programArgs = programArguments(rawArgs);

        Path artifact;
        try {
            artifact = artifactPath();
        } catch (RuntimeException failure) {
            rethrowFatal(failure);
            write(System.err, "LYR-INTERNAL: cannot locate bundled Lyra artifact: "
                    + message(failure) + "\n");
            return 2;
        }

        LoadedArtifact loaded = null;
        ModuleHandle module = null;
        Throwable primary = null;
        boolean invocationFailure = false;
        int status = 0;
        try {
            // The option permits a preview-requiring artifact to reach the
            // JVM's own preview check.  A process started without
            // --enable-preview then fails at class definition, while a
            // correctly started process succeeds.  The JAR cannot enable
            // preview for itself.
            LoadOptions options = LoadOptions.defaults()
                    .withPreviewEnabled(true)
                    .withIoEnvironment(new RuntimeIoEnvironment(
                            System.in, System.out, System.err, StandardCharsets.UTF_8));
            loaded = LyraRuntime.load(artifact, options);
            requireExactMain(loaded.metadata());
            module = loaded.instantiate();
            ExportHandle main = exactMain(module);
            status = (int) main.methodHandle().invokeExact(programArgs);
        } catch (Throwable failure) {
            rethrowFatal(failure);
            primary = failure;
            invocationFailure = true;
        } finally {
            if (module != null) {
                try {
                    module.close();
                } catch (Throwable failure) {
                    rethrowFatal(failure);
                    primary = suppress(primary, failure);
                }
            }
            if (loaded != null) {
                try {
                    loaded.close();
                } catch (Throwable failure) {
                    rethrowFatal(failure);
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

    private static void requireExactMain(ArtifactMetadata metadata) {
        List<ExportMetadata> mains = metadata.exports().stream()
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

    private static String[] programArguments(String[] args) {
        // java -jar supplies the program argument vector directly.  The CLI's
        // `--` separator is not part of the runnable-JAR protocol, so a
        // literal leading `--` must remain a program argument.
        return args.clone();
    }

    private static Path artifactPath() {
        CodeSource source = LyraLauncher.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("launcher has no code source");
        }
        try {
            URI location = source.getLocation().toURI();
            if (!"file".equalsIgnoreCase(location.getScheme())) {
                throw new IllegalStateException("launcher code source is not a file: " + location);
            }
            Path path = Path.of(location).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("launcher code source is not a bundled JAR: " + path);
            }
            return path;
        } catch (java.net.URISyntaxException | IllegalArgumentException failure) {
            throw new IllegalStateException("invalid launcher code source", failure);
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
            result.append("LYR-INTERNAL: cannot load bundled Lyra artifact: ")
                    .append(message(io));
        } else {
            result.append("LYR-INTERNAL: launcher failed: ").append(message(failure));
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
            // There is no reliable diagnostic channel left if stderr itself
            // fails.  The deterministic process status is still returned.
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError error) {
            throw error;
        }
        if (failure instanceof ThreadDeath death) {
            throw death;
        }
        if (failure instanceof LinkageError error) {
            throw error;
        }
    }
}
