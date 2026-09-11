package io.mindspice.lyra.compiler.identity;

import io.mindspice.lyra.compiler.source.ModuleRevision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Declaration identity for a module-level nominal type, independent of its shape.
 *
 * <p>The occurrence is source-ordered among declarations of this same name in this
 * module revision, not a compilation-global {@link DeclarationId} ordinal. Import
 * aliases, source offsets, JVM names and runtime instance identities are not inputs.
 * This identifies a declaration; it does not authenticate a schema or live object.
 * The resolver must issue it from the actual source declaration and revision.</p>
 *
 * <p>This is the identity foundation for the in-progress nominal extension, not
 * evidence that nominal source types can already be compiled or loaded.</p>
 */
public record NominalTypeId(
        ModuleIdentity module, String revision, String name, long occurrence)
        implements Comparable<NominalTypeId> {
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Za-z0-9_]*");
    private static final String DOMAIN = "LYRA-NOMINAL-TYPE-ID/1;";

    public NominalTypeId {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(name, "name");
        if (!ModuleRevision.isRevision(revision)) {
            throw new IllegalArgumentException("nominal type revision must be a SHA-256 digest");
        }
        revision = revision.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("nominal type name must be a capitalized ASCII identifier");
        }
        if (occurrence < 0) {
            throw new IllegalArgumentException("nominal type occurrence must not be negative");
        }
    }

    /** Versioned, UTF-8-length-prefixed identity input, not a source type spelling. */
    public String canonicalInput() {
        return DOMAIN + field(module.canonicalKey()) + field(revision)
                + field(name) + field(Long.toString(occurrence));
    }

    /** Deterministic naming/inventory key, never a runtime capability. */
    public String stableHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalInput().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Override
    public int compareTo(NominalTypeId other) {
        return canonicalInput().compareTo(Objects.requireNonNull(other, "other").canonicalInput());
    }

    private static String field(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }
}
