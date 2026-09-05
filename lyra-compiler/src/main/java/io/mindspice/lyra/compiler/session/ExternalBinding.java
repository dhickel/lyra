package io.mindspice.lyra.compiler.session;

import io.mindspice.lyra.compiler.api.SourceOrigin;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.BindingMutability;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.Objects;
import java.util.Optional;

/**
 * A typed value supplied by an already initialized session or application
 * root.  It is a declaration contract and storage authority, never a generic
 * Java value container.
 */
public record ExternalBinding(
        String name,
        DeclarationId declarationId,
        BindingContract contract,
        Visibility visibility,
        AssignmentAuthority assignmentAuthority,
        Optional<StorageIdentity> storageIdentity,
        SourceOrigin origin) {
    public ExternalBinding {
        name = token(name, "name");
        declarationId = Objects.requireNonNull(declarationId, "declarationId");
        contract = Objects.requireNonNull(contract, "contract");
        visibility = Objects.requireNonNull(visibility, "visibility");
        assignmentAuthority = Objects.requireNonNull(assignmentAuthority, "assignmentAuthority");
        storageIdentity = Objects.requireNonNull(storageIdentity, "storageIdentity");
        origin = Objects.requireNonNull(origin, "origin");
        if (contract.mutability() == BindingMutability.MUTABLE
                && storageIdentity.isEmpty()) {
            throw new IllegalArgumentException("a mutable external binding needs storage identity");
        }
        if (contract.mutability() == BindingMutability.IMMUTABLE
                && storageIdentity.isPresent()) {
            throw new IllegalArgumentException(
                    "an immutable external binding must not expose storage identity");
        }
        if (visibility == Visibility.IMPORTED
                && contract.mutability() == BindingMutability.MUTABLE) {
            throw new IllegalArgumentException("an imported external binding cannot be mutable");
        }
        if (visibility == Visibility.IMPORTED && assignmentAuthority != AssignmentAuthority.NONE) {
            throw new IllegalArgumentException("an imported external binding cannot grant assignment authority");
        }
        if (assignmentAuthority.allowsMutation()
                && contract.mutability() != BindingMutability.MUTABLE) {
            throw new IllegalArgumentException(
                    "assignment authority requires a mutable binding contract");
        }
    }

    public ExternalBinding(
            String name,
            DeclarationId declarationId,
            LyraType type,
            BindingMutability mutability,
            Visibility visibility,
            AssignmentAuthority assignmentAuthority,
            Optional<StorageIdentity> storageIdentity,
            SourceOrigin origin) {
        this(name, declarationId, new BindingContract(type, mutability), visibility,
                assignmentAuthority, storageIdentity, origin);
    }

    public ExternalBinding(
            String name,
            DeclarationId declarationId,
            BindingContract contract,
            SourceOrigin origin) {
        this(name, declarationId, contract, Visibility.PRIVATE, AssignmentAuthority.NONE,
                contract.mutability() == BindingMutability.MUTABLE
                        ? Optional.of(StorageIdentity.forDeclaration(declarationId))
                        : Optional.empty(), origin);
    }

    public LyraType type() {
        return contract.valueType();
    }

    public BindingMutability mutability() {
        return contract.mutability();
    }

    public boolean isMutable() {
        return mutability() == BindingMutability.MUTABLE;
    }

    public boolean allowsRebinding() {
        return assignmentAuthority.allowsRebinding();
    }

    public boolean allowsAggregateMutation() {
        return assignmentAuthority.allowsAggregateMutation();
    }

    public enum Visibility {
        PRIVATE,
        PUBLIC,
        IMPORTED
    }

    /** Explicit authority is intentionally independent from the value type. */
    public enum AssignmentAuthority {
        NONE(false, false),
        REBINDING(true, false),
        AGGREGATE_ELEMENT(false, true),
        ALL(true, true);

        private final boolean rebinding;
        private final boolean aggregateMutation;

        AssignmentAuthority(boolean rebinding, boolean aggregateMutation) {
            this.rebinding = rebinding;
            this.aggregateMutation = aggregateMutation;
        }

        public boolean allowsRebinding() {
            return rebinding;
        }

        public boolean allowsAggregateMutation() {
            return aggregateMutation;
        }

        public boolean allowsMutation() {
            return rebinding || aggregateMutation;
        }
    }

    private static String token(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
        return value;
    }
}
