package io.mindspice.lyra.runtime;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable stream/charset configuration; supplied streams are never owned or closed. */
public final class RuntimeIoEnvironment {
    private final InputStream input;
    private final OutputStream output;
    private final OutputStream error;
    private final Charset charset;

    public RuntimeIoEnvironment(InputStream input, OutputStream output,
                                OutputStream error, Charset charset) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    public static RuntimeIoEnvironment defaults() {
        return new RuntimeIoEnvironment(System.in, System.out, System.err, StandardCharsets.UTF_8);
    }

    public InputStream input() { return input; }
    public InputStream inputStream() { return input; }
    public InputStream in() { return input; }
    public OutputStream output() { return output; }
    public OutputStream outputStream() { return output; }
    public OutputStream out() { return output; }
    public OutputStream error() { return error; }
    public OutputStream errorStream() { return error; }
    public OutputStream err() { return error; }
    public Charset charset() { return charset; }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RuntimeIoEnvironment environment
                && input == environment.input && output == environment.output
                && error == environment.error && charset.equals(environment.charset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(input), System.identityHashCode(output),
                System.identityHashCode(error), charset);
    }

    @Override
    public String toString() {
        return "RuntimeIoEnvironment[charset=" + charset.name() + "]";
    }
}
