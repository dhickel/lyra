package io.mindspice.lyra.repl.remote;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/** A validated loopback-only TCP address. */
public record LoopbackEndpoint(InetAddress address, int port) {
    public LoopbackEndpoint {
        address = validateAddress(address);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("TCP port must be in 0..65535: " + port);
        }
    }

    public static LoopbackEndpoint bind(int port) {
        return new LoopbackEndpoint(InetAddress.getLoopbackAddress(), port);
    }

    public static LoopbackEndpoint of(String host, int port) throws UnknownHostException {
        Objects.requireNonNull(host, "host");
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        InetAddress selected = null;
        for (InetAddress candidate : addresses) {
            if (!candidate.isLoopbackAddress()) {
                throw new IllegalArgumentException("remote endpoint is not loopback-only: " + host);
            }
            if (selected == null) {
                selected = candidate;
            }
        }
        return new LoopbackEndpoint(selected, port);
    }

    public InetSocketAddress socketAddress() {
        return new InetSocketAddress(address, port);
    }

    public String host() {
        return address.getHostAddress();
    }

    @Override
    public String toString() {
        String rendered = host();
        return address instanceof java.net.Inet6Address
                ? "[" + rendered + "]:" + port
                : rendered + ":" + port;
    }

    private static InetAddress validateAddress(InetAddress value) {
        Objects.requireNonNull(value, "address");
        if (!value.isLoopbackAddress() || value.isAnyLocalAddress()) {
            throw new IllegalArgumentException("endpoint address must be loopback-only");
        }
        return value;
    }
}
