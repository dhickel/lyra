package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;

import java.util.Objects;
import java.util.Optional;

/** A declaration with its complete checked contract and optional initializer. */
public record TypedDeclaration(
        DeclarationId id,
        String name,
        SourceSpan span,
        ModuleId moduleId,
        DeclarationKind kind,
        Optional<BindingContract> contract,
        Optional<TypedExpression> initializer,
        Optional<LambdaId> initializerLambda) implements ImmutablePhaseArtifact {
    public TypedDeclaration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("declaration name must not be empty");
        }
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(initializer, "initializer");
        Objects.requireNonNull(initializerLambda, "initializerLambda");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("typed declaration span belongs to another module");
        }
        if (initializer.isPresent() && kind != DeclarationKind.LET) {
            throw new IllegalArgumentException("only let declarations have initializers");
        }
        if (initializerLambda.isPresent() && kind != DeclarationKind.LET) {
            throw new IllegalArgumentException("only let declarations own initializer lambdas");
        }
        contract.ifPresent(value -> {
            if (value.valueType().hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.MUT)) {
                throw new IllegalArgumentException("binding mutability must not be duplicated in a value contract");
            }
        });
    }

    public DeclarationId declarationId() {
        return id;
    }

    public Optional<BindingContract> typeContract() {
        return contract;
    }
}
