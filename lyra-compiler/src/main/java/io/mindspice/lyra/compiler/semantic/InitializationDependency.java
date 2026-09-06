package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One conservative eager-initialization dependency and its source path. */
public record InitializationDependency(
        ModuleId fromModule,
        ModuleId toModule,
        Optional<DeclarationId> initializerDeclaration,
        Optional<ReferenceId> referenceId,
        SourceSpan effectSpan,
        List<SourceSpan> sourcePath) {
    public InitializationDependency {
        Objects.requireNonNull(fromModule, "fromModule");
        Objects.requireNonNull(toModule, "toModule");
        Objects.requireNonNull(initializerDeclaration, "initializerDeclaration");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(effectSpan, "effectSpan");
        Objects.requireNonNull(sourcePath, "sourcePath");
        if (fromModule.equals(toModule)) {
            throw new IllegalArgumentException("an initialization dependency must cross modules");
        }
        ArrayList<SourceSpan> copy = new ArrayList<>();
        for (SourceSpan span : sourcePath) {
            copy.add(Objects.requireNonNull(span, "sourcePath must not contain null"));
        }
        if (copy.isEmpty()) {
            copy.add(effectSpan);
        }
        sourcePath = List.copyOf(copy);
    }

    public InitializationDependency(
            ModuleId fromModule,
            ModuleId toModule,
            Optional<DeclarationId> initializerDeclaration,
            SourceSpan effectSpan) {
        this(fromModule, toModule, initializerDeclaration, Optional.empty(), effectSpan, List.of(effectSpan));
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

    public static Comparator<InitializationDependency> comparator() {
        return Comparator.comparing((InitializationDependency value) -> value.fromModule())
                .thenComparing(InitializationDependency::toModule)
                .thenComparing(value -> value.initializerDeclaration().map(Object::toString).orElse(""))
                .thenComparing(value -> value.effectSpan().sourceId().value())
                .thenComparingInt(value -> value.effectSpan().startOffset())
                .thenComparingInt(value -> value.effectSpan().endOffset())
                .thenComparing(value -> value.referenceId().map(Object::toString).orElse(""));
    }
}
