package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;

import java.util.List;
import java.util.Objects;

/** One deterministic strongly connected component of function signatures. */
public record FunctionScc(
        int ordinal,
        List<DeclarationId> declarations,
        List<FunctionSignatureLink> links,
        boolean recursive) {
    public FunctionScc {
        if (ordinal < 0) {
            throw new IllegalArgumentException("function SCC ordinal must not be negative");
        }
        declarations = copy(declarations, "declarations");
        links = copy(links, "links");
        if (declarations.isEmpty()) {
            throw new IllegalArgumentException("a function SCC must contain a declaration");
        }
    }

    public List<DeclarationId> members() {
        return declarations;
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
