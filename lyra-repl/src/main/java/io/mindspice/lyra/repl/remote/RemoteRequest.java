package io.mindspice.lyra.repl.remote;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Handle for one admitted remote evaluation; it contains no live Lyra value. */
public final class RemoteRequest {
    private final RemoteClient client;
    private final UUID requestId;
    private final long sequence;
    private final CompletableFuture<ProtocolMessage.Result> result = new CompletableFuture<>();
    private final AtomicReference<ProtocolMessage.RemoteStatus> status =
            new AtomicReference<>(ProtocolMessage.RemoteStatus.QUEUED);

    RemoteRequest(RemoteClient client, UUID requestId, long sequence) {
        this.client = Objects.requireNonNull(client, "client");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.sequence = sequence;
    }

    public UUID requestId() {
        return requestId;
    }

    public long sequence() {
        return sequence;
    }

    public ProtocolMessage.RemoteStatus status() {
        return status.get();
    }

    public boolean isTerminal() {
        return status().isTerminal();
    }

    public CompletableFuture<ProtocolMessage.Result> result() {
        return result;
    }

    public CompletableFuture<ProtocolMessage.CancelResult> cancel() throws java.io.IOException {
        return client.cancel(requestId);
    }

    void accepted(ProtocolMessage.RemoteStatus value) {
        Objects.requireNonNull(value, "value");
        synchronized (this) {
            if (!result.isDone() && !status.get().isTerminal()) {
                status.set(value);
            }
        }
    }

    void status(ProtocolMessage.RemoteStatus value) {
        Objects.requireNonNull(value, "value");
        synchronized (this) {
            if (!result.isDone() && !status.get().isTerminal()) {
                status.set(value);
            }
        }
    }

    void completed(ProtocolMessage.Result value) {
        Objects.requireNonNull(value, "value");
        synchronized (this) {
            if (result.isDone()) {
                return;
            }
            status.set(value.status());
            result.complete(value);
        }
    }

    /** Settles a locally retained request that the server has expired. */
    void expired(long revision, Optional<String> detail) {
        Objects.requireNonNull(detail, "detail");
        completed(new ProtocolMessage.Result(
                RemoteProtocol.VERSION, requestId, sequence,
                ProtocolMessage.RemoteStatus.EXPIRED, revision, List.of(), Optional.empty(),
                detail, Optional.empty()));
    }

    void failed(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        synchronized (this) {
            if (result.isDone()) {
                return;
            }
            status.set(ProtocolMessage.RemoteStatus.UNAVAILABLE);
            result.completeExceptionally(failure);
        }
    }

    void disconnected() {
        synchronized (this) {
            if (!result.isDone() && !status.get().isTerminal()) {
                status.set(ProtocolMessage.RemoteStatus.DISCONNECTED);
            }
        }
    }
}
