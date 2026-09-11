package io.mindspice.lyra.editor;

import io.mindspice.lyra.repl.*;
import io.mindspice.lyra.repl.remote.*;
import io.mindspice.lyra.runtime.LyraOwnerController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

/** The child process owns every live language value; the editor receives only protocol snapshots. */
public final class EditorWorker {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Expected endpoint file, parent PID and source roots");
        Path endpointFile = Path.of(args[0]);
        long parent = Long.parseLong(args[1]);
        var options = SessionOptions.builder().sourceRoots(Arrays.stream(args).skip(2).map(Path::of).toList())
                .javaBasePackage("lyra.editor.generated").build();
        // Parent liveness is checked independently of a blocked input or user evaluation.
        Thread.ofPlatform().daemon().name("lyra-editor-parent-monitor").start(() -> {
            while (ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false))
                LockSupport.parkNanos(1_000_000_000L);
            Runtime.getRuntime().halt(0);
        });
        try (var session = LyraSession.open(options); var owner = new LyraOwnerController();
             var server = RemoteServer.open(LyraSessionAdapter.of(session), owner)) {
            RemoteEndpoint endpoint = server.endpoint();
            WorkspaceFiles.atomicWrite(endpointFile, (endpoint.address().host() + "\n" + endpoint.address().port()
                    + "\n" + endpoint.sessionId().orElseThrow() + "\n").getBytes(StandardCharsets.UTF_8));
            while (!Thread.currentThread().isInterrupted()) {
                if (!server.poll()) LockSupport.parkNanos(2_000_000L);
            }
        }
    }
}
