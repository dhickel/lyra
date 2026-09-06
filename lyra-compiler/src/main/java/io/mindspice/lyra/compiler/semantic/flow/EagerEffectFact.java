package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.Objects;
import java.util.Optional;

/**
 * One eager-effect witness attributed to the source initializer that can
 * execute it.  Initialization planning consumes these records directly,
 * without reconstructing expression flow.
 */
public record EagerEffectFact(
        ModuleId initializerModule,
        Optional<DeclarationId> initializerDeclaration,
        EagerEffectWitness witness)
        implements Comparable<EagerEffectFact> {
    public EagerEffectFact {
        Objects.requireNonNull(initializerModule, "initializerModule");
        Objects.requireNonNull(initializerDeclaration, "initializerDeclaration");
        Objects.requireNonNull(witness, "witness");
        if (!initializerModule.equals(witness.fromModule())) {
            throw new IllegalArgumentException(
                    "eager effect witness is attributed to another initializer module");
        }
    }

    public ModuleId fromModule() {
        return initializerModule;
    }

    public ModuleId targetModule() {
        return witness.targetModule();
    }

    public EagerEffectWitness effect() {
        return witness;
    }

    public EagerEffectWitness witness() {
        return witness;
    }

    @Override
    public int compareTo(EagerEffectFact other) {
        Objects.requireNonNull(other, "other");
        int comparison = initializerModule.compareTo(other.initializerModule);
        if (comparison != 0) {
            return comparison;
        }
        comparison = initializerDeclaration.map(Object::toString).orElse("")
                .compareTo(other.initializerDeclaration.map(Object::toString).orElse(""));
        return comparison != 0 ? comparison : witness.compareTo(other.witness);
    }

    public String canonicalKey() {
        return initializerModule + "/initializer="
                + initializerDeclaration.map(Object::toString).orElse("-")
                + "/" + witness.canonicalKey();
    }

    @Override
    public String toString() {
        return canonicalKey();
    }
}
