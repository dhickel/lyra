package io.mindspice.lyra.compiler.backend.jvm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Package-private length-prefixed hashing for stable generated names/IDs. */
final class JvmStableHash {
    private JvmStableHash() {
    }

    static String sha256(String domain, String... fields) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(fields, "fields");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            put(digest, domain);
            for (String field : fields) {
                put(digest, Objects.requireNonNull(field, "hash field"));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void put(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
