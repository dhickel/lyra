package io.mindspice.lyra.runtime;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Direct runtime entry points used by compiler-lowered {@code std->io} calls. */
public final class LyraIo {
    private static final String INTERRUPTED = "standard input was interrupted";

    private LyraIo() {
    }

    public static void print(LyraClosureAuthority authority, String value) {
        write(authority, value, false, false);
    }

    public static void println(LyraClosureAuthority authority, String value) {
        write(authority, value, true, false);
    }

    public static void eprint(LyraClosureAuthority authority, String value) {
        write(authority, value, false, true);
    }

    public static void eprintln(LyraClosureAuthority authority, String value) {
        write(authority, value, true, true);
    }

    /** Returns {@code null} for Lyra {@code #NIL} at clean input EOF. */
    public static String readLine(LyraClosureAuthority authority) {
        Objects.requireNonNull(authority, "authority");
        authority.token().checkIoAccess();
        RuntimeIoEnvironment environment = authority.token().ioEnvironment();
        ReentrantLock lock = environment.inputLock();
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ioFailure(INTERRUPTED, failure);
        }
        try {
            return readLineLocked(environment);
        } finally {
            lock.unlock();
        }
    }

    private static void write(LyraClosureAuthority authority, String value,
                              boolean newline, boolean errorStream) {
        Objects.requireNonNull(authority, "authority");
        authority.token().checkIoAccess();
        Objects.requireNonNull(value, "value");
        RuntimeIoEnvironment environment = authority.token().ioEnvironment();
        OutputStream stream = errorStream ? environment.error() : environment.output();
        ReentrantLock lock = errorStream
                ? environment.errorLock() : environment.outputLock();
        String text = newline ? value + "\n" : value;
        lock.lock();
        try {
            byte[] encoded = encode(text, environment.charset());
            // One write call under the stream lock keeps each encoded record
            // contiguous with respect to every call using this environment.
            stream.write(encoded);
            stream.flush();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof LyraIoException io) {
                throw io;
            }
            throw ioFailure("standard output failed", failure);
        } finally {
            lock.unlock();
        }
    }

    private static byte[] encode(String value, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw ioFailure("standard output cannot encode text", failure);
        }
    }

    private static String readLineLocked(RuntimeIoEnvironment environment) {
        InputStream input = environment.input();
        RuntimeIoEnvironment.InputState state = environment.inputState();
        try {
            while (true) {
                String line = state.pollLine();
                if (line != null) {
                    return line;
                }
                if (state.endOfInput()) {
                    String remainder = state.takeRemainder();
                    return remainder.isEmpty() ? null : remainder;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw interrupted();
                }
                int next = input.read();
                if (next < 0) {
                    state.finish();
                } else {
                    state.accept(next);
                }
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (InterruptedIOException failure) {
            Thread.currentThread().interrupt();
            throw ioFailure(INTERRUPTED, failure);
        } catch (CharacterCodingException failure) {
            throw ioFailure("standard input contains malformed text", failure);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof LyraIoException io) {
                throw io;
            }
            throw ioFailure("standard input failed", failure);
        }
    }

    private static InterruptedIOException interrupted() {
        return new InterruptedIOException(INTERRUPTED);
    }

    private static LyraIoException ioFailure(String summary, Throwable cause) {
        return new LyraIoException(summary, java.util.List.of(), cause);
    }
}
