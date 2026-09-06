package io.mindspice.lyra.repl;

/** Visibility context recorded for one binding description. */
public enum BindingVisibility {
    PRIVATE,
    PUBLIC,
    IMPORTED;

    public boolean isPublic() {
        return this == PUBLIC;
    }
}
