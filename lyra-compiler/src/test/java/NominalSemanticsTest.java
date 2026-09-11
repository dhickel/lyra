import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.source.*;
import io.mindspice.lyra.compiler.types.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class NominalSemanticsTest {
    @Test
    void typesDefaultAndRequiredStructConstruction() {
        var typed = success(io.mindspice.lyra.compiler.semantic.TypeChecker.check(success(resolve("""
                struct Point { let @mut x :I32 let y :I64 = 2 }
                let p :Point = Point[1]
                p:.x
                """))));
        assertEquals(1, typed.expressions().stream().filter(value -> value.kind()
                == io.mindspice.lyra.compiler.semantic.TypedExpressionKind.CONSTRUCTION).count());
        assertTrue(typed.expressions().stream().anyMatch(value -> value.nominalInitialization().isPresent()));
        success(io.mindspice.lyra.compiler.ir.TypedIrBuilder.build(typed));
    }

    @Test
    void rejectsIncompleteAndDuplicateClassInitialization() {
        for (String source : List.of(
                "class Bad { let x :I32 }",
                "class Bad { let x :I32 = self:.x }",
                "class Bad { let x :I32 = 1 Bad = (=> || { self:.x := 2 }) }",
                "class Bad { let x :I32 Bad = (=> |flag :Bool| { (flag -> { self:.x := 1 }) }) }")) {
            var result = io.mindspice.lyra.compiler.semantic.TypeChecker.check(success(resolve(source)));
            assertInstanceOf(PhaseResult.Failure.class, result, source);
            assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals(
                            io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.TYPE_INVALID_BINDING)),
                    () -> source + "\n" + result.diagnostics());
        }
    }

    @Test
    void typesClassConstructorAndBoundMethodSelection() {
        var typed = success(io.mindspice.lyra.compiler.semantic.TypeChecker.check(success(resolve("""
                class Counter {
                    let @mut value :I32
                    Counter = (=> |start :I32| { self:.value := start })
                    let @pub current :Fn<;I32> = (=> || self:.value)
                    let @pub increment :Fn<;Unit> = (=> || { self:.value := (++ self:.value) })
                }
                let counter :Counter = Counter[0]
                counter::increment[]
                counter::current[]
                """))));
        success(io.mindspice.lyra.compiler.ir.TypedIrBuilder.build(typed));
    }

    @Test
    void savedCallableRetainsSelectedSlotWhileAliasesSeeReplacement() {
        var typed = success(io.mindspice.lyra.compiler.semantic.TypeChecker.check(success(resolve("""
                class Cell { let @pub @mut read :Fn<;I32> = (=> || 1) }
                let @mut cell :Cell = Cell[]
                let alias :Cell = cell
                let saved :Fn<;I32> = cell:.read
                cell:.read := (=> || 2)
                let selected :Fn<;I32> = alias:.read
                """))));
        var saved = typed.declarations().stream().filter(value -> value.name().equals("saved")).findFirst().orElseThrow();
        var selected = typed.declarations().stream().filter(value -> value.name().equals("selected")).findFirst().orElseThrow();
        var savedValue = typed.semanticFlowFacts().declarationValues().get(saved.id());
        var selectedValue = typed.semanticFlowFacts().declarationValues().get(selected.id());
        var originalLambda = typed.lambdas().stream().min(java.util.Comparator.comparingInt(value -> value.span().startOffset())).orElseThrow();
        var replacementLambda = typed.lambdas().stream().max(java.util.Comparator.comparingInt(value -> value.span().startOffset())).orElseThrow();
        assertEquals(originalLambda.id(), savedValue.alternatives().getFirst().callableFlows().getFirst().lambdaId().orElseThrow());
        assertEquals(replacementLambda.id(), selectedValue.alternatives().getFirst().callableFlows().getFirst().lambdaId().orElseThrow());
        success(io.mindspice.lyra.compiler.ir.TypedIrBuilder.build(typed));
    }

    @Test
    void initializationRejectsPrematurePublicationAndInvocation() {
        for (String body : List.of(
                "let leaked = ::consume[self] self:.x := 1",
                "let callback :Fn<;Box> = (=> || self) let leaked = ::publish[callback] self:.x := 1",
                "let value = self::read[] self:.x := value",
                "::iter[(0..1:1) || { self:.x := 1 }]")) {
            String source = """
                    let consume :Fn<Box;Unit> = (=> |box| {})
                    let publish :Fn<Fn<;Box>;Unit> = (=> |callback| {})
                    class Box {
                        let @mut x :I32
                        let @pub read :Fn<;I32> = (=> || self:.x)
                        Box = (=> || { %s })
                    }
                    """.formatted(body);
            var result = io.mindspice.lyra.compiler.semantic.TypeChecker.check(success(resolve(source)));
            assertInstanceOf(PhaseResult.Failure.class, result, source);
            assertEquals(io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                    result.diagnostics().getFirst().code(), () -> result.diagnostics().toString());
        }
    }

    @Test
    void seededInitializationChecksUseIndependentBranchSetModel() {
        int cases = Integer.getInteger("lyra.nominal.initialization.cases", Integer.getInteger("lyra.fuzz.cases", 36));
        for (long seed : new long[] { 37, 971, 20260912 }) {
            Random random = new Random(seed);
            for (int test = 0; test < cases; test++) {
                int fields = 1 + random.nextInt(6);
                int full = (1 << fields) - 1;
                int defaults = random.nextInt(full + 1);
                int left = random.nextInt(full + 1);
                int right = random.nextInt(full + 1);
                boolean expected = (defaults | (left & right)) == full
                        && (defaults & (left | right)) == 0;
                StringBuilder source = new StringBuilder("class Box { ");
                for (int field = 0; field < fields; field++) {
                    source.append("let f").append(field).append(" :I32");
                    if ((defaults & (1 << field)) != 0) source.append(" = ").append(field);
                    source.append(' ');
                }
                source.append("Box = (=> |flag :Bool| { (flag -> { ");
                for (int field = 0; field < fields; field++) {
                    if ((left & (1 << field)) != 0) source.append("self:.f").append(field).append(" := ").append(field).append(' ');
                }
                source.append("} : { ");
                for (int field = 0; field < fields; field++) {
                    if ((right & (1 << field)) != 0) source.append("self:.f").append(field).append(" := ").append(field).append(' ');
                }
                source.append("}) }) } let box :Box = Box[#T]");
                var result = io.mindspice.lyra.compiler.semantic.TypeChecker.check(success(resolve(source.toString())));
                assertEquals(expected, result instanceof PhaseResult.Success<?>,
                        "seed=" + seed + " case=" + test + " source=" + source + " diagnostics=" + result.diagnostics());
                if (result instanceof PhaseResult.Success<?> success) {
                    success(io.mindspice.lyra.compiler.ir.TypedIrBuilder.build(
                            (io.mindspice.lyra.compiler.semantic.TypedSemanticGraph) success.value()));
                } else assertEquals(io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                        result.diagnostics().getFirst().code());
            }
        }
    }

    @Test
    void nominalNamesAreNotInstancesAndCapitalizedArraysRemainIndexable() {
        var invalid = io.mindspice.lyra.compiler.semantic.TypeChecker.check(success(resolve(
                "struct Point {} let notAnInstance = Point")));
        assertInstanceOf(PhaseResult.Failure.class, invalid);
        assertEquals(io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.TYPE_INVALID_ACCESS,
                invalid.diagnostics().getFirst().code());
        success(io.mindspice.lyra.compiler.ir.TypedIrBuilder.build(success(
                io.mindspice.lyra.compiler.semantic.TypeChecker.check(success(resolve(
                        "let Point = Array[1 2] let first = Point[0]"))))));
    }

    @Test
    void requiredArgumentsAreInstalledBeforeSourceOrderedDefaults() {
        var typed = success(io.mindspice.lyra.compiler.semantic.TypeChecker.check(success(resolve("""
                struct Point { let first :I32 = self:.required let required :I32 let next :I32 = (+ self:.first 1) }
                let point :Point = Point[4]
                """))));
        success(io.mindspice.lyra.compiler.ir.TypedIrBuilder.build(typed));
        var invalid = io.mindspice.lyra.compiler.semantic.TypeChecker.check(success(resolve(
                "struct Point { let first :I32 = self:.next let next :I32 = 1 }")));
        assertInstanceOf(PhaseResult.Failure.class, invalid);
        assertEquals(io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                invalid.diagnostics().getFirst().code());
    }

    @Test
    void collectsExactSchemasAndRequiredFieldConstructorOrder() {
        var graph = success(resolve("""
                struct Point { let @mut x :I32 let y :I64 let label :String = "p" }
                let p :Point = Point[1 2]
                p:.x
                """));
        var schema = graph.nominals().getFirst().schema();
        assertEquals(NominalSchema.Kind.STRUCT, schema.kind());
        assertEquals(List.of(LyraType.I32, LyraType.I64), schema.constructorParameters());
        assertEquals(List.of("x", "y", "label"), schema.members().stream().map(NominalSchema.Member::name).toList());
        assertTrue(schema.members().stream().allMatch(NominalSchema.Member::publicAccess));
        assertEquals(BindingMutability.MUTABLE, schema.members().getFirst().mutability());
        assertThrows(UnsupportedOperationException.class, () -> graph.nominals().clear());
    }

    @Test
    void resolvesClassSelfCapturesAndPrivateMethods() {
        var graph = success(resolve("""
                class Counter {
                    let @mut value :I32
                    Counter = (=> |start :I32| { self:.value := start })
                    let @pub current :Fn<;I32> = (=> || self:.value)
                    let @pub increment :Fn<;Unit> = (=> || { self:.value := (++ self:.value) })
                }
                let counter :Counter = Counter[0]
                counter::increment[]
                counter::current[]
                """));
        var nominal = graph.nominals().getFirst();
        assertEquals(List.of(LyraType.I32), nominal.schema().constructorParameters());
        assertTrue(nominal.constructor().isPresent());
        assertFalse(nominal.schema().members().getFirst().publicAccess());
        assertTrue(graph.captures().stream().anyMatch(capture -> capture.declarationId().equals(nominal.self())));
    }

    @Test
    void rejectsPrivateAccessImmutableSlotAndInvalidConstructorArity() {
        for (String source : List.of(
                "class Hidden { let x :I32 = 1 } let h :Hidden = Hidden[] h:.x",
                "struct Point { let x :I32 } let @mut p :Point = Point[1] p:.x := 2",
                "struct Point { let x :I32 } Point[]",
                "struct Point { let x :I32 } Point[1 2]",
                "struct Bad { let callback :Array<Fn<;Unit>> }",
                "struct Bad { let next :@nil Bad let callback :Fn<;I32> }",
                "class Bad { let x :I32 = 1 let x :I64 = 2 }")) {
            assertInstanceOf(PhaseResult.Failure.class, resolve(source), source);
        }
    }

    @Test
    void recursiveDataAllowsClassReferencesWithoutTraversingPrivateMethods() {
        var graph = success(resolve("""
                struct Node { let @nil next :Node let target :Target }
                class Target { let f :Fn<;I32> = (=> || 1) }
                """));
        assertEquals(2, graph.nominalTypes().schemas().size());
    }

    @Test
    void supportsInferredReceiversAndKeepsRedeclarationsNominal() {
        var graph = success(resolve("""
                struct Point { let @mut x :I32 }
                let @mut first = Point[1]
                first:.x := 2
                struct Point { let @mut x :I32 }
                let second = Point[3]
                second:.x
                """));
        assertEquals(2, graph.nominals().size());
        assertNotEquals(graph.nominals().getFirst().schema().type(), graph.nominals().getLast().schema().type());
        assertFalse(TypeRules.canImplicitlyConvert(graph.nominals().getFirst().schema().type(),
                graph.nominals().getLast().schema().type()));
        for (String source : List.of("struct @pub Point {} struct Point {}",
                "let @pub Point :I32 = 1 struct Point {}", "struct @pub Point {} let Point = 1")) {
            assertInstanceOf(PhaseResult.Failure.class, resolve(source), source);
        }
    }

    @Test
    void qualifiedAndSelectiveTypeImportsRetainOriginIdentity() {
        var library = module("model.lyra", "struct @pub Point { let x :I32 } class Hidden {}");
        for (String source : List.of("import model as m let p :m->Point = m->:.Point[1] p:.x",
                "import model->{Point as P} let p :P = P[1] p:.x")) {
            var main = module("main.lyra", source);
            var logical = LogicalModuleId.parse("model");
            var edges = main.program().imports().stream().map(value ->
                    new ModuleGraph.Edge(main.moduleId(), logical, library.moduleId(), value.path().span())).toList();
            var graph = success(SemanticResolver.resolve(new ModuleGraph(main.moduleId(), List.of(main, library), edges,
                    java.util.Map.of(logical, library.moduleId()))));
            var point = graph.nominals().stream().filter(value -> value.schema().type().id().name().equals("Point"))
                    .findFirst().orElseThrow();
            assertEquals(library.moduleId(), point.schema().type().id().module().moduleId());
            assertEquals(library.revision(), point.schema().type().id().revision());
        }
    }

    @Test
    void generatedSchemasMatchIndependentFieldAndConstructorModel() {
        int cases = Integer.getInteger("lyra.fuzz.cases", 100);
        for (long seed : new long[]{19, 83, 421, 20260911}) {
            Random random = new Random(seed);
            for (int sample = 0; sample < cases; sample++) {
                int fields = random.nextInt(9);
                boolean struct = random.nextBoolean();
                StringBuilder source = new StringBuilder(struct ? "struct Sample {" : "class Sample {");
                List<String> expectedNames = new ArrayList<>();
                List<LyraType> expectedParameters = new ArrayList<>();
                List<Boolean> expectedVisibility = new ArrayList<>();
                List<BindingMutability> expectedMutability = new ArrayList<>();
                for (int index = 0; index < fields; index++) {
                    boolean mutable = random.nextBoolean();
                    boolean pub = random.nextBoolean();
                    boolean initialized = !struct || random.nextBoolean();
                    String name = "field" + index;
                    expectedNames.add(name);
                    expectedVisibility.add(struct || pub);
                    expectedMutability.add(mutable ? BindingMutability.MUTABLE : BindingMutability.IMMUTABLE);
                    if (!initialized) expectedParameters.add(LyraType.I32);
                    source.append(" let ").append(mutable ? "@mut " : "").append(pub ? "@pub " : "")
                            .append(name).append(" :I32").append(initialized ? " = 7" : "");
                }
                source.append(" }");
                var schema = success(resolve(source.toString())).nominals().getFirst().schema();
                assertEquals(expectedNames, schema.members().stream().map(NominalSchema.Member::name).toList());
                assertEquals(expectedParameters, schema.constructorParameters());
                assertEquals(expectedVisibility, schema.members().stream().map(NominalSchema.Member::publicAccess).toList());
                assertEquals(expectedMutability, schema.members().stream().map(NominalSchema.Member::mutability).toList());
            }
        }
    }

    private static PhaseResult<ResolvedSemanticGraph> resolve(String source) {
        var node = module("nominal.lyra", source);
        return SemanticResolver.resolve(new ModuleGraph(node.moduleId(), List.of(node), List.of()));
    }

    private static ModuleGraph.Node module(String name, String source) {
        var id = ModuleId.of(name);
        var snapshot = success(SourceSnapshot.capture(id.sourceId(), PhysicalSourceKey.uri(URI.create("memory:" + name)),
                source.getBytes(StandardCharsets.UTF_8)));
        var lexed = success(Lexer.lex(snapshot));
        var syntax = success(Parser.parse(lexed, success(GrammarMatcher.match(lexed))));
        return new ModuleGraph.Node(id, Optional.empty(), snapshot, syntax, ModuleRevision.compute(snapshot));
    }

    private static <T extends io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact> T success(PhaseResult<T> result) {
        assertInstanceOf(PhaseResult.Success.class, result, () -> result.diagnostics().toString());
        return ((PhaseResult.Success<T>) result).value();
    }
}
