package io.mindspice.lyra.compiler.backend.jvm;

import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.PrimitiveType;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural planner tests; no generated class bytes are produced. */
public final class GeneratedTypePlannerTest {
    @Test
    void seededNominalProjectionPreservesIndependentFieldContracts() {
        int cases = Integer.getInteger("lyra.nominal.projection.cases", Integer.getInteger("lyra.fuzz.cases", 24));
        for (long seed : new long[] { 109, 20260911 }) {
            var random = new java.util.Random(seed);
            for (int index = 0; index < cases; index++) {
                boolean mutable = random.nextBoolean();
                boolean array = random.nextBoolean();
                String field = array ? "Array<@nil I64>" : "I32";
                String source = "struct Node { let " + (mutable ? "@mut " : "")
                        + "data :" + field + " let @nil next :Node }";
                var typed = ir(source);
                var runtime = NominalRuntimeContracts.from(typed);
                var schema = runtime.schemas().getFirst();
                var expected = array ? io.mindspice.lyra.runtime.ArrayType.of(io.mindspice.lyra.runtime.PrimitiveType.I64.nilable())
                        : io.mindspice.lyra.runtime.PrimitiveType.I32;
                String replay = "seed=" + seed + ", index=" + index + "\n" + source;
                assertEquals(expected, schema.members().getFirst().type(), replay);
                assertEquals(mutable, schema.members().getFirst().mutable(), replay);
                assertEquals(List.of(expected, schema.type().nilable()), schema.constructorParameters(), replay);
                var layouts = GeneratedTypePlanner.plan(typed).nominalLayouts();
                var layout = layouts.get(schema.type().canonicalSpelling());
                String descriptor = array ? "[Ljava/lang/Long;" : "I";
                assertEquals(descriptor, layout.fields().getFirst().value().descriptor(), replay);
                assertEquals("(Lio/mindspice/lyra/runtime/LyraNominalConstruction;" + descriptor + ")V",
                        layout.fields().getFirst().initializationSetterDescriptor(), replay);
                assertEquals("(Lio/mindspice/lyra/runtime/LyraClosureAuthority;)" + descriptor,
                        layout.fields().getFirst().generatedGetterDescriptor(), replay);
                assertEquals(mutable, layout.fields().getFirst().member().mutability().isMutable(), replay);
            }
        }
    }

    @Test
    void nominalRuntimeProjectionPreservesSourceSchemasWithoutReparsingUnknownNames() {
        TypedIr ir = ir("""
                struct Node { let @mut @nil next :Node = #NIL let data :Array<I32> }
                class Holder {
                    let @mut @nil node :Node = #NIL
                    let @pub @mut read :Fn<;@nil Node> = (=> || self:.node)
                }
                """);
        var runtime = NominalRuntimeContracts.from(ir);
        assertEquals(2, runtime.schemas().size());
        var node = runtime.schemas().stream().filter(schema -> schema.type().id().name().equals("Node")).findFirst().orElseThrow();
        var holder = runtime.schemas().stream().filter(schema -> schema.type().id().name().equals("Holder")).findFirst().orElseThrow();
        assertEquals(io.mindspice.lyra.runtime.NominalSchema.Kind.STRUCT, node.kind());
        assertEquals(List.of("next", "data"), node.members().stream().map(io.mindspice.lyra.runtime.NominalSchema.Member::name).toList());
        assertEquals(node.type().nilable(), node.members().getFirst().type());
        assertTrue(node.members().getFirst().mutable());
        assertEquals(List.of(io.mindspice.lyra.runtime.ArrayType.of(io.mindspice.lyra.runtime.PrimitiveType.I32)), node.constructorParameters());
        assertFalse(holder.members().getFirst().publicAccess());
        assertTrue(holder.members().get(1).publicAccess());
        assertTrue(holder.members().get(1).mutable());
        assertEquals(io.mindspice.lyra.runtime.FunctionType.of(List.of(), node.type().nilable()), holder.members().get(1).type());
        for (var schema : runtime.schemas()) assertEquals(schema.type(),
                io.mindspice.lyra.runtime.LyraType.parse(schema.type().canonicalSpelling(), runtime));
        var generated = GeneratedTypePlanner.plan(ir);
        assertEquals(2, generated.nominalLayouts().size());
        for (var layout : generated.nominalLayouts().values()) {
            var classPlan = generated.classPlan(layout.binaryName()).orElseThrow();
            assertEquals(GeneratedClassKind.NOMINAL_VALUE, classPlan.kind());
            assertTrue(classPlan.isFinal());
            assertTrue(classPlan.isPublic());
            assertTrue(classPlan.members().stream().filter(member -> member.kind() == GeneratedMemberKind.NOMINAL_FIELD)
                    .allMatch(GeneratedMemberPlan::isPrivate));
            assertEquals(layout.fields().size(), classPlan.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.NOMINAL_INITIALIZE).count());
            assertEquals(layout.fields().stream().filter(field -> field.member().mutability().isMutable()).count(),
                    classPlan.members().stream().filter(member -> member.kind() == GeneratedMemberKind.NOMINAL_SET).count());
            assertEquals(layout.fields().stream().filter(field -> field.member().publicAccess()).count(),
                    classPlan.members().stream().filter(member -> member.kind() == GeneratedMemberKind.NOMINAL_PUBLIC_GET).count());
            var structuralEquality = classPlan.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.NOMINAL_STRUCTURAL_EQUAL)
                    .toList();
            if (layout.schema().kind() == io.mindspice.lyra.compiler.types.NominalSchema.Kind.STRUCT) {
                assertEquals(1, structuralEquality.size());
                var equality = structuralEquality.getFirst();
                assertEquals("$lyra$structuralEquals", equality.name());
                assertEquals("(" + NominalClassLayout.AUTHORITY + "L"
                                + layout.binaryName().replace('.', '/')
                                + ";Lio/mindspice/lyra/runtime/LyraStructuralEquality;)Z",
                        equality.descriptor());
                assertFalse(equality.isStatic());
                assertTrue(equality.isPublic());
            } else {
                assertTrue(structuralEquality.isEmpty());
            }
        }
    }

    @Test
    void nominalLayoutRejectsForgedOriginFieldOrderAndFactoryContracts() {
        var generated = GeneratedTypePlanner.plan(ir("struct Pair { let first :I32 let @mut second :I64 }"));
        var layout = generated.nominalLayouts().values().iterator().next();
        var mapper = generated.mapper();
        assertThrows(IllegalArgumentException.class, () -> new NominalClassLayout(
                layout.binaryName() + "wrong", layout.schema(), layout.fields(), layout.factorySignature()));
        assertThrows(IllegalArgumentException.class, () -> new NominalClassLayout(
                layout.binaryName(), layout.schema(), layout.fields().reversed(), layout.factorySignature()));
        assertThrows(IllegalArgumentException.class, () -> new NominalClassLayout(
                layout.binaryName(), layout.schema(), List.of(), layout.factorySignature()));
        assertThrows(IllegalArgumentException.class, () -> new NominalClassLayout.Field(0,
                layout.fields().getFirst().member(), mapper.map(PrimitiveType.I32, JvmMappingContext.TUPLE_FIELD)));
        assertThrows(IllegalArgumentException.class, () -> new NominalClassLayout.Field(0,
                layout.fields().getFirst().member(), mapper.map(PrimitiveType.I64, JvmMappingContext.NOMINAL_FIELD)));
        assertThrows(IllegalArgumentException.class, () -> new NominalClassLayout(
                layout.binaryName(), layout.schema(), layout.fields(),
                mapper.mapSignature(LyraSignature.of(List.of(), layout.schema().type()))));
        var classPlan = generated.classPlan(layout.binaryName()).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> new GeneratedClassPlan(classPlan.binaryName(), classPlan.kind(),
                classPlan.stableKey(), classPlan.moduleId(), true, false, List.of(), List.of(),
                classPlan.dependencies(), classPlan.members().subList(1, classPlan.members().size()), Optional.of(layout)));
    }

    @Test
    void nominalFactoriesKeepConstructorParametersAndDoNotAllocateTypeNameStorage() {
        var typed = ir("""
                struct Empty { }
                class Counter {
                    let value :I32
                    Counter = (=> |initial :I32| { self:.value := initial })
                }
                """);
        var generated = GeneratedTypePlanner.plan(typed);
        var counter = generated.nominalLayouts().values().stream()
                .filter(layout -> layout.schema().type().id().name().equals("Counter")).findFirst().orElseThrow();
        assertEquals("(I)L" + counter.binaryName().replace('.', '/') + ";", counter.factorySignature().descriptor());
        var empty = generated.nominalLayouts().values().stream()
                .filter(layout -> layout.schema().type().id().name().equals("Empty")).findFirst().orElseThrow();
        assertTrue(empty.fields().isEmpty());
        assertEquals("()L" + empty.binaryName().replace('.', '/') + ";", empty.factorySignature().descriptor());
        var state = generated.classes().stream().filter(plan -> plan.kind() == GeneratedClassKind.MODULE_STATE)
                .findFirst().orElseThrow();
        for (var declaration : typed.declarations().stream().filter(value -> value.kind()
                == io.mindspice.lyra.compiler.semantic.DeclarationKind.NOMINAL).toList()) {
            var layout = generated.nominalLayouts().values().stream().filter(value -> value.schema().type().id().name()
                    .equals(declaration.name())).findFirst().orElseThrow();
            assertTrue(state.members().stream().anyMatch(member -> member.kind()
                    == GeneratedMemberKind.STATE_NOMINAL_FACTORY
                    && member.name().equals("$lyra$new$" + declaration.id().value())
                    && member.descriptor().equals(layout.factorySignature().descriptor())));
        }
        var nominalDeclarations = typed.declarations().stream()
                .filter(declaration -> declaration.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.NOMINAL)
                .map(declaration -> "$lyra$binding$" + declaration.id().value()).toList();
        assertTrue(generated.classes().stream().filter(plan -> plan.kind() == GeneratedClassKind.MODULE_STATE)
                .flatMap(plan -> plan.members().stream()).noneMatch(member -> nominalDeclarations.contains(member.name())));
    }

    @Test
    void plansAllReachableGeneratedTypesAndTypedFacadeMembers() {
        TypedIr ir = ir("let @pub value :Tuple<I32,@nil String> = Tuple[1 #NIL] "
                + "let @pub apply :Fn<Tuple<I32,@nil String>;@nil String> = "
                + "(=> |pair| pair:.1)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);

        assertEquals(1, plan.tupleClasses().size());
        GeneratedClassPlan tupleClass = plan.classPlan(plan.tupleClasses().values().iterator().next())
                .orElseThrow();
        assertTrue(tupleClass.isFinal());
        assertTrue(tupleClass.isPublic());
        assertTrue(tupleClass.members().stream().filter(member ->
                member.kind() == GeneratedMemberKind.TUPLE_FIELD).allMatch(GeneratedMemberPlan::isFinal));
        assertTrue(tupleClass.members().stream().filter(member ->
                member.kind() == GeneratedMemberKind.TUPLE_FIELD).allMatch(GeneratedMemberPlan::isPrivate));
        assertEquals(2, tupleClass.members().stream().filter(member ->
                member.kind() == GeneratedMemberKind.TUPLE_COMPONENT_GET).count());
        assertTrue(tupleClass.members().stream().filter(member ->
                member.kind() == GeneratedMemberKind.TUPLE_COMPONENT_GET)
                .allMatch(member -> member.visibility() == GeneratedMemberVisibility.PACKAGE));
        assertEquals(1, plan.functionInterfaces().size());
        assertTrue(plan.classPlan(plan.functionInterfaces().values().iterator().next())
                .orElseThrow().isPublic());
        assertEquals(1, plan.closureClasses().size());
        assertEquals(1, plan.moduleStates().size());
        assertEquals(1, plan.moduleFacades().size());
        assertEquals(2, plan.exports().size());

        GeneratedExportPlan value = plan.exports().stream()
                .filter(export -> export.sourceName().equals("value")).findFirst().orElseThrow();
        assertEquals("()" + value.valueType().descriptor(),
                value.getter().orElseThrow().descriptor());
        assertTrue(value.getter().orElseThrow().kind() == GeneratedMemberKind.VALUE_GETTER);

        GeneratedExportPlan apply = plan.exports().stream()
                .filter(export -> export.sourceName().equals("apply")).findFirst().orElseThrow();
        assertTrue(apply.isFunction());
        assertEquals("(L" + plan.tupleClasses().values().iterator().next().replace('.', '/')
                        + ";)Ljava/lang/String;",
                apply.invocation().orElseThrow().descriptor());
        assertTrue(apply.functionValueGetter().orElseThrow().descriptor().startsWith("()L"));
        assertFalse(apply.setter().isPresent());

        assertBefore(plan, GeneratedClassKind.TUPLE_VALUE, GeneratedClassKind.CLOSURE);
        assertBefore(plan, GeneratedClassKind.FUNCTION_INTERFACE, GeneratedClassKind.CLOSURE);
        assertBefore(plan, GeneratedClassKind.MODULE_STATE, GeneratedClassKind.MODULE_FACADE);
        assertEquals(plan.classes().size(), plan.generationOrder().size());
        assertTrue(plan.classes().stream().flatMap(classPlan -> classPlan.members().stream())
                .allMatch(member -> !member.descriptor().contains("Object")));
        assertTrue(plan.classNames().stream().allMatch(name -> !name.contains("/home/")));
        GeneratedClassPlan facade = plan.classes().stream()
                .filter(classPlan -> classPlan.kind() == GeneratedClassKind.MODULE_FACADE)
                .findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratedClassPlan(facade.binaryName(), facade.kind(), facade.stableKey(),
                        facade.moduleId(), facade.finalClass(), facade.functionalInterface(),
                        facade.interfaces(), facade.annotations(), facade.dependencies(),
                        facade.members().stream()
                                .filter(member -> member.kind() != GeneratedMemberKind.METADATA)
                                .toList()));
        JvmAbiParity.require(ir, plan);
    }

    @Test
    void mutableCapturesProduceSharedCellAndExplicitClosureLinkage() {
        TypedIr ir = ir("let @mut value :I32 = 1 "
                + "let @pub read :Fn<;I32> = (=> | | value)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        assertEquals(1, plan.cellClasses().size());
        GeneratedClassPlan closure = plan.classPlan(plan.closureClasses().values().iterator().next())
                .orElseThrow();
        assertTrue(closure.dependencies().stream().anyMatch(dependency ->
                dependency.kind() == GeneratedDependencyKind.CLOSURE_SHARED_CELL
                        && dependency.orderingRequired()));
        assertTrue(closure.dependencies().stream().anyMatch(dependency ->
                dependency.kind() == GeneratedDependencyKind.CLOSURE_MODULE_STATE
                        && dependency.targetBinaryName().equals(
                        plan.moduleStates().values().iterator().next())
                        && dependency.orderingRequired()));
        assertTrue(closure.members().stream().anyMatch(member ->
                member.kind() == GeneratedMemberKind.CLOSURE_STATE_FIELD
                        && member.isPrivate() && member.isFinal()));
        assertTrue(closure.members().stream().anyMatch(member ->
                member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_FIELD));
        assertTrue(plan.classNames().indexOf(plan.cellClasses().values().iterator().next())
                < plan.classNames().indexOf(closure.binaryName()));
    }

    @Test
    void moduleLinkedClosuresRetainTheirStateWithoutSyntheticCaptures() {
        TypedIr ir = ir("let first :Fn<;I32> = (=> | | ::second[]) "
                + "let second :Fn<;I32> = (=> | | ::first[])");
        assertTrue(ir.captures().isEmpty());
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        String stateName = plan.moduleStates().values().iterator().next();
        String stateDescriptor = "L" + stateName.replace('.', '/') + ";";

        for (String closureName : plan.closureClasses().values()) {
            GeneratedClassPlan closure = plan.classPlan(closureName).orElseThrow();
            assertTrue(closure.members().stream().anyMatch(member ->
                    member.kind() == GeneratedMemberKind.CLOSURE_STATE_FIELD
                            && member.descriptor().equals(stateDescriptor)));
            assertTrue(closure.dependencies().stream().anyMatch(dependency ->
                    dependency.kind() == GeneratedDependencyKind.CLOSURE_MODULE_STATE
                            && dependency.targetBinaryName().equals(stateName)));
            assertTrue(closure.dependencies().stream().anyMatch(dependency ->
                    dependency.kind() == GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE
                            && !dependency.orderingRequired()));
        }
    }

    @Test
    void repeatedPlanningAndIndependentOrderingAreDeterministic() {
        TypedIr ir = ir("let @pub f :Fn<I32;I32> = (=> |x| x) "
                + "let @pub pair :Tuple<I32,String> = Tuple[1 \"x\"]");
        GeneratedTypePlan first = GeneratedTypePlanner.plan(ir, "custom.generated");
        GeneratedTypePlan second = GeneratedTypePlanner.plan(ir, "custom.generated");
        assertEquals(first, second);
        assertEquals(first.classes(), second.classes());
        assertEquals(first.exports(), second.exports());
        assertEquals(first.typeNames().tupleNames(), second.typeNames().tupleNames());
        assertEquals(first.typeNames().functionNames(), second.typeNames().functionNames());
        assertThrows(UnsupportedOperationException.class, () -> first.classes().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.moduleStates().clear());

        GeneratedClassPlan a = raw("custom.generated.$lyra$state$a");
        GeneratedClassPlan b = raw("custom.generated.$lyra$state$b");
        assertEquals(GeneratedTypePlanner.order(List.of(a, b)),
                GeneratedTypePlanner.order(List.of(b, a)));
    }

    @Test
    void requiredClassDependencyCyclesAreRejectedButLinkageCyclesAreLegal() {
        GeneratedClassDependency aToB = new GeneratedClassDependency(
                "custom.generated.$lyra$state$b", GeneratedDependencyKind.FACADE_STATE, true, "a needs b");
        GeneratedClassDependency bToA = new GeneratedClassDependency(
                "custom.generated.$lyra$state$a", GeneratedDependencyKind.FACADE_STATE, true, "b needs a");
        GeneratedClassPlan a = new GeneratedClassPlan("custom.generated.$lyra$state$a",
                GeneratedClassKind.MODULE_STATE, "a", Optional.of(ModuleId.path("a.lyra")), true, false,
                List.of(), List.of(), List.of(aToB), List.of(
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CONSTRUCTOR,
                                "<init>", "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false)));
        GeneratedClassPlan b = new GeneratedClassPlan("custom.generated.$lyra$state$b",
                GeneratedClassKind.MODULE_STATE, "b", Optional.of(ModuleId.path("b.lyra")), true, false,
                List.of(), List.of(), List.of(bToA), List.of(
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CONSTRUCTOR,
                                "<init>", "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false)));
        GeneratedClassDependency cToA = new GeneratedClassDependency(
                "custom.generated.$lyra$state$a", GeneratedDependencyKind.FACADE_STATE, true,
                "c is blocked by the cycle but is not cyclic");
        GeneratedClassPlan c = new GeneratedClassPlan("custom.generated.$lyra$state$c",
                GeneratedClassKind.MODULE_STATE, "c", Optional.of(ModuleId.path("c.lyra")), true, false,
                List.of(), List.of(), List.of(cToA), List.of(
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CONSTRUCTOR,
                                "<init>", "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false)));
        IllegalArgumentException cycle = assertThrows(IllegalArgumentException.class,
                () -> GeneratedTypePlanner.order(List.of(c, a, b)));
        assertTrue(cycle.getMessage().contains("dependency cycle"));
        assertTrue(cycle.getMessage().contains("$lyra$state$a"));
        assertTrue(cycle.getMessage().contains("$lyra$state$b"));
        assertFalse(cycle.getMessage().contains("$lyra$state$c"));

        String recursiveName = "custom.generated.$lyra$closure$recursive";
        GeneratedClassDependency linkage = new GeneratedClassDependency(
                recursiveName, GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE, false,
                "legal recursion");
        GeneratedClassPlan recursive = new GeneratedClassPlan(recursiveName,
                GeneratedClassKind.CLOSURE, "recursive", Optional.of(ModuleId.path("recursive.lyra")), true, false,
                List.of("custom.generated.$lyra$fn$unit"), List.of(), List.of(linkage), List.of(
                        GeneratedMemberPlan.rawField(GeneratedMemberKind.CLOSURE_AUTHORITY_FIELD,
                                "$lyra$authority", "Lio/mindspice/lyra/runtime/LyraClosureAuthority;"),
                        GeneratedMemberPlan.rawField(GeneratedMemberKind.CLOSURE_STATE_FIELD,
                                "$lyra$state", "Lcustom/generated/$lyra$state$recursive;"),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CLOSURE_CONSTRUCTOR,
                                "<init>", "(Lio/mindspice/lyra/runtime/LyraClosureAuthority;"
                                        + "Lcustom/generated/$lyra$state$recursive;)V", false),
                        GeneratedMemberPlan.method(GeneratedMemberKind.CLOSURE_INVOKE,
                                "invoke", new JvmAbiMapper().mapSignature(
                                        LyraSignature.of(List.of(), PrimitiveType.UNIT),
                                        JvmAbiBoundary.JAVA_VISIBLE), false,
                                Optional.empty(), Optional.empty())));
        assertEquals(List.of(recursive), GeneratedTypePlanner.order(List.of(recursive)));
    }

    @Test
    void ambiguousDependencyEdgesAndNonrecursiveSelfLinksAreRejected() {
        String state = "custom.generated.$lyra$state$dependency";
        assertThrows(IllegalArgumentException.class, () -> new GeneratedClassDependency(
                state, GeneratedDependencyKind.TUPLE_MEMBER_TYPE, false,
                "a type edge cannot be linkage-only"));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedClassDependency(
                state, GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE, true,
                "recursive linkage cannot impose class order"));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedClassPlan(
                "custom.generated.$lyra$state$source", GeneratedClassKind.MODULE_STATE,
                "state:source", Optional.of(ModuleId.path("source.lyra")), true, false,
                List.of(), List.of(), List.of(
                        new GeneratedClassDependency(state, GeneratedDependencyKind.MODULE_IMPORT_LINKAGE,
                                false, "first"),
                        new GeneratedClassDependency(state, GeneratedDependencyKind.MODULE_IMPORT_LINKAGE,
                                false, "second")), List.of(
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CONSTRUCTOR,
                                "<init>", "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false))));

        GeneratedClassPlan self = new GeneratedClassPlan(
                state, GeneratedClassKind.MODULE_STATE, "state:self",
                Optional.of(ModuleId.path("self.lyra")), true, false,
                List.of(), List.of(), List.of(new GeneratedClassDependency(
                        state, GeneratedDependencyKind.MODULE_IMPORT_LINKAGE, false,
                        "self import")), List.of(GeneratedMemberPlan.rawMethod(
                        GeneratedMemberKind.STATE_CONSTRUCTOR, "<init>",
                        "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false)));
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedTypePlanner.order(List.of(self)));
    }

    @Test
    void moduleImportsRemainExplicitLinkageAndEachModuleGetsItsOwnFacadeState() {
        ModuleId main = ModuleId.path("phase14-main.lyra");
        ModuleId dependency = ModuleId.path("phase14-dependency.lyra");
        ModuleGraph.Node mainNode = node(main,
                "import dependency->{run} let @pub initial :I32 = ::run[] "
                        + "let @pub main :Fn<;I32> = (=> | | ::run[])",
                LogicalModuleId.parse("main"));
        ModuleGraph.Node dependencyNode = node(dependency,
                "let @pub run :Fn<;I32> = (=> | | 1)", LogicalModuleId.parse("dependency"));
        ModuleGraph graph = new ModuleGraph(main,
                List.of(mainNode, dependencyNode),
                List.of(new ModuleGraph.Edge(main, LogicalModuleId.parse("dependency"), dependency,
                        mainNode.program().imports().getFirst().path().span())),
                Map.of(LogicalModuleId.parse("main"), main,
                        LogicalModuleId.parse("dependency"), dependency));
        TypedIr typedIr = phaseSuccess(TypedIrBuilder.lower(typedGraph(graph)));
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(typedIr);

        assertTrue(typedIr.functionLinkage().links().stream().anyMatch(link ->
                link.from().equals(typedIr.declarations().stream()
                        .filter(declaration -> declaration.moduleId().equals(main)
                                && declaration.name().equals("main"))
                        .findFirst().orElseThrow().id())));
        assertEquals(2, plan.moduleStates().size());
        assertEquals(2, plan.moduleFacades().size());
        GeneratedClassPlan mainState = plan.classPlan(plan.moduleStates().get(main)).orElseThrow();
        assertTrue(mainState.members().stream().anyMatch(member ->
                member.kind() == GeneratedMemberKind.STATE_BINDING_FIELD));
        String dependencyStateDescriptor = "L"
                + plan.moduleStates().get(dependency).replace('.', '/') + ";";
        GeneratedMemberPlan importField = mainState.members().stream()
                .filter(member -> member.kind() == GeneratedMemberKind.STATE_IMPORT_FIELD)
                .findFirst().orElseThrow();
        assertEquals(dependencyStateDescriptor, importField.descriptor());
        assertTrue(importField.isPrivate());
        assertFalse(importField.isFinal(),
                "import state references are linked after SCC shells are allocated");
        assertTrue(mainState.members().stream().anyMatch(member ->
                member.kind() == GeneratedMemberKind.STATE_IMPORT_LINK
                        && member.descriptor().equals("(" + dependencyStateDescriptor + ")V")
                        && member.visibility() == GeneratedMemberVisibility.PACKAGE));
        assertTrue(mainState.dependencies().stream().anyMatch(dependencyEdge ->
                dependencyEdge.kind() == GeneratedDependencyKind.MODULE_IMPORT_LINKAGE
                        && !dependencyEdge.orderingRequired()));
        GeneratedClassPlan mainClosure = plan.classes().stream()
                .filter(classPlan -> classPlan.kind() == GeneratedClassKind.CLOSURE
                        && classPlan.moduleId().filter(main::equals).isPresent())
                .findFirst().orElseThrow();
        assertTrue(mainClosure.dependencies().stream().anyMatch(dependencyEdge ->
                dependencyEdge.kind() == GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE
                        && !dependencyEdge.orderingRequired()));
        assertEquals(2, plan.initializationOrder().size(),
                "semantic initialization order is retained separately from class emission order");
        assertEquals(dependency, plan.initializationOrder().getFirst());
        assertThrows(IllegalArgumentException.class, () -> new GeneratedTypePlan(
                plan.basePackage(), plan.typeNames(), plan.classes(), plan.tupleClasses(),
                plan.functionInterfaces(), plan.closureClasses(), plan.cellClasses(),
                plan.moduleStates(), plan.moduleFacades(), plan.exports(),
                List.of(main, dependency)));
    }

    @Test
    void recursiveImportSccsPlanExplicitWritableShellLinks() {
        ModuleId a = ModuleId.path("phase14-cycle-a.lyra");
        ModuleId b = ModuleId.path("phase14-cycle-b.lyra");
        LogicalModuleId aLogical = LogicalModuleId.parse("a");
        LogicalModuleId bLogical = LogicalModuleId.parse("b");
        ModuleGraph.Node aNode = node(a,
                "import b->{g} let @pub f :Fn<;I32> = (=> | | ::g[])", aLogical);
        ModuleGraph.Node bNode = node(b,
                "import a->{f} let @pub g :Fn<;I32> = (=> | | ::f[])", bLogical);
        ModuleGraph graph = new ModuleGraph(a, List.of(aNode, bNode), List.of(
                new ModuleGraph.Edge(a, bLogical, b,
                        aNode.program().imports().getFirst().path().span()),
                new ModuleGraph.Edge(b, aLogical, a,
                        bNode.program().imports().getFirst().path().span())),
                Map.of(aLogical, a, bLogical, b));

        TypedIr ir = phaseSuccess(TypedIrBuilder.lower(typedGraph(graph)));
        assertTrue(ir.functionLinkage().sccs().stream().anyMatch(component ->
                component.recursive() && component.declarations().size() == 2));
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        assertEquals(2, plan.moduleStates().size());
        for (ModuleId module : List.of(a, b)) {
            GeneratedClassPlan state = plan.classPlan(plan.moduleStates().get(module)).orElseThrow();
            GeneratedMemberPlan field = state.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.STATE_IMPORT_FIELD)
                    .findFirst().orElseThrow();
            GeneratedMemberPlan link = state.members().stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.STATE_IMPORT_LINK)
                    .findFirst().orElseThrow();
            assertTrue(field.isPrivate());
            assertFalse(field.isFinal());
            assertEquals("(" + field.descriptor() + ")V", link.descriptor());
            assertTrue(link.name().startsWith("$lyra$link$binding$"));
            assertEquals(GeneratedMemberVisibility.PACKAGE, link.visibility());
            assertTrue(state.members().stream().anyMatch(member ->
                    member.kind() == GeneratedMemberKind.STATE_CONSTRUCTOR
                            && member.descriptor().equals("(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V")));
            assertTrue(state.dependencies().stream().anyMatch(dependency ->
                    dependency.kind() == GeneratedDependencyKind.MODULE_IMPORT_LINKAGE
                            && !dependency.orderingRequired()));
        }
        JvmAbiParity.require(ir, plan);
    }

    @Test
    void reExportsRetainTheirExactReachableOriginIdentity() {
        ModuleId main = ModuleId.path("phase14-reexport-main.lyra");
        ModuleId dependency = ModuleId.path("phase14-reexport-dependency.lyra");
        ModuleGraph.Node mainNode = node(main,
                "import @pub dependency->{value run as execute}", LogicalModuleId.parse("main"));
        ModuleGraph.Node dependencyNode = node(dependency,
                "let @pub value :I32 = 1 let @pub run :Fn<;I32> = (=> | | 1)",
                LogicalModuleId.parse("dependency"));
        ModuleGraph graph = new ModuleGraph(main,
                List.of(mainNode, dependencyNode),
                List.of(new ModuleGraph.Edge(main, LogicalModuleId.parse("dependency"), dependency,
                        mainNode.program().imports().getFirst().path().span())),
                Map.of(LogicalModuleId.parse("main"), main,
                        LogicalModuleId.parse("dependency"), dependency));

        GeneratedTypePlan plan = GeneratedTypePlanner.plan(
                phaseSuccess(TypedIrBuilder.lower(typedGraph(graph))));
        GeneratedExportPlan origin = plan.exports().stream()
                .filter(export -> export.moduleId().equals(dependency)
                        && export.sourceName().equals("value")).findFirst().orElseThrow();
        GeneratedExportPlan reExport = plan.exports().stream()
                .filter(export -> export.moduleId().equals(main)
                        && export.sourceName().equals("value")).findFirst().orElseThrow();
        assertTrue(reExport.reExport());
        assertEquals(origin.exportId(), reExport.originExportId());
        assertEquals(origin.declarationId(), reExport.originDeclaration());
        assertEquals("value", origin.javaName());
        assertEquals("value", reExport.javaName());

        GeneratedExportPlan callableOrigin = plan.exports().stream()
                .filter(export -> export.moduleId().equals(dependency)
                        && export.sourceName().equals("run")).findFirst().orElseThrow();
        GeneratedExportPlan callableReExport = plan.exports().stream()
                .filter(export -> export.moduleId().equals(main)
                        && export.sourceName().equals("execute")).findFirst().orElseThrow();
        assertTrue(callableReExport.reExport());
        assertEquals(callableOrigin.exportId(), callableReExport.originExportId());
        assertEquals(callableOrigin.semanticExportId(),
                callableReExport.originExportId().semanticExportId());
    }

    @Test
    void exportedMutableValuesHaveOnlyTheExactTypedGetterAndSetterMembers() {
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(
                ir("let @pub @mut @nil value :I32 = #NIL"));
        GeneratedExportPlan value = plan.exports().getFirst();
        assertFalse(value.isFunction());
        assertEquals("get$value", value.getterName().orElseThrow());
        assertEquals("set$value", value.setterName().orElseThrow());
        assertEquals("()Ljava/lang/Integer;", value.getter().orElseThrow().descriptor());
        assertEquals("(Ljava/lang/Integer;)V", value.setter().orElseThrow().descriptor());
        assertTrue(value.getter().orElseThrow().isPublic());
        assertTrue(value.setter().orElseThrow().isPublic());
        assertEquals(2, value.members().size());

        GeneratedClassPlan state = plan.classPlan(
                plan.moduleStates().values().iterator().next()).orElseThrow();
        List<GeneratedMemberPlan> stateAccessors = state.members().stream()
                .filter(member -> member.kind() == GeneratedMemberKind.STATE_COMPONENT_GET
                        || member.kind() == GeneratedMemberKind.STATE_COMPONENT_SET)
                .toList();
        assertEquals(List.of("()Z", "()I", "(ZI)V"),
                stateAccessors.stream().map(GeneratedMemberPlan::descriptor).toList());
        assertTrue(stateAccessors.stream().allMatch(member ->
                member.visibility() == GeneratedMemberVisibility.PACKAGE));
    }

    @Test
    void nilableFunctionExportsKeepInvocationAndNullableFunctionValueContractsSeparate() {
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(
                ir("let @pub @nil computed :Fn<;I32> = #NIL"));
        GeneratedExportPlan computed = plan.exports().getFirst();
        assertTrue(computed.isFunction());
        assertEquals("()I", computed.invocation().orElseThrow().descriptor());
        assertTrue(computed.functionValueGetter().orElseThrow().descriptor()
                .startsWith("()Llyra/generated/$lyra$fn$"));
        assertEquals(JvmMaterializationKind.NULLABLE_REFERENCE,
                computed.functionValueGetter().orElseThrow().valueType().orElseThrow()
                        .materialization().kind());
        assertEquals("@nilFn<;I32>", computed.valueType().canonicalLyraType());
        assertEquals("@nilFn<;I32>", computed.exportId().canonicalContract());
    }

    @Test
    void callableAbiIdentityIncludesNullableFunctionValueContract() {
        ModuleId module = ModuleId.path("nullable-callable.lyra");
        LyraSignature signature = LyraSignature.of(List.of(), PrimitiveType.I32);
        io.mindspice.lyra.compiler.identity.ExportId semantic =
                io.mindspice.lyra.compiler.identity.ExportId.of(module, "callable", signature);
        JvmExportId nonNil = new JvmExportId(module, "callable", signature.canonicalSpelling(),
                Optional.of(semantic));
        JvmExportId nil = new JvmExportId(module, "callable", "@nil" + signature.canonicalSpelling(),
                Optional.of(semantic));

        assertFalse(nonNil.equals(nil));
        assertFalse(nonNil.hash().equals(nil.hash()));
        assertFalse(nonNil.canonicalInput().equals(nil.canonicalInput()));
        assertEquals(Optional.of(semantic), nil.semanticExportId());
    }

    @Test
    void JavaExportNamesUseEscapingReservedNamesAndStableCollisionSuffixes() {
        JvmExportId first = new JvmExportId(ModuleId.path("a.lyra"), "value", "I32");
        JvmExportId second = new JvmExportId(ModuleId.path("b.lyra"), "value", "I32");
        JvmExportId reserved = functionExportId(ModuleId.path("c.lyra"), "close");
        JvmJavaNamePlan names = JvmJavaNamePlan.plan(List.of(second, reserved, first));
        assertEquals("value", names.nameFor(first));
        assertEquals("value", names.nameFor(second),
                "separate facade classes have separate member collision domains");
        JvmExportId sameFacadeCollision = new JvmExportId(
                ModuleId.path("a.lyra"), "value", "String");
        JvmJavaNamePlan collided = JvmJavaNamePlan.plan(List.of(first, sameFacadeCollision));
        assertTrue(collided.nameFor(sameFacadeCollision).startsWith(
                "value$" + sameFacadeCollision.hash().substring(0, 8)));
        assertEquals("invoke$close", names.invocationNameFor(reserved));
        JvmExportId keyword = new JvmExportId(ModuleId.path("d.lyra"), "class", "I32");
        JvmExportId restrictedKeyword = new JvmExportId(ModuleId.path("d2.lyra"), "module", "I32");
        JvmExportId objectMethod = functionExportId(ModuleId.path("e.lyra"), "equals");
        JvmJavaNamePlan keywordNames = JvmJavaNamePlan.plan(List.of(keyword, restrictedKeyword, objectMethod));
        assertEquals("lyra$class", keywordNames.nameFor(keyword));
        assertEquals("lyra$module", keywordNames.nameFor(restrictedKeyword));
        assertEquals("invoke$equals", keywordNames.invocationNameFor(objectMethod));
        assertThrows(IllegalArgumentException.class,
                () -> JvmJavaNamePlan.plan(List.of(first, first)));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmJavaNamePlan(Map.of(first, "class")));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmJavaNamePlan(Map.of(
                        functionExportId(ModuleId.path("forged-name.lyra"), "ordinary"),
                        "equals")));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmJavaNamePlan(Map.of(
                        functionExportId(ModuleId.path("member-collision.lyra"), "first"), "first",
                        functionExportId(ModuleId.path("member-collision.lyra"), "second"), "set$first")));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmExportId(ModuleId.path("missing-semantic.lyra"),
                        "callable", "Fn<;Unit>"));
    }

    @Test
    void malformedMemberAndFacadeShapesAreRejectedBeforeClassEmission() {
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedMemberPlan.rawMethod(GeneratedMemberKind.FACTORY,
                        "$lyra$create", "()V", false));
        JvmTypePlan internal = new JvmAbiMapper().map(PrimitiveType.I32,
                JvmMappingContext.INTERNAL_VALUE);
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedMemberPlan.valueMethod(GeneratedMemberKind.VALUE_GETTER,
                        "get$value", internal, false, Optional.of("id"),
                        Optional.of("value")));

        GeneratedTypePlan valid = GeneratedTypePlanner.plan(ir("let @pub value :I32 = 1"));
        GeneratedClassPlan facade = valid.classes().stream()
                .filter(value -> value.kind() == GeneratedClassKind.MODULE_FACADE)
                .findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratedClassPlan(facade.binaryName(), facade.kind(), facade.stableKey(),
                        facade.moduleId(), facade.finalClass(), facade.functionalInterface(),
                        List.of(), facade.annotations(), facade.dependencies(), facade.members()));
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratedClassPlan(facade.binaryName(), facade.kind(), facade.stableKey(),
                        Optional.empty(), facade.finalClass(), facade.functionalInterface(),
                        facade.interfaces(), facade.annotations(), facade.dependencies(), facade.members()));

        String targetStateDescriptor = "Llyra/generated/$lyra$state$target;";
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratedClassPlan("lyra.generated.$lyra$state$unlinked",
                        GeneratedClassKind.MODULE_STATE, "state:unlinked",
                        Optional.of(ModuleId.path("unlinked.lyra")), true, false,
                        List.of(), List.of(), List.of(), List.of(
                        GeneratedMemberPlan.rawField(GeneratedMemberKind.STATE_IMPORT_FIELD,
                                "$lyra$binding$1", targetStateDescriptor),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_COMPONENT_GET,
                                "$lyra$get$binding$1", "()" + targetStateDescriptor, false),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CONSTRUCTOR,
                                "<init>", "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false))));

        String functionInterface = "lyra.generated.$lyra$fn$forged";
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratedClassPlan("lyra.generated.$lyra$closure$forged",
                        GeneratedClassKind.CLOSURE, "closure:forged",
                        Optional.of(ModuleId.path("forged.lyra")), true, false,
                        List.of(functionInterface), List.of(), List.of(), List.of(
                        GeneratedMemberPlan.rawField(GeneratedMemberKind.CLOSURE_AUTHORITY_FIELD,
                                "$lyra$authority",
                                "Lio/mindspice/lyra/runtime/LyraClosureAuthority;"),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CLOSURE_CONSTRUCTOR,
                                "<init>", "()V", false),
                        GeneratedMemberPlan.method(GeneratedMemberKind.CLOSURE_INVOKE,
                                "invoke", new JvmAbiMapper().mapSignature(
                                        LyraSignature.of(List.of(), PrimitiveType.UNIT),
                                        JvmAbiBoundary.JAVA_VISIBLE), false,
                                Optional.empty(), Optional.empty()))));
    }

    @Test
    void abiParityRejectsClosureDependenciesThatDivergeFromTypedIr() {
        TypedIr ir = ir("let @pub first :Fn<I32;I32> = (=> |value| value) "
                + "let @pub second :Fn<String;String> = (=> |value| value)");
        GeneratedTypePlan valid = GeneratedTypePlanner.plan(ir);
        GeneratedClassPlan first = valid.classPlan(valid.closureClasses().values().stream()
                .filter(name -> valid.classPlan(name).orElseThrow().interfaces().contains(
                        valid.functionInterfaces().get("Fn<I32;I32>")))
                .findFirst().orElseThrow()).orElseThrow();
        String secondName = valid.closureClasses().values().stream()
                .filter(name -> !name.equals(first.binaryName())).findFirst().orElseThrow();
        ArrayList<GeneratedClassDependency> dependencies = new ArrayList<>(first.dependencies());
        dependencies.add(new GeneratedClassDependency(secondName,
                GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE, false,
                "forged extra linkage"));
        GeneratedClassPlan forgedFirst = new GeneratedClassPlan(
                first.binaryName(), first.kind(), first.stableKey(), first.moduleId(),
                first.finalClass(), first.functionalInterface(), first.interfaces(),
                first.annotations(), dependencies, first.members());
        GeneratedTypePlan forged = new GeneratedTypePlan(
                valid.basePackage(), valid.typeNames(), valid.classes().stream()
                .map(value -> value.equals(first) ? forgedFirst : value).toList(),
                valid.tupleClasses(), valid.functionInterfaces(), valid.closureClasses(),
                valid.cellClasses(), valid.moduleStates(), valid.moduleFacades(), valid.exports(),
                valid.initializationOrder());
        JvmIrParity parity = JvmAbiParity.compare(ir, forged);
        assertFalse(parity.matches());
        assertTrue(parity.differences().stream().anyMatch(value ->
                value.contains("generated dependencies differ")));
    }

    @Test
    void abiParityRejectsModuleStateStorageThatDivergesFromTypedIr() {
        TypedIr ir = ir("let @pub @mut @nil value :I32 = #NIL");
        GeneratedTypePlan valid = GeneratedTypePlanner.plan(ir);
        GeneratedClassPlan state = valid.classPlan(
                valid.moduleStates().values().iterator().next()).orElseThrow();
        GeneratedClassPlan emptyState = new GeneratedClassPlan(
                state.binaryName(), state.kind(), state.stableKey(), state.moduleId(),
                state.finalClass(), state.functionalInterface(), state.interfaces(),
                state.annotations(), state.dependencies(), state.members().stream()
                .filter(member -> member.kind() == GeneratedMemberKind.STATE_CONSTRUCTOR).toList());
        List<GeneratedClassPlan> classes = valid.classes().stream()
                .map(value -> value.equals(state) ? emptyState : value).toList();
        GeneratedTypePlan forged = new GeneratedTypePlan(
                valid.basePackage(), valid.typeNames(), classes, valid.tupleClasses(),
                valid.functionInterfaces(), valid.closureClasses(), valid.cellClasses(),
                valid.moduleStates(), valid.moduleFacades(), valid.exports(),
                valid.initializationOrder());

        JvmIrParity parity = JvmAbiParity.compare(ir, forged);
        assertFalse(parity.matches());
        assertTrue(parity.differences().stream().anyMatch(value ->
                value.contains("module-state storage identities differ")));
        assertThrows(IllegalArgumentException.class, parity::requireMatch);
    }

    @Test
    void aggregatePlanRejectsFacadeMembersThatDivergeFromExportPlans() {
        GeneratedTypePlan valid = GeneratedTypePlanner.plan(ir("let @pub value :I32 = 1"));
        GeneratedClassPlan facade = valid.classPlan(
                valid.moduleFacades().values().iterator().next()).orElseThrow();
        GeneratedClassPlan missingGetter = new GeneratedClassPlan(
                facade.binaryName(), facade.kind(), facade.stableKey(), facade.moduleId(),
                facade.finalClass(), facade.functionalInterface(), facade.interfaces(),
                facade.annotations(), facade.dependencies(), facade.members().stream()
                .filter(member -> member.kind() != GeneratedMemberKind.VALUE_GETTER).toList());
        List<GeneratedClassPlan> classes = valid.classes().stream()
                .map(value -> value.equals(facade) ? missingGetter : value).toList();

        assertThrows(IllegalArgumentException.class, () -> new GeneratedTypePlan(
                valid.basePackage(), valid.typeNames(), classes, valid.tupleClasses(),
                valid.functionInterfaces(), valid.closureClasses(), valid.cellClasses(),
                valid.moduleStates(), valid.moduleFacades(), valid.exports(),
                valid.initializationOrder()));
    }

    @Test
    void generatedClassNamesStayInsideTheInfrastructureNamespace() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedClassPlan(
                "custom.generated.NotLyra", GeneratedClassKind.MODULE_STATE, "state",
                Optional.of(ModuleId.path("not-lyra.lyra")), true, false,
                List.of(), List.of(), List.of(), List.of(
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CONSTRUCTOR,
                                "<init>", "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false))));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedClassPlan(
                "custom.generated.$lyra$tuple$wrong", GeneratedClassKind.MODULE_STATE, "state",
                Optional.of(ModuleId.path("wrong-role.lyra")), true, false,
                List.of(), List.of(), List.of(), List.of(
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CONSTRUCTOR,
                                "<init>", "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false))));
    }

    @Test
    void moduleStateStoresOnlyModuleRootBindings() {
        TypedIr ir = ir("let root :I32 = 1 "
                + "let @pub apply :Fn<I32;I32> = (=> |parameter| "
                + "{ let local :I32 = parameter local })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        io.mindspice.lyra.compiler.ir.IrModule module = ir.modules().getFirst();
        GeneratedClassPlan state = plan.classPlan(
                plan.moduleStates().get(module.moduleId())).orElseThrow();

        java.util.Set<String> expectedFields = ir.declarations().stream()
                .filter(declaration -> declaration.moduleId().equals(module.moduleId())
                        && declaration.scopeId().equals(module.state().rootScope()))
                .map(declaration -> "$lyra$binding$" + declaration.id().value())
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> actualFields = state.members().stream()
                .filter(GeneratedMemberPlan::isField)
                .filter(member -> member.kind() != GeneratedMemberKind.STATE_LIFECYCLE_FIELD)
                .map(GeneratedMemberPlan::name)
                .map(name -> name.replaceFirst("\\$(present|payload)$", ""))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(expectedFields, actualFields);
        assertTrue(ir.declarations().stream().anyMatch(declaration ->
                !declaration.scopeId().equals(module.state().rootScope())
                        && !actualFields.contains("$lyra$binding$" + declaration.id().value())));
    }

    @Test
    void moduleStateStorageUsesCanonicalDeclarationComponentNames() {
        JvmTypePlan value = new JvmAbiMapper().map(
                PrimitiveType.I32, JvmMappingContext.INTERNAL_BINDING);
        assertThrows(IllegalArgumentException.class, () -> new GeneratedClassPlan(
                "custom.generated.$lyra$state$forged", GeneratedClassKind.MODULE_STATE,
                "state:forged", Optional.of(ModuleId.path("forged.lyra")), true, false,
                List.of(), List.of(), List.of(), List.of(
                        GeneratedMemberPlan.field(GeneratedMemberKind.STATE_BINDING_FIELD,
                                "$lyra$binding$1$payload", value, "I", 0,
                                Optional.empty(), Optional.of("value")),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_COMPONENT_GET,
                                "$lyra$get$binding$1", "()I", false),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CONSTRUCTOR,
                                "<init>", "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false))));
    }

    @Test
    void phase14BackendTypesDoNotExposeACompilerBackendSpi() {
        List<Class<?>> implementationTypes = List.of(
                GeneratedTypePlanner.class, GeneratedTypePlan.class, GeneratedClassPlan.class,
                GeneratedMemberPlan.class, JvmAbiMapper.class, JvmTypePlan.class,
                JvmSignaturePlan.class, JvmJavaNamePlan.class, JvmTypeNameTable.class);
        assertTrue(implementationTypes.stream().noneMatch(type ->
                Modifier.isPublic(type.getModifiers())));
        assertFalse(Modifier.isPublic(JvmDescriptors.MethodDescriptor.class.getModifiers()),
                "descriptor parse results must not expose a public backend SPI");
    }

    @Test
    void unvalidatedIrCannotCrossThePlannerPublicationGate() {
        TypedSemanticGraph typed = typed("let value :I32 = 1");
        TypedIr valid = phaseSuccess(TypedIrBuilder.lower(typed));
        TypedIr candidate = new TypedIr(typed, valid.modules());
        assertFalse(candidate.isValidated());
        assertThrows(IllegalStateException.class, () -> GeneratedTypePlanner.plan(candidate));
    }

    @Test
    void generatedValueStorageCannotOmitItsImmutableTypeMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedClassPlan(
                "custom.generated.$lyra$tuple$raw", GeneratedClassKind.TUPLE_VALUE, "tuple:Tuple<I32>",
                Optional.empty(), true, false, List.of(), List.of(), List.of(), List.of(
                        GeneratedMemberPlan.rawField(GeneratedMemberKind.TUPLE_FIELD,
                                "$lyra$0", "I"),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.TUPLE_COMPONENT_GET,
                                "$lyra$get$0", "()I", false),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.TUPLE_CONSTRUCTOR,
                                "<init>", "(I)V", false))));

        assertThrows(IllegalArgumentException.class, () -> new GeneratedClassPlan(
                "custom.generated.$lyra$cell$raw", GeneratedClassKind.CELL, "cell:raw",
                Optional.of(ModuleId.path("raw.lyra")), true, false, List.of(), List.of(), List.of(), List.of(
                        GeneratedMemberPlan.rawField(GeneratedMemberKind.CELL_VALUE_FIELD,
                                "$lyra$value", "I"),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_CONSTRUCTOR,
                                "<init>", "(I)V", false),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_GET,
                                "$lyra$get", "()I", false),
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.CELL_SET,
                                "$lyra$set", "(I)V", false))));
    }

    @Test
    void classIndexesMustRetainTheIdentityEncodedByEachGeneratedClass() {
        GeneratedTypePlan valid = GeneratedTypePlanner.plan(
                ir("let @pub value :Tuple<I32> = Tuple[1]"));
        String tupleName = valid.tupleClasses().values().iterator().next();
        Map<String, String> wrongTupleNames = Map.of("Tuple<String>", tupleName);
        JvmTypeNameTable wrongTable = JvmTypeNameTable.of(
                valid.basePackage(), wrongTupleNames, valid.functionInterfaces());
        assertThrows(IllegalArgumentException.class, () -> new GeneratedTypePlan(
                valid.basePackage(), wrongTable, valid.classes(), wrongTupleNames,
                valid.functionInterfaces(), valid.closureClasses(), valid.cellClasses(),
                valid.moduleStates(), valid.moduleFacades(), valid.exports(),
                valid.initializationOrder()));
    }

    @Test
    void splitImmutableCapturesArePlannedBeforeClosureConstruction() {
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir(
                "let @nil value :I32 = #NIL "
                        + "let @pub read :Fn<;@nil I32> = (=> | | value)"));
        GeneratedClassPlan closure = plan.classPlan(plan.closureClasses().values().iterator().next())
                .orElseThrow();
        assertEquals(2, closure.members().stream().filter(member ->
                member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PRESENCE_FIELD
                        || member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PAYLOAD_FIELD).count());
    }

    @Test
    void closureInvocationMustMatchItsPlannedFunctionalInterface() {
        GeneratedTypePlan valid = GeneratedTypePlanner.plan(ir(
                "let @pub first :Fn<I32;I32> = (=> |value| value) "
                        + "let @pub second :Fn<String;String> = (=> |value| value)"));
        String firstInterface = valid.functionInterfaces().get("Fn<I32;I32>");
        String secondInterface = valid.functionInterfaces().get("Fn<String;String>");
        GeneratedClassPlan firstClosure = valid.classPlan(valid.closureClasses().values().stream()
                .filter(name -> valid.classPlan(name).orElseThrow().interfaces().contains(firstInterface))
                .findFirst().orElseThrow()).orElseThrow();
        ArrayList<GeneratedClassDependency> dependencies = new ArrayList<>(firstClosure.dependencies());
        dependencies.add(new GeneratedClassDependency(secondInterface,
                GeneratedDependencyKind.CLOSURE_FUNCTION_INTERFACE, true,
                "forged interface dependency"));
        GeneratedClassPlan forgedClosure = new GeneratedClassPlan(
                firstClosure.binaryName(), firstClosure.kind(), firstClosure.stableKey(),
                firstClosure.moduleId(), firstClosure.finalClass(), firstClosure.functionalInterface(),
                List.of(secondInterface), firstClosure.annotations(), dependencies, firstClosure.members());
        List<GeneratedClassPlan> forgedClasses = valid.classes().stream()
                .map(value -> value.equals(firstClosure) ? forgedClosure : value).toList();
        assertThrows(IllegalArgumentException.class, () -> new GeneratedTypePlan(
                valid.basePackage(), valid.typeNames(), forgedClasses, valid.tupleClasses(),
                valid.functionInterfaces(), valid.closureClasses(), valid.cellClasses(),
                valid.moduleStates(), valid.moduleFacades(), valid.exports(),
                valid.initializationOrder()));
    }

    private static void assertBefore(
            GeneratedTypePlan plan, GeneratedClassKind earlier, GeneratedClassKind later) {
        int first = plan.classes().stream().map(GeneratedClassPlan::kind).toList().indexOf(earlier);
        int second = plan.classes().stream().map(GeneratedClassPlan::kind).toList().indexOf(later);
        assertTrue(first >= 0 && second >= 0 && first < second,
                earlier + " must precede " + later);
    }

    private static JvmExportId functionExportId(ModuleId moduleId, String name) {
        LyraSignature signature = LyraSignature.of(List.of(), PrimitiveType.UNIT);
        return new JvmExportId(moduleId, name, signature.canonicalSpelling(),
                Optional.of(io.mindspice.lyra.compiler.identity.ExportId.of(
                        moduleId, name, signature)));
    }

    private static GeneratedClassPlan raw(String name) {
        return new GeneratedClassPlan(name, GeneratedClassKind.MODULE_STATE, name,
                Optional.of(ModuleId.path(name + ".lyra")), true, false,
                List.of(), List.of(), List.of(), List.of(
                        GeneratedMemberPlan.rawMethod(GeneratedMemberKind.STATE_CONSTRUCTOR,
                                "<init>",
                                "(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V", false)));
    }

    private static TypedIr ir(String source) {
        return phaseSuccess(TypedIrBuilder.lower(typed(source)));
    }

    private static TypedSemanticGraph typed(String source) {
        ModuleId module = ModuleId.path("phase14.lyra");
        SourceSnapshot snapshot = (SourceSnapshot) phaseSuccess(SourceSnapshot.capture(
                module.sourceId(), PhysicalSourceKey.uri(URI.create("memory:phase14.lyra")),
                source.getBytes(StandardCharsets.UTF_8)));
        SyntaxProgram syntax = phaseSuccess(parse(snapshot));
        ModuleGraph graph = new ModuleGraph(module, List.of(new ModuleGraph.Node(
                module, Optional.empty(), snapshot, syntax, ModuleRevision.compute(snapshot))),
                List.of(), Map.of());
        return typedGraph(graph);
    }

    private static TypedSemanticGraph typedGraph(ModuleGraph graph) {
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(graph);
        assertTrue(resolved instanceof PhaseResult.Success<?>,
                () -> "resolution failed: " + resolved.diagnostics());
        return phaseSuccess(TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value()));
    }

    private static ModuleGraph.Node node(
            ModuleId module, String source, LogicalModuleId logical) {
        SourceSnapshot snapshot = (SourceSnapshot) phaseSuccess(SourceSnapshot.capture(
                module.sourceId(), PhysicalSourceKey.uri(URI.create("memory:" + module.value())),
                source.getBytes(StandardCharsets.UTF_8)));
        return new ModuleGraph.Node(module, Optional.of(logical), snapshot,
                phaseSuccess(parse(snapshot)), ModuleRevision.compute(snapshot));
    }

    private static PhaseResult<SyntaxProgram> parse(SourceSnapshot snapshot) {
        LexedSource lexed = phaseSuccess(Lexer.lex(snapshot));
        GrammarProgram grammar = phaseSuccess(GrammarMatcher.match(lexed));
        return Parser.parse(lexed, grammar);
    }

    private static <T extends io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact>
    T phaseSuccess(PhaseResult<T> result) {
        assertTrue(result instanceof PhaseResult.Success<?>,
                () -> "phase failed: " + result.diagnostics());
        return ((PhaseResult.Success<T>) result).value();
    }
}
