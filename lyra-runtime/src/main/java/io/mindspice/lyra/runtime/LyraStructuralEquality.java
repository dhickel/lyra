package io.mindspice.lyra.runtime;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Per-comparison identity-pair set used by generated structural traversal. */
public final class LyraStructuralEquality {
    private final IdentityHashMap<Object, IdentityHashMap<Object, Boolean>> visited =
            new IdentityHashMap<>();

    /** Returns true only when this pair still needs its fields compared. */
    public boolean enter(Object left, Object right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        IdentityHashMap<Object, Boolean> rightByLeft =
                visited.computeIfAbsent(left, ignored -> new IdentityHashMap<>());
        if (rightByLeft.put(right, Boolean.TRUE) != null) return false;
        visited.computeIfAbsent(right, ignored -> new IdentityHashMap<>())
                .put(left, Boolean.TRUE);
        return true;
    }
}
