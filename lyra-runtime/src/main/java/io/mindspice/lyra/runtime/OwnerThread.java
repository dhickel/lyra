package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Immutable identity of the thread that owns a module instance. */
public final class OwnerThread {
    private final Thread thread;

    private OwnerThread(Thread thread) {
        this.thread = Objects.requireNonNull(thread, "thread");
    }

    public static OwnerThread capture() {
        return new OwnerThread(Thread.currentThread());
    }

    public static OwnerThread of(Thread thread) {
        return new OwnerThread(thread);
    }

    public Thread thread() {
        return thread;
    }

    public boolean isCurrent() {
        return Thread.currentThread() == thread;
    }

    /** Fails before a caller can inspect owner-confined state. */
    public void check() {
        if (!isCurrent()) {
            throw new LyraThreadException("operation requires the module owner thread");
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof OwnerThread owner && thread == owner.thread;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(thread);
    }

    @Override
    public String toString() {
        return "owner-thread";
    }
}
