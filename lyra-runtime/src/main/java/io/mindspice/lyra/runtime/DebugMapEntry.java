package io.mindspice.lyra.runtime;

import java.util.Objects;

/** One BCI range mapped to an immutable Lyra source frame. */
public final class DebugMapEntry implements Comparable<DebugMapEntry> {
    private final String className;
    private final String methodName;
    private final int startBci;
    private final int endBci;
    private final SourceFrame frame;

    public DebugMapEntry(String className, String methodName, int startBci, int endBci,
                         SourceFrame frame) {
        this.className = text(className, "className");
        this.methodName = text(methodName, "methodName");
        if (startBci < 0 || endBci <= startBci) {
            throw new IllegalArgumentException("BCI range must be non-empty and end-exclusive");
        }
        this.startBci = startBci;
        this.endBci = endBci;
        this.frame = requireSerializableFrame(frame);
    }

    public static DebugMapEntry of(String className, String methodName, int startBci, int endBci,
                                   SourceFrame frame) {
        return new DebugMapEntry(className, methodName, startBci, endBci, frame);
    }

    public String className() { return className; }
    public String methodName() { return methodName; }
    public int startBci() { return startBci; }
    public int endBci() { return endBci; }
    public SourceFrame frame() { return frame; }
    public SourceSpan span() { return frame.span(); }
    public boolean synthetic() { return frame.synthetic(); }
    public boolean contains(int bci) { return startBci <= bci && bci < endBci; }

    @Override
    public int compareTo(DebugMapEntry other) {
        Objects.requireNonNull(other, "other");
        int value = className.compareTo(other.className);
        if (value != 0) return value;
        value = methodName.compareTo(other.methodName);
        if (value != 0) return value;
        value = Integer.compare(startBci, other.startBci);
        if (value != 0) return value;
        return Integer.compare(endBci, other.endBci);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof DebugMapEntry entry
                && className.equals(entry.className) && methodName.equals(entry.methodName)
                && startBci == entry.startBci && endBci == entry.endBci && frame.equals(entry.frame);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, startBci, endBci, frame);
    }

    private static SourceFrame requireSerializableFrame(SourceFrame frame) {
        SourceFrame current = Objects.requireNonNull(frame, "frame");
        while (true) {
            if (current.sourceData().isPresent()) {
                throw new IllegalArgumentException("debug-map frames must not embed source text");
            }
            if (current.origin().isEmpty()) {
                return frame;
            }
            current = current.origin().orElseThrow();
            if (current.synthetic()) {
                throw new IllegalArgumentException("debug-map origins must be non-synthetic");
            }
        }
    }

    private static String text(String value, String field) {
        CanonicalJson.requireUtf8(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " contains a control character");
            }
        }
        return value;
    }
}
