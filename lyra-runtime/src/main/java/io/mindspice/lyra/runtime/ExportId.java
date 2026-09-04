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
    private final LyraType contract;
    private final String canonicalInput;
    private final String hash;

    public ExportId(ModuleId moduleId, String exportName, LyraSignature signature) {
        this(moduleId, exportName, Objects.requireNonNull(signature, "signature").asFunctionType());
    }

    /** Creates an identity for either a callable or scalar exported contract. */
    public ExportId(ModuleId moduleId, String exportName, LyraType contract) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.exportName = validateName(exportName);
        this.contract = Objects.requireNonNull(contract, "contract");
        if (contract instanceof QualifiedType qualified) {
            qualified.validateFor(TypePosition.NESTED_VALUE);
        }
        this.canonicalInput = DOMAIN_TAG + "/" + LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION
                + ";" + field(moduleId.isUri() ? "uri" : "path")
                + field(moduleId.value()) + field(exportName) + field(contract.canonicalSpelling());
        this.hash = HexFormat.of().formatHex(digest(moduleId, exportName, contract));
    }

    public static ExportId of(ModuleId moduleId, String exportName, LyraSignature signature) {
        return new ExportId(moduleId, exportName, signature);
    }

    public static ExportId ofContract(ModuleId moduleId, String exportName, LyraType contract) {
        return new ExportId(moduleId, exportName, contract);
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

    /** Returns the callable signature; scalar identities have no callable signature. */
    public LyraSignature signature() {
        LyraType unqualified = contract.withoutQualifiers();
        if (!(unqualified instanceof FunctionType function)) {
            throw new IllegalStateException("scalar export has no callable signature: " + contract);
        }
        return LyraSignature.from(function);
    }

    /** Complete exported Lyra contract, including scalar values. */
    public LyraType contract() {
        return contract;
    }

    public String canonicalContract() {
        return contract.canonicalSpelling();
    }

    public boolean isFunction() {
        return contract.withoutQualifiers() instanceof FunctionType;
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
                && contract.equals(id.contract);
    }

    @Override
    public int hashCode() {
        return Objects.hash(moduleId, exportName, contract);
    }

    @Override
    public String toString() {
        return moduleId.canonicalSpelling() + "#" + exportName + ":" + contract.canonicalSpelling();
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "exportName");
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid Lyra export name: " + name);
        }
        return name;
    }

    private static byte[] digest(ModuleId moduleId, String name, LyraType contract) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            putBytes(digest, DOMAIN_TAG.getBytes(StandardCharsets.UTF_8));
            putInt(digest, LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION);
            putBytes(digest, (moduleId.isUri() ? "uri" : "path").getBytes(StandardCharsets.UTF_8));
            putBytes(digest, moduleId.value().getBytes(StandardCharsets.UTF_8));
            putBytes(digest, name.getBytes(StandardCharsets.UTF_8));
            putBytes(digest, contract.canonicalSpelling().getBytes(StandardCharsets.UTF_8));
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
