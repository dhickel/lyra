package io.mindspice.lyra.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * An exact callable export handle bound to one module instance.
 *
 * <p>The returned {@link #methodHandle()} is already bound to the module
 * receiver. Its parameter and return types are the generated JVM types, so
 * callers can use {@link MethodHandle#invokeExact(Object...)} at a statically
 * known call site without a name search or overload resolution on each call.
 * The handle is immutable; module lifecycle checks remain in the generated
 * facade invocation.</p>
 */
public final class ExportHandle {
    private final ExportMetadata metadata;
    private final LyraSignature signature;
    private final MethodType methodType;
    private final MethodHandle methodHandle;
    private final MethodHandle functionValueHandle;
    private final MethodHandle functionValueAsObject;

    ExportHandle(ExportMetadata metadata, LyraSignature signature,
                 MethodType methodType, MethodHandle methodHandle) {
        this(metadata, signature, methodType, methodHandle, null);
    }

    ExportHandle(ExportMetadata metadata, LyraSignature signature,
                 MethodType methodType, MethodHandle methodHandle,
                 MethodHandle functionValueHandle) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.signature = Objects.requireNonNull(signature, "signature");
        this.methodType = Objects.requireNonNull(methodType, "methodType");
        this.methodHandle = Objects.requireNonNull(methodHandle, "methodHandle");
        if (!metadata.isFunction()) {
            throw new IllegalArgumentException("an export handle requires a callable export");
        }
        if (!metadata.signature().equals(signature)) {
            throw new IllegalArgumentException("export signature disagrees with metadata");
        }
        if (methodHandle.type() != methodType) {
            throw new IllegalArgumentException("bound handle type disagrees with method type");
        }
        if (functionValueHandle != null && functionValueHandle.type().parameterCount() != 0) {
            throw new IllegalArgumentException("function-value handle must be receiver-bound");
        }
        this.functionValueHandle = functionValueHandle;
        this.functionValueAsObject = functionValueHandle == null ? null
                : functionValueHandle.asType(MethodType.methodType(Object.class));
    }

    public ExportMetadata metadata() {
        return metadata;
    }

    public String name() {
        return metadata.name();
    }

    public ExportId exportId() {
        return metadata.id();
    }

    public LyraSignature signature() {
        return signature;
    }

    public String canonicalSignature() {
        return signature.canonicalSpelling();
    }

    public MethodType methodType() {
        return methodType;
    }

    /** Returns the exact handle bound to the owning module instance. */
    public MethodHandle methodHandle() {
        return methodHandle;
    }

    /** Alias for {@link #methodHandle()}. */
    public MethodHandle handle() {
        return methodHandle();
    }

    /** Returns the exact bound function-value getter, when the artifact exposes one. */
    public MethodHandle functionValueHandle() {
        if (functionValueHandle == null) {
            throw new IllegalStateException("the export has no function-value getter");
        }
        return functionValueHandle;
    }

    /**
     * Returns the authenticated generated function object for this export.
     * The object is produced by the generated facade, not by an arbitrary Java
     * callback adapter.
     */
    public Object functionValue() {
        if (functionValueAsObject == null) {
            throw new IllegalStateException("the export has no function-value getter");
        }
        try {
            return (Object) functionValueAsObject.invokeExact();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("function-value getter failed", failure);
        }
    }

    /** Alias for {@link #functionValue()}. */
    public Object asFunction() {
        return functionValue();
    }

    @Override
    public String toString() {
        return "ExportHandle[" + metadata.id() + "," + methodType + "]";
    }
}
