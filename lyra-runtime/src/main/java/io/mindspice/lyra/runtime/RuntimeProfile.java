package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.regex.Pattern;

/** Java/profile compatibility data recorded by a Lyra artifact. */
public record RuntimeProfile(
        String name,
        int javaClassFileTarget,
        boolean previewSupported,
        RuntimeAbi runtimeAbi) {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    public static final RuntimeProfile CURRENT = new RuntimeProfile(
            "java-25", LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET, true, RuntimeAbi.CURRENT);
    public static final RuntimeProfile JAVA_25 = CURRENT;

    public RuntimeProfile {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid runtime profile name: " + name);
        }
        if (javaClassFileTarget < 1) {
            throw new IllegalArgumentException("Java class-file target must be positive");
        }
        Objects.requireNonNull(runtimeAbi, "runtimeAbi");
    }

    public RuntimeProfile(String name, int javaClassFileTarget, boolean previewSupported) {
        this(name, javaClassFileTarget, previewSupported, RuntimeAbi.CURRENT);
    }

    public static RuntimeProfile current() {
        return CURRENT;
    }

    public String profileName() {
        return name;
    }

    public int javaTarget() {
        return javaClassFileTarget;
    }

    public String canonicalSpelling() {
        return name;
    }

    public String canonical() {
        return name;
    }

    /** Compatibility of this running profile with an artifact profile. */
    public boolean isCompatibleWith(RuntimeProfile required, boolean previewRequired) {
        Objects.requireNonNull(required, "required");
        return name.equals(required.name)
                && javaClassFileTarget == required.javaClassFileTarget
                && (!previewRequired || previewSupported)
                && runtimeAbi.isCompatibleWith(required.runtimeAbi);
    }

    public void requireCompatible(RuntimeProfile required, boolean previewRequired) {
        if (!isCompatibleWith(required, previewRequired)) {
            throw new LyraCompatibilityException(
                    "artifact profile is not compatible with runtime profile " + name);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
