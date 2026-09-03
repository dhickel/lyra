package io.mindspice.lyra.compiler.backend.jvm;

import java.util.Objects;

/**
 * Immutable context supplied to every ABI mapping operation.  The context is
 * deliberately part of mapping equality/canonicalization so a descriptor can
 * never be mistaken for a complete Lyra contract.
 */
record JvmMappingContext(JvmAbiBoundary boundary, JvmValuePosition position) {
    public static final JvmMappingContext INTERNAL_BINDING =
            new JvmMappingContext(JvmAbiBoundary.INTERNAL, JvmValuePosition.BINDING);
    public static final JvmMappingContext INTERNAL_VALUE =
            new JvmMappingContext(JvmAbiBoundary.INTERNAL, JvmValuePosition.VALUE);
    public static final JvmMappingContext INTERNAL_PARAMETER =
            new JvmMappingContext(JvmAbiBoundary.INTERNAL, JvmValuePosition.FUNCTION_PARAMETER);
    public static final JvmMappingContext INTERNAL_RETURN =
            new JvmMappingContext(JvmAbiBoundary.INTERNAL, JvmValuePosition.FUNCTION_RETURN);
    public static final JvmMappingContext INTERNAL_CAPTURE =
            new JvmMappingContext(JvmAbiBoundary.INTERNAL, JvmValuePosition.CAPTURE);
    public static final JvmMappingContext INTERNAL_CELL =
            new JvmMappingContext(JvmAbiBoundary.INTERNAL, JvmValuePosition.CELL_VALUE);
    public static final JvmMappingContext INTERNAL_FUNCTION_VALUE =
            new JvmMappingContext(JvmAbiBoundary.INTERNAL, JvmValuePosition.FUNCTION_VALUE);

    public static final JvmMappingContext JAVA_VALUE =
            new JvmMappingContext(JvmAbiBoundary.JAVA_VISIBLE, JvmValuePosition.VALUE);
    public static final JvmMappingContext EXPORTED_VALUE =
            new JvmMappingContext(JvmAbiBoundary.JAVA_VISIBLE, JvmValuePosition.EXPORTED_VALUE);
    public static final JvmMappingContext JAVA_PARAMETER =
            new JvmMappingContext(JvmAbiBoundary.JAVA_VISIBLE, JvmValuePosition.FUNCTION_PARAMETER);
    public static final JvmMappingContext JAVA_RETURN =
            new JvmMappingContext(JvmAbiBoundary.JAVA_VISIBLE, JvmValuePosition.FUNCTION_RETURN);
    public static final JvmMappingContext TUPLE_FIELD =
            new JvmMappingContext(JvmAbiBoundary.JAVA_VISIBLE, JvmValuePosition.TUPLE_FIELD);
    public static final JvmMappingContext JAVA_ARRAY_ELEMENT =
            new JvmMappingContext(JvmAbiBoundary.JAVA_VISIBLE, JvmValuePosition.ARRAY_ELEMENT);
    public static final JvmMappingContext INTERNAL_ARRAY_ELEMENT =
            new JvmMappingContext(JvmAbiBoundary.INTERNAL, JvmValuePosition.ARRAY_ELEMENT);
    public static final JvmMappingContext JAVA_FUNCTION_VALUE =
            new JvmMappingContext(JvmAbiBoundary.JAVA_VISIBLE, JvmValuePosition.FUNCTION_VALUE);

    public JvmMappingContext {
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(position, "position");
        if (position == JvmValuePosition.EXPORTED_VALUE
                && boundary != JvmAbiBoundary.JAVA_VISIBLE) {
            throw new IllegalArgumentException("exported values require the Java-visible ABI boundary");
        }
        if ((position == JvmValuePosition.CAPTURE || position == JvmValuePosition.CELL_VALUE)
                && boundary != JvmAbiBoundary.INTERNAL) {
            throw new IllegalArgumentException(position + " is an internal-only ABI position");
        }
        if (position == JvmValuePosition.BINDING
                && boundary == JvmAbiBoundary.JAVA_VISIBLE) {
            throw new IllegalArgumentException("binding storage is not a Java-visible ABI position");
        }
    }

    public static JvmMappingContext of(JvmAbiBoundary boundary, JvmValuePosition position) {
        return new JvmMappingContext(boundary, position);
    }

    public static JvmMappingContext internal(JvmValuePosition position) {
        return of(JvmAbiBoundary.INTERNAL, position);
    }

    public static JvmMappingContext javaVisible(JvmValuePosition position) {
        return of(JvmAbiBoundary.JAVA_VISIBLE, position);
    }

    public boolean isJavaVisible() {
        return boundary == JvmAbiBoundary.JAVA_VISIBLE;
    }

    public boolean permitsMutableQualifier() {
        return position.permitsMutableQualifier()
                && (boundary == JvmAbiBoundary.INTERNAL
                || position == JvmValuePosition.FUNCTION_PARAMETER);
    }

    public boolean permitsInternalPresencePayload() {
        return boundary == JvmAbiBoundary.INTERNAL
                && position.permitsInternalPresencePayload();
    }

    public String canonicalSpelling() {
        return boundary.canonicalSpelling() + ":" + position.canonicalSpelling();
    }

    public String canonical() {
        return canonicalSpelling();
    }

    @Override
    public String toString() {
        return canonicalSpelling();
    }
}
