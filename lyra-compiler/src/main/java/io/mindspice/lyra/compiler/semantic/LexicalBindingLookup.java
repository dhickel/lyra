package io.mindspice.lyra.compiler.semantic;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Shared source-order lexical selection law for resolver production and audit. */
final class LexicalBindingLookup {
    private LexicalBindingLookup() {
    }

    static <S, D> D select(
            int sourceOffset,
            S startingScope,
            Function<S, List<D>> candidatesInScope,
            Function<S, S> parentScope,
            Function<D, DeclarationKind> declarationKind,
            ToIntFunction<D> declarationOffset,
            Predicate<D> signaturePredeclared) {
        Objects.requireNonNull(startingScope, "startingScope");
        Objects.requireNonNull(candidatesInScope, "candidatesInScope");
        Objects.requireNonNull(parentScope, "parentScope");
        Objects.requireNonNull(declarationKind, "declarationKind");
        Objects.requireNonNull(declarationOffset, "declarationOffset");
        Objects.requireNonNull(signaturePredeclared, "signaturePredeclared");

        S scope = startingScope;
        while (scope != null) {
            List<D> candidates = Objects.requireNonNull(
                    candidatesInScope.apply(scope), "candidatesInScope result");
            D visible = null;
            for (D candidate : candidates) {
                DeclarationKind kind = declarationKind.apply(candidate);
                if (kind == DeclarationKind.IMPORT_MODULE
                        || kind == DeclarationKind.IMPORT_VALUE
                        || declarationOffset.applyAsInt(candidate) <= sourceOffset) {
                    visible = candidate;
                }
            }
            if (visible != null) {
                return visible;
            }
            D predeclared = candidates.stream()
                    .filter(signaturePredeclared)
                    .min(Comparator.comparingInt(declarationOffset))
                    .orElse(null);
            if (predeclared != null) {
                return predeclared;
            }
            scope = parentScope.apply(scope);
        }
        return null;
    }
}
