package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.ir.IrDeclaration;
import io.mindspice.lyra.compiler.ir.IrExport;
import io.mindspice.lyra.compiler.ir.IrLambda;
import io.mindspice.lyra.compiler.ir.IrModule;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.runtime.ArtifactImport;
import io.mindspice.lyra.runtime.ArtifactProfile;

import java.util.ArrayList;
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
public final class JvmBytecodeArtifact implements ImmutablePhaseArtifact {
    /** Public compiler-pipeline bridge; the generated type plan remains internal. */
    public static PhaseResult<JvmBytecodeArtifact> emit(TypedIr ir, String basePackage) {
        EmissionMode mode = ir != null && ir.sessionExecution().isPresent()
                ? EmissionMode.SESSION : EmissionMode.NORMAL;
        return emit(ir, basePackage, mode, Map.of());
    }

    public static PhaseResult<JvmBytecodeArtifact> emit(TypedIr ir, String basePackage,
                                                         EmissionMode mode) {
        return emit(ir, basePackage, mode, Map.of());
    }

    public static PhaseResult<JvmBytecodeArtifact> emit(TypedIr ir, String basePackage,
                                                         EmissionMode mode,
                                                         Map<String, String> reproducibleOptions) {
        Objects.requireNonNull(ir, "ir").requireValidated();
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(reproducibleOptions, "reproducibleOptions");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir,
                Objects.requireNonNull(basePackage, "basePackage"), mode);
        PhaseResult<JvmBytecodeArtifact> result = JvmBytecodeEmitter.emitPhase(ir, plan);
        if (result instanceof PhaseResult.Success<JvmBytecodeArtifact> success) {
            // The emitter constructs the immutable bytecode inventory.  Copy
            // it into a context-bearing publication object only after the
            // phase has succeeded.
            JvmBytecodeArtifact emitted = success.value();
            return PhaseResult.success(new JvmBytecodeArtifact(
                    emitted.ir(), emitted.typePlan(), emitted.classes(), emitted.descriptors(),
                    emitted.previewRequired(), mode, reproducibleOptions), success.diagnostics());
        }
        return result;
    }

    /** Immutable export projection consumed by the internal artifact assembler. */
    public record EmittedExport(
            String stableId,
            ModuleId moduleId,
            String sourceName,
            String canonicalSignature,
            String jvmDescriptor,
            boolean mutable,
            String javaName,
            String getterName,
            String functionValueName,
            Optional<String> setterName,
            long declarationIdentity,
            long originDeclarationIdentity) {
        public EmittedExport(String stableId, ModuleId moduleId, String sourceName,
                             String canonicalSignature, String jvmDescriptor, boolean mutable,
                             String javaName, String getterName, String functionValueName,
                             Optional<String> setterName) {
            this(stableId, moduleId, sourceName, canonicalSignature, jvmDescriptor, mutable,
                    javaName, getterName, functionValueName, setterName, -1L, -1L);
        }

        public EmittedExport {
            Objects.requireNonNull(stableId, "stableId");
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(sourceName, "sourceName");
            Objects.requireNonNull(canonicalSignature, "canonicalSignature");
            Objects.requireNonNull(jvmDescriptor, "jvmDescriptor");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(getterName, "getterName");
            Objects.requireNonNull(functionValueName, "functionValueName");
            setterName = Objects.requireNonNull(setterName, "setterName");
            if (declarationIdentity < -1 || originDeclarationIdentity < -1) {
                throw new IllegalArgumentException("export declaration identities must not be negative");
            }
        }
    }

    /** One generated method's source origin, before BCI ranges are recovered. */
    public record EmittedMethod(
            String internalClassName,
            String methodName,
            String methodDescriptor,
            ModuleId moduleId,
            String functionName,
            SourceSpan originSpan,
            boolean synthetic) {
        public EmittedMethod {
            Objects.requireNonNull(internalClassName, "internalClassName");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(methodDescriptor, "methodDescriptor");
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(functionName, "functionName");
            Objects.requireNonNull(originSpan, "originSpan");
        }
    }

    private final TypedIr ir;
    private final GeneratedTypePlan typePlan;
    private final Map<String, byte[]> classFiles;
    private final Map<String, String> descriptors;
    private final boolean previewRequired;
    private final EmissionMode emissionMode;
    private final Map<String, String> reproducibleOptions;

    public JvmBytecodeArtifact(TypedIr ir, GeneratedTypePlan typePlan,
                               Map<String, byte[]> classFiles,
                               Map<String, String> descriptors,
                               boolean previewRequired) {
        this(ir, typePlan, classFiles, descriptors, previewRequired,
                Objects.requireNonNull(typePlan, "typePlan").emissionMode(), Map.of());
    }

    public JvmBytecodeArtifact(TypedIr ir, GeneratedTypePlan typePlan,
                               Map<String, byte[]> classFiles,
                               Map<String, String> descriptors,
                               boolean previewRequired, EmissionMode emissionMode,
                               Map<String, String> reproducibleOptions) {
        this.ir = Objects.requireNonNull(ir, "ir").requireValidated();
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
            validateClassFile(name, bytes);
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
        LinkedHashMap<String, String> expectedDescriptors = new LinkedHashMap<>();
        for (GeneratedClassPlan classPlan : typePlan.classes()) {
            for (GeneratedMemberPlan member : classPlan.members()) {
                expectedDescriptors.put(classPlan.binaryName() + "#" + member.declarationKey(),
                        member.descriptor());
            }
        }
        if (!expectedDescriptors.equals(copiedDescriptors)) {
            throw new IllegalArgumentException("emitted descriptor inventory disagrees with the generated type plan");
        }
        this.descriptors = Collections.unmodifiableMap(copiedDescriptors);
        boolean classPreview = copied.values().stream().anyMatch(JvmBytecodeArtifact::previewClassFile);
        if (previewRequired != classPreview) {
            throw new IllegalArgumentException("preview flag disagrees with emitted class-file versions");
        }
        this.previewRequired = previewRequired;
        this.emissionMode = Objects.requireNonNull(emissionMode, "emissionMode");
        if (typePlan.emissionMode() != emissionMode) {
            throw new IllegalArgumentException("emission mode disagrees with generated type plan");
        }
        this.reproducibleOptions = Map.copyOf(Objects.requireNonNull(
                reproducibleOptions, "reproducibleOptions"));
    }

    public TypedIr ir() {
        return ir;
    }

    public TypedIr typedIr() {
        return ir;
    }

    public GeneratedTypePlan typePlan() {
        return typePlan;
    }

    public GeneratedTypePlan plan() {
        return typePlan;
    }

    /** Base package used for every generated facade and support class. */
    public String javaBasePackage() {
        return typePlan.basePackage();
    }

    /** Emitted classes in the immutable deterministic plan order. */
    public Map<String, byte[]> classes() {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : classFiles.entrySet()) {
            result.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(result);
    }

    public Map<String, byte[]> classFiles() {
        return classes();
    }

    public List<String> classNames() {
        return List.copyOf(classFiles.keySet());
    }

    public Optional<byte[]> classBytes(String binaryName) {
        Objects.requireNonNull(binaryName, "binaryName");
        byte[] bytes = classFiles.get(binaryName);
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }

    public byte[] bytes(String binaryName) {
        return classBytes(binaryName).orElseThrow(() ->
                new IllegalArgumentException("emitted class is absent: " + binaryName));
    }

    /** Exact planned descriptors keyed as {@code binaryName#member+descriptor}. */
    public Map<String, String> descriptors() {
        return descriptors;
    }

    public boolean previewRequired() {
        return previewRequired;
    }

    public EmissionMode emissionMode() {
        return emissionMode;
    }

    public ArtifactProfile artifactProfile() {
        return emissionMode.artifactProfile();
    }

    public Map<String, String> reproducibleOptions() {
        return reproducibleOptions;
    }

    /** Canonical import topology retained for attachable context metadata. */
    public List<ArtifactImport> imports() {
        var graph = ir.typedSemanticGraph().resolvedGraph().moduleGraph();
        return graph.edges().stream().map(edge -> new ArtifactImport(
                runtimeModuleId(edge.from()), edge.logicalTarget().value(),
                runtimeModuleId(edge.target()), edge.importSpan().startOffset(),
                edge.importSpan().endOffset())).sorted().toList();
    }

    public int classCount() {
        return classFiles.size();
    }

    /**
     * Returns the complete export projection needed by schema-1 metadata.
     * This is deliberately a value projection rather than a public backend
     * planning SPI.
     */
    public List<EmittedExport> emittedExports() {
        ArrayList<EmittedExport> result = new ArrayList<>();
        for (GeneratedExportPlan export : typePlan.exports()) {
            // The export identity is the complete value contract.  A nullable
            // function is still a function ABI, but its top-level @nil
            // qualifier must remain part of the metadata identity.
            String signature = export.valueType().canonicalLyraType();
            String descriptor = export.functionSignature()
                    .map(JvmSignaturePlan::descriptor)
                    .orElse("()" + export.valueType().descriptor());
            String javaInvocationName = export.isFunction()
                    ? export.invocationName().orElseThrow()
                    : export.javaName();
            result.add(new EmittedExport(export.stableId(), export.moduleId(), export.sourceName(),
                    signature, descriptor, export.isMutable(), javaInvocationName,
                    export.getterName().orElse("get$" + export.javaName()),
                    export.functionValueName().orElse("value$" + export.javaName()),
                    export.setterName(), emissionMode == EmissionMode.ATTACHABLE
                            ? export.declarationId().ordinal() : -1L,
                    emissionMode == EmissionMode.ATTACHABLE
                            ? export.originDeclaration().ordinal() : -1L));
        }
        result.sort(java.util.Comparator.comparing(EmittedExport::stableId));
        return List.copyOf(result);
    }

    /** Returns generated method origins used to recover exact BCI ranges. */
    public List<EmittedMethod> emittedMethods() {
        ArrayList<EmittedMethod> result = new ArrayList<>();
        ModuleId root = ir.rootModule().moduleId();
        for (GeneratedClassPlan classPlan : typePlan.classes()) {
            ModuleId module = classPlan.moduleId().orElse(root);
            IrLambda lambda = null;
            IrDeclaration intrinsic = null;
            if (classPlan.kind() == GeneratedClassKind.CLOSURE) {
                lambda = ir.lambdas().stream()
                        .filter(candidate -> classPlan.binaryName().equals(
                                typePlan.closureClasses().get(candidate.id())))
                        .findFirst().orElse(null);
                if (lambda == null) {
                    intrinsic = ir.declarations().stream()
                            .filter(candidate -> classPlan.binaryName().equals(
                                    typePlan.intrinsicFunctionClasses().get(candidate.id())))
                            .findFirst().orElseThrow(() -> new IllegalStateException(
                                    "closure class has no lambda or intrinsic origin: "
                                            + classPlan.binaryName()));
                }
            }
            for (GeneratedMemberPlan member : classPlan.members()) {
                if (!member.isMethod()) {
                    continue;
                }
                SourceSpan span = ir.module(module).orElseThrow().span();
                String functionName = "<module>";
                boolean synthetic = true;
                if (lambda != null) {
                    span = lambda.bodySpan();
                    functionName = lambda.ownerDeclaration()
                            .flatMap(id -> ir.declarations().stream()
                                    .filter(declaration -> declaration.id().equals(id))
                                    .map(IrDeclaration::name).findFirst())
                            .orElse("<lambda>");
                    synthetic = member.kind() != GeneratedMemberKind.CLOSURE_INVOKE;
                } else if (intrinsic != null) {
                    span = intrinsic.span();
                    functionName = intrinsic.name();
                } else {
                    GeneratedExportPlan export = typePlan.exports().stream()
                            .filter(candidate -> candidate.moduleId().equals(module))
                            .filter(candidate -> candidate.members().stream().anyMatch(
                                    candidateMember -> candidateMember.kind() == member.kind()
                                            && candidateMember.name().equals(member.name())
                                            && candidateMember.descriptor().equals(member.descriptor())))
                            .findFirst().orElse(null);
                    if (export != null) {
                        span = ir.exports().stream()
                                .filter(value -> value.moduleId().equals(export.moduleId())
                                        && value.name().equals(export.sourceName()))
                                .map(IrExport::span).findFirst().orElse(span);
                        functionName = export.sourceName();
                        synthetic = false;
                    } else if (member.sourceName().isPresent()) {
                        String sourceName = member.sourceName().orElseThrow();
                        IrDeclaration declaration = ir.declarations().stream()
                                .filter(value -> value.moduleId().equals(module)
                                        && value.name().equals(sourceName))
                                .findFirst().orElse(null);
                        if (declaration != null) {
                            span = declaration.span();
                            functionName = declaration.name();
                        }
                    }
                }
                result.add(new EmittedMethod(classPlan.internalName(), member.name(),
                        member.descriptor(), module, functionName, span, synthetic));
            }
        }
        result.sort(java.util.Comparator.comparing(EmittedMethod::internalClassName)
                .thenComparing(EmittedMethod::methodName)
                .thenComparing(EmittedMethod::methodDescriptor));
        return List.copyOf(result);
    }

    private static io.mindspice.lyra.runtime.ModuleId runtimeModuleId(ModuleId module) {
        return module.isUri()
                ? io.mindspice.lyra.runtime.ModuleId.uri(module.asUri())
                : io.mindspice.lyra.runtime.ModuleId.path(module.value());
    }

    private static boolean previewClassFile(byte[] bytes) {
        return (bytes[4] & 0xff) == 0xff && (bytes[5] & 0xff) == 0xff;
    }

    private static void validateClassFile(String binaryName, byte[] bytes) {
        if (bytes.length < 10 || (bytes[0] & 0xff) != 0xca || (bytes[1] & 0xff) != 0xfe
                || (bytes[2] & 0xff) != 0xba || (bytes[3] & 0xff) != 0xbe) {
            throw new IllegalArgumentException("emitted class is not a class file: " + binaryName);
        }
        int minor = u2(bytes, 4);
        int major = u2(bytes, 6);
        if (major != 69 || (minor != 0 && minor != 65535)) {
            throw new IllegalArgumentException("emitted class has an invalid Java-25 version: "
                    + binaryName);
        }
        String expected = binaryName.replace('.', '/');
        String actual = classInternalName(bytes);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("class-file name disagrees with emitted name: "
                    + binaryName + " versus " + actual);
        }
    }

    private static int u2(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static String classInternalName(byte[] bytes) {
        ClassCursor cursor = new ClassCursor(bytes, 8);
        int count = cursor.u2();
        Object[] pool = new Object[count];
        for (int index = 1; index < count; index++) {
            int tag = cursor.u1();
            switch (tag) {
                case 1 -> pool[index] = cursor.utf8();
                case 3, 4 -> cursor.skip(4);
                case 5, 6 -> { cursor.skip(8); index++; }
                case 7, 8, 16, 19, 20 -> pool[index] = cursor.u2();
                case 9, 10, 11, 12, 17, 18 -> cursor.skip(4);
                case 15 -> cursor.skip(3);
                default -> throw new IllegalArgumentException("invalid constant-pool tag: " + tag);
            }
        }
        cursor.skip(2); // access flags
        int thisClass = cursor.u2();
        if (thisClass <= 0 || thisClass >= pool.length || !(pool[thisClass] instanceof Integer nameIndex)
                || nameIndex <= 0 || nameIndex >= pool.length || !(pool[nameIndex] instanceof String name)) {
            throw new IllegalArgumentException("class file has an invalid this_class entry");
        }
        return name;
    }

    private static final class ClassCursor {
        private final byte[] bytes;
        private int offset;

        private ClassCursor(byte[] bytes, int offset) {
            this.bytes = bytes;
            this.offset = offset;
        }

        private int u1() {
            require(1);
            return bytes[offset++] & 0xff;
        }

        private int u2() {
            require(2);
            int value = ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
            offset += 2;
            return value;
        }

        private String utf8() {
            int length = u2();
            require(length);
            byte[] encoded = new byte[length + 2];
            encoded[0] = (byte) (length >>> 8);
            encoded[1] = (byte) length;
            System.arraycopy(bytes, offset, encoded, 2, length);
            offset += length;
            try (java.io.DataInputStream input = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(encoded))) {
                return input.readUTF();
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("invalid modified UTF-8 class constant", exception);
            }
        }

        private void skip(int length) {
            require(length);
            offset += length;
        }

        private void require(int length) {
            if (length < 0 || offset > bytes.length - length) {
                throw new IllegalArgumentException("truncated class file");
            }
        }
    }
}
