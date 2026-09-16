import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.ir.IrValidator;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.TypedDeclaration;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedModule;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Retained nominal nilable member-read contract derivation (issue #8).
 *
 * <p>Nilable nominal member reads must derive their contracts independently at
 * typed semantic provenance sealing and IR validation from the exact resolved
 * schema member, never from the published typed expression alone. Valid
 * same-generation annotation, coalesce, predicate narrowing and value match
 * consumers seal and lower without reaching {@code LYC-IR-003}; nilable
 * receivers, mismatched annotations/contracts, forged member links and
 * invented match narrowing stay structured failures.</p>
 */
public class NilableMemberReadContractTest {

    @Test
    void nilableMemberReadConsumersSealAndLowerInOneGeneration() {
        for (String source : List.of(
                // Explicit annotation over a #NIL member read.
                """
                class Box { @pub @nil n :I32 = #NIL }
                let box :Box = :Box[]
                let v :@nil I32 = box:.n
                """,
                // Annotation widening over a non-nil member value.
                """
                class Box { @pub n :I32 = 4 }
                let box :Box = :Box[]
                let v :@nil I32 = box:.n
                """,
                // Coalesce consumes the independently derived nilable contract.
                """
                class Box { @pub @nil n :I32 = #NIL }
                let box :Box = :Box[]
                let v :I32 = (box:.n : 0)
                """,
                // Predicate narrowing consumes the independently derived contract.
                """
                class Box { @pub @nil n :I32 = 5I32 }
                let box :Box = :Box[]
                let v :I32 = (box:.n narrowed -> narrowed : 0)
                """,
                // Value match uses only the legal #NIL equality on a nilable subject.
                """
                class Box { @pub @nil n :I32 = #NIL }
                let box :Box = :Box[]
                let v :I32 = (match box:.n #NIL -> 1 _ -> 0)
                """,
                // Value match over a non-nilable member read keeps its value equality.
                """
                class Box { @pub n :I32 = 4 }
                let box :Box = :Box[]
                let v :I32 = (match box:.n 4 -> 1 _ -> 0)
                """,
                // Nilable member read inside a structural literal/self read.
                """
                class Box {
                    @pub @nil n :I32 = #NIL
                    @pub copy :@nil I32 = self:.n
                }
                let box :Box = :Box[]
                let t :Tuple<@nil I32> = Tuple[box:.copy]
                let v :@nil I32 = box:.n
                """)) {
            TypedSemanticGraph typed = success(source);
            TypedIr ir = phaseSuccess(TypedIrBuilder.lower(typed));
            assertTrue(IrValidator.isValid(ir), "valid nilable member read reached IR diagnostics: " + source);
            assertEquals(List.of(), IrValidator.validate(ir).stream()
                    .filter(diagnostic -> diagnostic.code().value().equals("LYC-IR-003"))
                    .toList(), "valid nilable member read reached LYC-IR-003: " + source);
        }
    }

    @Test
    void nilableMemberReadNegativesStayStructuredSourceFailures() {
        for (String[] negative : List.of(
                new String[] { """
                        class Box { @pub @nil n :I32 = #NIL }
                        let @nil box :Box = #NIL
                        let v :@nil I32 = box:.n
                        """, "LYC-TYPE-013", "nilable values must be narrowed before member access" },
                new String[] { """
                        class Box { @pub @nil n :I32 = #NIL }
                        let box :Box = :Box[]
                        let v :I32 = box:.n
                        """, "LYC-TYPE-001", "cannot use @nilI32 where I32 is required" },
                new String[] { """
                        class Box { @pub n :I32 = 4 }
                        let box :Box = :Box[]
                        let v :I32 = (box:.n : 0)
                        """, "LYC-TYPE-005", "nil coalescing requires an @nil value" },
                new String[] { """
                        class Box { @pub @nil n :I32 = #NIL }
                        let box :Box = :Box[]
                        let v :I32 = (match box:.n 1 -> 1 _ -> 0)
                        """, "LYC-TYPE-005", "nilable match subjects may only use #NIL equality before narrowing" },
                new String[] { """
                        class Box { @pub @nil n :I32 = #NIL }
                        let box :Box = :Box[]
                        let v :@nil I32 = box:.missing
                        """, "LYC-RESOLVE-013", "unknown member: missing" })) {
            assertSourceFailure(negative[0], negative[1], negative[2]);
        }
    }

    @Test
    void sealingRejectsForgedMemberContractTypes() {
        // The member read sits inside an inferred coalesce, so no declaration
        // contract constrains it contextually: the exact-schema-slot derivation
        // is the rejecting proof.
        TypedSemanticGraph original = success("""
                class Box { @pub @nil n :I32 = #NIL }
                let box :Box = :Box[]
                let v = (box:.n : 0)
                """);
        TypedExpression access = memberAccess(original);
        TypedExpression forged = new TypedExpression(
                access.kind(), access.span(),
                io.mindspice.lyra.compiler.types.PrimitiveType.I64.nilable(),
                access.children(), access.literal(), access.link(), access.conversion(),
                access.operator(), access.memberName(), access.tupleIndex(), access.declarationId(),
                access.lambdaId(), access.scopeId(), access.signature(), access.captureIds(),
                access.predicateBinding());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> sealWithMemberAccess(original, forged));
        assertTrue(failure.getMessage().contains("nominal member does not match its exact schema slot"),
                "sealing accepted a forged member contract: " + failure.getMessage());
    }

    @Test
    void irValidationRejectsForgedMemberLinksAndContracts() {
        TypedIr ir = phaseSuccess(TypedIrBuilder.lower(success("""
                class Box { @pub @nil n :I32 = #NIL }
                let box :Box = :Box[]
                let v :@nil I32 = box:.n
                """)));
        assertTrue(IrValidator.isValid(ir), "canonical nilable member read is invalid");
        io.mindspice.lyra.compiler.ir.IrNode.Access access = memberAccessNode(ir);

        // Forged non-nilable member contract type: the exact schema slot is @nil I32.
        io.mindspice.lyra.compiler.ir.IrNode.Access wrongType = new io.mindspice.lyra.compiler.ir.IrNode.Access(
                access.span(), io.mindspice.lyra.compiler.types.PrimitiveType.I32,
                access.accessKind(), access.receiver(), access.referenceId(), access.declarationId(),
                access.moduleId(), access.exportId(), access.memberName(), access.tupleIndex(),
                access.siteId());
        check(hasIrDiagnostic(withInitializer(ir, wrongType), "LYC-IR-003"),
                "validator accepts a member read whose contract differs from its schema slot");

        // Forged member name: no exact schema slot carries the invented name.
        io.mindspice.lyra.compiler.ir.IrNode.Access wrongName = new io.mindspice.lyra.compiler.ir.IrNode.Access(
                access.span(), access.type(), access.accessKind(), access.receiver(),
                access.referenceId(), access.declarationId(), access.moduleId(), access.exportId(),
                Optional.of("other"), access.tupleIndex(), access.siteId());
        check(hasIrDiagnostic(withInitializer(ir, wrongName), "LYC-IR-003"),
                "validator accepts a member read with a forged member name");

        // Forged declaration link: the field identity belongs to another member.
        io.mindspice.lyra.compiler.ir.IrNode.Declaration box =
                (io.mindspice.lyra.compiler.ir.IrNode.Declaration) ir.modules().getFirst()
                        .body().forms().get(1);
        io.mindspice.lyra.compiler.ir.IrNode.Access forgedLink =
                new io.mindspice.lyra.compiler.ir.IrNode.Access(
                        access.span(), access.type(), access.accessKind(), access.receiver(),
                        access.referenceId(), box.declarationId(), access.moduleId(),
                        access.exportId(), access.memberName(), access.tupleIndex(), access.siteId());
        check(hasIrDiagnostic(withInitializer(ir, forgedLink), "LYC-IR-003"),
                "validator accepts a member read with a forged declaration link");
    }

    @Test
    void forgedNilContractAnnotationsFailBeforeIr() {
        // A non-nil annotation consuming a nilable member read is an ordinary
        // type mismatch; it never reaches IR validation or LYC-IR-003.
        PhaseResult<?> typed = TypeChecker.check(success(resolve("""
                class Box { @pub @nil n :I32 = #NIL }
                let box :Box = :Box[]
                let v :I32 = box:.n
                """)));
        assertInstanceOf(PhaseResult.Failure.class, typed);
        assertEquals(CompilerDiagnosticCodes.TYPE_MISMATCH, typed.diagnostics().getFirst().code());
    }

    // ----- helpers -----

    private static void assertSourceFailure(String source, String code, String summaryFragment) {
        PhaseResult<?> resolved = resolve(source);
        if (resolved instanceof PhaseResult.Failure<?> failure) {
            assertEquals(code, failure.diagnostics().getFirst().code().value(), source);
            assertTrue(failure.diagnostics().getFirst().summary().contains(summaryFragment),
                    "unexpected summary: " + failure.diagnostics().getFirst().summary());
            return;
        }
        PhaseResult<?> typed = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value());
        assertInstanceOf(PhaseResult.Failure.class, typed, "expected structured failure for " + source);
        assertEquals(code, typed.diagnostics().getFirst().code().value(), source);
        assertTrue(typed.diagnostics().getFirst().summary().contains(summaryFragment),
                "unexpected summary: " + typed.diagnostics().getFirst().summary());
    }

    private static TypedExpression memberAccess(TypedSemanticGraph typed) {
        TypedExpression declarationForm = typed.modules().getFirst().forms().getLast();
        TypedExpression initializer = declarationForm.children().getFirst();
        TypedExpression value = initializer.kind() == TypedExpressionKind.COALESCE
                ? initializer.children().getFirst() : initializer;
        assertEquals(TypedExpressionKind.MEMBER_ACCESS, value.kind(),
                "expected the last declaration initializer to contain a member read");
        return value;
    }

    private static TypedSemanticGraph sealWithMemberAccess(
            TypedSemanticGraph original, TypedExpression forgedAccess) {
        TypedModule module = original.modules().getFirst();
        TypedExpression declarationForm = module.forms().getLast();
        TypedExpression originalInitializer = declarationForm.children().getFirst();
        TypedExpression forgedInitializer = originalInitializer.kind() == TypedExpressionKind.COALESCE
                ? new TypedExpression(originalInitializer.kind(), originalInitializer.span(),
                originalInitializer.type(), List.of(forgedAccess, originalInitializer.children().get(1)),
                originalInitializer.literal(), originalInitializer.link(),
                originalInitializer.conversion(), originalInitializer.operator(),
                originalInitializer.memberName(), originalInitializer.tupleIndex(),
                originalInitializer.declarationId(), originalInitializer.lambdaId(),
                originalInitializer.scopeId(), originalInitializer.signature(),
                originalInitializer.captureIds(), originalInitializer.predicateBinding())
                : forgedAccess;
        TypedExpression forgedForm = new TypedExpression(
                declarationForm.kind(), declarationForm.span(), declarationForm.type(),
                List.of(forgedInitializer), declarationForm.literal(), declarationForm.link(),
                declarationForm.conversion(), declarationForm.operator(), declarationForm.memberName(),
                declarationForm.tupleIndex(), declarationForm.declarationId(), declarationForm.lambdaId(),
                declarationForm.scopeId(), declarationForm.signature(), declarationForm.captureIds(),
                declarationForm.predicateBinding());
        List<TypedExpression> forms = new ArrayList<>(module.forms());
        forms.set(forms.size() - 1, forgedForm);
        TypedModule altered = new TypedModule(
                module.moduleId(), module.rootScope(), module.span(), forms);
        List<TypedDeclaration> declarations = new ArrayList<>(original.declarations());
        DeclarationId id = declarationForm.declarationId().orElseThrow();
        declarations.replaceAll(declaration -> declaration.id().equals(id)
                ? new TypedDeclaration(declaration.id(), declaration.name(), declaration.span(),
                declaration.moduleId(), declaration.kind(), declaration.contract(),
                Optional.of(forgedInitializer), declaration.initializerLambda())
                : declaration);
        List<TypedExpression> expressions = new ArrayList<>();
        List<io.mindspice.lyra.compiler.semantic.TypedConversion> conversions = new ArrayList<>();
        Map<SourceSpan, List<TypedExpression>> bySpan = new LinkedHashMap<>();
        for (TypedExpression form : forms) {
            collect(form, expressions, conversions, bySpan);
        }
        return io.mindspice.lyra.compiler.semantic.SemanticTestSupport.seal(
                original, original.resolvedGraph(), List.of(altered), declarations,
                original.references(), original.lambdas(), conversions, expressions,
                original.contractsByDeclaration(), bySpan, original.mutations(),
                original.semanticFlowFacts(), original.initializationPlan(),
                io.mindspice.lyra.compiler.semantic.TypedFailureSite.fromExpressions(expressions));
    }

    private static void collect(
            TypedExpression expression,
            List<TypedExpression> expressions,
            List<io.mindspice.lyra.compiler.semantic.TypedConversion> conversions,
            Map<SourceSpan, List<TypedExpression>> bySpan) {
        expressions.add(expression);
        expression.conversion().ifPresent(conversions::add);
        bySpan.computeIfAbsent(expression.span(), ignored -> new ArrayList<>()).add(expression);
        expression.children().forEach(child -> collect(child, expressions, conversions, bySpan));
    }

    private static io.mindspice.lyra.compiler.ir.IrNode.Access memberAccessNode(TypedIr ir) {
        io.mindspice.lyra.compiler.ir.IrNode.Declaration declaration =
                (io.mindspice.lyra.compiler.ir.IrNode.Declaration)
                        ir.modules().getFirst().body().forms().getLast();
        return (io.mindspice.lyra.compiler.ir.IrNode.Access) declaration.initializer();
    }

    private static TypedIr withInitializer(TypedIr ir, io.mindspice.lyra.compiler.ir.IrNode initializer) {
        io.mindspice.lyra.compiler.ir.IrModule original = ir.modules().getFirst();
        List<io.mindspice.lyra.compiler.ir.IrNode> forms =
                new ArrayList<>(original.body().forms());
        io.mindspice.lyra.compiler.ir.IrNode.Declaration declaration =
                (io.mindspice.lyra.compiler.ir.IrNode.Declaration) forms.getLast();
        forms.set(forms.size() - 1, new io.mindspice.lyra.compiler.ir.IrNode.Declaration(
                declaration.span(), declaration.type(), declaration.declarationId(),
                declaration.declarationKind(), declaration.contract(), initializer));
        return new TypedIr(ir.semanticGraph(), List.of(new io.mindspice.lyra.compiler.ir.IrModule(
                original.moduleId(), original.rootScope(), original.span(),
                new io.mindspice.lyra.compiler.ir.IrNode.Sequence(
                        original.body().span(), original.body().type(), forms))));
    }

    private static boolean hasIrDiagnostic(TypedIr ir, String code) {
        return IrValidator.validate(ir).stream()
                .anyMatch(diagnostic -> diagnostic.code().value().equals(code));
    }

    private static TypedSemanticGraph success(String source) {
        PhaseResult<TypedSemanticGraph> typed = TypeChecker.check(success(resolve(source)));
        if (!(typed instanceof PhaseResult.Success<?> typedSuccess)) {
            throw new AssertionError("type checking failed: " + typed.diagnostics());
        }
        return ((PhaseResult.Success<TypedSemanticGraph>) typedSuccess).value();
    }

    private static TypedIr phaseSuccess(PhaseResult<TypedIr> result) {
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("IR lowering failed: " + result.diagnostics());
        }
        return ((PhaseResult.Success<TypedIr>) success).value();
    }

    private static PhaseResult<ResolvedSemanticGraph> resolve(String source) {
        var node = module("nominal.lyra", source);
        return SemanticResolver.resolve(new ModuleGraph(node.moduleId(), List.of(node), List.of()));
    }

    private static ModuleGraph.Node module(String name, String source) {
        var id = ModuleId.of(name);
        var snapshot = success(SourceSnapshot.capture(id.sourceId(),
                PhysicalSourceKey.uri(URI.create("memory:" + name)),
                source.getBytes(StandardCharsets.UTF_8)));
        var lexed = success(Lexer.lex(snapshot));
        var syntax = success(Parser.parse(lexed, success(GrammarMatcher.match(lexed))));
        return new ModuleGraph.Node(id, Optional.empty(), snapshot, syntax,
                ModuleRevision.compute(snapshot));
    }

    private static <T extends io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact> T success(
            PhaseResult<T> result) {
        if (result instanceof PhaseResult.Success<T> success) {
            return success.value();
        }
        throw new AssertionError(result.diagnostics().toString());
    }

    private static void check(boolean condition, String message) {
        assertTrue(condition, message);
    }
}
