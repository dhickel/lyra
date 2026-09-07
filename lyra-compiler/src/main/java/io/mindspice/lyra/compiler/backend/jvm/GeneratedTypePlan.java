package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Complete immutable generated-type/class-order plan.  It is a planning
 * artifact only: none of its members contains bytecode or a method body.
 */
final class GeneratedTypePlan {
    private final String basePackage;
    private final JvmTypeNameTable typeNames;
    private final List<GeneratedClassPlan> classes;
    private final Map<String, GeneratedClassPlan> classesByName;
    private final Map<String, String> tupleClasses;
    private final Map<String, String> functionInterfaces;
    private final Map<LambdaId, String> closureClasses;
    private final Map<DeclarationId, String> cellClasses;
    private final Map<DeclarationId, String> intrinsicFunctionClasses;
    private final Map<ModuleId, String> moduleStates;
    private final Map<ModuleId, String> moduleFacades;
    private final List<GeneratedExportPlan> exports;
    private final Map<String, GeneratedExportPlan> exportsById;
    private final JvmJavaNamePlan javaNames;
    private final List<ModuleId> initializationOrder;
    private final Optional<io.mindspice.lyra.compiler.ir.IrSessionExecution> sessionExecution;

    public GeneratedTypePlan(
            String basePackage,
            JvmTypeNameTable typeNames,
            List<GeneratedClassPlan> classes,
            Map<String, String> tupleClasses,
            Map<String, String> functionInterfaces,
            Map<LambdaId, String> closureClasses,
            Map<DeclarationId, String> cellClasses,
            Map<ModuleId, String> moduleStates,
            Map<ModuleId, String> moduleFacades,
            List<GeneratedExportPlan> exports) {
        this(basePackage, typeNames, classes, tupleClasses, functionInterfaces, closureClasses,
                cellClasses, moduleStates, moduleFacades, exports, List.of(), Map.of());
    }

    public GeneratedTypePlan(
            String basePackage,
            JvmTypeNameTable typeNames,
            List<GeneratedClassPlan> classes,
            Map<String, String> tupleClasses,
            Map<String, String> functionInterfaces,
            Map<LambdaId, String> closureClasses,
            Map<DeclarationId, String> cellClasses,
            Map<ModuleId, String> moduleStates,
            Map<ModuleId, String> moduleFacades,
            List<GeneratedExportPlan> exports,
            List<ModuleId> initializationOrder) {
        this(basePackage, typeNames, classes, tupleClasses, functionInterfaces, closureClasses,
                cellClasses, moduleStates, moduleFacades, exports, initializationOrder, Map.of());
    }

    public GeneratedTypePlan(
            String basePackage,
            JvmTypeNameTable typeNames,
            List<GeneratedClassPlan> classes,
            Map<String, String> tupleClasses,
            Map<String, String> functionInterfaces,
            Map<LambdaId, String> closureClasses,
            Map<DeclarationId, String> cellClasses,
            Map<ModuleId, String> moduleStates,
            Map<ModuleId, String> moduleFacades,
            List<GeneratedExportPlan> exports,
            List<ModuleId> initializationOrder,
            Map<DeclarationId, String> intrinsicFunctionClasses) {
        this(basePackage, typeNames, classes, tupleClasses, functionInterfaces, closureClasses,
                cellClasses, moduleStates, moduleFacades, exports, initializationOrder,
                intrinsicFunctionClasses, Optional.empty());
    }

    GeneratedTypePlan(String basePackage, JvmTypeNameTable typeNames, List<GeneratedClassPlan> classes,
            Map<String, String> tupleClasses, Map<String, String> functionInterfaces,
            Map<LambdaId, String> closureClasses, Map<DeclarationId, String> cellClasses,
            Map<ModuleId, String> moduleStates, Map<ModuleId, String> moduleFacades,
            List<GeneratedExportPlan> exports, List<ModuleId> initializationOrder,
            Map<DeclarationId, String> intrinsicFunctionClasses,
            Optional<io.mindspice.lyra.compiler.ir.IrSessionExecution> sessionExecution) {
        this.sessionExecution = Objects.requireNonNull(sessionExecution, "sessionExecution");
        this.basePackage = Objects.requireNonNull(basePackage, "basePackage");
        this.typeNames = Objects.requireNonNull(typeNames, "typeNames");
        if (!this.basePackage.equals(typeNames.basePackage())) {
            throw new IllegalArgumentException("base package disagrees with type-name table");
        }
        this.classes = GeneratedTypePlanner.order(
                Objects.requireNonNull(classes, "classes"));
        LinkedHashMap<String, GeneratedClassPlan> byName = new LinkedHashMap<>();
        java.util.HashSet<String> stableKeys = new java.util.HashSet<>();
        for (GeneratedClassPlan plan : this.classes) {
            Objects.requireNonNull(plan, "classes must not contain null");
            if (!stableKeys.add(plan.stableKey())) {
                throw new IllegalArgumentException("duplicate generated class stable key: "
                        + plan.stableKey());
            }
            if (!plan.binaryName().startsWith(this.basePackage + ".")) {
                throw new IllegalArgumentException("generated class is outside the base package: "
                        + plan.binaryName());
            }
            if (byName.put(plan.binaryName(), plan) != null) {
                throw new IllegalArgumentException("duplicate generated class: " + plan.binaryName());
            }
        }
        this.classesByName = Collections.unmodifiableMap(byName);
        this.tupleClasses = sortedStringMap(tupleClasses, "tupleClasses");
        this.functionInterfaces = sortedStringMap(functionInterfaces, "functionInterfaces");
        this.closureClasses = sortedMap(closureClasses, "closureClasses");
        this.cellClasses = sortedMap(cellClasses, "cellClasses");
        this.intrinsicFunctionClasses = sortedMap(intrinsicFunctionClasses,
                "intrinsicFunctionClasses");
        this.moduleStates = sortedMap(moduleStates, "moduleStates");
        this.moduleFacades = sortedMap(moduleFacades, "moduleFacades");
        if (!this.tupleClasses.equals(this.typeNames.tupleNames())
                || !this.functionInterfaces.equals(this.typeNames.functionNames())) {
            throw new IllegalArgumentException("generated type maps disagree with the name table");
        }
        if (!this.moduleStates.keySet().equals(this.moduleFacades.keySet())) {
            throw new IllegalArgumentException("every module state needs exactly one facade");
        }
        this.exports = sortedExports(exports);
        java.util.HashSet<String> exportKeys = new java.util.HashSet<>();
        for (GeneratedExportPlan export : this.exports) {
            ModuleId exportModule = export.moduleId();
            String key = (exportModule.isUri() ? "uri:" : "path:")
                    + exportModule.value().length() + ":" + exportModule.value()
                    + ":" + export.sourceName();
            if (!exportKeys.add(key)) {
                throw new IllegalArgumentException("duplicate export name in module: " + key);
            }
        }
        LinkedHashMap<String, GeneratedExportPlan> exportIndex = new LinkedHashMap<>();
        for (GeneratedExportPlan export : this.exports) {
            if (exportIndex.put(export.stableId(), export) != null) {
                throw new IllegalArgumentException("duplicate generated export ID: " + export.stableId());
            }
        }
        this.exportsById = Collections.unmodifiableMap(exportIndex);
        LinkedHashMap<JvmExportId, String> exportNames = new LinkedHashMap<>();
        for (GeneratedExportPlan export : this.exports) {
            exportNames.put(export.exportId(), export.javaName());
        }
        this.javaNames = new JvmJavaNamePlan(exportNames);
        JvmJavaNamePlan expectedJavaNames = JvmJavaNamePlan.plan(
                this.exports.stream().map(GeneratedExportPlan::exportId).toList());
        for (GeneratedExportPlan export : this.exports) {
            if (!export.javaName().equals(expectedJavaNames.nameFor(export.exportId()))) {
                throw new IllegalArgumentException(
                        "export Java name is not the deterministic name for its identity");
            }
            if (export.isFunction()
                    && !export.invocationName().orElseThrow().equals(
                    expectedJavaNames.invocationNameFor(export.exportId()))) {
                throw new IllegalArgumentException(
                        "export invocation name is not the deterministic name for its identity");
            }
        }
        this.initializationOrder = validateInitializationOrder(initializationOrder, this.moduleStates.keySet());
        validateClassIndexes();
        validateClassReferences();
        validateFacadeExports();
        validateExportOrigins();
        validateInitializationDependencies();
    }

    public String basePackage() {
        return basePackage;
    }

    public JvmTypeNameTable typeNames() {
        return typeNames;
    }

    /** Classes in the exact deterministic predecessor-before-dependent order. */
    public List<GeneratedClassPlan> classes() {
        return classes;
    }

    public List<GeneratedClassPlan> generationOrder() {
        return classes;
    }

    public List<GeneratedClassPlan> classGenerationOrder() {
        return classes;
    }

    public List<String> classNames() {
        return classes.stream().map(GeneratedClassPlan::binaryName).toList();
    }

    public Optional<GeneratedClassPlan> classPlan(String binaryName) {
        return Optional.ofNullable(classesByName.get(Objects.requireNonNull(binaryName, "binaryName")));
    }

    public Map<String, GeneratedClassPlan> classesByName() {
        return classesByName;
    }

    public Map<String, String> tupleClasses() {
        return tupleClasses;
    }

    public Map<String, String> functionInterfaces() {
        return functionInterfaces;
    }

    public Map<LambdaId, String> closureClasses() {
        return closureClasses;
    }

    public Map<DeclarationId, String> cellClasses() {
        return cellClasses;
    }

    public Map<DeclarationId, String> intrinsicFunctionClasses() {
        return intrinsicFunctionClasses;
    }

    public Map<ModuleId, String> moduleStates() {
        return moduleStates;
    }

    public Map<ModuleId, String> moduleFacades() {
        return moduleFacades;
    }

    public List<GeneratedExportPlan> exports() {
        return exports;
    }

    public JvmJavaNamePlan javaNames() {
        return javaNames;
    }

    public JvmJavaNamePlan javaNamePlan() {
        return javaNames;
    }

    public Optional<GeneratedExportPlan> export(String stableId) {
        return Optional.ofNullable(exportsById.get(Objects.requireNonNull(stableId, "stableId")));
    }

    /** Semantic eager initialization order retained separately from class order. */
    public List<ModuleId> initializationOrder() {
        return initializationOrder;
    }

    public List<ModuleId> moduleInitializationOrder() {
        return initializationOrder;
    }

    public JvmAbiMapper mapper() {
        return new JvmAbiMapper(typeNames);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof GeneratedTypePlan plan
                && basePackage.equals(plan.basePackage)
                && typeNames.equals(plan.typeNames)
                && classes.equals(plan.classes)
                && tupleClasses.equals(plan.tupleClasses)
                && functionInterfaces.equals(plan.functionInterfaces)
                && closureClasses.equals(plan.closureClasses)
                && cellClasses.equals(plan.cellClasses)
                && intrinsicFunctionClasses.equals(plan.intrinsicFunctionClasses)
                && moduleStates.equals(plan.moduleStates)
                && moduleFacades.equals(plan.moduleFacades)
                && exports.equals(plan.exports)
                && javaNames.equals(plan.javaNames)
                && initializationOrder.equals(plan.initializationOrder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(basePackage, typeNames, classes, tupleClasses, functionInterfaces,
                closureClasses, cellClasses, intrinsicFunctionClasses, moduleStates,
                moduleFacades, exports, javaNames, initializationOrder);
    }

    public String canonicalSpelling() {
        return "package=" + basePackage
                + "|tuples=" + tupleClasses
                + "|functions=" + functionInterfaces
                + "|classes=" + classNames()
                + "|intrinsicFunctions=" + intrinsicFunctionClasses
                + "|exports=" + exports
                + "|javaNames=" + javaNames
                + "|initialization=" + initializationOrder;
    }

    public String canonical() {
        return canonicalSpelling();
    }

    @Override
    public String toString() {
        return canonicalSpelling();
    }

    private void validateClassIndexes() {
        requireExactKindIndex(tupleClasses.values(), GeneratedClassKind.TUPLE_VALUE, "tupleClasses");
        requireExactKindIndex(functionInterfaces.values(), GeneratedClassKind.FUNCTION_INTERFACE,
                "functionInterfaces");
        Set<String> allClosureClasses = new java.util.TreeSet<>(closureClasses.values());
        if (allClosureClasses.size() != closureClasses.size()) {
            throw new IllegalArgumentException("closure indexes contain duplicate generated classes");
        }
        int closureIndexSize = allClosureClasses.size();
        allClosureClasses.addAll(intrinsicFunctionClasses.values());
        if (allClosureClasses.size() != closureIndexSize + intrinsicFunctionClasses.size()) {
            throw new IllegalArgumentException("closure indexes contain duplicate generated classes");
        }
        requireExactKindIndex(allClosureClasses, GeneratedClassKind.CLOSURE, "closureClasses");
        requireExactKindIndex(cellClasses.values(), GeneratedClassKind.CELL, "cellClasses");
        requireExactKindIndex(moduleStates.values(), GeneratedClassKind.MODULE_STATE, "moduleStates");
        requireExactKindIndex(moduleFacades.values(), GeneratedClassKind.MODULE_FACADE, "moduleFacades");

        for (Map.Entry<String, String> entry : tupleClasses.entrySet()) {
            GeneratedClassPlan tuple = indexedClass(entry.getValue(), GeneratedClassKind.TUPLE_VALUE,
                    "tuple class");
            requireStableKey(tuple, "tuple:" + entry.getKey(), "tuple class index");
            String tupleCanonical = tuple.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.TUPLE_FIELD)
                    .map(member -> member.valueType().orElseThrow().canonicalLyraType())
                    .collect(java.util.stream.Collectors.joining(",", "Tuple<", ">"));
            if (!tupleCanonical.equals(entry.getKey())) {
                throw new IllegalArgumentException("tuple class fields disagree with their indexed type: "
                        + entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : functionInterfaces.entrySet()) {
            GeneratedClassPlan function = indexedClass(entry.getValue(),
                    GeneratedClassKind.FUNCTION_INTERFACE, "function interface");
            requireStableKey(function, "function:" + entry.getKey(), "function interface index");
            GeneratedMemberPlan invoke = function.members().getFirst();
            if (invoke.signature().isEmpty()
                    || !invoke.signature().orElseThrow().canonicalLyraSignature().equals(entry.getKey())) {
                throw new IllegalArgumentException("function interface method disagrees with its indexed type: "
                        + entry.getKey());
            }
        }
        for (Map.Entry<LambdaId, String> entry : closureClasses.entrySet()) {
            GeneratedClassPlan closure = indexedClass(entry.getValue(), GeneratedClassKind.CLOSURE,
                    "closure class");
            GeneratedMemberPlan invoke = closure.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_INVOKE)
                    .findFirst().orElseThrow();
            if (closure.moduleId().isEmpty() || invoke.signature().isEmpty()
                    || !closure.stableKey().equals("closure:"
                    + moduleKey(closure.moduleId().orElseThrow()) + ":" + entry.getKey().value() + ":"
                    + invoke.signature().orElseThrow().canonicalLyraSignature())) {
                throw new IllegalArgumentException("closure class index does not identify its lambda: "
                        + entry.getKey());
            }
        }
        for (Map.Entry<DeclarationId, String> entry : intrinsicFunctionClasses.entrySet()) {
            GeneratedClassPlan closure = indexedClass(entry.getValue(), GeneratedClassKind.CLOSURE,
                    "intrinsic function class");
            GeneratedMemberPlan invoke = closure.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_INVOKE)
                    .findFirst().orElseThrow();
            if (closure.moduleId().isEmpty() || invoke.signature().isEmpty()
                    || !closure.stableKey().equals("intrinsic-closure:"
                    + moduleKey(closure.moduleId().orElseThrow()) + ":" + entry.getKey().value() + ":"
                    + invoke.signature().orElseThrow().canonicalLyraSignature())) {
                throw new IllegalArgumentException(
                        "intrinsic function class index does not identify its declaration: " + entry.getKey());
            }
        }
        for (Map.Entry<DeclarationId, String> entry : cellClasses.entrySet()) {
            GeneratedClassPlan cell = indexedClass(entry.getValue(), GeneratedClassKind.CELL,
                    "cell class");
            GeneratedMemberPlan value = cell.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.CELL_VALUE_FIELD)
                    .findFirst().orElseThrow();
            if (cell.moduleId().isEmpty() || value.valueType().isEmpty()
                    || !cell.stableKey().equals("cell:"
                    + moduleKey(cell.moduleId().orElseThrow()) + ":" + entry.getKey().value() + ":"
                    + value.valueType().orElseThrow().canonicalLyraType())) {
                throw new IllegalArgumentException("cell class index does not identify its declaration: "
                        + entry.getKey());
            }
        }
        for (Map.Entry<ModuleId, String> entry : moduleStates.entrySet()) {
            GeneratedClassPlan state = indexedClass(entry.getValue(), GeneratedClassKind.MODULE_STATE,
                    "module state class");
            if (state.moduleId().filter(entry.getKey()::equals).isEmpty()
                    || !state.stableKey().equals("state:" + moduleKey(entry.getKey()))) {
                throw new IllegalArgumentException("module state index does not identify its module: "
                        + entry.getKey());
            }
        }
        for (Map.Entry<ModuleId, String> entry : moduleFacades.entrySet()) {
            GeneratedClassPlan facade = indexedClass(entry.getValue(), GeneratedClassKind.MODULE_FACADE,
                    "module facade class");
            if (facade.moduleId().filter(entry.getKey()::equals).isEmpty()
                    || !facade.stableKey().equals("facade:" + moduleKey(entry.getKey()))) {
                throw new IllegalArgumentException("module facade index does not identify its module: "
                        + entry.getKey());
            }
        }
    }

    private GeneratedClassPlan indexedClass(
            String name, GeneratedClassKind kind, String role) {
        GeneratedClassPlan result = classesByName.get(name);
        if (result == null) {
            throw new IllegalArgumentException(role + " is absent from class plan: " + name);
        }
        if (result.kind() != kind) {
            throw new IllegalArgumentException(role + " has the wrong generated kind: " + name);
        }
        return result;
    }

    private static void requireStableKey(
            GeneratedClassPlan plan, String expected, String role) {
        if (!plan.stableKey().equals(expected)) {
            throw new IllegalArgumentException(role + " does not identify its generated class: "
                    + plan.binaryName());
        }
    }

    private void requireExactKindIndex(
            java.util.Collection<String> indexedNames, GeneratedClassKind kind, String role) {
        Set<String> indexed = new java.util.TreeSet<>(indexedNames);
        if (indexed.size() != indexedNames.size()) {
            throw new IllegalArgumentException(role + " contains duplicate generated classes");
        }
        Set<String> planned = classes.stream().filter(value -> value.kind() == kind)
                .map(GeneratedClassPlan::binaryName)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if (!indexed.equals(planned)) {
            throw new IllegalArgumentException(role + " does not exactly index " + kind + " classes");
        }
    }

    private void validateClassReferences() {
        String generatedPrefix = basePackage.replace('.', '/') + "/$lyra$";
        java.util.regex.Pattern descriptorReference = java.util.regex.Pattern.compile("L([^;]+);");
        for (GeneratedClassPlan plan : classes) {
            Set<String> dependencyTargets = plan.dependencies().stream()
                    .map(GeneratedClassDependency::targetBinaryName)
                    .collect(java.util.stream.Collectors.toSet());
            for (GeneratedClassDependency dependency : plan.dependencies()) {
                GeneratedClassPlan target = classesByName.get(dependency.targetBinaryName());
                if (target == null) {
                    throw new IllegalArgumentException("generated dependency targets absent class: "
                            + dependency.targetBinaryName());
                }
                validateDependency(plan, target, dependency);
                if (dependency.orderingRequired()
                        && dependency.targetBinaryName().equals(plan.binaryName())) {
                    throw new IllegalArgumentException("generated class has an ordering self-cycle: "
                            + plan.binaryName());
                }
            }
            for (String implemented : plan.interfaces()) {
                if (implemented.startsWith(basePackage + ".")
                        && !classesByName.containsKey(implemented)) {
                    throw new IllegalArgumentException("generated interface is absent from class plan: "
                            + implemented);
                }
            }
            if (plan.kind() == GeneratedClassKind.CLOSURE) {
                GeneratedClassPlan functionInterface = classesByName.get(plan.interfaces().getFirst());
                if (functionInterface == null
                        || functionInterface.kind() != GeneratedClassKind.FUNCTION_INTERFACE) {
                    throw new IllegalArgumentException("closure does not implement a planned function interface: "
                            + plan.binaryName());
                }
                GeneratedMemberPlan closureInvoke = plan.members().stream()
                        .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_INVOKE)
                        .findFirst().orElseThrow();
                GeneratedMemberPlan interfaceInvoke = functionInterface.members().stream()
                        .filter(member -> member.kind() == GeneratedMemberKind.FUNCTION_INVOKE)
                        .findFirst().orElseThrow();
                if (!closureInvoke.descriptor().equals(interfaceInvoke.descriptor())
                        || closureInvoke.signature().isEmpty()
                        || interfaceInvoke.signature().isEmpty()
                        || !closureInvoke.signature().orElseThrow()
                        .equals(interfaceInvoke.signature().orElseThrow())) {
                    throw new IllegalArgumentException("closure invocation does not match its function interface: "
                            + plan.binaryName());
                }
            }
            for (GeneratedMemberPlan member : plan.members()) {
                java.util.regex.Matcher matcher = descriptorReference.matcher(member.descriptor());
                while (matcher.find()) {
                    String internalName = matcher.group(1);
                    if (internalName.startsWith(generatedPrefix)) {
                        String binaryName = internalName.replace('/', '.');
                        if (!classesByName.containsKey(binaryName)) {
                            throw new IllegalArgumentException(
                                    "generated descriptor target is absent from class plan: " + binaryName);
                        }
                    }
                }
            }
            for (GeneratedClassPlan target : classes) {
                if (target.binaryName().equals(plan.binaryName())) {
                    continue;
                }
                String descriptorName = "L" + target.internalName() + ";";
                boolean referenced = plan.interfaces().contains(target.binaryName())
                        || plan.members().stream().anyMatch(member ->
                        member.descriptor().contains(descriptorName));
                if (referenced && !dependencyTargets.contains(target.binaryName())) {
                    throw new IllegalArgumentException("generated class reference lacks an explicit dependency: "
                            + plan.binaryName() + " -> " + target.binaryName());
                }
            }
        }
        for (Map.Entry<String, String> entry : tupleClasses.entrySet()) {
            requireKind(entry.getValue(), GeneratedClassKind.TUPLE_VALUE, "tuple class");
        }
        for (Map.Entry<String, String> entry : functionInterfaces.entrySet()) {
            requireKind(entry.getValue(), GeneratedClassKind.FUNCTION_INTERFACE, "function interface");
        }
        for (String name : closureClasses.values()) {
            requireClass(name, "closure class");
        }
        for (String name : cellClasses.values()) {
            requireClass(name, "cell class");
        }
        for (String name : moduleStates.values()) {
            requireClass(name, "module state class");
        }
        for (Map.Entry<ModuleId, String> entry : moduleStates.entrySet()) {
            requireKind(entry.getValue(), GeneratedClassKind.MODULE_STATE, "module state class");
        }
        for (Map.Entry<ModuleId, String> entry : moduleFacades.entrySet()) {
            requireKind(entry.getValue(), GeneratedClassKind.MODULE_FACADE, "module facade class");
        }
        for (GeneratedExportPlan export : exports) {
            if (!moduleFacades.containsKey(export.moduleId())) {
                throw new IllegalArgumentException("export belongs to an unplanned module: " + export);
            }
        }
        for (Map.Entry<LambdaId, String> entry : closureClasses.entrySet()) {
            requireKind(entry.getValue(), GeneratedClassKind.CLOSURE, "closure class");
        }
        for (Map.Entry<DeclarationId, String> entry : cellClasses.entrySet()) {
            requireKind(entry.getValue(), GeneratedClassKind.CELL, "cell class");
        }
        for (Map.Entry<DeclarationId, String> entry : intrinsicFunctionClasses.entrySet()) {
            requireKind(entry.getValue(), GeneratedClassKind.CLOSURE,
                    "intrinsic function class");
        }
    }

    private static void validateDependency(
            GeneratedClassPlan source,
            GeneratedClassPlan target,
            GeneratedClassDependency dependency) {
        boolean targetKindMatches = switch (dependency.kind()) {
            case TUPLE_MEMBER_TYPE, FUNCTION_SIGNATURE_TYPE, CELL_VALUE_TYPE,
                    CLOSURE_CAPTURE_TYPE, FACADE_EXPORT_TYPE ->
                    target.kind() == GeneratedClassKind.TUPLE_VALUE
                            || target.kind() == GeneratedClassKind.FUNCTION_INTERFACE;
            case MODULE_STATE_FIELD_TYPE -> target.kind() == GeneratedClassKind.TUPLE_VALUE
                    || target.kind() == GeneratedClassKind.FUNCTION_INTERFACE
                    || target.kind() == GeneratedClassKind.CELL;
            case CLOSURE_FUNCTION_INTERFACE -> target.kind() == GeneratedClassKind.FUNCTION_INTERFACE;
            case CLOSURE_MODULE_STATE -> target.kind() == GeneratedClassKind.MODULE_STATE;
            case CLOSURE_SHARED_CELL -> target.kind() == GeneratedClassKind.CELL;
            case MODULE_IMPORT_LINKAGE, MODULE_INITIALIZATION, FACADE_STATE ->
                    target.kind() == GeneratedClassKind.MODULE_STATE;
            case RECURSIVE_FUNCTION_LINKAGE -> target.kind() == GeneratedClassKind.CLOSURE;
        };
        if (!targetKindMatches) {
            throw new IllegalArgumentException("generated dependency has an incompatible target kind: "
                    + source.binaryName() + " -> " + target.binaryName());
        }
        boolean sourceKindMatches = switch (dependency.kind()) {
            case TUPLE_MEMBER_TYPE -> source.kind() == GeneratedClassKind.TUPLE_VALUE;
            case FUNCTION_SIGNATURE_TYPE -> source.kind() == GeneratedClassKind.FUNCTION_INTERFACE
                    || source.kind() == GeneratedClassKind.CLOSURE;
            case CELL_VALUE_TYPE -> source.kind() == GeneratedClassKind.CELL;
            case CLOSURE_FUNCTION_INTERFACE, CLOSURE_MODULE_STATE, CLOSURE_CAPTURE_TYPE,
                    CLOSURE_SHARED_CELL, RECURSIVE_FUNCTION_LINKAGE ->
                    source.kind() == GeneratedClassKind.CLOSURE;
            case MODULE_IMPORT_LINKAGE, MODULE_INITIALIZATION, MODULE_STATE_FIELD_TYPE ->
                    source.kind() == GeneratedClassKind.MODULE_STATE;
            case FACADE_STATE, FACADE_EXPORT_TYPE ->
                    source.kind() == GeneratedClassKind.MODULE_FACADE;
        };
        if (!sourceKindMatches) {
            throw new IllegalArgumentException("generated dependency has an incompatible source kind: "
                    + source.binaryName() + " -> " + target.binaryName());
        }
        if (source.binaryName().equals(target.binaryName())
                && dependency.kind() != GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE) {
            throw new IllegalArgumentException(
                    "only recursive function linkage may target its own generated class");
        }
        if (dependency.kind() == GeneratedDependencyKind.CLOSURE_MODULE_STATE
                && (!source.moduleId().equals(target.moduleId())
                || source.kind() != GeneratedClassKind.CLOSURE)) {
            throw new IllegalArgumentException(
                    "closure module-state dependency must target its owning module");
        }
        if (dependency.kind() == GeneratedDependencyKind.CLOSURE_SHARED_CELL
                && !source.moduleId().equals(target.moduleId())) {
            throw new IllegalArgumentException(
                    "shared closure cells must belong to the closure's owning module");
        }
        if (dependency.kind() == GeneratedDependencyKind.MODULE_STATE_FIELD_TYPE
                && target.kind() == GeneratedClassKind.CELL
                && !source.moduleId().equals(target.moduleId())) {
            throw new IllegalArgumentException(
                    "module state cells must belong to their owning module");
        }
        if (dependency.kind() == GeneratedDependencyKind.FACADE_STATE
                && !source.moduleId().equals(target.moduleId())) {
            throw new IllegalArgumentException(
                    "facade state linkage must target its owning module state");
        }
        if (dependency.kind() == GeneratedDependencyKind.MODULE_IMPORT_LINKAGE
                && source.moduleId().equals(target.moduleId())) {
            throw new IllegalArgumentException(
                    "module import linkage cannot target its own module state");
        }
        boolean linkageOnly = dependency.kind() == GeneratedDependencyKind.MODULE_IMPORT_LINKAGE
                || dependency.kind() == GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE;
        if (dependency.orderingRequired() == linkageOnly) {
            throw new IllegalArgumentException("generated linkage dependency has invalid ordering policy: "
                    + dependency.kind());
        }
    }

    private void validateFacadeExports() {
        for (Map.Entry<ModuleId, String> entry : moduleFacades.entrySet()) {
            GeneratedClassPlan facade = classesByName.get(entry.getValue());
            List<GeneratedMemberPlan> expected = exports.stream()
                    .filter(export -> export.moduleId().equals(entry.getKey()))
                    .sorted(Comparator.comparing(GeneratedExportPlan::exportId))
                    .flatMap(export -> export.members().stream())
                    .toList();
            List<GeneratedMemberPlan> actual = facade.members().stream()
                    .filter(member -> member.kind().isExportMember())
                    .toList();
            if (!actual.equals(expected)) {
                throw new IllegalArgumentException(
                        "facade export members do not exactly match export plans: " + entry.getKey());
            }
        }
    }

    private void validateInitializationDependencies() {
        Map<ModuleId, Integer> positions = new TreeMap<>();
        for (int index = 0; index < initializationOrder.size(); index++) {
            positions.put(initializationOrder.get(index), index);
        }
        for (Map.Entry<ModuleId, String> entry : moduleStates.entrySet()) {
            GeneratedClassPlan state = classesByName.get(entry.getValue());
            int sourcePosition = positions.get(entry.getKey());
            for (GeneratedClassDependency dependency : state.dependencies()) {
                if (dependency.kind() != GeneratedDependencyKind.MODULE_INITIALIZATION) {
                    continue;
                }
                GeneratedClassPlan target = classesByName.get(dependency.targetBinaryName());
                ModuleId targetModule = target.moduleId().orElseThrow(() ->
                        new IllegalArgumentException(
                                "module-initialization dependency targets a non-module class"));
                Integer targetPosition = positions.get(targetModule);
                if (targetPosition == null || targetPosition >= sourcePosition) {
                    throw new IllegalArgumentException(
                            "module initialization order does not precede its eager dependency: "
                                    + entry.getKey() + " -> " + targetModule);
                }
            }
        }
    }

    private void validateExportOrigins() {
        Set<JvmExportId> plannedExports = exports.stream().map(GeneratedExportPlan::exportId)
                .collect(java.util.stream.Collectors.toSet());
        for (GeneratedExportPlan export : exports) {
            if (!export.reExport()) {
                continue;
            }
            var retained = sessionExecution.flatMap(execution -> execution.access(export.moduleId(), export.originDeclaration()));
            if (retained.isPresent()) {
                var target = retained.orElseThrow().target();
                var contract = target.declaration().contract().orElseThrow();
                var originId = JvmExportId.from(target.origin().moduleId(), target.declaration().name(),
                        contract.valueType().canonicalSpelling(), target.export().originExport());
                if (!originId.equals(export.originExportId())
                        || !contract.valueType().canonicalSpelling().equals(export.valueType().canonicalLyraType())
                        || !contract.mutability().equals(export.bindingMutability())) {
                    throw new IllegalArgumentException("re-export differs from its retained producer contract");
                }
                continue;
            }
            if (!moduleStates.containsKey(export.originModule())
                    || !plannedExports.contains(export.originExportId())) {
                throw new IllegalArgumentException(
                        "re-export origin is absent from the generated plan: " + export.originExportId());
            }
            GeneratedExportPlan origin = exports.stream()
                    .filter(candidate -> candidate.exportId().equals(export.originExportId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "re-export origin is absent from the generated plan: "
                                    + export.originExportId()));
            if (!origin.moduleId().equals(export.originModule())
                    || !origin.sourceName().equals(export.originName())
                    || !origin.declarationId().equals(export.originDeclaration())
                    || !origin.valueType().canonicalLyraType().equals(
                    export.valueType().canonicalLyraType())
                    || !origin.bindingMutability().equals(export.bindingMutability())
                    || origin.isFunction() != export.isFunction()) {
                throw new IllegalArgumentException(
                        "re-export origin metadata does not match its planned origin: "
                                + export.originExportId());
            }
        }
    }

    private void requireKind(String name, GeneratedClassKind expected, String role) {
        requireClass(name, role);
        if (classesByName.get(name).kind() != expected) {
            throw new IllegalArgumentException(role + " has the wrong generated kind: " + name);
        }
    }

    private void requireClass(String name, String role) {
        if (!classesByName.containsKey(name)) {
            throw new IllegalArgumentException(role + " is absent from class plan: " + name);
        }
    }

    private static String moduleKey(ModuleId module) {
        return (module.isUri() ? "uri:" : "path:") + module.value();
    }

    private static List<ModuleId> validateInitializationOrder(
            List<ModuleId> values, java.util.Set<ModuleId> modules) {
        Objects.requireNonNull(values, "initializationOrder");
        ArrayList<ModuleId> copy = new ArrayList<>();
        for (ModuleId value : values) {
            copy.add(Objects.requireNonNull(value, "initializationOrder must not contain null"));
        }
        if (!new java.util.LinkedHashSet<>(copy).equals(
                new java.util.LinkedHashSet<>(modules)) || copy.size() != modules.size()) {
            throw new IllegalArgumentException("initialization order must cover every planned module");
        }
        return List.copyOf(copy);
    }

    private static List<GeneratedExportPlan> sortedExports(List<GeneratedExportPlan> values) {
        Objects.requireNonNull(values, "exports");
        ArrayList<GeneratedExportPlan> copy = new ArrayList<>();
        for (GeneratedExportPlan value : values) {
            copy.add(Objects.requireNonNull(value, "exports must not contain null"));
        }
        copy.sort(Comparator.comparing(GeneratedExportPlan::exportId));
        return List.copyOf(copy);
    }

    private static Map<String, String> sortedStringMap(Map<String, String> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            String key = Objects.requireNonNull(entry.getKey(), name + " key");
            String value = Objects.requireNonNull(entry.getValue(), name + " value");
            if (copy.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate " + name + " key: " + key);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static <K extends Comparable<? super K>> Map<K, String> sortedMap(
            Map<K, String> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<Map.Entry<K, String>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<K, String> copy = new LinkedHashMap<>();
        for (Map.Entry<K, String> entry : entries) {
            K key = Objects.requireNonNull(entry.getKey(), name + " key");
            String value = Objects.requireNonNull(entry.getValue(), name + " value");
            if (copy.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate " + name + " key: " + key);
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
