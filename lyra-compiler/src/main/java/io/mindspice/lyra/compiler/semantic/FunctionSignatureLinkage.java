package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.types.LyraSignature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Complete immutable linkage table for predeclared function signatures. */
public final class FunctionSignatureLinkage implements ImmutablePhaseArtifact {
    private final Map<DeclarationId, LyraSignature> signatures;
    private final List<FunctionSignatureLink> links;
    private final List<FunctionScc> components;
    private final Map<DeclarationId, Integer> componentByDeclaration;

    public FunctionSignatureLinkage(
            Map<DeclarationId, LyraSignature> signatures,
            List<FunctionSignatureLink> links,
            List<FunctionScc> components) {
        Objects.requireNonNull(signatures, "signatures");
        Objects.requireNonNull(links, "links");
        Objects.requireNonNull(components, "components");

        List<Map.Entry<DeclarationId, LyraSignature>> entries = new ArrayList<>(signatures.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<DeclarationId, LyraSignature> signatureIndex = new LinkedHashMap<>();
        for (Map.Entry<DeclarationId, LyraSignature> entry : entries) {
            signatureIndex.put(Objects.requireNonNull(entry.getKey(), "signature key"),
                    Objects.requireNonNull(entry.getValue(), "signature value"));
        }
        List<FunctionSignatureLink> orderedLinks = new ArrayList<>();
        for (FunctionSignatureLink link : links) {
            orderedLinks.add(Objects.requireNonNull(link, "links must not contain null"));
        }
        orderedLinks.sort(Comparator.comparing(FunctionSignatureLink::from)
                .thenComparing(FunctionSignatureLink::to)
                .thenComparingInt(link -> link.span().startOffset()));

        List<FunctionScc> orderedComponents = new ArrayList<>();
        for (FunctionScc component : components) {
            orderedComponents.add(Objects.requireNonNull(component, "components must not contain null"));
        }
        orderedComponents.sort(Comparator.comparingInt(FunctionScc::ordinal));
        LinkedHashMap<DeclarationId, Integer> componentIndex = new LinkedHashMap<>();
        for (FunctionScc component : orderedComponents) {
            for (DeclarationId declaration : component.declarations()) {
                if (!signatureIndex.containsKey(declaration)) {
                    throw new IllegalArgumentException("SCC contains a non-function declaration");
                }
                if (componentIndex.put(declaration, component.ordinal()) != null) {
                    throw new IllegalArgumentException("function declaration occurs in two SCCs");
                }
            }
        }

        this.signatures = Collections.unmodifiableMap(signatureIndex);
        this.links = List.copyOf(orderedLinks);
        this.components = List.copyOf(orderedComponents);
        this.componentByDeclaration = Collections.unmodifiableMap(componentIndex);
    }

    public Map<DeclarationId, LyraSignature> signatures() {
        return signatures;
    }

    public List<FunctionSignatureLink> links() {
        return links;
    }

    public List<FunctionScc> components() {
        return components;
    }

    public List<FunctionScc> sccs() {
        return components;
    }

    public Optional<FunctionScc> component(DeclarationId declarationId) {
        Integer ordinal = componentByDeclaration.get(Objects.requireNonNull(declarationId, "declarationId"));
        if (ordinal == null) {
            return Optional.empty();
        }
        return components.stream().filter(component -> component.ordinal() == ordinal).findFirst();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof FunctionSignatureLinkage linkage
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
        return "FunctionSignatureLinkage[functions=" + signatures.size()
                + ", links=" + links.size() + ", sccs=" + components.size() + "]";
    }
}
