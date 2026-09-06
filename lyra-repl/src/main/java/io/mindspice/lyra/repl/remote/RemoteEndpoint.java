package io.mindspice.lyra.repl.remote;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Public endpoint descriptor containing no secret token. */
public record RemoteEndpoint(
        LoopbackEndpoint address,
        Path credentialFile,
        Optional<UUID> sessionId) {
    public RemoteEndpoint {
        address = Objects.requireNonNull(address, "address");
        if (address.port() == 0) {
            throw new IllegalArgumentException("a connectable endpoint needs a bound port");
        }
        credentialFile = Objects.requireNonNull(credentialFile, "credentialFile")
                .toAbsolutePath().normalize();
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public RemoteEndpoint(LoopbackEndpoint address, Path credentialFile, UUID sessionId) {
        this(address, credentialFile, Optional.of(Objects.requireNonNull(sessionId, "sessionId")));
    }

    public RemoteEndpoint(LoopbackEndpoint address, Path credentialFile) {
        this(address, credentialFile, Optional.empty());
    }

    /** No token is included in this display form. */
    public String display() {
        return address + " --token-file " + credentialFile;
    }

    @Override
    public String toString() {
        return "RemoteEndpoint[address=" + address + ",credentialFile="
                + credentialFile + ",sessionId=" + sessionId.orElse(null) + "]";
    }
}
