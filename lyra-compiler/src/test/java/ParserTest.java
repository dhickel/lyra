import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.ast.SyntaxVisitor;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.DescriptorMetadata;
import io.mindspice.lyra.compiler.grammar.GrammarDescriptor;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.grammar.ProductionKind;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.lex.NumericSuffix;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.parse.ParserInvariantException;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Assertion-grade syntax AST and grammar replay tests. */
public final class ParserTest {
    @Test
    public void testGeneratedReservedWhileBoundaries() {
        var random = new java.util.Random(24301);
        var separators = List.of(" ", "\n", "\t", "\n// boundary\n");
        int cases = Integer.getInteger("lyra.fuzz.cases", 180);
        for (int i = 0; i < cases; i++) {
            String predicate = random.nextBoolean() ? "|| #F" : "(=> :Bool || #F)";
            String action = random.nextBoolean() ? "|| ()" : "(=> :Unit || ())";
            boolean bracket = random.nextBoolean();
            String call = bracket ? "while[" + predicate + " " + action + "]"
                    : "(while " + predicate + " " + action + ")";
            Parsed parsed = parse("let before = 0" + separators.get(random.nextInt(separators.size())) + call);
            check(let(parsed.syntax(), "before").initializer() instanceof SyntaxNode.IntegerLiteral,
                    "while must not attach as a receiver suffix; seed 24301 case " + i);
            check(parsed.syntax().forms().size() == 2, "declaration and loop remain separate forms");
            Object loop = parsed.syntax().forms().getLast();
            if (bracket) {
                var direct = (SyntaxNode.CallableCall) loop;
                check(direct.target() instanceof SyntaxNode.Identifier identifier
                                && identifier.name().equals("while"),
                        "reserved call head retained");
                check(direct.arguments().size() == 2, "both callbacks retained");
            } else {
                check(loop instanceof SyntaxNode.CallableCall, "parenthesized callback call retained");
            }
        }
    }

    @Test
    public void testGeneratedReservedIterBoundaries() {
        var random = new java.util.Random(8675309);
        var separators = List.of(" ", "\n", "\t", "\n// boundary\n");
        int cases = Integer.getInteger("lyra.fuzz.cases", 180);
        for (int i = 0; i < cases; i++) {
            String separator = separators.get(random.nextInt(separators.size()));
            String callback = random.nextBoolean() ? "|| ()" : "|x| ()";
            String range = "(0" + (random.nextBoolean() ? ".." : "...")
                    + random.nextInt(100) + ":1)";
            Parsed parsed = parse("let r = " + range + separator
                    + "iter[r " + callback + "]");
            check(let(parsed.syntax(), "r").initializer() instanceof SyntaxNode.Range,
                    "reserved built-in must never become a receiver suffix; seed 8675309 case " + i);
        }
    }

    @Test
    public void testFirstClassRangeSyntaxRetainsBoundsStepAndEndpointKind() {
        Parsed parsed = parse("let values :Range<I32> = (0..100:1) "
                + "let reverse = (100...0:(- 1)) "
                + "\niter[values |x| iter[reverse || ()]]");
        SyntaxNode.LetBinding values = let(parsed.syntax(), "values");
        SyntaxNode.RangeType type = (SyntaxNode.RangeType) values.annotation().orElseThrow().type();
        check(((SyntaxNode.PrimitiveType) type.elementType()).name().equals("I32"),
                "range element annotation is retained");
        SyntaxNode.Range range = (SyntaxNode.Range) values.initializer();
        check(!range.inclusive(), "double dot range excludes its endpoint");
        check(range.start() instanceof SyntaxNode.IntegerLiteral, "range start is an expression");
        check(range.end() instanceof SyntaxNode.IntegerLiteral, "range end is an expression");
        check(range.step() instanceof SyntaxNode.IntegerLiteral, "range step is an expression");
        SyntaxNode.Range reverse = (SyntaxNode.Range) let(parsed.syntax(), "reverse").initializer();
        check(reverse.inclusive(), "triple dot range includes a reached endpoint");
        check(reverse.step() instanceof SyntaxNode.OperatorSExpression,
                "negative step uses the ordinary negation expression");
    }

    @Test
    public void testParserBuildsCompleteImmutableTree() {
        String source = "import game->math->vector as vec "
                + "import @pub game->math->vector->{length normalize as norm} "
                + "let @pub main :Fn<Array<String>;I32> = (=> |args| "
                + "{ let @mut total :I32 = 0 total := (+ 1, 2 3) "
                + "(#T value -> total : (total : 0)) }) "
                + "let array = Array<@nil String>[#NIL, \"ok\"] "
                + "let tuple = Tuple<I32,String>[1 \"ok\"] "
                + "let conversion = I32[1] "
                + "let index = array[0] "
                + "let direct = receiver::call[1] "
                + "let qualified = vec->::normalize[1] "
                + "let field = vec->:.length "
                + "let bound = receiver:.method "
                + "let called = (receiver:.method 1) "
                + "let compact = (consume |x :I32| x)";
        Parsed parsed = parse(source);
        SyntaxProgram program = parsed.syntax();

        check(program.imports().size() == 2, "both import forms are retained");
        check(program.imports().get(0).alias().orElseThrow().name().equals("vec"),
                "module alias is syntax data");
        SyntaxNode.ImportSelection selection = program.imports().get(1).selection().orElseThrow();
        check(selection.items().size() == 2, "selective import items are retained");
        check(selection.items().get(1).alias().orElseThrow().name().equals("norm"),
                "selective import alias is retained");

        SyntaxNode.LetBinding main = let(program, "main");
        check(main.modifiers().getFirst().kind().name().equals("PUBLIC"),
                "binding modifiers are syntax nodes");
        SyntaxNode.FunctionType mainType = (SyntaxNode.FunctionType)
                main.annotation().orElseThrow().type();
        check(mainType.parameterTypes().size() == 1, "function type parameter is retained");
        check(mainType.parameterTypes().getFirst() instanceof SyntaxNode.ArrayType,
                "composite function type is syntax, not semantic type metadata");
        SyntaxNode.Lambda lambda = (SyntaxNode.Lambda) main.initializer();
        check(lambda.parameters().getFirst().name().name().equals("args"),
                "lambda parameter names are retained");
        check(lambda.body() instanceof SyntaxNode.Block, "lambda body block is retained");
        SyntaxNode.Block block = (SyntaxNode.Block) lambda.body();
        check(block.forms().size() == 3, "block form order is retained");
        check(block.forms().get(1) instanceof SyntaxNode.Reassignment,
                "reassignment is retained inside a block");
        SyntaxNode.Reassignment reassignment = (SyntaxNode.Reassignment) block.forms().get(1);
        check(!reassignment.parenthesized(), "infix assignment enclosure is distinguished");
        check(reassignment.value() instanceof SyntaxNode.OperatorSExpression,
                "operator S-expression is distinct from bracket syntax");
        SyntaxNode.OperatorSExpression operator =
                (SyntaxNode.OperatorSExpression) reassignment.value();
        check(operator.operands().size() == 3 && operator.commaSpans().size() == 1,
                "optional operator commas and all operands are retained");

        SyntaxNode.ArrayLiteral array = (SyntaxNode.ArrayLiteral) let(program, "array").initializer();
        check(array.elements().size() == 2, "array elements are real expression nodes");
        check(array.explicitType().orElseThrow() instanceof SyntaxNode.ArrayType,
                "explicit array type prefix is retained");
        check(array.elements().getFirst() instanceof SyntaxNode.NilLiteral,
                "nil literal is not an array placeholder");
        SyntaxNode.TupleLiteral tuple = (SyntaxNode.TupleLiteral) let(program, "tuple").initializer();
        check(tuple.elements().size() == 2, "tuple elements are real expression nodes");
        check(((SyntaxNode.StringLiteral) tuple.elements().get(1)).decodedValue().equals("ok"),
                "tuple literal values are decoded exactly");
        check(let(program, "conversion").initializer() instanceof SyntaxNode.TypeConversion,
                "type bracket conversion has its own node");
        check(let(program, "index").initializer() instanceof SyntaxNode.IndexAccess,
                "single-expression bracket postfix is indexing");
        check(let(program, "direct").initializer() instanceof SyntaxNode.DirectCall,
                "receiver direct call is not a callable-value call");
        check(let(program, "qualified").initializer() instanceof SyntaxNode.NamespaceDirectCall,
                "namespace direct call preserves qualification");
        check(let(program, "field").initializer() instanceof SyntaxNode.NamespaceMemberAccess,
                "namespace member access preserves value access");
        check(let(program, "bound").initializer() instanceof SyntaxNode.MemberAccess,
                "member value access is distinct from direct call");
        check(let(program, "called").initializer() instanceof SyntaxNode.CallableCall,
                "parenthesized callable call is distinct from direct call");
        SyntaxNode.CallableCall compactCall =
                (SyntaxNode.CallableCall) let(program, "compact").initializer();
        check(compactCall.arguments().getFirst() instanceof SyntaxNode.CompactLambda,
                "compact lambda is retained as an argument");
    }

    @Test
    public void testMatchSurfacesRetainModesArmsAndExactSourceRoles() {
        SyntaxProgram program = parse(
                "let first = (match value 1 when guard -> one _ -> other) "
                        + "let second = match[condition #T -> yes _ -> no] "
                        + "let third = (cond condition -> yes _ -> no) "
                        + "let fourth = (match value game->:.value when game->::ready[] "
                        + "-> yes _ -> no) "
                        + "let fifth = cond[condition -> yes _ -> no]").syntax();
        SyntaxNode.Match traditional = (SyntaxNode.Match) let(program, "first").initializer();
        check(traditional.directAccessorSpan().isEmpty()
                        && traditional.subject() instanceof SyntaxNode.Identifier subject
                        && subject.name().equals("value"),
                "parenthesized match retains its subject and surface");
        check(traditional.arms().size() == 2
                        && traditional.arms().getFirst().guard().isPresent()
                        && traditional.arms().getLast().wildcard(),
                "traditional match retains guarded and final fallback arm roles");
        check(traditional.arms().getFirst().arrowSpan().startOffset()
                        < traditional.arms().getFirst().result().span().startOffset(),
                "match arm source punctuation and result spans remain exact");

        SyntaxNode.Match bracketed = (SyntaxNode.Match) let(program, "second").initializer();
        check(bracketed.directAccessorSpan().isEmpty()
                        && bracketed.subject() instanceof SyntaxNode.Identifier subject
                        && subject.name().equals("condition"),
                "bracket match retains its real subject and surface");
        check(bracketed.arms().getFirst().pattern().isPresent()
                        && bracketed.arms().getLast().wildcard(),
                "bracket match retains ordered pattern and fallback arm roles");
        SyntaxNode.Cond conditional = (SyntaxNode.Cond) let(program, "third").initializer();
        check(conditional.arms().getFirst().pattern().isPresent()
                        && conditional.arms().getLast().wildcard(),
                "cond retains ordered condition and fallback arms without a subject");
        SyntaxNode.Match qualified = (SyntaxNode.Match) let(program, "fourth").initializer();
        check(qualified.arms().getFirst().pattern().orElseThrow()
                        instanceof SyntaxNode.NamespaceMemberAccess
                        && qualified.arms().getFirst().guard().orElseThrow()
                        instanceof SyntaxNode.NamespaceDirectCall,
                "namespace-qualified pattern and guard expressions remain valid match heads");
        SyntaxNode.Cond bracketedCond = (SyntaxNode.Cond) let(program, "fifth").initializer();
        check(bracketedCond.arms().size() == 2
                        && bracketedCond.arms().getFirst().pattern().isPresent()
                        && bracketedCond.arms().getLast().wildcard(),
                "bracket cond retains ordered condition and fallback arms");
    }

    @Test
    public void testBracketMatchArgumentsAndFormsPreserveOrdinaryPostfixCalls() {
        SyntaxProgram program = parse("""
                let lambda = ((=> :I32 |value :I32| value) match[1 1 -> 2 _ -> 0])
                let arguments = (callee 1 match[value #T -> 2 _ -> 0]
                  match[3 3 -> 4 _ -> 0])
                let direct = ::callee[1 match[2 2 -> 3 _ -> 0]]
                let method = (receiver ::method[1] match[2 2 -> 3 _ -> 0])
                let member = match[1 1 -> Tuple[2] _ -> Tuple[3]]:.0
                let forms = { 1 match[2 2 -> 3 _ -> 0] match[value value -> 4 _ -> 0] }
                let branch = (enabled -> match[value #T -> 42 _ -> 0] : 7)
                """).syntax();
        SyntaxNode.CallableCall lambda = (SyntaxNode.CallableCall) let(program, "lambda").initializer();
        check(lambda.target() instanceof SyntaxNode.Lambda
                        && lambda.arguments().getFirst() instanceof SyntaxNode.Match,
                "bracket match is an ordinary expression argument to a lambda");
        SyntaxNode.CallableCall arguments = (SyntaxNode.CallableCall) let(program, "arguments").initializer();
        check(arguments.arguments().size() == 3
                        && arguments.arguments().get(1) instanceof SyntaxNode.Match
                        && arguments.arguments().get(2) instanceof SyntaxNode.Match,
                "adjacent bracket matches remain separate ordered arguments");
        SyntaxNode.DirectCall direct = (SyntaxNode.DirectCall) let(program, "direct").initializer();
        check(direct.receiver().isEmpty() && direct.argumentExpressions().size() == 2
                        && direct.argumentExpressions().getLast() instanceof SyntaxNode.Match,
                "unqualified direct calls retain bracket match arguments after ordinary values");
        SyntaxNode.CallableCall method = (SyntaxNode.CallableCall) let(program, "method").initializer();
        check(method.target() instanceof SyntaxNode.DirectCall call && call.isQualifiedByReceiver()
                        && call.name().name().equals("method")
                        && method.arguments().getFirst() instanceof SyntaxNode.Match,
                "ordinary method postfix still binds to the preceding receiver across whitespace");
        check(let(program, "member").initializer() instanceof SyntaxNode.MemberAccess member
                        && member.receiver() instanceof SyntaxNode.Match,
                "a match result may still have ordinary postfix accessors");
        SyntaxNode.Block forms = (SyntaxNode.Block) let(program, "forms").initializer();
        check(forms.forms().size() == 3 && forms.forms().get(1) instanceof SyntaxNode.Match
                        && forms.forms().getLast() instanceof SyntaxNode.Match,
                "bracket matches also start independent sequential block forms");
        SyntaxNode.Conditional branch = (SyntaxNode.Conditional) let(program, "branch").initializer();
        check(branch.predicate() instanceof SyntaxNode.Identifier
                        && branch.thenBranch() instanceof SyntaxNode.Match,
                "an identifier conditional predicate must not absorb the reserved bracket match result");
    }

    @Test
    public void testFullNamespaceMatchHeadsRetainOnlyRealTerminalArrowSpans() {
        for (String terminal : List.of("", "->")) {
            String qualifier = "game->constants" + terminal;
            Parsed parsed = parse("let result = (match value " + qualifier + ":.value when "
                    + qualifier + "::ready[] -> 42 _ -> 0)");
            SyntaxNode.Match match = (SyntaxNode.Match) let(parsed.syntax(), "result").initializer();
            SyntaxNode.NamespaceMemberAccess member = (SyntaxNode.NamespaceMemberAccess)
                    match.arms().getFirst().pattern().orElseThrow();
            SyntaxNode.NamespaceDirectCall guard = (SyntaxNode.NamespaceDirectCall)
                    match.arms().getFirst().guard().orElseThrow();
            check(member.path().segments().size() == 2 && guard.path().segments().size() == 2,
                    "a complete logical module path survives match-head replay");
            check(member.terminalArrowSpan().isPresent() == !terminal.isEmpty()
                            && guard.terminalArrowSpan().isPresent() == !terminal.isEmpty(),
                    "omitted terminal arrows have no invented source span");
            String source = parsed.lexed().snapshot().text();
            check(source.substring(member.accessorSpan().startOffset(), member.accessorSpan().endOffset()).equals(":.")
                            && source.substring(guard.accessorSpan().startOffset(), guard.accessorSpan().endOffset()).equals("::"),
                    "namespace accessors preserve their exact source punctuation");
            for (var arrow : List.of(member.terminalArrowSpan(), guard.terminalArrowSpan())) {
                arrow.ifPresent(span -> check(source.substring(span.startOffset(), span.endOffset()).equals("->"),
                        "present terminal arrows describe only their own source tokens"));
            }
        }
    }

    @Test
    public void testLiteralsAndTokenDerivedSpansRemainExact() {
        String source = "let truth = #T let nil = #NIL let integer = 42U16 "
                + "let decimal = 1.250F32 let text = \"a\\n\\u0042\" let character = '\\u0041'";
        Parsed parsed = parse(source);
        SyntaxProgram program = parsed.syntax();

        check(((SyntaxNode.BooleanLiteral) let(program, "truth").initializer()).value(),
                "boolean literal value is exact");
        check(let(program, "nil").initializer() instanceof SyntaxNode.NilLiteral,
                "nil literal has a dedicated syntax node");
        SyntaxNode.IntegerLiteral integer =
                (SyntaxNode.IntegerLiteral) let(program, "integer").initializer();
        check(integer.exactValue().equals(BigInteger.valueOf(42)), "integer value is exact");
        check(integer.suffix() == NumericSuffix.U16 && integer.lexeme().equals("42U16"),
                "integer suffix and spelling are retained");
        SyntaxNode.FloatLiteral decimal =
                (SyntaxNode.FloatLiteral) let(program, "decimal").initializer();
        check(decimal.exactValue().compareTo(new BigDecimal("1.250")) == 0,
                "decimal value remains exact");
        check(decimal.suffix() == NumericSuffix.F32 && decimal.lexeme().equals("1.250F32"),
                "decimal suffix and spelling are retained");
        SyntaxNode.StringLiteral text =
                (SyntaxNode.StringLiteral) let(program, "text").initializer();
        check(text.decodedValue().equals("a\nB") && text.lexeme().equals("\"a\\n\\u0042\""),
                "string decoded value and escape spelling are retained");
        SyntaxNode.CharacterLiteral character =
                (SyntaxNode.CharacterLiteral) let(program, "character").initializer();
        check(character.codeUnit() == 'A' && character.lexeme().equals("'\\u0041'"),
                "character code unit and escape spelling are retained");

        for (SyntaxNode.Form form : program.forms()) {
            check(form.span().sourceId().equals(program.sourceId()),
                    "every form span has the program source identity");
            check(form.span().startOffset() < form.span().endOffset(),
                    "every non-empty form has a complete span");
        }
        SyntaxNode.LetBinding truth = let(program, "truth");
        check(parsed.lexed().snapshot().text().substring(
                        truth.initializer().span().startOffset(),
                        truth.initializer().span().endOffset()).equals("#T"),
                "literal span is derived from the exact token range");
    }

    @Test
    public void testUnitAndAggregateSpellingForms() {
        SyntaxProgram program = parse(
                "let parenthesized = () let arrayUnit = Array[] let tupleUnit = Tuple[] "
                        + "let emptyArray = Array<I32>[] let emptyTuple = Tuple<I32>[] "
                        + "let tuple = Tuple[1 2]").syntax();
        check(((SyntaxNode.UnitLiteral) let(program, "parenthesized").initializer()).form()
                        == SyntaxNode.UnitForm.PARENTHESIZED, "() is Unit");
        check(((SyntaxNode.UnitLiteral) let(program, "arrayUnit").initializer()).form()
                        == SyntaxNode.UnitForm.ARRAY, "Array[] is Unit spelling");
        check(((SyntaxNode.UnitLiteral) let(program, "tupleUnit").initializer()).form()
                        == SyntaxNode.UnitForm.TUPLE, "Tuple[] is Unit spelling");
        check(let(program, "emptyArray").initializer() instanceof SyntaxNode.ArrayLiteral,
                "typed Array[] is an empty array, not Unit");
        check(let(program, "emptyTuple").initializer() instanceof SyntaxNode.TupleLiteral,
                "typed Tuple[] is an empty tuple expression");
        check(((SyntaxNode.TupleLiteral) let(program, "tuple").initializer()).elements().size() == 2,
                "tuple carries all element expressions");
    }

    @Test
    public void testCompactLambdaRestrictionAndMalformedDiagnostics() {
        expectGrammarFailure("let value = |x :I32| x", CompilerDiagnosticCodes.PARSE_INVALID_FORM);
        expectGrammarFailure("let value = Array[1,]", CompilerDiagnosticCodes.PARSE_INVALID_COMMA);
        expectGrammarFailure("let value = (foo", CompilerDiagnosticCodes.PARSE_MISSING_DELIMITER);
        check(let(parse("let value = a[0,1]").syntax(), "value").initializer()
                        instanceof SyntaxNode.BracketApplication,
                "non-unary brackets retain arguments for type-versus-value resolution");
        expectGrammarFailure("let value : I32 = 1",
                CompilerDiagnosticCodes.PARSE_INVALID_ANNOTATION_SPACING);
        expectGrammarFailure("let value = (not 1 2)", CompilerDiagnosticCodes.PARSE_INVALID_OPERATOR_ARITY);
        expectGrammarFailure("let bad = (match 1 ?? 1 -> 2 _ -> 3)",
                CompilerDiagnosticCodes.PARSE_OBSOLETE_ARM_MARKER);
        expectGrammarFailure("let bad = (match _ 1 -> 2 _ -> 3)",
                CompilerDiagnosticCodes.PARSE_OBSOLETE_CONDITIONAL_MATCH);
        expectGrammarFailure("let bad = ::match[1 _ -> 2]",
                CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        expectGrammarFailure("let bad = ns->::match[1 _ -> 2]",
                CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        expectGrammarFailure("let match :I32 = 1", CompilerDiagnosticCodes.PARSE_UNEXPECTED_TOKEN);
        expectGrammarFailure("let when :I32 = 1", CompilerDiagnosticCodes.PARSE_UNEXPECTED_TOKEN);

        SyntaxProgram program = parse("let value = (consume |x :I32| x)").syntax();
        SyntaxNode.CallableCall call = (SyntaxNode.CallableCall) let(program, "value").initializer();
        check(call.arguments().getFirst() instanceof SyntaxNode.CompactLambda,
                "compact lambda remains legal where it is an expression argument");
    }

    @Test
    public void testReplayIsDeterministicDeeplyImmutableAndInvariantChecked() {
        String source = "let value :Fn<I32,String;I32> = (=> |x :I32| (String[x]))";
        Parsed first = parse(source);
        Parsed second = parse(source);
        check(first.syntax().equals(second.syntax()), "replay is deterministic");
        check(first.syntax().forms() != second.syntax().forms(),
                "each parse owns its immutable list instance");
        expectThrows(UnsupportedOperationException.class,
                () -> first.syntax().forms().clear());
        expectThrows(UnsupportedOperationException.class,
                () -> let(first.syntax(), "value").modifiers().clear());
        SyntaxNode.FunctionType type = (SyntaxNode.FunctionType)
                let(first.syntax(), "value").annotation().orElseThrow().type();
        expectThrows(UnsupportedOperationException.class,
                () -> type.parameterTypes().clear());
        expectThrows(UnsupportedOperationException.class,
                () -> type.arguments().types().clear());

        GrammarProgram grammar = first.grammar();
        GrammarDescriptor original = grammar.forms().getFirst();
        GrammarDescriptor altered = new GrammarDescriptor(
                ProductionKind.IDENTIFIER,
                original.startTokenIndex(),
                original.endTokenIndex(),
                List.of(),
                DescriptorMetadata.NONE);
        List<GrammarDescriptor> children = new ArrayList<>(grammar.root().children());
        children.set(0, altered);
        GrammarDescriptor alteredRoot = new GrammarDescriptor(
                ProductionKind.PROGRAM,
                grammar.root().startTokenIndex(),
                grammar.root().endTokenIndex(),
                children,
                grammar.root().metadata());
        GrammarProgram alteredProgram = new GrammarProgram(
                grammar.sourceId(), grammar.sourceRevision(), grammar.tokenCount(), alteredRoot);
        expectThrows(ParserInvariantException.class,
                () -> Parser.parse(first.lexed(), alteredProgram));
    }

    @Test
    public void testExhaustiveVisitorDispatchIsAvailableForEveryPublishedNode() {
        SyntaxProgram program = parse(
                "import a->b as c let value :Fn<I32;String> = (=> :String |x :I32| "
                        + "{ let y = (not #F) (y -> \"x\" : \"y\") }) "
                        + "let compact = (use |z :I32| z) let array = Array[1] "
                        + "let tuple = Tuple[1] let conversion = I32[1] "
                        + "let direct = ::f[] let member = x:.field let qualified = n->:.field "
                        + "let qualifiedCall = n->::f[] let indexed = array[0] "
                        + "let bracket = +[1 2] let assignment = (:= x 1) let reassign = (x := 1) "
                        + "let coercion = (value : fallback) let condValue = (cond #T -> 1 _ -> 0) "
                        + "let constructed = :Point[]").syntax();
        CountingVisitor visitor = new CountingVisitor();
        program.accept(visitor);
        for (SyntaxNode.ImportDeclaration declaration : program.imports()) {
            declaration.accept(visitor);
        }
        for (SyntaxNode.Form form : program.forms()) {
            form.accept(visitor);
        }
        check(visitor.count > 0, "visitor dispatch visits the published tree");
        check(visitor.programs == 1 && visitor.imports == 1,
                "program and import visitor methods are dispatched");
        // The visitor below must implement every method in SyntaxVisitor; this
        // assertion guards the public exhaustive surface without a default branch.
    }

    private static SyntaxNode.LetBinding let(SyntaxProgram program, String name) {
        return program.forms().stream()
                .filter(form -> form instanceof SyntaxNode.LetBinding)
                .map(form -> (SyntaxNode.LetBinding) form)
                .filter(binding -> binding.name().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no binding named " + name));
    }

    private static Parsed parse(String source) {
        LexedSource lexed = lex(source);
        PhaseResult<GrammarProgram> grammarResult = GrammarMatcher.match(lexed);
        if (!(grammarResult instanceof PhaseResult.Success<?> grammarSuccess)) {
            throw new AssertionError("grammar failed: " + render(grammarResult));
        }
        GrammarProgram grammar = ((PhaseResult.Success<GrammarProgram>) grammarSuccess).value();
        PhaseResult<SyntaxProgram> parsed = Parser.parse(lexed, grammar);
        if (!(parsed instanceof PhaseResult.Success<?> parsedSuccess)) {
            throw new AssertionError("parser failed: " + render(parsed));
        }
        return new Parsed(
                lexed,
                grammar,
                ((PhaseResult.Success<SyntaxProgram>) parsedSuccess).value());
    }

    private static void expectGrammarFailure(String source,
                                              io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code) {
        LexedSource lexed = lex(source);
        PhaseResult<GrammarProgram> result = GrammarMatcher.match(lexed);
        check(result instanceof PhaseResult.Failure<?>, "expected grammar failure for " + source);
        check(result.optionalValue().isEmpty(), "grammar failure has no partial descriptor tree");
        check(result.diagnostics().getFirst().code().equals(code),
                "unexpected grammar diagnostic: " + render(result));
    }

    @SuppressWarnings("unchecked")
    private static LexedSource lex(String source) {
        PhaseResult<SourceSnapshot> captured = SourceSnapshot.capture(
                SourceId.path("parser-test.lyra"),
                PhysicalSourceKey.uri(URI.create("memory:parser-test")),
                source.getBytes(StandardCharsets.UTF_8));
        if (!(captured instanceof PhaseResult.Success<?> capturedSuccess)) {
            throw new AssertionError("source capture failed: " + render(captured));
        }
        SourceSnapshot snapshot = ((PhaseResult.Success<SourceSnapshot>) capturedSuccess).value();
        PhaseResult<LexedSource> lexed = Lexer.lex(snapshot);
        if (!(lexed instanceof PhaseResult.Success<?> lexedSuccess)) {
            throw new AssertionError("lexing failed: " + render(lexed));
        }
        return ((PhaseResult.Success<LexedSource>) lexedSuccess).value();
    }

    private static String render(PhaseResult<?> result) {
        return result.diagnostics().stream().map(Diagnostic::render).toList().toString();
    }

    private static void expectThrows(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Parsed(LexedSource lexed, GrammarProgram grammar, SyntaxProgram syntax) {
    }

    private static final class CountingVisitor implements SyntaxVisitor<Void> {
        private int count;
        private int programs;
        private int imports;

        private CountingVisitor() {
        }

        private Void hit() {
            count++;
            return null;
        }

        @Override
        public Void visitProgram(SyntaxProgram node) {
            programs++;
            return hit();
        }

        @Override
        public Void visitImportDeclaration(SyntaxNode.ImportDeclaration node) {
            imports++;
            return hit();
        }

        @Override public Void visitImportPath(SyntaxNode.ImportPath node) { return hit(); }
        @Override public Void visitImportAlias(SyntaxNode.ImportAlias node) { return hit(); }
        @Override public Void visitImportSelection(SyntaxNode.ImportSelection node) { return hit(); }
        @Override public Void visitImportItem(SyntaxNode.ImportItem node) { return hit(); }
        @Override public Void visitModifier(SyntaxNode.Modifier node) { return hit(); }
        @Override public Void visitIdentifier(SyntaxNode.Identifier node) { return hit(); }
        @Override public Void visitMemberName(SyntaxNode.MemberName node) { return hit(); }

        @Override
        public Void visitOperator(SyntaxNode.Operator node) {
            return hit();
        }

        @Override public Void visitTypeContract(SyntaxNode.TypeContract node) { return hit(); }
        @Override public Void visitPrimitiveType(SyntaxNode.PrimitiveType node) { return hit(); }
        @Override public Void visitArrayType(SyntaxNode.ArrayType node) { return hit(); }
        @Override public Void visitRangeType(SyntaxNode.RangeType node) { return hit(); }
        @Override public Void visitRange(SyntaxNode.Range node) { return hit(); }
        @Override public Void visitTupleType(SyntaxNode.TupleType node) { return hit(); }
        @Override public Void visitFunctionType(SyntaxNode.FunctionType node) { return hit(); }
        @Override public Void visitTypeAnnotation(SyntaxNode.TypeAnnotation node) { return hit(); }
        @Override public Void visitReturnAnnotation(SyntaxNode.ReturnAnnotation node) { return hit(); }
        @Override public Void visitParameter(SyntaxNode.Parameter node) { return hit(); }
        @Override public Void visitParameterList(SyntaxNode.ParameterList node) { return hit(); }
        @Override public Void visitArgumentList(SyntaxNode.ArgumentList node) { return hit(); }
        @Override public Void visitTypeArgumentList(SyntaxNode.TypeArgumentList node) { return hit(); }
        @Override public Void visitPredicateBinding(SyntaxNode.PredicateBinding node) { return hit(); }
        @Override public Void visitLetBinding(SyntaxNode.LetBinding node) { return hit(); }
        @Override public Void visitNominalDeclaration(SyntaxNode.NominalDeclaration node) { return hit(); }
        @Override public Void visitMemberDeclaration(SyntaxNode.MemberDeclaration node) { return hit(); }
        @Override public Void visitConstructorDeclaration(SyntaxNode.ConstructorDeclaration node) { return hit(); }
        @Override public Void visitNamedType(SyntaxNode.NamedType node) { return hit(); }
        @Override public Void visitBracketApplication(SyntaxNode.BracketApplication node) { return hit(); }
        @Override public Void visitReassignment(SyntaxNode.Reassignment node) { return hit(); }

        @Override
        public Void visitBooleanLiteral(SyntaxNode.BooleanLiteral node) {
            return hit();
        }

        @Override public Void visitNilLiteral(SyntaxNode.NilLiteral node) { return hit(); }
        @Override public Void visitIntegerLiteral(SyntaxNode.IntegerLiteral node) { return hit(); }
        @Override public Void visitFloatLiteral(SyntaxNode.FloatLiteral node) { return hit(); }
        @Override public Void visitStringLiteral(SyntaxNode.StringLiteral node) { return hit(); }
        @Override public Void visitCharacterLiteral(SyntaxNode.CharacterLiteral node) { return hit(); }
        @Override public Void visitUnitLiteral(SyntaxNode.UnitLiteral node) { return hit(); }
        @Override public Void visitBlock(SyntaxNode.Block node) { return hit(); }
        @Override public Void visitConditional(SyntaxNode.Conditional node) { return hit(); }
        @Override public Void visitMatchArm(SyntaxNode.MatchArm node) { return hit(); }
        @Override public Void visitMatch(SyntaxNode.Match node) { return hit(); }
        @Override public Void visitCond(SyntaxNode.Cond node) { return hit(); }
        @Override public Void visitExplicitConstruction(SyntaxNode.ExplicitConstruction node) { return hit(); }
        @Override public Void visitCoalesce(SyntaxNode.Coalesce node) { return hit(); }
        @Override public Void visitPrefixAssignment(SyntaxNode.PrefixAssignment node) { return hit(); }
        @Override public Void visitLambda(SyntaxNode.Lambda node) { return hit(); }
        @Override public Void visitCompactLambda(SyntaxNode.CompactLambda node) { return hit(); }
        @Override public Void visitCallableCall(SyntaxNode.CallableCall node) { return hit(); }
        @Override public Void visitDirectCall(SyntaxNode.DirectCall node) { return hit(); }
        @Override public Void visitMemberAccess(SyntaxNode.MemberAccess node) { return hit(); }
        @Override public Void visitNamespaceMemberAccess(SyntaxNode.NamespaceMemberAccess node) { return hit(); }
        @Override public Void visitNamespaceDirectCall(SyntaxNode.NamespaceDirectCall node) { return hit(); }
        @Override public Void visitNamespacePath(SyntaxNode.NamespacePath node) { return hit(); }
        @Override public Void visitIndexAccess(SyntaxNode.IndexAccess node) { return hit(); }
        @Override public Void visitOperatorSExpression(SyntaxNode.OperatorSExpression node) { return hit(); }
        @Override public Void visitOperatorBracket(SyntaxNode.OperatorBracket node) { return hit(); }
        @Override public Void visitArrayLiteral(SyntaxNode.ArrayLiteral node) { return hit(); }
        @Override public Void visitTupleLiteral(SyntaxNode.TupleLiteral node) { return hit(); }
        @Override public Void visitTypeConversion(SyntaxNode.TypeConversion node) { return hit(); }
    }
}
