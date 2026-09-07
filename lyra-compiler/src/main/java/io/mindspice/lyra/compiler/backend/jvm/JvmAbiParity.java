package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.ir.IrCapture;
import io.mindspice.lyra.compiler.ir.IrCell;
import io.mindspice.lyra.compiler.ir.IrDeclaration;
import io.mindspice.lyra.compiler.ir.IrExport;
import io.mindspice.lyra.compiler.ir.IrFunctionLink;
import io.mindspice.lyra.compiler.ir.IrImportBinding;
import io.mindspice.lyra.compiler.ir.IrLambda;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only pre-emission audit.  It compares mapper output with the immutable
 * typed-IR contracts and generated member plans; it never rewrites either.
 */
final class JvmAbiParity {
    private JvmAbiParity() {
    }

    public static JvmIrParity compare(TypedIr ir, GeneratedTypePlan plan) {
        Objects.requireNonNull(ir, "ir").requireValidated();
        Objects.requireNonNull(plan, "plan");
        JvmAbiMapper mapper = plan.mapper();
        ArrayList<String> differences = new ArrayList<>();

        for (IrExport export : ir.exports()) {
            if (ir.module(export.moduleId()).isEmpty()) continue;
            JvmExportId expectedId = JvmExportId.from(export);
            Optional<GeneratedExportPlan> generated = plan.exports().stream()
                    .filter(value -> value.exportId().equals(expectedId))
                    .findFirst();
            if (generated.isEmpty()) {
                differences.add("missing generated export: " + expectedId);
                continue;
            }
            GeneratedExportPlan value = generated.orElseThrow();
            if (!value.moduleId().equals(export.moduleId())
                    || !value.sourceName().equals(export.name())
                    || !value.declarationId().equals(export.declarationId())
                    || value.reExport() != export.reExport()
                    || !value.originModule().equals(export.originModule())
                    || !value.originName().equals(export.originName())
                    || !value.originDeclaration().equals(export.originDeclaration())
                    || !value.originExportId().semanticExportId().equals(export.originExport())
                    || !value.bindingMutability().equals(export.contract().mutability())
                    || !value.semanticExportId().equals(export.exportId())) {
                differences.add("export identity/linkage differs: " + export.name());
            }
            JvmTypePlan expectedValue = mapper.map(export.contract().valueType(),
                    JvmMappingContext.EXPORTED_VALUE);
            if (!value.valueType().equals(expectedValue)) {
                differences.add("export value mapping differs: " + export.name());
            }
            try {
                String expectedJavaName = plan.javaNames().nameFor(expectedId);
                if (!value.javaName().equals(expectedJavaName)) {
                    differences.add("export Java name differs: " + export.name());
                }
                Optional<String> expectedInvocation = export.functionSignature().isPresent()
                        ? Optional.of(plan.javaNames().invocationNameFor(expectedId)) : Optional.empty();
                Optional<String> expectedGetter = export.functionSignature().isPresent()
                        ? Optional.empty() : Optional.of("get$" + expectedJavaName);
                Optional<String> expectedFunctionGetter = export.functionSignature().isPresent()
                        ? Optional.of("value$" + expectedJavaName) : Optional.empty();
                Optional<String> expectedSetter = export.isMutable()
                        ? Optional.of("set$" + expectedJavaName) : Optional.empty();
                if (!value.invocationName().equals(expectedInvocation)
                        || !value.getterName().equals(expectedGetter)
                        || !value.functionValueName().equals(expectedFunctionGetter)
                        || !value.setterName().equals(expectedSetter)) {
                    differences.add("export Java member names differ: " + export.name());
                }
            } catch (RuntimeException failure) {
                differences.add("export Java name is absent: " + export.name());
            }
            if (export.functionSignature().isPresent()) {
                JvmSignaturePlan expectedSignature = mapper.mapSignature(
                        export.functionSignature().orElseThrow(), JvmAbiBoundary.JAVA_VISIBLE);
                JvmTypePlan expectedFunctionValue = mapper.map(export.contract().valueType(),
                        JvmMappingContext.JAVA_FUNCTION_VALUE);
                if (value.functionSignature().isEmpty()
                        || !value.functionSignature().orElseThrow().equals(expectedSignature)) {
                    differences.add("export function mapping differs: " + export.name());
                }
                boolean invocationMatches = value.invocation().map(member ->
                        member.descriptor().equals(expectedSignature.descriptor())
                                && member.signature().filter(expectedSignature::equals).isPresent())
                        .orElse(false);
                if (!invocationMatches) {
                    differences.add("export invocation descriptor/signature differs: " + export.name());
                }
                boolean functionGetterMatches = value.functionValueGetter().map(member ->
                        member.descriptor().equals("()" + expectedFunctionValue.descriptor())
                                && member.valueType().filter(expectedFunctionValue::equals).isPresent())
                        .orElse(false);
                if (!functionGetterMatches) {
                    differences.add("export function getter descriptor/type differs: " + export.name());
                }
            } else {
                boolean getterMatches = value.getter().map(member ->
                        member.descriptor().equals("()" + expectedValue.descriptor())
                                && member.valueType().filter(expectedValue::equals).isPresent())
                        .orElse(false);
                if (!getterMatches) {
                    differences.add("export getter descriptor/type differs: " + export.name());
                }
            }
            if (export.isMutable()) {
                JvmTypePlan expectedSetterValue = export.functionSignature().isPresent()
                        ? mapper.map(export.contract().valueType(), JvmMappingContext.JAVA_FUNCTION_VALUE)
                        : expectedValue;
                boolean setterMatches = value.setter().map(member ->
                        member.descriptor().equals("(" + expectedSetterValue.descriptor() + ")V")
                                && member.valueType().filter(expectedSetterValue::equals).isPresent())
                        .orElse(false);
                if (!setterMatches) {
                    differences.add("export setter descriptor/type differs: " + export.name());
                }
            }
        }
        for (GeneratedExportPlan generated : plan.exports()) {
            if (ir.exports().stream().map(JvmExportId::from)
                    .noneMatch(generated.exportId()::equals)) {
                differences.add("unexpected generated export: " + generated.exportId());
            }
        }
        if (!plan.initializationOrder().equals(ir.initializationOrder())) {
            differences.add("module initialization order differs from typed IR");
        }
        compareClassInventory(ir, plan, mapper, differences);

        for (IrLambda lambda : ir.lambdas()) {
            String interfaceName = plan.functionInterfaces().get(lambda.signature().canonicalSpelling());
            JvmSignaturePlan expectedPlan = mapper.mapSignature(
                    lambda.signature(), JvmAbiBoundary.JAVA_VISIBLE);
            boolean interfaceMatches = interfaceName != null
                    && plan.classPlan(interfaceName).map(classPlan ->
                    classPlan.kind() == GeneratedClassKind.FUNCTION_INTERFACE
                            && classPlan.functionalInterface()
                            && classPlan.members().stream()
                            .filter(member -> member.kind() == GeneratedMemberKind.FUNCTION_INVOKE)
                            .anyMatch(member -> member.signature().filter(expectedPlan::equals).isPresent()
                                    && member.descriptor().equals(expectedPlan.descriptor())))
                    .orElse(false);
            if (!interfaceMatches) {
                differences.add("function interface descriptor/signature differs: " + lambda.id());
            }
            String className = plan.closureClasses().get(lambda.id());
            if (className == null) {
                differences.add("missing closure class: " + lambda.id());
                continue;
            }
            GeneratedClassPlan closure = plan.classPlan(className).orElse(null);
            if (closure == null) {
                differences.add("closure class is not in class plan: " + className);
                continue;
            }
            String expectedInterface = plan.functionInterfaces()
                    .get(lambda.signature().canonicalSpelling());
            if (closure.kind() != GeneratedClassKind.CLOSURE
                    || !closure.interfaces().contains(expectedInterface)) {
                differences.add("closure functional-interface linkage differs: " + lambda.id());
            }
            closure.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_INVOKE)
                    .findFirst()
                    .ifPresentOrElse(member -> {
                        if (!member.descriptor().equals(expectedPlan.descriptor())
                                || member.signature().isEmpty()
                                || !member.signature().orElseThrow().equals(expectedPlan)) {
                            differences.add("closure invocation descriptor/signature differs: " + lambda.id());
                        }
                    }, () -> differences.add("closure has no invocation member: " + lambda.id()));
        }

        // Every IR type must be mappable in its explicit internal value
        // context.  This catches a newly introduced semantic type before a
        // body emitter can observe it.
        for (var node : io.mindspice.lyra.compiler.ir.IrTraversal.preOrder(ir)) {
            try {
                mapper.map(node.type(), JvmMappingContext.INTERNAL_VALUE);
            } catch (RuntimeException failure) {
                differences.add("unmappable IR type at " + node.span() + ": " + failure.getMessage());
            }
        }
        return new JvmIrParity(differences.isEmpty(), differences);
    }

    private static void compareClassInventory(
            TypedIr ir,
            GeneratedTypePlan plan,
            JvmAbiMapper mapper,
            List<String> differences) {
        Set<ModuleId> modules = ir.modules().stream().map(value -> value.moduleId())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (!plan.moduleStates().keySet().equals(modules)
                || !plan.moduleFacades().keySet().equals(modules)) {
            differences.add("module state/facade class inventory differs from typed IR");
        }
        Set<io.mindspice.lyra.compiler.identity.LambdaId> lambdas = ir.lambdas().stream()
                .map(IrLambda::id).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (!plan.closureClasses().keySet().equals(lambdas)) {
            differences.add("closure class inventory differs from typed IR");
        }
        Set<DeclarationId> cells = ir.cells().stream().map(value -> value.declarationId())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (!plan.cellClasses().keySet().equals(cells)) {
            differences.add("cell class inventory differs from typed IR");
        }

        TreeSet<String> tuples = new TreeSet<>();
        TreeSet<String> functions = new TreeSet<>();
        ir.declarations().forEach(value -> value.contract().ifPresent(contract ->
                collectTypeFamilies(contract.valueType(), tuples, functions)));
        ir.references().forEach(value -> value.type().ifPresent(type ->
                collectTypeFamilies(type, tuples, functions)));
        ir.lambdas().forEach(value -> collectTypeFamilies(
                value.signature().asFunctionType(), tuples, functions));
        ir.captures().forEach(value -> collectTypeFamilies(
                value.contract().valueType(), tuples, functions));
        ir.cells().forEach(value -> collectTypeFamilies(
                value.contract().valueType(), tuples, functions));
        ir.exports().forEach(value -> collectTypeFamilies(
                value.contract().valueType(), tuples, functions));
        ir.functionLinkage().signatures().values().forEach(value -> collectTypeFamilies(
                value.asFunctionType(), tuples, functions));
        io.mindspice.lyra.compiler.ir.IrTraversal.preOrder(ir).forEach(value ->
                collectTypeFamilies(value.type(), tuples, functions));
        if (!plan.tupleClasses().keySet().equals(tuples)) {
            differences.add("tuple class inventory differs from typed IR");
        }
        if (!plan.functionInterfaces().keySet().equals(functions)) {
            differences.add("function-interface inventory differs from typed IR");
        }

        Map<DeclarationId, IrDeclaration> declarations = ir.declarations().stream()
                .collect(java.util.stream.Collectors.toMap(IrDeclaration::id, value -> value));
        Map<DeclarationId, IrImportBinding> imports = ir.imports().stream()
                .collect(java.util.stream.Collectors.toMap(
                        IrImportBinding::declarationId, value -> value));
        compareDependencyInventory(ir, plan, differences, declarations, imports);
        compareCellLayouts(ir, plan, mapper, differences);
        compareClosureLayouts(ir, plan, mapper, differences);
        for (var module : ir.modules()) {
            GeneratedClassPlan state = Optional.ofNullable(plan.moduleStates().get(module.moduleId()))
                    .flatMap(plan::classPlan).orElse(null);
            if (state == null) {
                continue;
            }
            boolean lifecycleShape = state.members().stream().anyMatch(member ->
                    member.kind() == GeneratedMemberKind.STATE_LIFECYCLE_FIELD
                            && member.name().equals("$lyra$lifecycle")
                            && member.descriptor().equals(
                            "Lio/mindspice/lyra/runtime/ModuleLifecycle;"))
                    && state.members().stream().anyMatch(member ->
                    member.kind() == GeneratedMemberKind.STATE_AUTHORITY_GET
                            && member.name().equals("$lyra$closureAuthority")
                            && member.descriptor().equals(
                            "()Lio/mindspice/lyra/runtime/LyraClosureAuthority;"))
                    && state.members().stream().anyMatch(member ->
                    member.kind() == GeneratedMemberKind.STATE_CHECK_OPEN
                            && member.name().equals("$lyra$checkOpen")
                            && member.descriptor().equals("()V"))
                    && state.members().stream().anyMatch(member ->
                    member.kind() == GeneratedMemberKind.STATE_CLOSE
                            && member.name().equals("$lyra$close")
                            && member.descriptor().equals("()V"));
            if (!lifecycleShape) {
                differences.add("module-state lifecycle composition differs: " + module.moduleId());
            }
            var resultFields = state.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.SESSION_RESULT_FIELD).toList();
            var facade = plan.classPlan(plan.moduleFacades().get(module.moduleId())).orElseThrow();
            var resultGetters = facade.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.FACADE_SESSION_RESULT_GET).toList();
            var executions = facade.members().stream()
                    .filter(value -> value.kind() == GeneratedMemberKind.FACADE_SESSION_EXECUTE).toList();
            if (module.submissionResult().isPresent()) {
                String descriptor = mapper.map(module.submissionResult().orElseThrow().type(),
                        JvmMappingContext.JAVA_VALUE).descriptor();
                if (resultFields.size() != 1 || !resultFields.getFirst().descriptor().equals(descriptor)
                        || resultGetters.size() != 1 || !resultGetters.getFirst().descriptor().equals("()" + descriptor)
                        || !resultGetters.getFirst().name().equals("$lyra$sessionResult")
                        || executions.size() != 1 || !executions.getFirst().name().equals("$lyra$sessionRun")
                        || !executions.getFirst().descriptor().equals("()V")) {
                    differences.add("submission result boundary differs: " + module.moduleId());
                }
            } else if (!resultFields.isEmpty() || !resultGetters.isEmpty() || !executions.isEmpty()) {
                differences.add("ordinary module contains a submission result boundary: " + module.moduleId());
            }
            boolean externalImports = ir.sessionExecution().stream()
                    .flatMap(value -> value.externalAccesses().stream())
                    .anyMatch(value -> value.consumer().equals(module.moduleId()));
            if (externalImports && state.members().stream().noneMatch(member ->
                    member.kind() == GeneratedMemberKind.STATE_SESSION_ACCESSOR
                            && member.descriptor().equals("(JJLjava/lang/String;Z)Ljava/lang/invoke/MethodHandle;"))) {
                differences.add("external import accessor is absent: " + module.moduleId());
            }
            TreeMap<DeclarationId, List<GeneratedMemberPlan>> fields = new TreeMap<>();
            for (GeneratedMemberPlan member : state.members().stream()
                    .filter(GeneratedMemberPlan::isField)
                    .filter(member -> member.kind() != GeneratedMemberKind.STATE_LIFECYCLE_FIELD
                            && member.kind() != GeneratedMemberKind.SESSION_RESULT_FIELD)
                    .toList()) {
                Optional<DeclarationId> id = stateFieldDeclaration(member.name());
                if (id.isEmpty()) {
                    differences.add("module state has an unidentifiable field: " + member.name());
                    continue;
                }
                fields.computeIfAbsent(id.orElseThrow(), ignored -> new ArrayList<>()).add(member);
            }
            Set<DeclarationId> expectedIds = module.state().declarations().stream()
                    .map(declarations::get).filter(Objects::nonNull)
                    .filter(value -> value.scopeId().equals(module.state().rootScope()))
                    .filter(value -> value.externalBinding().isEmpty())
                    .filter(value -> !imports.containsKey(value.id()) || ir.sessionExecution()
                            .filter(execution -> !execution.emits(imports.get(value.id()).targetModule())).isEmpty())
                    .filter(value -> value.contract().isPresent() || imports.containsKey(value.id()))
                    .map(IrDeclaration::id)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (!fields.keySet().equals(expectedIds)) {
                differences.add("module-state storage identities differ: " + module.moduleId());
            }
            for (DeclarationId id : expectedIds) {
                IrDeclaration declaration = declarations.get(id);
                List<GeneratedMemberPlan> stored = fields.getOrDefault(id, List.of());
                IrImportBinding imported = imports.get(id);
                if (imported != null) {
                    String target = plan.moduleStates().get(imported.targetModule());
                    String descriptor = target == null ? "" : "L" + target.replace('.', '/') + ";";
                    boolean hasLink = state.members().stream().anyMatch(member ->
                            member.kind() == GeneratedMemberKind.STATE_IMPORT_LINK
                                    && member.name().equals("$lyra$link$binding$" + id.value())
                                    && member.descriptor().equals("(" + descriptor + ")V"));
                    if (stored.size() != 1
                            || stored.getFirst().kind() != GeneratedMemberKind.STATE_IMPORT_FIELD
                            || !stored.getFirst().descriptor().equals(descriptor)
                            || stored.getFirst().isFinal() || !hasLink) {
                        differences.add("import state linkage differs: " + id);
                    }
                    continue;
                }
                String cellName = plan.cellClasses().get(id);
                if (cellName != null) {
                    String descriptor = "L" + cellName.replace('.', '/') + ";";
                    if (stored.size() != 1
                            || stored.getFirst().kind() != GeneratedMemberKind.STATE_CELL_FIELD
                            || !stored.getFirst().descriptor().equals(descriptor)) {
                        differences.add("module shared-cell storage differs: " + id);
                    }
                    continue;
                }
                JvmBindingPlan binding = mapper.mapBinding(declaration.contract().orElseThrow());
                List<String> actualDescriptors = stored.stream()
                        .sorted(java.util.Comparator.comparingInt(GeneratedMemberPlan::componentIndex))
                        .map(GeneratedMemberPlan::descriptor).toList();
                if (!actualDescriptors.equals(binding.value().componentDescriptors())
                        || stored.stream().anyMatch(member -> member.valueType()
                        .filter(binding.value()::equals).isEmpty())) {
                    differences.add("module binding storage differs: " + id);
                }
                boolean hasSetter = state.members().stream().anyMatch(member ->
                        member.kind() == GeneratedMemberKind.STATE_COMPONENT_SET
                                && member.name().equals("$lyra$set$binding$" + id.value()));
                if (hasSetter != binding.isMutable()) {
                    differences.add("module binding mutability differs: " + id);
                }
            }
        }
    }

    private static void compareDependencyInventory(
            TypedIr ir,
            GeneratedTypePlan plan,
            List<String> differences,
            Map<DeclarationId, IrDeclaration> declarations,
            Map<DeclarationId, IrImportBinding> imports) {
        Map<String, Set<DependencyIdentity>> expected = new HashMap<>();
        Map<String, TupleType> tuples = new TreeMap<>();
        Map<String, FunctionType> functions = new TreeMap<>();
        collectTypeDefinitions(ir, tuples, functions);

        for (Map.Entry<String, TupleType> entry : tuples.entrySet()) {
            Set<DependencyIdentity> dependencies = expectedFor(expected,
                    plan.tupleClasses().get(entry.getKey()));
            if (dependencies == null) {
                continue;
            }
            for (LyraType member : entry.getValue().memberTypes()) {
                addExpectedTypeDependencies(dependencies, member,
                        GeneratedDependencyKind.TUPLE_MEMBER_TYPE, true, plan.typeNames());
            }
        }
        for (Map.Entry<String, FunctionType> entry : functions.entrySet()) {
            Set<DependencyIdentity> dependencies = expectedFor(expected,
                    plan.functionInterfaces().get(entry.getKey()));
            if (dependencies == null) {
                continue;
            }
            for (LyraType parameter : entry.getValue().parameterTypes()) {
                addExpectedTypeDependencies(dependencies, parameter,
                        GeneratedDependencyKind.FUNCTION_SIGNATURE_TYPE, true, plan.typeNames());
            }
            addExpectedTypeDependencies(dependencies, entry.getValue().returnType(),
                    GeneratedDependencyKind.FUNCTION_SIGNATURE_TYPE, true, plan.typeNames());
        }

        Map<LambdaId, IrLambda> lambdas = ir.lambdas().stream()
                .collect(java.util.stream.Collectors.toMap(IrLambda::id, value -> value));
        Map<DeclarationId, IrLambda> lambdasByOwner = new TreeMap<>();
        lambdas.values().forEach(lambda ->
                lambda.ownerDeclaration().ifPresent(owner -> lambdasByOwner.put(owner, lambda)));
        Map<CaptureId, IrCapture> captures = ir.captures().stream()
                .collect(java.util.stream.Collectors.toMap(IrCapture::id, value -> value));
        for (IrLambda lambda : ir.lambdas()) {
            Set<DependencyIdentity> dependencies = expectedFor(expected,
                    plan.closureClasses().get(lambda.id()));
            if (dependencies == null) {
                continue;
            }
            for (LyraType parameter : lambda.signature().parameterTypes()) {
                addExpectedTypeDependencies(dependencies, parameter,
                        GeneratedDependencyKind.FUNCTION_SIGNATURE_TYPE, true, plan.typeNames());
            }
            addExpectedTypeDependencies(dependencies, lambda.signature().returnType(),
                    GeneratedDependencyKind.FUNCTION_SIGNATURE_TYPE, true, plan.typeNames());
            addExpectedDependency(dependencies,
                    plan.functionInterfaces().get(lambda.signature().canonicalSpelling()),
                    GeneratedDependencyKind.CLOSURE_FUNCTION_INTERFACE, true);
            addExpectedDependency(dependencies, plan.moduleStates().get(lambda.moduleId()),
                    GeneratedDependencyKind.CLOSURE_MODULE_STATE, true);
            for (CaptureId captureId : lambda.captures()) {
                IrCapture capture = captures.get(captureId);
                if (capture == null) {
                    differences.add("missing closure capture: " + captureId);
                    continue;
                }
                if (capture.isSharedCell()) {
                    addExpectedDependency(dependencies,
                            plan.cellClasses().get(capture.sharedCellId().orElseThrow()),
                            GeneratedDependencyKind.CLOSURE_SHARED_CELL, true);
                } else {
                    addExpectedTypeDependencies(dependencies, capture.contract().valueType(),
                            GeneratedDependencyKind.CLOSURE_CAPTURE_TYPE, true, plan.typeNames());
                }
            }
            for (IrFunctionLink link : ir.functionLinkage().links()) {
                if (lambda.ownerDeclaration().filter(link.from()::equals).isEmpty()) {
                    continue;
                }
                IrDeclaration targetDeclaration = declarations.get(link.to());
                if (targetDeclaration != null
                        && targetDeclaration.kind()
                        == io.mindspice.lyra.compiler.semantic.DeclarationKind.INTRINSIC_EXPORT) {
                    continue;
                }
                IrLambda target = lambdasByOwner.get(link.to());
                addExpectedDependency(dependencies,
                        target == null ? null : plan.closureClasses().get(target.id()),
                        GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE, false);
            }
            if (ir.closureInitializations().stream()
                    .filter(value -> value.lambdaId().equals(lambda.id()))
                    .anyMatch(io.mindspice.lyra.compiler.ir.IrClosureInitialization::recursive)) {
                addExpectedDependency(dependencies, plan.closureClasses().get(lambda.id()),
                        GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE, false);
            }
        }

        for (IrDeclaration declaration : ir.declarations().stream()
                .filter(value -> value.kind()
                        == io.mindspice.lyra.compiler.semantic.DeclarationKind.INTRINSIC_EXPORT)
                .toList()) {
            Set<DependencyIdentity> dependencies = expectedFor(expected,
                    plan.intrinsicFunctionClasses().get(declaration.id()));
            if (dependencies == null || declaration.contract().isEmpty()) {
                continue;
            }
            LyraType intrinsicType = declaration.contract().orElseThrow().valueType().withoutQualifiers();
            if (!(intrinsicType instanceof FunctionType function)) {
                continue;
            }
            addExpectedDependency(dependencies,
                    plan.functionInterfaces().get(function.canonicalSpelling()),
                    GeneratedDependencyKind.CLOSURE_FUNCTION_INTERFACE, true);
            addExpectedDependency(dependencies,
                    plan.moduleStates().get(declaration.moduleId()),
                    GeneratedDependencyKind.CLOSURE_MODULE_STATE, true);
        }

        for (var module : ir.modules()) {
            Set<DependencyIdentity> stateDependencies = expectedFor(expected,
                    plan.moduleStates().get(module.moduleId()));
            if (stateDependencies == null) {
                continue;
            }
            for (IrImportBinding binding : ir.imports()) {
                IrDeclaration declaration = declarations.get(binding.declarationId());
                if (declaration != null && declaration.moduleId().equals(module.moduleId())) {
                    addExpectedDependency(stateDependencies,
                            plan.moduleStates().get(binding.targetModule()),
                            GeneratedDependencyKind.MODULE_IMPORT_LINKAGE, false);
                }
            }
            for (var initialization : ir.initializationDependencies()) {
                if (initialization.fromModule().equals(module.moduleId())) {
                    addExpectedDependency(stateDependencies,
                            plan.moduleStates().get(initialization.toModule()),
                            GeneratedDependencyKind.MODULE_INITIALIZATION, true);
                }
            }
            for (DeclarationId declarationId : module.state().declarations()) {
                IrDeclaration declaration = declarations.get(declarationId);
                if (declaration == null || !declaration.scopeId().equals(module.state().rootScope())
                        || declaration.externalBinding().isPresent()) {
                    continue;
                }
                IrImportBinding importBinding = imports.get(declarationId);
                if (importBinding != null) {
                    addExpectedDependency(stateDependencies,
                            plan.moduleStates().get(importBinding.targetModule()),
                            GeneratedDependencyKind.MODULE_IMPORT_LINKAGE, false);
                } else if (plan.cellClasses().containsKey(declarationId)) {
                    addExpectedDependency(stateDependencies,
                            plan.cellClasses().get(declarationId),
                            GeneratedDependencyKind.MODULE_STATE_FIELD_TYPE, true);
                } else {
                    declaration.contract().ifPresent(contract ->
                            addExpectedTypeDependencies(stateDependencies, contract.valueType(),
                                    GeneratedDependencyKind.MODULE_STATE_FIELD_TYPE, true,
                                    plan.typeNames()));
                }
            }

            module.submissionResult().ifPresent(result -> addExpectedTypeDependencies(stateDependencies,
                    result.type(), GeneratedDependencyKind.MODULE_STATE_FIELD_TYPE, true, plan.typeNames()));
            Set<DependencyIdentity> facadeDependencies = expectedFor(expected,
                    plan.moduleFacades().get(module.moduleId()));
            if (facadeDependencies != null) {
                module.submissionResult().ifPresent(result -> {
                    addExpectedTypeDependencies(facadeDependencies, result.type(),
                            GeneratedDependencyKind.FACADE_EXPORT_TYPE, true, plan.typeNames());
                    ir.declarations().stream()
                            .filter(declaration -> declaration.moduleId().equals(module.moduleId())
                                    && declaration.scopeId().equals(module.rootScope())
                                    && declaration.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.LET)
                            .forEach(declaration -> addExpectedTypeDependencies(facadeDependencies,
                                    declaration.contract().orElseThrow().valueType(),
                                    GeneratedDependencyKind.FACADE_EXPORT_TYPE, true, plan.typeNames()));
                });
                addExpectedDependency(facadeDependencies, plan.moduleStates().get(module.moduleId()),
                        GeneratedDependencyKind.FACADE_STATE, true);
                ir.exports().stream().filter(export -> export.moduleId().equals(module.moduleId()))
                        .forEach(export -> addExpectedTypeDependencies(facadeDependencies,
                                export.contract().valueType(),
                                GeneratedDependencyKind.FACADE_EXPORT_TYPE, true,
                                plan.typeNames()));
            }
        }

        for (IrCell cell : ir.cells()) {
            Set<DependencyIdentity> dependencies = expectedFor(expected,
                    plan.cellClasses().get(cell.declarationId()));
            if (dependencies != null) {
                addExpectedTypeDependencies(dependencies, cell.contract().valueType(),
                        GeneratedDependencyKind.CELL_VALUE_TYPE, true, plan.typeNames());
            }
        }

        for (GeneratedClassPlan classPlan : plan.classes()) {
            Set<DependencyIdentity> actual = classPlan.dependencies().stream()
                    .map(dependency -> new DependencyIdentity(dependency.targetBinaryName(),
                            dependency.kind(), dependency.orderingRequired()))
                    .collect(java.util.stream.Collectors.toSet());
            Set<DependencyIdentity> wanted = expected.getOrDefault(classPlan.binaryName(), Set.of());
            if (!actual.equals(wanted)) {
                differences.add("generated dependencies differ for " + classPlan.binaryName());
            }
        }
    }

    private static void compareCellLayouts(
            TypedIr ir,
            GeneratedTypePlan plan,
            JvmAbiMapper mapper,
            List<String> differences) {
        for (IrCell cell : ir.cells()) {
            String className = plan.cellClasses().get(cell.declarationId());
            GeneratedClassPlan classPlan = className == null
                    ? null : plan.classPlan(className).orElse(null);
            JvmTypePlan expected = mapper.map(cell.contract().valueType(), JvmMappingContext.INTERNAL_CELL);
            if (classPlan == null) {
                differences.add("missing cell layout: " + cell.declarationId());
                continue;
            }
            for (GeneratedMemberPlan member : classPlan.members()) {
                if (member.kind() == GeneratedMemberKind.CELL_VALUE_FIELD
                        || member.kind() == GeneratedMemberKind.CELL_PRESENCE_FIELD) {
                    if (member.valueType().filter(expected::equals).isEmpty()) {
                        differences.add("cell value mapping differs: " + cell.declarationId());
                    }
                }
            }
        }
    }

    private static void compareClosureLayouts(
            TypedIr ir,
            GeneratedTypePlan plan,
            JvmAbiMapper mapper,
            List<String> differences) {
        Map<CaptureId, IrCapture> captures = ir.captures().stream()
                .collect(java.util.stream.Collectors.toMap(IrCapture::id, value -> value));
        Map<DeclarationId, IrDeclaration> declarations = ir.declarations().stream()
                .collect(java.util.stream.Collectors.toMap(IrDeclaration::id, value -> value));
        Map<ModuleId, ScopeId> rootScopes =
                ir.modules().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        io.mindspice.lyra.compiler.ir.IrModule::moduleId,
                        io.mindspice.lyra.compiler.ir.IrModule::rootScope));
        Set<DeclarationId> rootDeclarations = ir.declarations().stream()
                .filter(declaration -> declaration.scopeId().equals(
                        rootScopes.get(declaration.moduleId())))
                .map(IrDeclaration::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (IrLambda lambda : ir.lambdas()) {
            String className = plan.closureClasses().get(lambda.id());
            GeneratedClassPlan closure = className == null
                    ? null : plan.classPlan(className).orElse(null);
            if (closure == null) {
                differences.add("missing closure layout: " + lambda.id());
                continue;
            }
            GeneratedMemberPlan state = closure.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_STATE_FIELD)
                    .findFirst().orElse(null);
            String stateName = plan.moduleStates().get(lambda.moduleId());
            if (state == null || stateName == null
                    || !state.descriptor().equals("L" + stateName.replace('.', '/') + ";")) {
                differences.add("closure state mapping differs: " + lambda.id());
            }
            Set<String> expectedCaptureNames = new java.util.TreeSet<>();
            for (CaptureId captureId : lambda.captures()) {
                IrCapture capture = captures.get(captureId);
                if (capture == null) {
                    differences.add("missing closure capture: " + captureId);
                    continue;
                }
                String prefix = "$lyra$capture$" + captureId.value();
                boolean functionSlot = usesLocalFunctionSlot(
                        capture, declarations, rootDeclarations);
                if (capture.isSharedCell() || functionSlot) {
                    expectedCaptureNames.add(prefix);
                } else {
                    JvmTypePlan expected = mapper.map(capture.contract().valueType(),
                            JvmMappingContext.INTERNAL_CAPTURE);
                    expectedCaptureNames.add(expected.isSplitValue()
                            ? prefix + "$present" : prefix);
                    if (expected.isSplitValue()) {
                        expectedCaptureNames.add(prefix + "$payload");
                    }
                }
                if (capture.isSharedCell()) {
                    String cellName = plan.cellClasses().get(capture.sharedCellId().orElseThrow());
                    boolean present = cellName != null && closure.members().stream().anyMatch(member ->
                            member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_FIELD
                                    && member.name().equals(prefix)
                                    && member.descriptor().equals("L" + cellName.replace('.', '/') + ";"));
                    if (!present) {
                        differences.add("shared closure capture mapping differs: " + captureId);
                    }
                    continue;
                }
                JvmTypePlan expected = mapper.map(capture.contract().valueType(),
                        JvmMappingContext.INTERNAL_CAPTURE);
                if (functionSlot) {
                    boolean present = expected.isSingleValue()
                            && expected.physicalComponents().getFirst().isReference()
                            && closure.members().stream().anyMatch(member ->
                            member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_FIELD
                                    && member.name().equals(prefix)
                                    && member.descriptor().equals("[" + expected.descriptor()));
                    if (!present) {
                        differences.add("linked function closure capture mapping differs: " + captureId);
                    }
                    continue;
                }
                List<GeneratedMemberPlan> actual = closure.members().stream()
                        .filter(member -> member.name().equals(prefix)
                                || member.name().equals(prefix + "$present")
                                || member.name().equals(prefix + "$payload"))
                        .toList();
                List<String> expectedNames = expected.isSplitValue()
                        ? List.of(prefix + "$present", prefix + "$payload")
                        : List.of(prefix);
                if (!actual.stream().map(GeneratedMemberPlan::name).toList().equals(expectedNames)
                        || actual.stream().anyMatch(member -> member.valueType()
                        .filter(expected::equals).isEmpty())) {
                    differences.add("immutable closure capture mapping differs: " + captureId);
                }
            }
            Set<String> actualCaptureNames = closure.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_FIELD
                            || member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PRESENCE_FIELD
                            || member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PAYLOAD_FIELD)
                    .map(GeneratedMemberPlan::name)
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            if (!actualCaptureNames.equals(expectedCaptureNames)) {
                differences.add("closure capture inventory differs: " + lambda.id());
            }
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
                && declaration.contract().map(io.mindspice.lyra.compiler.types.BindingContract::valueType)
                .map(LyraType::withoutQualifiers)
                .filter(FunctionType.class::isInstance)
                .isPresent();
    }

    private static Set<DependencyIdentity> expectedFor(
            Map<String, Set<DependencyIdentity>> expected, String className) {
        if (className == null) {
            return null;
        }
        return expected.computeIfAbsent(className, ignored -> new HashSet<>());
    }

    private static void addExpectedDependency(
            Set<DependencyIdentity> dependencies,
            String target,
            GeneratedDependencyKind kind,
            boolean orderingRequired) {
        if (target != null) {
            dependencies.add(new DependencyIdentity(target, kind, orderingRequired));
        }
    }

    private static void addExpectedTypeDependencies(
            Set<DependencyIdentity> dependencies,
            LyraType type,
            GeneratedDependencyKind kind,
            boolean orderingRequired,
            JvmTypeNameTable names) {
        LyraType base = type.withoutQualifiers();
        if (base instanceof ArrayType array) {
            addExpectedTypeDependencies(dependencies, array.elementType(), kind,
                    orderingRequired, names);
        } else if (base instanceof TupleType tuple) {
            addExpectedDependency(dependencies, names.tupleNames().get(tuple.canonicalSpelling()),
                    kind, orderingRequired);
            tuple.memberTypes().forEach(member -> addExpectedTypeDependencies(
                    dependencies, member, kind, orderingRequired, names));
        } else if (base instanceof FunctionType function) {
            addExpectedDependency(dependencies, names.functionNames().get(function.canonicalSpelling()),
                    kind, orderingRequired);
            function.parameterTypes().forEach(parameter -> addExpectedTypeDependencies(
                    dependencies, parameter, kind, orderingRequired, names));
            addExpectedTypeDependencies(dependencies, function.returnType(), kind,
                    orderingRequired, names);
        }
    }

    private static void collectTypeDefinitions(
            TypedIr ir,
            Map<String, TupleType> tuples,
            Map<String, FunctionType> functions) {
        ir.declarations().forEach(declaration -> declaration.contract().ifPresent(contract ->
                collectTypeDefinitions(contract.valueType(), tuples, functions)));
        ir.references().forEach(reference -> reference.type().ifPresent(type ->
                collectTypeDefinitions(type, tuples, functions)));
        ir.lambdas().forEach(lambda -> collectTypeDefinitions(
                lambda.signature().asFunctionType(), tuples, functions));
        ir.captures().forEach(capture -> collectTypeDefinitions(
                capture.contract().valueType(), tuples, functions));
        ir.cells().forEach(cell -> collectTypeDefinitions(
                cell.contract().valueType(), tuples, functions));
        ir.exports().forEach(export -> collectTypeDefinitions(
                export.contract().valueType(), tuples, functions));
        ir.functionLinkage().signatures().values().forEach(signature -> collectTypeDefinitions(
                signature.asFunctionType(), tuples, functions));
        io.mindspice.lyra.compiler.ir.IrTraversal.preOrder(ir).forEach(node ->
                collectTypeDefinitions(node.type(), tuples, functions));
    }

    private static void collectTypeDefinitions(
            LyraType type,
            Map<String, TupleType> tuples,
            Map<String, FunctionType> functions) {
        LyraType base = type.withoutQualifiers();
        if (base instanceof ArrayType array) {
            collectTypeDefinitions(array.elementType(), tuples, functions);
        } else if (base instanceof TupleType tuple) {
            tuples.putIfAbsent(tuple.canonicalSpelling(), tuple);
            tuple.memberTypes().forEach(member -> collectTypeDefinitions(member, tuples, functions));
        } else if (base instanceof FunctionType function) {
            functions.putIfAbsent(function.canonicalSpelling(), function);
            function.parameterTypes().forEach(parameter -> collectTypeDefinitions(
                    parameter, tuples, functions));
            collectTypeDefinitions(function.returnType(), tuples, functions);
        }
    }

    private record DependencyIdentity(
            String targetBinaryName,
            GeneratedDependencyKind kind,
            boolean orderingRequired) {
    }

    private static Optional<DeclarationId> stateFieldDeclaration(String name) {
        String prefix = "$lyra$binding$";
        if (!name.startsWith(prefix)) {
            return Optional.empty();
        }
        String ordinal = name.substring(prefix.length())
                .replaceFirst("\\$(present|payload)$", "");
        try {
            return Optional.of(new DeclarationId(Long.parseLong(ordinal)));
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private static void collectTypeFamilies(
            LyraType type, Set<String> tuples, Set<String> functions) {
        LyraType base = type.withoutQualifiers();
        if (base instanceof ArrayType array) {
            collectTypeFamilies(array.elementType(), tuples, functions);
        } else if (base instanceof TupleType tuple) {
            tuples.add(tuple.canonicalSpelling());
            tuple.memberTypes().forEach(value -> collectTypeFamilies(value, tuples, functions));
        } else if (base instanceof FunctionType function) {
            functions.add(function.canonicalSpelling());
            function.parameterTypes().forEach(value -> collectTypeFamilies(value, tuples, functions));
            collectTypeFamilies(function.returnType(), tuples, functions);
        }
    }

    public static void require(TypedIr ir, GeneratedTypePlan plan) {
        compare(ir, plan).requireMatch();
    }

    public static void requireParity(TypedIr ir, GeneratedTypePlan plan) {
        require(ir, plan);
    }
}
