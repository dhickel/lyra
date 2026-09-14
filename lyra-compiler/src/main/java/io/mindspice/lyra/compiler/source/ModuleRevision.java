package io.mindspice.lyra.compiler.source;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Deterministic revision helpers for one source module. */
public final class ModuleRevision {
    /** Kept in lock step with {@code LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION}. */
    public static final int LANGUAGE_CONTRACT_VERSION = 2;
    public static final String DOMAIN_TAG = "LYRA-MODULE-REVISION";

    private ModuleRevision() {
    }

    /** Computes the revision using the empty semantics-options set. */
    public static String compute(SourceSnapshot snapshot) {
        return compute(snapshot, RevisionOptions.empty());
    }

    public static String compute(SourceSnapshot snapshot, RevisionOptions options) {
        Objects.requireNonNull(snapshot, "snapshot");
        return compute(snapshot.utf8Bytes(), options);
    }

    public static String compute(SourceSnapshot snapshot, Map<String, String> options) {
        return compute(snapshot, RevisionOptions.of(options));
    }

    /**
     * Computes a revision from already canonical, BOM-free UTF-8 bytes.
     * Callers with source text should use {@link SourceSnapshot} so UTF-8
     * validation and BOM handling cannot be bypassed.
     */
    public static String compute(byte[] canonicalUtf8Bytes, RevisionOptions options) {
        return HexFormat.of().formatHex(digest(canonicalUtf8Bytes, options));
    }

    public static String compute(byte[] canonicalUtf8Bytes, Map<String, String> options) {
        return compute(canonicalUtf8Bytes, RevisionOptions.of(options));
    }

    public static byte[] digest(SourceSnapshot snapshot, RevisionOptions options) {
        Objects.requireNonNull(snapshot, "snapshot");
        return digest(snapshot.utf8Bytes(), options);
    }

    public static byte[] digest(byte[] canonicalUtf8Bytes, RevisionOptions options) {
        Objects.requireNonNull(canonicalUtf8Bytes, "canonicalUtf8Bytes");
        Objects.requireNonNull(options, "options");

        MessageDigest digest = sha256();
        putBytes(digest, DOMAIN_TAG.getBytes(StandardCharsets.UTF_8));
        putBytes(digest, canonicalUtf8Bytes);
        putInt(digest, LANGUAGE_CONTRACT_VERSION);
        putInt(digest, options.canonicalEntries().size());
        for (Map.Entry<String, String> entry : options.canonicalEntries()) {
            putBytes(digest, entry.getKey().getBytes(StandardCharsets.UTF_8));
            putBytes(digest, entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return digest.digest();
    }

    public static boolean isRevision(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hexadecimal = character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F';
            if (!hexadecimal) {
                return false;
            }
        }
        return true;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void putBytes(MessageDigest digest, byte[] value) {
        putInt(digest, value.length);
        digest.update(value);
    }

    private static void putInt(MessageDigest digest, int value) {
        byte[] encoded = ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(value)
                .array();
        digest.update(encoded);
    }
}
