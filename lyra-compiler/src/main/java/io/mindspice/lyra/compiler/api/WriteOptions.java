package io.mindspice.lyra.compiler.api;

/** Immutable artifact publication options. */
public record WriteOptions(boolean force) {
    public static final WriteOptions DEFAULTS = new WriteOptions(false);

    public static WriteOptions defaults() {
        return DEFAULTS;
    }

    public static WriteOptions defaultOptions() {
        return defaults();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean force;

        public Builder force(boolean value) {
            force = value;
            return this;
        }

        public WriteOptions build() {
            return new WriteOptions(force);
        }
    }
}
