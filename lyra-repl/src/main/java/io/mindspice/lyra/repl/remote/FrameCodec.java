package io.mindspice.lyra.repl.remote;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Four-byte big-endian length-prefixed frame transport.
 *
 * <p>The length is checked before allocation. Frames are opaque UTF-8 JSON
 * payloads to this class; schema validation belongs to {@link ProtocolCodec}.</p>
 */
public final class FrameCodec {
    public static final int DEFAULT_MAX_FRAME_BYTES = RemoteProtocol.MAX_FRAME_BYTES;

    private final int maxFrameBytes;

    public FrameCodec() {
        this(DEFAULT_MAX_FRAME_BYTES);
    }

    public FrameCodec(int maxFrameBytes) {
        if (maxFrameBytes <= 0 || maxFrameBytes > RemoteProtocol.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException(
                    "maxFrameBytes must be in 1.." + RemoteProtocol.MAX_FRAME_BYTES);
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    /** Reads one frame, returning empty only when EOF occurs before a header. */
    public Optional<byte[]> readFrame(InputStream input) throws IOException, ProtocolException {
        Objects.requireNonNull(input, "input");
        byte[] header = input.readNBytes(RemoteProtocol.FRAME_HEADER_BYTES);
        if (header.length == 0) {
            return Optional.empty();
        }
        if (header.length != RemoteProtocol.FRAME_HEADER_BYTES) {
            throw new ProtocolException(ProtocolException.Reason.END_OF_FRAME,
                    "truncated frame header");
        }
        int length = ByteBuffer.wrap(header).getInt();
        if (length <= 0) {
            throw new ProtocolException(ProtocolException.Reason.INVALID_FRAME_LENGTH,
                    "frame length must be positive");
        }
        if (length > maxFrameBytes) {
            throw new ProtocolException(ProtocolException.Reason.FRAME_TOO_LARGE,
                    "frame exceeds configured bound");
        }
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) {
            throw new ProtocolException(ProtocolException.Reason.END_OF_FRAME,
                    "truncated frame payload");
        }
        return Optional.of(payload);
    }

    /**
     * Reads one frame while applying one absolute deadline to every header and
     * payload read. This socket-aware overload is used only during the
     * handshake; ordinary reads retain the unbounded-by-deadline API above.
     */
    Optional<byte[]> readFrame(InputStream input, Socket socket, long deadlineNanos)
            throws IOException, ProtocolException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(socket, "socket");
        byte[] header = new byte[RemoteProtocol.FRAME_HEADER_BYTES];
        int headerLength = readUntil(input, socket, header, deadlineNanos);
        if (headerLength == 0) {
            return Optional.empty();
        }
        if (headerLength != header.length) {
            throw new ProtocolException(ProtocolException.Reason.END_OF_FRAME,
                    "truncated frame header");
        }
        int length = ByteBuffer.wrap(header).getInt();
        if (length <= 0) {
            throw new ProtocolException(ProtocolException.Reason.INVALID_FRAME_LENGTH,
                    "frame length must be positive");
        }
        if (length > maxFrameBytes) {
            throw new ProtocolException(ProtocolException.Reason.FRAME_TOO_LARGE,
                    "frame exceeds configured bound");
        }
        byte[] payload = new byte[length];
        int payloadLength = readUntil(input, socket, payload, deadlineNanos);
        if (payloadLength != length) {
            throw new ProtocolException(ProtocolException.Reason.END_OF_FRAME,
                    "truncated frame payload");
        }
        return Optional.of(payload);
    }

    private static int readUntil(
            InputStream input, Socket socket, byte[] target, long deadlineNanos)
            throws IOException {
        int offset = 0;
        while (offset < target.length) {
            socket.setSoTimeout(remainingTimeoutMillis(deadlineNanos));
            int read = input.read(target, offset, target.length - offset);
            if (read < 0) {
                return offset;
            }
            if (read == 0) {
                continue;
            }
            offset += read;
            if (System.nanoTime() >= deadlineNanos) {
                throw new SocketTimeoutException("frame read deadline exceeded");
            }
        }
        return offset;
    }

    private static int remainingTimeoutMillis(long deadlineNanos)
            throws SocketTimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("frame read deadline exceeded");
        }
        long millis = (remaining + 999_999L) / 1_000_000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, millis));
    }

    public void writeFrame(OutputStream output, byte[] payload) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(payload, "payload");
        if (payload.length <= 0) {
            throw new IllegalArgumentException("frame payload must not be empty");
        }
        if (payload.length > maxFrameBytes) {
            throw new IllegalArgumentException("frame payload exceeds configured bound");
        }
        byte[] header = ByteBuffer.allocate(RemoteProtocol.FRAME_HEADER_BYTES)
                .putInt(payload.length)
                .array();
        output.write(header);
        output.write(payload);
        output.flush();
    }

    public Optional<String> readUtf8Frame(InputStream input)
            throws IOException, ProtocolException {
        Optional<byte[]> payload = readFrame(input);
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(decodeUtf8(payload.orElseThrow()));
    }

    public void writeUtf8Frame(OutputStream output, String payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        // Use the exact remaining bytes; a backing array can contain unused capacity.
        java.nio.ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(payload));
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("payload contains invalid UTF-16", exception);
        }
        byte[] exact = new byte[encoded.remaining()];
        encoded.get(exact);
        writeFrame(output, exact);
    }

    public static String decodeUtf8(byte[] payload) throws ProtocolException {
        Objects.requireNonNull(payload, "payload");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException(ProtocolException.Reason.INVALID_UTF8,
                    "frame payload is not valid UTF-8", exception);
        }
    }
}
