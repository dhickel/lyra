package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.AttachableCompileResult;
import io.mindspice.lyra.compiler.api.CompileProfile;
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.RootTypeRegistration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Forked-JVM driver for generated application safe-point handshakes.
 *
 * <p>Each scenario prints READY before invoking the generated {@code main},
 * then the control thread exercises owner-dispatched evaluations and prints
 * SERVICED/RESUMED handshakes.  The parent test asserts the markers and the
 * exit code; an infinite self-tail main is terminated by the control thread's
 * deliberate {@link System#exit} after the handshakes succeed.</p>
 */
public final class SafePointMainDriver {
    private static final String ROOT_SOURCE = "let @pub @mut count :I32 = 0\n"
            + "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))\n"
            + "let @pub main :Fn<Array<String>;I32> = (=> |args| ::main[args])\n";

    private SafePointMainDriver() {
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 1) {
            System.err.println("usage: SafePointMainDriver <scenario>");
            System.exit(2);
        }
        switch (args[0]) {
            case "infiniteSelfTailMain" -> infiniteSelfTailMain();
            default -> {
                System.err.println("unknown scenario: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static void infiniteSelfTailMain() throws Throwable {
        AttachableCompileResult.Success compiled = assertInstanceOf(
                AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("main.lyra", ROOT_SOURCE)
                        .profile(CompileProfile.ATTACHABLE).build()));
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        ApplicationAttachment attachment = ApplicationAttachment.open(
                root, compiled.context(), SessionOptions.defaults());
        var registration = attachment.registration();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> controlFailure = new AtomicReference<>();
        Thread control = new Thread(() -> {
            try {
                ready.await();
                // One owner-dispatched request services inside main's safe
                // points and mutates the real root storage.
                DispatchedEvaluation mutation = attachment.submitDispatch(
                        "poll.lyra", "count := (add 20 22)");
                EvaluationResult mutationResult = mutation.awaitResult();
                if (!(mutationResult instanceof EvaluationResult.Success)) {
                    System.out.println("FAILED " + mutationResult);
                    System.exit(1);
                }
                System.out.println("SERVICED");
                // Main resumed and keeps servicing: a second request lands at
                // a later safe point and observes the completed root write.
                Thread.sleep(150);
                EvaluationResult again = attachment.submitDispatch(
                        "again.lyra", "(add 1 1)").awaitResult();
                if (!(again instanceof EvaluationResult.Success)) {
                    System.out.println("FAILED " + again);
                    System.exit(1);
                }
                EvaluationResult verify = submitUntilAdmitted(attachment, "verify.lyra", "count");
                if (!(verify instanceof EvaluationResult.Success)
                        || !"42".equals(scalar(verify))) {
                    System.out.println("FAILED " + verify);
                    System.exit(1);
                }
                System.out.println("RESUMED");
                System.exit(0);
            } catch (Throwable failure) {
                controlFailure.set(failure);
                failure.printStackTrace(System.out);
                System.exit(1);
            }
        });
        control.setName("safe-point-control");
        control.start();
        ready.countDown();
        System.out.println("READY");
        int ignored = (int) registration.requireBinding("main").invocation()
                .invoke(new String[0]);
        // The infinite self-tail main should never return; if it does, fail.
        System.out.println("MAIN-RETURNED " + ignored);
        System.exit(1);
    }

    /** Submits with bounded retry across transient terminal-publication races. */
    private static EvaluationResult submitUntilAdmitted(ApplicationAttachment attachment,
                                                        String label, String text)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (true) {
            DispatchedEvaluation dispatched = attachment.submitDispatch(label, text);
            if (dispatched.result().isPresent()) {
                EvaluationResult terminal = dispatched.result().orElseThrow();
                if (terminal instanceof EvaluationResult.Busy
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                    continue;
                }
                return terminal;
            }
            return dispatched.awaitResult();
        }
    }

    private static String scalar(EvaluationResult result) {
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                result.value().orElseThrow().data()).value();
    }

    private static <T> T assertInstanceOf(Class<T> type, Object value) {
        if (!type.isInstance(value)) {
            System.out.println("FAILED expected " + type.getSimpleName()
                    + " got " + value);
            System.exit(1);
        }
        return type.cast(value);
    }
}
