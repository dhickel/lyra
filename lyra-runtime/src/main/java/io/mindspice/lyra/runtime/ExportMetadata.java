package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.Optional;

/** Immutable public export compatibility and Java-name metadata. */
public final class ExportMetadata implements Comparable<ExportMetadata> {
    private final ExportId id;
    private final ModuleId moduleId;
    private final String name;
    private final LyraType contract;
    private final String jvmDescriptor;
    private final BindingMutability bindingMutability;
    private final String javaName;
    private final String getterName;
    private final String functionValueName;
    private final Optional<String> setterName;

    public ExportMetadata(ModuleId moduleId, String name, LyraSignature signature,
                          String jvmDescriptor, BindingMutability bindingMutability,
                          String javaName, String getterName, String functionValueName,
                          Optional<String> setterName) {
        this(new ExportId(moduleId, name, signature), jvmDescriptor, bindingMutability,
                javaName, getterName, functionValueName, setterName);
    }

    public ExportMetadata(ModuleId moduleId, String name, LyraSignature signature,
                          String jvmDescriptor) {
        this(moduleId, name, signature, jvmDescriptor, BindingMutability.IMMUTABLE,
                name, "get$" + name, "value$" + name, Optional.empty());
    }

    /** Creates metadata for either a callable or scalar exported contract. */
    public ExportMetadata(ModuleId moduleId, String name, LyraType contract,
                          String jvmDescriptor, BindingMutability bindingMutability,
                          String javaName, String getterName, String functionValueName,
                          Optional<String> setterName) {
        this(new ExportId(moduleId, name, contract), jvmDescriptor, bindingMutability,
                javaName, getterName, functionValueName, setterName);
    }

    public ExportMetadata(ModuleId moduleId, String name, LyraType contract,
                          String jvmDescriptor) {
        this(moduleId, name, contract, jvmDescriptor, BindingMutability.IMMUTABLE,
                name, "get$" + name, "value$" + name, Optional.empty());
    }

    public ExportMetadata(ModuleId moduleId, String name, LyraType contract,
                          String jvmDescriptor, BindingMutability bindingMutability) {
        this(moduleId, name, contract, jvmDescriptor, bindingMutability,
                name, "get$" + name, "value$" + name,
                bindingMutability == BindingMutability.MUTABLE
                        ? Optional.of("set$" + name) : Optional.empty());
    }

    public ExportMetadata(ExportId id, String jvmDescriptor,
                          BindingMutability bindingMutability, String javaName,
                          String getterName, String functionValueName,
                          Optional<String> setterName) {
        this.id = Objects.requireNonNull(id, "id");
        this.moduleId = id.moduleId();
        this.name = id.exportName();
        this.contract = id.contract();
        this.jvmDescriptor = JvmDescriptorValidator.requireMethodDescriptor(jvmDescriptor);
        this.bindingMutability = Objects.requireNonNull(bindingMutability, "bindingMutability");
        this.javaName = text(javaName, "javaName");
        this.getterName = text(getterName, "getterName");
        this.functionValueName = text(functionValueName, "functionValueName");
        this.setterName = Objects.requireNonNull(setterName, "setterName").map(value -> text(value, "setterName"));
        if (bindingMutability == BindingMutability.IMMUTABLE && this.setterName.isPresent()) {
            throw new IllegalArgumentException("immutable exports cannot have a setter");
        }
        if (bindingMutability == BindingMutability.MUTABLE && this.setterName.isEmpty()) {
            throw new IllegalArgumentException("mutable exports require a setter name");
        }
    }

    public static ExportMetadata of(ModuleId moduleId, String name, LyraSignature signature,
                                    String jvmDescriptor) {
        return new ExportMetadata(moduleId, name, signature, jvmDescriptor);
    }

    public ExportId id() {
        return id;
    }

    public ExportId exportId() {
        return id;
    }

    public ModuleId moduleId() {
        return moduleId;
    }

    public String name() {
        return name;
    }

    public String exportName() {
        return name;
    }

    /** Returns the callable signature; scalar exports have only {@link #contract()}. */
    public LyraSignature signature() {
        return id.signature();
    }

    public LyraType contract() {
        return contract;
    }

    public String canonicalContract() {
        return contract.canonicalSpelling();
    }

    public boolean isFunction() {
        return id.isFunction();
    }

    public String jvmDescriptor() {
        return jvmDescriptor;
    }

    public BindingMutability bindingMutability() {
        return bindingMutability;
    }

    public boolean mutable() {
        return bindingMutability == BindingMutability.MUTABLE;
    }

    public String javaName() {
        return javaName;
    }

    public String getterName() {
        return getterName;
    }

    public String functionValueName() {
        return functionValueName;
    }

    public Optional<String> setterName() {
        return setterName;
    }

    @Override
    public int compareTo(ExportMetadata other) {
        return id.compareTo(Objects.requireNonNull(other, "other").id);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ExportMetadata metadata
                && id.equals(metadata.id) && contract.equals(metadata.contract)
                && jvmDescriptor.equals(metadata.jvmDescriptor)
                && bindingMutability == metadata.bindingMutability
                && javaName.equals(metadata.javaName) && getterName.equals(metadata.getterName)
                && functionValueName.equals(metadata.functionValueName)
                && setterName.equals(metadata.setterName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, contract, jvmDescriptor, bindingMutability, javaName, getterName,
                functionValueName, setterName);
    }

    @Override
    public String toString() {
        return id.toString();
    }

    private static String text(String value, String field) {
        CanonicalJson.requireUtf8(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " contains a control character");
            }
        }
        return value;
    }
}

final class JvmDescriptorValidator {
    private JvmDescriptorValidator() {
    }

    static String requireMethodDescriptor(String descriptor) {
        CanonicalJson.requireUtf8(descriptor, "jvmDescriptor");
        if (descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            throw new IllegalArgumentException("invalid JVM method descriptor: " + descriptor);
        }
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            index = typeEnd(descriptor, index, false);
        }
        if (index >= descriptor.length()) {
            throw new IllegalArgumentException("invalid JVM method descriptor: " + descriptor);
        }
        index = typeEnd(descriptor, index + 1, true);
        if (index != descriptor.length()) {
            throw new IllegalArgumentException("invalid JVM method descriptor: " + descriptor);
        }
        return descriptor;
    }

    private static int typeEnd(String descriptor, int start, boolean returnType) {
        if (start >= descriptor.length()) {
            throw new IllegalArgumentException("truncated JVM descriptor: " + descriptor);
        }
        char value = descriptor.charAt(start);
        if ("BCSIZJFD".indexOf(value) >= 0) {
            return start + 1;
        }
        if (value == 'V') {
            if (!returnType) {
                throw new IllegalArgumentException("void parameter in JVM descriptor: " + descriptor);
            }
            return start + 1;
        }
        if (value == '[') {
            return typeEnd(descriptor, start + 1, false);
        }
        if (value == 'L') {
            int semicolon = descriptor.indexOf(';', start + 1);
            if (semicolon < 0 || semicolon == start + 1) {
                throw new IllegalArgumentException("invalid object JVM descriptor: " + descriptor);
            }
            if (descriptor.charAt(start + 1) == '/' || descriptor.charAt(semicolon - 1) == '/') {
                throw new IllegalArgumentException("invalid object JVM descriptor: " + descriptor);
            }
            for (int index = start + 1; index < semicolon; index++) {
                char character = descriptor.charAt(index);
                if (character == '.' || character == '[' || character == ';' || character == ':'
                        || character == '(' || character == ')' || Character.isWhitespace(character)
                        || Character.isISOControl(character)
                        || (character == '/' && descriptor.charAt(index - 1) == '/')) {
                    throw new IllegalArgumentException("invalid object JVM descriptor: " + descriptor);
                }
            }
            return semicolon + 1;
        }
        throw new IllegalArgumentException("invalid JVM descriptor: " + descriptor);
    }
}
