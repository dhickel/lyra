package io.mindspice.lyra.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Immutable stream/charset configuration; supplied streams are never owned or closed. */
public final class RuntimeIoEnvironment {
    private final InputStream input;
    private final OutputStream output;
    private final OutputStream error;
    private final Charset charset;
    private final ReentrantLock inputLock = new ReentrantLock(true);
    private final ReentrantLock outputLock = new ReentrantLock(true);
    private final ReentrantLock errorLock;
    private final InputState inputState;
    private volatile LyraIoException inputFailure;

    public RuntimeIoEnvironment(InputStream input, OutputStream output,
                                OutputStream error, Charset charset) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.errorLock = this.error == this.output
                ? outputLock : new ReentrantLock(true);
        this.inputState = new InputState(this.charset);
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

    /** Prevents a failed program line from becoming source when a console changes readers. */
    public void checkInputAvailable() {
        LyraIoException failure = inputFailure;
        if (failure != null) throw failure;
    }

    /**
     * Reads exactly one logical line from this environment's input owner.
     * The decoder and pending bytes are shared with {@link LyraIo#readLine},
     * so a local console can acquire source lines without buffering bytes that
     * belong to a generated program read. The returned line excludes one
     * terminal LF and its preceding CR; clean EOF returns {@code null}, while
     * a non-empty EOF remainder is returned once. The configured streams are
     * never closed by this method. A read/decoding failure retires input for
     * this environment because its partial line is no longer safe to transfer
     * to a different reader or console history.
     */
    public String readLine() {
        try {
            inputLock.lockInterruptibly();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ioFailure("standard input was interrupted", failure);
        }
        try {
            checkInputAvailable();
            while (true) {
                String line = inputState.pollLine();
                if (line != null) {
                    return line;
                }
                if (inputState.endOfInput()) {
                    String remainder = inputState.takeRemainder();
                    return remainder.isEmpty() ? null : remainder;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw interrupted();
                }
                int next = input.read();
                if (next < 0) {
                    inputState.finish();
                } else {
                    inputState.accept(next);
                }
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (InterruptedIOException failure) {
            Thread.currentThread().interrupt();
            inputFailure = ioFailure("standard input was interrupted", failure);
            throw inputFailure;
        } catch (CharacterCodingException failure) {
            inputFailure = ioFailure("standard input contains malformed text", failure);
            throw inputFailure;
        } catch (IOException | RuntimeException failure) {
            // A failed read has lost its line boundary. Never expose the partial
            // program line to a later console source/history acquisition.
            inputFailure = failure instanceof LyraIoException io
                    ? io : ioFailure("standard input failed", failure);
            throw inputFailure;
        } finally {
            inputLock.unlock();
        }
    }

    ReentrantLock inputLock() { return inputLock; }
    ReentrantLock outputLock() { return outputLock; }
    ReentrantLock errorLock() { return errorLock; }
    InputState inputState() { return inputState; }

    /** Decoder state belongs to one configured input stream and is only
     * accessed while {@link #inputLock} is held. */
    static final class InputState {
        private final CharsetDecoder decoder;
        private ByteBuffer pending = ByteBuffer.allocate(0);
        private final StringBuilder decoded = new StringBuilder();
        private boolean endOfInput;

        private InputState(Charset charset) {
            this.decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
        }

        void accept(int value) throws CharacterCodingException {
            if (endOfInput) {
                throw new IllegalStateException("input decoder was used after EOF");
            }
            ByteBuffer source = ByteBuffer.allocate(pending.remaining() + 1);
            source.put(pending);
            source.put((byte) value);
            source.flip();
            decode(source, false);
            saveRemaining(source);
        }

        void finish() throws CharacterCodingException {
            if (endOfInput) {
                return;
            }
            ByteBuffer source = pending;
            pending = ByteBuffer.allocate(0);
            decode(source, true);
            flush();
            endOfInput = true;
        }

        String pollLine() {
            int newline = decoded.indexOf("\n");
            if (newline < 0) {
                return null;
            }
            String line = decoded.substring(0, newline);
            decoded.delete(0, newline + 1);
            return line.endsWith("\r")
                    ? line.substring(0, line.length() - 1) : line;
        }

        String takeRemainder() {
            String result = decoded.toString();
            decoded.setLength(0);
            return result;
        }

        boolean endOfInput() {
            return endOfInput;
        }

        private void decode(ByteBuffer source, boolean endOfInput)
                throws CharacterCodingException {
            while (true) {
                CharBuffer output = CharBuffer.allocate(16);
                var result = decoder.decode(source, output, endOfInput);
                output.flip();
                decoded.append(output);
                if (result.isError()) {
                    result.throwException();
                }
                if (result.isUnderflow()) {
                    return;
                }
            }
        }

        private void flush() throws CharacterCodingException {
            while (true) {
                CharBuffer output = CharBuffer.allocate(16);
                var result = decoder.flush(output);
                output.flip();
                decoded.append(output);
                if (result.isError()) {
                    result.throwException();
                }
                if (result.isUnderflow()) {
                    return;
                }
            }
        }

        private void saveRemaining(ByteBuffer source) {
            ByteBuffer remaining = ByteBuffer.allocate(source.remaining());
            remaining.put(source);
            remaining.flip();
            pending = remaining;
        }
    }

    private static InterruptedIOException interrupted() {
        return new InterruptedIOException("standard input was interrupted");
    }

    private static LyraIoException ioFailure(String summary, Throwable cause) {
        return new LyraIoException(summary, java.util.List.of(), cause);
    }

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
