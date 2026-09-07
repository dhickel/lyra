package io.mindspice.lyra.runtime;

import java.util.Locale;

/**
 * Execution shape recorded by a compiled artifact.
 *
 * <p>The Java {@link RuntimeProfile} describes the class-file/runtime
 * platform.  This profile describes the generated Lyra ABI layered on that
 * platform.  NORMAL is intentionally the default and has no attachment or
 * session service surface.</p>
 */
public enum ArtifactProfile {
    NORMAL("normal", false, false),
    SESSION("session", false, true),
    ATTACHABLE("attachable", true, false);

    private final String spelling;
    private final boolean attachable;
    private final boolean session;

    ArtifactProfile(String spelling, boolean attachable, boolean session) {
        this.spelling = spelling;
        this.attachable = attachable;
        this.session = session;
    }

    public String canonicalSpelling() {
        return spelling;
    }

    public String canonical() {
        return spelling;
    }

    public boolean attachable() {
        return attachable;
    }

    public boolean isAttachable() {
        return attachable;
    }

    public boolean session() {
        return session;
    }

    public boolean isSession() {
        return session;
    }

    public boolean normal() {
        return this == NORMAL;
    }

    public static ArtifactProfile parse(String value) {
        if (value == null) {
            throw new NullPointerException("profile");
        }
        String spelling = value.toLowerCase(Locale.ROOT);
        for (ArtifactProfile profile : values()) {
            if (profile.spelling.equals(spelling)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("unknown artifact execution profile: " + value);
    }
}
