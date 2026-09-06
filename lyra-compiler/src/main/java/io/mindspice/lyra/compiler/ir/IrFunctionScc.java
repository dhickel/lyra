package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** One deterministic function-signature SCC, including legal recursion. */
public record IrFunctionScc(
        int ordinal,
        List<DeclarationId> declarations,
        List<IrFunctionLink> links,
        boolean recursive)
        implements ImmutablePhaseArtifact, Comparable<IrFunctionScc> {
    public IrFunctionScc {
        if (ordinal < 0) {
            throw new IllegalArgumentException("function SCC ordinal must not be negative");
        }
        declarations = copy(declarations, "declarations");
        links = copy(links, "links");
        ArrayList<DeclarationId> orderedDeclarations = new ArrayList<>(declarations);
        orderedDeclarations.sort(Comparator.naturalOrder());
        ArrayList<IrFunctionLink> orderedLinks = new ArrayList<>(links);
        orderedLinks.sort(Comparator.naturalOrder());
        if (!declarations.equals(orderedDeclarations)
                || new LinkedHashSet<>(declarations).size() != declarations.size()
                || !links.equals(orderedLinks)
                || new LinkedHashSet<>(links).size() != links.size()) {
            throw new IllegalArgumentException("function SCC members and links must be unique and canonical");
        }
        if (declarations.isEmpty()) {
            throw new IllegalArgumentException("function SCC needs a declaration");
        }
    }

    public List<DeclarationId> members() {
        return declarations;
    }

    @Override
    public int compareTo(IrFunctionScc other) {
        return Integer.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return List.copyOf(copy);
    }
}
