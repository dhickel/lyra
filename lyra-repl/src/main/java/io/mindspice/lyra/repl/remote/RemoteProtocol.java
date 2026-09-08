package io.mindspice.lyra.repl.remote;

/** Constants and hard limits for the credential-free loopback REPL protocol. */
public final class RemoteProtocol {
    public static final int VERSION = 2;
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    /** Minimum bound that can carry fixed terminal/result envelopes and bounded details. */
    public static final int MIN_FRAME_BYTES = 288;
    public static final int FRAME_HEADER_BYTES = Integer.BYTES;
    public static final int MAX_JSON_DEPTH = 16;
    public static final int MAX_JSON_MEMBERS = 64;
    public static final int MAX_JSON_ARRAY_ITEMS = 256;
    public static final int MAX_JSON_STRING_CHARS = 512 * 1024;
    public static final int MAX_SOURCE_CHARACTERS = 512 * 1024;
    public static final int MAX_LABEL_CHARACTERS = 4 * 1024;
    public static final int MAX_DIAGNOSTICS = 256;
    public static final int MAX_DIAGNOSTIC_CHARACTERS = 8 * 1024;
    public static final int MAX_QUERY_BINDINGS = 256;
    public static final int MAX_RETAINED_RESULTS = 256;
    public static final int MAX_LOAD_PATH_CHARACTERS = 4 * 1024;
    public static final int MAX_RELOAD_TARGET_CHARACTERS = 1024;
    public static final int MAX_INITIALIZERS = 256;
    public static final int MAX_COMPLETION_ITEMS = 256;
    public static final int MAX_COMPLETION_ITEM_CHARACTERS = 1024;
    public static final int MAX_COMPLETION_PREFIX_CHARACTERS = 4 * 1024;

    private RemoteProtocol() {
    }

    public static void requireVersion(int version) {
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported REPL protocol version: " + version);
        }
    }
}
