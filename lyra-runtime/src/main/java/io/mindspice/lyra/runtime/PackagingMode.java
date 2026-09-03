package io.mindspice.lyra.runtime;

import java.util.Arrays;

/** Supported artifact storage modes recorded in metadata. */
public enum PackagingMode {
    CLASSES("classes"),
    THIN_JAR("thin-jar"),
    BUNDLED_JAR("bundled-jar");

    private final String spelling;

    PackagingMode(String spelling) {
        this.spelling = spelling;
    }

    public String canonicalSpelling() {
        return spelling;
    }

    public String canonical() {
        return spelling;
    }

    public static PackagingMode parse(String spelling) {
        if (spelling == null) {
            throw new IllegalArgumentException("packaging mode must not be null");
        }
        return Arrays.stream(values())
                .filter(mode -> mode.spelling.equals(spelling))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown packaging mode: " + spelling));
    }

    @Override
    public String toString() {
        return spelling;
    }
}
