package io.mindspice.lyra.compiler.backend.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable exact JVM method shape paired with its canonical Lyra function
 * contract.  All parameters and the return are required to be single JVM
 * values; split nullable primitives are storage plans, never method ABI.
 */
record JvmSignaturePlan(
        String canonicalLyraSignature,
        JvmAbiBoundary boundary,
        List<JvmTypePlan> parameters,
        JvmTypePlan returnValue,
        String descriptor) {
    public JvmSignaturePlan {
        if (Objects.requireNonNull(canonicalLyraSignature, "canonicalLyraSignature").isBlank()
                || !canonicalLyraSignature.startsWith("Fn<")
                || canonicalLyraSignature.indexOf(';') < 0
                || !canonicalLyraSignature.endsWith(">")) {
            throw new IllegalArgumentException("canonical Lyra signature must be a complete Fn type");
        }
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(parameters, "parameters");
        ArrayList<JvmTypePlan> copied = new ArrayList<>(parameters.size());
        for (JvmTypePlan parameter : parameters) {
            Objects.requireNonNull(parameter, "parameters must not contain null");
            requireMethodComponent(parameter, "parameter");
            copied.add(parameter);
        }
        parameters = List.copyOf(copied);
        Objects.requireNonNull(returnValue, "returnValue");
        requireMethodComponent(returnValue, "return");
        descriptor = JvmDescriptors.requireMethodDescriptor(Objects.requireNonNull(descriptor, "descriptor"));
        if (JvmDescriptors.parseMethodDescriptor(descriptor).parameterSlots() >= 255) {
            throw new IllegalArgumentException(
                    "function signature exceeds the JVM instance-method receiver slot limit");
        }
        String expected = "(" + parameters.stream().map(JvmTypePlan::descriptor)
                .reduce("", String::concat) + ")" + returnValue.descriptor();
        if (!descriptor.equals(expected)) {
            throw new IllegalArgumentException("method descriptor does not match mapped components");
        }
        String expectedLyra = "Fn<" + parameters.stream()
                .map(JvmTypePlan::canonicalLyraType)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "," + right)
                + ";" + returnValue.canonicalLyraType() + ">";
        if (!canonicalLyraSignature.equals(expectedLyra)) {
            throw new IllegalArgumentException("Lyra signature does not match mapped components");
        }
        if (!parameters.stream().allMatch(value -> value.context().boundary() == boundary
                && value.context().position() == JvmValuePosition.FUNCTION_PARAMETER)
                || returnValue.context().boundary() != boundary
                || returnValue.context().position() != JvmValuePosition.FUNCTION_RETURN) {
            throw new IllegalArgumentException("signature component context disagrees with signature");
        }
    }

    public List<JvmTypePlan> parameterPlans() {
        return parameters;
    }

    public List<String> parameterDescriptors() {
        return parameters.stream().map(JvmTypePlan::descriptor).toList();
    }

    public JvmTypePlan result() {
        return returnValue;
    }

    public JvmTypePlan returnPlan() {
        return returnValue;
    }

    public String jvmDescriptor() {
        return descriptor;
    }

    public String methodDescriptor() {
        return descriptor;
    }

    public String descriptorString() {
        return descriptor;
    }

    public String canonicalSpelling() {
        return canonicalLyraSignature + "|boundary=" + boundary.canonicalSpelling()
                + "|descriptor=" + descriptor;
    }

    public String canonical() {
        return canonicalSpelling();
    }

    private static void requireMethodComponent(JvmTypePlan plan, String position) {
        if (!plan.isSingleValue()) {
            throw new IllegalArgumentException(
                    position + " cannot use a split presence/payload representation in a method descriptor");
        }
        if (plan.descriptor().equals("V") && !plan.context().position().isFunctionReturn()) {
            throw new IllegalArgumentException("void is legal only for a function return");
        }
    }
}
