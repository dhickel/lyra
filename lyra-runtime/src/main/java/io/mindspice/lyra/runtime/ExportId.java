package io.mindspice.lyra.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identity of one public export and its complete Lyra signature. */
public final class ExportId implements Comparable<ExportId> {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    public static final String DOMAIN_TAG = "LYRA-EXPORT-ID";

    private final ModuleId moduleId;
    private final String exportName;
    private final LyraSignature signature;
    private final String canonicalInput;
    private final String hash;

    public ExportId(ModuleId moduleId, String exportName, LyraSignature signature) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.exportName = validateName(exportName);
        this.signature = Objects.requireNonNull(signature, "signature");
        this.canonicalInput = DOMAIN_TAG + "/" + LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION
                + ";" + field(moduleId.isUri() ? "uri" : "path")
                + field(moduleId.value()) + field(exportName) + field(signature.canonicalSpelling());
        this.hash = HexFormat.of().formatHex(digest(moduleId, exportName, signature));
    }

    public static ExportId of(ModuleId moduleId, String exportName, LyraSignature signature) {
        return new ExportId(moduleId, exportName, signature);
    }

    public ModuleId moduleId() {
        return moduleId;
    }

    public ModuleId module() {
        return moduleId;
    }

    public String exportName() {
        return exportName;
    }

    public String name() {
        return exportName;
    }

    public LyraSignature signature() {
        return signature;
    }

    public String canonicalInput() {
        return canonicalInput;
    }

    public String hash() {
        return hash;
    }

    public String stableHash() {
        return hash;
    }

    public String id() {
        return hash;
    }

    @Override
    public int compareTo(ExportId other) {
        return canonicalInput.compareTo(Objects.requireNonNull(other, "other").canonicalInput);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ExportId id
                && moduleId.equals(id.moduleId) && exportName.equals(id.exportName)
                && signature.equals(id.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(moduleId, exportName, signature);
    }

    @Override
    public String toString() {
        return moduleId.canonicalSpelling() + "#" + exportName + ":" + signature.canonicalSpelling();
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "exportName");
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid Lyra export name: " + name);
        }
        return name;
    }

    private static byte[] digest(ModuleId moduleId, String name, LyraSignature signature) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            putBytes(digest, DOMAIN_TAG.getBytes(StandardCharsets.UTF_8));
            putInt(digest, LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION);
            putBytes(digest, (moduleId.isUri() ? "uri" : "path").getBytes(StandardCharsets.UTF_8));
            putBytes(digest, moduleId.value().getBytes(StandardCharsets.UTF_8));
            putBytes(digest, name.getBytes(StandardCharsets.UTF_8));
            putBytes(digest, signature.canonicalSpelling().getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String field(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }

    private static void putBytes(MessageDigest digest, byte[] bytes) {
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
