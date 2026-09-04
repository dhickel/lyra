package io.mindspice.lyra.runtime;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Objects;

/** Immutable options used when a runtime-owned module instance is created. */
public final class RuntimeOptions {
    private final OwnerThread owner;
    private final RuntimeIoEnvironment ioEnvironment;
    private final RuntimeProfile profile;
    private final RuntimeAbi runtimeAbi;
    private final boolean previewEnabled;
    private final LyraArtifactKey artifactKey;

    public RuntimeOptions(OwnerThread owner, RuntimeIoEnvironment ioEnvironment,
                          RuntimeProfile profile, RuntimeAbi runtimeAbi,
                          boolean previewEnabled) {
        this(owner, ioEnvironment, profile, runtimeAbi, previewEnabled, null);
    }

    RuntimeOptions(OwnerThread owner, RuntimeIoEnvironment ioEnvironment,
                   RuntimeProfile profile, RuntimeAbi runtimeAbi,
                   boolean previewEnabled, LyraArtifactKey artifactKey) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ioEnvironment = Objects.requireNonNull(ioEnvironment, "ioEnvironment");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.runtimeAbi = Objects.requireNonNull(runtimeAbi, "runtimeAbi");
        if (!profile.runtimeAbi().equals(runtimeAbi)) {
            throw new IllegalArgumentException("runtime ABI disagrees with runtime profile");
        }
        if (previewEnabled && !profile.previewSupported()) {
            throw new IllegalArgumentException("preview is enabled for a non-preview-capable profile");
        }
        this.previewEnabled = previewEnabled;
        this.artifactKey = artifactKey;
    }

    public RuntimeOptions(Thread ownerThread, RuntimeIoEnvironment ioEnvironment,
                          RuntimeProfile profile, RuntimeAbi runtimeAbi,
                          boolean previewEnabled) {
        this(OwnerThread.of(ownerThread), ioEnvironment, profile, runtimeAbi, previewEnabled);
    }

    public RuntimeOptions(OwnerThread owner, RuntimeIoEnvironment ioEnvironment,
                          RuntimeProfile profile, RuntimeAbi runtimeAbi) {
        this(owner, ioEnvironment, profile, runtimeAbi, false);
    }

    public RuntimeOptions(Thread ownerThread, RuntimeIoEnvironment ioEnvironment,
                          RuntimeProfile profile, RuntimeAbi runtimeAbi) {
        this(ownerThread, ioEnvironment, profile, runtimeAbi, false);
    }

    public RuntimeOptions(OwnerThread owner, RuntimeIoEnvironment ioEnvironment) {
        this(owner, ioEnvironment, RuntimeProfile.CURRENT, RuntimeAbi.CURRENT, false);
    }

    public static RuntimeOptions defaults() {
        return builder().build();
    }

    public static RuntimeOptions defaultOptions() {
        return defaults();
    }

    public static Builder builder() {
        return new Builder();
    }

    public OwnerThread owner() { return owner; }
    public Thread ownerThread() { return owner.thread(); }
    public RuntimeIoEnvironment ioEnvironment() { return ioEnvironment; }
    public RuntimeIoEnvironment environment() { return ioEnvironment; }
    public RuntimeIoEnvironment io() { return ioEnvironment; }
    public RuntimeProfile profile() { return profile; }
    public RuntimeAbi runtimeAbi() { return runtimeAbi; }
    public boolean previewEnabled() { return previewEnabled; }

    LyraArtifactKey artifactKey() { return artifactKey; }

    public RuntimeOptions forOwner(Thread thread) {
        return copy(OwnerThread.of(thread), ioEnvironment, previewEnabled);
    }

    public RuntimeOptions withIoEnvironment(RuntimeIoEnvironment environment) {
        return copy(owner, environment, previewEnabled);
    }

    public RuntimeOptions withPreviewEnabled(boolean enabled) {
        return copy(owner, ioEnvironment, enabled);
    }

    private RuntimeOptions copy(OwnerThread newOwner, RuntimeIoEnvironment environment,
                                boolean preview) {
        return artifactKey == null
                ? new RuntimeOptions(newOwner, environment, profile, runtimeAbi, preview)
                : new RuntimeOptions(newOwner, environment, profile, runtimeAbi, preview, artifactKey);
    }

    /** Performs the small compatibility check available before a loader exists. */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RuntimeOptions options
                && owner.equals(options.owner)
                && ioEnvironment.equals(options.ioEnvironment)
                && profile.equals(options.profile)
                && runtimeAbi.equals(options.runtimeAbi)
                && previewEnabled == options.previewEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, ioEnvironment, profile, runtimeAbi, previewEnabled);
    }

    @Override
    public String toString() {
        return "RuntimeOptions[profile=" + profile + ",abi=" + runtimeAbi
                + ",preview=" + previewEnabled + "]";
    }

    public void requireCompatible(ArtifactMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        try {
            LyraRuntimeConstants.requireArtifactSchema(metadata.schemaVersion());
            LyraRuntimeConstants.requireLanguageContract(metadata.languageContractVersion());
            LyraRuntimeConstants.requireDebugMapSchema(metadata.debugMapVersion());
            LyraRuntimeConstants.requireJavaTarget(metadata.javaClassFileTarget());
        } catch (LyraCompatibilityException exception) {
            throw exception;
        }
        if (metadata.javaClassFileTarget() != profile.javaClassFileTarget()
                || !runtimeAbi.isCompatibleWith(metadata.runtimeAbi())
                || !profile.isCompatibleWith(metadata.profile(), metadata.previewRequired())
                || (metadata.previewRequired() && !previewEnabled)) {
            throw new LyraCompatibilityException("artifact is incompatible with runtime options");
        }
    }

    public static final class Builder {
        private OwnerThread owner = OwnerThread.capture();
        private RuntimeIoEnvironment ioEnvironment = RuntimeIoEnvironment.defaults();
        private RuntimeProfile profile = RuntimeProfile.CURRENT;
        private RuntimeAbi runtimeAbi = RuntimeAbi.CURRENT;
        private boolean previewEnabled;

        public Builder owner(Thread thread) { owner = OwnerThread.of(thread); return this; }
        public Builder owner(OwnerThread value) { owner = Objects.requireNonNull(value, "owner"); return this; }
        public Builder ownerThread(Thread thread) { return owner(thread); }
        public Builder ioEnvironment(RuntimeIoEnvironment value) { ioEnvironment = Objects.requireNonNull(value, "ioEnvironment"); return this; }
        public Builder environment(RuntimeIoEnvironment value) { return ioEnvironment(value); }
        public Builder io(RuntimeIoEnvironment value) { return ioEnvironment(value); }
        public Builder streams(InputStream input, OutputStream output, OutputStream error, Charset charset) {
            ioEnvironment = new RuntimeIoEnvironment(input, output, error, charset);
            return this;
        }
        public Builder profile(RuntimeProfile value) { profile = Objects.requireNonNull(value, "profile"); return this; }
        public Builder runtimeAbi(RuntimeAbi value) { runtimeAbi = Objects.requireNonNull(value, "runtimeAbi"); return this; }
        public Builder previewEnabled(boolean value) { previewEnabled = value; return this; }

        public RuntimeOptions build() {
            return new RuntimeOptions(owner, ioEnvironment, profile, runtimeAbi, previewEnabled);
        }
    }
}
