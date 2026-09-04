package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.ir.TypedIr;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable class-file output of the scalar/control emitter.
 *
 * <p>The artifact retains the exact validated IR and type plan used to produce
 * it.  Class bytes are copied on ingress and egress so callers cannot mutate a
 * published emission or accidentally make a later phase observe different
 * input/output identities.</p>
 */
final class JvmBytecodeArtifact implements ImmutablePhaseArtifact {
    private final TypedIr ir;
    private final GeneratedTypePlan typePlan;
    private final Map<String, byte[]> classFiles;
    private final Map<String, String> descriptors;
    private final boolean previewRequired;

    JvmBytecodeArtifact(TypedIr ir, GeneratedTypePlan typePlan,
                        Map<String, byte[]> classFiles,
                        Map<String, String> descriptors,
                        boolean previewRequired) {
        this.ir = Objects.requireNonNull(ir, "ir");
        this.typePlan = Objects.requireNonNull(typePlan, "typePlan");
        Objects.requireNonNull(classFiles, "classFiles");
        LinkedHashMap<String, byte[]> copied = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : classFiles.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "classFiles contains null name");
            byte[] bytes = Objects.requireNonNull(entry.getValue(),
                    "classFiles contains null bytes");
            if (copied.put(name, bytes.clone()) != null) {
                throw new IllegalArgumentException("duplicate emitted class: " + name);
            }
        }
        if (!List.copyOf(copied.keySet()).equals(typePlan.classNames())) {
            throw new IllegalArgumentException(
                    "emitted class order/inventory disagrees with the generated type plan");
        }
        this.classFiles = Collections.unmodifiableMap(copied);
        Objects.requireNonNull(descriptors, "descriptors");
        LinkedHashMap<String, String> copiedDescriptors = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : descriptors.entrySet()) {
            copiedDescriptors.put(
                    Objects.requireNonNull(entry.getKey(), "descriptors contains null key"),
                    Objects.requireNonNull(entry.getValue(), "descriptors contains null value"));
        }
        this.descriptors = Collections.unmodifiableMap(copiedDescriptors);
        this.previewRequired = previewRequired;
    }

    TypedIr ir() {
        return ir;
    }

    TypedIr typedIr() {
        return ir;
    }

    GeneratedTypePlan typePlan() {
        return typePlan;
    }

    GeneratedTypePlan plan() {
        return typePlan;
    }

    /** Emitted classes in the immutable deterministic plan order. */
    Map<String, byte[]> classes() {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : classFiles.entrySet()) {
            result.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(result);
    }

    Map<String, byte[]> classFiles() {
        return classes();
    }

    List<String> classNames() {
        return List.copyOf(classFiles.keySet());
    }

    Optional<byte[]> classBytes(String binaryName) {
        Objects.requireNonNull(binaryName, "binaryName");
        byte[] bytes = classFiles.get(binaryName);
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }

    byte[] bytes(String binaryName) {
        return classBytes(binaryName).orElseThrow(() ->
                new IllegalArgumentException("emitted class is absent: " + binaryName));
    }

    /** Exact planned descriptors keyed as {@code binaryName#member+descriptor}. */
    Map<String, String> descriptors() {
        return descriptors;
    }

    boolean previewRequired() {
        return previewRequired;
    }

    int classCount() {
        return classFiles.size();
    }
}
