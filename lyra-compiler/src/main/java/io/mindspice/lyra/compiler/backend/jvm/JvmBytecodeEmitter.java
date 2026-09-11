package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.ir.IrCapture;
import io.mindspice.lyra.compiler.ir.IrConstantValue;
import io.mindspice.lyra.compiler.ir.IrCell;
import io.mindspice.lyra.compiler.ir.IrCheckKind;
import io.mindspice.lyra.compiler.ir.IrDeclaration;
import io.mindspice.lyra.compiler.ir.IrExport;
import io.mindspice.lyra.compiler.ir.IrFailureSite;
import io.mindspice.lyra.compiler.ir.IrImportBinding;
import io.mindspice.lyra.compiler.ir.IrModule;
import io.mindspice.lyra.compiler.ir.IrNode;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.semantic.AccessKind;
import io.mindspice.lyra.compiler.semantic.DeclarationKind;
import io.mindspice.lyra.compiler.semantic.ImportBindingKind;
import io.mindspice.lyra.compiler.semantic.ReferenceKind;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.ConversionKind;
import io.mindspice.lyra.compiler.types.ConversionStep;
import io.mindspice.lyra.compiler.types.ExactNumericLiteral;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.QualifiedType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Direct Java 25 Class-File API emitter for scalar/control, aggregate,
 * function, and module semantics through Phase 16. It consumes a sealed typed
 * IR and the audited immutable Phase-14 shape plan; it does not re-run semantic
 * analysis or synthesize an ABI.
 *
 * <p>Arrays, tuples, typed interfaces and closures, shared cells, recursive
 * calls, module state/import linkage, and deterministic eager initialization
 * are emitted in the same production walker.</p>
 */
final class JvmBytecodeEmitter {
    private static final String RUNTIME = "io.mindspice.lyra.runtime.";
    private static final ClassDesc CD_OBJECT = ConstantDescs.CD_Object;
    private static final ClassDesc CD_MODULE_ID = cd(RUNTIME + "ModuleId");
    private static final ClassDesc CD_ARTIFACT_KEY = cd(RUNTIME + "LyraArtifactKey");
    private static final ClassDesc CD_OBJECTS = cd("java.util.Objects");
    private static final ClassDesc CD_STRING = ConstantDescs.CD_String;
    private static final ClassDesc CD_LIST = ConstantDescs.CD_List;
    private static final ClassDesc CD_AUTHORITY = cd(RUNTIME + "LyraClosureAuthority");
    private static final ClassDesc CD_RUNTIME_OPTIONS = cd(RUNTIME + "RuntimeOptions");
    private static final ClassDesc CD_OWNER_THREAD = cd(RUNTIME + "OwnerThread");
    private static final ClassDesc CD_CLOSURE = cd(RUNTIME + "LyraClosure");
    private static final ClassDesc CD_SIGNATURE = cd(RUNTIME + "LyraSignature");
    private static final ClassDesc CD_LIFECYCLE = cd(RUNTIME + "ModuleLifecycle");
    private static final ClassDesc CD_LIFECYCLE_STATE = cd(RUNTIME + "LifecycleState");
    private static final ClassDesc CD_UNIT = cd(RUNTIME + "LyraUnit");
    private static final ClassDesc CD_FAILURE_CATEGORY = cd(RUNTIME + "LyraFailureCategory");
    private static final ClassDesc CD_RUNTIME_EXCEPTION = cd(RUNTIME + "LyraRuntimeException");
    private static final ClassDesc CD_JAVA_RUNTIME_EXCEPTION = cd("java.lang.RuntimeException");
    private static final ClassDesc CD_STACK_OVERFLOW = cd("java.lang.StackOverflowError");
    private static final ClassDesc CD_ARTIFACT_METADATA = cd(RUNTIME + "ArtifactMetadata");
    private static final ClassDesc CD_METADATA_READER = cd(RUNTIME + "ArtifactMetadataReader");
    private static final ClassDesc CD_RUNTIME_CLOSURE_SUPPORT = cd(RUNTIME + "LyraClosureSupport");
    private static final ClassDesc CD_RUNTIME_LYRA_CLOSURE = cd(RUNTIME + "LyraClosure");
    private static final ClassDesc CD_LYRA_IO = cd(RUNTIME + "LyraIo");
    private static final ClassDesc CD_INTEGER = cd("java.lang.Integer");
    private static final ClassDesc CD_LONG = cd("java.lang.Long");
    private static final ClassDesc CD_FLOAT = cd("java.lang.Float");
    private static final ClassDesc CD_DOUBLE = cd("java.lang.Double");
    private static final ClassDesc CD_BOOLEAN = cd("java.lang.Boolean");
    private static final ClassDesc CD_BYTE = cd("java.lang.Byte");
    private static final ClassDesc CD_SHORT = cd("java.lang.Short");
    private static final ClassDesc CD_CHARACTER = cd("java.lang.Character");
    private static final ClassDesc CD_LOCALE = cd("java.util.Locale");
    /*
     * A facade still exposes its metadata without depending on the artifact
     * container.  CONSTANT_Utf8 is limited to 65,535 modified-UTF-8 bytes,
     * while debug metadata may legitimately grow with graph/options size.
     * Reserve a fixed, marker-addressable chunk inventory so ArtifactAssembly
     * can replace the provisional text with a packaging-specific final value.
     */
    private static final String METADATA_CHUNK_PREFIX = "\u0001LYRA-METADATA-CHUNK-";
    private static final String METADATA_CHUNK_SUFFIX = "\u0001";
    private static final int METADATA_CHUNK_COUNT = 32;
    private static final int METADATA_CHUNK_BYTES = 60_000;

    private JvmBytecodeEmitter() {
    }

    /** Emits all supported classes in the supplied deterministic plan order. */
    static JvmBytecodeArtifact emit(TypedIr ir, GeneratedTypePlan plan) {
        return emit(ir, plan, false);
    }

    static JvmBytecodeArtifact emit(TypedIr ir, GeneratedTypePlan plan,
                                    boolean chunkedMetadata) {
        TypedIr validated = Objects.requireNonNull(ir, "ir").requireValidated();
        Objects.requireNonNull(plan, "plan");
        try {
            JvmAbiParity.require(validated, plan);
        } catch (IllegalArgumentException failure) {
            throw new JvmEmissionException(validated.rootModule().span(),
                    "invalid JVM type plan: " + failure.getMessage(), true, failure);
        }
        Emitter emitter = new Emitter(validated, plan, chunkedMetadata);
        emitter.validateSupportedInput();
        return emitter.emit();
    }

    /** Structured phase boundary for expected emission failures. */
    static PhaseResult<JvmBytecodeArtifact> emitPhase(TypedIr ir, GeneratedTypePlan plan) {
        return emitPhase(ir, plan, false);
    }

    static PhaseResult<JvmBytecodeArtifact> emitPhase(TypedIr ir, GeneratedTypePlan plan,
                                                      boolean chunkedMetadata) {
        Objects.requireNonNull(ir, "ir").requireValidated();
        try {
            return PhaseResult.success(emit(ir, plan, chunkedMetadata));
        } catch (JvmEmissionException failure) {
            return PhaseResult.failure(Diagnostic.error(
                    failure.invalidPlan()
                            ? CompilerDiagnosticCodes.EMIT_INVALID_PLAN
                            : CompilerDiagnosticCodes.EMIT_UNSUPPORTED_FEATURE,
                    failure.span(), failure.getMessage()));
        }
    }

    static JvmBytecodeArtifact generate(TypedIr ir, GeneratedTypePlan plan) {
        return emit(ir, plan);
    }

    private static ClassDesc cd(String binaryName) {
        return ClassDesc.of(binaryName);
    }

    private static MethodTypeDesc method(String descriptor) {
        String normalized = descriptor.replace("Lio.mindspice.lyra.runtime.",
                "Lio/mindspice/lyra/runtime/");
        return MethodTypeDesc.ofDescriptor(normalized);
    }

    private static ClassDesc type(String descriptor) {
        return ClassDesc.ofDescriptor(descriptor);
    }

    private static TypeKind typeKind(String descriptor) {
        return switch (descriptor.charAt(0)) {
            case 'V' -> TypeKind.VOID;
            case 'B' -> TypeKind.BYTE;
            case 'S' -> TypeKind.SHORT;
            case 'I' -> TypeKind.INT;
            case 'J' -> TypeKind.LONG;
            case 'F' -> TypeKind.FLOAT;
            case 'D' -> TypeKind.DOUBLE;
            case 'Z' -> TypeKind.BOOLEAN;
            case 'C' -> TypeKind.CHAR;
            case 'L', '[' -> TypeKind.REFERENCE;
            default -> throw new IllegalArgumentException("unknown JVM descriptor: " + descriptor);
        };
    }

    private static boolean isCategory2(String descriptor) {
        return descriptor.equals("J") || descriptor.equals("D");
    }

    private static String descriptorOf(GeneratedClassPlan plan) {
        return "L" + plan.internalName() + ";";
    }

    private static String descriptorOf(GeneratedMemberPlan member) {
        return member.descriptor();
    }

    private static JvmTypePlan mapped(JvmAbiMapper mapper, LyraType type,
                                      JvmMappingContext context) {
        return mapper.map(type, context);
    }

    private static PrimitiveType primitiveBase(LyraType type) {
        LyraType base = type.withoutQualifiers();
        if (!(base instanceof PrimitiveType primitive)) {
            throw new IllegalArgumentException("expected scalar primitive, got " + type);
        }
        return primitive;
    }

    private static FunctionType functionBase(LyraType type) {
        LyraType base = type.withoutQualifiers();
        if (!(base instanceof FunctionType function)) {
            throw new IllegalArgumentException("expected function type, got " + type);
        }
        return function;
    }

    private static LyraType withoutNil(LyraType type) {
        if (!type.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.NIL)) {
            return type;
        }
        Set<io.mindspice.lyra.compiler.types.TypeQualifier> qualifiers = new HashSet<>();
        if (type.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.MUT)) {
            qualifiers.add(io.mindspice.lyra.compiler.types.TypeQualifier.MUT);
        }
        return type.withoutQualifiers().withQualifiers(qualifiers);
    }

    private static LyraType withoutMutable(LyraType type) {
        if (!type.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.MUT)) {
            return type;
        }
        Set<io.mindspice.lyra.compiler.types.TypeQualifier> qualifiers = new HashSet<>();
        if (type.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.NIL)) {
            qualifiers.add(io.mindspice.lyra.compiler.types.TypeQualifier.NIL);
        }
        return type.withoutQualifiers().withQualifiers(qualifiers);
    }

    private static LyraType unqualified(LyraType type) {
        return type.withoutQualifiers();
    }

    private static final class Emitter {
        private final TypedIr ir;
        private final GeneratedTypePlan plan;
        private final JvmAbiMapper mapper;
        private final boolean chunkedMetadata;
        private final Map<DeclarationId, IrDeclaration> declarations;
        private final Map<LambdaId, io.mindspice.lyra.compiler.ir.IrLambda> lambdas;
        private final Map<CaptureId, IrCapture> captures;
        private final Map<ModuleId, IrModule> modules;
        private final Map<DeclarationId, IrCell> cells;
        private final Map<String, IrDeclaration> intrinsicFunctionsByClass;
        private final Map<DeclarationId, io.mindspice.lyra.compiler.ir.IrImportBinding> imports;
        private final Map<FlowSiteId, IrFailureSite> failureSites;
        private final Map<String, byte[]> bytes = new LinkedHashMap<>();
        private final Map<String, String> descriptors = new LinkedHashMap<>();

        private Emitter(TypedIr ir, GeneratedTypePlan plan, boolean chunkedMetadata) {
            this.ir = ir;
            this.plan = plan;
            this.mapper = plan.mapper();
            this.chunkedMetadata = chunkedMetadata;
            this.declarations = ir.declarations().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            IrDeclaration::id, value -> value));
            this.lambdas = ir.lambdas().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            io.mindspice.lyra.compiler.ir.IrLambda::id, value -> value));
            this.captures = ir.captures().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            IrCapture::id, value -> value));
            this.modules = ir.modules().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            IrModule::moduleId, value -> value));
            this.cells = ir.cells().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            IrCell::declarationId, value -> value));
            this.intrinsicFunctionsByClass = ir.declarations().stream()
                    .filter(value -> value.kind() == DeclarationKind.INTRINSIC_EXPORT)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            value -> plan.intrinsicFunctionClasses().get(value.id()), value -> value));
            this.imports = ir.imports().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            io.mindspice.lyra.compiler.ir.IrImportBinding::declarationId, value -> value));
            this.failureSites = ir.failureSites().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            IrFailureSite::siteId, value -> value));
        }

        private IrDeclaration intrinsicFunction(String binaryName) {
            return intrinsicFunctionsByClass.get(binaryName);
        }

        private boolean usesLocalFunctionSlot(IrCapture capture) {
            IrDeclaration declaration = declarations.get(capture.declarationId());
            IrModule declarationModule = declaration == null
                    ? null : modules.get(declaration.moduleId());
            return !capture.isSharedCell()
                    && declaration != null
                    && declarationModule != null
                    && !declaration.scopeId().equals(declarationModule.rootScope())
                    && declaration.signaturePredeclared()
                    && declaration.initializerLambda().isPresent()
                    && declaration.contract().map(BindingContract::valueType)
                    .map(LyraType::withoutQualifiers)
                    .filter(FunctionType.class::isInstance)
                    .isPresent();
        }

        private void validateSupportedInput() {
            if (ir.modules().isEmpty()) {
                throw unsupported(rootSpan(), "bytecode emission needs at least one module");
            }
            if (ir.initializationPlan().hasCycles()
                    || !ir.flowMetadata().eagerCycles().isEmpty()) {
                throw invalidPlan(rootSpan(),
                        "validated IR contains an eager initialization cycle");
            }
            for (var lambda : ir.lambdas()) {
                if (!modules.containsKey(lambda.moduleId())) {
                    throw invalidPlan(lambda.span(),
                            "lambda module is absent from the generated module inventory");
                }
            }
            for (IrNode node : io.mindspice.lyra.compiler.ir.IrTraversal.preOrder(ir)) {
                requireSupportedType(node.type(), node.span());
                if (node instanceof IrNode.Constant constant
                        && constant.value() instanceof IrConstantValue.StringValue string
                        && modifiedUtf8Length(string.value()) > 65_535) {
                    throw unsupported(node.span(),
                            "string literal exceeds the JVM CONSTANT_Utf8 limit of 65535 modified-UTF-8 bytes");
                }
                if (node instanceof IrNode.Access access
                        && access.accessKind() == AccessKind.MEMBER_VALUE
                        && access.memberName().filter(value -> !value.equals("length"))
                        .isPresent()
                        && access.tupleIndex().isEmpty()) {
                    throw unsupported(node.span(), "unsupported scalar member access");
                }
                if (node instanceof IrNode.DirectCall call && call.receiver().isPresent()) {
                    throw unsupported(node.span(),
                            "receiver-bound direct calls are unavailable in the current language core");
                }
            }
            for (IrDeclaration declaration : ir.declarations()) {
                declaration.contract().ifPresent(contract ->
                        requireSupportedType(contract.valueType(), declaration.span()));
            }
        }

        private static int modifiedUtf8Length(String value) {
            int length = 0;
            for (int index = 0; index < value.length(); index++) {
                char codeUnit = value.charAt(index);
                int encoded = codeUnit >= 0x0001 && codeUnit <= 0x007f
                        ? 1 : codeUnit <= 0x07ff ? 2 : 3;
                if (length > 65_535 - encoded) {
                    return 65_536;
                }
                length += encoded;
            }
            return length;
        }

        private void requireSupportedType(LyraType type, SourceSpan span) {
            LyraType base = type.withoutQualifiers();
            if (base instanceof PrimitiveType || base instanceof io.mindspice.lyra.compiler.types.RangeType) {
                return;
            }
            if (base instanceof ArrayType array) {
                requireSupportedType(array.elementType(), span);
                return;
            }
            if (base instanceof TupleType tuple) {
                tuple.memberTypes().forEach(member -> requireSupportedType(member, span));
                return;
            }
            if (base instanceof FunctionType function) {
                function.parameterTypes().forEach(parameter -> requireSupportedType(parameter, span));
                requireSupportedType(function.returnType(), span);
                return;
            }
            throw unsupported(span, "unsupported Lyra type in bytecode emission: " + type);
        }

        private JvmBytecodeArtifact emit() {
            for (GeneratedClassPlan classPlan : plan.classes()) {
                byte[] generated = emitClass(classPlan);
                bytes.put(classPlan.binaryName(), generated);
                for (GeneratedMemberPlan member : classPlan.members()) {
                    descriptors.put(classPlan.binaryName() + "#" + member.declarationKey(),
                            member.descriptor());
                }
            }
            return new JvmBytecodeArtifact(ir, plan, bytes, descriptors, false);
        }

        private byte[] emitClass(GeneratedClassPlan classPlan) {
            ClassDesc thisClass = cd(classPlan.binaryName());
            return ClassFile.of().build(thisClass, classBuilder -> {
                classBuilder.withVersion(ClassFile.JAVA_25_VERSION, 0);
                classBuilder.withFlags(classFlags(classPlan));
                classBuilder.withSuperclass(superclass(classPlan));
                if (!classPlan.interfaces().isEmpty()) {
                    classBuilder.withInterfaceSymbols(classPlan.interfaces().stream()
                            .map(JvmBytecodeEmitter::cd).toList());
                }
                classBuilder.with(SourceFileAttribute.of(sourceFile(classPlan)));
                if (!classPlan.annotations().isEmpty()) {
                    classBuilder.with(RuntimeVisibleAnnotationsAttribute.of(
                            classPlan.annotations().stream()
                                    .map(name -> Annotation.of(cd(name)))
                                    .toList()));
                }
                for (GeneratedMemberPlan member : classPlan.members()) {
                    if (member.isField()) {
                        classBuilder.withField(member.name(), type(member.descriptor()),
                                fieldFlags(member));
                    }
                }
                for (GeneratedMemberPlan member : classPlan.members()) {
                    if (!member.isMethod()) {
                        continue;
                    }
                    if (classPlan.kind() == GeneratedClassKind.FUNCTION_INTERFACE) {
                        classBuilder.withMethod(member.name(), method(member.descriptor()),
                                methodFlags(member) | ClassFile.ACC_ABSTRACT,
                                ignored -> { });
                    } else {
                        classBuilder.withMethod(member.name(), method(member.descriptor()),
                                methodFlags(member), methodBuilder -> {
                                    if (member.kind() == GeneratedMemberKind.FACADE_SESSION_RESULT_GET) {
                                        String contract = ir.module(classPlan.moduleId().orElseThrow())
                                                .orElseThrow().submissionResult().orElseThrow().type().canonicalSpelling();
                                        methodBuilder.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(
                                                cd(RUNTIME + "LyraSubmissionResult"),
                                                java.lang.classfile.AnnotationElement.ofString("value", contract))));
                                    }
                                    if (member.kind() == GeneratedMemberKind.SESSION_BINDING_GET) {
                                        long id = Long.parseLong(member.name().substring(member.name().lastIndexOf('$') + 1));
                                        IrDeclaration declaration = declarations.get(new DeclarationId(id));
                                        methodBuilder.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(
                                                cd(RUNTIME + "LyraSessionBinding"),
                                                java.lang.classfile.AnnotationElement.ofLong("id", id),
                                                java.lang.classfile.AnnotationElement.ofLong("storageIdentity", declaration.isMutable() ? id : -1L),
                                                java.lang.classfile.AnnotationElement.ofString("name", declaration.name()),
                                                java.lang.classfile.AnnotationElement.ofString("type", declaration.contract().orElseThrow().valueType().canonicalSpelling()),
                                                java.lang.classfile.AnnotationElement.ofBoolean("mutable", declaration.isMutable()))));
                                    }
                                    methodBuilder.withCode(code -> emitMethod(classPlan, member, code));
                                });
                    }
                }
            });
        }

        private int classFlags(GeneratedClassPlan classPlan) {
            if (classPlan.kind() == GeneratedClassKind.FUNCTION_INTERFACE) {
                return ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT;
            }
            int flags = ClassFile.ACC_SUPER;
            if (classPlan.isPublic()) {
                flags |= ClassFile.ACC_PUBLIC;
            }
            if (classPlan.isFinal()) {
                flags |= ClassFile.ACC_FINAL;
            }
            return flags;
        }

        private ClassDesc superclass(GeneratedClassPlan classPlan) {
            return switch (classPlan.kind()) {
                case CLOSURE -> CD_CLOSURE;
                default -> CD_OBJECT;
            };
        }

        private int fieldFlags(GeneratedMemberPlan member) {
            int flags = member.isPrivate() ? ClassFile.ACC_PRIVATE
                    : member.isPublic() ? ClassFile.ACC_PUBLIC : 0;
            if (member.isFinal()) {
                flags |= ClassFile.ACC_FINAL;
            }
            return flags;
        }

        private int methodFlags(GeneratedMemberPlan member) {
            int flags = member.isPrivate() ? ClassFile.ACC_PRIVATE
                    : member.isPublic() ? ClassFile.ACC_PUBLIC : 0;
            if (member.isStatic()) {
                flags |= ClassFile.ACC_STATIC;
            }
            return flags;
        }

        private boolean sharedSessionType(GeneratedClassPlan classPlan) {
            boolean structural = classPlan.kind() == GeneratedClassKind.TUPLE_VALUE
                    || classPlan.kind() == GeneratedClassKind.FUNCTION_INTERFACE;
            return structural && (ir.rootModule().submissionResult().isPresent()
                    || plan.emissionMode() == EmissionMode.ATTACHABLE);
        }

        private String sourceFile(GeneratedClassPlan classPlan) {
            // Structural types contain no source operations. Identical session
            // contracts must produce identical bytes independent of submission.
            if (sharedSessionType(classPlan)) return "$lyra$session-types";
            ModuleId module = classPlan.moduleId().orElse(rootModuleId());
            return ir.sourceSnapshot(module).map(value -> value.sourceId().value())
                    .orElse(module.value());
        }

        private SourceSpan rootSpan() {
            return ir.rootModule().span();
        }

        private ModuleId rootModuleId() {
            return ir.rootModule().moduleId();
        }

        private void emitMethod(GeneratedClassPlan classPlan,
                                GeneratedMemberPlan member,
                                CodeBuilder code) {
            MethodEmitter methodEmitter = new MethodEmitter(this, classPlan, member, code);
            methodEmitter.emit();
        }
    }

    private static final class MethodEmitter {
        private final Emitter owner;
        private final GeneratedClassPlan classPlan;
        private final GeneratedMemberPlan member;
        private final CodeBuilder code;
        private final Map<DeclarationId, BindingStorage> locals = new HashMap<>();
        /** Local declarations whose mutable identity is represented by a shared cell. */
        private final Map<DeclarationId, Integer> localCells = new HashMap<>();
        /** One-element typed arrays link immutable local function declarations. */
        private final Map<DeclarationId, Integer> localFunctionSlots = new HashMap<>();
        /** Function declaration forms available for on-demand forward linking. */
        private final Map<DeclarationId, IrNode.Declaration> localFunctionForms = new HashMap<>();
        /** Local function closures already materialized on the current code path. */
        private final Set<DeclarationId> initializedLocalFunctions = new HashSet<>();
        private final Set<DeclarationId> initializingLocalFunctions = new HashSet<>();
        private final Map<DeclarationId, IrDeclaration> declarations;
        private final Map<CaptureId, IrCapture> captures;
        private final IrModule module;
        private final io.mindspice.lyra.compiler.ir.IrLambda lambda;
        private final IrDeclaration intrinsicFunction;
        private final boolean stateMethod;
        private final boolean closureMethod;
        private Label loopLabel;
        private final List<FailureHandler> failureHandlers = new ArrayList<>();
        private final List<CallFailureHandler> callFailureHandlers = new ArrayList<>();
        private final Deque<IrFailureSite> activeFailureSites = new ArrayDeque<>();
        private final Set<DeclarationId> initializedRootDeclarations = new HashSet<>();
        private final Set<DeclarationId> initializingRootDeclarations = new HashSet<>();

        private MethodEmitter(Emitter owner, GeneratedClassPlan classPlan,
                              GeneratedMemberPlan member, CodeBuilder code) {
            this.owner = owner;
            this.classPlan = classPlan;
            this.member = member;
            this.code = code;
            this.declarations = owner.declarations;
            this.captures = owner.captures;
            ModuleId moduleId = classPlan.moduleId().orElse(owner.rootModuleId());
            this.module = owner.modules.get(moduleId);
            this.lambda = classPlan.kind() == GeneratedClassKind.CLOSURE
                    ? owner.lambdas.values().stream()
                    .filter(value -> owner.plan.closureClasses().get(value.id())
                            .equals(classPlan.binaryName())).findFirst().orElse(null)
                    : null;
            this.intrinsicFunction = classPlan.kind() == GeneratedClassKind.CLOSURE
                    ? owner.intrinsicFunction(classPlan.binaryName()) : null;
            this.stateMethod = classPlan.kind() == GeneratedClassKind.MODULE_STATE;
            this.closureMethod = classPlan.kind() == GeneratedClassKind.CLOSURE;
        }

        private void emit() {
            if (!owner.sharedSessionType(classPlan)) line(memberSpan());
            switch (classPlan.kind()) {
                case TUPLE_VALUE -> emitTupleMethod();
                case CELL -> emitCellMethod();
                case CLOSURE -> emitClosureMethod();
                case MODULE_STATE -> emitStateMethod();
                case MODULE_FACADE -> emitFacadeMethod();
                case FUNCTION_INTERFACE ->
                        throw invalidPlan(memberSpan(), "method cannot be emitted for " + classPlan.kind());
            }
            emitFailureHandlers();
            emitCallFailureHandlers();
        }

        private void emitCallFailureHandlers() {
            for (CallFailureHandler handler : callFailureHandlers) {
                code.exceptionCatch(handler.start(), handler.end(), handler.handler(),
                        CD_RUNTIME_EXCEPTION);
                code.labelBinding(handler.handler());
                emitSourceFrame(handler.span());
                code.iconst_0();
                code.invokeinterface(CD_LIST, "get", method("(I)Ljava/lang/Object;"));
                code.checkcast(cd(RUNTIME + "SourceFrame"));
                code.invokevirtual(CD_RUNTIME_EXCEPTION, "withFrame", method(
                        "(L" + RUNTIME + "SourceFrame;)L" + RUNTIME + "LyraRuntimeException;"));
                code.athrow();

                // A recursive ordinary call is the one generated boundary
                // that translates StackOverflowError.  Other VM errors are
                // deliberately not caught by generated code.
                code.exceptionCatch(handler.start(), handler.end(), handler.stackHandler(),
                        CD_STACK_OVERFLOW);
                code.labelBinding(handler.stackHandler());
                int cause = code.allocateLocal(TypeKind.REFERENCE);
                code.astore(cause);
                code.ldc("LYR-STACK");
                code.invokestatic(CD_FAILURE_CATEGORY, "fromCode",
                        method("(Ljava/lang/String;)L" + RUNTIME + "LyraFailureCategory;"));
                code.ldc("Lyra call stack overflow");
                code.aload(cause);
                code.invokestatic(CD_RUNTIME_EXCEPTION, "of", method(
                        "(L" + RUNTIME + "LyraFailureCategory;Ljava/lang/String;Ljava/lang/Throwable;)L"
                                + RUNTIME + "LyraRuntimeException;"));
                emitSourceFrame(handler.span());
                code.iconst_0();
                code.invokeinterface(CD_LIST, "get", method("(I)Ljava/lang/Object;"));
                code.checkcast(cd(RUNTIME + "SourceFrame"));
                code.invokevirtual(CD_RUNTIME_EXCEPTION, "withFrame", method(
                        "(L" + RUNTIME + "SourceFrame;)L" + RUNTIME + "LyraRuntimeException;"));
                code.athrow();
            }
        }

        /**
         * Conditional branches may refer to a failure site before the normal
         * path has finished emitting.  Bind those labels only after the
         * method's normal terminal instruction, so a successful operation can
         * never fall through into its failure body.
         */
        private void emitFailureHandlers() {
            for (FailureHandler handler : failureHandlers) {
                code.labelBinding(handler.label());
                if (member.kind() == GeneratedMemberKind.STATE_CHECK_OPEN) {
                    throwStateInitializationFailure(handler.span(), handler.code(), handler.summary());
                } else {
                    throwFailureRecorded(handler.span(), handler.code(), handler.summary());
                }
            }
        }

        /**
         * A failure label is outside the ordinary initialization try range,
         * because handlers must not catch their own throw.  State-only labels
         * therefore perform the same terminal FAILED transition and LYR-INIT
         * wrapping explicitly instead of relying on that range.
         */
        private void throwStateInitializationFailure(SourceSpan span, String codeValue,
                                                      String summary) {
            line(span);
            code.ldc(codeValue);
            code.invokestatic(CD_FAILURE_CATEGORY, "fromCode",
                    method("(Ljava/lang/String;)L" + RUNTIME + "LyraFailureCategory;"));
            code.ldc(summary);
            emitSourceFrame(span);
            code.invokestatic(CD_RUNTIME_EXCEPTION, "of", method(
                    "(L" + RUNTIME + "LyraFailureCategory;Ljava/lang/String;Ljava/util/List;)L"
                            + RUNTIME + "LyraRuntimeException;"));
            int cause = code.allocateLocal(TypeKind.REFERENCE);
            code.astore(cause);
            loadStateLifecycle();
            code.aload(cause);
            code.invokevirtual(CD_LIFECYCLE, "fail", method("(Ljava/lang/Throwable;)V"));
            code.ldc("LYR-INIT");
            code.invokestatic(CD_FAILURE_CATEGORY, "fromCode",
                    method("(Ljava/lang/String;)L" + RUNTIME + "LyraFailureCategory;"));
            code.ldc("module initialization failed");
            code.aload(cause);
            code.invokestatic(CD_RUNTIME_EXCEPTION, "of", method(
                    "(L" + RUNTIME + "LyraFailureCategory;Ljava/lang/String;Ljava/lang/Throwable;)L"
                            + RUNTIME + "LyraRuntimeException;"));
            emitSourceFrame(module.span());
            code.iconst_0();
            code.invokeinterface(CD_LIST, "get", method("(I)Ljava/lang/Object;"));
            code.checkcast(cd(RUNTIME + "SourceFrame"));
            code.invokevirtual(CD_RUNTIME_EXCEPTION, "withFrame", method(
                    "(L" + RUNTIME + "SourceFrame;)L" + RUNTIME + "LyraRuntimeException;"));
            code.athrow();
        }

        private SourceSpan memberSpan() {
            if (lambda != null) {
                return lambda.span();
            }
            return module.span();
        }

        private void emitTupleMethod() {
            switch (member.kind()) {
                case TUPLE_CONSTRUCTOR -> emitTupleConstructor();
                case TUPLE_COMPONENT_GET, SESSION_TUPLE_COMPONENT_GET -> emitTupleComponentGetter();
                default -> throw invalidPlan(memberSpan(), "unexpected tuple method: " + member.kind());
            }
        }

        private void emitTupleConstructor() {
            emitObjectSuperConstructor();
            List<GeneratedMemberPlan> fields = classPlan.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.TUPLE_FIELD)
                    .sorted(Comparator.comparingInt(GeneratedMemberPlan::componentIndex))
                    .toList();
            for (int index = 0; index < fields.size(); index++) {
                aloadReceiver();
                loadParameter(index);
                code.putfield(cd(classPlan.binaryName()), fields.get(index).name(),
                        type(fields.get(index).descriptor()));
            }
            code.return_();
        }

        private void emitTupleComponentGetter() {
            int index;
            try {
                index = Integer.parseInt(member.name().substring("$lyra$get$".length()));
            } catch (RuntimeException failure) {
                throw invalidPlan(memberSpan(), "tuple accessor has no component index");
            }
            GeneratedMemberPlan field = classPlan.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.TUPLE_FIELD)
                    .filter(value -> value.componentIndex() == index)
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                            "tuple accessor has no matching field"));
            aloadReceiver();
            code.getfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
            returnPhysicalDescriptor(member.descriptor().substring(member.descriptor().indexOf(')') + 1));
        }

        private void emitCellMethod() {
            switch (member.kind()) {
                case CELL_CONSTRUCTOR -> emitCellConstructor();
                case CELL_GET, CELL_PAYLOAD_GET -> emitCellGetter(member);
                case CELL_PRESENCE_GET -> emitCellPresenceGetter();
                case CELL_SET -> emitCellSetter();
                default -> throw invalidPlan(memberSpan(), "unexpected cell method: " + member.kind());
            }
        }

        private void emitCellConstructor() {
            emitObjectSuperConstructor();
            List<GeneratedMemberPlan> fields = fieldsForCell();
            int parameter = 0;
            for (GeneratedMemberPlan field : fields) {
                aloadReceiver();
                loadParameter(parameter++);
                code.putfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
            }
            code.return_();
        }

        private List<GeneratedMemberPlan> fieldsForCell() {
            return classPlan.members().stream().filter(GeneratedMemberPlan::isField).toList();
        }

        private void emitCellGetter(GeneratedMemberPlan ignored) {
            GeneratedMemberPlan field = classPlan.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.CELL_VALUE_FIELD)
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(), "cell has no value field"));
            aloadReceiver();
            code.getfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
            returnPhysicalDescriptor(member.descriptor().substring(member.descriptor().indexOf(')') + 1));
        }

        private void emitCellPresenceGetter() {
            GeneratedMemberPlan field = classPlan.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.CELL_PRESENCE_FIELD)
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(), "split cell has no presence field"));
            aloadReceiver();
            code.getfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
            code.ireturn();
        }

        private void emitCellSetter() {
            emitObjectReceiverAndParametersToFields(classPlan.members().stream()
                    .filter(GeneratedMemberPlan::isField).toList());
            code.return_();
        }

        private void emitObjectReceiverAndParametersToFields(List<GeneratedMemberPlan> fields) {
            int parameter = 0;
            for (GeneratedMemberPlan field : fields) {
                aloadReceiver();
                loadParameter(parameter++);
                code.putfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
            }
        }

        private void emitClosureMethod() {
            switch (member.kind()) {
                case CLOSURE_CONSTRUCTOR -> emitClosureConstructor();
                case CLOSURE_INVOKE -> emitClosureInvoke();
                default -> throw invalidPlan(memberSpan(), "unexpected closure method: " + member.kind());
            }
        }

        private void emitClosureConstructor() {
            emitObjectSuperClosureConstructor();
            List<GeneratedMemberPlan> fields = classPlan.members().stream()
                    .filter(GeneratedMemberPlan::isField).toList();
            int parameter = 0;
            for (GeneratedMemberPlan field : fields) {
                aloadReceiver();
                loadParameter(parameter++);
                code.putfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
            }
            code.return_();
        }

        private void emitObjectSuperClosureConstructor() {
            aloadReceiver();
            loadParameter(0);
            String signature = intrinsicFunction != null
                    ? functionBase(intrinsicFunction.contract().orElseThrow().valueType())
                    .signature().canonicalSpelling()
                    : lambda.signature().canonicalSpelling();
            code.ldc(signature);
            code.invokestatic(CD_SIGNATURE, "parse",
                    method("(Ljava/lang/String;)L" + RUNTIME + "LyraSignature;"));
            code.invokespecial(CD_CLOSURE, "<init>",
                    method("(L" + RUNTIME + "LyraClosureAuthority;L" + RUNTIME
                            + "LyraSignature;)V"));
        }

        private void emitClosureInvoke() {
            if (intrinsicFunction != null) {
                emitIntrinsicClosureInvoke();
                return;
            }
            if (lambda == null || lambda.signature() == null) {
                throw invalidPlan(memberSpan(), "closure invocation has no lambda metadata");
            }
            // The lifecycle/owner check is performed once at the generated
            // invocation boundary.  A self-tail loop then reuses the checked
            // frame without growing the JVM stack.
            aloadReceiver();
            code.invokevirtual(CD_CLOSURE, "checkInvocationFromGeneratedCode", method("()V"));
            loopLabel = code.newLabel();
            code.labelBinding(loopLabel);
            // Function boundary and direct self-tail-loop backedge.  An idle
            // initialized application consumes at most one pending request
            // here; an active evaluation only checks its own token.
            emitOwnerSafePoint();
            line(lambda.bodySpan());
            installLambdaParameters();
            emitTail(lambda.body());
        }

        private void emitIntrinsicClosureInvoke() {
            FunctionType function = functionBase(intrinsicFunction.contract().orElseThrow().valueType());
            JvmSignaturePlan signature = owner.mapper.mapSignature(
                    function.signature(), JvmAbiBoundary.JAVA_VISIBLE);
            aloadReceiver();
            code.invokevirtual(CD_CLOSURE, "checkInvocationFromGeneratedCode", method("()V"));
            line(memberSpan());
            emitCurrentAuthority();
            for (int index = 0; index < function.arity(); index++) {
                loadParameter(index);
            }
            recordCallFailureFrame(memberSpan(), () -> code.invokestatic(
                    CD_LYRA_IO, intrinsicFunction.name(), method(intrinsicDescriptor(signature))));
            returnPhysicalDescriptor(signature.returnValue().descriptor());
        }

        private void installLambdaParameters() {
            locals.clear();
            localCells.clear();
            for (int index = 0; index < lambda.parameterIds().size(); index++) {
                DeclarationId id = lambda.parameterIds().get(index);
                LyraType logical = lambda.signature().parameterType(index);
                JvmTypePlan physical = owner.mapper.map(logical, JvmMappingContext.JAVA_PARAMETER);
                boolean authenticated = authenticateFunctionParameter(index, logical, physical);
                int slot = code.parameterSlot(index);
                if (authenticated) {
                    code.storeLocal(typeKind(physical.descriptor()), slot);
                }
                IrDeclaration declaration = declarations.get(id);
                if (declaration != null && owner.cells.containsKey(id)) {
                    JvmTypePlan storage = owner.mapper.mapBinding(
                            declaration.contract().orElseThrow()).value();
                    List<Integer> values = allocateLocals(storage);
                    loadPhysical(physical.physicalComponents().getFirst(), slot);
                    // Java-visible nullable primitives arrive boxed, while a
                    // mutable parameter's shared cell uses the internal
                    // presence/payload representation.  Adapt the complete
                    // value plan rather than only its first component.
                    adapt(physical, storage);
                    storeLocal(storage, values);
                    emitNewCell(id, values);
                    int cell = allocateLocal(JvmType.reference(cellClassName(id)));
                    code.astore(cell);
                    localCells.put(id, cell);
                } else {
                    locals.put(id, BindingStorage.local(logical, physical, List.of(slot)));
                }
            }
        }

        private boolean authenticateFunctionParameter(int index, LyraType logical,
                                                      JvmTypePlan physical) {
            if (logical.withoutQualifiers() instanceof io.mindspice.lyra.compiler.types.RangeType) {
                loadParameter(index);
                authenticateRange(logical);
                return true;
            }
            if (!(logical.withoutQualifiers() instanceof FunctionType function)) {
                return false;
            }
            if (!physical.isSingleValue() || !physical.physicalComponents().getFirst().isReference()) {
                throw invalidPlan(memberSpan(), "function parameter has no reference representation");
            }
            String signature = function.signature().canonicalSpelling();
            String functionName = owner.mapper.names().functionBinaryName(signature);
            if (logical.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.NIL)) {
                loadParameter(index);
                code.dup();
                Label nil = code.newLabel();
                Label authenticated = code.newLabel();
                code.ifnull(nil);
                emitCurrentAuthority();
                code.ldc(signature);
                code.invokestatic(CD_SIGNATURE, "parse",
                        method("(Ljava/lang/String;)L" + RUNTIME + "LyraSignature;"));
                code.invokestatic(CD_RUNTIME_CLOSURE_SUPPORT,
                        "requireAuthenticatedForGeneratedInvocation",
                        method("(Ljava/lang/Object;L" + RUNTIME + "LyraClosureAuthority;L"
                                + RUNTIME + "LyraSignature;)L" + RUNTIME + "LyraClosure;"));
                code.checkcast(cd(functionName));
                code.goto_(authenticated);
                code.labelBinding(nil);
                code.labelBinding(authenticated);
            } else {
                loadParameter(index);
                emitCurrentAuthority();
                code.ldc(signature);
                code.invokestatic(CD_SIGNATURE, "parse",
                        method("(Ljava/lang/String;)L" + RUNTIME + "LyraSignature;"));
                code.invokestatic(CD_RUNTIME_CLOSURE_SUPPORT,
                        "requireAuthenticatedForGeneratedInvocation",
                        method("(Ljava/lang/Object;L" + RUNTIME + "LyraClosureAuthority;L"
                                + RUNTIME + "LyraSignature;)L" + RUNTIME + "LyraClosure;"));
                code.checkcast(cd(functionName));
            }
            return true;
        }

        private void emitStateMethod() {
            switch (member.kind()) {
                case SESSION_SAFE_POINT -> {
                    loadStateLifecycle();
                    code.invokevirtual(CD_LIFECYCLE, "sessionSafePoint", method("()V"));
                    code.return_();
                }
                case ATTACHMENT_SAFE_POINT -> {
                    loadStateLifecycle();
                    code.invokevirtual(CD_LIFECYCLE, "applicationSafePoint", method("()V"));
                    code.return_();
                }
                case STATE_ATTACHMENT_LIFECYCLE -> {
                    loadStateLifecycle();
                    code.areturn();
                }
                case STATE_SESSION_ACCESSOR -> {
                    loadStateLifecycle();
                    loadParameter(0); loadParameter(1); loadParameter(2); loadParameter(3);
                    code.invokevirtual(CD_LIFECYCLE, "sessionAccessor", method(member.descriptor()));
                    code.areturn();
                }
                case STATE_MODULE_STATE_LOOKUP -> {
                    loadStateLifecycle();
                    loadParameter(0);
                    code.invokevirtual(CD_LIFECYCLE, "moduleState", method(member.descriptor()));
                    code.areturn();
                }
                case SESSION_EXECUTE -> emitSessionExecute();
                case SESSION_RESULT_GET -> emitSessionResultGetter();
                case STATE_CONSTRUCTOR -> emitStateConstructor();
                case STATE_COMPONENT_GET -> emitStateGetter();
                case STATE_COMPONENT_SET -> emitStateSetter();
                case STATE_AUTHORITY_GET -> emitStateAuthorityGetter();
                case STATE_CHECK_OPEN -> emitStateCheckOpen();
                case STATE_FACTORY_CHECK_OPEN -> emitStateFactoryCheckOpen();
                case STATE_CLOSE -> emitStateClose();
                case STATE_IMPORT_LINK -> emitStateImportLink();
                default -> throw invalidPlan(memberSpan(), "unexpected module-state method: " + member.kind());
            }
        }

        private void emitStateConstructor() {
            // State construction is deliberately a shell operation.  The
            // facade factory supplies one opaque artifact key to every state,
            // so closures can cross module boundaries without authenticating
            // against an unrelated module instance.
            emitObjectSuperConstructor();
            loadParameter(0);
            code.new_(CD_MODULE_ID);
            code.dup();
            code.ldc(module.moduleId().value());
            code.invokespecial(CD_MODULE_ID, "<init>", method("(Ljava/lang/String;)V"));
            code.swap();
            code.invokestatic(CD_LIFECYCLE, "forArtifact", method(
                    "(L" + RUNTIME + "ModuleId;L" + RUNTIME
                            + "LyraArtifactKey;)L" + RUNTIME + "ModuleLifecycle;"));
            aloadReceiver();
            code.swap();
            GeneratedMemberPlan lifecycle = stateLifecycleField();
            code.putfield(cd(classPlan.binaryName()), lifecycle.name(), type(lifecycle.descriptor()));
            if (owner.ir.sessionExecution().isPresent()) {
                loadParameter(0);
                code.new_(CD_MODULE_ID);
                code.dup();
                code.ldc(module.moduleId().value());
                code.invokespecial(CD_MODULE_ID, "<init>", method("(Ljava/lang/String;)V"));
                loadStateLifecycle();
                code.invokevirtual(CD_ARTIFACT_KEY, "registerModuleLifecycle", method(
                        "(L" + RUNTIME + "ModuleId;L" + RUNTIME + "ModuleLifecycle;)V"));
            }
            for (IrDeclaration declaration : declarations.values()) {
                if (!declaration.moduleId().equals(module.moduleId()) || declaration.externalBinding().isEmpty()) continue;
                validateExternalAccessor(declaration, false);
                if (declaration.externalBinding().orElseThrow().allowsRebinding()) {
                    validateExternalAccessor(declaration, true);
                }
            }
            owner.ir.sessionExecution().ifPresent(execution -> execution.externalAccesses().stream()
                    .filter(value -> value.consumer().equals(module.moduleId())).forEach(value -> {
                        validateImportedAccessor(value, false);
                        if (value.writableFacade()) validateImportedAccessor(value, true);
                    }));
            initializeIntrinsicFunctions();
            // Deferred session artifacts expose their one allocated graph to
            // the runtime composition boundary.  The key ignores this call
            // for ordinary artifacts, whose instances intentionally retain
            // the existing isolated loading behavior.
            loadParameter(0);
            code.new_(CD_MODULE_ID);
            code.dup();
            code.ldc(module.moduleId().value());
            code.invokespecial(CD_MODULE_ID, "<init>", method("(Ljava/lang/String;)V"));
            aloadReceiver();
            code.invokevirtual(CD_ARTIFACT_KEY, "registerModuleState", method(
                    "(L" + RUNTIME + "ModuleId;Ljava/lang/Object;)V"));
            code.return_();
        }

        private void initializeIntrinsicFunctions() {
            if (owner.plan.intrinsicFunctionClasses().isEmpty()) {
                return;
            }
            for (IrDeclaration declaration : declarations.values().stream()
                    .filter(value -> value.kind() == DeclarationKind.INTRINSIC_EXPORT)
                    .filter(value -> value.moduleId().equals(module.moduleId()))
                    .sorted(java.util.Comparator.comparing(IrDeclaration::id)).toList()) {
                String adapterName = owner.plan.intrinsicFunctionClasses().get(declaration.id());
                if (adapterName == null) {
                    throw invalidPlan(memberSpan(), "intrinsic function adapter is absent: "
                            + declaration.id());
                }
                List<GeneratedMemberPlan> fields = stateFields(declaration.id());
                if (fields.size() != 1) {
                    throw invalidPlan(memberSpan(), "intrinsic function state storage is invalid: "
                            + declaration.id());
                }
                aloadReceiver();
                code.new_(cd(adapterName));
                code.dup();
                emitCurrentAuthority();
                aloadReceiver();
                code.invokespecial(cd(adapterName), "<init>", method("(L" + RUNTIME
                        + "LyraClosureAuthority;L" + owner.plan.moduleStates().get(module.moduleId())
                        .replace('.', '/') + ";)V"));
                code.putfield(cd(classPlan.binaryName()), fields.getFirst().name(),
                        type(fields.getFirst().descriptor()));
            }
        }

        private GeneratedMemberPlan stateLifecycleField() {
            return classPlan.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.STATE_LIFECYCLE_FIELD)
                    .findFirst().orElseThrow(() ->
                            invalidPlan(memberSpan(), "module state has no lifecycle field"));
        }

        private void loadStateLifecycle() {
            GeneratedMemberPlan lifecycle = stateLifecycleField();
            aloadReceiver();
            code.getfield(cd(classPlan.binaryName()), lifecycle.name(), type(lifecycle.descriptor()));
        }

        private void emitStateAuthorityGetter() {
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "closureAuthority",
                    method("()L" + RUNTIME + "LyraClosureAuthority;"));
            code.areturn();
        }

        private void emitStateFactoryCheckOpen() {
            if (module.submissionResult().isPresent()) {
                aloadReceiver();
                code.invokevirtual(cd(classPlan.binaryName()), "$lyra$checkOpen", method("()V"));
                code.return_();
                return;
            }
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "defersSubmission", method("()Z"));
            Label ordinary = code.newLabel();
            code.ifeq(ordinary);
            code.return_();
            code.labelBinding(ordinary);
            aloadReceiver();
            code.invokevirtual(cd(classPlan.binaryName()), "$lyra$checkOpen", method("()V"));
            code.return_();
        }

        private void emitStateCheckOpen() {
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "isOpen", method("()Z"));
            Label alreadyOpen = code.newLabel();
            Label initializationStart = code.newLabel();
            Label initializationEnd = code.newLabel();
            Label initializationFailure = code.newLabel();
            code.ifne(alreadyOpen);
            // Only INITIALIZING states may enter the one-shot initializer.
            // CLOSED and FAILED states must be delegated to the runtime so
            // callers receive LYR-CLOSED or LYR-INIT rather than an invalid
            // second transition attempt.
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "state", method("()L" + RUNTIME
                    + "LifecycleState;"));
            code.getstatic(CD_LIFECYCLE_STATE, "INITIALIZING", CD_LIFECYCLE_STATE);
            code.if_acmpeq(initializationStart);
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "checkOpen", method("()V"));
            code.return_();
            code.labelBinding(initializationStart);
            // Keep the protected range non-empty for import-only modules.  A
            // zero-width exception table entry is rejected by the JVM class
            // verifier even though the initializer has no source forms.
            code.nop();
            // Function signatures are predeclared, so every root function slot
            // must contain its closure before any eager initializer can invoke
            // it.  Capture dependencies are initialized on demand first; this
            // preserves the source order required by immutable capture
            // semantics without exposing a null forward slot.  In a deferred
            // artifact only the root scratch state is opened by the factory;
            // dependency states are initialized by the guarded session entry
            // point after the runtime has retained the attempted producer.
            if (module.submissionResult().isPresent()) {
                Label prepared = code.newLabel();
                loadStateLifecycle();
                code.invokevirtual(CD_LIFECYCLE, "defersSubmission", method("()Z"));
                code.ifne(prepared);
                aloadReceiver();
                code.invokevirtual(cd(classPlan.binaryName()), "$lyra$sessionExecute",
                        method("()" + sessionResultPlan().descriptor()));
                discard(sessionResultPlan());
                code.goto_(initializationEnd);
                code.labelBinding(prepared);
            } else {
                emitRootForms(false);
            }
            code.labelBinding(initializationEnd);
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "open", method("()V"));
            code.labelBinding(alreadyOpen);
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "checkOpen", method("()V"));
            code.return_();

            // An initializer failure makes this state terminally FAILED and
            // is never exposed as a partially constructed facade.  Preserve
            // the original runtime exception as the Java cause while giving
            // callers the stable initialization category.
            code.exceptionCatch(initializationStart, initializationEnd,
                    initializationFailure, CD_JAVA_RUNTIME_EXCEPTION);
            code.labelBinding(initializationFailure);
            int cause = code.allocateLocal(TypeKind.REFERENCE);
            code.astore(cause);
            loadStateLifecycle();
            code.aload(cause);
            code.invokevirtual(CD_LIFECYCLE, "fail", method("(Ljava/lang/Throwable;)V"));
            code.ldc("LYR-INIT");
            code.invokestatic(CD_FAILURE_CATEGORY, "fromCode",
                    method("(Ljava/lang/String;)L" + RUNTIME + "LyraFailureCategory;"));
            code.ldc("module initialization failed");
            code.aload(cause);
            code.invokestatic(CD_RUNTIME_EXCEPTION, "of", method(
                    "(L" + RUNTIME + "LyraFailureCategory;Ljava/lang/String;Ljava/lang/Throwable;)L"
                            + RUNTIME + "LyraRuntimeException;"));
            emitSourceFrame(module.span());
            code.iconst_0();
            code.invokeinterface(CD_LIST, "get", method("(I)Ljava/lang/Object;"));
            code.checkcast(cd(RUNTIME + "SourceFrame"));
            code.invokevirtual(CD_RUNTIME_EXCEPTION, "withFrame", method(
                    "(L" + RUNTIME + "SourceFrame;)L" + RUNTIME + "LyraRuntimeException;"));
            code.athrow();
        }

        private void emitSessionSafePoint() {
            if (owner.ir.sessionExecution().isEmpty()) return;
            emitLoadModuleState(module.moduleId());
            code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                    "$lyra$sessionSafePoint", method("()V"));
        }

        /**
         * Attachable dispatch boundary.  Only the root module's own generated
         * code may reference the registration hook; dependency code is never
         * instrumented.  Initialization runs before the lifecycle opens, so
         * any hook reached there is inert at runtime.
         */
        private void emitAttachmentSafePoint() {
            if (owner.plan.emissionMode() != EmissionMode.ATTACHABLE) return;
            if (!module.moduleId().equals(owner.rootModuleId())) return;
            emitLoadModuleState(module.moduleId());
            code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                    "$lyra$attachmentSafePoint", method("()V"));
        }

        /** Function/call/form/self-tail dispatch boundary for the current mode. */
        private void emitOwnerSafePoint() {
            emitSessionSafePoint();
            emitAttachmentSafePoint();
        }

        /** Loads one state from the single prepared graph without instantiating it. */
        private void emitLoadSessionGraphState(ModuleId targetModule) {
            emitLoadModuleState(module.moduleId());
            code.new_(CD_MODULE_ID);
            code.dup();
            code.ldc(targetModule.value());
            code.invokespecial(CD_MODULE_ID, "<init>", method("(Ljava/lang/String;)V"));
            String rootState = owner.plan.moduleStates().get(module.moduleId());
            code.invokevirtual(cd(rootState), "$lyra$moduleState", method(
                    "(L" + RUNTIME + "ModuleId;)Ljava/lang/Object;"));
            String targetState = owner.plan.moduleStates().get(targetModule);
            if (targetState == null) {
                throw invalidPlan(memberSpan(), "session graph state is absent: " + targetModule);
            }
            code.checkcast(cd(targetState));
        }

        private JvmTypePlan sessionResultPlan() {
            return owner.mapper.map(module.submissionResult().orElseThrow().type(),
                    JvmMappingContext.JAVA_VALUE);
        }

        private void emitSessionExecute() {
            // Ordinary session artifacts have already initialized every
            // dependency in their factory.  Prepared artifacts defer that
            // work until this guarded entry point, after the runtime has
            // retained the attempted producer.  Keep the branch in generated
            // bytecode so one emitted session contract supports both loading
            // modes without using a second graph instantiation.
            Label deferred = code.newLabel();
            Label afterDependencies = code.newLabel();
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "defersSubmission", method("()Z"));
            code.ifne(deferred);
            code.goto_(afterDependencies);
            code.labelBinding(deferred);
            // The factory only allocates and links shells. Initialize every
            // newly emitted dependency here in canonical order.
            for (ModuleId moduleId : owner.plan.initializationOrder()) {
                if (moduleId.equals(module.moduleId())) continue;
                emitLoadSessionGraphState(moduleId);
                String stateName = owner.plan.moduleStates().get(moduleId);
                if (stateName == null) {
                    throw invalidPlan(memberSpan(), "session initialization state is absent: " + moduleId);
                }
                code.invokevirtual(cd(stateName), "$lyra$checkOpen", method("()V"));
            }
            code.labelBinding(afterDependencies);
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "beginSubmission", method("()V"));
            emitRootForms(true);
            JvmTypePlan result = sessionResultPlan();
            int value = allocateLocal(result);
            storePhysical(result.physicalComponents().getFirst(), value);
            aloadReceiver();
            loadPhysical(result.physicalComponents().getFirst(), value);
            code.putfield(cd(classPlan.binaryName()), "$lyra$sessionResult", type(result.descriptor()));
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "completeSubmission", method("()V"));
            loadPhysical(result.physicalComponents().getFirst(), value);
            returnPhysicalDescriptor(result.descriptor());
        }

        private void emitRootForms(boolean keepResult) {
            for (IrNode form : module.body().forms()) {
                if (isRootFunctionSlotDeclaration(form)) {
                    initializeRootDeclaration(declarationId(form));
                }
            }
            List<IrNode> forms = module.body().forms();
            for (int index = 0; index < forms.size(); index++) {
                IrNode form = forms.get(index);
                emitSessionSafePoint();
                boolean last = keepResult && index == forms.size() - 1;
                if (form instanceof IrNode.Declaration declaration
                        && declaration.declarationId().isPresent()
                        && isRootDeclaration(declaration.declarationId().orElseThrow())) {
                    initializeRootDeclaration(declaration.declarationId().orElseThrow());
                    if (last) emitUnit();
                } else {
                    JvmTypePlan value = emitNode(form);
                    if (last) adapt(value, sessionResultPlan());
                    else discard(value);
                }
            }
            if (keepResult && forms.isEmpty()) emitUnit();
        }

        private void emitSessionResultGetter() {
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "checkSubmissionResult", method("()V"));
            aloadReceiver();
            String descriptor = sessionResultPlan().descriptor();
            code.getfield(cd(classPlan.binaryName()), "$lyra$sessionResult", type(descriptor));
            returnPhysicalDescriptor(descriptor);
        }

        private boolean isRootFunctionSlotDeclaration(IrNode form) {
            if (!(form instanceof IrNode.Declaration declaration)
                    || declaration.declarationId().isEmpty()) {
                return false;
            }
            IrDeclaration metadata = declarations.get(declaration.declarationId().orElseThrow());
            return metadata != null
                    && metadata.scopeId().equals(module.rootScope())
                    && metadata.initializerLambda().isPresent()
                    && module.state().functionSlots().contains(metadata.id());
        }

        private DeclarationId declarationId(IrNode form) {
            if (!(form instanceof IrNode.Declaration declaration)
                    || declaration.declarationId().isEmpty()) {
                throw invalidPlan(form.span(), "root initialization form has no declaration identity");
            }
            return declaration.declarationId().orElseThrow();
        }

        private void initializeRootDeclaration(DeclarationId id) {
            if (initializedRootDeclarations.contains(id)) {
                return;
            }
            if (!initializingRootDeclarations.add(id)) {
                throw invalidPlan(memberSpan(),
                        "root initialization has a cyclic capture dependency: " + id);
            }
            try {
                IrDeclaration metadata = declarations.get(id);
                if (metadata == null || !metadata.moduleId().equals(module.moduleId())
                        || !metadata.scopeId().equals(module.rootScope())) {
                    throw invalidPlan(memberSpan(), "root initialization declaration is absent: " + id);
                }
                if (metadata.initializerLambda().isPresent()) {
                    io.mindspice.lyra.compiler.ir.IrLambda rootLambda = owner.lambdas.values().stream()
                            .filter(value -> value.id().equals(metadata.initializerLambda().orElseThrow()))
                            .findFirst().orElseThrow(() -> invalidPlan(metadata.span(),
                                    "root function initializer has no lambda metadata"));
                    for (CaptureId captureId : rootLambda.captures()) {
                        IrCapture capture = captures.get(captureId);
                        if (capture == null) {
                            throw invalidPlan(metadata.span(), "root function capture is absent: " + captureId);
                        }
                        IrDeclaration captured = declarations.get(capture.declarationId());
                        if (captured != null && captured.moduleId().equals(module.moduleId())
                                && captured.scopeId().equals(module.rootScope())
                                && !captured.imported()) {
                            initializeRootDeclarationsThrough(captured.id());
                        }
                    }
                }
                IrNode form = module.body().forms().stream()
                        .filter(candidate -> candidate instanceof IrNode.Declaration declaration
                                && declaration.declarationId().filter(id::equals).isPresent())
                        .findFirst().orElseThrow(() -> invalidPlan(metadata.span(),
                                "root declaration is absent from the module body: " + id));
                if (owner.ir.sessionExecution().isPresent()) {
                    emitSessionSafePoint();
                    loadStateLifecycle();
                    code.ldc(id.ordinal());
                    code.invokevirtual(CD_LIFECYCLE, "beginSessionBinding", method("(J)V"));
                }
                discard(emitNode(form));
                if (owner.ir.sessionExecution().isPresent()) {
                    loadStateLifecycle();
                    code.ldc(id.ordinal());
                    code.invokevirtual(CD_LIFECYCLE, "initializeSessionBinding", method("(J)V"));
                }
                initializedRootDeclarations.add(id);
            } finally {
                initializingRootDeclarations.remove(id);
            }
        }

        private void initializeRootDeclarationsThrough(DeclarationId target) {
            boolean found = false;
            for (IrNode form : module.body().forms()) {
                if (!(form instanceof IrNode.Declaration declaration)
                        || declaration.declarationId().isEmpty()
                        || !isRootDeclaration(declaration.declarationId().orElseThrow())) {
                    continue;
                }
                DeclarationId id = declaration.declarationId().orElseThrow();
                initializeRootDeclaration(id);
                if (id.equals(target)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw invalidPlan(memberSpan(),
                        "captured root declaration is absent from source order: " + target);
            }
        }

        private void emitStateClose() {
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "isClosed", method("()Z"));
            Label alreadyClosed = code.newLabel();
            code.ifne(alreadyClosed);
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "closeGeneratedState", method("()V"));
            for (io.mindspice.lyra.compiler.ir.IrImportBinding binding : importsForModule(module.moduleId())) {
                if (owner.ir.sessionExecution().filter(value -> !value.emits(binding.targetModule())).isPresent()) continue;
                GeneratedMemberPlan field = stateFields(module.moduleId(), binding.declarationId()).stream()
                        .filter(value -> value.kind() == GeneratedMemberKind.STATE_IMPORT_FIELD)
                        .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                                "import state field is absent"));
                aloadReceiver();
                code.invokevirtual(cd(classPlan.binaryName()),
                        "$lyra$get$binding$" + binding.declarationId().value(),
                        method("()" + field.descriptor()));
                code.invokevirtual(type(field.descriptor()), "$lyra$close", method("()V"));
            }
            // Keep the lifecycle object as the small closed-state sentinel,
            // but release all compiler-owned value, cell, and dependency
            // references retained by this state.  Raw arrays and closures
            // already returned to Java remain independent caller-owned
            // values, as required by the trusted Java ABI.
            for (GeneratedMemberPlan field : classPlan.members().stream()
                    .filter(GeneratedMemberPlan::isField)
                    .filter(value -> value.kind() != GeneratedMemberKind.STATE_LIFECYCLE_FIELD)
                    .toList()) {
                aloadReceiver();
                emitZero(jvmType(field.descriptor()));
                code.putfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
            }
            code.labelBinding(alreadyClosed);
            code.return_();
        }

        private void emitStateImportLink() {
            DeclarationId id = declarationIdFromMember(member.name());
            GeneratedMemberPlan field = stateFields(module.moduleId(), id).stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.STATE_IMPORT_FIELD)
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                            "import link has no state field"));
            aloadReceiver();
            loadParameter(0);
            code.putfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
            code.return_();
        }

        private void emitStateGetter() {
            DeclarationId id = declarationIdFromMember(member.name());
            checkSessionBinding(id);
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(module.moduleId())).orElseThrow();
            List<GeneratedMemberPlan> fields = stateFields(id);
            if (fields.size() == 1) {
                aloadReceiver();
                GeneratedMemberPlan field = fields.getFirst();
                code.getfield(cd(state.binaryName()), field.name(), type(field.descriptor()));
                returnPhysicalDescriptor(field.descriptor());
                return;
            }
            if (fields.size() == 2) {
                String suffix = member.name().contains("$isPresent$") ? "$present" : "$payload";
                GeneratedMemberPlan field = fields.stream()
                        .filter(value -> value.name().endsWith(suffix))
                        .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                                "split state getter has no matching component"));
                aloadReceiver();
                code.getfield(cd(state.binaryName()), field.name(), type(field.descriptor()));
                returnPhysicalDescriptor(field.descriptor());
                return;
            }
            throw invalidPlan(memberSpan(), "invalid state getter storage shape");
        }

        private void emitStateSetter() {
            DeclarationId id = declarationIdFromMember(member.name());
            checkSessionBinding(id);
            List<GeneratedMemberPlan> fields = stateFields(id);
            int parameter = 0;
            for (GeneratedMemberPlan field : fields) {
                aloadReceiver();
                loadParameter(parameter++);
                code.putfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
            }
            code.return_();
        }

        private void emitFacadeMethod() {
            switch (member.kind()) {
                case SESSION_BINDING_GET, SESSION_BINDING_SET -> emitSessionBindingAccessor();
                case FACADE_SESSION_EXECUTE -> {
                    checkFacadeStateOpen();
                    loadFacadeState();
                    code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                            "$lyra$sessionExecute", method("()" + sessionResultPlan().descriptor()));
                    discard(sessionResultPlan());
                    code.return_();
                }
                case FACADE_SESSION_RESULT_GET -> {
                    checkFacadeStateOpen();
                    loadFacadeState();
                    code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                            "$lyra$sessionResult", method(member.descriptor()));
                    returnPhysicalDescriptor(sessionResultPlan().descriptor());
                }
                case FACADE_MODULE_STATE -> {
                    loadFacadeState();
                    loadParameter(0);
                    code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                            "$lyra$moduleState", method(member.descriptor()));
                    code.areturn();
                }
                case FACADE_VIEW_FACTORY -> emitFacadeViewFactory();
                case ATTACHMENT_LIFECYCLE -> {
                    loadFacadeState();
                    code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                            "$lyra$attachmentLifecycle", method("()" + CD_LIFECYCLE.descriptorString()));
                    code.areturn();
                }
                case FACADE_CONSTRUCTOR -> emitFacadeConstructor();
                case FACTORY, FACTORY_WITH_OPTIONS -> emitFacadeFactory();
                case METADATA -> emitFacadeMetadata();
                case CLOSE -> emitFacadeClose();
                case FUNCTION_INVOCATION, VALUE_GETTER, FUNCTION_VALUE_GETTER, SETTER ->
                        emitFacadeExport();
                default -> throw invalidPlan(memberSpan(), "unexpected facade method: " + member.kind());
            }
        }

        private void emitSessionBindingAccessor() {
            checkFacadeStateOpen();
            DeclarationId id = declarationIdFromMember(member.name());
            IrDeclaration declaration = declarations.get(id);
            JvmTypePlan value = owner.mapper.map(declaration.contract().orElseThrow().valueType(), JvmMappingContext.JAVA_VALUE);
            JvmTypePlan internal = storagePlan(id);
            if (member.kind() == GeneratedMemberKind.SESSION_BINDING_GET) {
                loadFacadeStateValue(id);
                adapt(internal, value);
                returnPhysicalDescriptor(value.descriptor());
                return;
            }
            loadParameter(0);
            authenticateSessionValue(declaration.contract().orElseThrow().valueType());
            discard(value);
            loadFacadeState();
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates().get(module.moduleId())).orElseThrow();
            if (stateBindingIsCell(id)) {
                GeneratedMemberPlan field = stateFields(id).getFirst();
                code.invokevirtual(cd(state.binaryName()), "$lyra$get$binding$" + id.value(), method("()" + field.descriptor()));
                loadParameter(0);
                adapt(value, internal);
                var cell = owner.plan.classPlan(owner.plan.cellClasses().get(id)).orElseThrow();
                var setter = cell.members().stream().filter(candidate -> candidate.kind() == GeneratedMemberKind.CELL_SET)
                        .findFirst().orElseThrow();
                code.invokevirtual(cd(cell.binaryName()), setter.name(), method(setter.descriptor()));
            } else {
                loadParameter(0);
                adapt(value, internal);
                invokeStateSetter(id, member.descriptor());
            }
            code.return_();
        }

        private void checkSessionBinding(DeclarationId id) {
            if (owner.ir.sessionExecution().isEmpty()) return;
            IrDeclaration declaration = declarations.get(id);
            if (declaration == null || declaration.kind() != DeclarationKind.LET) return;
            loadStateLifecycle();
            code.ldc(id.ordinal());
            code.invokevirtual(CD_LIFECYCLE, "checkSessionBinding", method("(J)V"));
        }

        /** Checks callable leaves without wrapping or copying the exact typed value. */
        private void authenticateSessionValue(LyraType logical) {
            LyraType base = logical.withoutQualifiers();
            if (base instanceof PrimitiveType) return;
            if (base instanceof io.mindspice.lyra.compiler.types.RangeType) {
                authenticateRange(logical);
                return;
            }
            JvmTypePlan physical = owner.mapper.map(logical, JvmMappingContext.JAVA_VALUE);
            int slot = allocateLocal(physical);
            storePhysical(physical.physicalComponents().getFirst(), slot);
            Label done = code.newLabel();
            if (logical.isNilable()) {
                code.aload(slot);
                code.ifnull(done);
            }
            if (base instanceof FunctionType function) {
                code.aload(slot);
                emitIoAuthority();
                code.ldc(function.signature().canonicalSpelling());
                code.invokestatic(CD_SIGNATURE, "parse", method("(Ljava/lang/String;)L" + RUNTIME + "LyraSignature;"));
                code.invokestatic(CD_RUNTIME_CLOSURE_SUPPORT, "requireAuthenticated", method(
                        "(Ljava/lang/Object;L" + RUNTIME + "LyraClosureAuthority;L" + RUNTIME
                                + "LyraSignature;)L" + RUNTIME + "LyraClosure;"));
                code.pop();
            } else if (base instanceof ArrayType array) {
                if (!(array.elementType().withoutQualifiers() instanceof PrimitiveType)) {
                    int index = code.allocateLocal(TypeKind.INT);
                    code.iconst_0();
                    code.istore(index);
                    Label loop = code.newLabel();
                    code.labelBinding(loop);
                    code.iload(index);
                    code.aload(slot);
                    code.arraylength();
                    code.if_icmpge(done);
                    code.aload(slot);
                    code.iload(index);
                    code.aaload();
                    authenticateSessionValue(array.elementType());
                    code.pop();
                    code.iinc(index, 1);
                    code.goto_(loop);
                }
            } else if (base instanceof TupleType tuple) {
                String name = owner.plan.tupleClasses().get(tuple.canonicalSpelling());
                for (int index = 0; index < tuple.arity(); index++) {
                    LyraType memberType = tuple.memberType(index);
                    if (memberType.withoutQualifiers() instanceof PrimitiveType) continue;
                    code.aload(slot);
                    String descriptor = owner.mapper.map(memberType, JvmMappingContext.TUPLE_FIELD).descriptor();
                    code.invokevirtual(cd(name), "$lyra$get$" + index, method("()" + descriptor));
                    authenticateSessionValue(memberType);
                    code.pop();
                }
            }
            code.labelBinding(done);
            code.aload(slot);
        }

        private void emitFacadeViewFactory() {
            String stateName = owner.plan.moduleStates().get(module.moduleId());
            if (stateName == null) {
                throw invalidPlan(memberSpan(), "facade view has no module state: " + module.moduleId());
            }
            code.new_(cd(classPlan.binaryName()));
            code.dup();
            loadParameter(0);
            code.checkcast(cd(stateName));
            code.invokespecial(cd(classPlan.binaryName()), "<init>",
                    method("(L" + stateName.replace('.', '/') + ";)V"));
            code.areturn();
        }

        private void emitFacadeConstructor() {
            emitObjectSuperConstructor();
            aloadReceiver();
            loadParameter(0);
            GeneratedMemberPlan field = classPlan.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.FACADE_STATE_FIELD)
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(), "facade has no state field"));
            code.putfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
            code.return_();
        }

        private void emitFacadeFactory() {
            // Both factory overloads enter through the same immutable runtime
            // option contract.  The no-argument form explicitly uses the
            // runtime defaults; the overload rejects a null options object
            // before any module shell is allocated.  Compatibility is checked
            // before construction so a bad profile cannot leave a partially
            // initialized graph behind.
            int options = code.allocateLocal(TypeKind.REFERENCE);
            if (member.kind() == GeneratedMemberKind.FACTORY) {
                code.invokestatic(CD_RUNTIME_OPTIONS, "defaults", method(
                        "()L" + RUNTIME + "RuntimeOptions;"));
            } else {
                loadParameter(0);
                code.invokestatic(CD_OBJECTS, "requireNonNull",
                        method("(Ljava/lang/Object;)Ljava/lang/Object;"));
                code.checkcast(CD_RUNTIME_OPTIONS);
            }
            code.astore(options);
            code.aload(options);
            code.invokestatic(cd(classPlan.binaryName()), "$lyra$metadata", method(
                    "()L" + RUNTIME + "ArtifactMetadata;"));
            code.invokevirtual(CD_RUNTIME_OPTIONS, "requireCompatible", method(
                    "(L" + RUNTIME + "ArtifactMetadata;)V"));
            code.aload(options);
            code.invokevirtual(CD_RUNTIME_OPTIONS, "owner", method(
                    "()L" + RUNTIME + "OwnerThread;"));
            code.invokevirtual(CD_OWNER_THREAD, "check", method("()V"));

            Map<ModuleId, Integer> stateSlots = new TreeMap<>();
            int artifactKey = code.allocateLocal(TypeKind.REFERENCE);
            code.aload(options);
            code.invokestatic(CD_LIFECYCLE, "newArtifactKey",
                    method("(L" + RUNTIME + "RuntimeOptions;)L" + RUNTIME
                            + "LyraArtifactKey;"));
            code.astore(artifactKey);
            for (ModuleId moduleId : owner.plan.initializationOrder()) {
                GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                        .get(moduleId)).orElseThrow();
                int slot = code.allocateLocal(TypeKind.REFERENCE);
                code.new_(cd(state.binaryName()));
                code.dup();
                code.aload(artifactKey);
                code.invokespecial(cd(state.binaryName()), "<init>",
                        method("(L" + RUNTIME + "LyraArtifactKey;)V"));
                code.astore(slot);
                stateSlots.put(moduleId, slot);
            }

            // All state objects are shells at this point.  Link every import
            // before running any eager initializer so recursive function SCCs
            // observe one coherent set of module instances.
            for (io.mindspice.lyra.compiler.ir.IrImportBinding binding : owner.ir.imports()) {
                Integer consumer = stateSlots.get(ModuleId.fromSourceId(
                        binding.importSpan().sourceId()));
                Integer target = stateSlots.get(binding.targetModule());
                if (owner.ir.sessionExecution().filter(execution ->
                        !execution.emits(ModuleId.fromSourceId(binding.importSpan().sourceId()))
                                || !execution.emits(binding.targetModule())).isPresent()) continue;
                if (consumer == null || target == null) {
                    throw invalidPlan(memberSpan(), "import state shell is absent");
                }
                GeneratedClassPlan consumerPlan = owner.plan.classPlan(owner.plan.moduleStates()
                        .get(ModuleId.fromSourceId(binding.importSpan().sourceId()))).orElseThrow();
                GeneratedMemberPlan link = consumerPlan.members().stream()
                        .filter(value -> value.kind() == GeneratedMemberKind.STATE_IMPORT_LINK
                                && value.name().equals("$lyra$link$binding$"
                                + binding.declarationId().value()))
                        .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                                "import state link is absent"));
                code.aload(consumer);
                code.aload(target);
                code.invokevirtual(cd(consumerPlan.binaryName()), link.name(),
                        method(link.descriptor()));
            }

            // Ordinary factories initialize every state.  A prepared session
            // factory opens only the root scratch state; dependency shells are
            // initialized by the guarded session entry point in canonical
            // order, so allocation cannot accidentally execute source.
            // Preserve the validated eager-initialization order.  The slot map
            // is keyed for lookup only; iterating it lexically would execute a
            // dependency after its consumer and regress ordinary AOT graphs.
            for (ModuleId moduleId : owner.plan.initializationOrder()) {
                Integer slot = stateSlots.get(moduleId);
                if (slot == null) {
                    throw invalidPlan(memberSpan(), "module state slot is absent: " + moduleId);
                }
                GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                        .get(moduleId)).orElseThrow();
                code.aload(slot);
                code.invokevirtual(cd(state.binaryName()), "$lyra$factoryCheckOpen", method("()V"));
            }

            ModuleId facadeModule = classPlan.moduleId().orElseThrow();
            GeneratedClassPlan facadeState = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(facadeModule)).orElseThrow();
            code.new_(cd(classPlan.binaryName()));
            code.dup();
            code.aload(stateSlots.get(facadeModule));
            code.invokespecial(cd(classPlan.binaryName()), "<init>",
                    method("(" + descriptorOf(facadeState) + ")V"));
            code.areturn();
        }

        private void emitFacadeMetadata() {
            if (!owner.chunkedMetadata) {
                code.ldc(metadataJson());
                code.invokestatic(CD_METADATA_READER, "read",
                        method("(Ljava/lang/String;)L" + RUNTIME + "ArtifactMetadata;"));
                code.areturn();
                return;
            }
            List<String> chunks;
            try {
                chunks = metadataChunks(metadataJson());
            } catch (IllegalArgumentException failure) {
                throw unsupported(memberSpan(), failure.getMessage());
            }
            for (int index = 0; index < METADATA_CHUNK_COUNT; index++) {
                code.ldc(metadataChunkMarker(index) + chunks.get(index));
                code.ldc(metadataChunkMarker(index).length());
                code.invokevirtual(CD_STRING, "substring", method("(I)Ljava/lang/String;"));
                if (index > 0) {
                    code.invokevirtual(CD_STRING, "concat", method("(Ljava/lang/String;)Ljava/lang/String;"));
                }
            }
            code.invokestatic(CD_METADATA_READER, "read",
                    method("(Ljava/lang/String;)L" + RUNTIME + "ArtifactMetadata;"));
            code.areturn();
        }

        private String metadataJson() {
            // Metadata construction is performed once by the compiler, then
            // embedded as canonical UTF-8 text.  The generated method has no
            // Object/varargs ABI and runtime validation remains authoritative.
            List<io.mindspice.lyra.runtime.ModuleMetadata> runtimeModules = new ArrayList<>();
            List<io.mindspice.lyra.runtime.SourceMetadata> runtimeSources = new ArrayList<>();
            for (IrModule value : owner.ir.modules()) {
                io.mindspice.lyra.compiler.source.SourceSnapshot snapshot =
                        owner.ir.sourceSnapshot(value.moduleId()).orElseThrow();
                io.mindspice.lyra.runtime.ModuleId moduleId = value.moduleId().isUri()
                        ? io.mindspice.lyra.runtime.ModuleId.uri(value.moduleId().asUri())
                        : io.mindspice.lyra.runtime.ModuleId.path(value.moduleId().value());
                runtimeModules.add(new io.mindspice.lyra.runtime.ModuleMetadata(
                        moduleId,
                        io.mindspice.lyra.runtime.ModuleRevision.of(
                                owner.ir.typedSemanticGraph().resolvedGraph().moduleGraph()
                                        .module(value.moduleId()).orElseThrow().revision()),
                        snapshot.sourceId().value()));
                io.mindspice.lyra.runtime.SourceId sourceId = snapshot.sourceId().isUri()
                        ? io.mindspice.lyra.runtime.SourceId.uri(snapshot.sourceId().asUri())
                        : io.mindspice.lyra.runtime.SourceId.path(snapshot.sourceId().value());
                runtimeSources.add(new io.mindspice.lyra.runtime.SourceMetadata(
                        sourceId, snapshot.sourceId().value(), snapshot.sha256()));
            }
            List<io.mindspice.lyra.runtime.ExportMetadata> runtimeExports = new ArrayList<>();
            Map<String, String> javaNames = new TreeMap<>();
            for (GeneratedExportPlan export : owner.plan.exports()) {
                io.mindspice.lyra.runtime.ModuleId moduleId = export.moduleId().isUri()
                        ? io.mindspice.lyra.runtime.ModuleId.uri(export.moduleId().asUri())
                        : io.mindspice.lyra.runtime.ModuleId.path(export.moduleId().value());
                // Metadata identities use the complete exported value
                // contract, including a top-level @nil on function values.
                String contractSpelling = export.valueType().canonicalLyraType();
                io.mindspice.lyra.runtime.LyraType contract =
                        io.mindspice.lyra.runtime.LyraType.parse(contractSpelling);
                String getter = export.getterName().orElse("get$" + export.javaName());
                String functionGetter = export.functionValueName()
                        .orElse("value$" + export.javaName());
                String jvmDescriptor = export.functionSignature()
                        .map(JvmSignaturePlan::descriptor)
                        .orElse("()" + export.valueType().descriptor());
                io.mindspice.lyra.runtime.BindingMutability mutability =
                        export.isMutable()
                                ? io.mindspice.lyra.runtime.BindingMutability.MUTABLE
                                : io.mindspice.lyra.runtime.BindingMutability.IMMUTABLE;
                String javaInvocationName = export.isFunction()
                        ? export.invocationName().orElseThrow()
                        : export.javaName();
                io.mindspice.lyra.runtime.ExportMetadata runtimeExport = new io.mindspice.lyra.runtime.ExportMetadata(
                        moduleId, export.sourceName(), contract, jvmDescriptor, mutability,
                        javaInvocationName, getter, functionGetter,
                        export.setterName().map(value -> value));
                runtimeExports.add(runtimeExport);
                javaNames.put(runtimeExport.id().id(), runtimeExport.javaName());
            }
            runtimeModules.sort(io.mindspice.lyra.runtime.ModuleMetadata::compareTo);
            runtimeSources.sort(io.mindspice.lyra.runtime.SourceMetadata::compareTo);
            runtimeExports.sort(io.mindspice.lyra.runtime.ExportMetadata::compareTo);
            io.mindspice.lyra.runtime.ArtifactRevision revision =
                    io.mindspice.lyra.runtime.ArtifactRevision.compute(
                            "lyra-phase15", runtimeModules, javaNames,
                            io.mindspice.lyra.runtime.RuntimeProfile.CURRENT,
                            io.mindspice.lyra.runtime.PackagingMode.CLASSES, false,
                            owner.plan.basePackage(), runtimeSources, java.util.Optional.empty());
            io.mindspice.lyra.runtime.ModuleId runtimeRoot = owner.rootModuleId().isUri()
                    ? io.mindspice.lyra.runtime.ModuleId.uri(owner.rootModuleId().asUri())
                    : io.mindspice.lyra.runtime.ModuleId.path(owner.rootModuleId().value());
            io.mindspice.lyra.runtime.ModuleMetadata root = runtimeModules.stream()
                    .filter(value -> value.id().equals(runtimeRoot))
                    .findFirst().orElseThrow();
            String canonical = io.mindspice.lyra.runtime.ArtifactMetadata.builder()
                    .compilerVersion("lyra-phase15")
                    .compilerBuild("lyra-phase15")
                    .runtimeAbi(io.mindspice.lyra.runtime.RuntimeAbi.CURRENT)
                    .profile(io.mindspice.lyra.runtime.RuntimeProfile.CURRENT)
                    .javaClassFileTarget(io.mindspice.lyra.runtime.LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET)
                    .previewRequired(false)
                    .artifactId(JvmStableHash.sha256("LYRA-JVM-ARTIFACT", owner.plan.canonical()))
                    .artifactRevision(revision)
                    .rootModuleId(root.id())
                    .rootModuleRevision(root.revision())
                    .modules(runtimeModules)
                    .sources(runtimeSources)
                    .javaPackage(owner.plan.basePackage())
                    .exports(runtimeExports)
                    .javaNameMap(javaNames)
                    .debugMapHash(JvmStableHash.sha256("LYRA-JVM-DEBUG-MAP", owner.plan.canonical()))
                    .packagingMode(io.mindspice.lyra.runtime.PackagingMode.CLASSES)
                    .build().canonicalJson();
            // Keep one compiler-owned marker in the provisional constant so
            // assembly cannot mistake a user string with the same JSON prefix
            // for the metadata that must be replaced at publication time.
            return canonical.substring(0, canonical.length() - 1)
                    + ",\"_lyraProvisional\":true}";
        }

        private static List<String> metadataChunks(String value) {
            ArrayList<String> result = new ArrayList<>(METADATA_CHUNK_COUNT);
            int offset = 0;
            for (int index = 0; index < METADATA_CHUNK_COUNT; index++) {
                int start = offset;
                int bytes = 0;
                while (offset < value.length()) {
                    char character = value.charAt(offset);
                    int characterBytes = character == '\u0000' ? 2
                            : character <= 0x7f ? 1
                            : character <= 0x7ff ? 2 : 3;
                    if (bytes + characterBytes > METADATA_CHUNK_BYTES) {
                        break;
                    }
                    bytes += characterBytes;
                    offset++;
                }
                if (start == offset && offset < value.length()) {
                    throw new IllegalArgumentException("facade metadata exceeds the supported size");
                }
                result.add(value.substring(start, offset));
            }
            if (offset < value.length()) {
                throw new IllegalArgumentException("facade metadata exceeds the supported size");
            }
            while (result.size() < METADATA_CHUNK_COUNT) {
                result.add("");
            }
            return List.copyOf(result);
        }

        private static String metadataChunkMarker(int index) {
            return METADATA_CHUNK_PREFIX + index + METADATA_CHUNK_SUFFIX;
        }

        private void emitFacadeClose() {
            GeneratedMemberPlan state = classPlan.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.FACADE_STATE_FIELD)
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(), "facade has no state field"));
            aloadReceiver();
            code.getfield(cd(classPlan.binaryName()), state.name(), type(state.descriptor()));
            code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                    "$lyra$close", method("()V"));
            code.return_();
        }

        private void emitFacadeExport() {
            GeneratedExportPlan export = owner.plan.exports().stream()
                    .filter(value -> value.moduleId().equals(module.moduleId()))
                    .filter(value -> value.members().stream().anyMatch(candidate ->
                            candidate.kind() == member.kind()
                                    && candidate.name().equals(member.name())
                                    && candidate.descriptor().equals(member.descriptor())))
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                            "facade member has no export identity: " + member.name()));
            checkFacadeStateOpen();
            if (member.kind() == GeneratedMemberKind.FUNCTION_INVOCATION) {
                emitFacadeFunctionInvocation(export);
            } else if (member.kind() == GeneratedMemberKind.FUNCTION_VALUE_GETTER) {
                emitFacadeFunctionValueGetter(export);
            } else if (member.kind() == GeneratedMemberKind.VALUE_GETTER) {
                emitFacadeValueGetter(export);
            } else if (member.kind() == GeneratedMemberKind.SETTER) {
                emitFacadeSetter(export);
            } else {
                throw invalidPlan(memberSpan(), "unknown facade export member");
            }
        }

        private void emitFacadeFunctionInvocation(GeneratedExportPlan export) {
            IrDeclaration intrinsic = intrinsicDeclaration(export.declarationId());
            FunctionType function = functionBase(declarations.get(export.declarationId()).contract()
                    .orElseThrow().valueType());
            JvmSignaturePlan signature = export.functionSignature().orElseThrow();
            if (intrinsic != null) {
                emitIntrinsicFacadeCall(intrinsic, function, signature);
                returnPhysicalDescriptor(member.descriptor().substring(member.descriptor().indexOf(')') + 1));
                return;
            }
            // The state stores only compiler-emitted or setter-authenticated
            // closures.  The facade still checks lifecycle before loading the
            // state, while the exact interface invocation avoids reparsing the
            // signature and re-authenticating the same closure on every typed
            // Java call.
            loadFacadeState();
            emitStateFunction(facadeStateDeclaration(export));
            if (export.valueType().isNilable()) {
                // A nullable function has a legal null invocation failure;
                // retain the authenticated boundary so it reports LYR-LINK
                // rather than exposing a JVM NullPointerException.  The
                // non-nullable primitive hot path above remains allocation-free.
                loadFacadeState();
                code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                        "$lyra$closureAuthority",
                        method("()L" + RUNTIME + "LyraClosureAuthority;"));
                code.ldc(signature.canonicalLyraSignature());
                code.invokestatic(CD_SIGNATURE, "parse", method(
                        "(Ljava/lang/String;)L" + RUNTIME + "LyraSignature;"));
                code.invokestatic(CD_RUNTIME_CLOSURE_SUPPORT, "requireAuthenticated", method(
                        "(Ljava/lang/Object;L" + RUNTIME + "LyraClosureAuthority;L" + RUNTIME
                                + "LyraSignature;)L" + RUNTIME + "LyraClosure;"));
                code.checkcast(cd(owner.plan.functionInterfaces().get(
                        function.canonicalSpelling())));
            }
            for (int index = 0; index < function.arity(); index++) {
                emitParameterFromFacade(index, function.parameterType(index), signature.parameters().get(index));
            }
            code.invokeinterface(cd(owner.plan.functionInterfaces()
                            .get(function.canonicalSpelling())),
                    "invoke", method(signature.descriptor()));
            // A Unit function invocation is a Java void method.  Unlike an
            // internal Lyra expression, it must not materialize LyraUnit on
            // the operand stack before returning.
            returnPhysicalDescriptor(member.descriptor().substring(member.descriptor().indexOf(')') + 1));
        }

        private void emitIntrinsicFacadeCall(
                IrDeclaration intrinsic, FunctionType function, JvmSignaturePlan signature) {
            emitIoAuthority();
            for (int index = 0; index < function.arity(); index++) {
                emitParameterFromFacade(index, function.parameterType(index),
                        signature.parameters().get(index));
            }
            recordCallFailureFrame(memberSpan(), () -> code.invokestatic(
                    CD_LYRA_IO, intrinsic.name(), method(intrinsicDescriptor(signature))));
        }

        private void emitFacadeFunctionValueGetter(GeneratedExportPlan export) {
            loadFacadeState();
            emitStateFunction(facadeStateDeclaration(export));
            code.areturn();
        }

        private void emitFacadeValueGetter(GeneratedExportPlan export) {
            loadFacadeStateValue(facadeStateDeclaration(export));
            JvmTypePlan internal = owner.mapper.map(
                    declarations.get(export.declarationId()).contract().orElseThrow().valueType(),
                    JvmMappingContext.INTERNAL_VALUE);
            adapt(internal, export.valueType());
            returnPhysicalDescriptor(export.valueType().descriptor());
        }

        private void emitFacadeSetter(GeneratedExportPlan export) {
            if (export.reExport()) {
                emitFacadeReExportSetter(export);
                return;
            }
            if (stateBindingIsCell(export.declarationId())) {
                emitFacadeCellSetter(export);
                return;
            }
            String stateName = owner.plan.moduleStates().get(module.moduleId());
            if (stateName == null) {
                throw invalidPlan(memberSpan(), "facade setter has no module state class");
            }
            JvmType stateType = JvmType.reference(stateName);
            int stateSlot = allocateLocal(stateType);
            loadFacadeState();
            storePhysical(stateType, stateSlot);

            JvmTypePlan internal = owner.mapper.map(
                    declarations.get(export.declarationId()).contract().orElseThrow().valueType(),
                    JvmMappingContext.INTERNAL_BINDING);
            JvmTypePlan external = export.valueType();
            int argument = allocateLocal(external);
            loadParameter(0);
            if (export.isFunction()) {
                // Authenticate the replacement while retaining a balanced
                // stack for the nullable-function case.  The state receiver
                // is reloaded only after authentication, so it cannot be
                // mistaken for the candidate object.
                Label acceptedNull = code.newLabel();
                Label argumentReady = code.newLabel();
                if (export.valueType().isNilable()) {
                    code.dup();
                    code.ifnull(acceptedNull);
                }
                loadPhysical(stateType, stateSlot);
                code.invokevirtual(cd(stateName), "$lyra$closureAuthority",
                        method("()L" + RUNTIME + "LyraClosureAuthority;"));
                code.ldc(export.functionSignature().orElseThrow().canonicalLyraSignature());
                code.invokestatic(CD_SIGNATURE, "parse", method(
                        "(Ljava/lang/String;)L" + RUNTIME + "LyraSignature;"));
                code.invokestatic(CD_RUNTIME_CLOSURE_SUPPORT, "requireAuthenticated", method(
                        "(Ljava/lang/Object;L" + RUNTIME + "LyraClosureAuthority;L" + RUNTIME
                                + "LyraSignature;)L" + RUNTIME + "LyraClosure;"));
                code.checkcast(cd(owner.plan.functionInterfaces().get(
                        functionBase(declarations.get(export.declarationId()).contract().orElseThrow().valueType())
                                .canonicalSpelling())));
                code.goto_(argumentReady);
                if (export.valueType().isNilable()) {
                    code.labelBinding(acceptedNull);
                }
                code.labelBinding(argumentReady);
            }
            storePhysical(external.physicalComponents().getFirst(), argument);
            loadPhysical(stateType, stateSlot);
            loadPhysical(external.physicalComponents().getFirst(), argument);
            if (!export.isFunction()) {
                adapt(external, internal);
            }
            invokeStateSetter(export.declarationId(), member.descriptor());
            code.return_();
        }

        private boolean stateBindingIsCell(DeclarationId id) {
            return stateFields(id).stream()
                    .anyMatch(value -> value.kind() == GeneratedMemberKind.STATE_CELL_FIELD);
        }

        /**
         * A public re-export has an import-state slot rather than a writable
         * field in the facade module.  Route its setter through every
         * re-export hop to the declaration that owns the value, preserving the
         * origin cell and the exact internal presence/payload representation.
         */
        private void emitFacadeReExportSetter(GeneratedExportPlan export) {
            IrDeclaration imported = declarations.get(export.declarationId());
            if (imported == null || imported.contract().isEmpty()) {
                throw invalidPlan(memberSpan(), "re-export setter has no local binding contract");
            }
            JvmTypePlan external = export.isFunction()
                    ? owner.mapper.map(imported.contract().orElseThrow().valueType(),
                    JvmMappingContext.JAVA_FUNCTION_VALUE)
                    : export.valueType();
            JvmTypePlan internal = owner.mapper.map(imported.contract().orElseThrow().valueType(),
                    JvmMappingContext.INTERNAL_BINDING);
            if (!external.isSingleValue()
                    || (!internal.isSingleValue() && !internal.isSplitValue())) {
                throw invalidPlan(memberSpan(), "re-export setter has an invalid value representation");
            }

            int argument = allocateLocal(external);
            loadParameter(0);
            if (export.isFunction()) {
                authenticateFacadeFunctionArgument(export);
            }
            storePhysical(external.physicalComponents().getFirst(), argument);
            loadPhysical(external.physicalComponents().getFirst(), argument);
            adapt(external, internal);
            List<Integer> values = allocateLocals(internal);
            storeLocal(internal, values);

            emitLoadModuleState(module.moduleId());
            emitStoreStateValueThroughImports(module.moduleId(), export.declarationId(),
                    internal, values);
            code.return_();
        }

        private void authenticateFacadeFunctionArgument(GeneratedExportPlan export) {
            Label acceptedNull = code.newLabel();
            Label argumentReady = code.newLabel();
            if (export.valueType().isNilable()) {
                code.dup();
                code.ifnull(acceptedNull);
            }
            loadFacadeState();
            code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                    "$lyra$closureAuthority",
                    method("()L" + RUNTIME + "LyraClosureAuthority;"));
            code.ldc(export.functionSignature().orElseThrow().canonicalLyraSignature());
            code.invokestatic(CD_SIGNATURE, "parse", method(
                    "(Ljava/lang/String;)L" + RUNTIME + "LyraSignature;"));
            code.invokestatic(CD_RUNTIME_CLOSURE_SUPPORT, "requireAuthenticated", method(
                    "(Ljava/lang/Object;L" + RUNTIME + "LyraClosureAuthority;L"
                            + RUNTIME + "LyraSignature;)L" + RUNTIME + "LyraClosure;"));
            code.checkcast(cd(owner.plan.functionInterfaces().get(
                    functionBase(declarations.get(export.declarationId()).contract()
                            .orElseThrow().valueType()).canonicalSpelling())));
            code.goto_(argumentReady);
            if (export.valueType().isNilable()) {
                code.labelBinding(acceptedNull);
            }
            code.labelBinding(argumentReady);
        }

        /** Consumes a state object and follows selective-import state links. */
        private void emitStoreStateValueThroughImports(
                ModuleId stateModule, DeclarationId id, JvmTypePlan storage, List<Integer> values) {
            var declaration = declarations.get(id);
            var access = owner.ir.externalAccess(module.moduleId(),
                    declaration == null ? id : declaration.originDeclaration().orElse(id));
            if (access.isPresent()) {
                if (!access.orElseThrow().writableFacade()) throw invalidPlan(memberSpan(), "import has no facade write contract");
                code.pop();
                emitImportedAccessor(access.orElseThrow(), true);
                var external = owner.mapper.map(access.orElseThrow().target().declaration().contract().orElseThrow().valueType(),
                        JvmMappingContext.JAVA_VALUE);
                for (int index = 0; index < values.size(); index++) {
                    loadPhysical(storage.physicalComponents().get(index), values.get(index));
                }
                adapt(storage, external);
                code.invokevirtual(cd("java.lang.invoke.MethodHandle"), "invokeExact", method("(" + external.descriptor() + ")V"));
                return;
            }
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(stateModule)).orElseThrow(() -> invalidPlan(memberSpan(),
                    "module state is absent: " + stateModule));
            JvmType stateType = JvmType.reference(state.binaryName());
            int stateSlot = allocateLocal(stateType);
            storePhysical(stateType, stateSlot);

            IrImportBinding importBinding = owner.imports.get(id);
            if (importBinding != null) {
                if (importBinding.kind() != ImportBindingKind.SELECTIVE_VALUE
                        || importBinding.targetDeclaration().isEmpty()) {
                    throw invalidPlan(memberSpan(), "re-export setter does not target a value import");
                }
                List<GeneratedMemberPlan> fields = stateFields(stateModule, id);
                GeneratedMemberPlan importField = fields.stream()
                        .filter(value -> value.kind() == GeneratedMemberKind.STATE_IMPORT_FIELD)
                        .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                                "re-export setter import state field is absent"));
                loadPhysical(stateType, stateSlot);
                code.invokevirtual(cd(state.binaryName()),
                        "$lyra$get$binding$" + id.value(),
                        method("()" + importField.descriptor()));
                DeclarationId target = importedStateDeclaration(importBinding);
                emitStoreStateValueThroughImports(importBinding.targetModule(), target,
                        storage, values);
                return;
            }

            List<GeneratedMemberPlan> fields = stateFields(stateModule, id);
            if (fields.stream().anyMatch(value -> value.kind() == GeneratedMemberKind.STATE_CELL_FIELD)) {
                GeneratedMemberPlan cellField = fields.stream()
                        .filter(value -> value.kind() == GeneratedMemberKind.STATE_CELL_FIELD)
                        .findFirst().orElseThrow();
                loadPhysical(stateType, stateSlot);
                code.invokevirtual(cd(state.binaryName()),
                        "$lyra$get$binding$" + id.value(),
                        method("()" + cellField.descriptor()));
                String cellName = cellField.descriptor()
                        .substring(1, cellField.descriptor().length() - 1).replace('/', '.');
                GeneratedClassPlan cell = owner.plan.classPlan(cellName).orElseThrow(() ->
                        invalidPlan(memberSpan(), "re-export setter cell class is absent"));
                GeneratedMemberPlan setter = cell.members().stream()
                        .filter(value -> value.kind() == GeneratedMemberKind.CELL_SET)
                        .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                                "re-export setter cell has no setter"));
                for (int index = 0; index < values.size(); index++) {
                    loadPhysical(storage.physicalComponents().get(index), values.get(index));
                }
                code.invokevirtual(cd(cell.binaryName()), setter.name(), method(setter.descriptor()));
                return;
            }

            GeneratedMemberPlan setter = state.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.STATE_COMPONENT_SET)
                    .filter(value -> value.name().equals("$lyra$set$binding$" + id.value()))
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                            "re-export setter has no writable state binding"));
            if (setter.descriptor().equals("()V")) {
                throw invalidPlan(memberSpan(), "re-export state setter has no parameters");
            }
            loadPhysical(stateType, stateSlot);
            for (int index = 0; index < values.size(); index++) {
                loadPhysical(storage.physicalComponents().get(index), values.get(index));
            }
            code.invokevirtual(cd(state.binaryName()), setter.name(), method(setter.descriptor()));
        }

        private DeclarationId importedStateDeclaration(IrImportBinding binding) {
            return owner.ir.exports().stream()
                    .filter(export -> export.moduleId().equals(binding.targetModule()))
                    .filter(export -> binding.targetExport()
                            .map(target -> export.exportId().equals(Optional.of(target)))
                            .orElseGet(() -> export.name().equals(
                                    binding.importedName().orElseThrow())))
                    .map(IrExport::declarationId)
                    .findFirst()
                    .orElseThrow(() -> invalidPlan(memberSpan(),
                            "re-export setter target export is absent: " + binding.targetModule()));
        }

        private void emitFacadeCellSetter(GeneratedExportPlan export) {
            JvmTypePlan external = export.valueType();
            JvmTypePlan internal = owner.mapper.map(
                    declarations.get(export.declarationId()).contract().orElseThrow().valueType(),
                    JvmMappingContext.INTERNAL_BINDING);
            int argument = allocateLocal(external);
            loadParameter(0);
            if (export.isFunction()) {
                Label acceptedNull = code.newLabel();
                Label argumentReady = code.newLabel();
                if (export.valueType().isNilable()) {
                    code.dup();
                    code.ifnull(acceptedNull);
                }
                loadFacadeState();
                code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                        "$lyra$closureAuthority",
                        method("()L" + RUNTIME + "LyraClosureAuthority;"));
                code.ldc(export.functionSignature().orElseThrow().canonicalLyraSignature());
                code.invokestatic(CD_SIGNATURE, "parse", method(
                        "(Ljava/lang/String;)L" + RUNTIME + "LyraSignature;"));
                code.invokestatic(CD_RUNTIME_CLOSURE_SUPPORT, "requireAuthenticated", method(
                        "(Ljava/lang/Object;L" + RUNTIME + "LyraClosureAuthority;L" + RUNTIME
                                + "LyraSignature;)L" + RUNTIME + "LyraClosure;"));
                code.checkcast(cd(owner.plan.functionInterfaces().get(functionBase(
                        declarations.get(export.declarationId()).contract().orElseThrow().valueType())
                        .canonicalSpelling())));
                code.goto_(argumentReady);
                if (export.valueType().isNilable()) {
                    code.labelBinding(acceptedNull);
                }
                code.labelBinding(argumentReady);
            }
            storePhysical(external.physicalComponents().getFirst(), argument);
            loadFacadeState();
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(module.moduleId())).orElseThrow();
            GeneratedMemberPlan field = stateFields(export.declarationId()).stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.STATE_CELL_FIELD)
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                            "mutable export has no state cell field"));
            code.invokevirtual(cd(state.binaryName()),
                    "$lyra$get$binding$" + export.declarationId().value(),
                    method("()" + field.descriptor()));
            loadPhysical(external.physicalComponents().getFirst(), argument);
            adapt(external, internal);
            GeneratedClassPlan cell = owner.plan.classPlan(field.descriptor()
                    .substring(1, field.descriptor().length() - 1).replace('/', '.')).orElseThrow();
            GeneratedMemberPlan setter = cell.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.CELL_SET)
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(),
                            "mutable export cell has no setter"));
            code.invokevirtual(cd(cell.binaryName()), setter.name(), method(setter.descriptor()));
            code.return_();
        }

        private void emitParameterFromFacade(int index, LyraType logical, JvmTypePlan target) {
            loadParameter(index);
            JvmTypePlan source = owner.mapper.map(logical, JvmMappingContext.JAVA_PARAMETER);
            adapt(source, target);
        }

        private void authenticateRange(LyraType logical) {
            var range = (io.mindspice.lyra.compiler.types.RangeType) logical.withoutQualifiers();
            emitInt(((PrimitiveType) range.elementType()).numericDomain().orElseThrow().bitWidth());
            emitInt(logical.isNilable() ? 1 : 0);
            code.invokestatic(cd("io.mindspice.lyra.runtime.LyraRange"), "requireWidth",
                    method("(L" + RUNTIME + "LyraRange;IZ)L" + RUNTIME + "LyraRange;"));
        }

        private void loadFacadeState() {
            GeneratedMemberPlan state = classPlan.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.FACADE_STATE_FIELD)
                    .findFirst().orElseThrow(() -> invalidPlan(memberSpan(), "facade has no state field"));
            aloadReceiver();
            code.getfield(cd(classPlan.binaryName()), state.name(), type(state.descriptor()));
        }

        private void checkFacadeStateOpen() {
            loadFacadeState();
            code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                    "$lyra$checkOpen", method("()V"));
        }

        private void loadFacadeStateValue(DeclarationId id) {
            loadFacadeState();
            emitStateFunctionOrValue(id);
        }

        private DeclarationId facadeStateDeclaration(GeneratedExportPlan export) {
            if (!export.reExport()) {
                return export.declarationId();
            }
            return owner.imports.values().stream()
                    .filter(binding -> declarations.get(binding.declarationId()) != null)
                    .filter(binding -> declarations.get(binding.declarationId()).moduleId()
                            .equals(module.moduleId()))
                    .filter(binding -> binding.reExport()
                            && binding.localName().equals(export.sourceName())
                            && binding.targetDeclaration().equals(Optional.of(export.originDeclaration())))
                    .map(IrImportBinding::declarationId)
                    .findFirst()
                    .orElseThrow(() -> invalidPlan(memberSpan(),
                            "re-export has no local state binding: " + export.sourceName()));
        }

        private void emitStateFunction(DeclarationId id) {
            emitStateFunctionOrValue(id);
        }

        /**
         * Loads a value from a module state already on the operand stack.  An
         * imported binding contributes another state object, so follow the
         * chain until its origin declaration rather than treating a
         * re-export's origin identity as a field in the facade module.
         */
        private void emitStateFunctionOrValue(DeclarationId id) {
            emitStateValueFromState(module.moduleId(), id);
        }

        private void emitStateValueFromState(ModuleId stateModule, DeclarationId id) {
            var metadata = declarations.get(id);
            var origin = metadata == null ? id : metadata.originDeclaration().orElse(id);
            if (owner.ir.externalAccess(module.moduleId(), origin).isPresent()) {
                code.pop();
                emitImportedRead(origin, storagePlan(id));
                return;
            }
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(stateModule)).orElseThrow(() -> invalidPlan(memberSpan(),
                    "module state is absent: " + stateModule));
            JvmType stateType = JvmType.reference(state.binaryName());
            int stateSlot = allocateLocal(stateType);
            storePhysical(stateType, stateSlot);

            IrImportBinding importBinding = owner.imports.get(id);
            if (importBinding != null) {
                IrDeclaration declaration = declarations.get(id);
                if (!importBinding.targetDeclaration().isPresent()
                        || declaration == null || !declaration.moduleId().equals(stateModule)) {
                    throw invalidPlan(memberSpan(), "state import is not a value binding: " + id);
                }
                List<GeneratedMemberPlan> fields = stateFields(stateModule, id);
                if (fields.size() != 1
                        || fields.getFirst().kind() != GeneratedMemberKind.STATE_IMPORT_FIELD) {
                    throw invalidPlan(memberSpan(), "import binding has an invalid state shape");
                }
                loadPhysical(stateType, stateSlot);
                code.invokevirtual(cd(state.binaryName()),
                        "$lyra$get$binding$" + id.value(),
                        method("()" + fields.getFirst().descriptor()));
                DeclarationId targetDeclaration = owner.ir.exports().stream()
                        .filter(export -> export.moduleId().equals(importBinding.targetModule()))
                        .filter(export -> importBinding.targetExport()
                                .map(target -> export.exportId().equals(Optional.of(target)))
                                .orElseGet(() -> export.name().equals(
                                        importBinding.importedName().orElseThrow())))
                        .map(IrExport::declarationId)
                        .findFirst()
                        .orElseThrow(() -> invalidPlan(memberSpan(),
                                "import target export is absent: " + importBinding.targetModule()));
                IrDeclaration targetMetadata = declarations.get(targetDeclaration);
                if (targetMetadata == null || targetMetadata.contract().isEmpty()) {
                    throw invalidPlan(memberSpan(), "import target declaration is absent: " + targetDeclaration);
                }
                if (!(targetMetadata.contract().orElseThrow().valueType().withoutQualifiers()
                        instanceof FunctionType)) {
                    GeneratedClassPlan targetState = owner.plan.classPlan(owner.plan.moduleStates()
                            .get(importBinding.targetModule())).orElseThrow(() -> invalidPlan(memberSpan(),
                            "import target state is absent: " + importBinding.targetModule()));
                    // A re-export can hide the state that actually owns the
                    // value. Open that state before following the link; the
                    // semantic schedule normally makes this redundant, but
                    // this preserves eager initialization when the
                    // intermediate module only re-exports the declaration.
                    code.dup();
                    code.invokevirtual(cd(targetState.binaryName()), "$lyra$checkOpen", method("()V"));
                }
                emitStateValueFromState(importBinding.targetModule(), targetDeclaration);
                return;
            }

            List<GeneratedMemberPlan> fields = stateFields(stateModule, id);
            if (fields.isEmpty()) {
                throw invalidPlan(memberSpan(), "state has no declaration field: " + id);
            }
            if (fields.size() == 1) {
                GeneratedMemberPlan field = fields.getFirst();
                loadPhysical(stateType, stateSlot);
                code.invokevirtual(cd(state.binaryName()),
                        "$lyra$get$binding$" + id.value(),
                        method("()" + field.descriptor()));
                if (field.kind() == GeneratedMemberKind.STATE_CELL_FIELD) {
                    emitCellValue(id);
                }
                return;
            }
            if (fields.size() == 2) {
                loadPhysical(stateType, stateSlot);
                code.invokevirtual(cd(state.binaryName()),
                        "$lyra$isPresent$binding$" + id.value(), method("()Z"));
                loadPhysical(stateType, stateSlot);
                GeneratedMemberPlan payload = fields.stream()
                        .filter(value -> value.name().endsWith("$payload"))
                        .findFirst().orElseThrow();
                code.invokevirtual(cd(state.binaryName()),
                        "$lyra$payload$binding$" + id.value(),
                        method("()" + payload.descriptor()));
                return;
            }
            throw invalidPlan(memberSpan(), "state has an unsupported value storage shape");
        }

        private void invokeStateSetter(DeclarationId id, String ignoredDescriptor) {
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(module.moduleId())).orElseThrow();
            List<GeneratedMemberPlan> fields = stateFields(id);
            String setter = "$lyra$set$binding$" + id.value();
            String descriptor = "(" + fields.stream().map(GeneratedMemberPlan::descriptor)
                    .reduce("", String::concat) + ")V";
            code.invokevirtual(cd(state.binaryName()), setter, method(descriptor));
        }

        private JvmTypePlan emitNode(IrNode node) {
            line(node.span());
            if (node instanceof IrNode.Constant constant) {
                return emitConstant(constant);
            }
            if (node instanceof IrNode.Reference reference) {
                return emitReference(reference);
            }
            if (node instanceof IrNode.CaptureReference reference) {
                return emitCaptureReference(reference);
            }
            if (node instanceof IrNode.Declaration declaration) {
                return emitDeclaration(declaration);
            }
            if (node instanceof IrNode.Rebinding rebinding) {
                return emitRebinding(rebinding);
            }
            if (node instanceof IrNode.Sequence sequence) {
                return emitSequence(sequence.forms(), sequence.type());
            }
            if (node instanceof IrNode.Block block) {
                Map<DeclarationId, BindingStorage> saved = new HashMap<>(locals);
                Map<DeclarationId, Integer> savedCells = new HashMap<>(localCells);
                Map<DeclarationId, Integer> savedFunctionSlots =
                        new HashMap<>(localFunctionSlots);
                JvmTypePlan result = emitSequence(block.forms(), block.type());
                locals.clear();
                locals.putAll(saved);
                localCells.clear();
                localCells.putAll(savedCells);
                localFunctionSlots.clear();
                localFunctionSlots.putAll(savedFunctionSlots);
                return result;
            }
            if (node instanceof IrNode.ArrayLiteral array) {
                return emitArrayLiteral(array);
            }
            if (node instanceof IrNode.TupleLiteral tuple) {
                return emitTupleLiteral(tuple);
            }
            if (node instanceof IrNode.Range range) {
                return emitRange(range);
            }
            if (node instanceof IrNode.Loop loop) {
                return emitLoop(loop);
            }
            if (node instanceof IrNode.IndexAccess index) {
                return emitIndexAccess(index);
            }
            if (node instanceof IrNode.Operator operator) {
                return emitOperator(operator);
            }
            if (node instanceof IrNode.ShortCircuit shortCircuit) {
                emitShortCircuit(shortCircuit);
                return owner.mapper.map(shortCircuit.type(), JvmMappingContext.INTERNAL_VALUE);
            }
            if (node instanceof IrNode.Conversion conversion) {
                return emitConversion(conversion);
            }
            if (node instanceof IrNode.Narrowing narrowing) {
                return emitNarrowing(narrowing);
            }
            if (node instanceof IrNode.Branch branch) {
                return emitBranch(branch);
            }
            if (node instanceof IrNode.Coalesce coalesce) {
                return emitCoalesce(coalesce);
            }
            if (node instanceof IrNode.Match match) {
                return emitMatch(match);
            }
            if (node instanceof IrNode.DirectCall call) {
                return emitDirectCall(call);
            }
            if (node instanceof IrNode.CallableCall call) {
                return emitCallableCall(call);
            }
            if (node instanceof IrNode.Lambda lambdaNode) {
                return emitLambda(lambdaNode);
            }
            if (node instanceof IrNode.Access access) {
                return emitAccess(access);
            }
            if (node instanceof IrNode.RuntimeCheck check) {
                return emitRuntimeCheck(check);
            }
            throw unsupported(node.span(), "unhandled IR node: " + node.getClass().getName());
        }

        private JvmTypePlan emitRange(IrNode.Range range) {
            var rangeType = (io.mindspice.lyra.compiler.types.RangeType) range.type();
            int bits = ((PrimitiveType) rangeType.elementType()).numericDomain().orElseThrow().bitWidth();
            List<Integer> slots = new ArrayList<>();
            for (IrNode bound : range.childrenInEvaluationOrder()) {
                emitNode(bound);
                if (bits != 64) {
                    code.i2l();
                }
                int slot = allocateLocal(JvmType.primitive("J"));
                code.lstore(slot);
                slots.add(slot);
            }
            Label nonzero = code.newLabel();
            code.lload(slots.get(2)).lconst_0().lcmp().ifne(nonzero);
            throwFailure(range.span(), "LYR-ARITH", "a range step must not be zero");
            code.labelBinding(nonzero);
            code.new_(cd("io.mindspice.lyra.runtime.LyraRange"));
            code.dup();
            for (int slot : slots) {
                code.lload(slot);
            }
            emitInt(range.inclusive() ? 1 : 0);
            emitInt(bits);
            code.invokespecial(cd("io.mindspice.lyra.runtime.LyraRange"), "<init>", method("(JJJZI)V"));
            return owner.mapper.map(range.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private JvmTypePlan emitLoop(IrNode.Loop loop) {
            // Retain both argument values before entering the loop. Authentication
            // belongs to the selected value, not to every repeated invocation.
            JvmTypePlan inputPlan = emitNode(loop.input());
            if (!loop.conditionControlled()) authenticateRange(loop.input().type());
            int input = allocateLocal(inputPlan.physicalComponents().getFirst());
            code.astore(input);
            FunctionType actionType = functionBase(loop.action().type());
            JvmTypePlan actionPlan = emitNode(loop.action());
            int action = allocateLocal(actionPlan.physicalComponents().getFirst());
            code.astore(action);
            code.aload(action);
            recordCallFailureFrame(loop.span(), () -> authenticateGeneratedFunctionValue(actionType));
            code.astore(action);
            var start = code.newLabel();
            var done = code.newLabel();
            if (loop.conditionControlled()) {
                FunctionType predicate = functionBase(loop.input().type());
                code.aload(input);
                recordCallFailureFrame(loop.span(), () -> authenticateGeneratedFunctionValue(predicate));
                code.astore(input);
                code.labelBinding(start);
                emitOwnerSafePoint();
                code.aload(input);
                emitLoopInvocation(predicate, loop.span());
                code.ifeq(done);
                code.aload(action);
                emitLoopInvocation(actionType, loop.span());
                code.goto_(start);
            } else {
                ClassDesc rangeClass = cd("io.mindspice.lyra.runtime.LyraRange");
                int cursor = allocateLocal(JvmType.primitive("J"));
                int step = allocateLocal(JvmType.primitive("J"));
                code.aload(input).invokevirtual(rangeClass, "start", method("()J")).lstore(cursor);
                code.aload(input).invokevirtual(rangeClass, "step", method("()J")).lstore(step);
                code.aload(input).invokevirtual(rangeClass, "isEmpty", method("()Z")).ifne(done);
                code.labelBinding(start);
                emitOwnerSafePoint();
                code.aload(action);
                if (actionType.arity() == 1) {
                    code.lload(cursor);
                    if (actionType.parameterType(0) != PrimitiveType.I64) code.l2i();
                }
                emitLoopInvocation(actionType, loop.span());
                code.aload(input).lload(cursor)
                        .invokevirtual(rangeClass, "hasSuccessor", method("(J)Z")).ifeq(done);
                code.lload(cursor).lload(step).ladd().lstore(cursor);
                code.goto_(start);
            }
            code.labelBinding(done);
            emitUnit();
            return owner.mapper.map(loop.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private void emitLoopInvocation(FunctionType function, SourceSpan span) {
            JvmSignaturePlan signature = owner.mapper.mapSignature(function.signature(), JvmAbiBoundary.JAVA_VISIBLE);
            recordCallFailureFrame(span, () -> code.invokeinterface(
                    cd(owner.plan.functionInterfaces().get(function.canonicalSpelling())),
                    "invoke", method(signature.descriptor())));
        }

        private JvmTypePlan emitArrayLiteral(IrNode.ArrayLiteral array) {
            LyraType arrayBase = array.type().withoutQualifiers();
            if (!(arrayBase instanceof ArrayType)) {
                throw invalidPlan(array.span(), "array literal has a non-array type");
            }
            ArrayType arrayType = (ArrayType) arrayBase;
            JvmTypePlan arrayPlan = owner.mapper.map(array.type(), JvmMappingContext.INTERNAL_VALUE);
            JvmTypePlan elementPlan = owner.mapper.map(arrayType.elementType(),
                    JvmMappingContext.INTERNAL_ARRAY_ELEMENT);
            if (!arrayPlan.isSingleValue() || !arrayPlan.physicalComponents().getFirst().isReference()
                    || !elementPlan.isSingleValue() || elementPlan.descriptor().equals("V")) {
                throw invalidPlan(array.span(), "array literal has an invalid JVM representation");
            }
            emitInt(array.elements().size());
            String elementDescriptor = elementPlan.descriptor();
            if (elementDescriptor.charAt(0) == 'L' || elementDescriptor.charAt(0) == '[') {
                code.anewarray(type(elementDescriptor));
            } else {
                code.newarray(typeKind(elementDescriptor));
            }
            int arraySlot = allocateLocal(arrayPlan.physicalComponents().getFirst());
            storePhysical(arrayPlan.physicalComponents().getFirst(), arraySlot);
            for (int index = 0; index < array.elements().size(); index++) {
                loadPhysical(arrayPlan.physicalComponents().getFirst(), arraySlot);
                emitInt(index);
                emitAt(array.elements().get(index), elementPlan);
                code.arrayStore(typeKind(elementDescriptor));
            }
            loadPhysical(arrayPlan.physicalComponents().getFirst(), arraySlot);
            return arrayPlan;
        }

        private JvmTypePlan emitTupleLiteral(IrNode.TupleLiteral tupleNode) {
            LyraType tupleBase = tupleNode.type().withoutQualifiers();
            if (!(tupleBase instanceof TupleType)) {
                throw invalidPlan(tupleNode.span(), "tuple literal has a non-tuple type");
            }
            TupleType tuple = (TupleType) tupleBase;
            if (tuple.arity() != tupleNode.elements().size()) {
                throw invalidPlan(tupleNode.span(), "tuple literal arity disagrees with its type");
            }
            JvmTypePlan tuplePlan = owner.mapper.map(tupleNode.type(), JvmMappingContext.INTERNAL_VALUE);
            String tupleName = owner.mapper.names().tupleBinaryName(tuple.canonicalSpelling());
            GeneratedClassPlan tupleClass = owner.plan.classPlan(tupleName).orElseThrow(() ->
                    invalidPlan(tupleNode.span(), "tuple class is absent from the generated plan"));
            GeneratedMemberPlan constructor = tupleClass.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.TUPLE_CONSTRUCTOR)
                    .findFirst().orElseThrow(() -> invalidPlan(tupleNode.span(),
                            "tuple constructor is absent from the generated plan"));
            List<JvmTypePlan> fields = new ArrayList<>(tuple.arity());
            List<List<Integer>> fieldSlots = new ArrayList<>(tuple.arity());
            for (int index = 0; index < tuple.arity(); index++) {
                JvmTypePlan field = owner.mapper.map(
                        tuple.memberType(index), JvmMappingContext.TUPLE_FIELD);
                if (!field.isSingleValue() || field.descriptor().equals("V")) {
                    throw invalidPlan(tupleNode.span(),
                            "tuple field has no single JVM representation");
                }
                emitAt(tupleNode.elements().get(index), field);
                List<Integer> slots = allocateLocals(field);
                storeLocal(field, slots);
                fields.add(field);
                fieldSlots.add(slots);
            }
            code.new_(cd(tupleName));
            code.dup();
            for (int index = 0; index < fields.size(); index++) {
                loadLocal(BindingStorage.local(
                        tuple.memberType(index), fields.get(index), fieldSlots.get(index)));
            }
            code.invokespecial(cd(tupleName), "<init>", method(constructor.descriptor()));
            return tuplePlan;
        }

        private JvmTypePlan emitIndexAccess(IrNode.IndexAccess index) {
            IrFailureSite failure = activeFailureSites.peek();
            if (failure == null || failure.checkKind() != IrCheckKind.BOUNDS
                    || !failure.failureCode().equals("LYR-BOUNDS")) {
                throw invalidPlan(index.span(), "index access has no active bounds failure site");
            }
            JvmTypePlan receiverPlan = owner.mapper.map(index.receiver().type(),
                    JvmMappingContext.INTERNAL_VALUE);
            if (!receiverPlan.isSingleValue() || !receiverPlan.physicalComponents().getFirst().isReference()) {
                throw invalidPlan(index.span(), "index receiver has no JVM reference representation");
            }
            JvmTypePlan indexPlan = owner.mapper.map(index.index().type(),
                    JvmMappingContext.INTERNAL_VALUE);
            if (!indexPlan.isSingleValue() || !indexPlan.physicalComponents().getFirst().isPrimitive()) {
                throw invalidPlan(index.span(), "index has no JVM integer representation");
            }
            int receiverSlot = allocateLocal(receiverPlan.physicalComponents().getFirst());
            emitAt(index.receiver(), receiverPlan);
            storePhysical(receiverPlan.physicalComponents().getFirst(), receiverSlot);
            List<Integer> indexSlots = allocateLocals(indexPlan);
            emitAt(index.index(), indexPlan);
            PrimitiveType indexPrimitive = primitiveBase(index.index().type());
            if (indexPrimitive == PrimitiveType.U8 || indexPrimitive == PrimitiveType.U16) {
                normalizeUnsigned(indexPrimitive);
            }
            storeLocal(indexPlan, indexSlots);

            Label boundsFailure = failureLabel(index.span(), failure.failureCode(),
                    "index is outside the aggregate bounds");
            LyraType receiverType = index.receiver().type().withoutQualifiers();
            emitIndexBoundsCheck(indexPlan, indexSlots, receiverSlot, boundsFailure,
                    indexPrimitive, receiverPlan.physicalComponents().getFirst(),
                    receiverType == PrimitiveType.STRING);

            loadPhysical(receiverPlan.physicalComponents().getFirst(), receiverSlot);
            loadIndexForJvm(indexPlan, indexSlots);
            JvmTypePlan resultPlan;
            if (receiverType == PrimitiveType.STRING) {
                code.invokevirtual(CD_STRING, "charAt", method("(I)C"));
                resultPlan = owner.mapper.map(PrimitiveType.CHAR,
                        JvmMappingContext.INTERNAL_ARRAY_ELEMENT);
            } else if (receiverType instanceof ArrayType arrayType) {
                resultPlan = owner.mapper.map(arrayType.elementType(),
                        JvmMappingContext.INTERNAL_ARRAY_ELEMENT);
                code.arrayLoad(typeKind(resultPlan.descriptor()));
                LyraType elementBase = arrayType.elementType().withoutQualifiers();
                if (resultPlan.isSingleValue() && elementBase instanceof PrimitiveType primitive
                        && (primitive == PrimitiveType.U8 || primitive == PrimitiveType.U16)) {
                    normalizeUnsigned(primitive);
                }
            } else {
                throw invalidPlan(index.span(), "index receiver is neither String nor Array");
            }
            JvmTypePlan desired = owner.mapper.map(index.type(), JvmMappingContext.INTERNAL_VALUE);
            adapt(resultPlan, desired);
            return desired;
        }

        private void emitIndexBoundsCheck(JvmTypePlan indexPlan, List<Integer> indexSlots,
                                          int receiverSlot, Label failure,
                                          PrimitiveType indexType, JvmType receiverPhysical,
                                          boolean stringReceiver) {
            String descriptor = indexPlan.physicalComponents().getFirst().descriptor();
            if (indexType == PrimitiveType.U64) {
                loadPhysical(indexPlan.physicalComponents().getFirst(), indexSlots.getFirst());
                loadLength(receiverSlot, receiverPhysical, stringReceiver);
                code.i2l();
                code.invokestatic(CD_LONG, "compareUnsigned", method("(JJ)I"));
                code.ifge(failure);
                return;
            }
            if (indexType == PrimitiveType.U32) {
                loadPhysical(indexPlan.physicalComponents().getFirst(), indexSlots.getFirst());
                loadLength(receiverSlot, receiverPhysical, stringReceiver);
                code.invokestatic(CD_INTEGER, "compareUnsigned", method("(II)I"));
                code.ifge(failure);
                return;
            }
            if (descriptor.equals("J")) {
                loadPhysical(indexPlan.physicalComponents().getFirst(), indexSlots.getFirst());
                code.lconst_0();
                code.lcmp();
                code.iflt(failure);
                loadPhysical(indexPlan.physicalComponents().getFirst(), indexSlots.getFirst());
                loadLength(receiverSlot, receiverPhysical, stringReceiver);
                code.i2l();
                code.lcmp();
                code.ifge(failure);
                return;
            }
            loadPhysical(indexPlan.physicalComponents().getFirst(), indexSlots.getFirst());
            code.iflt(failure);
            loadPhysical(indexPlan.physicalComponents().getFirst(), indexSlots.getFirst());
            loadLength(receiverSlot, receiverPhysical, stringReceiver);
            code.if_icmpge(failure);
        }

        private void loadLength(int receiverSlot, JvmType receiverPhysical,
                                boolean stringReceiver) {
            loadPhysical(receiverPhysical, receiverSlot);
            if (stringReceiver) {
                code.invokevirtual(CD_STRING, "length", method("()I"));
            } else {
                code.arraylength();
            }
        }

        private void loadIndexForJvm(JvmTypePlan indexPlan, List<Integer> slots) {
            loadPhysical(indexPlan.physicalComponents().getFirst(), slots.getFirst());
            if (!indexPlan.physicalComponents().getFirst().descriptor().equals("I")) {
                emitRawConversion(indexPlan.physicalComponents().getFirst().descriptor(), "I");
            }
        }

        private JvmTypePlan emitConstant(IrNode.Constant constant) {
            JvmTypePlan target = owner.mapper.map(constant.type(), JvmMappingContext.INTERNAL_VALUE);
            IrConstantValue value = constant.value();
            if (value instanceof IrConstantValue.NilValue) {
                if (target.isSplitValue()) {
                    code.iconst_0();
                    emitZero(target.physicalComponents().get(1));
                } else {
                    code.aconst_null();
                }
                return target;
            }
            if (value instanceof IrConstantValue.BooleanValue booleanValue) {
                emitInt(booleanValue.value() ? 1 : 0);
                return target;
            }
            if (value instanceof IrConstantValue.IntegerValue integerValue) {
                emitIntegerConstant(integerValue.exactValue(), primitiveBase(constant.type()));
                if (target.isSplitValue()) {
                    // The constant was emitted as a payload; move it below a
                    // present bit without changing the exact numeric value.
                    int payload = allocateLocal(target.physicalComponents().get(1));
                    storePhysical(target.physicalComponents().get(1), payload);
                    code.iconst_1();
                    loadPhysical(target.physicalComponents().get(1), payload);
                }
                return target;
            }
            if (value instanceof IrConstantValue.DecimalValue decimalValue) {
                emitDecimalConstant(decimalValue.exactValue(), primitiveBase(constant.type()));
                if (target.isSplitValue()) {
                    int payload = allocateLocal(target.physicalComponents().get(1));
                    storePhysical(target.physicalComponents().get(1), payload);
                    code.iconst_1();
                    loadPhysical(target.physicalComponents().get(1), payload);
                }
                return target;
            }
            if (value instanceof IrConstantValue.StringValue stringValue) {
                code.ldc(stringValue.value());
                return target;
            }
            if (value instanceof IrConstantValue.CharacterValue characterValue) {
                emitInt(characterValue.value());
                return target;
            }
            if (value instanceof IrConstantValue.UnitValue) {
                emitUnit();
                return target;
            }
            throw unsupported(constant.span(), "unknown constant value");
        }

        private void emitIntegerConstant(ExactNumericLiteral literal, PrimitiveType type) {
            BigInteger value = literal.integerValue();
            if (type == PrimitiveType.I64 || type == PrimitiveType.U64) {
                code.loadConstant(value.longValue());
            } else {
                code.loadConstant(value.intValue());
            }
        }

        private void emitDecimalConstant(ExactNumericLiteral literal, PrimitiveType type) {
            BigDecimal value = literal.decimalValue();
            if (type == PrimitiveType.F32) {
                code.loadConstant(value.floatValue());
            } else {
                code.loadConstant(value.doubleValue());
            }
        }

        private JvmTypePlan emitReference(IrNode.Reference reference) {
            DeclarationId id = reference.targetDeclaration().orElseThrow(() ->
                    invalidPlan(reference.span(), "reference has no declaration identity"));
            BindingStorage local = locals.get(id);
            JvmTypePlan desired = owner.mapper.map(reference.type(), JvmMappingContext.INTERNAL_VALUE);
            if (local != null) {
                loadLocal(local);
                adapt(local.physical(), desired);
                return desired;
            }
            emitLoadDeclaration(id, desired);
            return desired;
        }

        private JvmTypePlan emitCaptureReference(IrNode.CaptureReference reference) {
            CaptureId id = reference.captureId().orElseThrow(() ->
                    invalidPlan(reference.span(), "capture reference has no capture identity"));
            IrCapture capture = captures.get(id);
            if (capture == null || !closureMethod) {
                throw invalidPlan(reference.span(), "capture reference has no current closure storage");
            }
            JvmTypePlan desired = owner.mapper.map(reference.type(), JvmMappingContext.INTERNAL_VALUE);
            if (capture.isSharedCell()) {
                emitLoadClosureCaptureCell(id);
                emitCellValue(capture.declarationId());
            } else if (owner.usesLocalFunctionSlot(capture)) {
                emitLoadClosureFunctionSlot(id);
                emitInt(0);
                code.aaload();
            } else {
                emitLoadClosureCaptureValue(id, capture.contract().valueType());
            }
            JvmTypePlan source = owner.mapper.map(capture.contract().valueType(),
                    JvmMappingContext.INTERNAL_CAPTURE);
            adapt(source, desired);
            return desired;
        }

        private JvmTypePlan emitDeclaration(IrNode.Declaration declaration) {
            DeclarationId id = declaration.declarationId().orElseThrow(() ->
                    invalidPlan(declaration.span(), "declaration has no identity"));
            IrDeclaration metadata = declarations.get(id);
            if (metadata == null) {
                throw invalidPlan(declaration.span(), "declaration identity is absent");
            }
            BindingContract contract = metadata.contract().orElseThrow(() ->
                    unsupported(declaration.span(), "untyped declaration reached bytecode emission"));
            JvmTypePlan storage = owner.mapper.mapBinding(contract).value();
            JvmTypePlan value = emitNode(declaration.initializer());
            adapt(value, storage);
            if (stateMethod && isRootDeclaration(id)) {
                storeStateDeclaration(id, storage);
            } else if (owner.cells.containsKey(id)) {
                if (localCells.containsKey(id)) {
                    emitStoreCell(id, storage);
                } else {
                    List<Integer> slots = allocateLocals(storage);
                    storeLocal(storage, slots);
                    emitNewCell(id, slots);
                    int cellSlot = allocateLocal(JvmType.reference(cellClassName(id)));
                    code.astore(cellSlot);
                    localCells.put(id, cellSlot);
                }
                if (isLocalFunctionDeclaration(id)) {
                    initializedLocalFunctions.add(id);
                }
            } else {
                List<Integer> slots = allocateLocals(storage);
                storeLocal(storage, slots);
                locals.put(id, BindingStorage.local(contract.valueType(), storage, slots));
                if (isLocalFunctionDeclaration(id)) {
                    initializedLocalFunctions.add(id);
                }
                Integer functionSlot = localFunctionSlots.get(id);
                if (functionSlot != null) {
                    if (!storage.isSingleValue()
                            || !storage.physicalComponents().getFirst().isReference()) {
                        throw invalidPlan(declaration.span(),
                                "local function linkage has a non-reference value");
                    }
                    code.aload(functionSlot);
                    emitInt(0);
                    loadPhysical(storage.physicalComponents().getFirst(), slots.getFirst());
                    code.aastore();
                }
            }
            emitUnit();
            return owner.mapper.map(declaration.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private boolean isRootDeclaration(DeclarationId id) {
            return module.state().rootScope().equals(declarations.get(id).scopeId());
        }

        private JvmTypePlan emitRebinding(IrNode.Rebinding rebinding) {
            DeclarationId id = rebinding.targetDeclaration().orElseThrow(() ->
                    invalidPlan(rebinding.span(), "rebinding has no target declaration"));
            if (rebinding.route().isRoot()) {
                if (!(rootReferenceNode(rebinding.target()) instanceof IrNode.Reference)
                        && !(rootReferenceNode(rebinding.target()) instanceof IrNode.CaptureReference)) {
                    throw unsupported(rebinding.span(), "scalar rebinding target is not a binding reference");
                }
                // A scalar target has no observable load effect.  The IR still
                // records it as the first evaluation child; the value is then
                // evaluated and stored exactly once.
                JvmTypePlan storage = storagePlan(id);
                JvmTypePlan value = emitNode(rebinding.value());
                adapt(value, storage);
                storeDeclaration(id, storage);
            } else {
                if (rebinding.mutationKind().filter(io.mindspice.lyra.compiler.semantic.MutationKind.ARRAY_ELEMENT::equals)
                        .isEmpty()) {
                    throw unsupported(rebinding.span(), "aggregate rebinding is not an array-element mutation");
                }
                ArrayStoreTarget target = emitArrayStoreTarget(rebinding.target());
                JvmTypePlan value = emitNode(rebinding.value());
                adapt(value, target.elementPlan());
                List<Integer> valueSlots = allocateLocals(target.elementPlan());
                storeLocal(target.elementPlan(), valueSlots);
                loadPhysical(target.receiverPlan().physicalComponents().getFirst(), target.receiverSlot());
                loadIndexForJvm(target.indexPlan(), target.indexSlots());
                loadPhysical(target.elementPlan().physicalComponents().getFirst(), valueSlots.getFirst());
                code.arrayStore(typeKind(target.elementPlan().descriptor()));
            }
            emitUnit();
            return owner.mapper.map(rebinding.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        /** Emits an aggregate assignment target without reading its final element. */
        private ArrayStoreTarget emitArrayStoreTarget(IrNode node) {
            if (node instanceof IrNode.RuntimeCheck check) {
                FlowSiteId siteId = check.failureSiteId().orElseThrow(() ->
                        invalidPlan(check.span(), "array assignment check has no failure site"));
                IrFailureSite failure = owner.failureSites.get(siteId);
                if (failure == null || failure.checkKind() != check.checkKind()
                        || check.checkKind() != IrCheckKind.BOUNDS
                        || !failure.failureCode().equals(check.failureCode())
                        || !"LYR-BOUNDS".equals(check.failureCode())
                        || !failure.span().equals(check.span())) {
                    throw invalidPlan(check.span(), "array assignment check disagrees with its failure site");
                }
                activeFailureSites.push(failure);
                try {
                    return emitArrayStoreTarget(check.operand());
                } finally {
                    activeFailureSites.pop();
                }
            }
            if (!(node instanceof IrNode.IndexAccess index)) {
                throw unsupported(node.span(), "array assignment target is not an index access");
            }
            IrFailureSite failure = activeFailureSites.peek();
            if (failure == null || failure.checkKind() != IrCheckKind.BOUNDS
                    || !"LYR-BOUNDS".equals(failure.failureCode())) {
                throw invalidPlan(index.span(), "array assignment has no active bounds failure site");
            }
            JvmTypePlan receiverPlan = owner.mapper.map(index.receiver().type(),
                    JvmMappingContext.INTERNAL_VALUE);
            if (!receiverPlan.isSingleValue()
                    || !receiverPlan.physicalComponents().getFirst().isReference()) {
                throw invalidPlan(index.span(), "array assignment receiver has no JVM reference representation");
            }
            JvmTypePlan indexPlan = owner.mapper.map(index.index().type(),
                    JvmMappingContext.INTERNAL_VALUE);
            if (!indexPlan.isSingleValue()
                    || !indexPlan.physicalComponents().getFirst().isPrimitive()) {
                throw invalidPlan(index.span(), "array assignment index has no JVM integer representation");
            }
            int receiverSlot = allocateLocal(receiverPlan.physicalComponents().getFirst());
            emitAt(index.receiver(), receiverPlan);
            storePhysical(receiverPlan.physicalComponents().getFirst(), receiverSlot);
            List<Integer> indexSlots = allocateLocals(indexPlan);
            emitAt(index.index(), indexPlan);
            PrimitiveType indexType = primitiveBase(index.index().type());
            if (indexType == PrimitiveType.U8 || indexType == PrimitiveType.U16) {
                normalizeUnsigned(indexType);
            }
            storeLocal(indexPlan, indexSlots);
            Label boundsFailure = failureLabel(index.span(), failure.failureCode(),
                    "index is outside the aggregate bounds");
            LyraType receiverType = index.receiver().type().withoutQualifiers();
            if (!(receiverType instanceof ArrayType)) {
                throw invalidPlan(index.span(), "array assignment receiver is not an Array");
            }
            emitIndexBoundsCheck(indexPlan, indexSlots, receiverSlot, boundsFailure,
                    primitiveBase(index.index().type()), receiverPlan.physicalComponents().getFirst(), false);
            JvmTypePlan elementPlan = owner.mapper.map(((ArrayType) receiverType).elementType(),
                    JvmMappingContext.INTERNAL_ARRAY_ELEMENT);
            if (!elementPlan.isSingleValue() || "V".equals(elementPlan.descriptor())) {
                throw invalidPlan(index.span(), "array assignment element has no JVM representation");
            }
            return new ArrayStoreTarget(receiverPlan, receiverSlot, indexPlan, indexSlots, elementPlan);
        }

        private IrNode rootReferenceNode(IrNode node) {
            if (node instanceof IrNode.RuntimeCheck check) {
                return rootReferenceNode(check.operand());
            }
            return node;
        }

        private JvmTypePlan emitSequence(List<IrNode> forms, LyraType type) {
            if (forms.isEmpty()) {
                emitUnit();
                return owner.mapper.map(type, JvmMappingContext.INTERNAL_VALUE);
            }
            prepareLocalFunctionLinkage(forms);
            for (int index = 0; index < forms.size() - 1; index++) {
                IrNode form = forms.get(index);
                emitAttachmentSafePoint();
                if (skipAlreadyInitializedLocalFunction(form)) {
                    discardUnitForm();
                    continue;
                }
                ensureFunctionsReferencedBy(form);
                discard(emitNode(form));
            }
            IrNode last = forms.getLast();
            emitAttachmentSafePoint();
            if (skipAlreadyInitializedLocalFunction(last)) {
                emitUnit();
                return owner.mapper.map(type, JvmMappingContext.INTERNAL_VALUE);
            }
            ensureFunctionsReferencedBy(last);
            return emitNode(last);
        }

        /**
         * Function signatures are visible throughout their lexical scope. A
         * typed slot therefore exists before any closure in that scope is
         * built, allowing self/forward/mutual captures without constructing a
         * closure around a null peer. Mutable functions already use their
         * generated shared-cell shape; only their cell shell is preallocated.
         */
        private void prepareLocalFunctionLinkage(List<IrNode> forms) {
            for (IrNode form : forms) {
                if (form instanceof IrNode.Declaration declaration
                        && declaration.declarationId().isPresent()
                        && isLocalFunctionDeclaration(declaration.declarationId().orElseThrow())) {
                    localFunctionForms.put(declaration.declarationId().orElseThrow(), declaration);
                }
            }
            for (IrNode form : forms) {
                if (!(form instanceof IrNode.Declaration declarationNode)
                        || declarationNode.declarationId().isEmpty()) {
                    continue;
                }
                DeclarationId id = declarationNode.declarationId().orElseThrow();
                IrDeclaration declaration = declarations.get(id);
                if (declaration == null || isRootDeclaration(id)
                        || !declaration.signaturePredeclared()
                        || declaration.initializerLambda().isEmpty()
                        || declaration.contract().map(BindingContract::valueType)
                        .map(LyraType::withoutQualifiers)
                        .filter(FunctionType.class::isInstance)
                        .isEmpty()) {
                    continue;
                }
                if (owner.cells.containsKey(id)) {
                    preallocateFunctionCell(id);
                } else if (!localFunctionSlots.containsKey(id)) {
                    FunctionType function = functionBase(
                            declaration.contract().orElseThrow().valueType());
                    String functionName = owner.plan.functionInterfaces()
                            .get(function.canonicalSpelling());
                    if (functionName == null) {
                        throw invalidPlan(declaration.span(),
                                "local function slot has no generated interface");
                    }
                    emitInt(1);
                    code.anewarray(cd(functionName));
                    int slot = allocateLocal(JvmType.array(
                            "L" + functionName.replace('.', '/') + ";"));
                    code.astore(slot);
                    localFunctionSlots.put(id, slot);
                }
            }
        }

        private boolean isLocalFunctionDeclaration(DeclarationId id) {
            IrDeclaration declaration = declarations.get(id);
            return declaration != null
                    && !isRootDeclaration(id)
                    && declaration.signaturePredeclared()
                    && declaration.initializerLambda().isPresent()
                    && declaration.contract().map(BindingContract::valueType)
                    .map(LyraType::withoutQualifiers)
                    .filter(FunctionType.class::isInstance)
                    .isPresent();
        }

        private boolean skipAlreadyInitializedLocalFunction(IrNode form) {
            return form instanceof IrNode.Declaration declaration
                    && declaration.declarationId().filter(initializedLocalFunctions::contains).isPresent()
                    && isLocalFunctionDeclaration(declaration.declarationId().orElseThrow());
        }

        private void discardUnitForm() {
            emitUnit();
            discard(owner.mapper.map(PrimitiveType.UNIT, JvmMappingContext.INTERNAL_VALUE));
        }

        /**
         * A forward function slot is only materialized when the current eager
         * expression can reach it.  This preserves source-ordered immutable
         * captures while making predeclared function references executable
         * before their textual declaration.
         */
        private void ensureFunctionsReferencedBy(IrNode form) {
            for (IrNode node : io.mindspice.lyra.compiler.ir.IrTraversal.preOrder(form)) {
                if (node instanceof IrNode.Reference reference) {
                    reference.targetDeclaration().ifPresent(this::ensureLocalFunctionInitialized);
                } else if (node instanceof IrNode.CaptureReference reference) {
                    reference.declarationId().ifPresent(this::ensureLocalFunctionInitialized);
                } else if (node instanceof IrNode.DirectCall call) {
                    call.targetDeclaration().ifPresent(this::ensureLocalFunctionInitialized);
                }
            }
        }

        private void ensureLocalFunctionInitialized(DeclarationId id) {
            if (!isLocalFunctionDeclaration(id)
                    || !localFunctionForms.containsKey(id)
                    || initializedLocalFunctions.contains(id)) {
                return;
            }
            IrNode.Declaration form = localFunctionForms.get(id);
            // Function captures carry preallocated slots/cells, so a recursive
            // capture cycle needs no eager value recursion.  The outermost
            // walk will materialize each declaration once and the later slot
            // stores complete the SCC linkage.
            if (!initializingLocalFunctions.add(id)) {
                return;
            }
            try {
                IrDeclaration declaration = declarations.get(id);
                io.mindspice.lyra.compiler.ir.IrLambda lambda = owner.lambdas.values().stream()
                        .filter(value -> value.id().equals(declaration.initializerLambda().orElseThrow()))
                        .findFirst().orElseThrow(() -> invalidPlan(form.span(),
                                "local function initializer has no lambda metadata"));
                for (CaptureId captureId : lambda.captures()) {
                    IrCapture capture = captures.get(captureId);
                    if (capture == null) {
                        throw invalidPlan(form.span(), "local function capture is absent: " + captureId);
                    }
                    ensureLocalFunctionInitialized(capture.declarationId());
                }
                discard(emitDeclaration(form));
            } finally {
                initializingLocalFunctions.remove(id);
            }
        }

        private void preallocateFunctionCell(DeclarationId id) {
            if (localCells.containsKey(id)) {
                return;
            }
            String cellName = cellClassName(id);
            GeneratedClassPlan cell = owner.plan.classPlan(cellName).orElseThrow();
            GeneratedMemberPlan constructor = cell.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.CELL_CONSTRUCTOR)
                    .findFirst().orElseThrow();
            if (!constructor.descriptor().startsWith("(L")
                    || !constructor.descriptor().endsWith(";)V")) {
                throw invalidPlan(declarations.get(id).span(),
                        "local function cell has an invalid constructor");
            }
            code.new_(cd(cellName));
            code.dup();
            code.aconst_null();
            code.invokespecial(cd(cellName), "<init>", method(constructor.descriptor()));
            int slot = allocateLocal(JvmType.reference(cellName));
            code.astore(slot);
            localCells.put(id, slot);
        }

        private JvmTypePlan emitOperator(IrNode.Operator operator) {
            TokenKind token = operator.operator();
            if (token == TokenKind.NOT) {
                emitTruthValue(operator.operands().getFirst());
                code.iconst_1();
                code.ixor();
                return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
            }
            if (token == TokenKind.XOR || token == TokenKind.AND || token == TokenKind.OR) {
                if (operator.operands().size() < 2) {
                    throw invalidPlan(operator.span(), "boolean operator has too few operands");
                }
                emitTruthValue(operator.operands().getFirst());
                for (IrNode operand : operator.operands().subList(1, operator.operands().size())) {
                    emitTruthValue(operand);
                    if (token == TokenKind.XOR) {
                        code.ixor();
                    } else if (token == TokenKind.AND) {
                        code.iand();
                    } else {
                        code.ior();
                    }
                }
                return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
            }
            if (isComparison(token)) {
                return emitComparison(operator);
            }
            PrimitiveType primitive = primitiveBase(operator.type());
            if (primitive == PrimitiveType.STRING && token == TokenKind.PLUS) {
                return emitStringConcat(operator);
            }
            if (!primitive.isNumeric()) {
                throw unsupported(operator.span(), "unsupported scalar operator: " + token);
            }
            return emitCheckedNumeric(operator, primitive);
        }

        private boolean isComparison(TokenKind token) {
            return switch (token) {
                case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
                        EQUAL_EQUAL, NOT_EQUAL, IDENTITY_EQUAL, IDENTITY_NOT_EQUAL -> true;
                default -> false;
            };
        }

        private JvmTypePlan emitStringConcat(IrNode.Operator operator) {
            for (int index = 0; index < operator.operands().size(); index++) {
                IrNode operand = operator.operands().get(index);
                JvmTypePlan string = owner.mapper.map(PrimitiveType.STRING, JvmMappingContext.INTERNAL_VALUE);
                emitAt(operand, string);
                if (index > 0) {
                    code.invokevirtual(CD_STRING, "concat", method("(Ljava/lang/String;)Ljava/lang/String;"));
                }
            }
            return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private JvmTypePlan emitCheckedNumeric(IrNode.Operator operator, PrimitiveType primitive) {
            if (primitive.isFloating()) {
                return emitFloatingNumeric(operator, primitive);
            }
            if (operator.operator() == TokenKind.CARET) {
                return emitIntegerPower(operator, primitive);
            }
            return emitIntegerNumeric(operator, primitive);
        }

        private JvmTypePlan emitIntegerNumeric(IrNode.Operator operator, PrimitiveType primitive) {
            IrNode firstOperand = operator.operands().getFirst();
            if (operator.operands().size() == 1
                    && operator.operator() == TokenKind.MINUS
                    && firstOperand instanceof IrNode.Constant constant
                    && constant.value() instanceof IrConstantValue.IntegerValue integer
                    && primitive.isSignedInteger()
                    && integer.exactValue().integerValue().equals(
                    primitive.numericDomain().orElseThrow().minimumInteger().negate())) {
                // The semantic layer intentionally represents a signed
                // minimum literal as its positive magnitude so that the sign
                // remains an operator.  That magnitude is not representable
                // in the JVM source-width value, so it must not be mistaken
                // for an already-negated runtime minimum by the overflow
                // check below.
                emitIntegerConstant(integer.exactValue().negated(), primitive);
                return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
            }
            int accumulator = allocateLocal(primitive);
            IrNode first = operator.operands().getFirst();
            emitAt(first, owner.mapper.map(primitive, JvmMappingContext.INTERNAL_VALUE));
            normalizeUnsigned(primitive);
            storePrimitive(primitive, accumulator);
            if (operator.operands().size() == 1) {
                switch (operator.operator()) {
                    case PLUS -> loadPrimitive(primitive, accumulator);
                    case MINUS -> emitCheckedNegate(primitive, accumulator, operator.span());
                    case INCREMENT -> emitCheckedAddConstant(primitive, accumulator, 1, operator.span());
                    case DECREMENT -> emitCheckedAddConstant(primitive, accumulator, -1, operator.span());
                    default -> throw invalidPlan(operator.span(), "invalid unary integer operator");
                }
                return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
            }
            for (IrNode operand : operator.operands().subList(1, operator.operands().size())) {
                int right = allocateLocal(primitive);
                emitAt(operand, owner.mapper.map(primitive, JvmMappingContext.INTERNAL_VALUE));
                normalizeUnsigned(primitive);
                storePrimitive(primitive, right);
                int result = allocateLocal(primitive);
                emitIntegerBinary(primitive, operator.operator(), accumulator, right, result,
                        operator.span());
                loadPrimitive(primitive, result);
                storePrimitive(primitive, accumulator);
            }
            loadPrimitive(primitive, accumulator);
            return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private JvmTypePlan emitFloatingNumeric(IrNode.Operator operator, PrimitiveType primitive) {
            int accumulator = allocateLocal(primitive);
            JvmTypePlan target = owner.mapper.map(primitive, JvmMappingContext.INTERNAL_VALUE);
            emitFloatingOperand(operator.operands().getFirst(), target);
            storePrimitive(primitive, accumulator);
            if (operator.operands().size() == 1) {
                if (operator.operator() == TokenKind.MINUS) {
                    loadPrimitive(primitive, accumulator);
                    if (primitive == PrimitiveType.F32) {
                        code.fneg();
                    } else {
                        code.dneg();
                    }
                    checkFinite(primitive, operator.span());
                } else if (operator.operator() == TokenKind.PLUS) {
                    loadPrimitive(primitive, accumulator);
                } else if (operator.operator() == TokenKind.INCREMENT
                        || operator.operator() == TokenKind.DECREMENT) {
                    loadPrimitive(primitive, accumulator);
                    emitFloatingConstant(primitive, 1.0);
                    if (operator.operator() == TokenKind.INCREMENT) {
                        if (primitive == PrimitiveType.F32) code.fadd(); else code.dadd();
                    } else {
                        if (primitive == PrimitiveType.F32) code.fsub(); else code.dsub();
                    }
                    checkFinite(primitive, operator.span());
                } else if (operator.operator() == TokenKind.SLASH) {
                    emitFloatingConstant(primitive, 1.0);
                    loadPrimitive(primitive, accumulator);
                    if (primitive == PrimitiveType.F32) code.fdiv(); else code.ddiv();
                    checkFinite(primitive, operator.span());
                } else {
                    throw invalidPlan(operator.span(), "invalid unary floating operator");
                }
                return target;
            }
            for (IrNode operand : operator.operands().subList(1, operator.operands().size())) {
                int right = allocateLocal(primitive);
                emitFloatingOperand(operand, target);
                storePrimitive(primitive, right);
                loadPrimitive(primitive, accumulator);
                loadPrimitive(primitive, right);
                emitFloatingOpcode(operator.operator(), primitive, operator.span());
                checkFinite(primitive, operator.span());
                storePrimitive(primitive, accumulator);
            }
            loadPrimitive(primitive, accumulator);
            return target;
        }

        private void emitFloatingOpcode(TokenKind token, PrimitiveType primitive, SourceSpan span) {
            boolean f32 = primitive == PrimitiveType.F32;
            switch (token) {
                case PLUS -> { if (f32) code.fadd(); else code.dadd(); }
                case MINUS -> { if (f32) code.fsub(); else code.dsub(); }
                case ASTERISK -> { if (f32) code.fmul(); else code.dmul(); }
                case SLASH -> { if (f32) code.fdiv(); else code.ddiv(); }
                case CARET -> {
                    if (f32) {
                        // Widen the two float operands in left-to-right stack
                        // order.  The right operand must be saved before the
                        // left operand is widened.
                        int right = allocateLocal(PrimitiveType.F32);
                        storePrimitive(PrimitiveType.F32, right);
                        code.f2d();
                        loadPrimitive(PrimitiveType.F32, right);
                        code.f2d();
                    }
                    code.invokestatic(cd("java.lang.Math"), "pow", method("(DD)D"));
                    if (f32) code.d2f();
                }
                default -> throw unsupported(span, "unsupported floating operator: " + token);
            }
        }

        private JvmTypePlan emitIntegerPower(IrNode.Operator operator, PrimitiveType primitive) {
            if (operator.operands().size() != 2) {
                throw invalidPlan(operator.span(), "integer power needs two operands");
            }
            JvmTypePlan target = owner.mapper.map(primitive, JvmMappingContext.INTERNAL_VALUE);
            int base = allocateLocal(primitive);
            emitAt(operator.operands().getFirst(), target);
            normalizeUnsigned(primitive);
            storePrimitive(primitive, base);
            int exponent = allocateLocal(primitive);
            emitAt(operator.operands().get(1), target);
            normalizeUnsigned(primitive);
            storePrimitive(primitive, exponent);
            if (primitive.isSignedInteger()) {
                loadPrimitive(primitive, exponent);
                branchIfNegative(primitive, failureLabel(operator.span(), "LYR-ARITH",
                        "integer exponent must not be negative"));
            }
            int result = allocateLocal(primitive);
            emitIntegerConstantToLocal(primitive, BigInteger.ONE, result);
            Label loop = code.newLabel();
            Label skipMultiply = code.newLabel();
            Label afterShift = code.newLabel();
            Label done = code.newLabel();
            code.labelBinding(loop);
            loadPrimitive(primitive, exponent);
            if (wide(primitive)) {
                code.lconst_0();
                code.lcmp();
                code.ifeq(done);
                loadPrimitive(primitive, exponent);
                code.lconst_1();
                code.land();
                code.lconst_0();
                code.lcmp();
                code.ifeq(skipMultiply);
            } else {
                code.ifeq(done);
                loadPrimitive(primitive, exponent);
                code.iconst_1();
                code.iand();
                code.ifeq(skipMultiply);
            }
            int product = allocateLocal(primitive);
            emitIntegerBinaryFromSlots(primitive, TokenKind.ASTERISK, result, base, product,
                    operator.span());
            loadPrimitive(primitive, product);
            storePrimitive(primitive, result);
            code.labelBinding(skipMultiply);
            loadPrimitive(primitive, exponent);
            code.iconst_1();
            if (wide(primitive)) code.lushr(); else code.iushr();
            storePrimitive(primitive, exponent);
            loadPrimitive(primitive, exponent);
            if (wide(primitive)) {
                code.lconst_0();
                code.lcmp();
                code.ifne(afterShift);
            } else {
                code.ifne(afterShift);
            }
            code.goto_(done);
            code.labelBinding(afterShift);
            int square = allocateLocal(primitive);
            emitIntegerBinaryFromSlots(primitive, TokenKind.ASTERISK, base, base, square,
                    operator.span());
            loadPrimitive(primitive, square);
            storePrimitive(primitive, base);
            code.goto_(loop);
            code.labelBinding(done);
            loadPrimitive(primitive, result);
            return target;
        }

        private void emitIntegerConstantToLocal(PrimitiveType primitive, BigInteger value, int slot) {
            emitIntegerConstant(ExactNumericLiteral.integer(value), primitive);
            storePrimitive(primitive, slot);
        }

        private void emitCheckedNegate(PrimitiveType primitive, int operand, SourceSpan span) {
            Label safe = code.newLabel();
            loadPrimitive(primitive, operand);
            if (primitive.isUnsignedInteger()) {
                if (primitive == PrimitiveType.I64 || primitive == PrimitiveType.U64) {
                    code.lconst_0();
                    code.lcmp();
                    code.ifeq(safe);
                } else {
                    code.ifeq(safe);
                }
                code.goto_(failureLabel(span, "LYR-ARITH", "unsigned negation underflow"));
            } else {
                if (primitive == PrimitiveType.I64) {
                    code.ldc(Long.MIN_VALUE);
                    code.lcmp();
                    code.ifne(safe);
                } else {
                    emitIntegerMin(primitive);
                    code.if_icmpne(safe);
                }
                code.goto_(failureLabel(span, "LYR-ARITH", "integer arithmetic overflow"));
            }
            code.labelBinding(safe);
            loadPrimitive(primitive, operand);
            if (primitive == PrimitiveType.I64 || primitive == PrimitiveType.U64) code.lneg();
            else code.ineg();
            narrowIntegerResult(primitive);
        }

        private void narrowIntegerResult(PrimitiveType primitive) {
            switch (primitive) {
                case I8, U8 -> code.i2b();
                case I16, U16 -> code.i2s();
                default -> { }
            }
        }

        private void emitCheckedAddConstant(PrimitiveType primitive, int operand, int constant,
                                            SourceSpan span) {
            int rhs = allocateLocal(primitive);
            boolean subtract = primitive.isUnsignedInteger() && constant < 0;
            emitIntegerConstantToLocal(primitive,
                    BigInteger.valueOf(subtract ? -(long) constant : constant), rhs);
            int result = allocateLocal(primitive);
            emitIntegerBinaryFromSlots(primitive,
                    subtract ? TokenKind.MINUS : TokenKind.PLUS,
                    operand, rhs, result, span);
            loadPrimitive(primitive, result);
        }

        private void emitIntegerBinary(PrimitiveType primitive, TokenKind token,
                                       int left, int right, int result, SourceSpan span) {
            emitIntegerBinaryFromSlots(primitive, token, left, right, result, span);
        }

        private void emitIntegerBinaryFromSlots(PrimitiveType primitive, TokenKind token,
                                                int left, int right, int result, SourceSpan span) {
            if (token == TokenKind.SLASH || token == TokenKind.PERCENT) {
                loadIntegerOperand(primitive, right);
                emitIntOrLong(0, primitive);
                if (wide(primitive)) {
                    code.lcmp();
                    code.ifeq(failureLabel(span, "LYR-ARITH", "division by zero"));
                } else {
                    code.if_icmpeq(failureLabel(span, "LYR-ARITH", "division by zero"));
                }
            }
            loadIntegerOperand(primitive, left);
            loadIntegerOperand(primitive, right);
            switch (token) {
                case PLUS -> { if (wide(primitive)) code.ladd(); else code.iadd(); }
                case MINUS -> { if (wide(primitive)) code.lsub(); else code.isub(); }
                case ASTERISK -> { if (wide(primitive)) code.lmul(); else code.imul(); }
                case SLASH -> {
                    if (primitive == PrimitiveType.U32) {
                        code.invokestatic(CD_INTEGER, "divideUnsigned", method("(II)I"));
                    } else if (primitive == PrimitiveType.U64) {
                        code.invokestatic(CD_LONG, "divideUnsigned", method("(JJ)J"));
                    } else if (wide(primitive)) {
                        code.ldiv();
                    } else {
                        code.idiv();
                    }
                }
                case PERCENT -> {
                    if (primitive == PrimitiveType.U32) {
                        code.invokestatic(CD_INTEGER, "remainderUnsigned", method("(II)I"));
                    } else if (primitive == PrimitiveType.U64) {
                        code.invokestatic(CD_LONG, "remainderUnsigned", method("(JJ)J"));
                    } else if (wide(primitive)) {
                        code.lrem();
                    } else {
                        code.irem();
                    }
                }
                default -> throw unsupported(span, "unsupported integer operator: " + token);
            }
            storePrimitive(primitive, result);
            checkIntegerResult(primitive, token, left, right, result, span);
            loadPrimitive(primitive, result);
            narrowIntegerResult(primitive);
            storePrimitive(primitive, result);
        }

        private void loadIntegerOperand(PrimitiveType primitive, int slot) {
            loadPrimitive(primitive, slot);
            if (primitive == PrimitiveType.U8 || primitive == PrimitiveType.U16) {
                normalizeUnsigned(primitive);
            }
        }

        private boolean wide(PrimitiveType primitive) {
            return primitive == PrimitiveType.I64 || primitive == PrimitiveType.U64;
        }

        private void checkIntegerResult(PrimitiveType primitive, TokenKind token,
                                        int left, int right, int result, SourceSpan span) {
            if (primitive == PrimitiveType.I8 || primitive == PrimitiveType.I16
                    || primitive == PrimitiveType.U8 || primitive == PrimitiveType.U16) {
                int min = primitive.numericDomain().orElseThrow().minimumInteger().intValueExact();
                int max = primitive.numericDomain().orElseThrow().maximumInteger().intValueExact();
                loadPrimitive(primitive, result);
                emitInt(min);
                code.if_icmplt(failureLabel(span, "LYR-ARITH", "integer arithmetic overflow"));
                loadPrimitive(primitive, result);
                emitInt(max);
                code.if_icmpgt(failureLabel(span, "LYR-ARITH", "integer arithmetic overflow"));
                return;
            }
            if (primitive.isSignedInteger()) {
                checkSignedOverflow(primitive, token, left, right, result, span);
            } else {
                checkUnsignedOverflow(primitive, token, left, right, result, span);
            }
        }

        private void checkSignedOverflow(PrimitiveType primitive, TokenKind token,
                                         int left, int right, int result, SourceSpan span) {
            if (token == TokenKind.ASTERISK) {
                Label safe = code.newLabel();
                Label nonZero = code.newLabel();
                Label general = code.newLabel();
                loadPrimitive(primitive, right);
                if (wide(primitive)) {
                    code.lconst_0(); code.lcmp(); code.ifne(nonZero);
                } else {
                    code.ifne(nonZero);
                }
                code.goto_(safe);
                code.labelBinding(nonZero);
                // A signed minimum divided by -1 is the one product whose
                // wrapped Java result passes the ordinary reverse-division
                // check, so reject it explicitly first.
                loadPrimitive(primitive, left);
                emitIntegerMin(primitive);
                if (wide(primitive)) { code.lcmp(); code.ifne(general); }
                else { code.if_icmpne(general); }
                loadPrimitive(primitive, right);
                emitIntOrLong(-1, primitive);
                if (wide(primitive)) { code.lcmp(); code.ifeq(failureLabel(span, "LYR-ARITH", "integer arithmetic overflow")); }
                else { code.if_icmpeq(failureLabel(span, "LYR-ARITH", "integer arithmetic overflow")); }
                code.labelBinding(general);
                // For every other non-zero divisor, result / right must
                // reproduce left if the multiplication did not overflow.
                loadPrimitive(primitive, result);
                loadPrimitive(primitive, right);
                if (wide(primitive)) code.ldiv(); else code.idiv();
                loadPrimitive(primitive, left);
                if (wide(primitive)) { code.lcmp(); code.ifne(failureLabel(span, "LYR-ARITH", "integer arithmetic overflow")); }
                else { code.if_icmpne(failureLabel(span, "LYR-ARITH", "integer arithmetic overflow")); }
                code.labelBinding(safe);
                return;
            }
            if (token == TokenKind.SLASH) {
                Label safe = code.newLabel();
                loadPrimitive(primitive, left);
                emitIntegerMin(primitive);
                if (wide(primitive)) { code.lcmp(); code.ifne(safe); }
                else { code.if_icmpne(safe); }
                loadPrimitive(primitive, right);
                emitIntOrLong(-1, primitive);
                if (wide(primitive)) { code.lcmp(); code.ifeq(failureLabel(span, "LYR-ARITH", "integer arithmetic overflow")); }
                else { code.if_icmpeq(failureLabel(span, "LYR-ARITH", "integer arithmetic overflow")); }
                code.labelBinding(safe);
                return;
            }
            Label safe = code.newLabel();
            Label rightNegative = code.newLabel();
            loadPrimitive(primitive, right);
            emitIntOrLong(0, primitive);
            if (wide(primitive)) code.lcmp(); else code.isub();
            code.iflt(rightNegative);
            if (token == TokenKind.PLUS) {
                compareResultWithLeft(primitive, result, left, java.lang.classfile.Opcode.IFLT,
                        failureLabel(span, "LYR-ARITH", "integer arithmetic overflow"));
            } else if (token == TokenKind.MINUS) {
                compareResultWithLeft(primitive, result, left, java.lang.classfile.Opcode.IFGT,
                        failureLabel(span, "LYR-ARITH", "integer arithmetic overflow"));
            }
            code.goto_(safe);
            code.labelBinding(rightNegative);
            if (token == TokenKind.PLUS) {
                compareResultWithLeft(primitive, result, left, java.lang.classfile.Opcode.IFGT,
                        failureLabel(span, "LYR-ARITH", "integer arithmetic overflow"));
            } else if (token == TokenKind.MINUS) {
                compareResultWithLeft(primitive, result, left, java.lang.classfile.Opcode.IFLT,
                        failureLabel(span, "LYR-ARITH", "integer arithmetic overflow"));
            }
            code.labelBinding(safe);
        }

        private void compareResultWithLeft(PrimitiveType primitive, int result, int left,
                                           java.lang.classfile.Opcode branch, Label failure) {
            loadPrimitive(primitive, result);
            loadPrimitive(primitive, left);
            if (wide(primitive)) {
                code.lcmp();
                code.branch(switch (branch) {
                    case IFLT -> java.lang.classfile.Opcode.IFLT;
                    case IFGT -> java.lang.classfile.Opcode.IFGT;
                    default -> throw new IllegalArgumentException("unsupported overflow comparison");
                }, failure);
            } else {
                code.branch(switch (branch) {
                    case IFLT -> java.lang.classfile.Opcode.IF_ICMPLT;
                    case IFGT -> java.lang.classfile.Opcode.IF_ICMPGT;
                    default -> throw new IllegalArgumentException("unsupported overflow comparison");
                }, failure);
            }
        }

        private void checkUnsignedOverflow(PrimitiveType primitive, TokenKind token,
                                           int left, int right, int result, SourceSpan span) {
            if (primitive == PrimitiveType.U32 || primitive == PrimitiveType.U64) {
                ClassDesc integerClass = primitive == PrimitiveType.U32 ? CD_INTEGER : CD_LONG;
                String compare = "compareUnsigned";
                String descriptor = primitive == PrimitiveType.U32 ? "(II)I" : "(JJ)I";
                Label safe = code.newLabel();
                if (token == TokenKind.PLUS) {
                    loadPrimitive(primitive, result); loadPrimitive(primitive, left);
                    code.invokestatic(integerClass, compare, method(descriptor));
                    code.ifge(safe);
                } else if (token == TokenKind.MINUS) {
                    loadPrimitive(primitive, left); loadPrimitive(primitive, right);
                    code.invokestatic(integerClass, compare, method(descriptor));
                    code.ifge(safe);
                } else if (token == TokenKind.ASTERISK) {
                    loadPrimitive(primitive, right);
                    if (wide(primitive)) { code.lconst_0(); code.lcmp(); code.ifeq(safe); }
                    else code.ifeq(safe);
                    loadPrimitive(primitive, result); loadPrimitive(primitive, right);
                    String divideDescriptor = primitive == PrimitiveType.U32 ? "(II)I" : "(JJ)J";
                    code.invokestatic(integerClass, "divideUnsigned", method(divideDescriptor));
                    loadPrimitive(primitive, left);
                    code.invokestatic(integerClass, compare, method(descriptor));
                    code.ifeq(safe);
                } else if (token == TokenKind.SLASH || token == TokenKind.PERCENT) {
                    loadPrimitive(primitive, right);
                    if (wide(primitive)) { code.lconst_0(); code.lcmp(); code.ifne(safe); }
                    else code.ifne(safe);
                    code.goto_(failureLabel(span, "LYR-ARITH", "division by zero"));
                } else {
                    return;
                }
                code.goto_(failureLabel(span, "LYR-ARITH", "unsigned arithmetic overflow"));
                code.labelBinding(safe);
                return;
            }
            // U8/U16 have been normalized to non-negative int values.
            int max = primitive.numericDomain().orElseThrow().maximumInteger().intValueExact();
            Label safe = code.newLabel();
            loadPrimitive(primitive, result);
            code.iconst_0();
            code.if_icmplt(failureLabel(span, "LYR-ARITH", "unsigned arithmetic underflow"));
            emitInt(max);
            code.if_icmple(safe);
            code.goto_(failureLabel(span, "LYR-ARITH", "unsigned arithmetic overflow"));
            code.labelBinding(safe);
        }

        private void emitShortCircuit(IrNode.ShortCircuit shortCircuit) {
            Label shorted = code.newLabel();
            Label end = code.newLabel();
            boolean and = shortCircuit.operator() == TokenKind.AND;
            for (IrNode operand : shortCircuit.operands()) {
                emitTruthValue(operand);
                code.branch(and ? java.lang.classfile.Opcode.IFEQ : java.lang.classfile.Opcode.IFNE, shorted);
            }
            emitInt(and ? 1 : 0);
            code.goto_(end);
            code.labelBinding(shorted);
            emitInt(and ? 0 : 1);
            code.labelBinding(end);
        }

        private JvmTypePlan emitShortCircuitResult(IrNode.ShortCircuit shortCircuit) {
            emitShortCircuit(shortCircuit);
            return owner.mapper.map(shortCircuit.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private JvmTypePlan emitConversion(IrNode.Conversion conversion) {
            JvmTypePlan source = owner.mapper.map(conversion.sourceType(), JvmMappingContext.INTERNAL_VALUE);
            JvmTypePlan target = owner.mapper.map(conversion.type(), JvmMappingContext.INTERNAL_VALUE);
            JvmTypePlan actual = emitAt(conversion.operand(), source);
            if (conversion.step() == ConversionStep.NIL_LIFT) {
                adapt(actual, target);
                return target;
            }
            if (conversion.step() == ConversionStep.TEXT_EXPLICIT) {
                emitTextConversion(primitiveBase(conversion.sourceType()), target,
                        conversion.span());
                return target;
            }
            if (conversion.step() == ConversionStep.MUTABILITY_DROP) {
                adapt(actual, target);
                return target;
            }
            PrimitiveType sourcePrimitive = primitiveBase(conversion.sourceType());
            PrimitiveType targetPrimitive = primitiveBase(conversion.type());
            if (sourcePrimitive.isNumeric() && targetPrimitive.isNumeric()) {
                if (conversion.kind() == ConversionKind.EXPLICIT
                        || conversion.step() == ConversionStep.NUMERIC_EXPLICIT) {
                    checkExplicitNumericRange(sourcePrimitive, targetPrimitive, conversion.span());
                }
                emitPrimitiveConversion(sourcePrimitive, targetPrimitive);
                if (target.isSplitValue()) {
                    int payload = allocateLocal(target.physicalComponents().get(1));
                    storePhysical(target.physicalComponents().get(1), payload);
                    code.iconst_1();
                    loadPhysical(target.physicalComponents().get(1), payload);
                }
                return target;
            }
            adapt(actual, target);
            return target;
        }

        private void emitTextConversion(PrimitiveType source, JvmTypePlan target, SourceSpan span) {
            if (source == PrimitiveType.STRING) {
                return;
            }
            if (source == PrimitiveType.UNIT) {
                code.invokevirtual(CD_UNIT, "toString", method("()Ljava/lang/String;"));
                return;
            }
            if (source.isUnsignedInteger()) {
                normalizeUnsigned(source);
                if (source == PrimitiveType.U64) {
                    code.invokestatic(CD_LONG, "toUnsignedString", method("(J)Ljava/lang/String;"));
                } else {
                    code.invokestatic(CD_INTEGER, "toUnsignedString", method("(I)Ljava/lang/String;"));
                }
                return;
            }
            if (source == PrimitiveType.BOOL) {
                Label trueLabel = code.newLabel();
                Label end = code.newLabel();
                code.ifne(trueLabel);
                code.ldc("#F");
                code.goto_(end);
                code.labelBinding(trueLabel);
                code.ldc("#T");
                code.labelBinding(end);
                return;
            }
            String descriptor = switch (source) {
                case I8, I16, I32 -> "(I)Ljava/lang/String;";
                case I64 -> "(J)Ljava/lang/String;";
                case F32 -> "(F)Ljava/lang/String;";
                case F64 -> "(D)Ljava/lang/String;";
                case CHAR -> "(C)Ljava/lang/String;";
                default -> throw unsupported(span, "unsupported scalar text conversion");
            };
            code.invokestatic(CD_STRING, "valueOf", method(descriptor));
            if (source.isFloating()) {
                code.getstatic(CD_LOCALE, "ROOT", CD_LOCALE);
                // Java's shortest round-tripping spelling has the required
                // numeric digits and signed zero.  Normalize its exponent
                // marker without making host locale part of the result.
                code.invokevirtual(CD_STRING, "toLowerCase", method(
                        "(Ljava/util/Locale;)Ljava/lang/String;"));
            }
        }

        private void checkExplicitNumericRange(PrimitiveType source, PrimitiveType target,
                                               SourceSpan span) {
            if (source.isFloating() && target.isInteger()) {
                int slot = allocateLocal(source);
                storePrimitive(source, slot);
                checkFiniteConversionFromLocal(source, slot, span);
                loadPrimitive(source, slot);
                emitFloatingBound(source, target, false);
                if (source == PrimitiveType.F32) code.fcmpl(); else code.dcmpl();
                code.iflt(failureLabel(span, "LYR-CONVERT", "numeric conversion is out of range"));
                loadPrimitive(source, slot);
                emitFloatingBound(source, target, true);
                if (source == PrimitiveType.F32) code.fcmpl(); else code.dcmpl();
                // The upper bound is the exclusive mathematical boundary
                // (2^N for both signed and unsigned N-bit integers).  Using
                // maxValue with an F32 operand can round I32/U32's max up to
                // the invalid boundary and accidentally admit it.
                code.ifge(failureLabel(span, "LYR-CONVERT",
                        "numeric conversion is out of range"));
                loadPrimitive(source, slot);
                emitPrimitiveConversion(source, target);
                int roundTrip = allocateLocal(target);
                storePrimitive(target, roundTrip);
                loadPrimitive(target, roundTrip);
                emitPrimitiveConversion(target, source);
                loadPrimitive(source, slot);
                if (source == PrimitiveType.F32) code.fcmpl(); else code.dcmpl();
                code.ifne(failureLabel(span, "LYR-CONVERT", "numeric conversion is not integral"));
                loadPrimitive(source, slot);
                return;
            }
            if (source.isInteger() && target.isInteger()) {
                int slot = allocateLocal(source);
                storePrimitive(source, slot);
                if (source.isUnsignedInteger()) normalizeUnsignedLocal(source, slot);
                checkIntegerToIntegerRange(source, target, slot, span);
                loadPrimitive(source, slot);
                return;
            }
            if (source.isFloating() && target.isFloating()
                    && source == PrimitiveType.F64 && target == PrimitiveType.F32) {
                int slot = allocateLocal(source);
                storePrimitive(source, slot);
                loadPrimitive(source, slot);
                emitPrimitiveConversion(source, target);
                checkFinite(target, span, "LYR-CONVERT",
                        "numeric conversion requires a finite value");
                discardPhysical(owner.mapper.map(target, JvmMappingContext.INTERNAL_VALUE)
                        .physicalComponents().getFirst());
                loadPrimitive(source, slot);
            }
        }

        private void checkIntegerToIntegerRange(PrimitiveType source, PrimitiveType target,
                                                int slot, SourceSpan span) {
            String failure = "numeric conversion is out of range";
            BigInteger min = target.numericDomain().orElseThrow().minimumInteger();
            BigInteger max = target.numericDomain().orElseThrow().maximumInteger();
            boolean sourceLong = source == PrimitiveType.I64 || source == PrimitiveType.U64;
            boolean targetLong = target == PrimitiveType.I64 || target == PrimitiveType.U64;

            if (target == PrimitiveType.U64) {
                if (source == PrimitiveType.I64) {
                    loadPrimitive(source, slot);
                    code.lconst_0();
                    code.lcmp();
                    code.iflt(failureLabel(span, "LYR-CONVERT", failure));
                } else if (!sourceLong) {
                    if (!source.isUnsignedInteger()) {
                        loadPrimitive(source, slot);
                        code.iflt(failureLabel(span, "LYR-CONVERT", failure));
                    }
                }
                return;
            }
            if (target == PrimitiveType.I64) {
                if (source == PrimitiveType.U64) {
                    loadPrimitive(source, slot);
                    code.ldc(Long.MAX_VALUE);
                    code.invokestatic(CD_LONG, "compareUnsigned", method("(JJ)I"));
                    code.ifgt(failureLabel(span, "LYR-CONVERT", failure));
                }
                return;
            }
            if (target == PrimitiveType.U32) {
                if (source == PrimitiveType.I64) {
                    loadPrimitive(source, slot);
                    code.lconst_0();
                    code.lcmp();
                    code.iflt(failureLabel(span, "LYR-CONVERT", failure));
                    loadPrimitive(source, slot);
                    code.ldc(0xffff_ffffL);
                    code.lcmp();
                    code.ifgt(failureLabel(span, "LYR-CONVERT", failure));
                } else if (source == PrimitiveType.U64) {
                    loadPrimitive(source, slot);
                    code.ldc(0xffff_ffffL);
                    code.invokestatic(CD_LONG, "compareUnsigned", method("(JJ)I"));
                    code.ifgt(failureLabel(span, "LYR-CONVERT", failure));
                } else if (source != PrimitiveType.U32 && !source.isUnsignedInteger()) {
                    loadPrimitive(source, slot);
                    code.iflt(failureLabel(span, "LYR-CONVERT", failure));
                }
                return;
            }

            // Targets below 64 bits have an int-sized bound.  A long source
            // is compared in its signed or unsigned domain before narrowing.
            if (sourceLong) {
                loadPrimitive(source, slot);
                if (source == PrimitiveType.U64) {
                    emitLongConstant(max.longValueExact());
                    code.invokestatic(CD_LONG, "compareUnsigned", method("(JJ)I"));
                    code.ifgt(failureLabel(span, "LYR-CONVERT", failure));
                } else {
                    emitLongConstant(min.longValueExact());
                    code.lcmp();
                    code.iflt(failureLabel(span, "LYR-CONVERT", failure));
                    loadPrimitive(source, slot);
                    emitLongConstant(max.longValueExact());
                    code.lcmp();
                    code.ifgt(failureLabel(span, "LYR-CONVERT", failure));
                }
                return;
            }

            // U32 is represented by raw int bits.  Compare it unsigned so
            // values above Integer.MAX_VALUE cannot pass a signed/narrow
            // target check merely because the JVM sees a negative int.
            if (source == PrimitiveType.U32) {
                if (target != PrimitiveType.U32) {
                    loadPrimitive(source, slot);
                    emitInt(max.intValueExact());
                    code.invokestatic(CD_INTEGER, "compareUnsigned", method("(II)I"));
                    code.ifgt(failureLabel(span, "LYR-CONVERT", failure));
                    if (target.isSignedInteger()) {
                        // The signed target's lower bound is never below
                        // zero for a U32 source, so the upper comparison is
                        // sufficient.
                    }
                }
                return;
            }

            // Signed int-sized sources and normalized U8/U16 values use
            // ordinary signed comparisons.
            loadPrimitive(source, slot);
            emitInt(min.intValueExact());
            code.if_icmplt(failureLabel(span, "LYR-CONVERT", failure));
            loadPrimitive(source, slot);
            emitInt(max.intValueExact());
            code.if_icmpgt(failureLabel(span, "LYR-CONVERT", failure));
        }

        private void emitFloatingBound(PrimitiveType source, PrimitiveType target, boolean upper) {
            BigInteger boundary = upper
                    ? target.numericDomain().orElseThrow().maximumInteger().add(BigInteger.ONE)
                    : target.numericDomain().orElseThrow().minimumInteger();
            BigDecimal value = new BigDecimal(boundary);
            if (source == PrimitiveType.F32) code.loadConstant(value.floatValue());
            else code.loadConstant(value.doubleValue());
        }

        private JvmTypePlan emitNarrowing(IrNode.Narrowing narrowing) {
            JvmTypePlan source = owner.mapper.map(narrowing.sourceType(), JvmMappingContext.INTERNAL_VALUE);
            JvmTypePlan target = owner.mapper.map(narrowing.type(), JvmMappingContext.INTERNAL_VALUE);
            emitAt(narrowing.operand(), source);
            if (source.isSplitValue()) {
                int payload = allocateLocal(source.physicalComponents().get(1));
                int present = allocateLocal(source.physicalComponents().getFirst());
                storePhysical(source.physicalComponents().get(1), payload);
                storePhysical(source.physicalComponents().getFirst(), present);
                Label nonNil = code.newLabel();
                loadPhysical(source.physicalComponents().getFirst(), present);
                code.ifne(nonNil);
                throwFailure(narrowing.span(), "LYR-CONVERT",
                        "nil value was narrowed without a value");
                code.labelBinding(nonNil);
                loadPhysical(source.physicalComponents().get(1), payload);
                adaptPhysical(source.physicalComponents().get(1), target.physicalComponents().getFirst());
                return target;
            }
            if (source.isSingleValue() && source.physicalComponents().getFirst().isReference()) {
                Label nonNil = code.newLabel();
                code.dup();
                code.ifnonnull(nonNil);
                throwFailure(narrowing.span(), "LYR-CONVERT", "nil value was narrowed without a value");
                code.labelBinding(nonNil);
            }
            adaptPhysicalPlan(source, target);
            return target;
        }

        private JvmTypePlan emitBranch(IrNode.Branch branch) {
            JvmTypePlan target = owner.mapper.map(branch.type(), JvmMappingContext.INTERNAL_VALUE);
            PredicateStorage predicate = emitPredicate(branch.predicate());
            Label thenLabel = code.newLabel();
            Label elseLabel = code.newLabel();
            Label end = code.newLabel();
            emitPredicateBranch(predicate, thenLabel, elseLabel);
            code.labelBinding(thenLabel);
            Map<DeclarationId, BindingStorage> saved = new HashMap<>(locals);
            Map<DeclarationId, Integer> savedCells = new HashMap<>(localCells);
            installPredicateBinding(branch, predicate);
            JvmTypePlan thenValue = emitNode(branch.thenBranch());
            if (branch.elseBranch().isEmpty()) {
                discard(thenValue);
                emitUnit();
            } else {
                adapt(thenValue, target);
                if (branch.type().withoutQualifiers() instanceof FunctionType function) {
                    code.checkcast(cd(owner.plan.functionInterfaces().get(function.canonicalSpelling())));
                }
            }
            code.goto_(end);
            locals.clear();
            locals.putAll(saved);
            localCells.clear();
            localCells.putAll(savedCells);
            code.labelBinding(elseLabel);
            if (branch.elseBranch().isPresent()) {
                JvmTypePlan elseValue = emitNode(branch.elseBranch().orElseThrow());
                adapt(elseValue, target);
                if (branch.type().withoutQualifiers() instanceof FunctionType function) {
                    code.checkcast(cd(owner.plan.functionInterfaces().get(function.canonicalSpelling())));
                }
            } else {
                emitUnit();
                adapt(owner.mapper.map(PrimitiveType.UNIT, JvmMappingContext.INTERNAL_VALUE), target);
            }
            code.labelBinding(end);
            return target;
        }

        private PredicateStorage emitPredicate(IrNode predicate) {
            JvmTypePlan physical = emitNode(predicate);
            List<Integer> slots = allocateLocals(physical);
            storeLocal(physical, slots);
            return new PredicateStorage(predicate.type(), physical, slots);
        }

        private void installPredicateBinding(IrNode.Branch branch, PredicateStorage predicate) {
            if (branch.predicateBinding().isEmpty()) {
                return;
            }
            DeclarationId id = branch.predicateBinding().orElseThrow();
            LyraType narrowed = withoutMutable(withoutNil(branch.predicate().type()));
            JvmTypePlan narrowedPlan = owner.mapper.map(narrowed, JvmMappingContext.INTERNAL_VALUE);
            if (predicate.physical().isSplitValue()) {
                locals.put(id, BindingStorage.local(narrowed, narrowedPlan,
                        List.of(predicate.slots().get(1))));
            } else {
                locals.put(id, BindingStorage.local(narrowed, narrowedPlan, predicate.slots()));
            }
        }

        private JvmTypePlan emitCoalesce(IrNode.Coalesce coalesce) {
            JvmTypePlan source = owner.mapper.map(coalesce.sourceType(), JvmMappingContext.INTERNAL_VALUE);
            JvmTypePlan target = owner.mapper.map(coalesce.type(), JvmMappingContext.INTERNAL_VALUE);
            emitNode(coalesce.value() instanceof IrNode.Narrowing narrowing
                    ? narrowing.operand() : coalesce.value());
            List<Integer> slots = allocateLocals(source);
            storeLocal(source, slots);
            Label valueLabel = code.newLabel();
            Label fallbackLabel = code.newLabel();
            Label end = code.newLabel();
            if (source.isSplitValue()) {
                loadPhysical(source.physicalComponents().getFirst(), slots.getFirst());
                code.ifne(valueLabel);
            } else {
                loadPhysical(source.physicalComponents().getFirst(), slots.getFirst());
                code.ifnonnull(valueLabel);
            }
            code.goto_(fallbackLabel);
            code.labelBinding(valueLabel);
            loadNonNilFromSlots(source, target, slots);
            code.goto_(end);
            code.labelBinding(fallbackLabel);
            JvmTypePlan fallback = emitNode(coalesce.fallback());
            adapt(fallback, target);
            code.labelBinding(end);
            return target;
        }

        private void loadNonNilFromSlots(JvmTypePlan source, JvmTypePlan target,
                                         List<Integer> slots) {
            if (source.isSplitValue()) {
                if (target.isSplitValue()) {
                    emitInt(1);
                    loadPhysical(source.physicalComponents().get(1), slots.get(1));
                    adaptPhysical(source.physicalComponents().get(1),
                            target.physicalComponents().get(1));
                } else {
                    loadPhysical(source.physicalComponents().get(1), slots.get(1));
                    adaptPhysical(source.physicalComponents().get(1),
                            target.physicalComponents().getFirst());
                }
            } else {
                loadPhysical(source.physicalComponents().getFirst(), slots.getFirst());
                if (target.isSingleValue()) {
                    adaptPhysical(source.physicalComponents().getFirst(),
                            target.physicalComponents().getFirst());
                } else {
                    // A nullable reference cannot become a split primitive;
                    // this branch is only valid for a forged plan.
                    throw invalidPlan(module.span(), "coalesce target changes value representation");
                }
            }
        }

        private JvmTypePlan emitMatch(IrNode.Match match) {
            JvmTypePlan target = owner.mapper.map(match.type(), JvmMappingContext.INTERNAL_VALUE);
            Optional<BindingStorage> subject = match.subject().map(this::evaluateMatchSubject);
            if (subject.isPresent() && emitIntegralSwitchMatch(match, subject.orElseThrow(), target)) {
                return target;
            }
            Label end = code.newLabel();
            for (IrNode.MatchArm arm : match.arms()) {
                line(arm.span());
                Label selected = code.newLabel();
                Label next = code.newLabel();
                if (arm.wildcard()) {
                    code.goto_(selected);
                } else {
                    emitMatchCondition(match, arm, subject, selected, next);
                }
                code.labelBinding(selected);
                if (arm.guard().isPresent()) {
                    PredicateStorage guard = emitPredicate(arm.guard().orElseThrow());
                    Label guarded = code.newLabel();
                    emitPredicateBranch(guard, guarded, next);
                    code.labelBinding(guarded);
                }
                line(arm.result().span());
                adapt(emitNode(arm.result()), target);
                if (match.type().withoutQualifiers() instanceof FunctionType function) {
                    code.checkcast(cd(owner.plan.functionInterfaces().get(function.canonicalSpelling())));
                }
                code.goto_(end);
                code.labelBinding(next);
            }
            code.labelBinding(end);
            return target;
        }

        private BindingStorage evaluateMatchSubject(IrNode subject) {
            JvmTypePlan physical = owner.mapper.map(subject.type(), JvmMappingContext.INTERNAL_VALUE);
            emitAt(subject, physical);
            List<Integer> slots = allocateLocals(physical);
            storeLocal(physical, slots);
            return BindingStorage.local(subject.type(), physical, slots);
        }

        private void emitMatchCondition(
                IrNode.Match match,
                IrNode.MatchArm arm,
                Optional<BindingStorage> subject,
                Label selected,
                Label next) {
            IrNode pattern = arm.pattern().orElseThrow(() ->
                    invalidPlan(arm.span(), "non-wildcard match arm has no pattern"));
            if (match.mode() == IrNode.MatchMode.CONDITIONAL) {
                emitPredicateBranch(emitPredicate(pattern), selected, next);
                return;
            }
            BindingStorage original = subject.orElseThrow(() ->
                    invalidPlan(match.span(), "traditional match has no evaluated subject"));
            LyraType comparisonType = arm.comparisonType().orElseThrow(() ->
                    invalidPlan(arm.span(), "traditional match arm has no equality type"));
            JvmTypePlan comparison = owner.mapper.map(
                    comparisonType, JvmMappingContext.INTERNAL_VALUE);
            loadLocal(original);
            LyraType subjectType = original.logical().withoutQualifiers();
            LyraType targetType = comparisonType.withoutQualifiers();
            if (!subjectType.equals(targetType)
                    && subjectType instanceof PrimitiveType sourcePrimitive
                    && targetType instanceof PrimitiveType targetPrimitive
                    && sourcePrimitive.isNumeric() && targetPrimitive.isNumeric()) {
                // Physical adaptation alone loses the logical unsigned value
                // carried by narrow/raw JVM storage.  Match equality uses the
                // same lossless numeric conversion as an implicit IR edge.
                emitPrimitiveConversion(sourcePrimitive, targetPrimitive);
            } else {
                adapt(original.physical(), comparison);
            }
            List<Integer> subjectSlots = allocateLocals(comparison);
            storeLocal(comparison, subjectSlots);
            emitAt(pattern, comparison);
            List<Integer> patternSlots = allocateLocals(comparison);
            storeLocal(comparison, patternSlots);
            emitEqualityPair(
                    BindingStorage.local(comparisonType, comparison, subjectSlots),
                    BindingStorage.local(comparisonType, comparison, patternSlots),
                    selected, next, arm.span());
        }

        private boolean emitIntegralSwitchMatch(
                IrNode.Match match,
                BindingStorage subject,
                JvmTypePlan target) {
            if (match.mode() != IrNode.MatchMode.TRADITIONAL
                    || !subject.physical().isSingleValue()
                    || !isJvmIntSwitchKind(
                            subject.physical().physicalComponents().getFirst().kind())) {
                return false;
            }
            List<IrNode.MatchArm> cases = match.arms().subList(0, match.arms().size() - 1);
            Map<Integer, IrNode.MatchArm> byKey = new TreeMap<>();
            for (IrNode.MatchArm arm : cases) {
                if (arm.wildcard() || arm.guard().isPresent()
                        || arm.comparisonType().isEmpty()
                        || !arm.comparisonType().orElseThrow().equals(subject.logical())
                        || !(arm.pattern().orElse(null) instanceof IrNode.Constant constant)) {
                    return false;
                }
                Integer key = integralSwitchKey(constant);
                if (key == null || byKey.putIfAbsent(key, arm) != null) {
                    return false;
                }
            }
            if (byKey.size() < 2) {
                return false;
            }
            Label fallback = code.newLabel();
            Label end = code.newLabel();
            Map<Integer, Label> labels = new TreeMap<>();
            byKey.keySet().forEach(key -> labels.put(key, code.newLabel()));
            loadLocal(subject);
            LyraType logicalSubject = subject.logical().withoutQualifiers();
            if (logicalSubject == PrimitiveType.U8 || logicalSubject == PrimitiveType.U16) {
                normalizeUnsigned((PrimitiveType) logicalSubject);
            }
            code.lookupswitch(fallback, labels.entrySet().stream()
                    .map(entry -> java.lang.classfile.instruction.SwitchCase.of(
                            entry.getKey(), entry.getValue()))
                    .toList());
            for (Map.Entry<Integer, IrNode.MatchArm> entry : byKey.entrySet()) {
                IrNode.MatchArm arm = entry.getValue();
                code.labelBinding(labels.get(entry.getKey()));
                line(arm.span());
                line(arm.result().span());
                adapt(emitNode(arm.result()), target);
                if (match.type().withoutQualifiers() instanceof FunctionType function) {
                    code.checkcast(cd(owner.plan.functionInterfaces().get(function.canonicalSpelling())));
                }
                code.goto_(end);
            }
            IrNode.MatchArm defaultArm = match.arms().getLast();
            code.labelBinding(fallback);
            line(defaultArm.span());
            line(defaultArm.result().span());
            adapt(emitNode(defaultArm.result()), target);
            if (match.type().withoutQualifiers() instanceof FunctionType function) {
                code.checkcast(cd(owner.plan.functionInterfaces().get(function.canonicalSpelling())));
            }
            code.labelBinding(end);
            return true;
        }

        private boolean isJvmIntSwitchKind(JvmTypeKind kind) {
            return kind == JvmTypeKind.BYTE || kind == JvmTypeKind.SHORT
                    || kind == JvmTypeKind.INT || kind == JvmTypeKind.BOOLEAN
                    || kind == JvmTypeKind.CHAR;
        }

        private Integer integralSwitchKey(IrNode.Constant constant) {
            try {
                if (constant.value() instanceof IrConstantValue.IntegerValue integer) {
                    return integer.value().integerValue().intValueExact();
                }
                if (constant.value() instanceof IrConstantValue.CharacterValue character) {
                    return (int) character.value();
                }
                if (constant.value() instanceof IrConstantValue.BooleanValue bool) {
                    return bool.value() ? 1 : 0;
                }
            } catch (ArithmeticException ignored) {
                return null;
            }
            return null;
        }

        private JvmTypePlan emitDirectCall(IrNode.DirectCall call) {
            emitAttachmentSafePoint();
            DeclarationId id = call.targetDeclaration().orElseThrow(() ->
                    invalidPlan(call.span(), "direct call has no target declaration"));
            IrDeclaration intrinsic = intrinsicDeclaration(id);
            if (intrinsic != null) {
                return emitIntrinsicCall(intrinsic, call.arguments(), call.type(), call.span());
            }
            IrDeclaration declaration = declarations.get(id);
            if (declaration == null || declaration.contract().isEmpty()) {
                throw invalidPlan(call.span(), "direct call target declaration is absent");
            }
            FunctionType function = functionBase(declaration.contract().orElseThrow().valueType());
            emitLoadDeclaration(id, owner.mapper.map(declaration.contract().orElseThrow().valueType(),
                    JvmMappingContext.INTERNAL_VALUE));
            JvmSignaturePlan signature = owner.mapper.mapSignature(function.signature(),
                    JvmAbiBoundary.JAVA_VISIBLE);
            for (int index = 0; index < function.arity(); index++) {
                emitAt(call.arguments().get(index), signature.parameters().get(index));
            }
            recordCallFailureFrame(call.span(), () -> code.invokeinterface(
                    cd(owner.plan.functionInterfaces().get(function.canonicalSpelling())),
                    "invoke", method(signature.descriptor())));
            JvmTypePlan result = owner.mapper.map(call.type(), JvmMappingContext.INTERNAL_VALUE);
            if (signature.returnValue().descriptor().equals("V")) {
                emitUnit();
            } else {
                adaptPhysicalPlan(signature.returnValue(), result);
            }
            return result;
        }

        private JvmTypePlan emitCallableCall(IrNode.CallableCall call) {
            emitAttachmentSafePoint();
            FunctionType function = functionBase(call.target().type());
            DeclarationId target = targetDeclaration(call.target());
            IrDeclaration intrinsic = target == null ? null : intrinsicDeclaration(target);
            if (intrinsic != null) {
                return emitIntrinsicCall(intrinsic, call.arguments(), call.type(), call.span());
            }
            JvmSignaturePlan signature = owner.mapper.mapSignature(function.signature(),
                    JvmAbiBoundary.JAVA_VISIBLE);
            emitAt(call.target(), owner.mapper.map(
                    call.target().type(), JvmMappingContext.INTERNAL_VALUE));
            recordCallFailureFrame(call.span(),
                    () -> authenticateGeneratedFunctionValue(function));
            for (int index = 0; index < function.arity(); index++) {
                emitAt(call.arguments().get(index), signature.parameters().get(index));
            }
            recordCallFailureFrame(call.span(), () -> code.invokeinterface(
                    cd(owner.plan.functionInterfaces().get(function.canonicalSpelling())),
                    "invoke", method(signature.descriptor())));
            JvmTypePlan result = owner.mapper.map(call.type(), JvmMappingContext.INTERNAL_VALUE);
            if (signature.returnValue().descriptor().equals("V")) emitUnit();
            else adaptPhysicalPlan(signature.returnValue(), result);
            return result;
        }

        private JvmTypePlan emitIntrinsicCall(
                IrDeclaration intrinsic, List<IrNode> arguments, LyraType resultType,
                SourceSpan span) {
            if (intrinsic.contract().isEmpty()
                    || !(intrinsic.contract().orElseThrow().valueType().withoutQualifiers()
                    instanceof FunctionType function)) {
                throw invalidPlan(span, "intrinsic target has no function contract: " + intrinsic.id());
            }
            if (arguments.size() != function.arity()) {
                throw invalidPlan(span, "intrinsic argument count disagrees with its contract");
            }
            JvmSignaturePlan signature = owner.mapper.mapSignature(
                    function.signature(), JvmAbiBoundary.JAVA_VISIBLE);
            emitIoAuthority();
            for (int index = 0; index < arguments.size(); index++) {
                emitAt(arguments.get(index), signature.parameters().get(index));
            }
            recordCallFailureFrame(span, () -> code.invokestatic(
                    CD_LYRA_IO, intrinsic.name(), method(intrinsicDescriptor(signature))));
            JvmTypePlan result = owner.mapper.map(resultType, JvmMappingContext.INTERNAL_VALUE);
            if (signature.returnValue().descriptor().equals("V")) {
                emitUnit();
            } else {
                adaptPhysicalPlan(signature.returnValue(), result);
            }
            return result;
        }

        private String intrinsicDescriptor(JvmSignaturePlan signature) {
            String descriptor = signature.descriptor();
            return "(L" + RUNTIME + "LyraClosureAuthority;"
                    + descriptor.substring(1);
        }

        private void authenticateGeneratedFunctionValue(FunctionType function) {
            emitCurrentAuthority();
            code.ldc(function.signature().canonicalSpelling());
            code.invokestatic(CD_SIGNATURE, "parse", method(
                    "(Ljava/lang/String;)L" + RUNTIME + "LyraSignature;"));
            code.invokestatic(CD_RUNTIME_CLOSURE_SUPPORT,
                    "requireAuthenticatedForGeneratedInvocation", method(
                            "(Ljava/lang/Object;L" + RUNTIME + "LyraClosureAuthority;L"
                                    + RUNTIME + "LyraSignature;)L" + RUNTIME
                                    + "LyraClosure;"));
            code.checkcast(cd(owner.plan.functionInterfaces().get(
                    function.canonicalSpelling())));
        }

        private void recordCallFailureFrame(SourceSpan span, Runnable invocation) {
            Label start = code.newLabel();
            Label end = code.newLabel();
            Label handler = code.newLabel();
            Label stackHandler = code.newLabel();
            code.labelBinding(start);
            invocation.run();
            code.labelBinding(end);
            callFailureHandlers.add(new CallFailureHandler(start, end, handler, stackHandler, span));
        }

        private JvmTypePlan emitLambda(IrNode.Lambda lambdaNode) {
            LambdaId id = lambdaNode.lambdaId().orElseThrow(() ->
                    invalidPlan(lambdaNode.span(), "lambda has no identity"));
            io.mindspice.lyra.compiler.ir.IrLambda lambda = owner.lambdas.get(id);
            String closureName = owner.plan.closureClasses().get(id);
            if (lambda == null || closureName == null) {
                throw invalidPlan(lambdaNode.span(), "lambda has no generated closure class");
            }
            GeneratedClassPlan closure = owner.plan.classPlan(closureName).orElseThrow();
            code.new_(cd(closureName));
            code.dup();
            emitCurrentAuthority();
            emitCurrentState();
            for (CaptureId captureId : lambda.captures().stream().sorted().toList()) {
                IrCapture capture = captures.get(captureId);
                if (capture == null) throw invalidPlan(lambdaNode.span(), "lambda capture is absent");
                if (capture.isSharedCell()) {
                    emitLoadCellForDeclaration(capture.declarationId());
                } else if (owner.usesLocalFunctionSlot(capture)) {
                    emitLoadFunctionSlotForDeclaration(capture.declarationId());
                } else {
                    JvmTypePlan capturePlan = owner.mapper.map(capture.contract().valueType(),
                            JvmMappingContext.INTERNAL_CAPTURE);
                    emitReferenceByDeclaration(capture.declarationId(), capture.contract().valueType(), capturePlan);
                }
            }
            GeneratedMemberPlan constructor = closure.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.CLOSURE_CONSTRUCTOR)
                    .findFirst().orElseThrow();
            code.invokespecial(cd(closureName), "<init>", method(constructor.descriptor()));
            return owner.mapper.map(lambdaNode.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private void emitCurrentAuthority() {
            if (stateMethod) {
                aloadReceiver();
                code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                        "$lyra$closureAuthority",
                        method("()L" + RUNTIME + "LyraClosureAuthority;"));
            } else if (closureMethod) {
                aloadReceiver();
                GeneratedMemberPlan authority = classPlan.members().stream()
                        .filter(value -> value.kind() == GeneratedMemberKind.CLOSURE_AUTHORITY_FIELD)
                        .findFirst().orElseThrow();
                code.getfield(cd(classPlan.binaryName()), authority.name(), type(authority.descriptor()));
            } else {
                throw unsupported(module.span(), "lambda creation requires a Lyra-owned invocation/state context");
            }
        }

        private void emitIoAuthority() {
            if (stateMethod || closureMethod) {
                emitCurrentAuthority();
                return;
            }
            if (classPlan.kind() == GeneratedClassKind.MODULE_FACADE) {
                loadFacadeState();
                code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                        "$lyra$closureAuthority",
                        method("()L" + RUNTIME + "LyraClosureAuthority;"));
                return;
            }
            throw unsupported(module.span(), "intrinsic I/O requires a Lyra-owned invocation context");
        }

        private DeclarationId targetDeclaration(IrNode node) {
            if (node instanceof IrNode.Reference reference) {
                return reference.targetDeclaration().orElse(null);
            }
            if (node instanceof IrNode.CaptureReference capture) {
                return capture.declarationId().orElse(null);
            }
            return null;
        }

        private IrDeclaration intrinsicDeclaration(DeclarationId start) {
            DeclarationId current = start;
            Set<DeclarationId> visited = new HashSet<>();
            while (current != null && visited.add(current)) {
                IrDeclaration declaration = declarations.get(current);
                if (declaration == null) {
                    return null;
                }
                if (declaration.kind() == DeclarationKind.INTRINSIC_EXPORT) {
                    return declaration;
                }
                IrImportBinding binding = owner.imports.get(current);
                if (binding == null || binding.targetDeclaration().isEmpty()) {
                    return null;
                }
                current = binding.targetDeclaration().orElseThrow();
            }
            return null;
        }

        private void emitCurrentState() {
            if (stateMethod) {
                aloadReceiver();
            } else if (closureMethod) {
                aloadReceiver();
                GeneratedMemberPlan state = classPlan.members().stream()
                        .filter(value -> value.kind() == GeneratedMemberKind.CLOSURE_STATE_FIELD)
                        .findFirst().orElseThrow();
                code.getfield(cd(classPlan.binaryName()), state.name(), type(state.descriptor()));
            } else {
                throw unsupported(module.span(), "lambda creation requires a module state");
            }
        }

        private JvmTypePlan emitAccess(IrNode.Access access) {
            if (access.accessKind() == AccessKind.MEMBER_VALUE
                    && access.memberName().filter("length"::equals).isPresent()) {
                IrNode receiver = access.receiver().orElseThrow();
                JvmTypePlan receiverPlan = owner.mapper.map(receiver.type(),
                        JvmMappingContext.INTERNAL_VALUE);
                emitAt(receiver, receiverPlan);
                if (receiver.type().withoutQualifiers() == PrimitiveType.STRING) {
                    code.invokevirtual(CD_STRING, "length", method("()I"));
                } else if (receiver.type().withoutQualifiers() instanceof ArrayType) {
                    code.arraylength();
                } else {
                    throw invalidPlan(access.span(), "length access has an invalid receiver type");
                }
                return owner.mapper.map(access.type(), JvmMappingContext.INTERNAL_VALUE);
            }
            if (access.tupleIndex().isPresent()) {
                IrNode receiver = access.receiver().orElseThrow();
                LyraType receiverType = receiver.type().withoutQualifiers();
                if (!(receiverType instanceof TupleType tuple)) {
                    throw invalidPlan(access.span(), "tuple access has a non-tuple receiver");
                }
                int index = access.tupleIndex().orElseThrow().intValueExact();
                if (index < 0 || index >= tuple.arity()) {
                    throw invalidPlan(access.span(), "tuple access index is outside the tuple shape");
                }
                JvmTypePlan receiverPlan = owner.mapper.map(receiver.type(),
                        JvmMappingContext.INTERNAL_VALUE);
                JvmTypePlan fieldPlan = owner.mapper.map(tuple.memberType(index),
                        JvmMappingContext.TUPLE_FIELD);
                emitAt(receiver, receiverPlan);
                String tupleName = owner.mapper.names().tupleBinaryName(tuple.canonicalSpelling());
                code.invokevirtual(cd(tupleName), "$lyra$get$" + index,
                        method("()" + fieldPlan.descriptor()));
                JvmTypePlan desired = owner.mapper.map(access.type(), JvmMappingContext.INTERNAL_VALUE);
                adapt(fieldPlan, desired);
                return desired;
            }
            JvmTypePlan desired = owner.mapper.map(access.type(), JvmMappingContext.INTERNAL_VALUE);
            if (access.accessKind() == AccessKind.NAMESPACE_VALUE) {
                ModuleId targetModule = access.moduleId().orElseThrow(() ->
                        invalidPlan(access.span(), "namespace access has no target module"));
                String name = access.memberName().orElseThrow(() ->
                        invalidPlan(access.span(), "namespace access has no export name"));
                IrExport export = owner.ir.exports().stream()
                        .filter(value -> value.moduleId().equals(targetModule)
                                && value.name().equals(name))
                        .findFirst().orElseThrow(() -> invalidPlan(access.span(),
                                "namespace access has no matching export"));
                emitLoadDeclarationFromModule(targetModule, export.declarationId(), desired);
                return desired;
            }
            DeclarationId id = access.declarationId().orElseThrow(() ->
                    invalidPlan(access.span(), "member access has no declaration identity"));
            emitLoadDeclaration(id, desired);
            return desired;
        }

        private JvmTypePlan emitRuntimeCheck(IrNode.RuntimeCheck check) {
            FlowSiteId siteId = check.failureSiteId().orElseThrow(() ->
                    invalidPlan(check.span(), "runtime check has no IR failure-site identity"));
            IrFailureSite failure = owner.failureSites.get(siteId);
            if (failure == null || failure.checkKind() != check.checkKind()
                    || !failure.failureCode().equals(check.failureCode())
                    || !failure.span().equals(check.span())) {
                throw invalidPlan(check.span(),
                        "runtime check disagrees with its IR failure-site record");
            }
            activeFailureSites.push(failure);
            try {
                return emitNode(check.operand());
            } finally {
                activeFailureSites.pop();
            }
        }

        private void emitTail(IrNode node) {
            if (node instanceof IrNode.Sequence sequence) {
                if (sequence.forms().isEmpty()) {
                    emitUnit();
                    emitReturn(owner.mapper.map(PrimitiveType.UNIT, JvmMappingContext.INTERNAL_VALUE));
                    return;
                }
                prepareLocalFunctionLinkage(sequence.forms());
                for (IrNode form : sequence.forms().subList(0, sequence.forms().size() - 1)) {
                    emitAttachmentSafePoint();
                    if (skipAlreadyInitializedLocalFunction(form)) {
                        discardUnitForm();
                        continue;
                    }
                    ensureFunctionsReferencedBy(form);
                    discard(emitNode(form));
                }
                IrNode last = sequence.forms().getLast();
                emitAttachmentSafePoint();
                if (skipAlreadyInitializedLocalFunction(last)) {
                    emitUnit();
                    emitReturn(owner.mapper.map(PrimitiveType.UNIT, JvmMappingContext.INTERNAL_VALUE));
                } else {
                    ensureFunctionsReferencedBy(last);
                    emitTail(last);
                }
                return;
            }
            if (node instanceof IrNode.Block block) {
                Map<DeclarationId, BindingStorage> saved = new HashMap<>(locals);
                Map<DeclarationId, Integer> savedCells = new HashMap<>(localCells);
                Map<DeclarationId, Integer> savedFunctionSlots =
                        new HashMap<>(localFunctionSlots);
                if (block.forms().isEmpty()) {
                    emitUnit();
                    emitReturn(owner.mapper.map(PrimitiveType.UNIT, JvmMappingContext.INTERNAL_VALUE));
                } else {
                    prepareLocalFunctionLinkage(block.forms());
                    for (IrNode form : block.forms().subList(0, block.forms().size() - 1)) {
                        emitAttachmentSafePoint();
                        if (skipAlreadyInitializedLocalFunction(form)) {
                            discardUnitForm();
                            continue;
                        }
                        ensureFunctionsReferencedBy(form);
                        discard(emitNode(form));
                    }
                    IrNode last = block.forms().getLast();
                    emitAttachmentSafePoint();
                    if (skipAlreadyInitializedLocalFunction(last)) {
                        emitUnit();
                        emitReturn(owner.mapper.map(PrimitiveType.UNIT, JvmMappingContext.INTERNAL_VALUE));
                    } else {
                        ensureFunctionsReferencedBy(last);
                        emitTail(last);
                    }
                }
                locals.clear();
                locals.putAll(saved);
                localCells.clear();
                localCells.putAll(savedCells);
                localFunctionSlots.clear();
                localFunctionSlots.putAll(savedFunctionSlots);
                return;
            }
            if (node instanceof IrNode.RuntimeCheck check) {
                FlowSiteId siteId = check.failureSiteId().orElseThrow(() ->
                        invalidPlan(check.span(), "runtime check has no IR failure-site identity"));
                IrFailureSite failure = owner.failureSites.get(siteId);
                if (failure == null || failure.checkKind() != check.checkKind()
                        || !failure.failureCode().equals(check.failureCode())
                        || !failure.span().equals(check.span())) {
                    throw invalidPlan(check.span(),
                            "runtime check disagrees with its IR failure-site record");
                }
                activeFailureSites.push(failure);
                try {
                    emitTail(check.operand());
                } finally {
                    activeFailureSites.pop();
                }
                return;
            }
            if (node instanceof IrNode.Branch branch) {
                emitTailBranch(branch);
                return;
            }
            if (node instanceof IrNode.Coalesce coalesce) {
                emitTailCoalesce(coalesce);
                return;
            }
            if (node instanceof IrNode.Match match) {
                emitTailMatch(match);
                return;
            }
            if (node instanceof IrNode.DirectCall call
                    && call.targetDeclaration().equals(lambda.ownerDeclaration())
                    && call.receiver().isEmpty()) {
                emitSelfTailCall(call);
                return;
            }
            JvmTypePlan value = emitNode(node);
            emitReturn(value);
        }

        private void emitTailBranch(IrNode.Branch branch) {
            PredicateStorage predicate = emitPredicate(branch.predicate());
            Label thenLabel = code.newLabel();
            Label elseLabel = code.newLabel();
            emitPredicateBranch(predicate, thenLabel, elseLabel);
            code.labelBinding(thenLabel);
            Map<DeclarationId, BindingStorage> saved = new HashMap<>(locals);
            Map<DeclarationId, Integer> savedCells = new HashMap<>(localCells);
            installPredicateBinding(branch, predicate);
            if (branch.elseBranch().isEmpty() && !branch.thenBranch().type().equals(PrimitiveType.UNIT)) {
                // A then-only conditional discards non-Unit values. Unit branches
                // retain ordinary tail lowering, including constant-stack self calls.
                discard(emitNode(branch.thenBranch()));
                emitUnit();
                emitReturn(owner.mapper.map(PrimitiveType.UNIT, JvmMappingContext.INTERNAL_VALUE));
            } else {
                emitTail(branch.thenBranch());
            }
            locals.clear(); locals.putAll(saved);
            localCells.clear(); localCells.putAll(savedCells);
            code.labelBinding(elseLabel);
            if (branch.elseBranch().isPresent()) emitTail(branch.elseBranch().orElseThrow());
            else {
                emitUnit();
                emitReturn(owner.mapper.map(PrimitiveType.UNIT, JvmMappingContext.INTERNAL_VALUE));
            }
        }

        private void emitTailCoalesce(IrNode.Coalesce coalesce) {
            JvmTypePlan source = owner.mapper.map(coalesce.sourceType(), JvmMappingContext.INTERNAL_VALUE);
            JvmTypePlan target = owner.mapper.map(coalesce.type(), JvmMappingContext.INTERNAL_VALUE);
            IrNode sourceNode = coalesce.value() instanceof IrNode.Narrowing narrowing
                    ? narrowing.operand() : coalesce.value();
            emitNode(sourceNode);
            List<Integer> slots = allocateLocals(source);
            storeLocal(source, slots);
            Label value = code.newLabel();
            Label fallback = code.newLabel();
            if (source.isSplitValue()) {
                loadPhysical(source.physicalComponents().getFirst(), slots.getFirst());
                code.ifne(value);
            } else {
                loadPhysical(source.physicalComponents().getFirst(), slots.getFirst());
                code.ifnonnull(value);
            }
            code.goto_(fallback);
            code.labelBinding(value);
            loadNonNilFromSlots(source, target, slots);
            emitReturn(target);
            code.labelBinding(fallback);
            emitTail(coalesce.fallback());
        }

        private void emitTailMatch(IrNode.Match match) {
            Optional<BindingStorage> subject = match.subject().map(this::evaluateMatchSubject);
            for (IrNode.MatchArm arm : match.arms()) {
                line(arm.span());
                Label selected = code.newLabel();
                Label next = code.newLabel();
                if (arm.wildcard()) {
                    code.goto_(selected);
                } else {
                    emitMatchCondition(match, arm, subject, selected, next);
                }
                code.labelBinding(selected);
                if (arm.guard().isPresent()) {
                    PredicateStorage guard = emitPredicate(arm.guard().orElseThrow());
                    Label guarded = code.newLabel();
                    emitPredicateBranch(guard, guarded, next);
                    code.labelBinding(guarded);
                }
                line(arm.result().span());
                emitTail(arm.result());
                code.labelBinding(next);
            }
        }

        private void emitSelfTailCall(IrNode.DirectCall call) {
            if (loopLabel == null) {
                throw invalidPlan(call.span(), "self-tail call outside a generated closure loop");
            }
            FunctionType function = functionBase(declarations.get(call.targetDeclaration().orElseThrow())
                    .contract().orElseThrow().valueType());
            JvmSignaturePlan signature = owner.mapper.mapSignature(function.signature(),
                    JvmAbiBoundary.JAVA_VISIBLE);
            List<Integer> temporary = new ArrayList<>();
            for (int index = 0; index < function.arity(); index++) {
                emitAt(call.arguments().get(index), signature.parameters().get(index));
                int slot = allocateLocal(signature.parameters().get(index));
                storePhysical(signature.parameters().get(index).physicalComponents().getFirst(), slot);
                temporary.add(slot);
            }
            for (int index = 0; index < function.arity(); index++) {
                loadPhysical(signature.parameters().get(index).physicalComponents().getFirst(), temporary.get(index));
                int destination = code.parameterSlot(index);
                code.storeLocal(typeKind(signature.parameters().get(index).descriptor()), destination);
            }
            code.goto_(loopLabel);
        }

        private void emitReturn(JvmTypePlan value) {
            JvmTypePlan target = owner.mapper.map(lambda.signature().returnType(), JvmMappingContext.JAVA_RETURN);
            if (target.descriptor().equals("V")) {
                discard(value);
                code.return_();
                return;
            }
            adapt(value, target);
            returnPhysicalDescriptor(target.descriptor());
        }

        private void emitTruthValue(IrNode node) {
            JvmTypePlan physical = emitNode(node);
            List<Integer> slots = allocateLocals(physical);
            storeLocal(physical, slots);
            emitTruthValueFromSlots(node.type(), physical, slots, node.span());
        }

        /** Leaves a canonical JVM int (0 or 1) on the stack. */
        private void emitTruthValueFromSlots(LyraType logical, JvmTypePlan physical,
                                             List<Integer> slots, SourceSpan span) {
            Label truthy = code.newLabel();
            Label falsy = code.newLabel();
            Label end = code.newLabel();
            emitTruthyBranchFromSlots(logical, physical, slots, truthy, falsy, span);
            code.labelBinding(truthy);
            emitInt(1);
            code.goto_(end);
            code.labelBinding(falsy);
            emitInt(0);
            code.labelBinding(end);
        }

        private void emitPredicateBranch(PredicateStorage predicate, Label thenLabel, Label elseLabel) {
            emitTruthyBranchFromSlots(predicate.type(), predicate.physical(),
                    predicate.slots(), thenLabel, elseLabel, module.span());
        }

        /** Branches using values already stored in the supplied local slots. */
        private void emitTruthyBranchFromSlots(LyraType logical, JvmTypePlan physical,
                                               List<Integer> slots, Label thenLabel,
                                               Label elseLabel, SourceSpan span) {
            if (physical.isSplitValue()) {
                loadPhysical(physical.physicalComponents().getFirst(), slots.getFirst());
                code.ifeq(elseLabel);
                emitTruthyBranchFromSingleSlot(withoutNil(logical),
                        physical.physicalComponents().get(1), slots.get(1),
                        thenLabel, elseLabel, span);
                return;
            }
            emitTruthyBranchFromSingleSlot(logical, physical.physicalComponents().getFirst(),
                    slots.getFirst(), thenLabel, elseLabel, span);
        }

        private void emitTruthyBranchFromSingleSlot(LyraType logical, JvmType physical,
                                                    int slot, Label thenLabel, Label elseLabel,
                                                    SourceSpan span) {
            LyraType base = logical.withoutQualifiers();
            if (base == PrimitiveType.UNIT) {
                code.goto_(elseLabel);
                return;
            }
            if (base == PrimitiveType.STRING) {
                if (logical.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.NIL)) {
                    loadPhysical(physical, slot);
                    code.ifnull(elseLabel);
                }
                loadPhysical(physical, slot);
                code.invokevirtual(CD_STRING, "isEmpty", method("()Z"));
                code.ifne(elseLabel);
                code.goto_(thenLabel);
                return;
            }
            if (base instanceof ArrayType) {
                if (logical.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.NIL)) {
                    loadPhysical(physical, slot);
                    code.ifnull(elseLabel);
                }
                loadPhysical(physical, slot);
                code.arraylength();
                code.ifeq(elseLabel);
                code.goto_(thenLabel);
                return;
            }
            if (base instanceof FunctionType) {
                loadPhysical(physical, slot);
                code.ifnonnull(thenLabel);
                code.goto_(elseLabel);
                return;
            }
            if (base instanceof PrimitiveType primitive && primitive.isNumeric()) {
                if (logical.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.NIL)
                        && physical.isReference()) {
                    loadPhysical(physical, slot);
                    code.ifnull(elseLabel);
                    loadPhysical(physical, slot);
                    JvmType valueType = owner.mapper.map(
                                    primitive, JvmMappingContext.INTERNAL_VALUE)
                            .physicalComponents().getFirst();
                    unboxPrimitive(valueType);
                    if (primitive == PrimitiveType.U8 || primitive == PrimitiveType.U16) {
                        normalizeUnsigned(primitive);
                    }
                } else {
                    loadPhysical(physical, slot);
                }
                if (primitive == PrimitiveType.I64 || primitive == PrimitiveType.U64) {
                    code.lconst_0();
                    code.lcmp();
                } else if (primitive == PrimitiveType.F32) {
                    code.fconst_0();
                    code.fcmpl();
                } else if (primitive == PrimitiveType.F64) {
                    code.dconst_0();
                    code.dcmpl();
                }
                code.ifne(thenLabel);
                code.goto_(elseLabel);
                return;
            }
            if (base == PrimitiveType.BOOL) {
                if (logical.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.NIL)
                        && physical.isReference()) {
                    loadPhysical(physical, slot);
                    code.ifnull(elseLabel);
                    loadPhysical(physical, slot);
                    unboxPrimitive(JvmType.primitive("Z"));
                } else {
                    loadPhysical(physical, slot);
                }
                code.ifne(thenLabel);
                code.goto_(elseLabel);
                return;
            }
            if (base == PrimitiveType.CHAR) {
                // Every non-nil character is truthy, including U+0000.
                if (logical.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.NIL)
                        && physical.isReference()) {
                    loadPhysical(physical, slot);
                    code.ifnull(elseLabel);
                }
                code.goto_(thenLabel);
                return;
            }
            if (physical.isReference()) {
                loadPhysical(physical, slot);
                code.ifnonnull(thenLabel);
                code.goto_(elseLabel);
                return;
            }
            throw unsupported(span, "cannot compute scalar truthiness for " + logical);
        }

        private JvmTypePlan emitComparison(IrNode.Operator operator) {
            TokenKind token = operator.operator();
            if (token == TokenKind.IDENTITY_EQUAL || token == TokenKind.IDENTITY_NOT_EQUAL) {
                return emitIdentityComparison(operator);
            }
            LyraType firstType = operator.operands().getFirst().type().withoutQualifiers();
            if (firstType == PrimitiveType.STRING) {
                return emitValueEquality(operator);
            }
            if (firstType instanceof PrimitiveType primitive) {
                if (primitive.isNumeric() || primitive == PrimitiveType.BOOL
                        || primitive == PrimitiveType.CHAR) {
                    if (token == TokenKind.EQUAL_EQUAL || token == TokenKind.NOT_EQUAL) {
                        return emitValueEquality(operator);
                    }
                    return emitNumericComparison(operator);
                }
            }
            return emitValueEquality(operator);
        }

        private JvmTypePlan emitIdentityComparison(IrNode.Operator operator) {
            List<BindingStorage> values = new ArrayList<>();
            for (IrNode operand : operator.operands()) {
                JvmTypePlan physical = owner.mapper.map(operand.type(), JvmMappingContext.INTERNAL_VALUE);
                if (!physical.isSingleValue() || !physical.physicalComponents().getFirst().isReference()) {
                    throw unsupported(operator.span(), "identity comparison requires a reference value");
                }
                emitAt(operand, physical);
                List<Integer> slots = allocateLocals(physical);
                storeLocal(physical, slots);
                values.add(BindingStorage.local(operand.type(), physical, slots));
            }
            Label falseLabel = code.newLabel();
            Label end = code.newLabel();
            boolean equal = operator.operator() == TokenKind.IDENTITY_EQUAL;
            for (int index = 1; index < values.size(); index++) {
                loadLocal(values.get(index - 1)); loadLocal(values.get(index));
                code.branch(equal ? java.lang.classfile.Opcode.IF_ACMPNE
                        : java.lang.classfile.Opcode.IF_ACMPEQ, falseLabel);
            }
            emitInt(1); code.goto_(end);
            code.labelBinding(falseLabel); emitInt(0); code.labelBinding(end);
            return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private JvmTypePlan emitValueEquality(IrNode.Operator operator) {
            boolean truthEquality = operator.operands().stream().anyMatch(value ->
                    value.type().withoutQualifiers() == PrimitiveType.BOOL)
                    && operator.operands().stream().map(IrNode::type).distinct().count() > 1;
            if (truthEquality) {
                return emitTruthEquality(operator);
            }
            List<BindingStorage> values = new ArrayList<>();
            for (IrNode operand : operator.operands()) {
                JvmTypePlan physical = owner.mapper.map(operand.type(), JvmMappingContext.INTERNAL_VALUE);
                emitAt(operand, physical);
                List<Integer> slots = allocateLocals(physical);
                storeLocal(physical, slots);
                values.add(BindingStorage.local(operand.type(), physical, slots));
            }
            Label falseLabel = code.newLabel();
            Label end = code.newLabel();
            boolean equal = operator.operator() == TokenKind.EQUAL_EQUAL;
            for (int index = 1; index < values.size(); index++) {
                BindingStorage left = values.get(index - 1);
                BindingStorage right = values.get(index);
                Label pairEqual = code.newLabel();
                Label pairUnequal = code.newLabel();
                Label pairEnd = code.newLabel();
                emitEqualityPair(left, right, pairEqual, pairUnequal, operator.span());
                code.labelBinding(pairEqual);
                code.goto_(equal ? pairEnd : falseLabel);
                code.labelBinding(pairUnequal);
                code.goto_(equal ? falseLabel : pairEnd);
                code.labelBinding(pairEnd);
            }
            emitInt(1); code.goto_(end);
            code.labelBinding(falseLabel); emitInt(0); code.labelBinding(end);
            return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private JvmTypePlan emitTruthEquality(IrNode.Operator operator) {
            List<Integer> values = new ArrayList<>();
            for (IrNode operand : operator.operands()) {
                emitTruthValue(operand);
                int slot = allocateLocal(JvmType.primitive("Z"));
                code.istore(slot);
                values.add(slot);
            }
            Label falseLabel = code.newLabel();
            Label end = code.newLabel();
            boolean equal = operator.operator() == TokenKind.EQUAL_EQUAL;
            for (int index = 1; index < values.size(); index++) {
                code.iload(values.get(index - 1));
                code.iload(values.get(index));
                code.branch(equal ? java.lang.classfile.Opcode.IF_ICMPNE
                        : java.lang.classfile.Opcode.IF_ICMPEQ, falseLabel);
            }
            emitInt(1);
            code.goto_(end);
            code.labelBinding(falseLabel);
            emitInt(0);
            code.labelBinding(end);
            return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private void emitEqualityPair(BindingStorage left, BindingStorage right,
                                      Label equal, Label unequal, SourceSpan span) {
            LyraType base = left.logical().withoutQualifiers();
            if (base instanceof ArrayType || base instanceof TupleType) {
                emitStructuralEquality(left, right, equal, unequal, span);
                return;
            }
            if (left.physical().isSplitValue() || right.physical().isSplitValue()) {
                emitNullablePrimitiveEquality(left, right, equal, unequal, span);
                return;
            }
            if (base instanceof FunctionType) {
                loadLocal(left);
                loadLocal(right);
                code.if_acmpeq(equal);
                code.goto_(unequal);
                return;
            }
            if (left.physical().physicalComponents().getFirst().isReference()) {
                if (base instanceof PrimitiveType primitive
                        && primitive != PrimitiveType.STRING
                        && primitive != PrimitiveType.UNIT
                        && left.logical().hasQualifier(
                        io.mindspice.lyra.compiler.types.TypeQualifier.NIL)) {
                    emitBoxedNullablePrimitiveEquality(
                            left, right, primitive, equal, unequal);
                    return;
                }
                loadLocal(left);
                loadLocal(right);
                code.invokestatic(CD_OBJECTS, "equals",
                        method("(Ljava/lang/Object;Ljava/lang/Object;)Z"));
                code.ifne(equal);
                code.goto_(unequal);
                return;
            }
            loadLocal(left);
            normalizeEqualityValue(left);
            loadLocal(right);
            normalizeEqualityValue(right);
            emitPrimitiveEqualityToLabels(left.physical().physicalComponents().getFirst(),
                    equal, unequal, span);
        }

        private void emitBoxedNullablePrimitiveEquality(
                BindingStorage left,
                BindingStorage right,
                PrimitiveType primitive,
                Label equal,
                Label unequal) {
            Label leftPresent = code.newLabel();
            loadLocal(left);
            code.ifnonnull(leftPresent);
            loadLocal(right);
            code.ifnull(equal);
            code.goto_(unequal);

            code.labelBinding(leftPresent);
            loadLocal(right);
            code.ifnull(unequal);
            JvmType valueType = owner.mapper.map(
                            primitive, JvmMappingContext.INTERNAL_VALUE)
                    .physicalComponents().getFirst();
            loadLocal(left);
            unboxPrimitive(valueType);
            if (primitive == PrimitiveType.U8 || primitive == PrimitiveType.U16) {
                normalizeUnsigned(primitive);
            }
            loadLocal(right);
            unboxPrimitive(valueType);
            if (primitive == PrimitiveType.U8 || primitive == PrimitiveType.U16) {
                normalizeUnsigned(primitive);
            }
            emitPrimitiveEqualityToLabels(valueType, equal, unequal, module.span());
        }

        private void emitNullablePrimitiveEquality(BindingStorage left, BindingStorage right,
                                                   Label equal, Label unequal, SourceSpan span) {
            if (left.physical().isSplitValue() != right.physical().isSplitValue()
                    || !left.physical().isSplitValue()) {
                throw unsupported(span, "incompatible nullable scalar equality representation");
            }
            loadPhysical(left.physical().physicalComponents().getFirst(), left.slots().getFirst());
            loadPhysical(right.physical().physicalComponents().getFirst(), right.slots().getFirst());
            Label samePresence = code.newLabel();
            code.if_icmpeq(samePresence);
            code.goto_(unequal);
            code.labelBinding(samePresence);
            Label bothNil = code.newLabel();
            Label bothPresent = code.newLabel();
            loadPhysical(left.physical().physicalComponents().getFirst(), left.slots().getFirst());
            code.ifeq(bothNil);
            loadPhysical(right.physical().physicalComponents().getFirst(), right.slots().getFirst());
            code.ifne(bothPresent);
            code.goto_(unequal);
            code.labelBinding(bothNil);
            code.goto_(equal);
            code.labelBinding(bothPresent);
            loadPhysical(left.physical().physicalComponents().get(1), left.slots().get(1));
            normalizeEqualityValue(left);
            loadPhysical(right.physical().physicalComponents().get(1), right.slots().get(1));
            normalizeEqualityValue(right);
            emitPrimitiveEqualityToLabels(left.physical().physicalComponents().get(1),
                    equal, unequal, span);
        }

        private void emitPrimitiveEqualityToLabels(JvmType type, Label equal,
                                                   Label unequal, SourceSpan span) {
            switch (type.kind()) {
                case LONG -> {
                    code.lcmp();
                    code.ifeq(equal);
                }
                case FLOAT -> {
                    code.fcmpl();
                    code.ifeq(equal);
                }
                case DOUBLE -> {
                    code.dcmpl();
                    code.ifeq(equal);
                }
                case BYTE, SHORT, INT, BOOLEAN, CHAR -> code.if_icmpeq(equal);
                default -> throw unsupported(span,
                        "value equality requires a primitive scalar, got " + type);
            }
            code.goto_(unequal);
        }

        private void normalizeEqualityValue(BindingStorage value) {
            LyraType logical = value.logical().withoutQualifiers();
            if (logical instanceof PrimitiveType primitive
                    && (primitive == PrimitiveType.U8 || primitive == PrimitiveType.U16)) {
                normalizeUnsigned(primitive);
            }
        }

        private void emitPrimitiveEqualityBranch(JvmType type, boolean equal, Label falseLabel) {
            java.lang.classfile.Opcode mismatch;
            switch (type.kind()) {
                case LONG -> {
                    code.lcmp();
                    mismatch = equal ? java.lang.classfile.Opcode.IFNE
                            : java.lang.classfile.Opcode.IFEQ;
                }
                case FLOAT -> {
                    code.fcmpl();
                    mismatch = equal ? java.lang.classfile.Opcode.IFNE
                            : java.lang.classfile.Opcode.IFEQ;
                }
                case DOUBLE -> {
                    code.dcmpl();
                    mismatch = equal ? java.lang.classfile.Opcode.IFNE
                            : java.lang.classfile.Opcode.IFEQ;
                }
                case BYTE, SHORT, INT, BOOLEAN, CHAR -> {
                    mismatch = equal ? java.lang.classfile.Opcode.IF_ICMPNE
                            : java.lang.classfile.Opcode.IF_ICMPEQ;
                }
                default -> throw unsupported(module.span(),
                        "value equality requires a primitive scalar, got " + type);
            }
            code.branch(mismatch, falseLabel);
        }

        private void emitStructuralEquality(BindingStorage left, BindingStorage right,
                                            Label equal, Label unequal, SourceSpan span) {
            LyraType base = left.logical().withoutQualifiers();
            if (left.physical().isSingleValue()
                    && left.physical().physicalComponents().getFirst().isReference()
                    && left.logical().hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.NIL)) {
                Label nonNilLeft = code.newLabel();
                Label bothNil = code.newLabel();
                loadLocal(left);
                code.ifnonnull(nonNilLeft);
                loadLocal(right);
                code.ifnull(bothNil);
                code.goto_(unequal);
                code.labelBinding(bothNil);
                code.goto_(equal);
                code.labelBinding(nonNilLeft);
                loadLocal(right);
                // A non-nil left value is never equal to a nil right value;
                // only the both-non-nil path may continue structurally.
                code.ifnull(unequal);
            }
            if (base instanceof ArrayType array) {
                emitArrayStructuralEquality(left, right, array, equal, unequal, span);
            } else if (base instanceof TupleType tuple) {
                emitTupleStructuralEquality(left, right, tuple, equal, unequal, span);
            } else {
                throw unsupported(span, "unsupported structural equality type: " + base);
            }
        }

        private void emitArrayStructuralEquality(BindingStorage left, BindingStorage right,
                                                 ArrayType array, Label equal, Label unequal,
                                                 SourceSpan span) {
            JvmType leftArray = left.physical().physicalComponents().getFirst();
            JvmType rightArray = right.physical().physicalComponents().getFirst();
            if (!leftArray.isReference() || !rightArray.isReference()) {
                throw invalidPlan(span, "array equality has no reference representation");
            }
            JvmTypePlan elementPlan = owner.mapper.map(array.elementType(),
                    JvmMappingContext.INTERNAL_ARRAY_ELEMENT);
            if (!elementPlan.isSingleValue() || elementPlan.descriptor().equals("V")) {
                throw invalidPlan(span, "array equality element has no single representation");
            }
            int length = allocateLocal(JvmType.primitive("I"));
            loadLocal(left);
            code.arraylength();
            storePrimitive(PrimitiveType.I32, length);
            loadLocal(right);
            code.arraylength();
            loadPrimitive(PrimitiveType.I32, length);
            code.if_icmpne(unequal);
            int cursor = allocateLocal(JvmType.primitive("I"));
            emitInt(0);
            storePrimitive(PrimitiveType.I32, cursor);
            Label loop = code.newLabel();
            Label next = code.newLabel();
            code.labelBinding(loop);
            loadPrimitive(PrimitiveType.I32, cursor);
            loadPrimitive(PrimitiveType.I32, length);
            code.if_icmpge(equal);
            loadLocal(left);
            loadPrimitive(PrimitiveType.I32, cursor);
            code.arrayLoad(typeKind(elementPlan.descriptor()));
            List<Integer> leftElementSlots = allocateLocals(elementPlan);
            storeLocal(elementPlan, leftElementSlots);
            loadLocal(right);
            loadPrimitive(PrimitiveType.I32, cursor);
            code.arrayLoad(typeKind(elementPlan.descriptor()));
            List<Integer> rightElementSlots = allocateLocals(elementPlan);
            storeLocal(elementPlan, rightElementSlots);
            emitEqualityPair(BindingStorage.local(array.elementType(), elementPlan, leftElementSlots),
                    BindingStorage.local(array.elementType(), elementPlan, rightElementSlots),
                    next, unequal, span);
            code.labelBinding(next);
            code.iinc(cursor, 1);
            code.goto_(loop);
        }

        private void emitTupleStructuralEquality(BindingStorage left, BindingStorage right,
                                                 TupleType tuple, Label equal, Label unequal,
                                                 SourceSpan span) {
            String tupleName = owner.mapper.names().tupleBinaryName(tuple.canonicalSpelling());
            for (int index = 0; index < tuple.arity(); index++) {
                JvmTypePlan fieldPlan = owner.mapper.map(tuple.memberType(index),
                        JvmMappingContext.TUPLE_FIELD);
                List<Integer> leftSlots = allocateLocals(fieldPlan);
                List<Integer> rightSlots = allocateLocals(fieldPlan);
                loadLocal(left);
                code.invokevirtual(cd(tupleName), "$lyra$get$" + index,
                        method("()" + fieldPlan.descriptor()));
                storeLocal(fieldPlan, leftSlots);
                loadLocal(right);
                code.invokevirtual(cd(tupleName), "$lyra$get$" + index,
                        method("()" + fieldPlan.descriptor()));
                storeLocal(fieldPlan, rightSlots);
                Label memberEqual = code.newLabel();
                Label memberNext = code.newLabel();
                emitEqualityPair(BindingStorage.local(tuple.memberType(index), fieldPlan, leftSlots),
                        BindingStorage.local(tuple.memberType(index), fieldPlan, rightSlots),
                        memberEqual, unequal, span);
                code.labelBinding(memberEqual);
                code.goto_(memberNext);
                code.labelBinding(memberNext);
            }
            code.goto_(equal);
        }

        private JvmTypePlan emitNumericComparison(IrNode.Operator operator) {
            PrimitiveType primitive = primitiveBase(operator.operands().getFirst().type());
            List<BindingStorage> values = new ArrayList<>();
            JvmTypePlan target = owner.mapper.map(primitive, JvmMappingContext.INTERNAL_VALUE);
            for (IrNode operand : operator.operands()) {
                emitAt(operand, target);
                if (primitive == PrimitiveType.U8 || primitive == PrimitiveType.U16) {
                    normalizeUnsigned(primitive);
                }
                List<Integer> slots = allocateLocals(target);
                storeLocal(target, slots);
                values.add(BindingStorage.local(operand.type(), target, slots));
            }
            Label falseLabel = code.newLabel();
            Label end = code.newLabel();
            for (int index = 1; index < values.size(); index++) {
                loadLocal(values.get(index - 1)); loadLocal(values.get(index));
                emitNumericFalseBranch(primitive, operator.operator(), falseLabel);
            }
            emitInt(1); code.goto_(end);
            code.labelBinding(falseLabel); emitInt(0); code.labelBinding(end);
            return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private void emitNumericFalseBranch(PrimitiveType primitive, TokenKind token, Label falseLabel) {
            if (primitive == PrimitiveType.U32 || primitive == PrimitiveType.U64) {
                ClassDesc owner = primitive == PrimitiveType.U32 ? CD_INTEGER : CD_LONG;
                String descriptor = primitive == PrimitiveType.U32 ? "(II)I" : "(JJ)I";
                code.invokestatic(owner, "compareUnsigned", method(descriptor));
                code.branch(switch (token) {
                    case LESS -> java.lang.classfile.Opcode.IFGE;
                    case LESS_EQUAL -> java.lang.classfile.Opcode.IFGT;
                    case GREATER -> java.lang.classfile.Opcode.IFLE;
                    case GREATER_EQUAL -> java.lang.classfile.Opcode.IFLT;
                    default -> throw new IllegalArgumentException("not relational: " + token);
                }, falseLabel);
                return;
            }
            if (!wide(primitive) && primitive != PrimitiveType.F32 && primitive != PrimitiveType.F64) {
                java.lang.classfile.Opcode opposite = switch (token) {
                    case LESS -> java.lang.classfile.Opcode.IF_ICMPGE;
                    case LESS_EQUAL -> java.lang.classfile.Opcode.IF_ICMPGT;
                    case GREATER -> java.lang.classfile.Opcode.IF_ICMPLE;
                    case GREATER_EQUAL -> java.lang.classfile.Opcode.IF_ICMPLT;
                    default -> throw new IllegalArgumentException("not relational: " + token);
                };
                code.branch(opposite, falseLabel);
                return;
            }
            if (wide(primitive)) {
                code.lcmp();
                code.branch(switch (token) {
                    case LESS -> java.lang.classfile.Opcode.IFGE;
                    case LESS_EQUAL -> java.lang.classfile.Opcode.IFGT;
                    case GREATER -> java.lang.classfile.Opcode.IFLE;
                    case GREATER_EQUAL -> java.lang.classfile.Opcode.IFLT;
                    default -> throw new IllegalArgumentException("not relational: " + token);
                }, falseLabel);
            } else if (primitive == PrimitiveType.F32) {
                // Use the NaN result ordering that makes every relational
                // comparison false: fcmpg for the less-than directions and
                // fcmpl for the greater-than directions.
                if (token == TokenKind.LESS || token == TokenKind.LESS_EQUAL) {
                    code.fcmpg();
                } else {
                    code.fcmpl();
                }
                code.branch(switch (token) {
                    case LESS -> java.lang.classfile.Opcode.IFGE;
                    case LESS_EQUAL -> java.lang.classfile.Opcode.IFGT;
                    case GREATER -> java.lang.classfile.Opcode.IFLE;
                    case GREATER_EQUAL -> java.lang.classfile.Opcode.IFLT;
                    default -> throw new IllegalArgumentException("not relational: " + token);
                }, falseLabel);
            } else {
                if (token == TokenKind.LESS || token == TokenKind.LESS_EQUAL) {
                    code.dcmpg();
                } else {
                    code.dcmpl();
                }
                code.branch(switch (token) {
                    case LESS -> java.lang.classfile.Opcode.IFGE;
                    case LESS_EQUAL -> java.lang.classfile.Opcode.IFGT;
                    case GREATER -> java.lang.classfile.Opcode.IFLE;
                    case GREATER_EQUAL -> java.lang.classfile.Opcode.IFLT;
                    default -> throw new IllegalArgumentException("not relational: " + token);
                }, falseLabel);
            }
        }

        private void emitRuntimeCheckArithmetic(IrNode.RuntimeCheck check) {
            emitNode(check.operand());
        }

        private JvmTypePlan emitAt(IrNode node, JvmTypePlan target) {
            JvmTypePlan actual = emitNode(node);
            adapt(actual, target);
            return target;
        }

        private void adapt(JvmTypePlan source, JvmTypePlan target) {
            if (target.isSingleValue() && target.descriptor().equals("V")) {
                discard(source);
                return;
            }
            if (source.componentDescriptors().equals(target.componentDescriptors())) {
                return;
            }
            if (source.isSplitValue() && target.isSingleValue()) {
                boxNullable(source, target);
                return;
            }
            if (source.isSingleValue() && target.isSplitValue()) {
                if (source.physicalComponents().getFirst().isReference()) {
                    unboxNullable(source, target);
                } else {
                    liftPrimitiveToNullable(source, target);
                }
                return;
            }
            if (source.isSingleValue() && target.isSingleValue()) {
                adaptPhysical(source.physicalComponents().getFirst(), target.physicalComponents().getFirst());
                return;
            }
            if (source.isSplitValue() && target.isSplitValue()) {
                // Presence components are both Z; only payload widening needs
                // a physical conversion.
                if (!source.physicalComponents().getFirst().descriptor()
                        .equals(target.physicalComponents().getFirst().descriptor())) {
                    adaptPhysical(source.physicalComponents().getFirst(), target.physicalComponents().getFirst());
                }
                adaptPhysical(source.physicalComponents().get(1), target.physicalComponents().get(1));
                return;
            }
            throw unsupported(module.span(), "cannot adapt JVM value representation");
        }

        private void adaptPhysicalPlan(JvmTypePlan source, JvmTypePlan target) {
            if (source.componentDescriptors().equals(target.componentDescriptors())) return;
            if (source.isSingleValue() && target.isSingleValue()) {
                adaptPhysical(source.physicalComponents().getFirst(), target.physicalComponents().getFirst());
            } else {
                adapt(source, target);
            }
        }

        private void adaptPhysical(JvmType source, JvmType target) {
            if (source.descriptor().equals(target.descriptor())) return;
            if (source.isReference() && target.isReference()) {
                if (!source.descriptor().equals(target.descriptor())) code.checkcast(type(target.descriptor()));
                return;
            }
            emitRawConversion(source.descriptor(), target.descriptor());
        }

        private void boxNullable(JvmTypePlan source, JvmTypePlan target) {
            int payload = allocateLocal(source.physicalComponents().get(1));
            int present = code.allocateLocal(TypeKind.INT);
            storePhysical(source.physicalComponents().get(1), payload);
            code.istore(present);
            Label presentLabel = code.newLabel();
            Label end = code.newLabel();
            code.iload(present); code.ifne(presentLabel);
            code.aconst_null(); code.goto_(end);
            code.labelBinding(presentLabel);
            loadPhysical(source.physicalComponents().get(1), payload);
            boxPrimitive(source.physicalComponents().get(1));
            code.labelBinding(end);
        }

        private void liftPrimitiveToNullable(JvmTypePlan source, JvmTypePlan target) {
            int value = allocateLocal(source.physicalComponents().getFirst());
            storePhysical(source.physicalComponents().getFirst(), value);
            code.iconst_1();
            loadPhysical(source.physicalComponents().getFirst(), value);
            adaptPhysical(source.physicalComponents().getFirst(), target.physicalComponents().get(1));
        }

        private void unboxNullable(JvmTypePlan source, JvmTypePlan target) {
            int value = allocateLocal(source.physicalComponents().getFirst());
            storePhysical(source.physicalComponents().getFirst(), value);
            Label nonNil = code.newLabel();
            Label end = code.newLabel();
            loadPhysical(source.physicalComponents().getFirst(), value);
            code.ifnonnull(nonNil);
            code.iconst_0();
            emitZero(target.physicalComponents().get(1));
            code.goto_(end);
            code.labelBinding(nonNil);
            code.iconst_1();
            loadPhysical(source.physicalComponents().getFirst(), value);
            unboxPrimitive(target.physicalComponents().get(1));
            normalizeUnsignedPlan(target);
            code.labelBinding(end);
        }

        private void emitRawConversion(String source, String target) {
            if (source.equals(target)) return;
            // JVM narrowing opcodes consume an int.  Widen first when a
            // long/float/double source is converted to a byte/short/char.
            if (target.equals("B") || target.equals("S") || target.equals("C")) {
                if (!source.equals("I") && !source.equals("B") && !source.equals("S")
                        && !source.equals("C") && !source.equals("Z")) {
                    emitRawConversion(source, "I");
                    emitRawConversion("I", target);
                    return;
                }
            }
            if (source.equals("I") && target.equals("J")) code.i2l();
            else if (source.equals("I") && target.equals("F")) code.i2f();
            else if (source.equals("I") && target.equals("D")) code.i2d();
            else if (source.equals("J") && target.equals("I")) code.l2i();
            else if (source.equals("J") && target.equals("F")) code.l2f();
            else if (source.equals("J") && target.equals("D")) code.l2d();
            else if (source.equals("F") && target.equals("I")) code.f2i();
            else if (source.equals("F") && target.equals("J")) code.f2l();
            else if (source.equals("F") && target.equals("D")) code.f2d();
            else if (source.equals("D") && target.equals("I")) code.d2i();
            else if (source.equals("D") && target.equals("J")) code.d2l();
            else if (source.equals("D") && target.equals("F")) code.d2f();
            else if (source.equals("I") && target.equals("B")) code.i2b();
            else if (source.equals("I") && target.equals("S")) code.i2s();
            else if (source.equals("I") && target.equals("C")) code.i2c();
            else if (source.equals("B") || source.equals("S") || source.equals("C")
                    || source.equals("Z")) emitRawConversion("I", target);
            else {
                throw unsupported(module.span(), "unsupported JVM primitive conversion "
                        + source + " -> " + target);
            }
        }

        private void emitFloatingOperand(IrNode operand, JvmTypePlan target) {
            JvmTypePlan actual = emitNode(operand);
            PrimitiveType logical = primitiveBase(operand.type());
            if (logical == PrimitiveType.U8 || logical == PrimitiveType.U16) {
                normalizeUnsigned(logical);
            }
            if (target.isSingleValue() && (target.descriptor().equals("F")
                    || target.descriptor().equals("D"))
                    && (logical == PrimitiveType.U32 || logical == PrimitiveType.U64)) {
                emitUnsignedToFloating(logical, target.descriptor());
            } else {
                adapt(actual, target);
            }
        }

        private void emitUnsignedToFloating(PrimitiveType source, String targetDescriptor) {
            if (source == PrimitiveType.U32) {
                code.invokestatic(CD_INTEGER, "toUnsignedLong", method("(I)J"));
                emitRawConversion("J", targetDescriptor);
                return;
            }
            if (source != PrimitiveType.U64) {
                throw new IllegalArgumentException("not an unsigned wide integer: " + source);
            }
            int value = allocateLocal(PrimitiveType.U64);
            storePrimitive(PrimitiveType.U64, value);
            Label nonNegative = code.newLabel();
            Label end = code.newLabel();
            loadPrimitive(PrimitiveType.U64, value);
            code.lconst_0();
            code.lcmp();
            code.ifge(nonNegative);
            // Halve the unsigned value and retain a sticky low bit before rounding.
            // Adding 2^63 to an already rounded signed payload can round twice.
            loadPrimitive(PrimitiveType.U64, value);
            code.iconst_1();
            code.lushr();
            loadPrimitive(PrimitiveType.U64, value);
            code.lconst_1();
            code.land();
            code.lor();
            emitRawConversion("J", targetDescriptor);
            if (targetDescriptor.equals("F")) {
                code.loadConstant(2.0f);
                code.fmul();
            } else {
                code.loadConstant(2.0);
                code.dmul();
            }
            code.goto_(end);
            code.labelBinding(nonNegative);
            loadPrimitive(PrimitiveType.U64, value);
            emitRawConversion("J", targetDescriptor);
            code.labelBinding(end);
        }

        private void emitPrimitiveConversion(PrimitiveType source, PrimitiveType target) {
            if (source.isUnsignedInteger()) normalizeUnsigned(source);
            String from = owner.mapper.map(source, JvmMappingContext.INTERNAL_VALUE).descriptor();
            String to = owner.mapper.map(target, JvmMappingContext.INTERNAL_VALUE).descriptor();
            if (from.equals(to)) return;
            if (source == PrimitiveType.U32
                    && (target == PrimitiveType.I64 || target == PrimitiveType.U64
                    || target == PrimitiveType.F32 || target == PrimitiveType.F64)) {
                code.invokestatic(CD_INTEGER, "toUnsignedLong", method("(I)J"));
                emitRawConversion("J", to);
                return;
            }
            if (source == PrimitiveType.U64
                    && (target == PrimitiveType.F32 || target == PrimitiveType.F64)) {
                emitUnsignedToFloating(source, to);
                return;
            }
            if (source.isFloating() && target == PrimitiveType.U32) {
                emitFloatingToUnsigned32(source);
                return;
            }
            if (source.isFloating() && target == PrimitiveType.U64) {
                emitFloatingToUnsigned64(source);
                return;
            }
            emitRawConversion(from, to);
        }

        private void normalizeUnsigned(PrimitiveType primitive) {
            if (primitive == PrimitiveType.U8) {
                emitInt(0xff); code.iand();
            } else if (primitive == PrimitiveType.U16) {
                emitInt(0xffff); code.iand();
            }
        }

        private void normalizeUnsignedPlan(JvmTypePlan plan) {
            switch (plan.baseCanonicalLyraType()) {
                case "U8" -> normalizeUnsigned(PrimitiveType.U8);
                case "U16" -> normalizeUnsigned(PrimitiveType.U16);
                default -> { }
            }
        }

        private void normalizeUnsignedLocal(PrimitiveType primitive, int slot) {
            loadPrimitive(primitive, slot);
            normalizeUnsigned(primitive);
            storePrimitive(primitive, slot);
        }

        private void checkFinite(PrimitiveType primitive, SourceSpan span) {
            checkFinite(primitive, span, "LYR-ARITH",
                    "floating arithmetic produced a non-finite result");
        }

        private void checkFinite(PrimitiveType primitive, SourceSpan span,
                                String failureCode, String summary) {
            // The finite predicate consumes its operand.  Preserve the result
            // while checking it so callers can continue with the same value.
            int result = allocateLocal(primitive);
            storePrimitive(primitive, result);
            loadPrimitive(primitive, result);
            if (primitive == PrimitiveType.F32) {
                code.invokestatic(CD_FLOAT, "isFinite", method("(F)Z"));
            } else {
                code.invokestatic(CD_DOUBLE, "isFinite", method("(D)Z"));
            }
            Label finite = code.newLabel();
            code.ifne(finite);
            throwFailure(span, failureCode, summary);
            code.labelBinding(finite);
            loadPrimitive(primitive, result);
        }

        private void checkFiniteConversionFromLocal(PrimitiveType primitive, int slot,
                                                    SourceSpan span) {
            loadPrimitive(primitive, slot);
            if (primitive == PrimitiveType.F32) {
                code.invokestatic(CD_FLOAT, "isFinite", method("(F)Z"));
            } else {
                code.invokestatic(CD_DOUBLE, "isFinite", method("(D)Z"));
            }
            Label finite = code.newLabel();
            code.ifne(finite);
            code.goto_(failureLabel(span, "LYR-CONVERT",
                    "numeric conversion requires a finite value"));
            code.labelBinding(finite);
        }

        private void emitFloatingToUnsigned32(PrimitiveType source) {
            int value = allocateLocal(source);
            storePrimitive(source, value);
            Label high = code.newLabel();
            Label end = code.newLabel();
            loadPrimitive(source, value);
            emitFloatingConstant(source, 0x1.0p31);
            if (source == PrimitiveType.F32) code.fcmpl(); else code.dcmpl();
            code.ifge(high);
            loadPrimitive(source, value);
            emitRawConversion(source == PrimitiveType.F32 ? "F" : "D", "I");
            code.goto_(end);
            code.labelBinding(high);
            loadPrimitive(source, value);
            emitFloatingConstant(source, 0x1.0p31);
            if (source == PrimitiveType.F32) code.fsub(); else code.dsub();
            emitRawConversion(source == PrimitiveType.F32 ? "F" : "D", "I");
            emitInt(Integer.MIN_VALUE);
            code.iadd();
            code.labelBinding(end);
        }

        private void emitFloatingToUnsigned64(PrimitiveType source) {
            int value = allocateLocal(source);
            storePrimitive(source, value);
            Label high = code.newLabel();
            Label end = code.newLabel();
            loadPrimitive(source, value);
            emitFloatingConstant(source, 0x1.0p63);
            if (source == PrimitiveType.F32) code.fcmpl(); else code.dcmpl();
            code.ifge(high);
            loadPrimitive(source, value);
            emitRawConversion(source == PrimitiveType.F32 ? "F" : "D", "J");
            code.goto_(end);
            code.labelBinding(high);
            loadPrimitive(source, value);
            emitFloatingConstant(source, 0x1.0p63);
            if (source == PrimitiveType.F32) code.fsub(); else code.dsub();
            emitRawConversion(source == PrimitiveType.F32 ? "F" : "D", "J");
            code.ldc(Long.MIN_VALUE);
            code.ladd();
            code.labelBinding(end);
        }

        private void emitRawReturn(String descriptor) {
            switch (descriptor.charAt(0)) {
                case 'V' -> code.return_();
                case 'L', '[' -> code.areturn();
                case 'J' -> code.lreturn();
                case 'F' -> code.freturn();
                case 'D' -> code.dreturn();
                default -> code.ireturn();
            }
        }

        private void returnPhysicalDescriptor(String descriptor) {
            emitRawReturn(descriptor);
        }

        private void validateExternalAccessor(IrDeclaration declaration, boolean write) {
            String value = owner.mapper.map(declaration.contract().orElseThrow().valueType(),
                    JvmMappingContext.JAVA_VALUE).descriptor();
            emitExternalAccessor(declaration, write);
            code.invokevirtual(cd("java.lang.invoke.MethodHandle"), "type",
                    method("()Ljava/lang/invoke/MethodType;"));
            code.ldc(method(write ? "(" + value + ")V" : "()" + value));
            code.invokevirtual(cd("java.lang.invoke.MethodType"), "equals", method("(Ljava/lang/Object;)Z"));
            Label valid = code.newLabel();
            code.ifne(valid);
            throwFailureRecorded(memberSpan(), "LYR-LINK", "session storage JVM type mismatch");
            code.labelBinding(valid);
        }

        private void emitExternalAccessor(IrDeclaration declaration, boolean write) {
            emitLoadModuleState(declaration.moduleId());
            code.ldc(declaration.id().ordinal());
            code.ldc(declaration.externalBinding().orElseThrow().storageIdentity().map(value -> value.ordinal()).orElse(-1L));
            code.ldc(declaration.contract().orElseThrow().valueType().canonicalSpelling());
            code.loadConstant(write ? 1 : 0);
            code.invokevirtual(cd(owner.plan.moduleStates().get(declaration.moduleId())),
                    "$lyra$sessionAccessor", method("(JJLjava/lang/String;Z)Ljava/lang/invoke/MethodHandle;"));
        }

        private void validateImportedAccessor(io.mindspice.lyra.compiler.ir.IrSessionExecution.ExternalAccess access, boolean write) {
            emitImportedAccessor(access, write);
            String descriptor = owner.mapper.map(access.target().declaration().contract().orElseThrow().valueType(),
                    JvmMappingContext.JAVA_VALUE).descriptor();
            code.invokevirtual(cd("java.lang.invoke.MethodHandle"), "type", method("()Ljava/lang/invoke/MethodType;"));
            code.ldc(method(write ? "(" + descriptor + ")V" : "()" + descriptor));
            code.invokevirtual(cd("java.lang.invoke.MethodType"), "equals", method("(Ljava/lang/Object;)Z"));
            Label valid = code.newLabel();
            code.ifne(valid);
            throwFailureRecorded(memberSpan(), "LYR-LINK", "import storage JVM type mismatch");
            code.labelBinding(valid);
        }

        private void emitImportedAccessor(io.mindspice.lyra.compiler.ir.IrSessionExecution.ExternalAccess access, boolean write) {
            var declaration = access.target().declaration();
            emitLoadModuleState(module.moduleId());
            code.ldc(declaration.id().ordinal());
            code.ldc(declaration.contract().orElseThrow().isMutable()
                    ? io.mindspice.lyra.compiler.session.StorageIdentity.forDeclaration(declaration.id()).ordinal() : -1L);
            code.ldc(declaration.contract().orElseThrow().valueType().canonicalSpelling());
            code.loadConstant(write ? 1 : 0);
            code.invokevirtual(cd(owner.plan.moduleStates().get(module.moduleId())),
                    "$lyra$sessionAccessor", method("(JJLjava/lang/String;Z)Ljava/lang/invoke/MethodHandle;"));
        }

        private boolean emitImportedRead(DeclarationId id, JvmTypePlan desired) {
            var declaration = declarations.get(id);
            var access = owner.ir.externalAccess(module.moduleId(),
                    declaration == null ? id : declaration.originDeclaration().orElse(id));
            if (access.isEmpty()) return false;
            var value = access.orElseThrow();
            var type = owner.mapper.map(value.target().declaration().contract().orElseThrow().valueType(),
                    JvmMappingContext.JAVA_VALUE);
            emitImportedAccessor(value, false);
            code.invokevirtual(cd("java.lang.invoke.MethodHandle"), "invokeExact", method("()" + type.descriptor()));
            adapt(type, desired);
            return true;
        }

        private void emitLoadDeclaration(DeclarationId id, JvmTypePlan desired) {
            IrDeclaration resolved = declarations.get(id);
            if (emitImportedRead(resolved == null ? id : resolved.originDeclaration().orElse(id), desired)) return;
            IrDeclaration external = declarations.get(id);
            if (external != null && external.externalBinding().isPresent()) {
                JvmTypePlan value = owner.mapper.map(external.contract().orElseThrow().valueType(), JvmMappingContext.JAVA_VALUE);
                emitExternalAccessor(external, false);
                code.invokevirtual(cd("java.lang.invoke.MethodHandle"), "invokeExact", method("()" + value.descriptor()));
                adapt(value, desired);
                return;
            }
            BindingStorage local = locals.get(id);
            if (local != null) {
                loadLocal(local);
                adapt(local.physical(), desired);
                return;
            }
            Integer localCell = localCells.get(id);
            if (localCell != null) {
                loadPhysical(JvmType.reference(cellClassName(id)), localCell);
                emitCellValue(id);
                adapt(storagePlan(id), desired);
                return;
            }
            if (closureMethod && lambda != null) {
                IrCapture capture = lambda.captures().stream()
                        .map(captures::get)
                        .filter(Objects::nonNull)
                        .filter(value -> value.declarationId().equals(id))
                        .findFirst().orElse(null);
                if (capture != null) {
                    if (capture.isSharedCell()) {
                        emitLoadClosureCaptureCell(capture.id());
                        emitCellValue(id);
                        adapt(storagePlan(id), desired);
                    } else if (owner.usesLocalFunctionSlot(capture)) {
                        emitLoadClosureFunctionSlot(capture.id());
                        emitInt(0);
                        code.aaload();
                        JvmTypePlan source = owner.mapper.map(
                                capture.contract().valueType(),
                                JvmMappingContext.INTERNAL_CAPTURE);
                        adapt(source, desired);
                    } else {
                        emitLoadClosureCaptureValue(
                                capture.id(), capture.contract().valueType());
                        JvmTypePlan source = owner.mapper.map(
                                capture.contract().valueType(),
                                JvmMappingContext.INTERNAL_CAPTURE);
                        adapt(source, desired);
                    }
                    return;
                }
                IrDeclaration self = lambda.ownerDeclaration()
                        .filter(id::equals)
                        .map(declarations::get)
                        .orElse(null);
                if (self != null && !self.isMutable() && self.isFunction()) {
                    aloadReceiver();
                    adapt(storagePlan(id), desired);
                    return;
                }
            }
            IrDeclaration declaration = declarations.get(id);
            if (declaration == null) {
                throw invalidPlan(module.span(), "declaration identity is absent: " + id);
            }
            if (stateMethod
                    && declaration.moduleId().equals(module.moduleId())
                    && declaration.scopeId().equals(module.rootScope())
                    && !declaration.imported()
                    && !initializedRootDeclarations.contains(id)) {
                initializeRootDeclaration(id);
            }
            IrImportBinding importBinding = owner.imports.get(id);
            if (importBinding != null) {
                if (importBinding.kind() != ImportBindingKind.SELECTIVE_VALUE
                        || importBinding.targetDeclaration().isEmpty()) {
                    throw invalidPlan(module.span(),
                            "module namespace binding cannot be used as a value: " + id);
                }
                emitLoadDeclarationFromModule(importBinding.targetModule(),
                        importBinding.targetDeclaration().orElseThrow(), desired);
                return;
            }
            emitLoadDeclarationFromModule(declaration.moduleId(), id, desired);
        }

        private void emitLoadDeclarationFromModule(ModuleId targetModule, DeclarationId id,
                                                    JvmTypePlan desired) {
            if (emitImportedRead(id, desired)) return;
            if (!owner.modules.containsKey(targetModule)) {
                throw invalidPlan(module.span(), "declaration module is absent: " + targetModule);
            }
            IrDeclaration declaration = declarations.get(id);
            if (declaration == null || declaration.contract().isEmpty()) {
                throw invalidPlan(module.span(), "declaration has no storage contract: " + id);
            }
            StateLocation location = reachableStateLocation(targetModule, id, declaration);
            JvmTypePlan storage = owner.mapper.mapBinding(declaration.contract().orElseThrow()).value();
            emitLoadModuleState(location.moduleId());
            emitStateValueFromState(location.moduleId(), location.declarationId());
            adapt(storage, desired);
        }

        /**
         * Selects a state reachable by one of the current module's explicit
         * import links. References retain the canonical origin declaration,
         * while a chained re-export is physically reached through its
         * immediate imported module. The state-value walker follows the
         * remaining links from there.
         */
        private StateLocation reachableStateLocation(ModuleId targetModule, DeclarationId id,
                                                      IrDeclaration declaration) {
            DeclarationId stateDeclaration = stateFields(targetModule, id).isEmpty()
                    ? owner.ir.exports().stream()
                    .filter(export -> export.moduleId().equals(targetModule))
                    .filter(export -> export.originModule().equals(declaration.moduleId())
                            && export.originDeclaration().equals(id))
                    .map(IrExport::declarationId)
                    .findFirst()
                    .orElseThrow(() -> invalidPlan(module.span(),
                            "module state field is absent: " + id))
                    : id;
            if (targetModule.equals(module.moduleId())
                    || importsForModule(module.moduleId()).stream()
                    .anyMatch(binding -> binding.targetModule().equals(targetModule))) {
                return new StateLocation(targetModule, stateDeclaration);
            }

            List<StateLocation> reachable = importsForModule(module.moduleId()).stream()
                    .map(IrImportBinding::targetModule)
                    .distinct()
                    .sorted()
                    .flatMap(importedModule -> owner.ir.exports().stream()
                            .filter(export -> export.moduleId().equals(importedModule))
                            .filter(export -> export.originModule().equals(targetModule)
                                    && export.originDeclaration().equals(id))
                            .map(export -> new StateLocation(importedModule,
                                    export.declarationId())))
                    .distinct()
                    .toList();
            if (reachable.isEmpty()) {
                throw invalidPlan(module.span(),
                        "foreign module is not reachable through an import state link: "
                                + targetModule);
            }
            return reachable.getFirst();
        }

        private void emitReferenceByDeclaration(DeclarationId id, LyraType logical,
                                                JvmTypePlan target) {
            emitLoadDeclaration(id, target);
        }

        private JvmTypePlan storagePlan(DeclarationId id) {
            IrDeclaration declaration = declarations.get(id);
            if (declaration == null || declaration.contract().isEmpty()) {
                throw invalidPlan(module.span(), "declaration has no storage contract: " + id);
            }
            return owner.mapper.mapBinding(declaration.contract().orElseThrow()).value();
        }

        private void emitLoadModuleState(ModuleId targetModule) {
            if (targetModule.equals(module.moduleId())) {
                if (stateMethod) {
                    aloadReceiver();
                } else if (closureMethod) {
                    emitLoadClosureState();
                } else if (classPlan.kind() == GeneratedClassKind.MODULE_FACADE) {
                    loadFacadeState();
                } else {
                    throw unsupported(module.span(), "module state access outside an owned context");
                }
                return;
            }
            IrImportBinding binding = importsForModule(module.moduleId()).stream()
                    .filter(value -> value.targetModule().equals(targetModule))
                    .findFirst().orElseThrow(() -> invalidPlan(module.span(),
                            "foreign module is not linked from the current module: " + targetModule));
            GeneratedClassPlan currentState = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(module.moduleId())).orElseThrow();
            if (stateMethod) {
                aloadReceiver();
            } else if (closureMethod) {
                emitLoadClosureState();
            } else if (classPlan.kind() == GeneratedClassKind.MODULE_FACADE) {
                loadFacadeState();
            } else {
                throw unsupported(module.span(), "foreign module access outside an owned context");
            }
            GeneratedMemberPlan field = stateFields(module.moduleId(), binding.declarationId()).stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.STATE_IMPORT_FIELD)
                    .findFirst().orElseThrow(() -> invalidPlan(module.span(),
                            "module import state field is absent"));
            code.invokevirtual(cd(currentState.binaryName()),
                    "$lyra$get$binding$" + binding.declarationId().value(),
                    method("()" + field.descriptor()));
        }

        private void loadFacadeStateValueInternal(DeclarationId id) {
            loadFacadeState();
            emitStateFunctionOrValue(id);
        }

        private void storeStateDeclaration(DeclarationId id, JvmTypePlan storage) {
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(module.moduleId())).orElseThrow();
            List<GeneratedMemberPlan> fields = stateFields(id);
            if (fields.stream().anyMatch(value -> value.kind() == GeneratedMemberKind.STATE_CELL_FIELD)) {
                List<Integer> cellValues = allocateLocals(storage);
                storeLocal(storage, cellValues);
                emitNewCell(id, cellValues);
                aloadReceiver();
                code.swap();
                code.putfield(cd(state.binaryName()), fields.getFirst().name(),
                        type(fields.getFirst().descriptor()));
                return;
            }
            storeStateFields(state, fields, storage);
        }

        private void storeStateFields(GeneratedClassPlan state,
                                      List<GeneratedMemberPlan> fields, JvmTypePlan storage) {
            List<Integer> slots = allocateLocals(storage);
            storeLocal(storage, slots);
            for (int index = 0; index < fields.size(); index++) {
                aloadReceiver();
                loadPhysical(storage.physicalComponents().get(index), slots.get(index));
                code.putfield(cd(state.binaryName()), fields.get(index).name(),
                        type(fields.get(index).descriptor()));
            }
        }

        private void storeDeclaration(DeclarationId id, JvmTypePlan storage) {
            IrDeclaration external = declarations.get(id);
            if (external != null && external.externalBinding().isPresent()) {
                JvmTypePlan value = owner.mapper.map(external.contract().orElseThrow().valueType(), JvmMappingContext.JAVA_VALUE);
                adapt(storage, value);
                int temporary = allocateLocal(value);
                storePhysical(value.physicalComponents().getFirst(), temporary);
                emitExternalAccessor(external, true);
                loadPhysical(value.physicalComponents().getFirst(), temporary);
                code.invokevirtual(cd("java.lang.invoke.MethodHandle"), "invokeExact", method("(" + value.descriptor() + ")V"));
                return;
            }
            BindingStorage local = locals.get(id);
            if (local != null) {
                storeLocal(storage, local.slots());
                return;
            }
            if (stateMethod && isRootDeclaration(id)) {
                if (owner.cells.containsKey(id)) {
                    emitStoreCell(id, storage);
                } else {
                    storeStateDeclaration(id, storage);
                }
                return;
            }
            if (closureMethod && owner.cells.containsKey(id)) {
                emitStoreCell(id, storage);
                return;
            }
            if (closureMethod && isRootDeclaration(id)) {
                storeClosureStateDeclaration(id, storage);
                return;
            }
            throw invalidPlan(module.span(), "mutable declaration has no writable storage: " + id);
        }

        private void storeClosureStateDeclaration(DeclarationId id, JvmTypePlan storage) {
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(module.moduleId())).orElseThrow();
            List<GeneratedMemberPlan> fields = stateFields(id);
            if (fields.isEmpty() || fields.stream().anyMatch(value ->
                    value.kind() == GeneratedMemberKind.STATE_CELL_FIELD)) {
                throw invalidPlan(module.span(), "root closure storage has an invalid state shape: " + id);
            }
            List<Integer> slots = allocateLocals(storage);
            storeLocal(storage, slots);
            emitLoadClosureState();
            for (int index = 0; index < slots.size(); index++) {
                loadPhysical(storage.physicalComponents().get(index), slots.get(index));
            }
            String descriptor = "(" + fields.stream().map(GeneratedMemberPlan::descriptor)
                    .reduce("", String::concat) + ")V";
            code.invokevirtual(cd(state.binaryName()),
                    "$lyra$set$binding$" + id.value(), method(descriptor));
        }

        private void emitNewCell(DeclarationId id, List<Integer> values) {
            String cellName = owner.plan.cellClasses().get(id);
            if (cellName == null) throw invalidPlan(module.span(), "cell class is absent: " + id);
            GeneratedClassPlan cell = owner.plan.classPlan(cellName).orElseThrow();
            GeneratedMemberPlan constructor = cell.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.CELL_CONSTRUCTOR)
                    .findFirst().orElseThrow();
            code.new_(cd(cellName)); code.dup();
            for (int index = 0; index < values.size(); index++) {
                GeneratedMemberPlan field = cell.members().stream().filter(GeneratedMemberPlan::isField)
                        .toList().get(index);
                loadPhysical(jvmType(field.descriptor()), values.get(index));
            }
            code.invokespecial(cd(cellName), "<init>", method(constructor.descriptor()));
        }

        private void emitStoreCell(DeclarationId id, JvmTypePlan storage) {
            List<Integer> values = allocateLocals(storage);
            storeLocal(storage, values);
            emitLoadCellForDeclaration(id);
            String cellName = owner.plan.cellClasses().get(id);
            if (cellName == null) {
                throw invalidPlan(module.span(), "cell class is absent: " + id);
            }
            GeneratedClassPlan cell = owner.plan.classPlan(cellName).orElseThrow();
            GeneratedMemberPlan setter = cell.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.CELL_SET).findFirst().orElseThrow();
            for (int index = 0; index < values.size(); index++) {
                loadPhysical(storage.physicalComponents().get(index), values.get(index));
            }
            code.invokevirtual(cd(cell.binaryName()), setter.name(), method(setter.descriptor()));
        }

        private void emitLoadCellForDeclaration(DeclarationId id) {
            Integer localCell = localCells.get(id);
            if (localCell != null) {
                loadPhysical(JvmType.reference(cellClassName(id)), localCell);
                return;
            }
            if (closureMethod && lambda != null) {
                IrCapture capture = captures.values().stream()
                        .filter(value -> value.lambdaId().equals(lambda.id())
                                && value.declarationId().equals(id)
                                && value.isSharedCell())
                        .findFirst().orElse(null);
                if (capture != null) {
                    emitLoadClosureCaptureCell(capture.id());
                    return;
                }
            }
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(module.moduleId())).orElseThrow();
            List<GeneratedMemberPlan> fields = stateFields(id);
            if (fields.size() != 1 || fields.getFirst().kind() != GeneratedMemberKind.STATE_CELL_FIELD) {
                throw unsupported(module.span(), "only root shared cells are supported");
            }
            if (stateMethod) {
                aloadReceiver();
                code.getfield(cd(state.binaryName()), fields.getFirst().name(),
                        type(fields.getFirst().descriptor()));
            } else if (closureMethod) {
                emitLoadClosureState();
                code.invokevirtual(cd(state.binaryName()), "$lyra$get$binding$" + id.value(),
                        method("()" + fields.getFirst().descriptor()));
            } else {
                throw unsupported(module.span(), "cell access outside state/closure");
            }
        }

        private void emitCellValue(DeclarationId id) {
            String cellName = owner.plan.cellClasses().get(id);
            if (cellName == null) {
                throw invalidPlan(module.span(), "cell class is absent: " + id);
            }
            GeneratedClassPlan cell = owner.plan.classPlan(cellName).orElseThrow();
            List<GeneratedMemberPlan> fields = cell.members().stream().filter(GeneratedMemberPlan::isField).toList();
            if (fields.size() == 1) {
                code.invokevirtual(cd(cell.binaryName()), "$lyra$get", method("()" + fields.getFirst().descriptor()));
                return;
            }
            if (fields.size() == 2) {
                GeneratedMemberPlan presence = fields.stream()
                        .filter(value -> value.kind() == GeneratedMemberKind.CELL_PRESENCE_FIELD)
                        .findFirst().orElseThrow();
                GeneratedMemberPlan payload = fields.stream()
                        .filter(value -> value.kind() == GeneratedMemberKind.CELL_VALUE_FIELD)
                        .findFirst().orElseThrow();
                code.dup();
                code.invokevirtual(cd(cell.binaryName()), "$lyra$isPresent",
                        method("()" + presence.descriptor()));
                code.swap();
                code.invokevirtual(cd(cell.binaryName()), "$lyra$payload",
                        method("()" + payload.descriptor()));
                return;
            }
            throw invalidPlan(module.span(), "cell has an unsupported storage shape: " + id);
        }

        private void emitLoadClosureCaptureCell(CaptureId id) {
            GeneratedMemberPlan field = closureCaptureFields(id).stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_FIELD)
                    .findFirst().orElseThrow(() -> invalidPlan(module.span(), "shared capture field absent"));
            aloadReceiver();
            code.getfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
        }

        private void emitLoadClosureFunctionSlot(CaptureId id) {
            GeneratedMemberPlan field = closureCaptureFields(id).stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_FIELD
                            && value.descriptor().startsWith("["))
                    .findFirst().orElseThrow(() -> invalidPlan(
                            module.span(), "linked function capture field absent"));
            aloadReceiver();
            code.getfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
        }

        private void emitLoadFunctionSlotForDeclaration(DeclarationId id) {
            Integer slot = localFunctionSlots.get(id);
            if (slot != null) {
                code.aload(slot);
                return;
            }
            if (closureMethod && lambda != null) {
                IrCapture capture = lambda.captures().stream()
                        .map(captures::get)
                        .filter(Objects::nonNull)
                        .filter(value -> value.declarationId().equals(id)
                                && owner.usesLocalFunctionSlot(value))
                        .findFirst().orElse(null);
                if (capture != null) {
                    emitLoadClosureFunctionSlot(capture.id());
                    return;
                }
            }
            throw invalidPlan(module.span(),
                    "local function capture has no linked slot: " + id);
        }

        private void emitLoadClosureCaptureValue(CaptureId id, LyraType logical) {
            List<GeneratedMemberPlan> fields = closureCaptureFields(id);
            if (fields.size() == 1) {
                aloadReceiver();
                code.getfield(cd(classPlan.binaryName()), fields.getFirst().name(),
                        type(fields.getFirst().descriptor()));
            } else {
                for (GeneratedMemberPlan field : fields) {
                    aloadReceiver();
                    code.getfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
                }
            }
        }

        private List<GeneratedMemberPlan> closureCaptureFields(CaptureId id) {
            String prefix = "$lyra$capture$" + id.value();
            return classPlan.members().stream().filter(GeneratedMemberPlan::isField)
                    .filter(value -> value.name().equals(prefix)
                            || value.name().startsWith(prefix + "$"))
                    .toList();
        }

        private void emitLoadClosureState() {
            GeneratedMemberPlan field = classPlan.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.CLOSURE_STATE_FIELD)
                    .findFirst().orElseThrow(() -> invalidPlan(module.span(), "closure state field absent"));
            aloadReceiver();
            code.getfield(cd(classPlan.binaryName()), field.name(), type(field.descriptor()));
        }

        private List<GeneratedMemberPlan> stateFields(DeclarationId id) {
            return stateFields(module.moduleId(), id);
        }

        private List<GeneratedMemberPlan> stateFields(ModuleId moduleId, DeclarationId id) {
            String stateName = owner.plan.moduleStates().get(moduleId);
            if (stateName == null) {
                throw invalidPlan(memberSpan(), "module state class is absent: " + moduleId);
            }
            GeneratedClassPlan state = owner.plan.classPlan(stateName).orElseThrow();
            String prefix = "$lyra$binding$" + id.value();
            return state.members().stream().filter(GeneratedMemberPlan::isField)
                    .filter(value -> value.name().equals(prefix)
                            || value.name().startsWith(prefix + "$"))
                    .toList();
        }

        private List<IrImportBinding> importsForModule(ModuleId moduleId) {
            return owner.ir.imports().stream()
                    .filter(value -> owner.declarations.get(value.declarationId()) != null)
                    .filter(value -> owner.declarations.get(value.declarationId()).moduleId()
                            .equals(moduleId))
                    .sorted()
                    .toList();
        }

        private String cellClassName(DeclarationId id) {
            String name = owner.plan.cellClasses().get(id);
            if (name == null) {
                throw invalidPlan(memberSpan(), "cell class is absent: " + id);
            }
            return name;
        }

        private DeclarationId declarationIdFromMember(String name) {
            String digits = name.substring(name.lastIndexOf('$') + 1);
            if (digits.startsWith("binding$")) digits = digits.substring("binding$".length());
            if (digits.contains("$")) digits = digits.substring(digits.lastIndexOf('$') + 1);
            final String ordinal = digits;
            try {
                return declarations.keySet().stream()
                        .filter(value -> Long.toString(value.value()).equals(ordinal))
                        .findFirst().orElseThrow();
            } catch (RuntimeException failure) {
                throw invalidPlan(memberSpan(), "member has no declaration identity: " + name);
            }
        }

        private void installLocal(DeclarationId id, JvmTypePlan physical, LyraType logical,
                                  List<Integer> slots) {
            locals.put(id, BindingStorage.local(logical, physical, slots));
        }

        private List<Integer> allocateLocals(JvmTypePlan physical) {
            List<Integer> slots = new ArrayList<>();
            for (JvmType component : physical.physicalComponents()) {
                slots.add(allocateLocal(component));
            }
            return List.copyOf(slots);
        }

        private int allocateLocal(PrimitiveType primitive) {
            JvmTypePlan plan = owner.mapper.map(primitive, JvmMappingContext.INTERNAL_VALUE);
            return allocateLocal(plan.physicalComponents().getLast());
        }

        private int allocateLocal(JvmTypePlan plan) {
            if (plan.physicalComponents().size() != 1) {
                throw new IllegalArgumentException("one local slot requested for a split value");
            }
            return allocateLocal(plan.physicalComponents().getFirst());
        }

        private int allocateLocal(JvmType type) {
            return code.allocateLocal(typeKind(type.descriptor()));
        }

        private void storeLocal(JvmTypePlan plan, List<Integer> slots) {
            List<JvmType> components = plan.physicalComponents();
            if (components.size() != slots.size()) throw new IllegalArgumentException("local component mismatch");
            for (int index = components.size() - 1; index >= 0; index--) {
                storePhysical(components.get(index), slots.get(index));
            }
        }

        private void loadLocal(BindingStorage storage) {
            for (int index = 0; index < storage.physical().physicalComponents().size(); index++) {
                loadPhysical(storage.physical().physicalComponents().get(index), storage.slots().get(index));
            }
        }

        private void storeLocal(JvmTypePlan plan, int slot) {
            storePhysical(plan.physicalComponents().getFirst(), slot);
        }

        private void storePrimitive(PrimitiveType primitive, int slot) {
            JvmType type = owner.mapper.map(primitive, JvmMappingContext.INTERNAL_VALUE)
                    .physicalComponents().getFirst();
            storePhysical(type, slot);
        }

        private void loadPrimitive(PrimitiveType primitive, int slot) {
            JvmType type = owner.mapper.map(primitive, JvmMappingContext.INTERNAL_VALUE)
                    .physicalComponents().getFirst();
            loadPhysical(type, slot);
        }

        private void storePhysical(JvmType type, int slot) {
            code.storeLocal(typeKind(type.descriptor()), slot);
        }

        private void loadPhysical(JvmType type, int slot) {
            code.loadLocal(typeKind(type.descriptor()), slot);
        }

        private JvmType jvmType(String descriptor) {
            if (descriptor.charAt(0) == 'L') {
                return JvmType.reference(descriptor.substring(1, descriptor.length() - 1)
                        .replace('/', '.'));
            }
            if (descriptor.charAt(0) == '[') {
                return JvmType.array(descriptor.substring(1));
            }
            return JvmType.primitive(descriptor);
        }

        private void loadPhysical(JvmType type, int slot, boolean ignored) {
            loadPhysical(type, slot);
        }

        private void discard(JvmTypePlan plan) {
            for (int index = plan.physicalComponents().size() - 1; index >= 0; index--) {
                discardPhysical(plan.physicalComponents().get(index));
            }
        }

        private void discardPhysical(JvmType type) {
            if (isCategory2(type.descriptor())) code.pop2(); else code.pop();
        }

        private void emitZero(JvmType type) {
            String descriptor = type.descriptor();
            if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                code.aconst_null();
                return;
            }
            switch (descriptor) {
                case "J" -> code.lconst_0();
                case "F" -> code.fconst_0();
                case "D" -> code.dconst_0();
                default -> code.iconst_0();
            }
        }

        private void emitInt(int value) {
            switch (value) {
                case -1 -> code.iconst_m1();
                case 0 -> code.iconst_0();
                case 1 -> code.iconst_1();
                case 2 -> code.iconst_2();
                case 3 -> code.iconst_3();
                case 4 -> code.iconst_4();
                case 5 -> code.iconst_5();
                default -> code.loadConstant(value);
            }
        }

        private void emitLongConstant(long value) {
            code.loadConstant(value);
        }

        private void emitIntOrLong(long value, PrimitiveType primitive) {
            if (wide(primitive)) code.loadConstant(value); else emitInt((int) value);
        }

        private void emitIntegerMin(PrimitiveType primitive) {
            if (wide(primitive)) code.loadConstant(Long.MIN_VALUE);
            else emitInt(primitive.numericDomain().orElseThrow().minimumInteger().intValueExact());
        }

        private void emitFloatingConstant(PrimitiveType primitive, double value) {
            if (primitive == PrimitiveType.F32) code.loadConstant((float) value);
            else code.loadConstant(value);
        }

        private void boxPrimitive(JvmType type) {
            ClassDesc wrapper = wrapper(type.descriptor());
            String primitive = type.descriptor();
            // JVM B/S parameters still occupy int stack slots. Normalize raw
            // unsigned payloads before Java wrapper factories inspect them.
            if (primitive.equals("B")) code.i2b();
            if (primitive.equals("S")) code.i2s();
            code.invokestatic(wrapper, "valueOf", method("(" + primitive + ")" + wrapper.descriptorString()));
        }

        private void unboxPrimitive(JvmType type) {
            String method = switch (type.descriptor()) {
                case "B" -> "byteValue";
                case "S" -> "shortValue";
                case "I" -> "intValue";
                case "J" -> "longValue";
                case "F" -> "floatValue";
                case "D" -> "doubleValue";
                case "Z" -> "booleanValue";
                case "C" -> "charValue";
                default -> throw new IllegalArgumentException("not a wrapper primitive: " + type);
            };
            code.invokevirtual(wrapper(type.descriptor()), method, method("()" + type.descriptor()));
        }

        private ClassDesc wrapper(String descriptor) {
            return switch (descriptor) {
                case "B" -> CD_BYTE;
                case "S" -> CD_SHORT;
                case "I" -> CD_INTEGER;
                case "J" -> CD_LONG;
                case "F" -> CD_FLOAT;
                case "D" -> CD_DOUBLE;
                case "Z" -> CD_BOOLEAN;
                case "C" -> CD_CHARACTER;
                default -> throw new IllegalArgumentException("not a nullable primitive descriptor: " + descriptor);
            };
        }

        private void emitUnit() {
            code.getstatic(CD_UNIT, "INSTANCE", CD_UNIT);
        }

        private void emitObjectSuperConstructor() {
            aloadReceiver();
            code.invokespecial(CD_OBJECT, "<init>", method("()V"));
        }

        private void aloadReceiver() {
            code.aload(code.receiverSlot());
        }

        private void loadParameter(int index) {
            code.loadLocal(typeKind(method(member.descriptor()).parameterType(index).descriptorString()),
                    code.parameterSlot(index));
        }

        private void line(SourceSpan span) {
            owner.ir.sourceSnapshot(ModuleId.fromSourceId(span.sourceId())).ifPresent(snapshot ->
                    code.lineNumber(snapshot.positionAt(span.startOffset()).line()));
        }

        private Label failureLabel(SourceSpan span, String codeValue, String summary) {
            IrFailureSite site = activeFailureSite(span, codeValue);
            Label failure = code.newLabel();
            failureHandlers.add(new FailureHandler(failure, site.span(), site.failureCode(), summary));
            return failure;
        }

        private IrFailureSite activeFailureSite(SourceSpan span, String codeValue) {
            IrFailureSite site = activeFailureSites.peek();
            if (site == null) {
                throw invalidPlan(span, "generated runtime failure has no IR failure-site record");
            }
            if (!site.span().equals(span) || !site.failureCode().equals(codeValue)) {
                throw invalidPlan(span,
                        "generated runtime failure disagrees with active IR failure site");
            }
            return site;
        }

        private void throwFailure(SourceSpan span, String codeValue, String summary) {
            IrFailureSite site = activeFailureSite(span, codeValue);
            throwFailureRecorded(site.span(), site.failureCode(), summary);
        }

        private void throwFailureRecorded(SourceSpan span, String codeValue, String summary) {
            line(span);
            code.ldc(codeValue);
            code.invokestatic(CD_FAILURE_CATEGORY, "fromCode",
                    method("(Ljava/lang/String;)L" + RUNTIME + "LyraFailureCategory;"));
            code.ldc(summary);
            emitSourceFrame(span);
            code.invokestatic(CD_RUNTIME_EXCEPTION, "of", method(
                    "(L" + RUNTIME + "LyraFailureCategory;Ljava/lang/String;Ljava/util/List;)L"
                            + RUNTIME + "LyraRuntimeException;"));
            code.athrow();
        }

        private void emitSourceFrame(SourceSpan span) {
            // Build one non-synthetic frame without retaining compiler-side
            // source objects in generated classes.
            code.new_(cd(RUNTIME + "SourceFrame"));
            code.dup();
            code.new_(cd(RUNTIME + "ModuleId"));
            code.dup();
            code.ldc(span.sourceId().value());
            code.invokespecial(cd(RUNTIME + "ModuleId"), "<init>", method("(Ljava/lang/String;)V"));
            code.ldc(lambda == null ? "<module>" :
                    declarations.values().stream()
                            .filter(value -> lambda.ownerDeclaration().filter(value.id()::equals).isPresent())
                            .map(IrDeclaration::name).findFirst().orElse("<lambda>"));
            code.new_(cd(RUNTIME + "SourceSpan"));
            code.dup();
            code.new_(cd(RUNTIME + "SourceId"));
            code.dup();
            code.ldc(span.sourceId().value());
            code.invokespecial(cd(RUNTIME + "SourceId"), "<init>", method("(Ljava/lang/String;)V"));
            emitInt(span.startOffset());
            emitInt(span.endOffset());
            code.invokespecial(cd(RUNTIME + "SourceSpan"), "<init>",
                    method("(L" + RUNTIME + "SourceId;II)V"));
            code.invokespecial(cd(RUNTIME + "SourceFrame"), "<init>",
                    method("(L" + RUNTIME + "ModuleId;Ljava/lang/String;L" + RUNTIME
                            + "SourceSpan;)V"));
            code.invokestatic(CD_LIST, "of", method("(Ljava/lang/Object;)Ljava/util/List;"), true);
        }

        private Label newLabel() {
            return code.newLabel();
        }

        private void branchIfNegative(PrimitiveType primitive, Label failure) {
            if (wide(primitive)) {
                code.lconst_0(); code.lcmp(); code.iflt(failure);
            } else {
                code.iflt(failure);
            }
        }

        private void installLambdaParameter(DeclarationId id, JvmTypePlan physical,
                                            List<Integer> slots) {
            locals.put(id, BindingStorage.local(declarations.get(id).contract()
                    .orElseThrow().valueType(), physical, slots));
        }

        private record StateLocation(ModuleId moduleId, DeclarationId declarationId) {
            private StateLocation {
                Objects.requireNonNull(moduleId, "moduleId");
                Objects.requireNonNull(declarationId, "declarationId");
            }
        }

        private record ArrayStoreTarget(
                JvmTypePlan receiverPlan,
                int receiverSlot,
                JvmTypePlan indexPlan,
                List<Integer> indexSlots,
                JvmTypePlan elementPlan) {
            private ArrayStoreTarget {
                Objects.requireNonNull(receiverPlan, "receiverPlan");
                Objects.requireNonNull(indexPlan, "indexPlan");
                indexSlots = List.copyOf(indexSlots);
                Objects.requireNonNull(elementPlan, "elementPlan");
            }
        }

        private record BindingStorage(LyraType logical, JvmTypePlan physical, List<Integer> slots) {
            private BindingStorage {
                Objects.requireNonNull(logical, "logical");
                Objects.requireNonNull(physical, "physical");
                slots = List.copyOf(slots);
            }

            static BindingStorage local(LyraType logical, JvmTypePlan physical, List<Integer> slots) {
                return new BindingStorage(logical, physical, slots);
            }

            BindingStorage storage() {
                return this;
            }
        }

        private record PredicateStorage(LyraType type, JvmTypePlan physical, List<Integer> slots) {
            BindingStorage storage() {
                return BindingStorage.local(type, physical, slots);
            }
        }

        private record FailureHandler(Label label, SourceSpan span, String code, String summary) {
        }

        private record CallFailureHandler(Label start, Label end, Label handler,
                                          Label stackHandler, SourceSpan span) {
        }
    }

    private static JvmEmissionException unsupported(SourceSpan span, String message) {
        return new JvmEmissionException(span, message);
    }

    private static JvmEmissionException invalidPlan(SourceSpan span, String message) {
        return new JvmEmissionException(span, message, true);
    }
}
