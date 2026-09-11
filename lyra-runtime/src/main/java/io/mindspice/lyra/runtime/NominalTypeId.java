package io.mindspice.lyra.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Exact source declaration identity; this is metadata, never an object capability. */
public record NominalTypeId(ModuleId module, String revision, String name, long occurrence)
        implements Comparable<NominalTypeId> {
    public NominalTypeId {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(name, "name");
        if (!ModuleRevision.isRevision(revision)) throw new IllegalArgumentException("invalid nominal revision");
        if (!name.matches("[A-Z][A-Za-z0-9_]*")) throw new IllegalArgumentException("invalid nominal name");
        if (occurrence < 0) throw new IllegalArgumentException("negative nominal occurrence");
    }

    public String canonicalInput() {
        return "LYRA-NOMINAL-TYPE-ID/1;" + field(module.canonicalSpelling()) + field(revision)
                + field(name) + field(Long.toString(occurrence));
    }

    public String stableHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalInput().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String field(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    @Override public int compareTo(NominalTypeId other) {
        return canonicalInput().compareTo(other.canonicalInput());
    }
}
