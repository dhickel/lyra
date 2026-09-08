package io.mindspice.lyra.cli;

import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.JarMode;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.LyraCompilerBugException;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeConstants;
import io.mindspice.lyra.runtime.LyraRuntimeException;
import io.mindspice.lyra.runtime.LyraLinkException;
import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.SourceData;
import io.mindspice.lyra.runtime.SourceFrame;
import io.mindspice.lyra.runtime.SourceFrameRenderer;
import io.mindspice.lyra.repl.LyraSession;
import io.mindspice.lyra.repl.PlainConsole;
import io.mindspice.lyra.repl.SessionOptions;
import io.mindspice.lyra.repl.remote.LoopbackEndpoint;
import io.mindspice.lyra.repl.remote.RemoteConsoleSession;
import io.mindspice.lyra.repl.remote.RemoteEndpoint;
import io.mindspice.lyra.repl.remote.RemoteOperationException;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Fixed standalone command surface for compiling and running Lyra sources. */
public final class LyraCli {
    public static final String VERSION = LyraRuntimeConstants.RUNTIME_VERSION;
    public static final String VERSION_TEXT = "Lyra " + VERSION;
    public static final String USAGE_TEXT = "Usage: lyra <command> <root> [options]";
    public static final String HELP_TEXT = USAGE_TEXT + "\n"
            + "\n"
            + "Commands:\n"
            + "  repl [ROOT] [--history PATH] [--plain] [--keymap emacs|vi]\n"
            + "  attach ENDPOINT\n"
            + "  run ROOT [--source-root DIR]* [-- ARGS...]\n"
            + "  compile ROOT [--source-root DIR]* [--output PATH]\n"
            + "         [--format classes|thin-jar|bundled-jar]\n"
            + "         [--java-package PACKAGE] [--include-sources] [--force]\n"
            + "\n"
            + "Global options:\n"
            + "  --help       Show this help message.\n"
            + "  --version    Show the Lyra version.";

    private LyraCli() {
    }

    /** JVM entry point. */
    public static void main(String[] args) {
        int status;
        try {
            status = execute(args);
        } catch (VirtualMachineError | ThreadDeath failure) {
            throw failure;
        } catch (RuntimeException failure) {
            write(System.err, "lyra: internal CLI failure: " + message(failure) + "\n");
            status = 2;
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    /** Runs without terminating the hosting JVM; injected-stream REPL invocations always use plain mode. */
    public static int execute(String[] args, InputStream input,
                              OutputStream output, OutputStream error) {
        return executeInvocation(args, input, output, error, false);
    }

    private static int executeInvocation(String[] args, InputStream input,
                                         OutputStream output, OutputStream error,
                                         boolean processTerminal) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");

        ParsedCommand command;
        try {
            command = CommandParser.parse(args);
        } catch (UsageFailure failure) {
            usageError(error, failure.getMessage());
            return 2;
        }

        if (command instanceof HelpCommand) {
            write(output, HELP_TEXT + "\n");
            return 0;
        }
        if (command instanceof VersionCommand) {
            write(output, VERSION_TEXT + "\n");
            return 0;
        }

        try {
            return switch (command) {
                case ReplCommand repl -> repl(repl, input, output, error, processTerminal);
                case AttachCommand attach -> attach(attach, input, output, error, processTerminal);
                case RunCommand run -> run(run, input, output, error);
                case CompileCommand compile -> compile(compile, error);
                case HelpCommand ignored -> 0;
                case VersionCommand ignored -> 0;
            };
        } catch (VirtualMachineError | ThreadDeath failure) {
            throw failure;
        } catch (UsageFailure failure) {
            usageError(error, failure.getMessage());
            return 2;
        } catch (LyraCompilerBugException failure) {
            write(error, "lyra: compiler failure: " + message(failure) + "\n");
            return 2;
        } catch (IOException failure) {
            write(error, "lyra: cannot access artifact or output: " + message(failure) + "\n");
            return 2;
        } catch (RuntimeException failure) {
            write(error, "lyra: internal CLI failure: " + message(failure) + "\n");
            return 2;
        }
    }

    /** Runs one CLI invocation with the process streams. */
    public static int execute(String[] args) {
        return executeInvocation(args, System.in, System.out, System.err, true);
    }

    private static int repl(ReplCommand command, InputStream input,
                            OutputStream output, OutputStream error,
                            boolean processTerminal) throws IOException {
        SessionOptions.Builder options = SessionOptions.builder();
        if (command.root().isPresent()) {
            Path root = command.root().orElseThrow();
            Path normalized = root.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new UsageFailure("REPL ROOT must be an existing non-symbolic-link directory: " + root);
            }
            options.sourceRoot(normalized);
        }

        org.jline.terminal.Terminal terminal = null;
        JLineConsole rich = null;
        try {
            if (processTerminal && !command.plain()) {
                try {
                    terminal = JLineConsole.openTerminal();
                    if (terminal != null) {
                        rich = new JLineConsole(terminal, command.keymap());
                    }
                } catch (IOException | RuntimeException | LinkageError failure) {
                    if (terminal != null) {
                        terminal.close();
                        terminal = null;
                    }
                    write(error, "lyra: rich console unavailable; using plain console: "
                            + message(failure) + "\n");
                }
            }

            InputStream sessionInput = rich == null ? input : rich.programInput();
            RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                    sessionInput, output, error, StandardCharsets.UTF_8);
            options.ioEnvironment(environment);
            try (LyraSession session = LyraSession.open(options.build())) {
                PlainConsole console = new PlainConsole(session, environment,
                        command.history().orElse(null));
                return rich == null
                        ? console.run()
                        : console.runInteractive(rich);
            }
        } finally {
            if (terminal != null) {
                terminal.close();
            }
        }
    }

    private static int attach(AttachCommand command, InputStream input,
                              OutputStream output, OutputStream error,
                              boolean processTerminal) {
        RemoteEndpoint endpoint = attachEndpoint(command);
        org.jline.terminal.Terminal terminal = null;
        try (RemoteConsoleSession session = RemoteConsoleSession.connect(endpoint)) {
            JLineConsole rich = null;
            if (processTerminal) {
                try {
                    terminal = JLineConsole.openTerminal();
                    if (terminal != null) {
                        rich = new JLineConsole(terminal, "emacs");
                    }
                } catch (IOException | RuntimeException | LinkageError failure) {
                    if (terminal != null) {
                        terminal.close();
                        terminal = null;
                    }
                    write(error, "lyra: rich console unavailable; using plain console: "
                            + message(failure) + "\n");
                }
            }
            // The environment is local console state only. It is never sent
            // over the attached protocol or used by the host session.
            RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                    input, output, error, StandardCharsets.UTF_8);
            PlainConsole console = new PlainConsole(session, environment);
            return rich == null
                    ? console.run()
                    : console.runInteractive(rich);
        } catch (RemoteOperationException failure) {
            write(error, "lyra: attach failed: " + failure.code() + ": "
                    + message(failure) + "\n");
            return 1;
        } catch (IOException failure) {
            write(error, "lyra: attach failed: " + message(failure) + "\n");
            return 1;
        } finally {
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (IOException failure) {
                    write(error, "lyra: attach terminal cleanup failed: "
                            + message(failure) + "\n");
                }
            }
        }
    }

    private static RemoteEndpoint attachEndpoint(AttachCommand command) {
        return parseAttachEndpoint(command.endpoint());
    }

    private static RemoteEndpoint parseAttachEndpoint(String spelling) {
        Objects.requireNonNull(spelling, "spelling");
        String host;
        String portSpelling;
        if (spelling.startsWith("[")) {
            int closing = spelling.indexOf(']');
            if (closing <= 1 || closing + 1 >= spelling.length()
                    || spelling.charAt(closing + 1) != ':') {
                throw new UsageFailure("invalid attach ENDPOINT (expected HOST:PORT): " + spelling);
            }
            host = spelling.substring(1, closing);
            portSpelling = spelling.substring(closing + 2);
        } else {
            int firstColon = spelling.indexOf(':');
            int lastColon = spelling.lastIndexOf(':');
            if (firstColon <= 0 || firstColon != lastColon) {
                throw new UsageFailure(
                        "invalid attach ENDPOINT (IPv6 addresses must use [HOST]:PORT): "
                                + spelling);
            }
            host = spelling.substring(0, firstColon);
            portSpelling = spelling.substring(firstColon + 1);
        }
        if (host.isBlank() || portSpelling.isBlank()
                || portSpelling.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw new UsageFailure("invalid attach ENDPOINT (expected HOST:PORT): " + spelling);
        }
        final int port;
        try {
            port = Integer.parseInt(portSpelling);
        } catch (NumberFormatException failure) {
            throw new UsageFailure("invalid attach endpoint port: " + portSpelling);
        }
        if (port < 1 || port > 65535) {
            throw new UsageFailure("attach endpoint port must be in 1..65535: " + port);
        }
        try {
            return new RemoteEndpoint(LoopbackEndpoint.of(host, port));
        } catch (java.net.UnknownHostException | IllegalArgumentException failure) {
            throw new UsageFailure("invalid loopback attach ENDPOINT: " + spelling
                    + " (" + message(failure) + ")");
        }
    }

    private static int run(RunCommand command, InputStream input,
                           OutputStream output, OutputStream error) {
        CompileRequest request = request(command.root(), command.sourceRoots(),
                null, false, false);
        CompileResult result = LyraCompiler.compile(request);
        renderDiagnostics(result.diagnostics(), command.root(), command.sourceRoots(), error);
        if (result instanceof CompileResult.Failure failure) {
            return compilerExitCode(failure.diagnostics());
        }

        CompiledArtifact artifact = ((CompileResult.Success) result).artifact();
        Diagnostic mainDiagnostic = mainDiagnostic(artifact.metadata());
        if (mainDiagnostic != null) {
            renderDiagnostic(mainDiagnostic, command.root(), command.sourceRoots(), error);
            return 1;
        }
        return invoke(artifact, command.arguments(), command.root(),
                command.sourceRoots(), input, output, error);
    }

    private static int compile(CompileCommand command, OutputStream error)
            throws IOException {
        CompileRequest request = request(command.root(), command.sourceRoots(),
                command.javaPackage(), command.includeSources(), false);
        Path output = command.output().orElseGet(() -> defaultOutput(command));
        validateOutput(output, command.format(), command.force());

        CompileResult result = LyraCompiler.compile(request);
        renderDiagnostics(result.diagnostics(), command.root(), command.sourceRoots(), error);
        if (result instanceof CompileResult.Failure failure) {
            return compilerExitCode(failure.diagnostics());
        }

        CompiledArtifact artifact = ((CompileResult.Success) result).artifact();
        if (command.format() == OutputFormat.BUNDLED_JAR) {
            Diagnostic mainDiagnostic = mainDiagnostic(artifact.metadata());
            if (mainDiagnostic != null) {
                renderDiagnostic(mainDiagnostic, command.root(), command.sourceRoots(), error);
                return 1;
            }
        }

        try {
            if (command.format() == OutputFormat.CLASSES) {
                artifact.writeClasses(output, new io.mindspice.lyra.compiler.api.WriteOptions(
                        command.force()));
            } else {
                artifact.writeJar(output, command.format().jarMode(),
                        new io.mindspice.lyra.compiler.api.WriteOptions(command.force()));
            }
        } catch (VirtualMachineError | ThreadDeath failure) {
            throw failure;
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            write(error, "lyra: cannot publish artifact: " + message(failure) + "\n");
            return 2;
        }
        return 0;
    }

    private static CompileRequest request(String root, List<Path> sourceRoots,
                                          String javaPackage, boolean includeSources,
                                          boolean previewEnabled) {
        try {
            CompileRequest.Builder builder = CompileRequest.builder();
            if (isPathRoot(root)) {
                builder.root(Path.of(root));
            } else {
                builder.rootModule(LogicalModuleId.parse(root));
            }
            for (Path sourceRoot : sourceRoots) {
                builder.sourceRoot(sourceRoot);
            }
            if (javaPackage != null) {
                builder.javaBasePackage(javaPackage);
            }
            builder.includeSources(includeSources).previewEnabled(previewEnabled);
            return builder.build();
        } catch (IllegalArgumentException failure) {
            throw new UsageFailure("invalid ROOT: " + message(failure));
        }
    }

    private static int invoke(CompiledArtifact artifact, String[] arguments,
                              String root, List<Path> sourceRoots,
                              InputStream input, OutputStream output, OutputStream error) {
        LoadedArtifact loaded = null;
        ModuleHandle module = null;
        Throwable primary = null;
        boolean invocationFailure = false;
        int status = 0;
        try {
            LoadOptions options = LoadOptions.defaults().forStreams(
                    input, output, error, StandardCharsets.UTF_8);
            loaded = LyraRuntime.load(artifact, options);
            module = loaded.instantiate();
            try {
                status = (int) module.export("main", "Fn<Array<String>;I32>")
                        .methodHandle().invokeExact(arguments);
            } catch (IllegalArgumentException failure) {
                throw new LyraLinkException(
                        "root module does not expose exact main :Fn<Array<String>;I32>",
                        List.of(), failure);
            }
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
            write(error, renderFailure(primary, root, sourceRoots));
            return primary instanceof LyraRuntimeException || !invocationFailure ? 1 : 2;
        }
        return status;
    }

    private static Diagnostic mainDiagnostic(ArtifactMetadata metadata) {
        List<io.mindspice.lyra.runtime.ExportMetadata> mains = metadata.exports().stream()
                .filter(export -> export.moduleId().equals(metadata.rootModuleId()))
                .filter(export -> export.name().equals("main"))
                .toList();
        boolean valid = mains.size() == 1
                && mains.getFirst().isFunction()
                && mains.getFirst().canonicalContract().equals("Fn<Array<String>;I32>");
        if (valid) {
            return null;
        }
        SourceId source = metadata.rootModuleId().isUri()
                ? SourceId.uri(metadata.rootModuleId().asUri())
                : SourceId.path(metadata.rootModuleId().value());
        return Diagnostic.error(CompilerDiagnosticCodes.PACKAGE_INVALID_MAIN,
                SourceSpan.at(source, 0),
                "root module must export exactly main :Fn<Array<String>;I32>");
    }

    private static void validateOutput(Path output, OutputFormat format, boolean force) {
        Objects.requireNonNull(output, "output");
        Path target = output.toAbsolutePath().normalize();
        if (target.getFileName() == null) {
            throw new UsageFailure("output path has no file name: " + output);
        }
        if (Files.isSymbolicLink(target)) {
            throw new UsageFailure("output path must not be a symbolic link: " + output);
        }
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && !force) {
            throw new UsageFailure("output already exists; use --force to replace it: " + output);
        }
        if (exists && format == OutputFormat.CLASSES && !Files.isDirectory(target,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new UsageFailure("classes output must be a directory: " + output);
        }
        if (exists && format != OutputFormat.CLASSES
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new UsageFailure("JAR output must be a regular file: " + output);
        }
    }

    private static Path defaultOutput(CompileCommand command) {
        return Path.of("build", "lyra", rootName(command.root()) + ".jar");
    }

    private static String rootName(String root) {
        String value = root;
        if (!isPathRoot(root)) {
            int separator = root.lastIndexOf("->");
            value = separator < 0 ? root : root.substring(separator + 2);
        } else {
            try {
                Path path = Path.of(root);
                Path fileName = path.getFileName();
                value = fileName == null ? root : fileName.toString();
            } catch (InvalidPathException ignored) {
                // The compiler will return the source/configuration failure;
                // this fallback only keeps the output name deterministic.
            }
        }
        return value.endsWith(".lyra")
                ? value.substring(0, value.length() - ".lyra".length()) : value;
    }

    private static boolean isPathRoot(String root) {
        return root.endsWith(".lyra");
    }

    private static int compilerExitCode(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.code().equals(CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION)
                        || diagnostic.code().equals(CompilerDiagnosticCodes.RESOLVE_INVALID_ROOT))
                ? 2 : 1;
    }

    private static void renderDiagnostics(List<Diagnostic> diagnostics, String root,
                                          List<Path> sourceRoots, OutputStream error) {
        for (Diagnostic diagnostic : diagnostics) {
            renderDiagnostic(diagnostic, root, sourceRoots, error);
        }
    }

    private static void renderDiagnostic(Diagnostic diagnostic, String root,
                                         List<Path> sourceRoots, OutputStream error) {
        String rendered = diagnostic.render();
        Optional<SourceSnapshot> snapshot = sourceSnapshot(diagnostic, root, sourceRoots);
        if (snapshot.isPresent()) {
            try {
                rendered = diagnostic.render(snapshot.orElseThrow());
            } catch (RuntimeException ignored) {
                // Offset rendering remains valid when a failing source cannot
                // provide a safe excerpt.
            }
        }
        write(error, rendered + "\n");
    }

    private static Optional<SourceSnapshot> sourceSnapshot(Diagnostic diagnostic,
                                                            String root,
                                                            List<Path> sourceRoots) {
        SourceId sourceId = diagnostic.primarySpan().sourceId();
        if (!sourceId.isPath()) {
            return Optional.empty();
        }
        ArrayList<Path> roots = new ArrayList<>(sourceRoots);
        if (roots.isEmpty() && isPathRoot(root)) {
            try {
                Path path = Path.of(root).toAbsolutePath().normalize();
                if (path.getParent() != null) {
                    roots.add(path.getParent());
                }
            } catch (InvalidPathException ignored) {
                return Optional.empty();
            }
        }
        for (Path sourceRoot : roots) {
            Path candidate;
            try {
                Path canonicalRoot = sourceRoot.toAbsolutePath().normalize();
                candidate = canonicalRoot.resolve(sourceId.value()).normalize();
                if (!candidate.startsWith(canonicalRoot)
                        || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(candidate);
                PhysicalSourceKey physical = PhysicalSourceKey.from(candidate);
                var captured = SourceSnapshot.capture(sourceId, physical, bytes);
                if (captured instanceof io.mindspice.lyra.compiler.diagnostic.PhaseResult.Success<SourceSnapshot> success) {
                    return Optional.of(success.value());
                }
            } catch (IOException | RuntimeException ignored) {
                // The compiler diagnostic is still renderable without source text.
            }
        }
        return Optional.empty();
    }

    private static void usageError(OutputStream error, String message) {
        write(error, "lyra: error: " + message + "\n" + USAGE_TEXT + "\n");
    }

    private static String renderFailure(Throwable failure, String root,
                                         List<Path> sourceRoots) {
        StringBuilder result = new StringBuilder();
        if (failure instanceof LyraRuntimeException lyra) {
            String frames = renderRuntimeFrames(lyra.frames(), root, sourceRoots);
            if (frames.isEmpty()) {
                result.append(lyra.render());
            } else {
                result.append(lyra.code()).append(": ").append(lyra.summary())
                        .append('\n').append(frames);
                if (!lyra.relatedSources().isEmpty()) {
                    result.append('\n').append("related:");
                    lyra.relatedSources().forEach(related ->
                            result.append('\n').append("  ").append(related.render()));
                }
            }
        } else {
            result.append("LYR-INTERNAL: ").append(message(failure));
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

    private static String renderRuntimeFrames(List<SourceFrame> frames, String root,
                                              List<Path> sourceRoots) {
        ArrayList<SourceFrame> rendered = new ArrayList<>();
        boolean attached = false;
        for (SourceFrame frame : frames) {
            SourceFrame visible = frame.synthetic() ? frame.nearestOrigin() : frame;
            Optional<SourceData> source = runtimeSourceData(visible, root, sourceRoots);
            if (source.isPresent()) {
                visible = visible.withSource(source.orElseThrow());
                attached = true;
            }
            rendered.add(visible);
        }
        return attached ? SourceFrameRenderer.renderFrames(rendered) : "";
    }

    private static Optional<SourceData> runtimeSourceData(SourceFrame frame, String root,
                                                          List<Path> sourceRoots) {
        if (!frame.span().sourceId().isPath()) {
            return Optional.empty();
        }
        String sourceValue = frame.span().sourceId().value();
        for (Path sourceRoot : sourceRootsFor(root, sourceRoots)) {
            try {
                Path canonicalRoot = sourceRoot.toAbsolutePath().normalize();
                Path candidate = canonicalRoot.resolve(sourceValue).normalize();
                if (!candidate.startsWith(canonicalRoot)
                        || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(candidate);
                SourceId compilerSource = SourceId.path(sourceValue);
                var captured = SourceSnapshot.capture(compilerSource,
                        PhysicalSourceKey.from(candidate), bytes);
                if (captured instanceof io.mindspice.lyra.compiler.diagnostic.PhaseResult.Success<SourceSnapshot> success) {
                    String label = frame.sourceLabel().orElse(sourceValue);
                    return Optional.of(new SourceData(frame.span().sourceId(), label,
                            success.value().text()));
                }
            } catch (IOException | RuntimeException ignored) {
                // A source excerpt is an optional presentation enhancement.
            }
        }
        return Optional.empty();
    }

    private static List<Path> sourceRootsFor(String root, List<Path> sourceRoots) {
        if (!sourceRoots.isEmpty()) {
            return sourceRoots;
        }
        if (!isPathRoot(root)) {
            return List.of();
        }
        try {
            Path path = Path.of(root).toAbsolutePath().normalize();
            return path.getParent() == null ? List.of() : List.of(path.getParent());
        } catch (InvalidPathException ignored) {
            return List.of();
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

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value;
    }

    private static void write(OutputStream output, String text) {
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ignored) {
            // The caller's output stream is not owned by the CLI.  There is
            // no alternate diagnostic channel if it fails.
        }
    }

    private sealed interface ParsedCommand permits HelpCommand, VersionCommand,
            ReplCommand, AttachCommand, RunCommand, CompileCommand {
    }

    private record ReplCommand(Optional<Path> root, Optional<Path> history,
                                boolean plain, String keymap) implements ParsedCommand {
        private ReplCommand {
            root = Objects.requireNonNull(root, "root");
            history = Objects.requireNonNull(history, "history");
        }
    }

    private record AttachCommand(String endpoint) implements ParsedCommand {
        private AttachCommand {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("attach ENDPOINT must not be blank");
            }
        }
    }

    private record HelpCommand() implements ParsedCommand {
    }

    private record VersionCommand() implements ParsedCommand {
    }

    private record RunCommand(String root, List<Path> sourceRoots, String[] arguments)
            implements ParsedCommand {
        private RunCommand {
            sourceRoots = List.copyOf(sourceRoots);
            arguments = arguments.clone();
        }

        @Override
        public String[] arguments() {
            return arguments.clone();
        }
    }

    private record CompileCommand(String root, List<Path> sourceRoots,
                                  Optional<Path> output, OutputFormat format,
                                  String javaPackage, boolean includeSources, boolean force)
            implements ParsedCommand {
        private CompileCommand {
            sourceRoots = List.copyOf(sourceRoots);
            output = Objects.requireNonNull(output, "output");
            format = Objects.requireNonNull(format, "format");
            javaPackage = Objects.requireNonNull(javaPackage, "javaPackage");
        }
    }

    private enum OutputFormat {
        CLASSES,
        THIN_JAR,
        BUNDLED_JAR;

        private JarMode jarMode() {
            return switch (this) {
                case THIN_JAR -> JarMode.THIN_JAR;
                case BUNDLED_JAR -> JarMode.BUNDLED_JAR;
                case CLASSES -> throw new IllegalStateException("classes has no JAR mode");
            };
        }

        private static OutputFormat parse(String value) {
            return switch (value) {
                case "classes" -> CLASSES;
                case "thin-jar" -> THIN_JAR;
                case "bundled-jar" -> BUNDLED_JAR;
                default -> throw new UsageFailure(
                        "unknown --format value: " + value
                                + " (expected classes, thin-jar, or bundled-jar)");
            };
        }
    }

    private static final class CommandParser {
        private CommandParser() {
        }

        private static ParsedCommand parse(String[] args) {
            if (args.length == 0) {
                throw new UsageFailure("missing command");
            }
            if (args.length == 1 && "--help".equals(args[0])) {
                return new HelpCommand();
            }
            if (args.length == 1 && "--version".equals(args[0])) {
                return new VersionCommand();
            }
            String command = args[0];
            if (command == null) {
                throw new UsageFailure("argument must not be null");
            }
            if (command.equals("repl")) {
                return parseRepl(args);
            }
            if (command.equals("attach")) {
                return parseAttach(args);
            }
            if (!command.equals("run") && !command.equals("compile")) {
                throw new UsageFailure("unknown command: " + command);
            }
            if (args.length < 2) {
                throw new UsageFailure(command + " requires ROOT");
            }
            String root = args[1];
            if (root == null) {
                throw new UsageFailure(command + " requires ROOT");
            }
            if (root.equals("--") || (root.startsWith("--") && !isPathRoot(root))) {
                throw new UsageFailure(command + " requires ROOT");
            }
            if (root.isBlank()) {
                throw new UsageFailure("ROOT must not be blank");
            }
            return command.equals("run")
                    ? parseRun(root, args)
                    : parseCompile(root, args);
        }

        private static ParsedCommand parseRepl(String[] args) {
            Optional<Path> root = Optional.empty();
            Optional<Path> history = Optional.empty();
            int index = 1;
            if (index < args.length && args[index] != null
                    && !args[index].startsWith("--")) {
                root = Optional.of(pathValue(args, index++, "repl ROOT"));
            }
            boolean plain = false;
            String keymap = "emacs";
            boolean seenHistory = false;
            boolean seenKeymap = false;
            while (index < args.length) {
                String token = args[index++];
                if (token == null) {
                    throw new UsageFailure("argument must not be null");
                }
                switch (token) {
                    case "--plain" -> {
                        if (plain) {
                            throw new UsageFailure("duplicate option: --plain");
                        }
                        plain = true;
                    }
                    case "--history" -> {
                        if (seenHistory) {
                            throw new UsageFailure("duplicate option: --history");
                        }
                        seenHistory = true;
                        history = Optional.of(pathValue(args, index++, "--history"));
                    }
                    case "--keymap" -> {
                        if (seenKeymap) {
                            throw new UsageFailure("duplicate option: --keymap");
                        }
                        seenKeymap = true;
                        keymap = value(args, index++, "--keymap");
                        if (!keymap.equals("emacs") && !keymap.equals("vi")) {
                            throw new UsageFailure("unknown --keymap value: " + keymap
                                    + " (expected emacs or vi)");
                        }
                    }
                    default -> throw new UsageFailure("unknown repl option: " + token);
                }
            }
            return new ReplCommand(root, history, plain, keymap);
        }

        private static ParsedCommand parseAttach(String[] args) {
            if (args.length < 2 || args[1] == null || args[1].isBlank()
                    || args[1].startsWith("--")) {
                throw new UsageFailure("attach requires explicit ENDPOINT");
            }
            String endpoint = args[1];
            for (int index = 2; index < args.length; index++) {
                if (args[index] == null) {
                    throw new UsageFailure("argument must not be null");
                }
                throw new UsageFailure("attach accepts no options; "
                        + "the protocol is credential-free: " + args[index]);
            }
            return new AttachCommand(endpoint);
        }

        private static ParsedCommand parseRun(String root, String[] args) {
            ArrayList<Path> sourceRoots = new ArrayList<>();
            ArrayList<String> programArguments = new ArrayList<>();
            boolean separated = false;
            for (int index = 2; index < args.length; index++) {
                String token = args[index];
                if (token == null) {
                    throw new UsageFailure("argument must not be null");
                }
                if (separated) {
                    programArguments.add(token);
                    continue;
                }
                if (token.equals("--")) {
                    separated = true;
                    continue;
                }
                if (!token.equals("--source-root")) {
                    throw new UsageFailure("run accepts only --source-root and --: " + token);
                }
                sourceRoots.add(pathValue(args, ++index, "--source-root"));
            }
            return new RunCommand(root, sourceRoots,
                    programArguments.toArray(String[]::new));
        }

        private static ParsedCommand parseCompile(String root, String[] args) {
            ArrayList<Path> sourceRoots = new ArrayList<>();
            Optional<Path> output = Optional.empty();
            OutputFormat format = OutputFormat.BUNDLED_JAR;
            String javaPackage = "lyra.generated";
            boolean includeSources = false;
            boolean force = false;
            boolean seenOutput = false;
            boolean seenFormat = false;
            boolean seenPackage = false;
            boolean seenIncludeSources = false;
            boolean seenForce = false;

            for (int index = 2; index < args.length; index++) {
                String token = args[index];
                if (token == null) {
                    throw new UsageFailure("argument must not be null");
                }
                switch (token) {
                    case "--source-root" -> sourceRoots.add(
                            pathValue(args, ++index, "--source-root"));
                    case "--output" -> {
                        if (seenOutput) {
                            throw new UsageFailure("duplicate option: --output");
                        }
                        seenOutput = true;
                        output = Optional.of(pathValue(args, ++index, "--output"));
                    }
                    case "--format" -> {
                        if (seenFormat) {
                            throw new UsageFailure("duplicate option: --format");
                        }
                        seenFormat = true;
                        String value = value(args, ++index, "--format");
                        format = OutputFormat.parse(value);
                    }
                    case "--java-package" -> {
                        if (seenPackage) {
                            throw new UsageFailure("duplicate option: --java-package");
                        }
                        seenPackage = true;
                        javaPackage = value(args, ++index, "--java-package");
                    }
                    case "--include-sources" -> {
                        if (seenIncludeSources) {
                            throw new UsageFailure("duplicate option: --include-sources");
                        }
                        seenIncludeSources = true;
                        includeSources = true;
                    }
                    case "--force" -> {
                        if (seenForce) {
                            throw new UsageFailure("duplicate option: --force");
                        }
                        seenForce = true;
                        force = true;
                    }
                    default -> throw new UsageFailure("unknown compile option: " + token);
                }
            }
            return new CompileCommand(root, sourceRoots, output, format,
                    javaPackage, includeSources, force);
        }

        private static Path pathValue(String[] args, int index, String option) {
            String value = value(args, index, option);
            try {
                return Path.of(value);
            } catch (InvalidPathException failure) {
                throw new UsageFailure("invalid path for " + option + ": " + value);
            }
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length || args[index] == null
                    || args[index].startsWith("--")) {
                throw new UsageFailure("missing value for " + option);
            }
            String value = args[index];
            if (value.isBlank()) {
                throw new UsageFailure("value for " + option + " must not be blank");
            }
            return value;
        }
    }

    private static final class UsageFailure extends RuntimeException {
        private UsageFailure(String message) {
            super(message);
        }
    }
}
