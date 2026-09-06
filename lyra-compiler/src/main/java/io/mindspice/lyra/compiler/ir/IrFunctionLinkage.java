package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.semantic.FunctionSignatureLinkage;
import io.mindspice.lyra.compiler.types.LyraSignature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** IR-owned immutable copy of predeclared function recursion/linkage. */
public final class IrFunctionLinkage implements ImmutablePhaseArtifact {
    private final Map<DeclarationId, LyraSignature> signatures;
    private final List<IrFunctionLink> links;
    private final List<IrFunctionScc> components;

    public IrFunctionLinkage(
            Map<DeclarationId, LyraSignature> signatures,
            List<IrFunctionLink> links,
            List<IrFunctionScc> components) {
        Objects.requireNonNull(signatures, "signatures");
        TreeMap<DeclarationId, LyraSignature> signatureMap = new TreeMap<>();
        signatures.forEach((id, signature) -> signatureMap.put(
                Objects.requireNonNull(id, "signature id"),
                Objects.requireNonNull(signature, "signature")));
        ArrayList<IrFunctionLink> linkCopy = copy(links, "links");
        linkCopy.sort(Comparator.naturalOrder());
        ArrayList<IrFunctionScc> componentCopy = copy(components, "components");
        componentCopy.sort(Comparator.naturalOrder());
        java.util.HashSet<Integer> ordinals = new java.util.HashSet<>();
        TreeMap<DeclarationId, Integer> membership = new TreeMap<>();
        for (IrFunctionScc component : componentCopy) {
            if (!ordinals.add(component.ordinal())) {
                throw new IllegalArgumentException("function SCC ordinals must be unique");
            }
            for (DeclarationId declaration : component.declarations()) {
                if (!signatureMap.containsKey(declaration)
                        || membership.put(declaration, component.ordinal()) != null) {
                    throw new IllegalArgumentException("function SCC membership is incomplete");
                }
            }
        }
        if (!membership.keySet().equals(signatureMap.keySet())) {
            throw new IllegalArgumentException("function SCCs do not cover every function signature");
        }
        if (new java.util.LinkedHashSet<>(linkCopy).size() != linkCopy.size()) {
            throw new IllegalArgumentException("function links must be unique");
        }
        this.signatures = Collections.unmodifiableMap(new LinkedHashMap<>(signatureMap));
        this.links = List.copyOf(linkCopy);
        this.components = List.copyOf(componentCopy);
    }

    public static IrFunctionLinkage from(FunctionSignatureLinkage source) {
        Objects.requireNonNull(source, "source");
        List<IrFunctionLink> links = source.links().stream()
                .map(value -> new IrFunctionLink(value.from(), value.to(), value.referenceId(), value.span()))
                .toList();
        List<IrFunctionScc> components = source.components().stream()
                .map(value -> new IrFunctionScc(value.ordinal(), value.declarations(), value.links().stream()
                        .map(link -> new IrFunctionLink(link.from(), link.to(), link.referenceId(), link.span()))
                        .toList(), value.recursive()))
                .toList();
        return new IrFunctionLinkage(source.signatures(), links, components);
    }

    public Map<DeclarationId, LyraSignature> signatures() {
        return signatures;
    }

    public List<IrFunctionLink> links() {
        return links;
    }

    public List<IrFunctionScc> components() {
        return components;
    }

    public List<IrFunctionScc> sccs() {
        return components;
    }

    public java.util.Optional<IrFunctionScc> component(DeclarationId declarationId) {
        Objects.requireNonNull(declarationId, "declarationId");
        return components.stream().filter(value -> value.declarations().contains(declarationId)).findFirst();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof IrFunctionLinkage linkage
                && signatures.equals(linkage.signatures)
                && links.equals(linkage.links)
                && components.equals(linkage.components);
    }

    @Override
    public int hashCode() {
        return Objects.hash(signatures, links, components);
    }

    @Override
    public String toString() {
        return "IrFunctionLinkage[functions=" + signatures.size()
                + ", links=" + links.size() + ", sccs=" + components.size() + "]";
    }

    private static <T> ArrayList<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return copy;
    }
}
