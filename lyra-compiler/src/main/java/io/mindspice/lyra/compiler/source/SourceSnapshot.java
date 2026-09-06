package io.mindspice.lyra.compiler.source;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * An immutable, one-time source capture. The retained text is decoded UTF-8;
 * the initial UTF-8 BOM is metadata and is not part of the source text or
 * source revision bytes.
 */
public final class SourceSnapshot implements io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact {
    private static final byte UTF8_BOM_0 = (byte) 0xEF;
    private static final byte UTF8_BOM_1 = (byte) 0xBB;
    private static final byte UTF8_BOM_2 = (byte) 0xBF;

    private final SourceId sourceId;
    private final PhysicalSourceKey physicalKey;
    private final byte[] capturedUtf8Bytes;
    private final byte[] utf8Bytes;
    private final String text;
    private final SourceLineIndex lineIndex;
    private final byte[] sha256Digest;
    private final String sha256;
    private final boolean hadUtf8Bom;

    private SourceSnapshot(
            SourceId sourceId,
            PhysicalSourceKey physicalKey,
            byte[] capturedUtf8Bytes,
            byte[] utf8Bytes,
            String text,
            boolean hadUtf8Bom) {
        this.sourceId = sourceId;
        this.physicalKey = physicalKey;
        this.capturedUtf8Bytes = capturedUtf8Bytes;
        this.utf8Bytes = utf8Bytes;
        this.text = text;
        this.lineIndex = new SourceLineIndex(text);
        this.sha256Digest = digest(utf8Bytes);
        this.sha256 = HexFormat.of().formatHex(sha256Digest);
        this.hadUtf8Bom = hadUtf8Bom;
    }

    /** Captures and validates one UTF-8 source byte sequence. */
    public static PhaseResult<SourceSnapshot> capture(
            SourceId sourceId,
            PhysicalSourceKey physicalKey,
            byte[] capturedBytes) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(physicalKey, "physicalKey");
        Objects.requireNonNull(capturedBytes, "capturedBytes");

        byte[] retainedCapture = capturedBytes.clone();
        boolean hasBom = hasUtf8Bom(retainedCapture);
        int bomLength = hasBom ? 3 : 0;
        byte[] sourceBytes = Arrays.copyOfRange(retainedCapture, bomLength, retainedCapture.length);

        DecodeResult decoded = decode(sourceBytes);
        if (decoded.errorByteOffset >= 0) {
            int utf16Offset = decodeValidPrefix(sourceBytes, decoded.errorByteOffset).length();
            Diagnostic diagnostic = Diagnostic.error(
                    CompilerDiagnosticCodes.SOURCE_MALFORMED_UTF8,
                    SourceSpan.at(sourceId, utf16Offset),
                    "Malformed UTF-8 input at byte offset "
                            + (bomLength + decoded.errorByteOffset));
            return PhaseResult.failure(diagnostic);
        }

        return PhaseResult.success(new SourceSnapshot(
                sourceId,
                physicalKey,
                retainedCapture,
                sourceBytes,
                decoded.text,
                hasBom));
    }

    /** Alias emphasizing that the input is encoded UTF-8. */
    public static PhaseResult<SourceSnapshot> fromUtf8(
            SourceId sourceId,
            PhysicalSourceKey physicalKey,
            byte[] capturedBytes) {
        return capture(sourceId, physicalKey, capturedBytes);
    }

    public SourceId sourceId() {
        return sourceId;
    }

    public PhysicalSourceKey physicalKey() {
        return physicalKey;
    }

    /** Returns the exact bytes captured, including an ignored initial BOM. */
    public byte[] capturedUtf8Bytes() {
        return capturedUtf8Bytes.clone();
    }

    /** Returns canonical source bytes after removing an initial UTF-8 BOM. */
    public byte[] utf8Bytes() {
        return utf8Bytes.clone();
    }

    /** Alias for the canonical, BOM-free source bytes. */
    public byte[] bytes() {
        return utf8Bytes();
    }

    public boolean hadUtf8Bom() {
        return hadUtf8Bom;
    }

    /** Decoded UTF-16 source text. */
    public String text() {
        return text;
    }

    public String decodedText() {
        return text;
    }

    public int byteLength() {
        return utf8Bytes.length;
    }

    public int capturedByteLength() {
        return capturedUtf8Bytes.length;
    }

    public int utf16Length() {
        return text.length();
    }

    public SourceLineIndex lineIndex() {
        return lineIndex;
    }

    public SourcePosition positionAt(int utf16Offset) {
        return lineIndex.positionAt(utf16Offset);
    }

    /** SHA-256 of the canonical BOM-free UTF-8 source bytes, in lowercase hex. */
    public String sha256() {
        return sha256;
    }

    public String contentHash() {
        return sha256;
    }

    public byte[] sha256Digest() {
        return sha256Digest.clone();
    }

    public void validateSpan(SourceSpan span) {
        Objects.requireNonNull(span, "span").validateAgainst(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SourceSnapshot snapshot)) {
            return false;
        }
        return sourceId.equals(snapshot.sourceId)
                && physicalKey.equals(snapshot.physicalKey)
                && Arrays.equals(capturedUtf8Bytes, snapshot.capturedUtf8Bytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sourceId, physicalKey);
        result = 31 * result + Arrays.hashCode(capturedUtf8Bytes);
        return result;
    }

    @Override
    public String toString() {
        return "SourceSnapshot["
                + sourceId
                + ", utf16Length="
                + text.length()
                + ", sha256="
                + sha256
                + "]";
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == UTF8_BOM_0
                && bytes[1] == UTF8_BOM_1
                && bytes[2] == UTF8_BOM_2;
    }

    private static DecodeResult decode(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate(Math.max(1, Math.min(8192, bytes.length + 1)));
        StringBuilder text = new StringBuilder();

        while (true) {
            var result = decoder.decode(input, output, true);
            output.flip();
            text.append(output);
            output.clear();
            if (result.isError()) {
                return new DecodeResult(null, input.position());
            }
            if (result.isUnderflow()) {
                break;
            }
        }

        var result = decoder.flush(output);
        output.flip();
        text.append(output);
        if (result.isError()) {
            return new DecodeResult(null, input.position());
        }
        return new DecodeResult(text.toString(), -1);
    }

    private static String decodeValidPrefix(byte[] bytes, int length) {
        if (length == 0) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
        } catch (CharacterCodingException exception) {
            // The decoder's reported error position is the first invalid byte.
            throw new IllegalStateException("decoder returned an invalid error position", exception);
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record DecodeResult(String text, int errorByteOffset) {
    }
}
