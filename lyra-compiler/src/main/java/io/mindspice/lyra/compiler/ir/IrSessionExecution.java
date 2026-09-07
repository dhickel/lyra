package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.session.SessionExecutionPlan;
import io.mindspice.lyra.compiler.session.SessionModuleContract;
import io.mindspice.lyra.compiler.session.SessionModuleEnvironment;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Sealed session execution projection. Retained semantic evidence is not executable code. */
public record IrSessionExecution(SessionExecutionPlan plan, SessionModuleEnvironment environment,
                                 List<ExternalAccess> externalAccesses) {
    public IrSessionExecution {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(environment, "environment");
        externalAccesses = List.copyOf(externalAccesses);
        var graph = environment.typedGraph().orElseThrow();
        plan.validateAgainst(graph);
        for (var work : plan.modules()) {
            if (work.scratch()) continue;
            var producer = environment.module(work.moduleId()).orElseThrow();
            if (!work.producerId().equals(producer.producerId()) || !work.generationId().equals(producer.generationId())) {
                throw new IllegalArgumentException("execution plan has a different producer/generation than its environment");
            }
        }
        if (!externalAccesses.equals(accesses(graph, plan, environment))) {
            throw new IllegalArgumentException("external accesses are not the exact producer-qualified projection");
        }
    }

    public record ExternalAccess(ModuleId consumer, SessionModuleContract.Export target, boolean writableFacade) {
        public ExternalAccess {
            Objects.requireNonNull(consumer, "consumer");
            Objects.requireNonNull(target, "target");
            if (writableFacade && !target.declaration().contract().orElseThrow().isMutable()) {
                throw new IllegalArgumentException("immutable export cannot have a facade writer");
            }
        }
        public DeclarationId declarationId() { return target.declaration().id(); }
    }

    public static IrSessionExecution from(TypedSemanticGraph graph, SessionExecutionPlan plan,
                                         SessionModuleEnvironment environment) {
        if (environment.typedGraph().orElseThrow() != graph) {
            throw new IllegalArgumentException("session projection belongs to a different typed graph");
        }
        return new IrSessionExecution(plan, environment, accesses(graph, plan, environment));
    }

    /** Intrinsic helper classes contain no Lyra source initialization. */
    public boolean emits(ModuleId module) {
        return plan.module(module).map(work -> work.isNew()
                || work.logicalModule().filter(value -> value.isStdIo()).isPresent()).orElse(false);
    }

    public Optional<ExternalAccess> access(ModuleId consumer, DeclarationId declaration) {
        return externalAccesses.stream().filter(value -> value.consumer().equals(consumer)
                && value.declarationId().equals(declaration)).findFirst();
    }

    private static List<ExternalAccess> accesses(TypedSemanticGraph graph, SessionExecutionPlan plan,
                                                 SessionModuleEnvironment environment) {
        List<ExternalAccess> result = new ArrayList<>();
        for (var binding : graph.resolvedGraph().imports()) {
            var consumer = graph.resolvedGraph().declaration(binding.declarationId()).orElseThrow().moduleId();
            if (!plan.module(consumer).orElseThrow().isNew()) continue;
            var contract = binding.producerContract().orElseGet(() ->
                    SessionModuleContract.from(environment, binding.targetModule()));
            for (var export : contract.exports()) {
                boolean newPlannedProducer = plan.module(export.origin().moduleId())
                        .filter(origin -> origin.generationId().equals(export.origin().generationId())
                                && origin.producerId().equals(export.origin().producerId()) && origin.isNew())
                        .isPresent();
                if (newPlannedProducer || export.origin().logicalModule().isStdIo()) continue;
                if (binding.isSelective() && !binding.importedName().orElseThrow().equals(export.export().name())) continue;
                if (result.stream().noneMatch(value -> value.consumer().equals(consumer)
                        && value.declarationId().equals(export.declaration().id()))) {
                    boolean writable = graph.resolvedGraph().exports().stream().anyMatch(value ->
                            value.moduleId().equals(consumer) && value.reExport() && value.contract().isMutable()
                                    && value.originDeclaration().equals(export.declaration().id()));
                    result.add(new ExternalAccess(consumer, export, writable));
                }
            }
        }
        return List.copyOf(result);
    }
}
