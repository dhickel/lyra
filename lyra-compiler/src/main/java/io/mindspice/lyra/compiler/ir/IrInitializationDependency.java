package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Frozen eager-initialization dependency edge consumed by later lowering. */
public record IrInitializationDependency(
        ModuleId fromModule,
        ModuleId toModule,
        Optional<DeclarationId> initializerDeclaration,
        Optional<ReferenceId> referenceId,
        SourceSpan effectSpan,
        List<SourceSpan> sourcePath,
        List<FlowSiteId> sourceSitePath)
        implements ImmutablePhaseArtifact, Comparable<IrInitializationDependency> {
    public IrInitializationDependency {
        Objects.requireNonNull(fromModule, "fromModule");
        Objects.requireNonNull(toModule, "toModule");
        Objects.requireNonNull(initializerDeclaration, "initializerDeclaration");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(effectSpan, "effectSpan");
        sourcePath = copy(sourcePath, "sourcePath");
        sourceSitePath = copy(sourceSitePath, "sourceSitePath");
        if (fromModule.equals(toModule)) {
            throw new IllegalArgumentException("initialization dependency must cross modules");
        }
        if (sourcePath.isEmpty()) {
            sourcePath = List.of(effectSpan);
        }
        if (!sourcePath.contains(effectSpan)) {
            throw new IllegalArgumentException("initialization dependency path must include its effect span");
        }
        if (!sourceSitePath.isEmpty() && sourceSitePath.size() != sourcePath.size()) {
            throw new IllegalArgumentException(
                    "initialization dependency source-site and source-span paths must have equal lengths");
        }
    }

    public IrInitializationDependency(
            ModuleId fromModule,
            ModuleId toModule,
            Optional<DeclarationId> initializerDeclaration,
            Optional<ReferenceId> referenceId,
            SourceSpan effectSpan,
            List<SourceSpan> sourcePath) {
        this(fromModule, toModule, initializerDeclaration, referenceId, effectSpan,
                sourcePath, List.of());
    }

    public IrInitializationDependency(
            ModuleId fromModule,
            ModuleId toModule,
            Optional<DeclarationId> initializerDeclaration,
            SourceSpan effectSpan) {
        this(fromModule, toModule, initializerDeclaration, Optional.empty(), effectSpan,
                List.of(effectSpan), List.of());
    }

    public ModuleId sourceModule() {
        return fromModule;
    }

    public ModuleId dependencyModule() {
        return toModule;
    }

    public SourceSpan span() {
        return effectSpan;
    }

    public Optional<ReferenceId> reference() {
        return referenceId;
    }

    public List<SourceSpan> spans() {
        return sourcePath;
    }

    public String canonicalKey() {
        return fromModule + "->" + toModule
                + "/initializer=" + initializerDeclaration.map(Object::toString).orElse("-")
                + "/reference=" + referenceId.map(Object::toString).orElse("-")
                + "/effect=" + effectSpan + "/path=" + sourcePath
                + "/sites=" + sourceSitePath;
    }

    @Override
    public int compareTo(IrInitializationDependency other) {
        return canonicalKey().compareTo(Objects.requireNonNull(other, "other").canonicalKey());
    }

    public static Comparator<IrInitializationDependency> comparator() {
        return Comparator.naturalOrder();
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return List.copyOf(copy);
    }
}
