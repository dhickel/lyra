package io.mindspice.lyra.compiler.lex;

import java.util.Objects;

/** Immutable decoded data attached to a token when the spelling has a value. */
public sealed interface TokenValue
        permits LiteralValue, TokenValue.None, TokenValue.Identifier, TokenValue.Modifier {
    /** Marker value used by punctuation, keywords, and EOF. */
    record None() implements TokenValue {
        public static final None INSTANCE = new None();
    }

    /** The decoded spelling of an ordinary ASCII identifier. */
    record Identifier(String name) implements TokenValue {
        public Identifier {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("identifier name must not be empty");
            }
        }

        public String value() {
            return name;
        }
    }

    /** The decoded current modifier spelling. */
    record Modifier(ModifierKind kind) implements TokenValue {
        public Modifier {
            Objects.requireNonNull(kind, "kind");
        }
    }
}
