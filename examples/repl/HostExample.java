import io.mindspice.lyra.compiler.api.AttachableCompileResult;
import io.mindspice.lyra.compiler.api.CompileProfile;
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.repl.ApplicationAttachment;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.LyraSession;
import io.mindspice.lyra.repl.ValueSnapshot;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runnable Java-host example for the local session API and explicit
 * attachment polling. Build/run with examples/repl/run-java-host.sh.
 */
public final class HostExample {

    private static String scalar(EvaluationResult result) {
        if (!(result instanceof EvaluationResult.Success success)) {
            throw new AssertionError("expected success, got " + result);
        }
        return ((ValueSnapshot.Scalar) success.value().orElseThrow().data()).value();
    }

    public static void main(String[] args) throws Exception {
        localSession();
        attachedRoot();
        System.out.println("HostExample: PASS");
    }

    private static void localSession() throws Exception {
        try (LyraSession session = LyraSession.open()) {
            session.submit(EvaluationSource.of("counter.lyra", "let @mut count :I32 = 1"));
            session.submit(EvaluationSource.of("bump.lyra", "count := 42"));
            if (!scalar(session.submit(EvaluationSource.of("read.lyra", "count"))).equals("42")) {
                throw new AssertionError("local session counter");
            }
        }
        System.out.println("local session: PASS");
    }

    private static void attachedRoot() throws Exception {
        AttachableCompileResult.Success compiled = (AttachableCompileResult.Success)
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("app.lyra",
                                "let @pub @mut count :I32 = 0\n"
                                        + "let @pub readCount :Fn<;I32> = (=> || count)\n"
                                        + "let @pub main :Fn<Array<String>;I32> = (=> |args| 0)\n")
                        .profile(CompileProfile.ATTACHABLE)
                        .build());
        var loaded = LyraRuntime.load(compiled.artifact());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);

        Thread owner = new Thread(() -> {
            ModuleHandle root = null;
            try {
                root = loaded.instantiate();
                ApplicationAttachment attachment =
                        ApplicationAttachment.open(root, compiled.context(),
                                io.mindspice.lyra.repl.SessionOptions.defaults());
                ready.countDown();
                // The attached scope sees public root exports through real
                // typed accessors; writes target the original root storage.
                attachment.submit(EvaluationSource.of("write.lyra", "count := 7"));
                if (!scalar(attachment.submit(
                        EvaluationSource.of("read.lyra", "count"))).equals("7")) {
                    throw new AssertionError("attached count");
                }
                // Synchronous submission already ran on the owner thread.
                // Cross-thread callers use submitDispatch(...) and poll().
                // Close retires control resources; the root survives.
                attachment.close();
                // Reopen shares the retained root-lifetime domain.
                try (var reopened = ApplicationAttachment.open(
                        root, compiled.context(),
                        io.mindspice.lyra.repl.SessionOptions.defaults())) {
                    if (!scalar(reopened.submit(
                            EvaluationSource.of("again.lyra", "count"))).equals("7")) {
                        throw new AssertionError("root-held state lost");
                    }
                }
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                // The root is caller-owned and owner-confined: retire it here.
                if (root != null) {
                    root.close();
                }
            }
        }, "host-owner");
        owner.start();
        ready.await();
        owner.join(30_000);
        if (failure.get() != null) {
            throw new AssertionError("attached root failure", failure.get());
        }
        loaded.close();
        System.out.println("attached root: PASS");
    }

    private HostExample() {
    }
}
