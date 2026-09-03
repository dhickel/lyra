package io.mindspice.lyra.compiler.backend.jvm;

/** Planned JVM access for a generated member. */
enum GeneratedMemberVisibility {
    PRIVATE,
    PACKAGE,
    PROTECTED,
    PUBLIC;

    public boolean isPublic() {
        return this == PUBLIC;
    }

    public boolean isPrivate() {
        return this == PRIVATE;
    }
}
