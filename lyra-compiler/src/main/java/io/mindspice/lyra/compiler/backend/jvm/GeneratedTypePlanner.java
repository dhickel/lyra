package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.ir.IrCapture;
import io.mindspice.lyra.compiler.ir.IrCell;
import io.mindspice.lyra.compiler.ir.IrClosureInitialization;
import io.mindspice.lyra.compiler.ir.IrDeclaration;
import io.mindspice.lyra.compiler.ir.IrExport;
import io.mindspice.lyra.compiler.ir.IrFunctionLink;
import io.mindspice.lyra.compiler.ir.IrImportBinding;
import io.mindspice.lyra.compiler.ir.IrLambda;
import io.mindspice.lyra.compiler.ir.IrModule;
import io.mindspice.lyra.compiler.ir.IrModuleState;
import io.mindspice.lyra.compiler.ir.IrNode;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.semantic.DeclarationKind;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds all deterministic generated class/member shapes from a validated
 * Phase-12 IR.  It never emits bytecode or executes an initializer.
 */
final class GeneratedTypePlanner {
    public static final String DEFAULT_BASE_PACKAGE = JvmTypeNameTable.DEFAULT_BASE_PACKAGE;
    private static final String AUTHORITY_DESCRIPTOR =
            "Lio/mindspice/lyra/runtime/LyraClosureAuthority;";
    private static final String LIFECYCLE_DESCRIPTOR =
            "Lio/mindspice/lyra/runtime/ModuleLifecycle;";
    private static final String OPTIONS_DESCRIPTOR =
            "Lio/mindspice/lyra/runtime/RuntimeOptions;";
    private static final String METADATA_DESCRIPTOR =
            "Lio/mindspice/lyra/runtime/ArtifactMetadata;";

    private GeneratedTypePlanner() {
    }

    public static GeneratedTypePlan plan(TypedIr ir) {
        return plan(ir, DEFAULT_BASE_PACKAGE);
    }

    public static GeneratedTypePlan plan(TypedIr ir, String basePackage) {
        return plan(ir, basePackage, ir != null && ir.sessionExecution().isPresent()
                ? EmissionMode.SESSION : EmissionMode.NORMAL);
    }

    public static GeneratedTypePlan plan(TypedIr ir, String basePackage, EmissionMode emissionMode) {
        Objects.requireNonNull(ir, "ir").requireValidated();
        Objects.requireNonNull(basePackage, "basePackage");
        Objects.requireNonNull(emissionMode, "emissionMode");
        if (emissionMode == EmissionMode.SESSION && ir.sessionExecution().isEmpty()) {
            throw new IllegalArgumentException("session emission requires session IR metadata");
        }
        if (emissionMode == EmissionMode.NORMAL && ir.sessionExecution().isPresent()) {
            throw new IllegalArgumentException("session IR cannot use normal emission mode");
        }
        if (emissionMode == EmissionMode.ATTACHABLE && ir.sessionExecution().isPresent()) {
            throw new IllegalArgumentException("session IR cannot use attachable emission mode");
        }
        validateIrCoverage(ir);

        Inventory inventory = inventory(ir);
        NameAssignment names = names(basePackage, inventory, ir);
        JvmTypeNameTable typeNames = new JvmTypeNameTable(
                basePackage, names.tupleNames(), names.functionNames());
        JvmAbiMapper mapper = new JvmAbiMapper(typeNames);
        var nominalLayouts = new TreeMap<String, NominalClassLayout>();
        for (var schema : ir.semanticGraph().resolvedGraph().nominalTypes().schemas()) {
            nominalLayouts.put(schema.type().canonicalSpelling(), NominalClassLayout.plan(schema, mapper));
        }

        Map<DeclarationId, IrDeclaration> declarations = ir.declarations().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        IrDeclaration::id, value -> value));
        List<IrExport> emittedExports = ir.exports().stream()
                .filter(export -> ir.module(export.moduleId()).isPresent())
                // A public type is compile-time namespace/schema linkage. It has
                // no module-state value getter; construction uses the state factory.
                .filter(export -> !isNominalTypeRole(declarations, export.declarationId()))
                .toList();
        List<JvmExportId> exportIds = emittedExports.stream()
                .map(export -> exportId(export))
                .sorted()
                .toList();
        JvmJavaNamePlan javaNames = JvmJavaNamePlan.plan(exportIds);
        Map<IrExport, GeneratedExportPlan> exportPlans = exportPlans(
                emittedExports, javaNames, mapper);

        Map<ModuleId, String> moduleStates = names.moduleStates();
        Map<ModuleId, String> moduleFacades = names.moduleFacades();
        Map<LambdaId, String> closureClasses = names.closureClasses();
        Map<DeclarationId, String> cellClasses = names.cellClasses();
        Map<DeclarationId, String> intrinsicFunctionClasses = names.intrinsicFunctionClasses();

        Map<DeclarationId, IrLambda> lambdasByOwner = new TreeMap<>();
        for (IrLambda lambda : ir.lambdas()) {
            lambda.ownerDeclaration().ifPresent(owner -> lambdasByOwner.put(owner, lambda));
        }
        Map<DeclarationId, IrCell> cellsByDeclaration = ir.cells().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        IrCell::declarationId, value -> value));
        Map<CaptureId, IrCapture> captures = ir.captures().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        IrCapture::id, value -> value));

        ArrayList<GeneratedClassPlan> classes = new ArrayList<>();
        LinkedHashMap<String, NominalMemberDelegateLayout> delegateLayouts = new LinkedHashMap<>();
        for (var layout : nominalLayouts.values()) {
            var dependencies = new LinkedHashSet<GeneratedClassDependency>();
            for (var field : layout.fields()) addTypeDependencies(dependencies, field.member().type(),
                    GeneratedDependencyKind.NOMINAL_TYPE_LINKAGE, false, names, "nominal field type");
            classes.add(new GeneratedClassPlan(layout.binaryName(), GeneratedClassKind.NOMINAL_VALUE,
                    "nominal:" + layout.schema().type().canonicalSpelling(), Optional.empty(), true,
                    false, List.of(), List.of(), List.copyOf(dependencies), layout.members(), Optional.of(layout)));
        }
        // Occurrence-scoped callable member route delegates are session-only
        // structural classes: ordinary AOT artifacts keep their exact class
        // inventory, and attached roots already bridge through their shared
        // root lifetime without per-read evidence.
        if (emissionMode == EmissionMode.SESSION) {
            for (var layout : nominalLayouts.values()) {
                for (int index = 0; index < layout.fields().size(); index++) {
                    var field = layout.fields().get(index);
                    var base = field.member().type().withoutQualifiers();
                    if (!(base instanceof FunctionType function)) continue;
                    JvmSignaturePlan signature = mapper.mapSignature(
                            function.signature(), JvmAbiBoundary.JAVA_VISIBLE);
                    String interfaceName = typeNames.functionBinaryName(
                            function.signature().canonicalSpelling());
                    NominalMemberDelegateLayout delegate = new NominalMemberDelegateLayout(
                            layout, index, interfaceName, signature);
                    String delegateName = delegate.binaryName(typeNames);
                    if (delegateLayouts.put(delegateName, delegate) != null) {
                        throw new IllegalArgumentException(
                                "duplicate nominal member delegate: " + delegateName);
                    }
                    var dependencies = new LinkedHashSet<GeneratedClassDependency>();
                    dependencies.add(new GeneratedClassDependency(interfaceName,
                            GeneratedDependencyKind.NOMINAL_MEMBER_DELEGATE_INTERFACE, true,
                            "nominal member delegate implements one function interface"));
                    dependencies.add(new GeneratedClassDependency(layout.binaryName(),
                            GeneratedDependencyKind.NOMINAL_TYPE_LINKAGE, false,
                            "nominal member delegate routes one nominal field"));
                    var members = List.of(
                            GeneratedMemberPlan.rawMethod(
                                    GeneratedMemberKind.NOMINAL_MEMBER_DELEGATE_CONSTRUCTOR, "<init>",
                                    "(Ljava/lang/Object;)V", false),
                            GeneratedMemberPlan.method(
                                    GeneratedMemberKind.NOMINAL_MEMBER_DELEGATE_INVOKE, "invoke",
                                    signature, false, Optional.empty(), Optional.empty()));
                    classes.add(new GeneratedClassPlan(delegateName,
                            GeneratedClassKind.NOMINAL_MEMBER_DELEGATE,
                            "nominal-member-delegate:" + layout.schema().type().canonicalSpelling()
                                    + "#" + index, Optional.empty(), true, false,
                            List.of(interfaceName), List.of(), List.copyOf(dependencies), members));
                }
            }
        }
        addTupleClasses(classes, inventory, mapper, names,
                emissionMode != EmissionMode.NORMAL);
        addFunctionInterfaces(classes, inventory, mapper, names);
        addCellClasses(classes, ir.cells(), mapper, names, cellClasses);
        Map<ModuleId, ScopeId> rootScopes =
                ir.modules().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        IrModule::moduleId, IrModule::rootScope));
        Set<DeclarationId> rootDeclarations = ir.declarations().stream()
                .filter(declaration -> declaration.scopeId().equals(
                        rootScopes.get(declaration.moduleId())))
                .map(IrDeclaration::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        addClosureClasses(classes, ir, mapper, names, closureClasses, cellClasses, moduleStates,
                captures, lambdasByOwner, declarations, rootDeclarations);
        addIntrinsicClosureClasses(classes, ir, mapper, names, intrinsicFunctionClasses,
                moduleStates);
        addModuleStateClasses(classes, ir, mapper, names, moduleStates, cellClasses,
                declarations, cellsByDeclaration, nominalLayouts, emissionMode);
        addModuleFacadeClasses(classes, ir, mapper, names, moduleStates, moduleFacades,
                exportPlans, emissionMode);

        List<GeneratedClassPlan> ordered = order(classes);
        GeneratedTypePlan result = new GeneratedTypePlan(basePackage, typeNames, ordered,
                names.tupleNames(), names.functionNames(), closureClasses, cellClasses,
                moduleStates, moduleFacades, new ArrayList<>(exportPlans.values()),
                ir.initializationOrder(), intrinsicFunctionClasses, ir.sessionExecution(),
                emissionMode, nominalLayouts, delegateLayouts);
        // Descriptor/signature parity is a publication gate for the plan; no
        // later class-body phase may start from a partially audited shape.
        JvmAbiParity.require(ir, result);
        return result;
    }

    /**
     * Topologically orders an independently built class-plan set.  Only
     * {@link GeneratedClassDependency#orderingRequired()} edges participate;
     * linkage-only recursion is retained in the classes but cannot create a
     * false emission cycle.
     */
    public static List<GeneratedClassPlan> order(Collection<? extends GeneratedClassPlan> classes) {
        Objects.requireNonNull(classes, "classes");
        ArrayList<GeneratedClassPlan> input = new ArrayList<>();
        for (GeneratedClassPlan value : classes) {
            input.add(Objects.requireNonNull(value, "classes must not contain null"));
        }
        input.sort(classComparator());
        LinkedHashMap<String, GeneratedClassPlan> byName = new LinkedHashMap<>();
        for (GeneratedClassPlan value : input) {
            if (byName.put(value.binaryName(), value) != null) {
                throw new IllegalArgumentException("duplicate generated class: " + value.binaryName());
            }
        }

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (GeneratedClassPlan value : input) {
            indegree.put(value.binaryName(), 0);
            dependents.put(value.binaryName(), new TreeSet<>());
        }
        for (GeneratedClassPlan value : input) {
            Set<String> countedTargets = new HashSet<>();
            for (GeneratedClassDependency dependency : value.dependencies()) {
                if (!byName.containsKey(dependency.targetBinaryName())) {
                    throw new IllegalArgumentException("generated dependency targets absent class: "
                            + dependency.targetBinaryName());
                }
                if (dependency.targetBinaryName().equals(value.binaryName())) {
                    boolean nominalSelf = value.kind() == GeneratedClassKind.NOMINAL_VALUE
                            && dependency.kind() == GeneratedDependencyKind.NOMINAL_TYPE_LINKAGE
                            && !dependency.orderingRequired();
                    if (!nominalSelf && (value.kind() != GeneratedClassKind.CLOSURE
                            || dependency.kind() != GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE
                            || dependency.orderingRequired())) {
                        throw cycle(List.of(value.binaryName()));
                    }
                    continue;
                }
                if (!dependency.orderingRequired()) {
                    continue;
                }
                if (countedTargets.add(dependency.targetBinaryName())) {
                    indegree.compute(value.binaryName(), (ignored, current) -> current + 1);
                    dependents.get(dependency.targetBinaryName()).add(value.binaryName());
                }
            }
        }

        PriorityQueue<GeneratedClassPlan> ready = new PriorityQueue<>(classComparator());
        for (GeneratedClassPlan value : input) {
            if (indegree.get(value.binaryName()) == 0) {
                ready.add(value);
            }
        }
        ArrayList<GeneratedClassPlan> result = new ArrayList<>(input.size());
        while (!ready.isEmpty()) {
            GeneratedClassPlan current = ready.remove();
            result.add(current);
            for (String dependent : dependents.get(current.binaryName())) {
                int remaining = indegree.computeIfPresent(dependent,
                        (ignored, currentDegree) -> currentDegree - 1);
                if (remaining == 0) {
                    ready.add(byName.get(dependent));
                }
            }
        }
        if (result.size() != input.size()) {
            throw cycle(findRequiredCycle(byName, indegree));
        }
        return List.copyOf(result);
    }

    public static List<GeneratedClassPlan> orderClasses(
            Collection<? extends GeneratedClassPlan> classes) {
        return order(classes);
    }

    private static Map<IrExport, GeneratedExportPlan> exportPlans(
            List<IrExport> exports,
            JvmJavaNamePlan javaNames,
            JvmAbiMapper mapper) {
        Map<String, JvmExportId> idsByKey = new HashMap<>();
        for (IrExport export : exports) {
            if (idsByKey.put(exportKey(export), exportId(export)) != null) {
                throw new IllegalArgumentException("duplicate ABI export identity: " + exportKey(export));
            }
        }
        ArrayList<IrExport> sorted = new ArrayList<>(exports);
        sorted.sort(Comparator.comparing(value -> idsByKey.get(exportKey(value))));
        LinkedHashMap<IrExport, GeneratedExportPlan> result = new LinkedHashMap<>();
        for (IrExport export : sorted) {
            JvmExportId id = idsByKey.get(exportKey(export));
            JvmExportId originId = new JvmExportId(
                    export.originModule(), export.originName(), id.canonicalContract(),
                    export.originExport());
            String javaName = javaNames.nameFor(id);
            JvmTypePlan valueType = mapper.map(export.contract().valueType(),
                    JvmMappingContext.EXPORTED_VALUE);
            Optional<JvmSignaturePlan> functionSignature = export.functionSignature()
                    .map(signature -> mapper.mapSignature(signature, JvmAbiBoundary.JAVA_VISIBLE));
            boolean function = functionSignature.isPresent();
            Optional<String> invocationName = function
                    ? Optional.of(javaNames.invocationNameFor(id)) : Optional.empty();
            Optional<String> getterName = function
                    ? Optional.empty() : Optional.of("get$" + javaName);
            Optional<String> functionValueName = function
                    ? Optional.of("value$" + javaName) : Optional.empty();
            Optional<String> setterName = export.isMutable()
                    ? Optional.of("set$" + javaName) : Optional.empty();
            Optional<String> exportId = Optional.of(id.id());
            ArrayList<GeneratedMemberPlan> members = new ArrayList<>();
            functionSignature.ifPresent(signature -> members.add(GeneratedMemberPlan.method(
                    GeneratedMemberKind.FUNCTION_INVOCATION, invocationName.orElseThrow(), signature,
                    false, exportId, Optional.of(export.name()))));
            if (function) {
                JvmTypePlan functionValue = mapper.map(export.contract().valueType(),
                        JvmMappingContext.JAVA_FUNCTION_VALUE);
                members.add(GeneratedMemberPlan.valueMethod(
                        GeneratedMemberKind.FUNCTION_VALUE_GETTER, functionValueName.orElseThrow(),
                        functionValue, false, exportId, Optional.of(export.name())));
                if (setterName.isPresent()) {
                    members.add(GeneratedMemberPlan.valueMethod(
                            GeneratedMemberKind.SETTER, setterName.orElseThrow(), functionValue,
                            false, exportId, Optional.of(export.name())));
                }
            } else {
                members.add(GeneratedMemberPlan.valueMethod(
                        GeneratedMemberKind.VALUE_GETTER, getterName.orElseThrow(), valueType,
                        false, exportId, Optional.of(export.name())));
                if (setterName.isPresent()) {
                    members.add(GeneratedMemberPlan.valueMethod(
                            GeneratedMemberKind.SETTER, setterName.orElseThrow(), valueType,
                            false, exportId, Optional.of(export.name())));
                }
            }
            GeneratedExportPlan plan = new GeneratedExportPlan(
                    id, originId, export.moduleId(), export.declarationId(), export.name(),
                    export.reExport(), export.originModule(), export.originName(),
                    export.originDeclaration(), export.contract().mutability(), valueType,
                    functionSignature, javaName, invocationName, getterName, functionValueName,
                    setterName, members, export.exportId());
            result.put(export, plan);
        }
        return result;
    }

    private static void addTupleClasses(
            List<GeneratedClassPlan> classes,
            Inventory inventory,
            JvmAbiMapper mapper,
            NameAssignment names,
            boolean sharedSession) {
        for (Map.Entry<String, TupleType> entry : inventory.tuples().entrySet()) {
            TupleType tuple = entry.getValue();
            ArrayList<GeneratedMemberPlan> members = new ArrayList<>();
            LinkedHashSet<GeneratedClassDependency> dependencies = new LinkedHashSet<>();
            ArrayList<String> constructorDescriptors = new ArrayList<>();
            for (int index = 0; index < tuple.arity(); index++) {
                JvmTypePlan memberType = mapper.map(tuple.memberType(index), JvmMappingContext.TUPLE_FIELD);
                if (!memberType.isSingleValue() || memberType.descriptor().equals("V")) {
                    throw new IllegalArgumentException("tuple member has no single JVM descriptor: "
                            + tuple.memberType(index).canonicalSpelling());
                }
                String fieldName = "$lyra$" + index;
                members.add(GeneratedMemberPlan.field(GeneratedMemberKind.TUPLE_FIELD, fieldName,
                        memberType, memberType.descriptor(), index, Optional.empty(), Optional.empty()));
                members.add(GeneratedMemberPlan.rawMethod(sharedSession
                                ? GeneratedMemberKind.SESSION_TUPLE_COMPONENT_GET : GeneratedMemberKind.TUPLE_COMPONENT_GET,
                        "$lyra$get$" + index, "()" + memberType.descriptor(), false));
                constructorDescriptors.add(memberType.descriptor());
                addTypeDependencies(dependencies, tuple.memberType(index),
                        GeneratedDependencyKind.TUPLE_MEMBER_TYPE, true, names,
                        "tuple member type");
            }
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.TUPLE_CONSTRUCTOR,
                    "<init>", "(" + String.join("", constructorDescriptors) + ")V", false));
            classes.add(new GeneratedClassPlan(names.tupleNames().get(entry.getKey()),
                    GeneratedClassKind.TUPLE_VALUE, "tuple:" + entry.getKey(), Optional.empty(),
                    true, false, List.of(), List.of(), new ArrayList<>(dependencies), members));
        }
    }

    private static void addFunctionInterfaces(
            List<GeneratedClassPlan> classes,
            Inventory inventory,
            JvmAbiMapper mapper,
            NameAssignment names) {
        for (Map.Entry<String, FunctionType> entry : inventory.functions().entrySet()) {
            JvmSignaturePlan signature = mapper.mapSignature(
                    entry.getValue().signature(), JvmAbiBoundary.JAVA_VISIBLE);
            LinkedHashSet<GeneratedClassDependency> dependencies = new LinkedHashSet<>();
            FunctionType function = entry.getValue();
            for (LyraType parameter : function.parameterTypes()) {
                addTypeDependencies(dependencies, parameter,
                        GeneratedDependencyKind.FUNCTION_SIGNATURE_TYPE, true, names,
                        "function parameter type");
            }
            addTypeDependencies(dependencies, function.returnType(),
                    GeneratedDependencyKind.FUNCTION_SIGNATURE_TYPE, true, names,
                    "function return type");
            GeneratedMemberPlan invoke = GeneratedMemberPlan.method(
                    GeneratedMemberKind.FUNCTION_INVOKE, "invoke", signature, false,
                    Optional.empty(), Optional.empty());
            classes.add(new GeneratedClassPlan(names.functionNames().get(entry.getKey()),
                    GeneratedClassKind.FUNCTION_INTERFACE, "function:" + entry.getKey(), Optional.empty(),
                    false, true, List.of(), List.of("java.lang.FunctionalInterface"),
                    new ArrayList<>(dependencies), List.of(invoke)));
        }
    }

    private static void addCellClasses(
            List<GeneratedClassPlan> classes,
            List<IrCell> cells,
            JvmAbiMapper mapper,
            NameAssignment names,
            Map<DeclarationId, String> cellClasses) {
        for (IrCell cell : cells.stream().sorted(Comparator.comparing(IrCell::id)).toList()) {
            JvmTypePlan value = mapper.map(cell.contract().valueType(), JvmMappingContext.INTERNAL_CELL);
            ArrayList<GeneratedMemberPlan> members = new ArrayList<>();
            LinkedHashSet<GeneratedClassDependency> dependencies = new LinkedHashSet<>();
            List<String> components = storageFields(members, value, "$lyra$value",
                    GeneratedMemberKind.CELL_VALUE_FIELD, GeneratedMemberKind.CELL_PRESENCE_FIELD,
                    Optional.empty(), Optional.empty());
            addTypeDependencies(dependencies, cell.contract().valueType(),
                    GeneratedDependencyKind.CELL_VALUE_TYPE, true, names, "cell value type");
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_CONSTRUCTOR,
                    "<init>", "(" + String.join("", components) + ")V", false));
            if (components.size() == 1) {
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_GET,
                        "$lyra$get", "()" + components.getFirst(), false));
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_SET,
                        "$lyra$set", "(" + components.getFirst() + ")V", false));
            } else {
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_PRESENCE_GET,
                        "$lyra$isPresent", "()Z", false));
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_PAYLOAD_GET,
                        "$lyra$payload", "()" + components.get(1), false));
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_SET,
                        "$lyra$set", "(" + String.join("", components) + ")V", false));
            }
            classes.add(new GeneratedClassPlan(cellClasses.get(cell.declarationId()),
                    GeneratedClassKind.CELL, "cell:" + moduleKey(cell.moduleId())
                    + ":" + cell.declarationId().value() + ":" + cell.contract().valueType().canonicalSpelling(),
                    Optional.of(cell.moduleId()), true, false, List.of(), List.of(),
                    new ArrayList<>(dependencies), members));
        }
    }

    private static void addClosureClasses(
            List<GeneratedClassPlan> classes,
            TypedIr ir,
            JvmAbiMapper mapper,
            NameAssignment names,
            Map<LambdaId, String> closureClasses,
            Map<DeclarationId, String> cellClasses,
            Map<ModuleId, String> moduleStates,
            Map<CaptureId, IrCapture> captures,
            Map<DeclarationId, IrLambda> lambdasByOwner,
            Map<DeclarationId, IrDeclaration> declarations,
            Set<DeclarationId> rootDeclarations) {
        for (IrLambda lambda : ir.lambdas().stream().sorted(Comparator.comparing(IrLambda::id)).toList()) {
            JvmSignaturePlan signature = mapper.mapSignature(
                    lambda.signature(), JvmAbiBoundary.JAVA_VISIBLE);
            ArrayList<GeneratedMemberPlan> members = new ArrayList<>();
            LinkedHashSet<GeneratedClassDependency> dependencies = new LinkedHashSet<>();
            for (LyraType parameter : lambda.signature().parameterTypes()) {
                addTypeDependencies(dependencies, parameter,
                        GeneratedDependencyKind.FUNCTION_SIGNATURE_TYPE, true, names,
                        "closure invocation parameter type");
            }
            addTypeDependencies(dependencies, lambda.signature().returnType(),
                    GeneratedDependencyKind.FUNCTION_SIGNATURE_TYPE, true, names,
                    "closure invocation return type");
            members.add(GeneratedMemberPlan.rawField(
                    GeneratedMemberKind.CLOSURE_AUTHORITY_FIELD, "$lyra$authority",
                    AUTHORITY_DESCRIPTOR));
            String stateName = moduleStates.get(lambda.moduleId());
            if (stateName == null) {
                throw new IllegalArgumentException("lambda module has no generated state class");
            }
            String stateDescriptor = "L" + stateName.replace('.', '/') + ";";
            members.add(GeneratedMemberPlan.rawField(
                    GeneratedMemberKind.CLOSURE_STATE_FIELD, "$lyra$state", stateDescriptor));
            dependencies.add(new GeneratedClassDependency(stateName,
                    GeneratedDependencyKind.CLOSURE_MODULE_STATE, true,
                    "closure retains and accesses its module-state instance"));
            ArrayList<String> constructorDescriptors = new ArrayList<>();
            constructorDescriptors.add(AUTHORITY_DESCRIPTOR);
            constructorDescriptors.add(stateDescriptor);
            for (var captureId : lambda.captures().stream().sorted().toList()) {
                IrCapture capture = captures.get(captureId);
                if (capture == null) {
                    throw new IllegalArgumentException("lambda refers to absent capture: " + captureId);
                }
                if (isNominalTypeRole(declarations, capture.declarationId())) {
                    // A constructor target is a linked type role, not a live value capture.
                    continue;
                }
                if (capture.isSharedCell()) {
                    String cellName = cellClasses.get(capture.sharedCellId().orElseThrow());
                    if (cellName == null) {
                        throw new IllegalArgumentException("capture refers to absent cell");
                    }
                    String descriptor = "L" + cellName.replace('.', '/') + ";";
                    members.add(GeneratedMemberPlan.rawField(
                            GeneratedMemberKind.CLOSURE_CAPTURE_FIELD,
                            "$lyra$capture$" + captureId.value(), descriptor));
                    constructorDescriptors.add(descriptor);
                    dependencies.add(new GeneratedClassDependency(cellName,
                            GeneratedDependencyKind.CLOSURE_SHARED_CELL, true,
                            "shared mutable capture cell"));
                } else if (usesLocalFunctionSlot(capture, declarations, rootDeclarations)) {
                    JvmTypePlan captureType = mapper.map(capture.contract().valueType(),
                            JvmMappingContext.INTERNAL_CAPTURE);
                    if (!captureType.isSingleValue()
                            || !captureType.physicalComponents().getFirst().isReference()) {
                        throw new IllegalArgumentException(
                                "local function slot has no reference representation: " + captureId);
                    }
                    String descriptor = "[" + captureType.descriptor();
                    members.add(GeneratedMemberPlan.rawField(
                            GeneratedMemberKind.CLOSURE_CAPTURE_FIELD,
                            "$lyra$capture$" + captureId.value(), descriptor));
                    constructorDescriptors.add(descriptor);
                    addTypeDependencies(dependencies, capture.contract().valueType(),
                            GeneratedDependencyKind.CLOSURE_CAPTURE_TYPE, true, names,
                            "linked local function capture type");
                } else {
                    JvmTypePlan captureType = mapper.map(capture.contract().valueType(),
                            JvmMappingContext.INTERNAL_CAPTURE);
                    List<String> descriptors = storageFields(members, captureType,
                            "$lyra$capture$" + captureId.value(),
                            GeneratedMemberKind.CLOSURE_CAPTURE_PAYLOAD_FIELD,
                            GeneratedMemberKind.CLOSURE_CAPTURE_PRESENCE_FIELD,
                            Optional.empty(), Optional.empty());
                    constructorDescriptors.addAll(descriptors);
                    addTypeDependencies(dependencies, capture.contract().valueType(),
                            GeneratedDependencyKind.CLOSURE_CAPTURE_TYPE, true, names,
                            "immutable capture type");
                }
            }
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CLOSURE_CONSTRUCTOR,
                    "<init>", "(" + String.join("", constructorDescriptors) + ")V", false));
            members.add(GeneratedMemberPlan.method(GeneratedMemberKind.CLOSURE_INVOKE,
                    "invoke", signature, false, Optional.empty(), Optional.empty()));
            String functionName = names.functionNames().get(lambda.signature().canonicalSpelling());
            if (functionName == null) {
                throw new IllegalArgumentException("lambda signature has no generated function interface");
            }
            dependencies.add(new GeneratedClassDependency(functionName,
                    GeneratedDependencyKind.CLOSURE_FUNCTION_INTERFACE, true,
                    "closure implements its typed functional interface"));

            for (IrFunctionLink link : ir.functionLinkage().links()) {
                if (lambda.ownerDeclaration().filter(link.from()::equals).isEmpty()) {
                    continue;
                }
                IrDeclaration targetDeclaration = declarations.get(link.to());
                if (targetDeclaration != null
                        && targetDeclaration.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.INTRINSIC_EXPORT) {
                    // Intrinsic calls are lowered directly to the runtime and
                    // therefore have no generated closure class or linkage
                    // ordering edge.
                    continue;
                }
                if (ir.sessionExecution().flatMap(execution ->
                        execution.access(lambda.moduleId(), link.to())).isPresent()) {
                    continue;
                }
                IrLambda target = lambdasByOwner.get(link.to());
                if (target == null) {
                    throw new IllegalArgumentException(
                            "function linkage target has no generated closure: " + link.to());
                }
                String targetName = closureClasses.get(target.id());
                if (targetName == null) {
                    throw new IllegalArgumentException(
                            "function linkage target closure is absent from the class index: "
                                    + target.id());
                }
                dependencies.add(new GeneratedClassDependency(targetName,
                        GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE, false,
                        "recursive function linkage does not impose class order"));
            }
            if (ir.closureInitializations().stream()
                    .filter(value -> value.lambdaId().equals(lambda.id()))
                    .anyMatch(value -> value.recursive())) {
                dependencies.add(new GeneratedClassDependency(closureClasses.get(lambda.id()),
                        GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE, false,
                        "recursive function linkage does not impose class order"));
            }
            classes.add(new GeneratedClassPlan(closureClasses.get(lambda.id()),
                    GeneratedClassKind.CLOSURE, "closure:" + moduleKey(lambda.moduleId())
                    + ":" + lambda.id().value() + ":" + lambda.signature().canonicalSpelling(),
                    Optional.of(lambda.moduleId()), true, false, List.of(functionName), List.of(),
                    new ArrayList<>(dependencies), members));
        }
    }

    private static void addIntrinsicClosureClasses(
            List<GeneratedClassPlan> classes,
            TypedIr ir,
            JvmAbiMapper mapper,
            NameAssignment names,
            Map<DeclarationId, String> intrinsicFunctionClasses,
            Map<ModuleId, String> moduleStates) {
        for (IrDeclaration declaration : ir.declarations().stream()
                .filter(value -> value.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.INTRINSIC_EXPORT)
                .sorted(Comparator.comparing(IrDeclaration::id)).toList()) {
            if (declaration.contract().isEmpty()
                    || !(declaration.contract().orElseThrow().valueType().withoutQualifiers()
                    instanceof FunctionType function)) {
                throw new IllegalArgumentException("intrinsic export is not a function: " + declaration.id());
            }
            String className = intrinsicFunctionClasses.get(declaration.id());
            String functionName = names.functionNames().get(function.canonicalSpelling());
            String stateName = moduleStates.get(declaration.moduleId());
            if (className == null || functionName == null || stateName == null) {
                throw new IllegalArgumentException("intrinsic export has incomplete generated type mappings: "
                        + declaration.id());
            }
            JvmSignaturePlan signature = mapper.mapSignature(function.signature(),
                    JvmAbiBoundary.JAVA_VISIBLE);
            String stateDescriptor = "L" + stateName.replace('.', '/') + ";";
            ArrayList<GeneratedMemberPlan> members = new ArrayList<>();
            members.add(GeneratedMemberPlan.rawField(
                    GeneratedMemberKind.CLOSURE_AUTHORITY_FIELD, "$lyra$authority",
                    AUTHORITY_DESCRIPTOR));
            members.add(GeneratedMemberPlan.rawField(
                    GeneratedMemberKind.CLOSURE_STATE_FIELD, "$lyra$state", stateDescriptor));
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CLOSURE_CONSTRUCTOR,
                    "<init>", "(" + AUTHORITY_DESCRIPTOR + stateDescriptor + ")V", false));
            members.add(GeneratedMemberPlan.method(GeneratedMemberKind.CLOSURE_INVOKE,
                    "invoke", signature, false, Optional.empty(), Optional.empty()));
            LinkedHashSet<GeneratedClassDependency> dependencies = new LinkedHashSet<>();
            dependencies.add(new GeneratedClassDependency(functionName,
                    GeneratedDependencyKind.CLOSURE_FUNCTION_INTERFACE, true,
                    "intrinsic function adapter implements its typed functional interface"));
            dependencies.add(new GeneratedClassDependency(stateName,
                    GeneratedDependencyKind.CLOSURE_MODULE_STATE, true,
                    "intrinsic function adapter retains its module-state instance"));
            classes.add(new GeneratedClassPlan(className, GeneratedClassKind.CLOSURE,
                    "intrinsic-closure:" + moduleKey(declaration.moduleId()) + ":"
                            + declaration.id().value() + ":" + function.canonicalSpelling(),
                    Optional.of(declaration.moduleId()), true, false, List.of(functionName), List.of(),
                    new ArrayList<>(dependencies), members));
        }
    }

    private static boolean usesLocalFunctionSlot(
            IrCapture capture,
            Map<DeclarationId, IrDeclaration> declarations,
            Set<DeclarationId> rootDeclarations) {
        IrDeclaration declaration = declarations.get(capture.declarationId());
        return !capture.isSharedCell()
                && declaration != null
                && !rootDeclarations.contains(declaration.id())
                && declaration.signaturePredeclared()
                && declaration.initializerLambda().isPresent()
                && declaration.contract().map(BindingContract::valueType)
                .map(LyraType::withoutQualifiers)
                .filter(FunctionType.class::isInstance)
                .isPresent();
    }

    private static void addModuleStateClasses(
            List<GeneratedClassPlan> classes,
            TypedIr ir,
            JvmAbiMapper mapper,
            NameAssignment names,
            Map<ModuleId, String> moduleStates,
            Map<DeclarationId, String> cellClasses,
            Map<DeclarationId, IrDeclaration> declarations,
            Map<DeclarationId, IrCell> cellsByDeclaration,
            Map<String, NominalClassLayout> nominalLayouts,
            EmissionMode emissionMode) {
        Map<DeclarationId, IrImportBinding> importsByDeclaration = ir.imports().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        IrImportBinding::declarationId, value -> value));
        for (IrModule module : ir.modules()) {
            ArrayList<GeneratedMemberPlan> members = new ArrayList<>();
            LinkedHashSet<GeneratedClassDependency> dependencies = new LinkedHashSet<>();
            members.add(GeneratedMemberPlan.rawField(
                    GeneratedMemberKind.STATE_LIFECYCLE_FIELD,
                    "$lyra$lifecycle", LIFECYCLE_DESCRIPTOR));
            for (IrImportBinding importBinding : ir.imports()) {
                IrDeclaration importDeclaration = declarations.get(importBinding.declarationId());
                if (importDeclaration == null || !importDeclaration.moduleId().equals(module.moduleId())) {
                    continue;
                }
                String targetState = moduleStates.get(importBinding.targetModule());
                if (targetState == null && ir.sessionExecution()
                        .filter(value -> !value.emits(importBinding.targetModule())).isPresent()) continue;
                if (targetState == null) {
                    throw new IllegalArgumentException("import binding targets absent state class");
                }
                dependencies.add(new GeneratedClassDependency(targetState,
                        GeneratedDependencyKind.MODULE_IMPORT_LINKAGE, false,
                        "imported module/value linkage"));
            }
            for (var initialization : ir.initializationDependencies()) {
                if (!initialization.fromModule().equals(module.moduleId())) {
                    continue;
                }
                String targetState = moduleStates.get(initialization.toModule());
                if (targetState == null) {
                    throw new IllegalArgumentException(
                            "initialization dependency targets absent state class");
                }
                dependencies.add(new GeneratedClassDependency(targetState,
                        GeneratedDependencyKind.MODULE_INITIALIZATION, true,
                        "eager initialization dependency"));
            }
            for (DeclarationId declarationId : module.state().declarations()) {
                IrDeclaration declaration = declarations.get(declarationId);
                if (declaration == null) {
                    throw new IllegalArgumentException("module state refers to absent declaration");
                }
                if (!declaration.scopeId().equals(module.state().rootScope())) {
                    continue;
                }
                if (declaration.kind() == DeclarationKind.EXTERNAL || declaration.kind() == DeclarationKind.NOMINAL) continue;
                String prefix = "$lyra$binding$" + declarationId.value();
                IrImportBinding importBinding = importsByDeclaration.get(declarationId);
                if (importBinding != null) {
                    String targetState = moduleStates.get(importBinding.targetModule());
                    if (targetState == null && ir.sessionExecution()
                            .filter(value -> !value.emits(importBinding.targetModule())).isPresent()) continue;
                    if (targetState == null) {
                        throw new IllegalArgumentException("import binding targets absent state class");
                    }
                    String descriptor = "L" + targetState.replace('.', '/') + ";";
                    members.add(GeneratedMemberPlan.rawField(
                            GeneratedMemberKind.STATE_IMPORT_FIELD, prefix, descriptor));
                    members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_COMPONENT_GET,
                            "$lyra$get$binding$" + declarationId.value(), "()" + descriptor, false));
                    members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_IMPORT_LINK,
                            "$lyra$link$binding$" + declarationId.value(),
                            "(" + descriptor + ")V", false));
                    continue;
                }
                String cellName = cellClasses.get(declarationId);
                if (cellName != null && cellsByDeclaration.containsKey(declarationId)) {
                    String descriptor = "L" + cellName.replace('.', '/') + ";";
                    members.add(GeneratedMemberPlan.rawField(
                            GeneratedMemberKind.STATE_CELL_FIELD, prefix, descriptor));
                    members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_COMPONENT_GET,
                            "$lyra$get$binding$" + declarationId.value(), "()" + descriptor, false));
                    dependencies.add(new GeneratedClassDependency(cellName,
                            GeneratedDependencyKind.MODULE_STATE_FIELD_TYPE, true,
                            "module state owns the shared mutable cell"));
                    continue;
                }
                if (declaration.imported()) {
                    throw new IllegalArgumentException("import declaration has no IR import binding");
                }
                Optional<BindingContract> contract = declaration.contract();
                if (contract.isEmpty()) {
                    continue;
                }
                JvmBindingPlan binding = mapper.mapBinding(contract.orElseThrow());
                List<String> descriptors = storageFields(members, binding.value(), prefix,
                        GeneratedMemberKind.STATE_PAYLOAD_FIELD,
                        GeneratedMemberKind.STATE_PRESENCE_FIELD,
                        Optional.empty(), Optional.of(declaration.name()));
                if (descriptors.size() == 1) {
                    // storageFields emitted a payload role; replace only
                    // the normal one-component role with binding-field.
                    members.removeLast();
                    members.add(GeneratedMemberPlan.field(
                            GeneratedMemberKind.STATE_BINDING_FIELD, prefix,
                            binding.value(), descriptors.getFirst(), 0,
                            Optional.empty(), Optional.of(declaration.name())));
                    members.add(GeneratedMemberPlan.rawMethod(
                            GeneratedMemberKind.STATE_COMPONENT_GET,
                            "$lyra$get$binding$" + declarationId.value(),
                            "()" + descriptors.getFirst(), false));
                } else {
                    members.add(GeneratedMemberPlan.rawMethod(
                            GeneratedMemberKind.STATE_COMPONENT_GET,
                            "$lyra$isPresent$binding$" + declarationId.value(), "()Z", false));
                    members.add(GeneratedMemberPlan.rawMethod(
                            GeneratedMemberKind.STATE_COMPONENT_GET,
                            "$lyra$payload$binding$" + declarationId.value(),
                            "()" + descriptors.get(1), false));
                }
                if (binding.isMutable()) {
                    members.add(GeneratedMemberPlan.rawMethod(
                            GeneratedMemberKind.STATE_COMPONENT_SET,
                            "$lyra$set$binding$" + declarationId.value(),
                            "(" + String.join("", descriptors) + ")V", false));
                }
                addTypeDependencies(dependencies, contract.orElseThrow().valueType(),
                        GeneratedDependencyKind.MODULE_STATE_FIELD_TYPE, true, names,
                        "module state binding type");
            }
            for (IrNode form : module.body().forms()) {
                if (!(form instanceof IrNode.NominalDeclaration nominal)) continue;
                NominalClassLayout layout = nominalLayouts.get(nominal.schema().type().canonicalSpelling());
                IrDeclaration declaration = declarations.get(nominal.declarationId());
                if (layout == null || declaration == null
                        || declaration.kind() != DeclarationKind.NOMINAL
                        || !declaration.moduleId().equals(module.moduleId())) {
                    throw new IllegalArgumentException("nominal declaration has no exact class layout");
                }
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_NOMINAL_FACTORY,
                        "$lyra$new$" + nominal.declarationId().value(),
                        layout.factorySignature().descriptor(), false));
                addTypeDependency(dependencies, layout.binaryName(),
                        GeneratedDependencyKind.NOMINAL_TYPE_LINKAGE, false,
                        "nominal construction result");
                for (LyraType parameter : nominal.schema().constructorParameters()) {
                    addTypeDependencies(dependencies, parameter,
                            GeneratedDependencyKind.NOMINAL_TYPE_LINKAGE, false, names,
                            "nominal constructor parameter");
                }
            }
            if (ir.sessionExecution().isPresent()) {
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_SESSION_ACCESSOR,
                        "$lyra$sessionAccessor", "(JJLjava/lang/String;Z)Ljava/lang/invoke/MethodHandle;", false));
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_SESSION_NOMINAL_FACTORY,
                        "$lyra$sessionNominalFactory", "(JLjava/lang/String;)Ljava/lang/invoke/MethodHandle;", false));
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_MODULE_STATE_LOOKUP,
                        "$lyra$moduleState", "(Lio/mindspice/lyra/runtime/ModuleId;)Ljava/lang/Object;", false));
            }
            if (ir.sessionExecution().isPresent()) {
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.SESSION_SAFE_POINT,
                        "$lyra$sessionSafePoint", "()V", false));
            }
            if (emissionMode == EmissionMode.ATTACHABLE
                    && module.moduleId().equals(ir.rootModule().moduleId())) {
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.ATTACHMENT_SAFE_POINT,
                        "$lyra$attachmentSafePoint", "()V", false));
                members.add(GeneratedMemberPlan.rawMethod(
                        GeneratedMemberKind.STATE_ATTACHMENT_LIFECYCLE,
                        "$lyra$attachmentLifecycle", "()Lio/mindspice/lyra/runtime/ModuleLifecycle;", false));
            }
            module.submissionResult().ifPresent(result -> {
                String descriptor = mapper.map(result.type(), JvmMappingContext.JAVA_VALUE).descriptor();
                addTypeDependencies(dependencies, result.type(),
                        GeneratedDependencyKind.MODULE_STATE_FIELD_TYPE, true, names,
                        "submission result storage type");
                members.add(GeneratedMemberPlan.rawField(GeneratedMemberKind.SESSION_RESULT_FIELD,
                        "$lyra$sessionResult", descriptor));
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.SESSION_EXECUTE,
                        "$lyra$sessionExecute", "()" + descriptor, false));
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.SESSION_RESULT_GET,
                        "$lyra$sessionResult", "()" + descriptor, false));
            });
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CONSTRUCTOR,
                    "<init>", "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false));
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_AUTHORITY_GET,
                    "$lyra$closureAuthority", "()" + AUTHORITY_DESCRIPTOR, false));
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CHECK_OPEN,
                    "$lyra$checkOpen", "()V", false));
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_FACTORY_CHECK_OPEN,
                    "$lyra$factoryCheckOpen", "()V", false));
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CLOSE,
                    "$lyra$close", "()V", false));
            classes.add(new GeneratedClassPlan(moduleStates.get(module.moduleId()),
                    GeneratedClassKind.MODULE_STATE,
                    "state:" + moduleKey(module.moduleId()), Optional.of(module.moduleId()),
                    true, false, List.of(), List.of(), new ArrayList<>(dependencies), members));
        }
    }

    private static void addModuleFacadeClasses(
            List<GeneratedClassPlan> classes,
            TypedIr ir,
            JvmAbiMapper mapper,
            NameAssignment names,
            Map<ModuleId, String> moduleStates,
            Map<ModuleId, String> moduleFacades,
            Map<IrExport, GeneratedExportPlan> exportPlans,
            EmissionMode emissionMode) {
        for (ModuleId moduleId : ir.modules().stream().map(IrModule::moduleId).sorted().toList()) {
            String stateName = moduleStates.get(moduleId);
            String facadeName = moduleFacades.get(moduleId);
            if (stateName == null || facadeName == null) {
                throw new IllegalArgumentException("module has no generated state/facade name");
            }
            ArrayList<GeneratedMemberPlan> members = new ArrayList<>();
            LinkedHashSet<GeneratedClassDependency> dependencies = new LinkedHashSet<>();
            String stateDescriptor = "L" + stateName.replace('.', '/') + ";";
            members.add(GeneratedMemberPlan.rawField(
                    GeneratedMemberKind.FACADE_STATE_FIELD, "$lyra$state", stateDescriptor));
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.FACADE_CONSTRUCTOR,
                    "<init>", "(" + stateDescriptor + ")V", false));
            String facadeDescriptor = "L" + facadeName.replace('.', '/') + ";";
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.FACTORY,
                    "$lyra$create", "()" + facadeDescriptor, true));
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.FACTORY_WITH_OPTIONS,
                    "$lyra$create", "(" + OPTIONS_DESCRIPTOR + ")" + facadeDescriptor, true));
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.METADATA,
                    "$lyra$metadata", "()" + METADATA_DESCRIPTOR, true));
            members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CLOSE,
                    "close", "()V", false));
            if (emissionMode == EmissionMode.ATTACHABLE
                    && moduleId.equals(ir.rootModule().moduleId())) {
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.ATTACHMENT_LIFECYCLE,
                        "$lyra$attachmentLifecycle", "()" + LIFECYCLE_DESCRIPTOR, false));
            }
            if (ir.sessionExecution().isPresent()) {
                for (IrDeclaration declaration : ir.declarations()) {
                    if (!declaration.moduleId().equals(moduleId)
                            || !declaration.scopeId().equals(ir.module(moduleId).orElseThrow().rootScope())
                            || declaration.kind() != DeclarationKind.LET) continue;
                    String value = mapper.map(declaration.contract().orElseThrow().valueType(),
                            JvmMappingContext.JAVA_VALUE).descriptor();
                    addTypeDependencies(dependencies, declaration.contract().orElseThrow().valueType(),
                            GeneratedDependencyKind.FACADE_EXPORT_TYPE, true, names,
                            "session binding boundary type");
                    members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.SESSION_BINDING_GET,
                            "$lyra$sessionRead$binding$" + declaration.id().value(), "()" + value, false));
                    if (declaration.isMutable()) members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.SESSION_BINDING_SET,
                            "$lyra$sessionWrite$binding$" + declaration.id().value(), "(" + value + ")V", false));
                }
                if (moduleId.equals(ir.rootModule().moduleId())) {
                    members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.FACADE_MODULE_STATE,
                            "$lyra$moduleState", "(Lio/mindspice/lyra/runtime/ModuleId;)Ljava/lang/Object;", false));
                }
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.FACADE_VIEW_FACTORY,
                        "$lyra$view", "(Ljava/lang/Object;)" + facadeDescriptor, true));
            }
            ir.module(moduleId).orElseThrow().submissionResult().ifPresent(result -> {
                String descriptor = mapper.map(result.type(), JvmMappingContext.JAVA_VALUE).descriptor();
                addTypeDependencies(dependencies, result.type(),
                        GeneratedDependencyKind.FACADE_EXPORT_TYPE, true, names,
                        "submission result boundary type");
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.FACADE_SESSION_RESULT_GET,
                        "$lyra$sessionResult", "()" + descriptor, false));
                members.add(GeneratedMemberPlan.rawMethod(GeneratedMemberKind.FACADE_SESSION_EXECUTE,
                        "$lyra$sessionRun", "()V", false));
            });
            dependencies.add(new GeneratedClassDependency(stateName,
                    GeneratedDependencyKind.FACADE_STATE, true,
                    "facade owns exactly one module-state instance"));
            for (Map.Entry<IrExport, GeneratedExportPlan> entry : exportPlans.entrySet().stream()
                    .filter(value -> value.getKey().moduleId().equals(moduleId))
                    .sorted(Map.Entry.comparingByValue(Comparator.comparing(GeneratedExportPlan::exportId)))
                    .toList()) {
                GeneratedExportPlan export = entry.getValue();
                members.addAll(export.members());
                addTypeDependencies(dependencies, entry.getKey().contract().valueType(),
                        GeneratedDependencyKind.FACADE_EXPORT_TYPE, true, names,
                        "facade export type");
            }
            classes.add(new GeneratedClassPlan(facadeName, GeneratedClassKind.MODULE_FACADE,
                    "facade:" + moduleKey(moduleId), Optional.of(moduleId), true, false,
                    List.of("java.lang.AutoCloseable"), List.of(), new ArrayList<>(dependencies), members));
        }
    }

    /** Adds fields for one logical storage value and returns constructor components. */
    private static List<String> storageFields(
            List<GeneratedMemberPlan> members,
            JvmTypePlan value,
            String prefix,
            GeneratedMemberKind payloadKind,
            GeneratedMemberKind presenceKind,
            Optional<String> exportId,
            Optional<String> sourceName) {
        List<JvmType> components = value.physicalComponents();
        ArrayList<String> descriptors = new ArrayList<>(components.size());
        if (components.size() == 1) {
            JvmType component = components.getFirst();
            members.add(GeneratedMemberPlan.field(payloadKind, prefix, value,
                    component.descriptor(), 0, exportId, sourceName));
            descriptors.add(component.descriptor());
            return List.copyOf(descriptors);
        }
        if (components.size() != 2 || !(value.representation() instanceof JvmPresencePayload)) {
            throw new IllegalArgumentException("unsupported multi-component ABI storage plan");
        }
        JvmType presence = components.getFirst();
        JvmType payload = components.get(1);
        members.add(GeneratedMemberPlan.field(presenceKind, prefix + "$present", value,
                presence.descriptor(), 0, exportId, sourceName));
        members.add(GeneratedMemberPlan.field(payloadKind, prefix + "$payload", value,
                payload.descriptor(), 1, exportId, sourceName));
        descriptors.add(presence.descriptor());
        descriptors.add(payload.descriptor());
        return List.copyOf(descriptors);
    }

    private static void addTypeDependencies(
            Set<GeneratedClassDependency> dependencies,
            LyraType type,
            GeneratedDependencyKind kind,
            boolean orderingRequired,
            NameAssignment names,
            String reason) {
        LyraType base = type.withoutQualifiers();
        if (base instanceof io.mindspice.lyra.compiler.types.NominalType nominal) {
            String target = names.nominalNames().get(nominal.canonicalSpelling());
            if (target == null) throw new IllegalArgumentException("nominal type has no generated class: " + nominal);
            addTypeDependency(dependencies, target, GeneratedDependencyKind.NOMINAL_TYPE_LINKAGE, false, reason);
            return;
        }
        if (base instanceof ArrayType array) {
            addTypeDependencies(dependencies, array.elementType(), kind, orderingRequired, names, reason);
            return;
        }
        if (base instanceof TupleType tuple) {
            String target = names.tupleNames().get(tuple.canonicalSpelling());
            if (target == null) {
                throw new IllegalArgumentException("tuple type has no generated class: " + tuple);
            }
            addTypeDependency(dependencies, target, kind, orderingRequired, reason);
            for (LyraType member : tuple.memberTypes()) {
                addTypeDependencies(dependencies, member, kind, orderingRequired, names, reason);
            }
            return;
        }
        if (base instanceof FunctionType function) {
            String target = names.functionNames().get(function.canonicalSpelling());
            if (target == null) {
                throw new IllegalArgumentException("function type has no generated interface: " + function);
            }
            addTypeDependency(dependencies, target, kind, orderingRequired, reason);
            for (LyraType parameter : function.parameterTypes()) {
                addTypeDependencies(dependencies, parameter, kind, orderingRequired, names, reason);
            }
            addTypeDependencies(dependencies, function.returnType(), kind, orderingRequired, names, reason);
        }
    }

    /**
     * Adds one type edge by its structural identity.  The reason is diagnostic
     * context, not part of the generated dependency contract, so a composite
     * session result may legitimately revisit a type already used by a named
     * binding.  The class-plan validator still rejects conflicting duplicate
     * edges supplied outside this planner.
     */
    private static void addTypeDependency(
            Set<GeneratedClassDependency> dependencies,
            String target,
            GeneratedDependencyKind kind,
            boolean orderingRequired,
            String reason) {
        GeneratedClassDependency candidate = new GeneratedClassDependency(
                target, kind, orderingRequired, reason);
        for (GeneratedClassDependency existing : dependencies) {
            if (!existing.targetBinaryName().equals(candidate.targetBinaryName())
                    || existing.kind() != candidate.kind()) {
                continue;
            }
            if (existing.orderingRequired() != candidate.orderingRequired()) {
                throw new IllegalArgumentException(
                        "conflicting generated dependency ordering: "
                                + candidate.targetBinaryName() + " / " + candidate.kind());
            }
            return;
        }
        dependencies.add(candidate);
    }

    private static void validateIrCoverage(TypedIr ir) {
        Map<ModuleId, IrModule> modulesById = new TreeMap<>();
        for (IrModule module : ir.modules()) {
            if (modulesById.put(module.moduleId(), module) != null) {
                throw new IllegalArgumentException("typed IR contains duplicate module metadata");
            }
        }
        Map<DeclarationId, IrDeclaration> declarationsById = new TreeMap<>();
        for (IrDeclaration declaration : ir.declarations()) {
            if (declarationsById.put(declaration.id(), declaration) != null) {
                throw new IllegalArgumentException("typed IR contains duplicate declaration metadata");
            }
        }
        Map<io.mindspice.lyra.compiler.identity.ReferenceId, io.mindspice.lyra.compiler.ir.IrReference>
                referencesById = new TreeMap<>();
        for (var reference : ir.references()) {
            if (referencesById.put(reference.id(), reference) != null) {
                throw new IllegalArgumentException("typed IR contains duplicate reference metadata");
            }
        }
        Map<LambdaId, IrLambda> lambdasById = new TreeMap<>();
        Map<DeclarationId, IrLambda> lambdaOwners = new TreeMap<>();
        for (IrLambda lambda : ir.lambdas()) {
            if (lambdasById.put(lambda.id(), lambda) != null) {
                throw new IllegalArgumentException("typed IR contains duplicate lambda metadata");
            }
            lambda.ownerDeclaration().ifPresent(owner -> {
                if (lambdaOwners.put(owner, lambda) != null) {
                    throw new IllegalArgumentException("typed IR contains duplicate lambda owners: " + owner);
                }
            });
        }
        Map<CaptureId, IrCapture> capturesById = new TreeMap<>();
        for (IrCapture capture : ir.captures()) {
            if (capturesById.put(capture.id(), capture) != null) {
                throw new IllegalArgumentException("typed IR contains duplicate capture metadata");
            }
        }
        Map<DeclarationId, IrCell> cellsByDeclaration = new TreeMap<>();
        for (IrCell cell : ir.cells()) {
            if (cellsByDeclaration.put(cell.declarationId(), cell) != null) {
                throw new IllegalArgumentException("typed IR contains duplicate cell metadata");
            }
        }
        Map<DeclarationId, IrImportBinding> importsByDeclaration = new TreeMap<>();
        for (IrImportBinding binding : ir.imports()) {
            if (importsByDeclaration.put(binding.declarationId(), binding) != null) {
                throw new IllegalArgumentException("typed IR contains duplicate import metadata");
            }
        }

        requireExactIds("module metadata", modulesById.keySet(),
                ir.modules().stream().map(IrModule::moduleId).collect(java.util.stream.Collectors.toSet()));
        for (Map.Entry<DeclarationId, LyraSignature> entry
                : ir.functionLinkage().signatures().entrySet()) {
            IrDeclaration declaration = declarationsById.get(entry.getKey());
            boolean matches = declaration != null && declaration.contract()
                    .map(BindingContract::valueType)
                    .map(LyraType::withoutQualifiers)
                    .filter(FunctionType.class::isInstance)
                    .map(FunctionType.class::cast)
                    .map(FunctionType::signature)
                    .filter(entry.getValue()::equals)
                    .isPresent();
            if (!matches) {
                throw new IllegalArgumentException("function signature linkage has the wrong declaration: "
                        + entry.getKey());
            }
        }
        for (IrModule module : ir.modules()) {
            IrModuleState state = module.state();
            Set<DeclarationId> moduleDeclarations = ir.declarations().stream()
                    .filter(value -> value.moduleId().equals(module.moduleId()))
                    .map(IrDeclaration::id).collect(java.util.stream.Collectors.toSet());
            requireExactIds("module declarations for " + module.moduleId(),
                    Set.copyOf(state.declarations()), moduleDeclarations);
            for (DeclarationId declarationId : state.declarations()) {
                IrDeclaration declaration = declarationsById.get(declarationId);
                if (declaration == null || !declaration.moduleId().equals(module.moduleId())) {
                    throw new IllegalArgumentException("module state declaration has the wrong owner: "
                            + declarationId);
                }
            }

            Set<io.mindspice.lyra.compiler.identity.ReferenceId> moduleReferences = ir.references().stream()
                    .filter(value -> value.moduleId().equals(module.moduleId()))
                    .map(io.mindspice.lyra.compiler.ir.IrReference::id)
                    .collect(java.util.stream.Collectors.toSet());
            requireExactIds("module references for " + module.moduleId(),
                    Set.copyOf(state.references()), moduleReferences);
            Set<LambdaId> moduleLambdas = ir.lambdas().stream()
                    .filter(value -> value.moduleId().equals(module.moduleId()))
                    .map(IrLambda::id).collect(java.util.stream.Collectors.toSet());
            requireExactIds("module lambdas for " + module.moduleId(),
                    Set.copyOf(state.lambdas()), moduleLambdas);
            Set<DeclarationId> moduleImports = ir.imports().stream()
                    .filter(value -> ModuleId.fromSourceId(value.importSpan().sourceId())
                            .equals(module.moduleId()))
                    .map(IrImportBinding::declarationId)
                    .collect(java.util.stream.Collectors.toSet());
            requireExactIds("module imports for " + module.moduleId(),
                    Set.copyOf(state.imports()), moduleImports);

            Set<DeclarationId> functionSlots = ir.declarations().stream()
                    .filter(value -> value.moduleId().equals(module.moduleId()) && value.isFunction())
                    .filter(value -> value.kind()
                            != io.mindspice.lyra.compiler.semantic.DeclarationKind.EXTERNAL)
                    .filter(value -> value.kind()
                            != io.mindspice.lyra.compiler.semantic.DeclarationKind.PARAMETER)
                    .filter(value -> value.kind()
                            != io.mindspice.lyra.compiler.semantic.DeclarationKind.PREDICATE_BINDING)
                    .map(IrDeclaration::id).collect(java.util.stream.Collectors.toSet());
            requireExactIds("module function slots for " + module.moduleId(),
                    Set.copyOf(state.functionSlots()), functionSlots);
            Set<DeclarationId> eagerDeclarations = ir.declarations().stream()
                    .filter(value -> value.moduleId().equals(module.moduleId())
                            && value.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.LET
                            && value.initializerLambda().isEmpty())
                    .map(IrDeclaration::id).collect(java.util.stream.Collectors.toSet());
            requireExactIds("module eager declarations for " + module.moduleId(),
                    Set.copyOf(state.eagerDeclarations()), eagerDeclarations);
            Set<DeclarationId> exportedDeclarations = ir.exports().stream()
                    .filter(value -> value.moduleId().equals(module.moduleId()))
                    .map(IrExport::declarationId).collect(java.util.stream.Collectors.toSet());
            requireExactIds("module exported declarations for " + module.moduleId(),
                    Set.copyOf(state.exportedDeclarations()), exportedDeclarations);
            Set<io.mindspice.lyra.compiler.identity.ExportId> functionExports = ir.exports().stream()
                    .filter(value -> value.moduleId().equals(module.moduleId()))
                    .map(IrExport::exportId).flatMap(Optional::stream)
                    .collect(java.util.stream.Collectors.toSet());
            requireExactIds("module function exports for " + module.moduleId(),
                    Set.copyOf(state.exports()), functionExports);
        }

        for (IrImportBinding binding : ir.imports()) {
            IrDeclaration declaration = declarationsById.get(binding.declarationId());
            if (declaration == null || !declaration.imported()
                    || !declaration.moduleId().equals(ModuleId.fromSourceId(binding.importSpan().sourceId()))) {
                throw new IllegalArgumentException("import metadata has no matching declaration: "
                        + binding.declarationId());
            }
        }
        for (IrLambda lambda : ir.lambdas()) {
            for (CaptureId captureId : lambda.captures()) {
                IrCapture capture = capturesById.get(captureId);
                if (capture == null || !capture.lambdaId().equals(lambda.id())
                        || !capture.moduleId().equals(lambda.moduleId())) {
                    throw new IllegalArgumentException("lambda capture metadata has the wrong owner: "
                            + lambda.id() + "/" + captureId);
                }
            }
        }
        for (IrCapture capture : ir.captures()) {
            IrLambda lambda = lambdasById.get(capture.lambdaId());
            IrDeclaration declaration = declarationsById.get(capture.declarationId());
            if (lambda == null || declaration == null
                    || !lambda.moduleId().equals(capture.moduleId())
                    || !declaration.moduleId().equals(capture.moduleId())
                    || !lambda.captures().contains(capture.id())) {
                throw new IllegalArgumentException("capture metadata has an absent or wrong owner: "
                        + capture.id());
            }
            if (capture.isSharedCell() && !cellsByDeclaration.containsKey(
                    capture.sharedCellId().orElseThrow())) {
                throw new IllegalArgumentException("shared capture has no planned cell: " + capture.id());
            }
        }
        for (IrCell cell : ir.cells()) {
            IrDeclaration declaration = declarationsById.get(cell.declarationId());
            if (declaration == null || !declaration.moduleId().equals(cell.moduleId())
                    || !declaration.bindingMutability().isMutable()) {
                throw new IllegalArgumentException("cell metadata has the wrong declaration: "
                        + cell.declarationId());
            }
            Set<CaptureId> expectedCaptures = ir.captures().stream()
                    .filter(capture -> capture.sharedCellId()
                            .filter(cell.declarationId()::equals).isPresent())
                    .map(IrCapture::id).collect(java.util.stream.Collectors.toSet());
            requireExactIds("cell captures for " + cell.declarationId(),
                    Set.copyOf(cell.captures()), expectedCaptures);
            for (CaptureId captureId : cell.captures()) {
                IrCapture capture = capturesById.get(captureId);
                if (capture == null || !capture.isSharedCell()
                        || !capture.sharedCellId().orElseThrow().equals(cell.declarationId())) {
                    throw new IllegalArgumentException("cell metadata has the wrong capture: " + captureId);
                }
            }
        }
        for (IrFunctionLink link : ir.functionLinkage().links()) {
            IrDeclaration from = declarationsById.get(link.from());
            IrDeclaration to = declarationsById.get(link.to());
            var reference = referencesById.get(link.referenceId());
            if (from != null && ir.sessionExecution().filter(value -> !value.emits(from.moduleId())).isPresent()) continue;
            boolean externalTarget = from != null && to != null && ir.externalAccess(from.moduleId(), to.id()).isPresent();
            boolean intrinsicTarget = to != null
                    && to.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.INTRINSIC_EXPORT;
            if (from == null || to == null || reference == null
                    || !from.isFunction() || !to.isFunction()
                    || !lambdaOwners.containsKey(from.id())
                    || (!intrinsicTarget && !externalTarget && !lambdaOwners.containsKey(to.id()))
                    || !reference.moduleId().equals(from.moduleId())) {
                throw new IllegalArgumentException("function linkage has absent metadata: " + link);
            }
        }
        for (IrClosureInitialization initialization : ir.closureInitializations()) {
            IrLambda lambda = lambdasById.get(initialization.lambdaId());
            if (lambda == null || !lambda.moduleId().equals(initialization.moduleId())) {
                throw new IllegalArgumentException("closure initialization has absent lambda metadata: "
                        + initialization.lambdaId());
            }
            requireExactIds("closure initialization captures for " + initialization.lambdaId(),
                    Set.copyOf(initialization.captures()), Set.copyOf(lambda.captures()));
            Set<DeclarationId> expectedCells = lambda.captures().stream()
                    .map(capturesById::get)
                    .filter(Objects::nonNull)
                    .map(IrCapture::sharedCellId)
                    .flatMap(Optional::stream)
                    .collect(java.util.stream.Collectors.toSet());
            requireExactIds("closure initialization cells for " + initialization.lambdaId(),
                    Set.copyOf(initialization.sharedCells()), expectedCells);
        }
    }

    private static void requireExactIds(String role, Set<?> actual, Set<?> expected) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(role + " is incomplete or contains foreign identities"
                    + "; actual=" + actual + "; expected=" + expected);
        }
    }

    private static Inventory inventory(TypedIr ir) {
        TreeMap<String, TupleType> tuples = new TreeMap<>();
        TreeMap<String, FunctionType> functions = new TreeMap<>();
        for (var schema : ir.semanticGraph().resolvedGraph().nominalTypes().schemas()) {
            schema.members().forEach(member -> collectType(member.type(), tuples, functions));
            schema.constructorParameters().forEach(parameter -> collectType(parameter, tuples, functions));
        }
        for (IrDeclaration declaration : ir.declarations()) {
            declaration.contract().ifPresent(contract -> collectType(
                    contract.valueType(), tuples, functions));
        }
        for (var reference : ir.references()) {
            reference.type().ifPresent(type -> collectType(type, tuples, functions));
        }
        for (IrLambda lambda : ir.lambdas()) {
            collectFunction(lambda.signature().asFunctionType(), tuples, functions);
        }
        for (IrCapture capture : ir.captures()) {
            collectType(capture.contract().valueType(), tuples, functions);
        }
        for (IrCell cell : ir.cells()) {
            collectType(cell.contract().valueType(), tuples, functions);
        }
        for (IrExport export : ir.exports()) {
            collectType(export.contract().valueType(), tuples, functions);
            export.functionSignature().ifPresent(signature -> collectFunction(
                    signature.asFunctionType(), tuples, functions));
        }
        for (LyraSignature signature : ir.functionLinkage().signatures().values()) {
            collectFunction(signature.asFunctionType(), tuples, functions);
        }
        for (IrNode node : ir.modules().stream().flatMap(module ->
                io.mindspice.lyra.compiler.ir.IrTraversal.preOrder(module.body()).stream()).toList()) {
            collectType(node.type(), tuples, functions);
        }
        return new Inventory(tuples, functions);
    }

    private static void collectType(
            LyraType type,
            Map<String, TupleType> tuples,
            Map<String, FunctionType> functions) {
        Objects.requireNonNull(type, "type");
        LyraType base = type.withoutQualifiers();
        if (base instanceof ArrayType array) {
            collectType(array.elementType(), tuples, functions);
        } else if (base instanceof TupleType tuple) {
            tuples.putIfAbsent(tuple.canonicalSpelling(), tuple);
            for (LyraType member : tuple.memberTypes()) {
                collectType(member, tuples, functions);
            }
        } else if (base instanceof FunctionType function) {
            collectFunction(function, tuples, functions);
        }
    }

    private static void collectFunction(
            FunctionType function,
            Map<String, TupleType> tuples,
            Map<String, FunctionType> functions) {
        functions.putIfAbsent(function.canonicalSpelling(), function);
        for (LyraType parameter : function.parameterTypes()) {
            collectType(parameter, tuples, functions);
        }
        collectType(function.returnType(), tuples, functions);
    }

    private static JvmExportId exportId(IrExport export) {
        return JvmExportId.from(export);
    }

    private static String exportKey(IrExport export) {
        return moduleKey(export.moduleId()) + "#" + export.name() + "#"
                + export.functionSignature().map(LyraSignature::canonicalSpelling)
                .orElseGet(() -> export.contract().valueType().canonicalSpelling());
    }

    private static String moduleKey(ModuleId module) {
        return (module.isUri() ? "uri:" : "path:") + module.value();
    }

    private static NameAssignment names(
            String basePackage,
            Inventory inventory,
            TypedIr ir) {
        ArrayList<NameRequest> requests = new ArrayList<>();
        for (String canonical : inventory.tuples().keySet()) {
            requests.add(new NameRequest("tuple:" + canonical,
                    basePackage + ".$lyra$tuple$" + JvmStableHash.sha256(
                            "LYRA-JVM-GENERATED-TYPE", "tuple", canonical).substring(0, 16)));
        }
        for (String canonical : inventory.functions().keySet()) {
            requests.add(new NameRequest("function:" + canonical,
                    basePackage + ".$lyra$fn$" + JvmStableHash.sha256(
                            "LYRA-JVM-GENERATED-TYPE", "function", canonical).substring(0, 16)));
        }
        for (IrCell cell : ir.cells().stream().sorted(Comparator.comparing(IrCell::id)).toList()) {
            requests.add(new NameRequest("cell:" + moduleKey(cell.moduleId())
                    + ":" + cell.declarationId().value() + ":"
                    + cell.contract().valueType().canonicalSpelling(),
                    basePackage + ".$lyra$cell$" + JvmStableHash.sha256(
                            "LYRA-JVM-GENERATED-CLASS", "cell", moduleKey(cell.moduleId()),
                            Long.toString(cell.declarationId().value()),
                            cell.contract().valueType().canonicalSpelling()).substring(0, 16)));
        }
        for (IrLambda lambda : ir.lambdas().stream().sorted(Comparator.comparing(IrLambda::id)).toList()) {
            requests.add(new NameRequest("closure:" + moduleKey(lambda.moduleId())
                    + ":" + lambda.id().value() + ":" + lambda.signature().canonicalSpelling(),
                    basePackage + ".$lyra$closure$" + JvmStableHash.sha256(
                            "LYRA-JVM-GENERATED-CLASS", "closure",
                            moduleKey(lambda.moduleId()), Long.toString(lambda.id().value()),
                            lambda.signature().canonicalSpelling()).substring(0, 16)));
        }
        for (IrDeclaration declaration : ir.declarations().stream()
                .filter(value -> value.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.INTRINSIC_EXPORT)
                .sorted(Comparator.comparing(IrDeclaration::id)).toList()) {
            FunctionType function = declaration.contract()
                    .map(BindingContract::valueType)
                    .map(LyraType::withoutQualifiers)
                    .filter(FunctionType.class::isInstance)
                    .map(FunctionType.class::cast)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "intrinsic export is not a function: " + declaration.id()));
            requests.add(new NameRequest("intrinsic-closure:" + moduleKey(declaration.moduleId())
                    + ":" + declaration.id().value() + ":" + function.canonicalSpelling(),
                    basePackage + ".$lyra$closure$" + JvmStableHash.sha256(
                            "LYRA-JVM-GENERATED-CLASS", "intrinsic-closure",
                            moduleKey(declaration.moduleId()), Long.toString(declaration.id().value()),
                            function.canonicalSpelling()).substring(0, 16)));
        }
        for (ModuleId module : ir.modules().stream().map(IrModule::moduleId).sorted().toList()) {
            requests.add(new NameRequest("state:" + moduleKey(module),
                    basePackage + ".$lyra$state$" + JvmStableHash.sha256(
                            "LYRA-JVM-GENERATED-CLASS", "state", moduleKey(module)).substring(0, 16)));
            requests.add(new NameRequest("facade:" + moduleKey(module),
                    basePackage + ".$lyra$facade$" + JvmStableHash.sha256(
                            "LYRA-JVM-GENERATED-CLASS", "facade", moduleKey(module)).substring(0, 16)));
        }
        Map<String, String> assigned = assignNames(requests);
        TreeMap<String, String> tupleNames = new TreeMap<>();
        inventory.tuples().keySet().forEach(key -> tupleNames.put(key, assigned.get("tuple:" + key)));
        TreeMap<String, String> functionNames = new TreeMap<>();
        inventory.functions().keySet().forEach(key -> functionNames.put(key, assigned.get("function:" + key)));
        TreeMap<LambdaId, String> closures = new TreeMap<>();
        for (IrLambda lambda : ir.lambdas()) {
            closures.put(lambda.id(), assigned.get("closure:" + moduleKey(lambda.moduleId())
                    + ":" + lambda.id().value() + ":" + lambda.signature().canonicalSpelling()));
        }
        TreeMap<DeclarationId, String> intrinsicFunctions = new TreeMap<>();
        for (IrDeclaration declaration : ir.declarations().stream()
                .filter(value -> value.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.INTRINSIC_EXPORT)
                .sorted(Comparator.comparing(IrDeclaration::id)).toList()) {
            FunctionType function = declaration.contract()
                    .map(BindingContract::valueType)
                    .map(LyraType::withoutQualifiers)
                    .filter(FunctionType.class::isInstance)
                    .map(FunctionType.class::cast)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "intrinsic export is not a function: " + declaration.id()));
            intrinsicFunctions.put(declaration.id(), assigned.get("intrinsic-closure:"
                    + moduleKey(declaration.moduleId()) + ":" + declaration.id().value() + ":"
                    + function.canonicalSpelling()));
        }
        TreeMap<DeclarationId, String> cells = new TreeMap<>();
        for (IrCell cell : ir.cells()) {
            cells.put(cell.declarationId(), assigned.get("cell:" + moduleKey(cell.moduleId())
                    + ":" + cell.declarationId().value() + ":"
                    + cell.contract().valueType().canonicalSpelling()));
        }
        TreeMap<ModuleId, String> states = new TreeMap<>();
        TreeMap<ModuleId, String> facades = new TreeMap<>();
        for (IrModule module : ir.modules()) {
            states.put(module.moduleId(), assigned.get("state:" + moduleKey(module.moduleId())));
            facades.put(module.moduleId(), assigned.get("facade:" + moduleKey(module.moduleId())));
        }
        var nominalNames = new TreeMap<String, String>();
        var nominalTable = JvmTypeNameTable.forPackage(basePackage);
        for (var schema : ir.semanticGraph().resolvedGraph().nominalTypes().schemas()) {
            String canonical = schema.type().canonicalSpelling();
            nominalNames.put(canonical, nominalTable.nominalBinaryName(canonical));
        }
        return new NameAssignment(tupleNames, functionNames, closures, cells, intrinsicFunctions,
                states, facades, nominalNames);
    }

    private static Map<String, String> assignNames(List<NameRequest> requests) {
        ArrayList<NameRequest> sorted = new ArrayList<>(requests);
        sorted.sort(Comparator.comparing(NameRequest::key));
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        HashSet<String> used = new HashSet<>();
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).key().equals(sorted.get(index).key())) {
                throw new IllegalArgumentException("duplicate generated-name key: " + sorted.get(index).key());
            }
        }
        for (NameRequest request : sorted) {
            String candidate = request.candidate();
            if (used.contains(candidate)) {
                String suffix = JvmStableHash.sha256("LYRA-JVM-NAME-COLLISION", request.key());
                candidate = candidate + "$" + suffix.substring(0, 8);
                if (used.contains(candidate)) {
                    candidate = candidate + "$" + suffix;
                }
                int ordinal = 2;
                String base = candidate;
                while (used.contains(candidate)) {
                    candidate = base + "$" + ordinal++;
                }
            }
            if (!used.add(candidate)) {
                throw new IllegalStateException("generated-name collision resolution failed: " + candidate);
            }
            result.put(request.key(), candidate);
        }
        return Map.copyOf(result);
    }

    private static List<String> findRequiredCycle(
            Map<String, GeneratedClassPlan> byName, Map<String, Integer> indegree) {
        Set<String> remaining = indegree.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> visited = new HashSet<>();
        ArrayList<String> stack = new ArrayList<>();
        Map<String, Integer> stackIndexes = new HashMap<>();
        for (String name : remaining) {
            List<String> cycle = findRequiredCycle(
                    name, byName, remaining, visited, stack, stackIndexes);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        throw new IllegalStateException("blocked generated dependency graph has no cycle");
    }

    private static List<String> findRequiredCycle(
            String name,
            Map<String, GeneratedClassPlan> byName,
            Set<String> remaining,
            Set<String> visited,
            ArrayList<String> stack,
            Map<String, Integer> stackIndexes) {
        Integer existing = stackIndexes.get(name);
        if (existing != null) {
            ArrayList<String> cycle = new ArrayList<>(stack.subList(existing, stack.size()));
            cycle.add(name);
            return List.copyOf(cycle);
        }
        if (!visited.add(name)) {
            return List.of();
        }
        stackIndexes.put(name, stack.size());
        stack.add(name);
        List<String> targets = byName.get(name).dependencies().stream()
                .filter(GeneratedClassDependency::orderingRequired)
                .map(GeneratedClassDependency::targetBinaryName)
                .filter(remaining::contains)
                .distinct().sorted().toList();
        for (String target : targets) {
            List<String> cycle = findRequiredCycle(
                    target, byName, remaining, visited, stack, stackIndexes);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        stack.removeLast();
        stackIndexes.remove(name);
        return List.of();
    }

    private static Comparator<GeneratedClassPlan> classComparator() {
        return Comparator.comparingInt((GeneratedClassPlan value) -> value.kind().orderRank())
                .thenComparing(GeneratedClassPlan::stableKey)
                .thenComparing(GeneratedClassPlan::binaryName);
    }

    private static boolean isNominalTypeRole(
            Map<DeclarationId, IrDeclaration> declarations, DeclarationId declarationId) {
        Set<DeclarationId> visited = new HashSet<>();
        IrDeclaration declaration = declarations.get(declarationId);
        while (declaration != null && visited.add(declaration.id())) {
            if (declaration.kind() == DeclarationKind.NOMINAL) return true;
            declaration = declaration.originDeclaration().map(declarations::get).orElse(null);
        }
        return false;
    }

    private static IllegalArgumentException cycle(List<String> names) {
        return new IllegalArgumentException("generated class dependency cycle: " + names);
    }

    private record Inventory(
            Map<String, TupleType> tuples,
            Map<String, FunctionType> functions) {
        private Inventory {
            tuples = immutableSortedMap(tuples);
            functions = immutableSortedMap(functions);
        }

        private static <T> Map<String, T> immutableSortedMap(Map<String, T> values) {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(values)));
        }
    }

    private record NameRequest(String key, String candidate) {
        private NameRequest {
            if (Objects.requireNonNull(key, "key").isBlank()
                    || Objects.requireNonNull(candidate, "candidate").isBlank()) {
                throw new IllegalArgumentException("generated-name request cannot be blank");
            }
        }
    }

    private record NameAssignment(
            Map<String, String> tupleNames,
            Map<String, String> functionNames,
            Map<LambdaId, String> closureClasses,
            Map<DeclarationId, String> cellClasses,
            Map<DeclarationId, String> intrinsicFunctionClasses,
            Map<ModuleId, String> moduleStates,
            Map<ModuleId, String> moduleFacades,
            Map<String, String> nominalNames) {
        private NameAssignment {
            tupleNames = immutableStringMap(tupleNames);
            functionNames = immutableStringMap(functionNames);
            closureClasses = Map.copyOf(closureClasses);
            cellClasses = Map.copyOf(cellClasses);
            intrinsicFunctionClasses = Map.copyOf(intrinsicFunctionClasses);
            moduleStates = Map.copyOf(moduleStates);
            moduleFacades = Map.copyOf(moduleFacades);
            nominalNames = immutableStringMap(nominalNames);
        }

        private static Map<String, String> immutableStringMap(Map<String, String> values) {
            return Map.copyOf(new TreeMap<>(values));
        }
    }
}
