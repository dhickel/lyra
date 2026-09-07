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
        return simple.startsWith("$lyra$tuple$") || simple.startsWith("$lyra$fn$");
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
                validateStructure(binaryName, definition);
                staged.definitions.put(binaryName, definition.clone());
            }
        });
        return staged.definitions.isEmpty() ? this : staged;
    }

    private byte[] definition(String name) {
        for (ClassLoader loader = this; loader instanceof SessionTypeLoader current; loader = loader.getParent()) {
            byte[] bytes = current.definitions.get(name);
            if (bytes != null) return bytes;
        }
        return null;
    }

    private static void validateStructure(String name, byte[] bytes) {
        try {
            var model = ClassFile.of().parse(bytes);
            boolean function = name.substring(name.lastIndexOf('.') + 1).startsWith("$lyra$fn$");
            int flags = function ? ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
                    : ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER;
            if (!model.thisClass().asSymbol().descriptorString().equals("L" + name.replace('.', '/') + ";")
                    || model.flags().flagsMask() != flags || !model.interfaces().isEmpty()
                    || model.superclass().isEmpty()
                    || !model.superclass().orElseThrow().asInternalName().equals("java/lang/Object")
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
            } else {
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
            }
            if (!ClassFile.of().verify(bytes).isEmpty()) {
                throw new LyraLinkException("invalid session structural bytecode: " + name);
            }
        } catch (LyraLinkException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LyraLinkException("malformed session structural type: " + name,
                    java.util.List.of(), failure);
        }
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
