package io.mindspice.lyra.compiler.backend.jvm;

import java.util.List;
import java.util.Objects;

/**
 * Internal nullable-primitive storage: a boolean presence component followed
 * by the primitive payload.  It is never a valid single JVM descriptor and is
 * rejected wherever a JVM method/field needs one descriptor.
 */
record JvmPresencePayload(JvmType presence, JvmType payload)
        implements JvmValueRepresentation {
    public JvmPresencePayload {
        Objects.requireNonNull(presence, "presence");
        Objects.requireNonNull(payload, "payload");
        if (presence.kind() != JvmTypeKind.BOOLEAN || !presence.descriptor().equals("Z")) {
            throw new IllegalArgumentException("nullable primitive presence must be boolean");
        }
        if (!payload.isPrimitive() || payload.isVoid()) {
            throw new IllegalArgumentException("nullable primitive payload must be non-void primitive");
        }
    }

    @Override
    public List<JvmType> components() {
        return List.of(presence, payload);
    }

    @Override
    public String canonicalSpelling() {
        return "presence-payload(" + presence.descriptor() + "," + payload.descriptor() + ")";
    }
}
