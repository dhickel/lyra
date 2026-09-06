package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Resolver-owned authority for the declaration/scope topology and exact source
 * binding and capture linkage of every resolved reference. It is
 * compilation-local and deliberately not part of graph equality or a
 * reconstruction API.
 */
final class ResolvedReferenceTopology {
    private final ScopeTree scopeTree;
    private final List<ResolvedDeclaration> declarations;
    private final List<ResolvedReference> references;
    private final List<ResolvedCapture> captures;
    private final Map<DeclarationId, List<ReferenceId>> referencesByDeclaration;
    private final Map<ScopeId, List<ReferenceId>> referencesByScope;
    private final Map<CaptureId, List<ReferenceId>> referencesByCapture;
    private final Map<ReferenceId, CaptureId> captureByReference;
    private ResolvedSemanticGraph owner;

    ResolvedReferenceTopology(
            ScopeTree scopeTree,
            List<ResolvedDeclaration> declarations,
            List<ResolvedReference> references,
            List<ResolvedCapture> captures) {
        this.scopeTree = Objects.requireNonNull(scopeTree, "scopeTree");
        Objects.requireNonNull(declarations, "declarations");
        ArrayList<ResolvedDeclaration> orderedDeclarations = new ArrayList<>();
        for (ResolvedDeclaration declaration : declarations) {
            orderedDeclarations.add(Objects.requireNonNull(
                    declaration, "declarations must not contain null"));
        }
        orderedDeclarations.sort(Comparator.comparing(ResolvedDeclaration::id));
        for (int index = 1; index < orderedDeclarations.size(); index++) {
            if (orderedDeclarations.get(index - 1).id().equals(
                    orderedDeclarations.get(index).id())) {
                throw new IllegalArgumentException(
                        "source topology contains a duplicate declaration identity");
            }
        }
        this.declarations = List.copyOf(orderedDeclarations);

        Objects.requireNonNull(references, "references");
        ArrayList<ResolvedReference> ordered = new ArrayList<>();
        for (ResolvedReference reference : references) {
            ordered.add(Objects.requireNonNull(
                    reference, "references must not contain null"));
        }
        ordered.sort(Comparator.comparing(ResolvedReference::id));
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).id().equals(ordered.get(index).id())) {
                throw new IllegalArgumentException(
                        "source topology contains a duplicate reference identity");
            }
        }
        this.references = List.copyOf(ordered);

        Objects.requireNonNull(captures, "captures");
        ArrayList<ResolvedCapture> orderedCaptures = new ArrayList<>();
        for (ResolvedCapture capture : captures) {
            orderedCaptures.add(Objects.requireNonNull(
                    capture, "captures must not contain null"));
        }
        orderedCaptures.sort(Comparator.comparing(ResolvedCapture::id));
        for (int index = 1; index < orderedCaptures.size(); index++) {
            if (orderedCaptures.get(index - 1).id().equals(
                    orderedCaptures.get(index).id())) {
                throw new IllegalArgumentException(
                        "source topology contains a duplicate capture identity");
            }
        }
        this.captures = List.copyOf(orderedCaptures);
        this.referencesByDeclaration = indexByDeclaration(this.references);
        this.referencesByScope = indexByScope(this.references);
        this.referencesByCapture = indexByCapture(this.captures);
        this.captureByReference = indexCaptureByReference(this.references);
    }

    ScopeTree scopeTree() {
        return scopeTree;
    }

    List<ResolvedDeclaration> declarations() {
        return declarations;
    }

    List<ResolvedReference> references() {
        return references;
    }

    List<ResolvedCapture> captures() {
        return captures;
    }

    Map<DeclarationId, List<ReferenceId>> referencesByDeclaration() {
        return referencesByDeclaration;
    }

    Map<ScopeId, List<ReferenceId>> referencesByScope() {
        return referencesByScope;
    }

    Map<CaptureId, List<ReferenceId>> referencesByCapture() {
        return referencesByCapture;
    }

    Map<ReferenceId, CaptureId> captureByReference() {
        return captureByReference;
    }

    synchronized void bind(ResolvedSemanticGraph graph) {
        if (owner != null) {
            throw new IllegalStateException(
                    "resolved source topology is already bound to a semantic graph");
        }
        owner = Objects.requireNonNull(graph, "graph");
    }

    synchronized void validateOwner(ResolvedSemanticGraph graph) {
        if (owner != Objects.requireNonNull(graph, "graph")) {
            throw new IllegalArgumentException(
                    "resolved source topology belongs to another semantic graph instance");
        }
    }

    private static Map<DeclarationId, List<ReferenceId>> indexByDeclaration(
            List<ResolvedReference> references) {
        TreeMap<DeclarationId, List<ReferenceId>> mutable = new TreeMap<>();
        for (ResolvedReference reference : references) {
            reference.targetDeclaration().ifPresent(declaration -> mutable
                    .computeIfAbsent(declaration, ignored -> new ArrayList<>())
                    .add(reference.id()));
        }
        return freezeIndex(mutable);
    }

    private static Map<ScopeId, List<ReferenceId>> indexByScope(
            List<ResolvedReference> references) {
        TreeMap<ScopeId, List<ReferenceId>> mutable = new TreeMap<>();
        for (ResolvedReference reference : references) {
            mutable.computeIfAbsent(reference.scopeId(), ignored -> new ArrayList<>())
                    .add(reference.id());
        }
        return freezeIndex(mutable);
    }

    private static Map<CaptureId, List<ReferenceId>> indexByCapture(
            List<ResolvedCapture> captures) {
        LinkedHashMap<CaptureId, List<ReferenceId>> result = new LinkedHashMap<>();
        for (ResolvedCapture capture : captures) {
            result.put(capture.id(), List.copyOf(capture.references()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<ReferenceId, CaptureId> indexCaptureByReference(
            List<ResolvedReference> references) {
        TreeMap<ReferenceId, CaptureId> result = new TreeMap<>();
        for (ResolvedReference reference : references) {
            reference.capture().ifPresent(capture -> result.put(reference.id(), capture));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static <K> Map<K, List<ReferenceId>> freezeIndex(
            Map<K, List<ReferenceId>> source) {
        LinkedHashMap<K, List<ReferenceId>> result = new LinkedHashMap<>();
        for (Map.Entry<K, List<ReferenceId>> entry : source.entrySet()) {
            ArrayList<ReferenceId> references = new ArrayList<>(entry.getValue());
            references.sort(Comparator.naturalOrder());
            result.put(entry.getKey(), List.copyOf(references));
        }
        return Collections.unmodifiableMap(result);
    }
}
