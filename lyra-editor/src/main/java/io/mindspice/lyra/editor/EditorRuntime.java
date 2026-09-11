package io.mindspice.lyra.editor;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.connect.ListeningConnector;
import io.mindspice.lyra.repl.*;
import io.mindspice.lyra.repl.remote.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owns a disposable JVM and the existing loopback protocol. Call blocking operations off the FX thread. */
public final class EditorRuntime implements AutoCloseable {
    private Process process;
    private RemoteConsoleSession console;
    private Debugger debugger;
    private Path handshakeDirectory;
    private final AtomicReference<RemoteRequest> active = new AtomicReference<>();
    private volatile boolean closed;
    private boolean attached;

    public static EditorRuntime attach(String host, int port) throws IOException {
        EditorRuntime runtime = new EditorRuntime();
        runtime.attached = true;
        runtime.console = RemoteConsoleSession.connect(new RemoteEndpoint(LoopbackEndpoint.of(host, port)));
        return runtime;
    }
    public RemoteEndpoint endpoint() { return console.client().endpoint(); }
    public boolean isAttached() { return attached; }

    public static EditorRuntime start(WorkspaceSettings workspace, boolean debug, Consumer<String> output,
                                      Consumer<Debugger.Pause> paused, Consumer<String> status) throws Exception {
        EditorRuntime runtime = new EditorRuntime();
        try { runtime.launch(workspace, debug, output, paused, status); return runtime; }
        catch (Exception failure) { runtime.close(); throw failure; }
    }
    private void launch(WorkspaceSettings workspace, boolean debug, Consumer<String> output,
                        Consumer<Debugger.Pause> paused, Consumer<String> status) throws Exception {
        handshakeDirectory = Files.createTempDirectory("lyra-editor-");
        Path handshake = handshakeDirectory.resolve("endpoint");
        List<String> command = new ArrayList<>(List.of(javaExecutable(), "--enable-preview", "-Xmx768m"));
        ListeningConnector connector = null;
        Map<String, com.sun.jdi.connect.Connector.Argument> arguments = null;
        if (debug) {
            connector = Bootstrap.virtualMachineManager().listeningConnectors().stream()
                    .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketListen")).findFirst().orElseThrow();
            arguments = connector.defaultArguments();
            arguments.get("localAddress").setValue("127.0.0.1");
            arguments.get("port").setValue("0");
            arguments.get("timeout").setValue("15000");
            String address = connector.startListening(arguments);
            command.add("-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=" + address);
        }
        try {
            command.addAll(List.of("-cp", workerClasspath(), EditorWorker.class.getName(), handshake.toString(),
                    Long.toString(ProcessHandle.current().pid())));
            command.addAll(workspace.sourceRoots().stream().map(Path::toString).toList());
            process = new ProcessBuilder(command).directory(workspace.root().toFile()).start();
            pump(process.getInputStream(), output, "stdout");
            pump(process.getErrorStream(), output, "stderr");
            if (connector != null) debugger = new Debugger(connector.accept(arguments), workspace.sourceRoots(), paused, status);
        } finally { if (connector != null) connector.stopListening(arguments); }
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!Files.exists(handshake)) {
            if (!process.isAlive()) throw new IOException("Lyra process exited during startup (" + process.exitValue() + ")");
            if (System.nanoTime() >= deadline) throw new IOException("Lyra process did not become ready within 20 seconds");
            Thread.sleep(15);
        }
        List<String> endpoint = Files.readAllLines(handshake, StandardCharsets.UTF_8);
        console = RemoteConsoleSession.connect(new RemoteEndpoint(LoopbackEndpoint.of(endpoint.get(0), Integer.parseInt(endpoint.get(1))),
                UUID.fromString(endpoint.get(2))));
        Files.deleteIfExists(handshake);
    }
    public Optional<Debugger> debugger() { return Optional.ofNullable(debugger); }
    public boolean isAlive() { return !closed && (attached ? console != null && console.isConnected() : process != null && process.isAlive()); }
    public ConsoleSession.Query bindings() { return console.query(ConsoleSession.QueryRequest.bindings()); }
    public ConsoleSession.Query type(String source) { return console.query(ConsoleSession.QueryRequest.type(EvaluationSource.of("Editor type query", source))); }
    public ConsoleSession.Control reset() { return console.reset(); }
    public ConsoleSession.Evaluation reload(String module) { return console.reload(module); }
    public ConsoleSession.Evaluation evaluate(String text) throws Exception {
        return evaluate(EvaluationSource.of("Editor REPL", text));
    }
    public ConsoleSession.Evaluation evaluate(EvaluationSource source) throws Exception {
        return await(console.client().submit(source));
    }
    public ConsoleSession.Evaluation load(Path path) throws Exception {
        RemoteRequest request = console.client().load(path.toString());
        if (debugger != null) debugger.registerSubmission(request.requestId(), path);
        return await(request);
    }
    private ConsoleSession.Evaluation await(RemoteRequest request) throws Exception {
        if (!active.compareAndSet(null, request)) { request.cancel(); throw new IllegalStateException("An evaluation is already active"); }
        try {
            ProtocolMessage.Result result;
            while (true) {
                try { result = request.result().get(250, TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException waiting) {
                    if (!isAlive() || !console.isConnected()) throw new IOException("Lyra process disconnected");
                }
            }
            ConsoleSession.EvaluationStatus status = switch (result.status()) {
                case SUCCESS -> ConsoleSession.EvaluationStatus.SUCCESS;
                case COMPILATION_FAILURE -> ConsoleSession.EvaluationStatus.COMPILATION_FAILURE;
                case RUNTIME_FAILURE -> ConsoleSession.EvaluationStatus.RUNTIME_FAILURE;
                case CANCELLED -> ConsoleSession.EvaluationStatus.CANCELLED;
                case BUSY -> ConsoleSession.EvaluationStatus.BUSY;
                case REVISION_CONFLICT -> ConsoleSession.EvaluationStatus.REVISION_CONFLICT;
                case REJECTED -> ConsoleSession.EvaluationStatus.REJECTED;
                case EXPIRED -> ConsoleSession.EvaluationStatus.EXPIRED;
                default -> ConsoleSession.EvaluationStatus.UNAVAILABLE;
            };
            return new ConsoleSession.Evaluation(EvaluationId.of(request.requestId()), status, console.revision(),
                    result.diagnostics().stream().map(d -> new ConsoleSession.DiagnosticInfo(d.code(), d.severity(), d.summary(),
                            new ConsoleSession.Span(d.primarySpan().sourceId(), d.primarySpan().startOffset(), d.primarySpan().endOffset()),
                            d.relatedSpans().stream().map(r -> new ConsoleSession.RelatedSpan(new ConsoleSession.Span(r.span().sourceId(),
                                    r.span().startOffset(), r.span().endOffset()), r.label())).toList())).toList(),
                    result.value().map(value -> new ConsoleSession.Value(value.canonicalType(), render(value.data()))), result.failureSummary());
        } finally { active.compareAndSet(request, null); }
    }
    public void cancel() throws IOException {
        if (debugger != null && debugger.isPaused()) debugger.resume();
        RemoteRequest request = active.get();
        if (request != null) request.cancel();
        else if (console != null) console.cancelActive();
    }
    public void input(String line) throws IOException {
        if (attached) throw new IOException("Attached applications retain their own program stdin");
        if (!isAlive()) throw new IOException("No running process");
        process.outputWriter(StandardCharsets.UTF_8).write(line + "\n");
        process.outputWriter(StandardCharsets.UTF_8).flush();
    }
    public void endInput() throws IOException { if (attached) throw new IOException("Attached applications retain their own program stdin"); if (process != null) process.getOutputStream().close(); }

    private static String render(ProtocolMessage.ValueData value) {
        return switch (value) {
            case ProtocolMessage.Nil ignored -> "#NIL";
            case ProtocolMessage.Unit ignored -> "Unit";
            case ProtocolMessage.Scalar scalar -> scalar.scalarKind().equalsIgnoreCase("STRING") ? "\"" + scalar.value() + "\"" : scalar.value();
            case ProtocolMessage.Aggregate aggregate -> "[" + String.join(", ", aggregate.elements().stream()
                    .map(item -> render(item.data())).toList()) + aggregate.truncation().map(t -> ", …").orElse("") + "]";
            default -> value.toString();
        };
    }
    private static void pump(InputStream stream, Consumer<String> output, String name) {
        Thread.ofPlatform().daemon().name("lyra-editor-" + name).start(() -> {
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                for (int count; (count = reader.read(buffer)) >= 0;) if (count > 0) output.accept(new String(buffer, 0, count));
            } catch (IOException ignored) { /* process shutdown closes its pipes */ }
        });
    }
    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }
    private static String workerClasspath() {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return String.join(File.pathSeparator, Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString()).toList());
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            try { if (console != null) console.close(); }
            finally { if (debugger != null) debugger.close(); }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                try { if (!process.waitFor(800, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); } }
                catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
            }
            if (handshakeDirectory != null) {
                try { Files.deleteIfExists(handshakeDirectory.resolve("endpoint")); Files.deleteIfExists(handshakeDirectory); }
                catch (IOException ignored) { }
            }
        }
    }
}
