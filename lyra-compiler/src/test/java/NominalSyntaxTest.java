import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.DescriptorMetadata;
import io.mindspice.lyra.compiler.grammar.GrammarDescriptor;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.grammar.ProductionKind;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.lex.ModifierKind;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Syntax/replay assertions only; executable nominal support remains a separate gate. */
public class NominalSyntaxTest {
    @Test
    void existingValueIndexArityAndUnknownTypeFailuresRemainRealDiagnostics() {
        for (String source : List.of("let a = Array<I32>[1 2] a[0,1]",
                "let a = Array<I32>[1 2] a[]", "let a = Array<I32>[1 2] let Upper = a Upper[0,1]")) {
            var result = io.mindspice.lyra.compiler.api.LyraCompiler.compile(
                    io.mindspice.lyra.compiler.api.CompileRequest.source("nominal-negative.lyra", source));
            var failure = assertInstanceOf(io.mindspice.lyra.compiler.api.CompileResult.Failure.class, result);
            assertEquals("LYC-RESOLVE-016", failure.diagnostics().getFirst().code().toString());
        }
        var unknown = io.mindspice.lyra.compiler.api.LyraCompiler.compile(
                io.mindspice.lyra.compiler.api.CompileRequest.source("unknown-type.lyra", "let x :Unknown = 1"));
        var failure = assertInstanceOf(io.mindspice.lyra.compiler.api.CompileResult.Failure.class, unknown);
        assertEquals(io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.RESOLVE_UNRESOLVED_NAME,
                failure.diagnostics().getFirst().code());
    }

    @Test
    void agreedClassAndStructFormsRetainExactRoles() {
        String source = """
                struct @pub Vec2 {
                    let @mut x :F64
                    let @mut y :F64
                    let label :String = "point"
                }
                class Counter {
                    let @mut value :I32
                    Counter = (=> |start :I32| { self:.value := start })
                    let @pub @mut increment :Fn<;Unit> = (=> || {
                        self:.value := (++ self:.value)
                    })
                    let @pub current :Fn<;I32> = (=> || self:.value)
                }
                let point :Vec2 = Vec2[1.0 2.0]
                let counter :Counter = Counter[0]
                counter::increment[]
                let saved :Fn<;Unit> = counter:.increment
                """;
        Parsed parsed = parse(source);
        var struct = assertInstanceOf(SyntaxNode.NominalDeclaration.class, parsed.syntax().forms().get(0));
        assertEquals(SyntaxNode.NominalKind.STRUCT, struct.kind());
        assertEquals("Vec2", struct.name().name());
        assertEquals(ModifierKind.PUBLIC, struct.modifiers().getFirst().kind());
        assertTrue(struct.constructor().isEmpty());
        assertEquals(List.of("x", "y", "label"), struct.members().stream().map(member -> member.name().name()).toList());
        assertTrue(struct.members().get(0).initializer().isEmpty());
        assertTrue(struct.members().get(0).equalsSpan().isEmpty());
        assertEquals("=", slice(source, struct.members().get(2).equalsSpan().orElseThrow()));
        assertEquals("struct", slice(source, struct.keywordSpan()));
        assertEquals("{", slice(source, struct.openingBraceSpan()));
        assertEquals("}", slice(source, struct.closingBraceSpan()));
        assertThrows(UnsupportedOperationException.class, () -> struct.members().clear());

        var clazz = assertInstanceOf(SyntaxNode.NominalDeclaration.class, parsed.syntax().forms().get(1));
        assertEquals(SyntaxNode.NominalKind.CLASS, clazz.kind());
        var constructor = clazz.constructor().orElseThrow();
        assertEquals("Counter", constructor.name().name());
        assertEquals("=", slice(source, constructor.equalsSpan()));
        assertTrue(constructor.span().startOffset() > clazz.members().getFirst().span().startOffset());
        assertTrue(constructor.span().endOffset() < clazz.members().get(1).span().startOffset());
        assertEquals(3, clazz.members().size());
        assertEquals(List.of(ModifierKind.PUBLIC, ModifierKind.MUTABLE), clazz.members().get(1)
                .modifiers().stream().map(SyntaxNode.Modifier::kind).toList());
        var point = assertInstanceOf(SyntaxNode.LetBinding.class, parsed.syntax().forms().get(2));
        assertInstanceOf(SyntaxNode.NamedType.class, point.annotation().orElseThrow().type());
        assertEquals(2, assertInstanceOf(SyntaxNode.BracketApplication.class,
                point.initializer()).arguments().expressions().size());
        assertInstanceOf(SyntaxNode.DirectCall.class, parsed.syntax().forms().get(4));
        var saved = assertInstanceOf(SyntaxNode.LetBinding.class, parsed.syntax().forms().get(5));
        assertInstanceOf(SyntaxNode.MemberAccess.class, saved.initializer());
    }

    @Test
    void typeReferencesAndBracketsDoNotGuessNamesFromCapitalization() {
        var syntax = parse("""
                struct Empty {} class Object {}
                let value :pkg->model->Object = pkg->model->:.Object[]
                let items :Array<pkg->model->Object>=Array<pkg->model->Object>[]
                let UppercaseArray = Array<I32>[1]
                UppercaseArray[0]
                Counter[0]
                Empty[]
                lowerAlias[1 2]
                """).syntax();
        var binding = (SyntaxNode.LetBinding) syntax.forms().get(2);
        var named = assertInstanceOf(SyntaxNode.NamedType.class, binding.annotation().orElseThrow().type());
        assertEquals(List.of("pkg", "model", "Object"), named.path().segments().stream()
                .map(SyntaxNode.Identifier::name).toList());
        assertEquals(2, named.path().arrowSpans().size());
        assertInstanceOf(SyntaxNode.IndexAccess.class, syntax.forms().get(5));
        assertInstanceOf(SyntaxNode.IndexAccess.class, syntax.forms().get(6));
        assertInstanceOf(SyntaxNode.BracketApplication.class, syntax.forms().get(7));
        assertInstanceOf(SyntaxNode.BracketApplication.class, syntax.forms().get(8));
    }

    @Test
    void malformedDeclarationsFailWithoutPartialGrammar() {
        for (String source : List.of(
                "struct", "class C {", "struct lower {}", "class @mut C {}", "class @pub @pub C {}",
                "struct @nil C {}", "class C let x :I32 = 0", "class C { let x = 0 }",
                "class C { let x:I32 }", "class C { let x : I32 }", "class C { let @pub @pub x :I32 }",
                "class C { let x :I32 = }", "class C { let f :Fn<;Unit> = || () }",
                "class C { Other = (=> || ()) }", "struct C { C = (=> || ()) }",
                "class C { C = || () }", "class C { C = 1 }", "class C { C := (=> || ()) }",
                "class C { C = (=> || ()) C = (=> || ()) }", "class C { (someFunction) }",
                "class C { class D {} }", "class C :Parent {}", "{ class C {} }", "{ struct S {} }",
                "let x :I32", "let class = 1", "let struct = 1", "class C {} import later",
                "class C { let x :Array<Missing,> }", "class C { let x :pkg-> }",
                "Counter[1,]", "Counter[,1]", "Counter[1 2", "class C { let x :I32, let y :I32 }")) {
            var result = GrammarMatcher.match(lex(source));
            assertInstanceOf(PhaseResult.Failure.class, result, source);
            assertTrue(result.optionalValue().isEmpty(), source);
        }
    }

    @Test
    void declarationMetadataCannotPointAtNestedPunctuation() {
        Parsed parsed = parse("class C { let x :I32 = 1 C = (=> || { let y :I32 = 2 () }) }");
        var declaration = parsed.grammar().root().children().getFirst();
        int nestedLet = parsed.lexed().tokens().stream().filter(token -> token.lexeme().equals("let"))
                .mapToInt(token -> parsed.lexed().tokens().indexOf(token)).reduce((a, b) -> b).orElseThrow();
        assertInvalidRewrite(parsed, ProductionKind.NOMINAL_DECLARATION,
                descriptor -> withMetadata(descriptor, new DescriptorMetadata(
                        descriptor.metadata().openingTokenIndex(), descriptor.metadata().closingTokenIndex(),
                        nestedLet, List.of(), descriptor.metadata().modifierTokenIndices(), List.of())));
        int nestedBrace = parsed.lexed().tokens().stream().filter(token -> token.kind() == TokenKind.LEFT_BRACE)
                .mapToInt(token -> parsed.lexed().tokens().indexOf(token)).reduce((a, b) -> b).orElseThrow();
        assertInvalidRewrite(parsed, ProductionKind.NOMINAL_DECLARATION,
                descriptor -> withMetadata(descriptor, new DescriptorMetadata(nestedBrace,
                        descriptor.metadata().closingTokenIndex(), declaration.startTokenIndex(),
                        List.of(), List.of(), List.of())));
        int nestedEquals = parsed.lexed().tokens().stream().filter(token -> token.kind() == TokenKind.EQUAL)
                .mapToInt(token -> parsed.lexed().tokens().indexOf(token)).reduce((a, b) -> b).orElseThrow();
        assertInvalidRewrite(parsed, ProductionKind.CONSTRUCTOR_DECLARATION,
                descriptor -> withMetadata(descriptor, new DescriptorMetadata(-1, -1,
                        nestedEquals, List.of(), List.of(), List.of())));
        assertInvalidRewrite(parsed, ProductionKind.MEMBER_DECLARATION,
                descriptor -> new GrammarDescriptor(descriptor.kind(), descriptor.startTokenIndex(),
                        descriptor.endTokenIndex(), descriptor.children().subList(0, 1), descriptor.metadata()));
        Parsed named = parse("let value :outer->inner->Type = x");
        assertInvalidRewrite(named, ProductionKind.NAMED_TYPE, descriptor -> {
            int arrow = descriptor.metadata().operatorTokenIndices().getLast();
            return withMetadata(descriptor, new DescriptorMetadata(-1, -1, -1,
                    List.of(), List.of(), List.of(arrow, arrow)));
        });
        Parsed modifiers = parse("class C { let @pub @mut value :I32 = 0 }");
        LexedSource duplicate = lex("class C { let @pub @pub value :I32 = 0 }");
        GrammarProgram forged = new GrammarProgram(duplicate.snapshot().sourceId(),
                duplicate.snapshot().sha256(), duplicate.tokens().size(), modifiers.grammar().root());
        assertThrows(IllegalArgumentException.class, () -> forged.validateAgainst(duplicate));
    }

    @Test
    void seededDeclarationGeneratorChecksIndependentFieldModel() {
        int cases = Integer.getInteger("lyra.fuzz.cases", 240);
        assertTrue(cases >= 110, "nominal grammar fuzz may not be disabled");
        for (long seed : new long[] {1, 24301, 8675309, Long.MAX_VALUE}) {
            Random random = new Random(seed);
            for (int sample = 0; sample < cases; sample++) {
                boolean struct = random.nextBoolean();
                int count = random.nextInt(7);
                String name = "Type" + sample;
                StringBuilder source = new StringBuilder(struct ? "struct " : "class ");
                source.append(name).append(" {\n");
                List<Boolean> initialized = new ArrayList<>();
                List<Boolean> mutable = new ArrayList<>();
                for (int field = 0; field < count; field++) {
                    boolean hasInitializer = random.nextBoolean();
                    boolean hasMutable = random.nextBoolean();
                    initialized.add(hasInitializer);
                    mutable.add(hasMutable);
                    source.append("let ").append(hasMutable ? "@mut " : "")
                            .append("field").append(field).append(" :I32")
                            .append(hasInitializer ? " = " + field : "").append('\n');
                }
                if (!struct) source.append(name).append(" = (=> || ())\n");
                source.append('}');
                String text = source.toString();
                var declaration = (SyntaxNode.NominalDeclaration) parse(text).syntax().forms().getFirst();
                assertEquals(name, declaration.name().name());
                assertEquals(count, declaration.members().size());
                assertEquals(!struct, declaration.constructor().isPresent());
                for (int field = 0; field < count; field++) {
                    var member = declaration.members().get(field);
                    assertEquals("field" + field, member.name().name());
                    assertEquals(initialized.get(field), member.initializer().isPresent());
                    assertEquals(mutable.get(field), member.modifiers().stream()
                            .anyMatch(modifier -> modifier.kind() == ModifierKind.MUTABLE));
                }
                assertInstanceOf(PhaseResult.Failure.class,
                        GrammarMatcher.match(lex(text.substring(0, text.length() - 1))),
                        "truncated declaration, seed=" + seed + ", sample=" + sample);
            }
        }
    }

    private static String slice(String source, io.mindspice.lyra.compiler.source.SourceSpan span) {
        return source.substring(span.startOffset(), span.endOffset());
    }

    private static GrammarDescriptor withMetadata(GrammarDescriptor descriptor, DescriptorMetadata metadata) {
        return new GrammarDescriptor(descriptor.kind(), descriptor.startTokenIndex(), descriptor.endTokenIndex(),
                descriptor.children(), metadata);
    }

    private static void assertInvalidRewrite(Parsed parsed, ProductionKind kind,
                                             UnaryOperator<GrammarDescriptor> mutation) {
        assertThrows(IllegalArgumentException.class, () -> {
            var grammar = parsed.grammar();
            var altered = new GrammarProgram(grammar.sourceId(), grammar.sourceRevision(), grammar.tokenCount(),
                    rewrite(grammar.root(), kind, mutation));
            altered.validateAgainst(parsed.lexed());
        });
    }

    private static GrammarDescriptor rewrite(GrammarDescriptor descriptor, ProductionKind kind,
                                              UnaryOperator<GrammarDescriptor> mutation) {
        if (descriptor.kind() == kind) return mutation.apply(descriptor);
        return new GrammarDescriptor(descriptor.kind(), descriptor.startTokenIndex(), descriptor.endTokenIndex(),
                descriptor.children().stream().map(child -> rewrite(child, kind, mutation)).toList(),
                descriptor.metadata());
    }

    private static Parsed parse(String source) {
        var lexed = lex(source);
        var result = GrammarMatcher.match(lexed);
        assertTrue(result.isSuccess(), () -> source + "\n" + result.diagnostics());
        var grammar = result.optionalValue().orElseThrow();
        var syntax = Parser.parse(lexed, grammar).optionalValue().orElseThrow();
        return new Parsed(lexed, grammar, syntax);
    }

    private static LexedSource lex(String source) {
        var snapshot = SourceSnapshot.capture(SourceId.path("nominal-test.lyra"),
                PhysicalSourceKey.uri(URI.create("memory:nominal-test")), source.getBytes(StandardCharsets.UTF_8))
                .optionalValue().orElseThrow();
        return Lexer.lex(snapshot).optionalValue().orElseThrow();
    }

    private record Parsed(LexedSource lexed, GrammarProgram grammar, SyntaxProgram syntax) {}
}
