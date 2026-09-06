package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Source witness for an eager value re-entry discovered while evaluating one
 * initializer.  Function-summary recursion is intentionally not represented
 * here; only active value declarations enter this witness.
 */
public record EagerCycleWitness(
        List<ModuleId> modules,
        SourceSpan primarySpan,
        Optional<DeclarationId> activeDeclaration,
        List<SourceSpan> sourcePath)
        implements Comparable<EagerCycleWitness> {
    public EagerCycleWitness {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(primarySpan, "primarySpan");
        Objects.requireNonNull(activeDeclaration, "activeDeclaration");
        Objects.requireNonNull(sourcePath, "sourcePath");
        LinkedHashSet<ModuleId> unique = new LinkedHashSet<>();
        for (ModuleId module : modules) {
            unique.add(Objects.requireNonNull(module, "modules must not contain null"));
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("eager cycle witness needs a module");
        }
        modules = unique.stream().sorted().toList();
        ArrayList<SourceSpan> path = new ArrayList<>();
        for (SourceSpan span : sourcePath) {
            path.add(Objects.requireNonNull(span, "sourcePath must not contain null"));
        }
        if (path.isEmpty()) {
            path.add(primarySpan);
        }
        if (!path.contains(primarySpan)) {
            path.add(primarySpan);
        }
        sourcePath = List.copyOf(path);
    }

    public List<ModuleId> members() {
        return modules;
    }

    public SourceSpan span() {
        return primarySpan;
    }

    public String canonicalKey() {
        return modules + "/" + primarySpan + "/active="
                + activeDeclaration.map(Object::toString).orElse("-")
                + "/path=" + sourcePath;
    }

    @Override
    public int compareTo(EagerCycleWitness other) {
        return canonicalKey().compareTo(
                Objects.requireNonNull(other, "other").canonicalKey());
    }

    @Override
    public String toString() {
        return canonicalKey();
    }
}
