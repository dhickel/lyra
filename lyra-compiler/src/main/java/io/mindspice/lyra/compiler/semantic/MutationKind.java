package io.mindspice.lyra.compiler.semantic;

/** Mutation operation whose authorization was proven during resolution. */
public enum MutationKind {
    REBINDING,
    ARRAY_ELEMENT,
    MEMBER_FIELD
}
