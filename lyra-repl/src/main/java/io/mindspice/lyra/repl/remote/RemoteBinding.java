package io.mindspice.lyra.repl.remote;

/** Bounded, metadata-only binding description for the internal bindings query. */
public record RemoteBinding(
        String name,
        String canonicalType,
        String visibility,
        boolean mutable) {
    public RemoteBinding {
        name = ProtocolValues.token(name, "binding name", 1024);
        canonicalType = ProtocolValues.token(canonicalType, "binding type", 4096);
        visibility = ProtocolValues.token(visibility, "binding visibility", 64);
    }
}
