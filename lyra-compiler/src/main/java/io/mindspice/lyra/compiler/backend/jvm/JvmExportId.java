package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.ir.IrExport;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.runtime.LyraRuntimeConstants;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Stable ABI identity for every export, including scalar exports whose older
 * semantic model has no callable {@link ExportId}. Callable exports retain
 * their semantic identity while value-level qualifiers remain part of the
 * complete ABI contract.
 */
final class JvmExportId implements Comparable<JvmExportId> {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ModuleId moduleId;
    private final String exportName;
    private final String canonicalContract;
    private final Optional<ExportId> semanticExportId;
    private final String canonicalInput;
    private final String hash;

    public JvmExportId(
            ModuleId moduleId,
            String exportName,
            String canonicalContract,
            Optional<ExportId> semanticExportId) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.exportName = validateName(exportName);
        this.canonicalContract = requireText(canonicalContract, "canonicalContract");
        JvmTypePlan.requireCanonicalTypeSpelling(this.canonicalContract,
                JvmMappingContext.INTERNAL_VALUE);
        if (this.canonicalContract.startsWith("@mut")) {
            throw new IllegalArgumentException("export identity cannot carry binding mutability");
        }
        this.semanticExportId = Objects.requireNonNull(semanticExportId, "semanticExportId");
        String unqualifiedContract = this.canonicalContract.startsWith("@nil")
                ? this.canonicalContract.substring("@nil".length()) : this.canonicalContract;
        if (unqualifiedContract.startsWith("Fn<") && this.semanticExportId.isEmpty()) {
            throw new IllegalArgumentException(
                    "function ABI exports require their canonical semantic export identity");
        }
        if (!unqualifiedContract.startsWith("Fn<") && this.semanticExportId.isPresent()) {
            throw new IllegalArgumentException(
                    "scalar ABI exports cannot carry a callable semantic export identity");
        }
        semanticExportId.ifPresent(id -> {
            if (!id.moduleId().equals(moduleId) || !id.exportName().equals(exportName)
                    || !id.signature().canonicalSpelling().equals(unqualifiedContract)) {
                throw new IllegalArgumentException("semantic export identity disagrees with ABI export");
            }
        });
        boolean semanticSignatureIsComplete = semanticExportId
                .map(id -> id.signature().canonicalSpelling().equals(canonicalContract))
                .orElse(false);
        this.canonicalInput = semanticSignatureIsComplete
                ? semanticExportId.orElseThrow().canonicalInput()
                : canonicalExportInput(moduleId, exportName, canonicalContract);
        this.hash = semanticSignatureIsComplete
                ? semanticExportId.orElseThrow().hash()
                : canonicalExportHash(moduleId, exportName, canonicalContract);
    }

    public JvmExportId(ModuleId moduleId, String exportName, String canonicalContract) {
        this(moduleId, exportName, canonicalContract, Optional.empty());
    }

    public static JvmExportId from(
            ModuleId moduleId,
            String exportName,
            String canonicalContract,
            Optional<ExportId> semanticExportId) {
        return new JvmExportId(moduleId, exportName, canonicalContract, semanticExportId);
    }

    public static JvmExportId from(IrExport export) {
        Objects.requireNonNull(export, "export");
        return new JvmExportId(export.moduleId(), export.name(),
                export.contract().valueType().canonicalSpelling(), export.exportId());
    }

    public ModuleId moduleId() {
        return moduleId;
    }

    public String exportName() {
        return exportName;
    }

    public String canonicalContract() {
        return canonicalContract;
    }

    public String canonicalSignature() {
        return canonicalContract;
    }

    public Optional<ExportId> semanticExportId() {
        return semanticExportId;
    }

    public String canonicalInput() {
        return canonicalInput;
    }

    public String hash() {
        return hash;
    }

    public String id() {
        return hash;
    }

    @Override
    public int compareTo(JvmExportId other) {
        return canonicalInput.compareTo(Objects.requireNonNull(other, "other").canonicalInput);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof JvmExportId id
                && moduleId.equals(id.moduleId)
                && exportName.equals(id.exportName)
                && canonicalContract.equals(id.canonicalContract)
                && semanticExportId.equals(id.semanticExportId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(moduleId, exportName, canonicalContract, semanticExportId);
    }

    @Override
    public String toString() {
        return moduleId + "#" + exportName + ":" + canonicalContract;
    }

    private static String validateName(String value) {
        if (!NAME.matcher(Objects.requireNonNull(value, "exportName")).matches()) {
            throw new IllegalArgumentException("invalid Lyra export name: " + value);
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String canonicalExportInput(ModuleId moduleId, String name, String contract) {
        return "LYRA-EXPORT-ID/" + LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION
                + ";" + field(moduleId.isUri() ? "uri" : "path")
                + field(moduleId.value()) + field(name) + field(contract);
    }

    private static String canonicalExportHash(ModuleId moduleId, String name, String contract) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            putBytes(digest, "LYRA-EXPORT-ID".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            putInt(digest, LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION);
            putBytes(digest, (moduleId.isUri() ? "uri" : "path")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            putBytes(digest, moduleId.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            putBytes(digest, name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            putBytes(digest, contract.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void putBytes(java.security.MessageDigest digest, byte[] bytes) {
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putInt(java.security.MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String field(String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }
}
