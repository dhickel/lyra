package io.mindspice.lyra.compiler.session;

import io.mindspice.lyra.compiler.identity.GenerationId;
import io.mindspice.lyra.compiler.identity.ProducerId;
import io.mindspice.lyra.compiler.semantic.ResolvedExport;
import io.mindspice.lyra.compiler.semantic.TypedDeclaration;
import io.mindspice.lyra.compiler.semantic.flow.BindingFlowState;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummarySet;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.List;
import java.util.Objects;

/** Exact producer-qualified namespace and export contracts, not runtime storage authority. */
public record SessionModuleContract(Producer producer, List<Export> exports) {
    public SessionModuleContract {
        Objects.requireNonNull(producer, "producer");
        exports = List.copyOf(exports);
        if (exports.stream().map(value -> value.export().name()).distinct().count() != exports.size()
                || exports.stream().anyMatch(value -> !value.export().moduleId().equals(producer.moduleId()))) {
            throw new IllegalArgumentException("module export coverage is inconsistent");
        }
    }

    public record Producer(LogicalModuleId logicalModule, ModuleId moduleId, String revision,
                           GenerationId generationId, ProducerId producerId) {
        public Producer {
            Objects.requireNonNull(logicalModule, "logicalModule");
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(generationId, "generationId");
            Objects.requireNonNull(producerId, "producerId");
            if (!io.mindspice.lyra.compiler.source.ModuleRevision.isRevision(revision)) {
                throw new IllegalArgumentException("invalid producer revision");
            }
        }

        static Producer from(SessionModuleEnvironment.ModuleRecord record) {
            return new Producer(record.logicalModule(), record.moduleId(), record.revision(),
                    record.generationId(), record.producerId());
        }
    }

    /** The exported name and the actual declaration/storage owner remain distinct for re-exports. */
    public record Export(ResolvedExport export, Producer origin, TypedDeclaration declaration,
                         BindingFlowState boundaryState, CallableSummarySet callableSummaries) {
        public Export {
            Objects.requireNonNull(export, "export");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(declaration, "declaration");
            Objects.requireNonNull(boundaryState, "boundaryState");
            Objects.requireNonNull(callableSummaries, "callableSummaries");
            if (!export.originModule().equals(origin.moduleId())
                    || !export.originDeclaration().equals(declaration.id())
                    || !declaration.moduleId().equals(origin.moduleId())
                    || !declaration.contract().orElseThrow().equals(export.contract())) {
                throw new IllegalArgumentException("export lost its exact origin declaration contract");
            }
        }
    }

    public static SessionModuleContract from(SessionModuleEnvironment environment, ModuleId module) {
        var record = environment.module(module).orElseThrow();
        return new SessionModuleContract(Producer.from(record), record.exports().stream().map(export -> {
            var origin = environment.module(export.originModule()).orElseThrow();
            return new Export(export, Producer.from(origin), origin.producerGraph()
                    .declaration(export.originDeclaration()).orElseThrow(),
                    origin.finalState().orElseThrow(), origin.callableSummaries());
        }).toList());
    }
}
