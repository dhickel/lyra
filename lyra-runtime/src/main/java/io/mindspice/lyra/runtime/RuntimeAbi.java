package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Version of the runtime ABI understood by generated Lyra artifacts. */
public record RuntimeAbi(int major, int minor) implements Comparable<RuntimeAbi> {
    public static final int CURRENT_MAJOR = 1;
    public static final int CURRENT_MINOR = 0;
    public static final int MAJOR = CURRENT_MAJOR;
    public static final int MINOR = CURRENT_MINOR;
    public static final String VERSION = CURRENT_MAJOR + "." + CURRENT_MINOR;
    public static final RuntimeAbi CURRENT = new RuntimeAbi(CURRENT_MAJOR, CURRENT_MINOR);

    public RuntimeAbi {
        if (major < 1 || minor < 0) {
            throw new IllegalArgumentException("runtime ABI major must be positive and minor non-negative");
        }
    }

    public int majorVersion() {
        return major;
    }

    public int minorVersion() {
        return minor;
    }

    public static RuntimeAbi current() {
        return CURRENT;
    }

    public static RuntimeAbi of(int major, int minor) {
        return new RuntimeAbi(major, minor);
    }

    public static RuntimeAbi parse(String spelling) {
        Objects.requireNonNull(spelling, "spelling");
        int dot = spelling.indexOf('.');
        if (dot <= 0 || dot == spelling.length() - 1 || spelling.indexOf('.', dot + 1) >= 0) {
            throw new IllegalArgumentException("invalid runtime ABI: " + spelling);
        }
        try {
            return new RuntimeAbi(parseComponent(spelling.substring(0, dot)),
                    parseComponent(spelling.substring(dot + 1)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid runtime ABI: " + spelling, exception);
        }
    }

    /** Returns the canonical {@code major.minor} spelling. */
    public String canonicalSpelling() {
        return major + "." + minor;
    }

    public String canonical() {
        return canonicalSpelling();
    }

    /** True when this ABI can consume an artifact requiring {@code required}. */
    public boolean isCompatibleWith(RuntimeAbi required) {
        Objects.requireNonNull(required, "required");
        return major == required.major && minor >= required.minor;
    }

    public boolean isCurrentCompatible() {
        return CURRENT.isCompatibleWith(this);
    }

    @Override
    public int compareTo(RuntimeAbi other) {
        Objects.requireNonNull(other, "other");
        int majorComparison = Integer.compare(major, other.major);
        return majorComparison != 0 ? majorComparison : Integer.compare(minor, other.minor);
    }

    @Override
    public String toString() {
        return canonicalSpelling();
    }

    private static int parseComponent(String value) {
        if (value.length() > 1 && value.charAt(0) == '0') {
            throw new NumberFormatException("leading zero");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new NumberFormatException("non-digit");
            }
        }
        return Integer.parseInt(value);
    }
}
