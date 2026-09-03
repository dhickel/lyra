package io.mindspice.lyra.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical SHA-256 revision of one Lyra source module. */
public final class ModuleRevision implements Comparable<ModuleRevision> {
    private static final int HEX_LENGTH = 64;
    public static final String DOMAIN_TAG = "LYRA-MODULE-REVISION";

    private final String value;

    private ModuleRevision(String value) {
        this.value = value;
    }

    public static ModuleRevision of(String value) {
        Objects.requireNonNull(value, "revision");
        if (!isRevision(value)) {
            throw new IllegalArgumentException("invalid module revision: " + value);
        }
        return new ModuleRevision(value);
    }

    public static ModuleRevision from(String value) {
        return of(value);
    }

    public static ModuleRevision compute(byte[] canonicalUtf8Bytes) {
        return compute(canonicalUtf8Bytes, Map.of());
    }

    public static ModuleRevision compute(byte[] canonicalUtf8Bytes,
                                         Map<String, String> semanticsOptions) {
        Objects.requireNonNull(canonicalUtf8Bytes, "canonicalUtf8Bytes");
        Objects.requireNonNull(semanticsOptions, "semanticsOptions");
        List<Map.Entry<String, String>> entries = new ArrayList<>(semanticsOptions.entrySet());
        for (Map.Entry<String, String> entry : entries) {
            String key = CanonicalJson.requireUtf8(entry.getKey(), "option key");
            CanonicalJson.requireUtf8(entry.getValue(), "option value");
            if (key.isBlank()) {
                throw new IllegalArgumentException("option key must not be blank");
            }
        }
        entries.sort(Map.Entry.comparingByKey());
        MessageDigest digest = sha256();
        putBytes(digest, DOMAIN_TAG.getBytes(StandardCharsets.UTF_8));
        putBytes(digest, canonicalUtf8Bytes);
        putInt(digest, LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION);
        putInt(digest, entries.size());
        for (Map.Entry<String, String> entry : entries) {
            putBytes(digest, Objects.requireNonNull(entry.getKey(), "option key")
                    .getBytes(StandardCharsets.UTF_8));
            putBytes(digest, Objects.requireNonNull(entry.getValue(), "option value")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return new ModuleRevision(HexFormat.of().formatHex(digest.digest()));
    }

    public String value() {
        return value;
    }

    public String hex() {
        return value;
    }

    public String canonicalSpelling() {
        return value;
    }

    public static boolean isRevision(String value) {
        if (value == null || value.length() != HEX_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int compareTo(ModuleRevision other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ModuleRevision revision && value.equals(revision.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
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
        digest.update(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(value).array());
    }
}
