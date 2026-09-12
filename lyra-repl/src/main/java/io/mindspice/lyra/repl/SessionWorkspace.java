package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.SessionCompileResult;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.runtime.BindingMutability;
import io.mindspice.lyra.runtime.LyraType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable namespace projections; compiler and runtime use the same declaration/storage ordinals. */
final class SessionWorkspace {
    private WorkspaceState.Committed committed = WorkspaceState.empty();

    WorkspaceState.Committed committedState() {
        return committed;
    }

    Pending stage(SessionCompileResult.Success submission) {
        LinkedHashMap<String, BindingMetadata> bindings = new LinkedHashMap<>();
        LinkedHashMap<String, String> modules = new LinkedHashMap<>(committed.moduleRevisions());
        for (var module : submission.artifact().metadata().modules()) {
            String name = module.id().canonicalSpelling();
            String previous = modules.putIfAbsent(name, module.revision().value());
            if (previous != null && !previous.equals(module.revision().value())) {
                throw new Conflict(CompilerDiagnosticCodes.MODULE_DUPLICATE_IDENTITY,
                        "module identity is already committed at another revision: " + name);
            }
        }
        submission.stagedSnapshot().bindings().forEach((name, binding) -> bindings.put(name,
                new BindingMetadata(name, new BindingIdentity(binding.declarationId().ordinal()),
                        LyraType.parse(binding.type().canonicalSpelling(),
                                submission.artifact().metadata().nominalSchemas()),
                        BindingVisibility.valueOf(binding.visibility().name()),
                        binding.isMutable() ? BindingMutability.MUTABLE : BindingMutability.IMMUTABLE,
                        binding.storageIdentity().map(value -> new StorageIdentity(value.ordinal())))));
        return new Pending(new WorkspaceState.Pending(committed.revision(), committed.revision().next(), bindings, modules));
    }

    void commit(Pending pending) {
        Objects.requireNonNull(pending, "pending");
        if (!pending.state().baseRevision().equals(committed.revision())) {
            throw new IllegalStateException("workspace pending state is stale");
        }
        committed = new WorkspaceState.Committed(pending.state().revision(),
                pending.state().bindings(), pending.state().moduleRevisions());
    }

    void reset() {
        committed = new WorkspaceState.Committed(committed.revision(), Map.of(), Map.of());
    }

    void close() {
        reset();
    }

    record Pending(WorkspaceState.Pending state) {
        Pending { Objects.requireNonNull(state, "state"); }
    }

    static final class Conflict extends RuntimeException {
        private final io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code;
        Conflict(io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }
        io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code() { return code; }
    }
}
