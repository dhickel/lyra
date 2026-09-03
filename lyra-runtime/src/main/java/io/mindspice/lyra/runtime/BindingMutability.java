package io.mindspice.lyra.runtime;

/** Export binding permission, kept separate from the canonical function signature. */
public enum BindingMutability {
    IMMUTABLE("immutable"),
    MUTABLE("mutable");

    private final String spelling;

    BindingMutability(String spelling) {
        this.spelling = spelling;
    }

    public String canonicalSpelling() {
        return spelling;
    }

    public static BindingMutability parse(String spelling) {
        if (spelling == null) {
            throw new IllegalArgumentException("binding mutability must not be null");
        }
        for (BindingMutability value : values()) {
            if (value.spelling.equals(spelling)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown binding mutability: " + spelling);
    }

    @Override
    public String toString() {
        return spelling;
    }
}
