package io.mindspice.lyra.compiler.identity;

import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraSignature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable public-export identity.  Its hash input contains only module identity,
 * export name, and the complete canonical Lyra signature.
 */
public final class ExportId implements Comparable<ExportId> {
    private static final Pattern IDENTIFIER =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final String DOMAIN_TAG = "LYRA-EXPORT-ID";

    private final ModuleIdentity module;
    private final String exportName;
    private final LyraSignature signature;
    private final String canonicalInput;
    private final String hash;

    public ExportId(ModuleId moduleId, String exportName, LyraSignature signature) {
        this(ModuleIdentity.of(moduleId), exportName, signature);
    }

    public ExportId(ModuleIdentity module, String exportName, LyraSignature signature) {
        this.module = Objects.requireNonNull(module, "module");
        this.exportName = validateExportName(exportName);
        this.signature = Objects.requireNonNull(signature, "signature");
        this.canonicalInput = canonicalInput(module, this.exportName, signature);
        this.hash = HexFormat.of().formatHex(digest(module, this.exportName, signature));
    }

    public static ExportId of(
            ModuleId moduleId, String exportName, LyraSignature signature) {
        return new ExportId(moduleId, exportName, signature);
    }

    public static ExportId of(
            ModuleIdentity module, String exportName, LyraSignature signature) {
        return new ExportId(module, exportName, signature);
    }

    /** Creates the identity from a declaration span without using span offsets. */
    public static ExportId from(
            SourceSpan declarationSpan, String exportName, LyraSignature signature) {
        Objects.requireNonNull(declarationSpan, "declarationSpan");
        return new ExportId(ModuleIdentity.from(declarationSpan), exportName, signature);
    }

    public ModuleIdentity module() {
        return module;
    }

    public ModuleIdentity moduleIdentity() {
        return module;
    }

    public ModuleId moduleId() {
        return module.moduleId();
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

    /** Deterministic length-prefixed textual representation of all hash inputs. */
    public String canonicalInput() {
        return canonicalInput;
    }

    /** Lowercase SHA-256 of the canonical export identity input. */
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
        return this == other
                || other instanceof ExportId export
                && module.equals(export.module)
                && exportName.equals(export.exportName)
                && signature.equals(export.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(module, exportName, signature);
    }

    @Override
    public String toString() {
        return module.canonicalKey() + "#" + exportName + ":" + signature.canonicalSpelling();
    }

    private static String validateExportName(String name) {
        Objects.requireNonNull(name, "exportName");
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid Lyra export name: " + name);
        }
        return name;
    }

    private static String canonicalInput(
            ModuleIdentity module, String name, LyraSignature signature) {
        return DOMAIN_TAG + "/" + ModuleRevision.LANGUAGE_CONTRACT_VERSION
                + ";" + field(module.moduleId().isUri() ? "uri" : "path")
                + field(module.moduleId().value())
                + field(name)
                + field(signature.canonicalSpelling());
    }

    private static byte[] digest(
            ModuleIdentity module, String name, LyraSignature signature) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            putBytes(digest, DOMAIN_TAG.getBytes(StandardCharsets.UTF_8));
            putInt(digest, ModuleRevision.LANGUAGE_CONTRACT_VERSION);
            putBytes(digest, (module.moduleId().isUri() ? "uri" : "path")
                    .getBytes(StandardCharsets.UTF_8));
            putBytes(digest, module.moduleId().value().getBytes(StandardCharsets.UTF_8));
            putBytes(digest, name.getBytes(StandardCharsets.UTF_8));
            putBytes(digest, signature.canonicalSpelling().getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String field(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        return utf8.length + ":" + value;
    }

    private static void putBytes(MessageDigest digest, byte[] value) {
        putInt(digest, value.length);
        digest.update(value);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
