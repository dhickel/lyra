package io.mindspice.lyra.compiler.backend.jvm;

import java.util.List;
import java.util.Objects;

/** One logical Lyra value represented by one JVM value. */
record JvmSingleValue(JvmType type) implements JvmValueRepresentation {
    public JvmSingleValue {
        Objects.requireNonNull(type, "type");
    }

    @Override
    public List<JvmType> components() {
        return List.of(type);
    }

    @Override
    public String canonicalSpelling() {
        return "single(" + type.descriptor() + ")";
    }
}
