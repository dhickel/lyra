package io.mindspice.lyra.runtime;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Session-owned structural ABI classes. State, closures and cells stay generation-local. */
final class SessionTypeLoader extends ClassLoader {
    private final Map<String, byte[]> definitions = new HashMap<>();
    private boolean retired;

    SessionTypeLoader() {
        super(LyraRuntime.sharedRuntimeLoader());
    }

    SessionTypeLoader(SessionTypeLoader parent) {
        super(Objects.requireNonNull(parent, "parent"));
    }

    static boolean isShared(String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return simple.startsWith("$lyra$tuple$") || simple.startsWith("$lyra$fn$")
                || simple.startsWith("$lyra$nominal$")
                || simple.startsWith("$lyra$delegate$");
    }

    /** Stages definitions without changing the live domain before complete load validation. */
    SessionTypeLoader stage(Map<String, byte[]> classes) {
        if (retired) throw new LyraLinkException("session structural type domain is retired");
        Objects.requireNonNull(classes, "classes");
        SessionTypeLoader staged = new SessionTypeLoader(this);
        classes.forEach((name, bytes) -> {
            String binaryName = Objects.requireNonNull(name, "class name");
            byte[] definition = Objects.requireNonNull(bytes, "class bytes");
            if (!isShared(binaryName)) return;
            byte[] existing = definition(binaryName);
            if (existing != null) {
                if (!Arrays.equals(existing, definition)) {
                    throw new LyraLinkException("session structural type definition changed: " + binaryName);
                }
            } else {
                staged.definitions.put(binaryName, definition.clone());
            }
        });
        // Validate only after every new shared definition is staged so a
        // delegate can authenticate its exact nominal field and interface
        // regardless of class-map iteration order. The staged loader is not
        // published if any definition fails.
        staged.definitions.forEach(staged::validateStructure);
        return staged.definitions.isEmpty() ? this : staged;
    }

    private byte[] definition(String name) {
        for (ClassLoader loader = this; loader instanceof SessionTypeLoader current; loader = loader.getParent()) {
            byte[] bytes = current.definitions.get(name);
            if (bytes != null) return bytes;
        }
        return null;
    }

    private void validateStructure(String name, byte[] bytes) {
        try {
            var model = ClassFile.of().parse(bytes);
            String simple = name.substring(name.lastIndexOf('.') + 1);
            boolean function = simple.startsWith("$lyra$fn$");
            boolean nominal = simple.startsWith("$lyra$nominal$");
            boolean delegate = simple.startsWith("$lyra$delegate$");
            int flags = function ? ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
                    : ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER;
            if (!model.thisClass().asSymbol().descriptorString().equals("L" + name.replace('.', '/') + ";")
                    || model.flags().flagsMask() != flags
                    || (!delegate && !model.interfaces().isEmpty())
                    || model.superclass().isEmpty()
                    || !model.superclass().orElseThrow().asInternalName().equals(nominal
                    ? "io/mindspice/lyra/runtime/LyraNominalObject"
                    : delegate
                    ? "io/mindspice/lyra/runtime/LyraNominalMemberDelegate" : "java/lang/Object")
                    || model.findAttribute(java.lang.classfile.Attributes.sourceFile())
                            .map(SourceFileAttribute::sourceFile).map(value -> value.stringValue())
                            .filter("$lyra$session-types"::equals).isEmpty()) {
                throw new LyraLinkException("invalid session structural type inventory: " + name);
            }
            if (function) {
                if (!model.fields().isEmpty() || model.methods().size() != 1
                        || !model.methods().getFirst().methodName().equalsString("invoke")
                        || model.methods().getFirst().flags().flagsMask() != (ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT)) {
                    throw new LyraLinkException("invalid session function interface: " + name);
                }
            } else if (delegate) {
                validateDelegate(name, simple, model);
            } else if (!nominal) {
                int fields = model.fields().size();
                StringBuilder constructor = new StringBuilder("(");
                for (int index = 0; index < fields; index++) {
                    var field = model.fields().get(index);
                    String descriptor = field.fieldType().stringValue();
                    String getter = "$lyra$get$" + index;
                    if (!field.fieldName().equalsString("$lyra$" + index)
                            || field.flags().flagsMask() != (ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL)
                            || model.methods().stream().noneMatch(method -> method.methodName().equalsString(getter)
                                && method.methodType().equalsString("()" + descriptor)
                                && method.flags().flagsMask() == ClassFile.ACC_PUBLIC)) {
                        throw new LyraLinkException("invalid session tuple component: " + name);
                    }
                    constructor.append(descriptor);
                }
                constructor.append(")V");
                if (fields == 0 || model.methods().size() != fields + 1
                        || model.methods().stream().noneMatch(method -> method.methodName().equalsString("<init>")
                            && method.methodType().equalsString(constructor.toString())
                            && method.flags().flagsMask() == ClassFile.ACC_PUBLIC)) {
                    throw new LyraLinkException("invalid session tuple constructor: " + name);
                }
            } else {
                boolean signatureFields = false;
                int fieldCount = 0;
                for (var field : model.fields()) {
                    String fieldName = field.fieldName().stringValue();
                    if (fieldName.startsWith("$lyra$signature$")) {
                        // Deterministic producer-scoped expected callable
                        // signature metadata: one private final instance
                        // field per distinct callable member signature,
                        // resolved exactly once per generated object.
                        signatureFields = true;
                        String suffix = fieldName.substring("$lyra$signature$".length());
                        if (suffix.length() != 16
                                || !suffix.chars().allMatch(SessionTypeLoader::isLowerHex)
                                || !field.fieldType().stringValue().equals(
                                "Lio/mindspice/lyra/runtime/LyraSignature;")
                                || field.flags().flagsMask()
                                != (ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL)) {
                            throw new LyraLinkException(
                                    "invalid session nominal signature metadata: " + name);
                        }
                        continue;
                    }
                    if (signatureFields
                            || !fieldName.equals("$lyra$field$" + fieldCount)
                            || (field.flags().flagsMask() & (ClassFile.ACC_PRIVATE
                            | ClassFile.ACC_STATIC)) != ClassFile.ACC_PRIVATE) {
                        throw new LyraLinkException(
                                "invalid session nominal field inventory: " + name);
                    }
                    fieldCount++;
                }
                if (model.methods().stream().noneMatch(method ->
                        method.methodName().equalsString("<init>")
                                && method.methodType().equalsString(
                                "(Lio/mindspice/lyra/runtime/LyraNominalConstruction;)V")
                                && method.flags().flagsMask() == ClassFile.ACC_PUBLIC)) {
                    throw new LyraLinkException("invalid session nominal constructor: " + name);
                }
            }
            // The class-file verifier cannot resolve a nominal's self-typed accessor
            // descriptors before this staged loader owns the definition. The JVM
            // verifier performs that hierarchy-aware check when the staged class is
            // loaded; retain the eager standalone check for tuples and functions.
            // Delegates reference nominal representation types the same way.
            var verificationErrors = nominal || delegate ? java.util.List.<VerifyError>of()
                    : ClassFile.of().verify(bytes);
            if (!verificationErrors.isEmpty()) {
                throw new LyraLinkException("invalid session structural bytecode: " + name
                        + ": " + verificationErrors);
            }
        } catch (LyraLinkException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LyraLinkException("malformed session structural type: " + name,
                    java.util.List.of(), failure);
        }
    }

    private void validateDelegate(String name, String simple, java.lang.classfile.ClassModel model) {
        // $lyra$delegate$<nominal-hash>$<field-index> over the nominal's base package.
        String prefix = "$lyra$delegate$";
        int indexSeparator = simple.lastIndexOf('$');
        if (indexSeparator <= prefix.length()) {
            throw new LyraLinkException("invalid session nominal delegate name: " + name);
        }
        String nominalHash = simple.substring(prefix.length(), indexSeparator);
        String fieldIndex = simple.substring(indexSeparator + 1);
        if (nominalHash.length() != 64
                || !nominalHash.chars().allMatch(value -> value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f')
                || fieldIndex.isEmpty()
                || !fieldIndex.chars().allMatch(Character::isDigit)) {
            throw new LyraLinkException("invalid session nominal delegate identity: " + name);
        }
        String packageName = name.substring(0, name.lastIndexOf('.') + 1);
        String nominalBinary = packageName + "$lyra$nominal$" + nominalHash;
        if (model.interfaces().size() != 1
                || !model.interfaces().getFirst().asInternalName().contains("/$lyra$fn$")
                || !model.fields().isEmpty()
                || model.methods().size() != 2) {
            throw new LyraLinkException("invalid session nominal delegate shape: " + name);
        }
        String interfaceName = model.interfaces().getFirst().asInternalName().replace('/', '.');
        if (!interfaceName.startsWith(packageName + "$lyra$fn$")) {
            throw new LyraLinkException("session nominal delegate interface is outside its type domain: " + name);
        }
        byte[] interfaceBytes = definition(interfaceName);
        byte[] nominalBytes = definition(nominalBinary);
        if (interfaceBytes == null || nominalBytes == null) {
            throw new LyraLinkException("session nominal delegate route types are absent: " + name);
        }
        var interfaceModel = ClassFile.of().parse(interfaceBytes);
        var nominalModel = ClassFile.of().parse(nominalBytes);
        var constructor = model.methods().stream()
                .filter(method -> method.methodName().equalsString("<init>"))
                .findFirst().orElse(null);
        var invoke = model.methods().stream()
                .filter(method -> method.methodName().equalsString("invoke"))
                .findFirst().orElse(null);
        var interfaceInvoke = interfaceModel.methods().stream()
                .filter(method -> method.methodName().equalsString("invoke"))
                .findFirst().orElse(null);
        int parsedIndex;
        try {
            parsedIndex = Integer.parseInt(fieldIndex);
        } catch (NumberFormatException failure) {
            throw new LyraLinkException("invalid session nominal delegate field index: " + name,
                    java.util.List.of(), failure);
        }
        if (!Integer.toString(parsedIndex).equals(fieldIndex)) {
            throw new LyraLinkException("non-canonical session nominal delegate field index: " + name);
        }
        var nominalField = nominalModel.fields().stream()
                .filter(field -> field.fieldName().equalsString("$lyra$field$" + parsedIndex))
                .findFirst().orElse(null);
        String interfaceDescriptor = "L" + model.interfaces().getFirst().asInternalName() + ";";
        if (constructor == null || invoke == null || interfaceInvoke == null || nominalField == null
                || constructor.flags().flagsMask() != 0
                || invoke.flags().flagsMask() != ClassFile.ACC_PUBLIC
                || !constructor.methodType().equalsString("(Ljava/lang/Object;)V")
                || !invoke.methodType().equals(interfaceInvoke.methodType())
                || interfaceInvoke.flags().flagsMask()
                != (ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT)
                || !nominalField.fieldType().equalsString(interfaceDescriptor)) {
            throw new LyraLinkException("invalid session nominal delegate members: " + name);
        }
    }

    private static boolean isLowerHex(int value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f');
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (retired) throw new ClassNotFoundException("retired session structural type domain: " + name);
        byte[] bytes = definitions.get(name);
        if (bytes == null) throw new ClassNotFoundException(name);
        return defineClass(name, bytes, 0, bytes.length);
    }

    void retire() {
        retire(true);
    }

    /** Retires only this extension, preserving an independently owned parent domain. */
    void retireSelf() {
        retire(false);
    }

    private void retire(boolean ancestors) {
        for (ClassLoader loader = this; loader instanceof SessionTypeLoader current;
             loader = ancestors ? loader.getParent() : null) {
            current.definitions.clear();
            current.retired = true;
        }
    }
}
