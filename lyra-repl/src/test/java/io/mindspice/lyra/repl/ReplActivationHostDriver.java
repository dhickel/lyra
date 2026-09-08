package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.AttachableCompileResult;
import io.mindspice.lyra.compiler.api.CompileProfile;
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Forked-JVM driver for the public Java host activation surface.
 *
 * <p>Each scenario exercises the shipped composition exactly as a host
 * would: bootstrap before root publication, register the live attachable
 * root, run the generated main on the original owner, then close the
 * service while the externally owned root and its retained domain survive
 * for a later reopened attachment. Marker lines are the handshake evidence
 * the parent test asserts.</p>
 */
public final class ReplActivationHostDriver {
    private static final String SPIN_SOURCE = "let @pub @mut count :I32 = 0\n"
            + "let @pub spin :Fn<;I32> = (=> | | ((== count 0) -> ::spin[] : count))\n"
            + "let @pub main :Fn<Array<String>;I32> = (=> |args| ::spin[])\n";

    private ReplActivationHostDriver() {
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 1) {
            System.err.println("usage: ReplActivationHostDriver <scenario> [signal-file]");
            System.exit(2);
        }
        switch (args[0]) {
            case "ownedRunSurvivalAndReopen" -> ownedRunSurvivalAndReopen();
            case "queuedRequestRetiredAtShutdown" ->
                    queuedRequestRetiredAtShutdown(args.length > 1 ? args[1] : null);
            default -> {
                System.err.println("unknown scenario: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static AttachableCompileResult.Success compiled() {
        return assertInstanceOf(AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("main.lyra", SPIN_SOURCE)
                        .profile(CompileProfile.ATTACHABLE)
                        .debugCapable(true)
                        .build()));
    }

    private static void ownedRunSurvivalAndReopen() throws Throwable {
        AttachableCompileResult.Success compiled = compiled();
        LoadedArtifact loaded = LyraRuntime.load(compiled.artifact());
        ReplActivation activation = ReplActivation.bootstrap(0, false,
                SessionOptions.defaults(), System.out);
        System.out.println("READY " + activation.endpoint().address());
        ModuleHandle root = loaded.instantiate();
        activation.register(root, compiled.context());
        System.out.println("REGISTERED");
        // The generated main spins at safe points until a remote evaluation
        // replaces count; the evaluation executes on the original owner.
        int status = (int) root.export("main", "Fn<Array<String>;I32>")
                .methodHandle().invokeExact(new String[0]);
        System.out.println("MAIN-DONE " + status);
        // Service close never closes the externally owned root.
        activation.close();
        System.out.println("SERVICE-CLOSED");
        System.out.println("ROOT-OPEN " + !root.isClosed());
        // Reopening attachment on the same live root reuses the retained
        // structural domain with a fresh service.
        ReplActivation reopened = ReplActivation.bootstrap(0, false,
                SessionOptions.defaults(), System.out);
        System.out.println("REOPEN " + reopened.endpoint().address());
        reopened.register(root, compiled.context());
        System.out.println("REOPEN-REGISTERED");
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline && !reopened.poll()) {
            Thread.sleep(2);
        }
        System.out.println("REOPEN-SERVICED");
        reopened.close();
        root.close();
        loaded.close();
        requireNoLeakedThreads();
        System.out.println("DONE");
    }

    private static void queuedRequestRetiredAtShutdown(String signalFile)
            throws Throwable {
        AttachableCompileResult.Success compiled = compiled();
        LoadedArtifact loaded = LyraRuntime.load(compiled.artifact());
        ReplActivation activation = ReplActivation.bootstrap(0, false,
                SessionOptions.defaults(), System.out);
        System.out.println("READY " + activation.endpoint().address());
        ModuleHandle root = loaded.instantiate();
        activation.register(root, compiled.context());
        System.out.println("REGISTERED");
        // No owner polling: the remote evaluation stays queued on the
        // controller until the service closes, which must retire it.
        Path signal = Path.of(signalFile == null ? "submitted.signal" : signalFile);
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!Files.isRegularFile(signal) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        if (!Files.isRegularFile(signal)) {
            throw new AssertionError("the parent never submitted its queued request");
        }
        // The wire write can race the service close. Wait until the request
        // is provably queued on the shared controller so the shutdown path
        // deterministically retires a genuinely queued control request.
        while (System.nanoTime() < deadline
                && !activation.attachment().registration().controller().hasLiveWork()) {
            Thread.sleep(2);
        }
        if (!activation.attachment().registration().controller().hasLiveWork()) {
            throw new AssertionError("the queued request never reached the controller");
        }
        activation.close();
        System.out.println("CLOSED-WITH-QUEUED");
        System.out.println("ROOT-OPEN " + !root.isClosed());
        root.close();
        loaded.close();
        requireNoLeakedThreads();
        System.out.println("DONE");
    }

    private static void requireNoLeakedThreads() throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean leaked = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.isAlive()
                            && (thread.getName().startsWith("lyra-repl-remote-")
                            || thread.getName().startsWith("lyra-managed-console-owner")));
            if (!leaked) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("REPL threads leaked after close: "
                + Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive).map(Thread::getName).toList());
    }

    private static <T> T assertInstanceOf(Class<T> type, Object value) {
        if (!type.isInstance(value)) {
            throw new AssertionError("expected " + type.getSimpleName() + " but was " + value);
        }
        return type.cast(value);
    }
}
