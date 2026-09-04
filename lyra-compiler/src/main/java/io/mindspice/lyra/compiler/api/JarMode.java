package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.runtime.PackagingMode;

/** Public JAR packaging choices for a compiled artifact. */
public enum JarMode {
    THIN,
    BUNDLED,
    THIN_JAR,
    BUNDLED_JAR;

    public PackagingMode packagingMode() {
        return switch (this) {
            case THIN, THIN_JAR -> PackagingMode.THIN_JAR;
            case BUNDLED, BUNDLED_JAR -> PackagingMode.BUNDLED_JAR;
        };
    }

    public String canonicalSpelling() {
        return packagingMode().canonicalSpelling();
    }
}
