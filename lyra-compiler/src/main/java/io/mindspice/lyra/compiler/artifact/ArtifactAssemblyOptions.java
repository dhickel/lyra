package io.mindspice.lyra.compiler.artifact;

import io.mindspice.lyra.runtime.LyraRuntimeConstants;
import io.mindspice.lyra.runtime.PackagingMode;
import io.mindspice.lyra.runtime.RuntimeAbi;
import io.mindspice.lyra.runtime.RuntimeProfile;
import io.mindspice.lyra.runtime.RuntimeRequirement;

import java.util.Objects;
import java.util.Optional;

/**
 * Internal immutable assembly configuration.  This is deliberately not the
 * Phase-19 public compiler WriteOptions contract.
 */
final class ArtifactAssemblyOptions {
    static final String DEFAULT_COMPILER_VERSION = LyraRuntimeConstants.COMPILER_VERSION;
    static final String DEFAULT_COMPILER_BUILD = "lyra-phase18";

    private final PackagingMode packagingMode;
    private final boolean includeSources;
    private final boolean replCapable;
    private final RuntimeProfile profile;
    private final RuntimeAbi runtimeAbi;
    private final String compilerVersion;
    private final String compilerBuild;
    private final Optional<String> artifactId;
    private final Optional<RuntimeRequirement> runtimeRequirement;

    private ArtifactAssemblyOptions(Builder builder) {
        this.packagingMode = Objects.requireNonNull(builder.packagingMode, "packagingMode");
        this.includeSources = builder.includeSources;
        this.replCapable = builder.replCapable;
        this.profile = Objects.requireNonNull(builder.profile, "profile");
        this.runtimeAbi = Objects.requireNonNull(builder.runtimeAbi, "runtimeAbi");
        if (!this.runtimeAbi.equals(profile.runtimeAbi())) {
            throw new ArtifactAssemblyException("runtime ABI disagrees with assembly profile");
        }
        if (profile.javaClassFileTarget() != 25 || !profile.name().equals("java-25")) {
            throw new ArtifactAssemblyException("Lyra artifacts require the java-25 profile");
        }
        this.compilerVersion = text(builder.compilerVersion, "compilerVersion");
        this.compilerBuild = text(builder.compilerBuild, "compilerBuild");
        this.artifactId = Optional.ofNullable(builder.artifactId)
                .map(value -> text(value, "artifactId"));
        this.runtimeRequirement = packagingMode == PackagingMode.THIN_JAR
                && builder.runtimeRequirement.isEmpty()
                ? Optional.of(RuntimeRequirement.current())
                : Objects.requireNonNull(builder.runtimeRequirement, "runtimeRequirement");
        if (packagingMode == PackagingMode.THIN_JAR) {
            RuntimeRequirement requirement = runtimeRequirement.orElseThrow(() ->
                    new ArtifactAssemblyException("thin artifacts require an external runtime requirement"));
            if (!requirement.groupId().equals("io.mindspice")
                    || !requirement.artifactId().equals("lyra-runtime")
                    || !requirement.version().equals(LyraRuntimeConstants.RUNTIME_VERSION)) {
                throw new ArtifactAssemblyException("thin artifacts require the Lyra runtime coordinate");
            }
            if (requirement.profile().javaClassFileTarget() != 25
                    || !requirement.profile().name().equals("java-25")
                    || !requirement.profile().runtimeAbi().equals(requirement.minimumRuntimeAbi())) {
                throw new ArtifactAssemblyException("thin runtime requirement has an invalid profile");
            }
            if (!runtimeAbi.isCompatibleWith(requirement.minimumRuntimeAbi())) {
                throw new ArtifactAssemblyException("thin runtime requirement exceeds the artifact ABI");
            }
        } else if (runtimeRequirement.isPresent()) {
            throw new ArtifactAssemblyException(
                    "only thin artifacts may carry an external runtime requirement");
        }
    }

    static Builder builder() {
        return new Builder();
    }

    static ArtifactAssemblyOptions defaults() {
        return builder().build();
    }

    PackagingMode packagingMode() {
        return packagingMode;
    }

    boolean includeSources() {
        return includeSources;
    }

    /** True when the publication declares the debug REPL capability. */
    boolean replCapable() {
        return replCapable;
    }

    RuntimeProfile profile() {
        return profile;
    }

    RuntimeAbi runtimeAbi() {
        return runtimeAbi;
    }

    String compilerVersion() {
        return compilerVersion;
    }

    String compilerBuild() {
        return compilerBuild;
    }

    Optional<String> artifactId() {
        return artifactId;
    }

    Optional<RuntimeRequirement> runtimeRequirement() {
        return runtimeRequirement;
    }

    static final class Builder {
        private PackagingMode packagingMode = PackagingMode.CLASSES;
        private boolean includeSources;
        private boolean replCapable;
        private RuntimeProfile profile = RuntimeProfile.CURRENT;
        private RuntimeAbi runtimeAbi = RuntimeAbi.CURRENT;
        private String compilerVersion = DEFAULT_COMPILER_VERSION;
        private String compilerBuild = DEFAULT_COMPILER_BUILD;
        private String artifactId;
        private Optional<RuntimeRequirement> runtimeRequirement = Optional.empty();

        Builder packagingMode(PackagingMode value) {
            packagingMode = Objects.requireNonNull(value, "packagingMode");
            return this;
        }

        Builder includeSources(boolean value) {
            includeSources = value;
            return this;
        }

        Builder replCapable(boolean value) {
            replCapable = value;
            return this;
        }

        Builder profile(RuntimeProfile value) {
            profile = Objects.requireNonNull(value, "profile");
            return this;
        }

        Builder runtimeAbi(RuntimeAbi value) {
            runtimeAbi = Objects.requireNonNull(value, "runtimeAbi");
            return this;
        }

        Builder compilerVersion(String value) {
            compilerVersion = value;
            return this;
        }

        Builder compilerBuild(String value) {
            compilerBuild = value;
            return this;
        }

        Builder artifactId(String value) {
            artifactId = value;
            return this;
        }

        Builder runtimeRequirement(RuntimeRequirement value) {
            runtimeRequirement = Optional.ofNullable(value);
            return this;
        }

        Builder runtimeRequirement(Optional<RuntimeRequirement> value) {
            runtimeRequirement = Objects.requireNonNull(value, "runtimeRequirement");
            return this;
        }

        ArtifactAssemblyOptions build() {
            return new ArtifactAssemblyOptions(this);
        }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new ArtifactAssemblyException(name + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new ArtifactAssemblyException(name + " contains a control character");
            }
        }
        if (value.startsWith("/") || value.startsWith("\\\\")
                || value.matches("[A-Za-z]:[/\\\\].*")) {
            throw new ArtifactAssemblyException(name + " must not contain an absolute path");
        }
        return value;
    }
}
