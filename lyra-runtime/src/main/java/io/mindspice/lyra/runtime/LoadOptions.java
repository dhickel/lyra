package io.mindspice.lyra.runtime;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Objects;

/** Immutable options used while loading a compiled artifact. */
public final class LoadOptions {
    private final RuntimeProfile profile;
    private final RuntimeAbi runtimeAbi;
    private final boolean previewEnabled;
    private final RuntimeIoEnvironment ioEnvironment;

    public LoadOptions(RuntimeProfile profile, RuntimeAbi runtimeAbi,
                       boolean previewEnabled, RuntimeIoEnvironment ioEnvironment) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.runtimeAbi = Objects.requireNonNull(runtimeAbi, "runtimeAbi");
        this.ioEnvironment = Objects.requireNonNull(ioEnvironment, "ioEnvironment");
        if (!profile.runtimeAbi().equals(runtimeAbi)) {
            throw new IllegalArgumentException("runtime ABI disagrees with runtime profile");
        }
        if (previewEnabled && !profile.previewSupported()) {
            throw new IllegalArgumentException(
                    "preview is enabled for a non-preview-capable profile");
        }
        this.previewEnabled = previewEnabled;
    }

    public LoadOptions(RuntimeProfile profile, RuntimeAbi runtimeAbi,
                       RuntimeIoEnvironment ioEnvironment) {
        this(profile, runtimeAbi, false, ioEnvironment);
    }

    public LoadOptions(RuntimeIoEnvironment ioEnvironment) {
        this(RuntimeProfile.CURRENT, RuntimeAbi.CURRENT, false, ioEnvironment);
    }

    public LoadOptions(RuntimeOptions options) {
        this(Objects.requireNonNull(options, "options").profile(), options.runtimeAbi(),
                options.previewEnabled(), options.ioEnvironment());
    }

    public static LoadOptions defaults() {
        return new LoadOptions(RuntimeProfile.CURRENT, RuntimeAbi.CURRENT, false,
                RuntimeIoEnvironment.defaults());
    }

    public static LoadOptions defaultOptions() {
        return defaults();
    }

    public static Builder builder() {
        return new Builder();
    }

    public RuntimeProfile profile() {
        return profile;
    }

    public RuntimeProfile runtimeProfile() {
        return profile;
    }

    public RuntimeProfile javaProfile() {
        return profile;
    }

    public RuntimeAbi runtimeAbi() {
        return runtimeAbi;
    }

    public boolean previewEnabled() {
        return previewEnabled;
    }

    public boolean preview() {
        return previewEnabled;
    }

    public RuntimeIoEnvironment ioEnvironment() {
        return ioEnvironment;
    }

    public RuntimeIoEnvironment environment() {
        return ioEnvironment;
    }

    public RuntimeIoEnvironment io() {
        return ioEnvironment;
    }

    /** Produces the per-instance immutable options bound to the calling thread. */
    RuntimeOptions runtimeOptions(Thread owner) {
        return new RuntimeOptions(owner, ioEnvironment, profile, runtimeAbi, previewEnabled);
    }

    /** Produces options sharing one loaded-artifact authentication domain. */
    RuntimeOptions runtimeOptions(Thread owner, LyraArtifactKey artifactKey) {
        return new RuntimeOptions(OwnerThread.of(owner), ioEnvironment, profile, runtimeAbi,
                previewEnabled, Objects.requireNonNull(artifactKey, "artifactKey"));
    }

    public LoadOptions withProfile(RuntimeProfile value) {
        return new LoadOptions(value, value.runtimeAbi(), previewEnabled, ioEnvironment);
    }

    public LoadOptions withRuntimeAbi(RuntimeAbi value) {
        return new LoadOptions(profile, value, previewEnabled, ioEnvironment);
    }

    public LoadOptions withPreviewEnabled(boolean value) {
        return new LoadOptions(profile, runtimeAbi, value, ioEnvironment);
    }

    public LoadOptions withIoEnvironment(RuntimeIoEnvironment value) {
        return new LoadOptions(profile, runtimeAbi, previewEnabled, value);
    }

    public LoadOptions forStreams(InputStream input, OutputStream output,
                                  OutputStream error, Charset charset) {
        return withIoEnvironment(new RuntimeIoEnvironment(input, output, error, charset));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof LoadOptions options
                && profile.equals(options.profile)
                && runtimeAbi.equals(options.runtimeAbi)
                && previewEnabled == options.previewEnabled
                && ioEnvironment.equals(options.ioEnvironment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, runtimeAbi, previewEnabled, ioEnvironment);
    }

    @Override
    public String toString() {
        return "LoadOptions[profile=" + profile + ",abi=" + runtimeAbi
                + ",preview=" + previewEnabled + "]";
    }

    public static final class Builder {
        private RuntimeProfile profile = RuntimeProfile.CURRENT;
        private RuntimeAbi runtimeAbi = RuntimeAbi.CURRENT;
        private boolean previewEnabled;
        private RuntimeIoEnvironment ioEnvironment = RuntimeIoEnvironment.defaults();

        public Builder profile(RuntimeProfile value) {
            profile = Objects.requireNonNull(value, "profile");
            return this;
        }

        public Builder runtimeOptions(RuntimeOptions value) {
            RuntimeOptions options = Objects.requireNonNull(value, "runtimeOptions");
            profile = options.profile();
            runtimeAbi = options.runtimeAbi();
            previewEnabled = options.previewEnabled();
            ioEnvironment = options.ioEnvironment();
            return this;
        }

        public Builder runtimeProfile(RuntimeProfile value) {
            return profile(value);
        }

        public Builder javaProfile(RuntimeProfile value) {
            return profile(value);
        }

        public Builder runtimeAbi(RuntimeAbi value) {
            runtimeAbi = Objects.requireNonNull(value, "runtimeAbi");
            return this;
        }

        public Builder previewEnabled(boolean value) {
            previewEnabled = value;
            return this;
        }

        public Builder preview(boolean value) {
            return previewEnabled(value);
        }

        public Builder ioEnvironment(RuntimeIoEnvironment value) {
            ioEnvironment = Objects.requireNonNull(value, "ioEnvironment");
            return this;
        }

        public Builder environment(RuntimeIoEnvironment value) {
            return ioEnvironment(value);
        }

        public Builder io(RuntimeIoEnvironment value) {
            return ioEnvironment(value);
        }

        public Builder streams(InputStream input, OutputStream output,
                               OutputStream error, Charset charset) {
            return ioEnvironment(new RuntimeIoEnvironment(input, output, error, charset));
        }

        public LoadOptions build() {
            return new LoadOptions(profile, runtimeAbi, previewEnabled, ioEnvironment);
        }
    }
}
