package io.mindspice.lyra.repl.remote;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Public endpoint descriptor for the credential-free v2 protocol. The
 * endpoint contains an address and an optional session identity and no
 * secret material of any kind.
 */
public record RemoteEndpoint(
        LoopbackEndpoint address,
        Optional<UUID> sessionId) {
    public RemoteEndpoint {
        address = Objects.requireNonNull(address, "address");
        if (address.port() == 0) {
            throw new IllegalArgumentException("a connectable endpoint needs a bound port");
        }
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public RemoteEndpoint(LoopbackEndpoint address, UUID sessionId) {
        this(address, Optional.of(Objects.requireNonNull(sessionId, "sessionId")));
    }

    public RemoteEndpoint(LoopbackEndpoint address) {
        this(address, Optional.empty());
    }

    /** Explicit endpoint display used by startup surfaces, including its authority warning. */
    public String display() {
        return address + " (" + warning() + ")";
    }

    /** Unauthenticated local-execution warning attached to endpoint display. */
    public String warning() {
        return "no authentication: any process that can reach " + address
                + " may execute code with the application's authority";
    }

    @Override
    public String toString() {
        return "RemoteEndpoint[address=" + address + ",sessionId=" + sessionId.orElse(null) + "]";
    }
}
