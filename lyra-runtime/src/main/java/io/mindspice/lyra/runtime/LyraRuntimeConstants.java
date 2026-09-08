package io.mindspice.lyra.runtime;

/** Stable contract constants shared by the runtime and generated artifacts. */
public final class LyraRuntimeConstants {
    public static final int LANGUAGE_CONTRACT_VERSION = 1;
    public static final int LANGUAGE_VERSION = LANGUAGE_CONTRACT_VERSION;
    public static final RuntimeAbi RUNTIME_ABI = RuntimeAbi.CURRENT;
    public static final int RUNTIME_ABI_MAJOR = RuntimeAbi.CURRENT_MAJOR;
    public static final int RUNTIME_ABI_MINOR = RuntimeAbi.CURRENT_MINOR;
    public static final int ARTIFACT_SCHEMA_VERSION = 1;
    public static final int ARTIFACT_SCHEMA = ARTIFACT_SCHEMA_VERSION;
    public static final int DEBUG_MAP_SCHEMA_VERSION = 1;
    public static final int DEBUG_MAP_SCHEMA = DEBUG_MAP_SCHEMA_VERSION;
    public static final int JAVA_CLASS_FILE_TARGET = 25;
    public static final int JAVA_TARGET = JAVA_CLASS_FILE_TARGET;
    public static final String JAVA_PROFILE = "java-25";
    public static final String RUNTIME_GROUP_ID = "io.mindspice";
    public static final String RUNTIME_ARTIFACT_ID = "lyra-runtime";
    public static final String COMPILER_ARTIFACT_ID = "lyra-compiler";
    public static final String REPL_ARTIFACT_ID = "lyra-repl";
    public static final String RUNTIME_VERSION = "1.0-SNAPSHOT";
    public static final String COMPILER_VERSION = RUNTIME_VERSION;
    public static final String REPL_VERSION = RUNTIME_VERSION;
    public static final int REPL_CAPABILITY_SCHEMA = ReplCapability.SCHEMA;
    public static final String DEBUG_MAP_PATH = "META-INF/lyra/debug-map.json";
    public static final String ARTIFACT_METADATA_PATH = "META-INF/lyra/artifact.json";

    public static void requireLanguageContract(int version) {
        if (version != LANGUAGE_CONTRACT_VERSION) {
            throw new LyraCompatibilityException("unsupported language contract version: " + version);
        }
    }

    public static void requireArtifactSchema(int version) {
        if (version != ARTIFACT_SCHEMA_VERSION) {
            throw new LyraCompatibilityException("unsupported artifact schema version: " + version);
        }
    }

    public static void requireDebugMapSchema(int version) {
        if (version != DEBUG_MAP_SCHEMA_VERSION) {
            throw new LyraCompatibilityException("unsupported debug-map schema version: " + version);
        }
    }

    public static void requireJavaTarget(int target) {
        if (target != JAVA_CLASS_FILE_TARGET) {
            throw new LyraCompatibilityException("unsupported Java class-file target: " + target);
        }
    }

    private LyraRuntimeConstants() {
    }
}
