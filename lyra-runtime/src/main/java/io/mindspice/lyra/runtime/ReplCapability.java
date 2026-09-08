package io.mindspice.lyra.runtime;

import java.util.Objects;

/**
 * Versioned debug capability declaration recorded by a packaged artifact.
 *
 * <p>Presence means the publication embeds the complete original reachable
 * source snapshots, the canonical import resolution topology, the
 * reproducible scalar compilation options, and the exact production closure
 * requirement.  It is a deployment-layout declaration, not a security
 * signature and not an execution profile: NORMAL generated code may carry it
 * and ATTACHABLE generated code adds it to the existing hook requirements.</p>
 */
public record ReplCapability(int schema) {
    public static final int SCHEMA = 1;
    public static final ReplCapability CURRENT = new ReplCapability(SCHEMA);

    public ReplCapability {
        if (schema != SCHEMA) {
            throw new IllegalArgumentException("unsupported REPL capability schema: " + schema);
        }
    }

    public int schemaVersion() {
        return schema;
    }

    /** Canonical no-whitespace JSON object. */
    public String canonicalJson() {
        return "{\"schema\":" + schema + "}";
    }

    @Override
    public String toString() {
        return canonicalJson();
    }

    static ReplCapability parse(String canonicalJson) {
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        if (!canonicalJson.equals("{\"schema\":1}")) {
            throw new IllegalArgumentException(
                    "REPL capability must be the canonical {\"schema\":1} object");
        }
        return CURRENT;
    }
}
