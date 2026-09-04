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
import io.mindspice.lyra.compiler.ir.IrFailureSite;
import io.mindspice.lyra.compiler.ir.IrModule;
import io.mindspice.lyra.compiler.ir.IrNode;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.semantic.AccessKind;
import io.mindspice.lyra.compiler.semantic.DeclarationKind;
import io.mindspice.lyra.compiler.semantic.ReferenceKind;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.ConversionKind;
import io.mindspice.lyra.compiler.types.ConversionStep;
import io.mindspice.lyra.compiler.types.ExactNumericLiteral;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.QualifiedType;

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
 * Direct Java 25 Class-File API emitter for the scalar/control Phase-15
 * subset.  It consumes a sealed typed IR and the already audited Phase-14
 * shape plan; it does not re-run semantic analysis or synthesize an ABI.
 *
 * <p>Arrays, tuples, imports, and multi-module execution remain explicit
 * Phase-16 inputs.  They are rejected before any class bytes are published.
 * Scalar values, typed function interfaces/closures, local/state storage,
 * checked arithmetic, control flow, and direct self-tail calls are emitted
 * here.</p>
 */
final class JvmBytecodeEmitter {
    private static final String RUNTIME = "io.mindspice.lyra.runtime.";
    private static final ClassDesc CD_OBJECT = ConstantDescs.CD_Object;
    private static final ClassDesc CD_OBJECTS = cd("java.util.Objects");
    private static final ClassDesc CD_STRING = ConstantDescs.CD_String;
    private static final ClassDesc CD_LIST = ConstantDescs.CD_List;
    private static final ClassDesc CD_AUTHORITY = cd(RUNTIME + "LyraClosureAuthority");
    private static final ClassDesc CD_CLOSURE = cd(RUNTIME + "LyraClosure");
    private static final ClassDesc CD_SIGNATURE = cd(RUNTIME + "LyraSignature");
    private static final ClassDesc CD_LIFECYCLE = cd(RUNTIME + "ModuleLifecycle");
    private static final ClassDesc CD_UNIT = cd(RUNTIME + "LyraUnit");
    private static final ClassDesc CD_FAILURE_CATEGORY = cd(RUNTIME + "LyraFailureCategory");
    private static final ClassDesc CD_RUNTIME_EXCEPTION = cd(RUNTIME + "LyraRuntimeException");
    private static final ClassDesc CD_ARTIFACT_METADATA = cd(RUNTIME + "ArtifactMetadata");
    private static final ClassDesc CD_METADATA_READER = cd(RUNTIME + "ArtifactMetadataReader");
    private static final ClassDesc CD_RUNTIME_CLOSURE_SUPPORT = cd(RUNTIME + "LyraClosureSupport");
    private static final ClassDesc CD_RUNTIME_LYRA_CLOSURE = cd(RUNTIME + "LyraClosure");
    private static final ClassDesc CD_INTEGER = cd("java.lang.Integer");
    private static final ClassDesc CD_LONG = cd("java.lang.Long");
    private static final ClassDesc CD_FLOAT = cd("java.lang.Float");
    private static final ClassDesc CD_DOUBLE = cd("java.lang.Double");
    private static final ClassDesc CD_BOOLEAN = cd("java.lang.Boolean");
    private static final ClassDesc CD_BYTE = cd("java.lang.Byte");
    private static final ClassDesc CD_SHORT = cd("java.lang.Short");
    private static final ClassDesc CD_CHARACTER = cd("java.lang.Character");
    private static final ClassDesc CD_LOCALE = cd("java.util.Locale");

    private JvmBytecodeEmitter() {
    }

    /** Emits all Phase-15-supported classes in the supplied deterministic plan order. */
    static JvmBytecodeArtifact emit(TypedIr ir, GeneratedTypePlan plan) {
        TypedIr validated = Objects.requireNonNull(ir, "ir").requireValidated();
        Objects.requireNonNull(plan, "plan");
        try {
            JvmAbiParity.require(validated, plan);
        } catch (IllegalArgumentException failure) {
            throw new JvmEmissionException(validated.rootModule().span(),
                    "invalid JVM type plan: " + failure.getMessage(), true, failure);
        }
        Emitter emitter = new Emitter(validated, plan);
        emitter.validateSupportedInput();
        return emitter.emit();
    }

    /** Structured phase boundary for expected Phase-15 subset failures. */
    static PhaseResult<JvmBytecodeArtifact> emitPhase(TypedIr ir, GeneratedTypePlan plan) {
        Objects.requireNonNull(ir, "ir").requireValidated();
        try {
            return PhaseResult.success(emit(ir, plan));
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
        private final Map<DeclarationId, IrDeclaration> declarations;
        private final Map<LambdaId, io.mindspice.lyra.compiler.ir.IrLambda> lambdas;
        private final Map<CaptureId, IrCapture> captures;
        private final Map<ModuleId, IrModule> modules;
        private final Map<DeclarationId, IrCell> cells;
        private final Map<FlowSiteId, IrFailureSite> failureSites;
        private final Map<String, byte[]> bytes = new LinkedHashMap<>();
        private final Map<String, String> descriptors = new LinkedHashMap<>();

        private Emitter(TypedIr ir, GeneratedTypePlan plan) {
            this.ir = ir;
            this.plan = plan;
            this.mapper = plan.mapper();
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
            this.failureSites = ir.failureSites().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            IrFailureSite::siteId, value -> value));
        }

        private void validateSupportedInput() {
            if (ir.modules().isEmpty()) {
                throw unsupported(rootSpan(), "bytecode emission needs at least one module");
            }
            if (ir.modules().size() != 1) {
                throw unsupported(rootSpan(),
                        "multi-module emission belongs to the later module backend");
            }
            if (!ir.imports().isEmpty()) {
                throw unsupported(ir.imports().getFirst().importSpan(),
                        "module imports are not part of the scalar/control emission slice");
            }
            if (!ir.captures().isEmpty()) {
                throw unsupported(ir.captures().getFirst().span(),
                        "capturing closures belong to the later function backend");
            }
            if (!ir.cells().isEmpty()) {
                IrCell cell = ir.cells().getFirst();
                IrDeclaration declaration = declarations.get(cell.declarationId());
                throw unsupported(declaration == null ? rootSpan() : declaration.span(),
                        "shared mutable cells belong to the later function backend");
            }
            for (var scc : ir.functionLinkage().components()) {
                if (scc.recursive() && scc.declarations().size() > 1) {
                    IrDeclaration first = declarations.get(scc.declarations().getFirst());
                    throw unsupported(first == null ? rootSpan() : first.span(),
                            "mutual recursion belongs to the later function backend");
                }
            }
            for (var lambda : ir.lambdas()) {
                DeclarationId owner = lambda.ownerDeclaration().orElseThrow(() ->
                        unsupported(lambda.span(),
                                "anonymous closure values belong to the later function backend"));
                IrDeclaration declaration = declarations.get(owner);
                IrModule lambdaModule = modules.get(lambda.moduleId());
                if (declaration == null || lambdaModule == null
                        || !declaration.scopeId().equals(lambdaModule.state().rootScope())) {
                    throw unsupported(lambda.span(),
                            "nested closure values belong to the later function backend");
                }
                validateSelfRecursion(lambda.body(), owner, true);
            }
            for (IrNode node : io.mindspice.lyra.compiler.ir.IrTraversal.preOrder(ir)) {
                requireSupportedType(node.type(), node.span());
                if (node instanceof IrNode.ArrayLiteral || node instanceof IrNode.TupleLiteral
                        || node instanceof IrNode.IndexAccess) {
                    throw unsupported(node.span(),
                            "arrays, tuples, and indexing belong to the aggregate backend");
                }
                if (node instanceof IrNode.Access access) {
                    if (access.accessKind() == AccessKind.MEMBER_VALUE
                            && access.memberName().filter("length"::equals).isPresent()
                            && access.receiver().orElseThrow().type().withoutQualifiers()
                            != PrimitiveType.STRING) {
                        throw unsupported(node.span(), "array length belongs to the aggregate backend");
                    }
                    if (access.accessKind() == AccessKind.MEMBER_VALUE
                            && access.memberName().filter("length"::equals).isEmpty()) {
                        throw unsupported(node.span(), "unsupported scalar member access");
                    }
                }
                if (node instanceof IrNode.Rebinding rebinding
                        && rebinding.route().steps().stream().anyMatch(step ->
                        step.getClass().getSimpleName().contains("Array"))) {
                    throw unsupported(node.span(), "aggregate mutation belongs to the later backend");
                }
                if (node instanceof IrNode.DirectCall call
                        && call.targetModule().filter(module -> !module.equals(rootModuleId())).isPresent()) {
                    throw unsupported(node.span(), "cross-module calls belong to the later module backend");
                }
                if (node instanceof IrNode.DirectCall call && call.receiver().isPresent()) {
                    throw unsupported(node.span(),
                            "receiver-bound direct calls are unavailable in the scalar language slice");
                }
            }
            for (IrDeclaration declaration : ir.declarations()) {
                declaration.contract().ifPresent(contract ->
                        requireSupportedType(contract.valueType(), declaration.span()));
                if (declaration.imported()) {
                    throw unsupported(declaration.span(), "imports are not part of the scalar/control slice");
                }
            }
            for (GeneratedClassPlan classPlan : plan.classes()) {
                if (classPlan.kind() == GeneratedClassKind.TUPLE_VALUE) {
                    throw unsupported(rootSpan(), "tuple class emission belongs to the aggregate backend");
                }
            }
        }

        private void validateSelfRecursion(IrNode node, DeclarationId owner, boolean tail) {
            if (node instanceof IrNode.DirectCall call
                    && call.targetDeclaration().filter(owner::equals).isPresent()) {
                if (!tail) {
                    throw unsupported(call.span(),
                            "non-tail self recursion belongs to the later function backend");
                }
                for (IrNode argument : call.arguments()) {
                    validateSelfRecursion(argument, owner, false);
                }
                return;
            }
            if (node instanceof IrNode.CallableCall call
                    && call.target() instanceof IrNode.Reference reference
                    && reference.targetDeclaration().filter(owner::equals).isPresent()) {
                throw unsupported(call.span(),
                        "callable self recursion belongs to the later function backend");
            }
            if (node instanceof IrNode.Sequence sequence) {
                for (int index = 0; index < sequence.forms().size(); index++) {
                    validateSelfRecursion(sequence.forms().get(index), owner,
                            tail && index == sequence.forms().size() - 1);
                }
                return;
            }
            if (node instanceof IrNode.Block block) {
                for (int index = 0; index < block.forms().size(); index++) {
                    validateSelfRecursion(block.forms().get(index), owner,
                            tail && index == block.forms().size() - 1);
                }
                return;
            }
            if (node instanceof IrNode.RuntimeCheck check) {
                validateSelfRecursion(check.operand(), owner, tail);
                return;
            }
            if (node instanceof IrNode.Branch branch) {
                validateSelfRecursion(branch.predicate(), owner, false);
                validateSelfRecursion(branch.thenBranch(), owner, tail);
                branch.elseBranch().ifPresent(value -> validateSelfRecursion(value, owner, tail));
                return;
            }
            if (node instanceof IrNode.Coalesce coalesce) {
                validateSelfRecursion(coalesce.value(), owner, false);
                validateSelfRecursion(coalesce.fallback(), owner, tail);
                return;
            }
            for (IrNode child : node.childrenInEvaluationOrder()) {
                validateSelfRecursion(child, owner, false);
            }
        }

        private void requireSupportedType(LyraType type, SourceSpan span) {
            LyraType base = type.withoutQualifiers();
            if (base instanceof PrimitiveType) {
                return;
            }
            if (base instanceof FunctionType function) {
                for (LyraType parameter : function.parameterTypes()) {
                    requireSupportedType(parameter, span);
                }
                requireSupportedType(function.returnType(), span);
                return;
            }
            throw unsupported(span, "aggregate types are not supported by Phase-15 emission: " + type);
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
                        classBuilder.withMethodBody(member.name(), method(member.descriptor()),
                                methodFlags(member), code -> emitMethod(classPlan, member, code));
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

        private String sourceFile(GeneratedClassPlan classPlan) {
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
        private final Map<DeclarationId, IrDeclaration> declarations;
        private final Map<CaptureId, IrCapture> captures;
        private final IrModule module;
        private final io.mindspice.lyra.compiler.ir.IrLambda lambda;
        private final boolean stateMethod;
        private final boolean closureMethod;
        private Label loopLabel;
        private final List<FailureHandler> failureHandlers = new ArrayList<>();
        private final List<CallFailureHandler> callFailureHandlers = new ArrayList<>();
        private final Deque<IrFailureSite> activeFailureSites = new ArrayDeque<>();

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
            this.stateMethod = classPlan.kind() == GeneratedClassKind.MODULE_STATE;
            this.closureMethod = classPlan.kind() == GeneratedClassKind.CLOSURE;
        }

        private void emit() {
            line(memberSpan());
            switch (classPlan.kind()) {
                case CELL -> emitCellMethod();
                case CLOSURE -> emitClosureMethod();
                case MODULE_STATE -> emitStateMethod();
                case MODULE_FACADE -> emitFacadeMethod();
                case FUNCTION_INTERFACE, TUPLE_VALUE ->
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
                throwFailureRecorded(handler.span(), handler.code(), handler.summary());
            }
        }

        private SourceSpan memberSpan() {
            if (lambda != null) {
                return lambda.span();
            }
            return module.span();
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
            code.ldc(lambda.signature().canonicalSpelling());
            code.invokestatic(CD_SIGNATURE, "parse",
                    method("(Ljava/lang/String;)L" + RUNTIME + "LyraSignature;"));
            code.invokespecial(CD_CLOSURE, "<init>",
                    method("(L" + RUNTIME + "LyraClosureAuthority;L" + RUNTIME
                            + "LyraSignature;)V"));
        }

        private void emitClosureInvoke() {
            if (lambda == null || lambda.signature() == null) {
                throw invalidPlan(memberSpan(), "closure invocation has no lambda metadata");
            }
            // The lifecycle/owner check is performed once at the generated
            // invocation boundary.  A self-tail loop then reuses the checked
            // frame without growing the JVM stack.
            aloadReceiver();
            code.invokevirtual(CD_CLOSURE, "checkInvocation", method("()V"));
            loopLabel = code.newLabel();
            code.labelBinding(loopLabel);
            line(lambda.bodySpan());
            installLambdaParameters();
            emitTail(lambda.body());
        }

        private void installLambdaParameters() {
            for (int index = 0; index < lambda.parameterIds().size(); index++) {
                DeclarationId id = lambda.parameterIds().get(index);
                LyraType logical = lambda.signature().parameterType(index);
                JvmTypePlan physical = owner.mapper.map(logical, JvmMappingContext.JAVA_PARAMETER);
                int slot = code.parameterSlot(index);
                locals.put(id, BindingStorage.local(logical, physical, List.of(slot)));
            }
        }

        private void emitStateMethod() {
            switch (member.kind()) {
                case STATE_CONSTRUCTOR -> emitStateConstructor();
                case STATE_COMPONENT_GET -> emitStateGetter();
                case STATE_COMPONENT_SET -> emitStateSetter();
                case STATE_AUTHORITY_GET -> emitStateAuthorityGetter();
                case STATE_CHECK_OPEN -> emitStateCheckOpen();
                case STATE_CLOSE -> emitStateClose();
                case STATE_IMPORT_LINK ->
                        throw unsupported(memberSpan(), "module import linkage is outside the scalar slice");
                default -> throw invalidPlan(memberSpan(), "unexpected module-state method: " + member.kind());
            }
        }

        private void emitStateConstructor() {
            emitObjectSuperConstructor();
            aloadReceiver();
            code.new_(CD_LIFECYCLE);
            code.dup();
            code.invokespecial(CD_LIFECYCLE, "<init>", method("()V"));
            GeneratedMemberPlan lifecycle = stateLifecycleField();
            code.putfield(cd(classPlan.binaryName()), lifecycle.name(), type(lifecycle.descriptor()));
            // The state fields provide the predeclared function slots needed
            // for recursion.  Initializers themselves still run in source
            // order: captures must observe the value that existed when their
            // lambda was evaluated, and shared cells must already exist when
            // a function declaration captures one.
            for (IrNode form : module.body().forms()) {
                JvmTypePlan value = emitNode(form);
                discard(value);
            }
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "open", method("()V"));
            code.return_();
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

        private void emitStateCheckOpen() {
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "checkOpen", method("()V"));
            code.return_();
        }

        private void emitStateClose() {
            loadStateLifecycle();
            code.invokevirtual(CD_LIFECYCLE, "close", method("()V"));
            code.return_();
        }

        private void emitStateGetter() {
            DeclarationId id = declarationIdFromMember(member.name());
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
                case FACADE_CONSTRUCTOR -> emitFacadeConstructor();
                case FACTORY, FACTORY_WITH_OPTIONS -> emitFacadeFactory();
                case METADATA -> emitFacadeMetadata();
                case CLOSE -> emitFacadeClose();
                case FUNCTION_INVOCATION, VALUE_GETTER, FUNCTION_VALUE_GETTER, SETTER ->
                        emitFacadeExport();
                default -> throw invalidPlan(memberSpan(), "unexpected facade method: " + member.kind());
            }
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
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(classPlan.moduleId().orElseThrow())).orElseThrow();
            int stateSlot = code.allocateLocal(TypeKind.REFERENCE);
            code.new_(cd(state.binaryName()));
            code.dup();
            code.invokespecial(cd(state.binaryName()), "<init>", method("()V"));
            code.astore(stateSlot);
            code.new_(cd(classPlan.binaryName()));
            code.dup();
            code.aload(stateSlot);
            code.invokespecial(cd(classPlan.binaryName()), "<init>",
                    method("(" + descriptorOf(state) + ")V"));
            code.areturn();
        }

        private void emitFacadeMetadata() {
            code.ldc(metadataJson());
            code.invokestatic(CD_METADATA_READER, "read",
                    method("(Ljava/lang/String;)L" + RUNTIME + "ArtifactMetadata;"));
            code.areturn();
        }

        private String metadataJson() {
            // Metadata construction is performed once by the compiler, then
            // embedded as canonical UTF-8 text.  The generated method has no
            // Object/varargs ABI and runtime validation remains authoritative.
            List<io.mindspice.lyra.runtime.ModuleMetadata> runtimeModules = new ArrayList<>();
            for (IrModule value : owner.ir.modules()) {
                io.mindspice.lyra.compiler.source.SourceSnapshot snapshot =
                        owner.ir.sourceSnapshot(value.moduleId()).orElseThrow();
                io.mindspice.lyra.runtime.ModuleId moduleId =
                        io.mindspice.lyra.runtime.ModuleId.of(value.moduleId().value());
                runtimeModules.add(new io.mindspice.lyra.runtime.ModuleMetadata(
                        moduleId,
                        io.mindspice.lyra.runtime.ModuleRevision.of(
                                io.mindspice.lyra.compiler.source.ModuleRevision.compute(snapshot)),
                        snapshot.sourceId().value()));
            }
            List<io.mindspice.lyra.runtime.ExportMetadata> runtimeExports = new ArrayList<>();
            Map<String, String> javaNames = new TreeMap<>();
            for (GeneratedExportPlan export : owner.plan.exports()) {
                io.mindspice.lyra.runtime.ModuleId moduleId =
                        io.mindspice.lyra.runtime.ModuleId.of(export.moduleId().value());
                String signatureSpelling = export.functionSignature()
                        .map(JvmSignaturePlan::canonicalLyraSignature)
                        .orElseGet(() -> "Fn<;" + export.valueType().canonicalLyraType() + ">");
                io.mindspice.lyra.runtime.LyraSignature signature =
                        io.mindspice.lyra.runtime.LyraSignature.parse(signatureSpelling);
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
                io.mindspice.lyra.runtime.ExportMetadata runtimeExport = new io.mindspice.lyra.runtime.ExportMetadata(
                        moduleId, export.sourceName(), signature, jvmDescriptor, mutability,
                        export.javaName(), getter, functionGetter,
                        export.setterName().map(value -> value));
                runtimeExports.add(runtimeExport);
                javaNames.put(runtimeExport.id().id(), runtimeExport.javaName());
            }
            runtimeModules.sort(io.mindspice.lyra.runtime.ModuleMetadata::compareTo);
            runtimeExports.sort(io.mindspice.lyra.runtime.ExportMetadata::compareTo);
            io.mindspice.lyra.runtime.ArtifactRevision revision =
                    io.mindspice.lyra.runtime.ArtifactRevision.compute(
                            "lyra-phase15", runtimeModules, javaNames,
                            io.mindspice.lyra.runtime.RuntimeProfile.CURRENT,
                            io.mindspice.lyra.runtime.PackagingMode.CLASSES, false);
            io.mindspice.lyra.runtime.ModuleMetadata root = runtimeModules.stream()
                    .filter(value -> value.id().equals(
                            io.mindspice.lyra.runtime.ModuleId.of(owner.rootModuleId().value())))
                    .findFirst().orElseThrow();
            return io.mindspice.lyra.runtime.ArtifactMetadata.builder()
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
                    .exports(runtimeExports)
                    .javaNameMap(javaNames)
                    .debugMapHash(JvmStableHash.sha256("LYRA-JVM-DEBUG-MAP", owner.plan.canonical()))
                    .packagingMode(io.mindspice.lyra.runtime.PackagingMode.CLASSES)
                    .build().canonicalJson();
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
            loadFacadeState();
            emitStateFunction(export.declarationId());
            FunctionType function = functionBase(declarations.get(export.declarationId()).contract()
                    .orElseThrow().valueType());
            JvmSignaturePlan signature = export.functionSignature().orElseThrow();
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

        private void emitFacadeFunctionValueGetter(GeneratedExportPlan export) {
            loadFacadeState();
            emitStateFunction(export.declarationId());
            code.areturn();
        }

        private void emitFacadeValueGetter(GeneratedExportPlan export) {
            loadFacadeStateValue(export.declarationId());
            JvmTypePlan internal = owner.mapper.map(
                    declarations.get(export.declarationId()).contract().orElseThrow().valueType(),
                    JvmMappingContext.INTERNAL_VALUE);
            adapt(internal, export.valueType());
            returnPhysicalDescriptor(export.valueType().descriptor());
        }

        private void emitFacadeSetter(GeneratedExportPlan export) {
            loadFacadeState();
            if (export.isFunction()) {
                // Preserve the receiver below the authenticated replacement.
                int argument = code.allocateLocal(TypeKind.REFERENCE);
                loadParameter(0);
                code.astore(argument);
                code.aload(argument);
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
                code.checkcast(cd(owner.plan.functionInterfaces().get(
                        functionBase(declarations.get(export.declarationId()).contract().orElseThrow().valueType())
                                .canonicalSpelling())));
                invokeStateSetter(export.declarationId(), member.descriptor());
            } else {
                JvmTypePlan internal = owner.mapper.map(
                        declarations.get(export.declarationId()).contract().orElseThrow().valueType(),
                        JvmMappingContext.INTERNAL_BINDING);
                JvmTypePlan external = export.valueType();
                // The state receiver is already underneath the argument.
                loadParameter(0);
                adapt(external, internal);
                invokeStateSetter(export.declarationId(), member.descriptor());
            }
            code.return_();
        }

        private void emitParameterFromFacade(int index, LyraType logical, JvmTypePlan target) {
            loadParameter(index);
            JvmTypePlan source = owner.mapper.map(logical, JvmMappingContext.JAVA_PARAMETER);
            adapt(source, target);
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

        private void emitStateFunction(DeclarationId id) {
            emitStateFunctionOrValue(id);
        }

        private void emitStateFunctionOrValue(DeclarationId id) {
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(module.moduleId())).orElseThrow();
            List<GeneratedMemberPlan> fields = stateFields(id);
            if (fields.isEmpty()) {
                throw invalidPlan(memberSpan(), "state has no declaration field: " + id);
            }
            if (fields.size() == 1) {
                GeneratedMemberPlan field = fields.getFirst();
                if (field.kind() == GeneratedMemberKind.STATE_CELL_FIELD) {
                    throw unsupported(memberSpan(), "shared cell is not a facade value");
                }
                code.invokevirtual(cd(state.binaryName()),
                        "$lyra$get$binding$" + id.value(),
                        method("()" + field.descriptor()));
                return;
            }
            if (fields.size() == 2) {
                code.invokevirtual(cd(state.binaryName()),
                        "$lyra$isPresent$binding$" + id.value(), method("()Z"));
                // The first accessor consumes the state receiver.  Reload it
                // before asking for the payload; the JVM operand stack must
                // never use the presence bit as a receiver.
                loadFacadeState();
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
                JvmTypePlan result = emitSequence(block.forms(), block.type());
                locals.clear();
                locals.putAll(saved);
                return result;
            }
            if (node instanceof IrNode.ArrayLiteral || node instanceof IrNode.TupleLiteral
                    || node instanceof IrNode.IndexAccess) {
                throw unsupported(node.span(), "aggregate node reached scalar emitter");
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
            } else {
                if (metadata.bindingMutability().isMutable() && owner.cells.containsKey(id)) {
                    throw unsupported(declaration.span(), "captured local mutable cells are outside the scalar slice");
                }
                List<Integer> slots = allocateLocals(storage);
                storeLocal(storage, slots);
                locals.put(id, BindingStorage.local(contract.valueType(), storage, slots));
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
            if (rebinding.route().steps().size() != 0) {
                throw unsupported(rebinding.span(), "aggregate rebinding route reached scalar emitter");
            }
            if (!(rootReferenceNode(rebinding.target()) instanceof IrNode.Reference)
                    && !(rootReferenceNode(rebinding.target()) instanceof IrNode.CaptureReference)) {
                throw unsupported(rebinding.span(), "scalar rebinding target is not a binding reference");
            }
            // The scalar target has no observable load effect.  The IR still
            // records it as the first evaluation child; the value is then
            // evaluated and stored exactly once.
            JvmTypePlan storage = storagePlan(id);
            JvmTypePlan value = emitNode(rebinding.value());
            adapt(value, storage);
            storeDeclaration(id, storage);
            emitUnit();
            return owner.mapper.map(rebinding.type(), JvmMappingContext.INTERNAL_VALUE);
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
            for (int index = 0; index < forms.size() - 1; index++) {
                discard(emitNode(forms.get(index)));
            }
            return emitNode(forms.getLast());
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
                case SLASH -> { if (wide(primitive)) code.ldiv(); else code.idiv(); }
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
                    String divide = primitive == PrimitiveType.U32 ? "divideUnsigned" : "divideUnsigned";
                    code.invokestatic(integerClass, divide, method(descriptor));
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
                Label upperFailure = failureLabel(span, "LYR-CONVERT",
                        "numeric conversion is out of range");
                if (target == PrimitiveType.I64 || target == PrimitiveType.U64) {
                    code.ifge(upperFailure);
                } else {
                    code.ifgt(upperFailure);
                }
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
            if (upper && target == PrimitiveType.I64) {
                emitFloatingConstant(source, 0x1.0p63);
                return;
            }
            if (upper && target == PrimitiveType.U64) {
                emitFloatingConstant(source, 0x1.0p64);
                return;
            }
            BigDecimal value;
            if (upper) {
                value = new BigDecimal(target.numericDomain().orElseThrow().maximumInteger());
            } else {
                value = new BigDecimal(target.numericDomain().orElseThrow().minimumInteger());
            }
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
            installPredicateBinding(branch, predicate);
            JvmTypePlan thenValue = emitNode(branch.thenBranch());
            adapt(thenValue, target);
            code.goto_(end);
            locals.clear();
            locals.putAll(saved);
            code.labelBinding(elseLabel);
            if (branch.elseBranch().isPresent()) {
                JvmTypePlan elseValue = emitNode(branch.elseBranch().orElseThrow());
                adapt(elseValue, target);
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

        private JvmTypePlan emitDirectCall(IrNode.DirectCall call) {
            DeclarationId id = call.targetDeclaration().orElseThrow(() ->
                    invalidPlan(call.span(), "direct call has no target declaration"));
            IrDeclaration declaration = declarations.get(id);
            if (declaration == null || declaration.contract().isEmpty()) {
                throw invalidPlan(call.span(), "direct call target declaration is absent");
            }
            FunctionType function = functionBase(declaration.contract().orElseThrow().valueType());
            if (!declaration.moduleId().equals(module.moduleId())) {
                throw unsupported(call.span(), "cross-module direct calls are outside Phase 15");
            }
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
            FunctionType function = functionBase(call.target().type());
            JvmSignaturePlan signature = owner.mapper.mapSignature(function.signature(),
                    JvmAbiBoundary.JAVA_VISIBLE);
            emitAt(call.target(), owner.mapper.map(call.target().type(), JvmMappingContext.INTERNAL_VALUE));
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

        private void recordCallFailureFrame(SourceSpan span, Runnable invocation) {
            Label start = code.newLabel();
            Label end = code.newLabel();
            Label handler = code.newLabel();
            code.labelBinding(start);
            invocation.run();
            code.labelBinding(end);
            callFailureHandlers.add(new CallFailureHandler(start, end, handler, span));
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
                emitAt(access.receiver().orElseThrow(), owner.mapper.map(
                        access.receiver().orElseThrow().type(), JvmMappingContext.INTERNAL_VALUE));
                code.invokevirtual(CD_STRING, "length", method("()I"));
                return owner.mapper.map(access.type(), JvmMappingContext.INTERNAL_VALUE);
            }
            DeclarationId id = access.declarationId().orElseThrow(() ->
                    invalidPlan(access.span(), "namespace access has no declaration identity"));
            JvmTypePlan desired = owner.mapper.map(access.type(), JvmMappingContext.INTERNAL_VALUE);
            emitLoadDeclaration(id, desired);
            return desired;
        }

        private JvmTypePlan emitRuntimeCheck(IrNode.RuntimeCheck check) {
            if (check.checkKind() == IrCheckKind.BOUNDS) {
                throw unsupported(check.span(), "bounds checks belong to the aggregate backend");
            }
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
                for (IrNode form : sequence.forms().subList(0, sequence.forms().size() - 1)) {
                    discard(emitNode(form));
                }
                emitTail(sequence.forms().getLast());
                return;
            }
            if (node instanceof IrNode.Block block) {
                Map<DeclarationId, BindingStorage> saved = new HashMap<>(locals);
                if (block.forms().isEmpty()) {
                    emitUnit();
                    emitReturn(owner.mapper.map(PrimitiveType.UNIT, JvmMappingContext.INTERNAL_VALUE));
                } else {
                    for (IrNode form : block.forms().subList(0, block.forms().size() - 1)) {
                        discard(emitNode(form));
                    }
                    emitTail(block.forms().getLast());
                }
                locals.clear();
                locals.putAll(saved);
                return;
            }
            if (node instanceof IrNode.RuntimeCheck check) {
                if (check.checkKind() == IrCheckKind.BOUNDS) {
                    throw unsupported(check.span(), "bounds checks belong to the aggregate backend");
                }
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
            installPredicateBinding(branch, predicate);
            emitTail(branch.thenBranch());
            locals.clear(); locals.putAll(saved);
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
            if (base instanceof FunctionType) {
                loadPhysical(physical, slot);
                code.ifnonnull(thenLabel);
                code.goto_(elseLabel);
                return;
            }
            if (base instanceof PrimitiveType primitive && primitive.isNumeric()) {
                loadPhysical(physical, slot);
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
            if (base == PrimitiveType.BOOL || base == PrimitiveType.CHAR) {
                loadPhysical(physical, slot);
                code.ifne(thenLabel);
                code.goto_(elseLabel);
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
            if (operator.operands().getFirst().type().withoutQualifiers() == PrimitiveType.STRING) {
                return emitValueEquality(operator, true);
            }
            if (primitiveBase(operator.operands().getFirst().type()).isNumeric()
                    || primitiveBase(operator.operands().getFirst().type()) == PrimitiveType.BOOL
                    || primitiveBase(operator.operands().getFirst().type()) == PrimitiveType.CHAR) {
                if (token == TokenKind.EQUAL_EQUAL || token == TokenKind.NOT_EQUAL) {
                    return emitValueEquality(operator, false);
                }
                return emitNumericComparison(operator);
            }
            return emitValueEquality(operator, false);
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

        private JvmTypePlan emitValueEquality(IrNode.Operator operator, boolean strings) {
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
                if (left.physical().isSplitValue() || right.physical().isSplitValue()) {
                    emitSplitEquality(left, right, falseLabel, equal, operator.span());
                } else if (strings || left.physical().physicalComponents().getFirst().isReference()) {
                    loadLocal(left);
                    loadLocal(right);
                    code.invokestatic(CD_OBJECTS, "equals",
                            method("(Ljava/lang/Object;Ljava/lang/Object;)Z"));
                    code.branch(equal ? java.lang.classfile.Opcode.IFEQ
                            : java.lang.classfile.Opcode.IFNE, falseLabel);
                } else {
                    loadLocal(left);
                    normalizeEqualityValue(left);
                    loadLocal(right);
                    normalizeEqualityValue(right);
                    emitPrimitiveEqualityBranch(left.physical().physicalComponents().getFirst(),
                            equal, falseLabel);
                }
            }
            emitInt(1); code.goto_(end);
            code.labelBinding(falseLabel); emitInt(0); code.labelBinding(end);
            return owner.mapper.map(operator.type(), JvmMappingContext.INTERNAL_VALUE);
        }

        private void emitSplitEquality(BindingStorage left, BindingStorage right,
                                       Label falseLabel, boolean equal, SourceSpan span) {
            if (left.physical().isSplitValue() != right.physical().isSplitValue()) {
                throw unsupported(span, "incompatible nullable scalar equality representation");
            }
            if (!left.physical().isSplitValue()) {
                throw unsupported(span, "unsupported nullable reference equality representation");
            }
            Label bothPresent = code.newLabel();
            Label equalValue = code.newLabel();
            Label unequal = code.newLabel();
            loadPhysical(left.physical().physicalComponents().getFirst(), left.slots().getFirst());
            loadPhysical(right.physical().physicalComponents().getFirst(), right.slots().getFirst());
            code.if_icmpeq(bothPresent);
            code.goto_(equal ? falseLabel : equalValue);
            code.labelBinding(bothPresent);
            loadPhysical(left.physical().physicalComponents().getFirst(), left.slots().getFirst());
            code.ifeq(equal ? equalValue : falseLabel);
            loadPhysical(right.physical().physicalComponents().getFirst(), right.slots().getFirst());
            code.ifeq(equal ? falseLabel : equalValue);
            loadPhysical(left.physical().physicalComponents().get(1), left.slots().get(1));
            normalizeEqualityValue(left);
            loadPhysical(right.physical().physicalComponents().get(1), right.slots().get(1));
            normalizeEqualityValue(right);
            emitPrimitiveEqualityBranch(left.physical().physicalComponents().get(1), equal, falseLabel);
            code.labelBinding(equalValue);
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
                code.fcmpl();
                code.branch(switch (token) {
                    case LESS -> java.lang.classfile.Opcode.IFGE;
                    case LESS_EQUAL -> java.lang.classfile.Opcode.IFGT;
                    case GREATER -> java.lang.classfile.Opcode.IFLE;
                    case GREATER_EQUAL -> java.lang.classfile.Opcode.IFLT;
                    default -> throw new IllegalArgumentException("not relational: " + token);
                }, falseLabel);
            } else {
                code.dcmpl();
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
            loadPrimitive(PrimitiveType.U64, value);
            code.ldc(Long.MAX_VALUE);
            code.land();
            code.l2d();
            code.loadConstant(0x1.0p63);
            code.dadd();
            if (targetDescriptor.equals("F")) code.d2f();
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

        private void emitLoadDeclaration(DeclarationId id, JvmTypePlan desired) {
            BindingStorage local = locals.get(id);
            if (local != null) {
                loadLocal(local);
                adapt(local.physical(), desired);
                return;
            }
            if (!declarations.containsKey(id)) {
                throw invalidPlan(module.span(), "declaration identity is absent: " + id);
            }
            IrDeclaration declaration = declarations.get(id);
            if (!declaration.moduleId().equals(module.moduleId())) {
                throw unsupported(module.span(), "foreign module declaration reached scalar emitter");
            }
            emitLoadStateBinding(id);
            JvmTypePlan storage = storagePlan(id);
            adapt(storage, desired);
        }

        private void emitReferenceByDeclaration(DeclarationId id, LyraType logical,
                                                JvmTypePlan target) {
            emitLoadDeclaration(id, target);
            adaptPhysicalPlan(storagePlan(id), target);
        }

        private JvmTypePlan storagePlan(DeclarationId id) {
            IrDeclaration declaration = declarations.get(id);
            if (declaration == null || declaration.contract().isEmpty()) {
                throw invalidPlan(module.span(), "declaration has no storage contract: " + id);
            }
            return owner.mapper.mapBinding(declaration.contract().orElseThrow()).value();
        }

        private void emitLoadStateBinding(DeclarationId id) {
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(module.moduleId())).orElseThrow(() -> invalidPlan(module.span(),
                    "module state class is absent"));
            List<GeneratedMemberPlan> fields = stateFields(id);
            if (fields.isEmpty()) {
                throw invalidPlan(module.span(), "module state field is absent: " + id);
            }
            if (stateMethod) {
                for (GeneratedMemberPlan field : fields) {
                    aloadReceiver();
                    code.getfield(cd(state.binaryName()), field.name(), type(field.descriptor()));
                }
            } else if (closureMethod) {
                for (GeneratedMemberPlan field : fields) {
                    // Each component accessor consumes its receiver.  A
                    // split nullable binding therefore needs a fresh
                    // closure-state receiver for every component.
                    emitLoadClosureState();
                    String getter = field.name().endsWith("$present")
                            ? "$lyra$isPresent$binding$" + id.value()
                            : field.name().endsWith("$payload")
                            ? "$lyra$payload$binding$" + id.value()
                            : "$lyra$get$binding$" + id.value();
                    code.invokevirtual(cd(state.binaryName()), getter,
                            method("()" + field.descriptor()));
                }
            } else {
                throw unsupported(module.span(), "state binding access outside module/closure");
            }
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
            BindingStorage local = locals.get(id);
            if (local != null) {
                storeLocal(storage, local.slots());
                return;
            }
            if (stateMethod && isRootDeclaration(id)) {
                storeStateDeclaration(id, storage);
                return;
            }
            if (closureMethod && owner.cells.containsKey(id)) {
                emitStoreCell(id, storage);
                return;
            }
            throw invalidPlan(module.span(), "mutable declaration has no writable storage: " + id);
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
            GeneratedClassPlan state = owner.plan.classPlan(owner.plan.moduleStates()
                    .get(module.moduleId())).orElseThrow();
            String prefix = "$lyra$binding$" + id.value();
            return state.members().stream().filter(GeneratedMemberPlan::isField)
                    .filter(value -> value.name().equals(prefix)
                            || value.name().startsWith(prefix + "$"))
                    .toList();
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
            switch (type.descriptor()) {
                case "J" -> code.lconst_0();
                case "F" -> code.fconst_0();
                case "D" -> code.dconst_0();
                case "L", "[" -> code.aconst_null();
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

        private record CallFailureHandler(Label start, Label end, Label handler, SourceSpan span) {
        }
    }

    private static JvmEmissionException unsupported(SourceSpan span, String message) {
        return new JvmEmissionException(span, message);
    }

    private static JvmEmissionException invalidPlan(SourceSpan span, String message) {
        return new JvmEmissionException(span, message, true);
    }
}
