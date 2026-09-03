package io.mindspice.lyra.runtime;

import java.io.Serial;
import java.io.Serializable;

/** The single value inhabiting the Lyra {@code Unit} type. */
public final class LyraUnit implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** The only Lyra Unit value. */
    public static final LyraUnit INSTANCE = new LyraUnit();

    private LyraUnit() {
    }

    @Serial
    private Object readResolve() {
        return INSTANCE;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return 0x4C595241;
    }

    @Override
    public String toString() {
        return "()";
    }
}
