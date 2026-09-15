import org.junit.jupiter.api.Test;

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
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Assertion-grade, dependency-free tests for grammar matching and replay data. */
public final class GrammarMatcherTest {
    @Test
    public void testReservedWhileGrammar() {
        for (String source : List.of("while[|| #F || ()]",
                "(while || #T || ())", "while[test action]",
                "while[(=> :Bool || #F) (=> :Unit || ())]",
                "(#T -> while[|| #F || ()] : ())",
                "iter[(0..10:1) |x| while[|| (< x 0) || ()]]")) {
            success(source);
        }
        for (String source : List.of("let while = 1", "let f = (=> |while :I32| ())",
                "let f = while", "while", "::while", "(while -> ())",
                "ns->::while[|| #F || ()]", "::while[|| #F || ()",
                "(while || #F || ()", "::while[|| #F, || (),]")) {
            check(GrammarMatcher.match(lex(source)) instanceof PhaseResult.Failure<?>,
                    "reserved while must reject: " + source);
        }
        for (String source : List.of("::while[|| #F || ()]",
                "ns->::while[|| #F || ()]", "value::while[|| #F || ()]")) {
            expectFailure(source, CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        }
    }

    @Test
    public void testReservedIterCallBoundaries() {
        for (String separator : List.of(" ", "\n", "\n// boundary\n")) {
            success("let r = (0..10:1)" + separator + "iter[r || ()]");
            success("(#T -> iter[(0..10:1) || ()] : ())");
        }
        for (String source : List.of("let iter = 1", "let f = (=> |iter :I32| ())",
                "let f = iter", "iter", "::iter", "(iter -> ())",
                "ns->::iter[(0..10:1) || ()]")) {
            check(GrammarMatcher.match(lex(source)) instanceof PhaseResult.Failure<?>,
                    "reserved iter must reject: " + source);
        }
        for (String source : List.of("::iter[(0..10:1) || ()]",
                "ns->::iter[(0..10:1) || ()]", "value::iter[(0..10:1) || ()]")) {
            expectFailure(source, CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        }
        success("let iterator = 1 let iterate = iterator");
    }

    @Test
    public void testRangeExpressionGrammarAndMalformedBounds() {
        for (String source : List.of("(0..100:1)", "(100...0:(- 1))",
                "(::start[]..::end[]:stride)", "(0.0..1.0:0.1)",
                "(iter (0..10:1) |x| ())", "iter[(0..10:1) || ()]", "(0..10:-1)",
                "let r :Range<I32> = (0I32..10I32:1I32)",
                "let r :Range<I32>=(0..10:1)")) {
            success(source);
        }
        for (String source : List.of("(0..10)", "(0..:1)", "(..10:1)",
                "(0..10:)", "0..10:1", "(0..10:1 2)",
                "let r :Range<I32,I64> = (0..1:1)")) {
            check(GrammarMatcher.match(lex(source)) instanceof PhaseResult.Failure<?>,
                    "malformed range must fail grammar: " + source);
        }
    }

    @Test
    public void testNormativeFormsProduceReplayDescriptors() {
        normativeFormsProduceReplayDescriptors();
    }

    @Test
    public void testMalformedFormsProduceStructuredDiagnostics() {
        malformedFormsProduceStructuredDiagnostics();
    }

    @Test
    public void testRangesAndNestedInvariantsAreExecutable() {
        rangesAndNestedInvariantsAreExecutable();
    }

    @Test
    public void testCommaScopesAndAnnotationTriviaAreRetained() {
        commaScopesAndAnnotationTriviaAreRetained();
    }

    @Test
    public void testMatchingIsDeterministicAndDeeplyImmutable() {
        matchingIsDeterministicAndDeeplyImmutable();
    }

    @Test
    public void testDeferredProductionsAreAbsent() {
        deferredProductionsAreAbsent();
    }

    @Test
    public void testAllOperatorSpellingsAndNestedBracketForms() {
        allOperatorSpellingsAndNestedBracketForms();
    }

    @Test
    public void testTypeCloseBeforeAssignmentMatches() {
        typeCloseBeforeAssignmentMatches();
    }

    @Test
    public void testLetPrimaryTokenInvariant() {
        letPrimaryTokenInvariant();
    }

    @Test
    public void testDescriptorMetadataInvariants() {
        descriptorMetadataInvariants();
    }

    @Test
    public void testParenthesizedTargetTopLevelReassignment() {
        parenthesizedTargetTopLevelReassignment();
    }

    @Test
    public void testExactPrimaryMetadataPositions() {
        exactPrimaryMetadataPositions();
    }

    @Test
    public void testAggregateAndMemberRoleValidation() {
        aggregateAndMemberRoleValidation();
    }

    @Test
    public void testModifierAndOperatorRoleValidation() {
        modifierAndOperatorRoleValidation();
    }

    @Test
    public void testBracketMatchStartsANewExpressionRatherThanAReceiverCall() {
        for (String separator : List.of("", " ", "\n", "/* boundary */")) {
            LexedSource lexed = lex("((=> :I32 |value :I32| value)" + separator
                    + "match[1 1 -> 2 _ -> 0])");
            GrammarProgram program = success(lexed);
            GrammarDescriptor lambda = findFirst(program.root(), ProductionKind.LAMBDA);
            GrammarDescriptor match = findFirst(program.root(), ProductionKind.MATCH);
            check(program.forms().getFirst().kind() == ProductionKind.CALLABLE_CALL,
                    "bracket match is the lambda call's argument, independent of trivia");
            check(lambda.endTokenIndex() == match.startTokenIndex(),
                    "the callable target ends before the bracket match accessor");
            check(match.metadata().openingTokenIndex() == match.startTokenIndex() + 1
                            && match.metadata().primaryTokenIndex() == match.startTokenIndex(),
                    "bracket match retains its exact accessor/keyword/opening roles");
            check(!collectKinds(program.root()).contains(ProductionKind.DIRECT_CALL),
                    "a bracket match argument is not a receiver method call");
            assertNoIllegalSiblingOverlap(program.root());
            program.validateAgainst(lexed);
            program.assertReplayConsumed(0, lexed.tokens().size());

            DescriptorMetadata metadata = match.metadata();
            GrammarDescriptor invalid = withMetadata(match, new DescriptorMetadata(
                    metadata.openingTokenIndex(), metadata.closingTokenIndex(),
                    match.children().getFirst().startTokenIndex(), metadata.commaTokenIndices(),
                    metadata.modifierTokenIndices(), metadata.operatorTokenIndices()));
            expectThrows(IllegalArgumentException.class,
                    () -> replaceDescriptor(program, match, invalid).validateAgainst(lexed));
        }
        expectFailure("(callee ::match)", CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        expectFailure("(callee ::when[])", CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR);
        expectFailure("::match[1 _ -> 0]", CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        expectFailure("ns->::match[1 _ -> 0]", CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        expectFailure("value::match[1 _ -> 0]", CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        expectFailure("ns->inner->::match[1 _ -> 0]", CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
    }

    @Test
    public void testConditionalArrowBeforeBracketMatchIsNotNamespaceQualification() {
        for (String source : List.of(
                "(enabled -> match[value #T -> 42 _ -> 0] : 7)",
                "(enabled -> match[1 _ -> 42])",
                "(candidate value -> match[value _ -> 42] : 7)")) {
            LexedSource lexed = lex(source);
            GrammarProgram program = success(lexed);
            GrammarDescriptor conditional = program.forms().getFirst();
            check(conditional.kind() == ProductionKind.CONDITIONAL
                            && conditional.children().getFirst().kind() == ProductionKind.IDENTIFIER,
                    "an identifier before an arrow and bracket match remains a conditional predicate");
            check(findFirst(conditional, ProductionKind.MATCH).startTokenIndex()
                            == conditional.metadata().primaryTokenIndex() + 1,
                    "the bracket match starts immediately after the conditional arrow");
            check(!collectKinds(conditional).contains(ProductionKind.NAMESPACE_DIRECT_CALL),
                    "a reserved match result cannot be mistaken for a qualified callable");
            assertNoIllegalSiblingOverlap(program.root());
            program.validateAgainst(lexed);
        }
    }

    @Test
    public void testMatchHeadsAcceptBothNamespaceTerminalAccessorSpellings() {
        for (String path : List.of("game->constants", "game->math->constants")) {
            for (String terminal : List.of("", "->")) {
                String qualifier = path + terminal;
                LexedSource lexed = lex("(match value " + qualifier + ":.values[0] -> 7"
                        + " " + qualifier + "::pair[]:.0 when " + qualifier + "::pair[]:.1 -> 8"
                        + " _ -> 0)");
                GrammarProgram program = success(lexed);
                GrammarDescriptor match = program.forms().getFirst();
                check(match.kind() == ProductionKind.MATCH && match.children().size() == 4,
                        "qualified pattern and guard accessors do not consume the arm arrow");
                GrammarDescriptor member = findFirst(match, ProductionKind.NAMESPACE_MEMBER_ACCESS);
                GrammarDescriptor call = findFirst(match, ProductionKind.NAMESPACE_DIRECT_CALL);
                check(member.metadata().operatorTokenIndices().size() == (terminal.isEmpty() ? 0 : 1)
                                && call.metadata().operatorTokenIndices().size() == (terminal.isEmpty() ? 0 : 1),
                        "only a real terminal namespace arrow is recorded in accessor metadata");
                assertNoIllegalSiblingOverlap(program.root());
                program.validateAgainst(lexed);
                for (GrammarDescriptor access : List.of(member, call)) {
                    DescriptorMetadata metadata = access.metadata();
                    List<Integer> wrongArrows = terminal.isEmpty()
                            ? List.of(access.children().getFirst().endTokenIndex()) : List.of();
                    GrammarDescriptor invalid = withMetadata(access, new DescriptorMetadata(
                            metadata.openingTokenIndex(), metadata.closingTokenIndex(), metadata.primaryTokenIndex(),
                            metadata.commaTokenIndices(), metadata.modifierTokenIndices(), wrongArrows));
                    expectThrows(IllegalArgumentException.class,
                            () -> replaceDescriptor(program, access, invalid).validateAgainst(lexed));
                }
            }
        }
        assertNestedPrimaryRejected("let value = game->constants::outer[::inner[]]",
                ProductionKind.NAMESPACE_DIRECT_CALL, ProductionKind.DIRECT_CALL);
        for (String source : List.of("receiver:.member", "receiver::method[]")) {
            LexedSource lexed = lex(source);
            GrammarProgram program = success(lexed);
            GrammarDescriptor access = program.forms().getFirst();
            GrammarDescriptor receiver = access.children().getFirst();
            GrammarDescriptor path = new GrammarDescriptor(ProductionKind.NAMESPACE_PATH,
                    receiver.startTokenIndex(), receiver.endTokenIndex(), List.of(receiver), DescriptorMetadata.NONE);
            var children = new java.util.ArrayList<>(access.children());
            children.set(0, path);
            GrammarDescriptor invalid = new GrammarDescriptor(
                    access.kind() == ProductionKind.MEMBER_ACCESS
                            ? ProductionKind.NAMESPACE_MEMBER_ACCESS : ProductionKind.NAMESPACE_DIRECT_CALL,
                    access.startTokenIndex(), access.endTokenIndex(), children, access.metadata());
            expectThrows(IllegalArgumentException.class,
                    () -> replaceDescriptor(program, access, invalid).validateAgainst(lexed));
        }
    }

    private static void normativeFormsProduceReplayDescriptors() {
        String source = "import game->math->vector "
                + "import game->math->vector as vec "
                + "import @pub game->math->vector->{length normalize as norm} "
                + "let @pub main :Fn<Array<String>;I32> = (=> |args| "
                + "{ let @mut total :I32 = 0 "
                + "total := (+ 1,2 3) "
                + "(#T -> total : (total : 0)) }) "
                + "let nested :Fn<@mut Array<I32>,@nil String;@nil I32> = "
                + "(=> @nil :@nil I32 |@mut values :Array<I32>, label :@nil String| "
                + "(values:.length)) "
                + "let array = Array<@nil String>[#NIL, \"x\"] "
                + "let tuple = Tuple<I32,String>[1 \"x\"] "
                + "let converted = I32[1] "
                + "let bracket = +[1,2] "
                + "let indexed = array[0] "
                + "let direct = ::callee[1, 2] "
                + "let qualified = vec->::normalize[1] "
                + "let field = vec->:.length "
                + "let bound = receiver:.method "
                + "let called = (receiver:.method 1) "
                + "let compact = (consume |x :I32| x) "
                + "let noArgs :Fn<;Unit> = (=> || ()) "
                + "let matched = match[1 1 -> 2, ::pattern[] -> 3 _ -> 0] "
                + "let conditional = (cond #T -> 1, ::condition[] -> 2 _ -> 0) "
                + "let bracketConditional = cond[#T -> 1 _ -> 0] "
                + "let constructed = :Thing[] "
                + "let negative = -1 "
                + "let preserved = (::callee[]) "
                + "let iterated = iter[(0..10:1) || ()] "
                + "let waited = while[|| #F || ()] "
                + "x := 1";
        GrammarProgram program = success(source);
        check(program.forms().size() == 26, "all header and source forms are matched");
        check(program.forms().getFirst().kind() == ProductionKind.IMPORT_DECLARATION,
                "imports are first-class descriptors");
        check(program.forms().getLast().kind() == ProductionKind.REASSIGNMENT,
                "reassignment has its own replay production");

        Set<ProductionKind> kinds = collectKinds(program.root());
        for (ProductionKind required : List.of(
                ProductionKind.PROGRAM,
                ProductionKind.EOF,
                ProductionKind.IMPORT_PATH,
                ProductionKind.IMPORT_SELECTION,
                ProductionKind.IMPORT_ALIAS,
                ProductionKind.LET_BINDING,
                ProductionKind.REASSIGNMENT,
                ProductionKind.BLOCK,
                ProductionKind.CONDITIONAL,
                ProductionKind.COALESCE,
                ProductionKind.LAMBDA,
                ProductionKind.COMPACT_LAMBDA,
                ProductionKind.PARAMETER_LIST,
                ProductionKind.FUNCTION_TYPE,
                ProductionKind.ARRAY_LITERAL,
                ProductionKind.TUPLE_LITERAL,
                ProductionKind.TYPE_CONVERSION,
                ProductionKind.INDEX_ACCESS,
                ProductionKind.DIRECT_CALL,
                ProductionKind.NAMESPACE_DIRECT_CALL,
                ProductionKind.NAMESPACE_MEMBER_ACCESS,
                ProductionKind.MEMBER_ACCESS,
                ProductionKind.OPERATOR_S_EXPRESSION,
                ProductionKind.OPERATOR_BRACKET,
                ProductionKind.ARGUMENT_LIST,
                ProductionKind.MATCH,
                ProductionKind.MATCH_ARM,
                ProductionKind.COND,
                ProductionKind.CONSTRUCTION,
                ProductionKind.CALLBACK_LOOP_BRACKET,
                ProductionKind.PARENTHESIZED_DIRECT_CALL,
                ProductionKind.NEGATIVE_LITERAL)) {
            check(kinds.contains(required), "descriptor tree contains " + required);
        }
        GrammarDescriptor match = findFirst(program.root(), ProductionKind.MATCH);
        check(match.children().size() == 5
                        && match.metadata().commaTokenIndices().size() == 1,
                "match children retain the subject, marker-free arms and narrow comma sibling");
        GrammarDescriptor arm = findFirst(match, ProductionKind.MATCH_ARM);
        check(arm.metadata().primaryTokenIndex() == arm.metadata().operatorTokenIndices().getFirst(),
                "match arm primary token is its arrow");
        check(arm.metadata().operatorTokenIndices().equals(
                        List.of(arm.metadata().primaryTokenIndex())),
                "match arm operator metadata contains exactly its arrow");
        GrammarDescriptor cond = findFirst(program.root(), ProductionKind.COND);
        check(cond.children().size() == 4
                        && cond.metadata().commaTokenIndices().size() == 1,
                "cond children retain marker-free arms, narrow comma sibling and no subject");
        program.validateAgainst(lex(source));
        program.assertReplayConsumed(0, lex(source).tokens().size());
    }

    private static void malformedFormsProduceStructuredDiagnostics() {
        expectFailure("let x : I32 = 1", CompilerDiagnosticCodes.PARSE_INVALID_ANNOTATION_SPACING);
        expectFailure("let x: I32 = 1", CompilerDiagnosticCodes.PARSE_INVALID_ANNOTATION_SPACING);
        expectFailure("let x :I32", CompilerDiagnosticCodes.PARSE_UNEXPECTED_TOKEN);
        expectFailure("let x = (foo", CompilerDiagnosticCodes.PARSE_MISSING_DELIMITER);
        expectFailure("let x = Array[1,]", CompilerDiagnosticCodes.PARSE_INVALID_COMMA);
        expectFailure("let x = Array[,1]", CompilerDiagnosticCodes.PARSE_INVALID_COMMA);
        expectFailure("let x = Array[1,,2]", CompilerDiagnosticCodes.PARSE_INVALID_COMMA);
        check(collectKinds(success("let x = a[0,1]").root()).contains(ProductionKind.BRACKET_APPLICATION),
                "non-unary brackets defer construction-versus-indexing legality to resolution");
        expectFailure("let x = ::f", CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR);
        expectFailure("let x = receiver::f", CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR);
        expectFailure("let x = (f", CompilerDiagnosticCodes.PARSE_MISSING_DELIMITER);
        expectFailure("let x = { let y = 1", CompilerDiagnosticCodes.PARSE_MISSING_DELIMITER);
        expectFailure("let x = (=> |x| x", CompilerDiagnosticCodes.PARSE_MISSING_DELIMITER);
        expectFailure("let x = Fn<I32;I32>", CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM);
        expectFailure("let x = |x :I32| x", CompilerDiagnosticCodes.PARSE_INVALID_FORM);
        expectFailure("let x = (match 1 ?? 1 -> 2 _ -> 3)",
                CompilerDiagnosticCodes.PARSE_OBSOLETE_ARM_MARKER);
        expectFailure("let x = (match _ 1 -> 2 _ -> 3)",
                CompilerDiagnosticCodes.PARSE_OBSOLETE_CONDITIONAL_MATCH);
        expectFailure("let x = ::match[1 _ -> 0]",
                CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        expectFailure("let x = ns->::match[1 _ -> 0]",
                CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        expectFailure("let x = value::match[1 _ -> 0]",
                CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        expectFailure("let x = (match 1 1 -> 2, _ -> 3)", CompilerDiagnosticCodes.PARSE_INVALID_COMMA);
        expectFailure("let x = (cond #T -> 1, _ -> 0)", CompilerDiagnosticCodes.PARSE_INVALID_COMMA);
        success("let x = cond[#T -> 1 _ -> 0]");
        expectFailure("let x = ::cond[#T -> 1 _ -> 0]",
                CompilerDiagnosticCodes.PARSE_OBSOLETE_DIRECT_SPECIAL_FORM);
        success("let x = -1 let y = -128I8 let z = -0.0 let e = -1.0e3 "
                + "let b = -[9223372036854775808I64]");
        expectFailure("let x = - 1", CompilerDiagnosticCodes.PARSE_INVALID_FORM);
        expectFailure("let x = -/* trivia */1", CompilerDiagnosticCodes.PARSE_INVALID_FORM);
        expectFailure("let x = --1", CompilerDiagnosticCodes.PARSE_INVALID_FORM);
        expectFailure("let x = (not 1 2)", CompilerDiagnosticCodes.PARSE_INVALID_OPERATOR_ARITY);
        expectFailure("let x = (+ 1)", CompilerDiagnosticCodes.PARSE_INVALID_OPERATOR_ARITY);
        expectFailure("let x = (% 1 2 3)", CompilerDiagnosticCodes.PARSE_INVALID_OPERATOR_ARITY);
        expectFailure("let x = (#T -> 1 :)", CompilerDiagnosticCodes.PARSE_UNEXPECTED_TOKEN);
        expectFailure("let x = (#T -> : 1)", CompilerDiagnosticCodes.PARSE_UNEXPECTED_TOKEN);
        expectFailure("let x = (:= x 1 2)", CompilerDiagnosticCodes.PARSE_UNEXPECTED_TOKEN);
        success("let x = (x := 1)");
        expectFailure("let x = (=> @mut :I32 |x| x)", CompilerDiagnosticCodes.PARSE_INVALID_MODIFIER);
        expectFailure("let @mut @mut x = 1", CompilerDiagnosticCodes.PARSE_INVALID_MODIFIER);
        expectFailure("import @pub game->math", CompilerDiagnosticCodes.PARSE_INVALID_MODIFIER);
        expectFailure("import game->math {name}", CompilerDiagnosticCodes.PARSE_UNEXPECTED_TOKEN);
        expectFailure("import game->math->{name,other}", CompilerDiagnosticCodes.PARSE_INVALID_COMMA);
        expectFailure("let x = 1 import game->math", CompilerDiagnosticCodes.PARSE_IMPORT_HEADER);
        success("let x :Unknown = 1"); // Named type validity now belongs to resolution.
        expectFailure("let x = Array<I32", CompilerDiagnosticCodes.PARSE_MISSING_DELIMITER);
        expectFailure("let x = Tuple<>[1]", CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM);
        expectFailure("let x = Fn<;I32>", CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM);
    }

    private static void rangesAndNestedInvariantsAreExecutable() {
        LexedSource lexed = lex(
                "let value :Fn<@nil Array<I32>,String;@nil I32> = "
                        + "(=> @nil :@nil I32 |@mut input :Array<I32>, name :String| "
                        + "{ let result = Array<I32>[1, 2] result[0] := 3 result })");
        GrammarProgram program = success(lexed);
        GrammarDescriptor root = program.root();
        check(root.startTokenIndex() == 0, "program starts at token zero");
        check(root.endTokenIndex() == lexed.tokens().size(), "program ends after EOF");
        check(program.eof().startTokenIndex() == lexed.tokens().size() - 1,
                "EOF descriptor starts at the lexical EOF token");
        check(program.eof().endTokenIndex() == lexed.tokens().size(),
                "EOF descriptor is one token wide");
        assertNoIllegalSiblingOverlap(root);
        program.validateAgainst(lexed);
        GrammarProgram empty = success("");
        check(empty.forms().isEmpty(), "an empty source still publishes a program and EOF range");
        check(empty.root().startTokenIndex() == 0 && empty.root().endTokenIndex() == 1,
                "empty program range contains only EOF");

        GrammarDescriptor let = program.forms().getFirst();
        GrammarDescriptor annotation = findFirst(let, ProductionKind.TYPE_ANNOTATION);
        GrammarDescriptor functionType = findFirst(annotation, ProductionKind.FUNCTION_TYPE);
        GrammarDescriptor typeArguments = findFirst(functionType, ProductionKind.TYPE_ARGUMENT_LIST);
        check(typeArguments.metadata().commaTokenIndices().size() == 1,
                "nested function type retains its parameter comma");
        check(typeArguments.metadata().openingTokenIndex() < typeArguments.metadata().closingTokenIndex(),
                "nested type argument delimiters are retained");

        GrammarDescriptor lambda = findFirst(let, ProductionKind.LAMBDA);
        GrammarDescriptor parameters = findFirst(lambda, ProductionKind.PARAMETER_LIST);
        check(parameters.metadata().commaTokenIndices().size() == 1,
                "lambda parameter comma scope is retained");
        GrammarDescriptor block = findFirst(lambda, ProductionKind.BLOCK);
        check(block.metadata().openingTokenIndex() + 1
                        <= block.metadata().closingTokenIndex(),
                "block delimiter range is recorded");
        GrammarDescriptor argumentList = findFirst(block, ProductionKind.ARGUMENT_LIST);
        check(argumentList.metadata().commaTokenIndices().size() == 1,
                "array argument comma scope is retained");

        let.assertConsumed(let.startTokenIndex(), let.endTokenIndex());
        expectThrows(IllegalStateException.class,
                () -> let.assertConsumed(let.startTokenIndex(), let.endTokenIndex() - 1));
        LexedSource differentSource = lex("let value :I32 = 1");
        expectThrows(IllegalArgumentException.class,
                () -> program.validateAgainst(differentSource));
        GrammarProgram shortProgram = success("let a = 1");
        LexedSource renamedSource = lex("let b = 1");
        expectThrows(IllegalArgumentException.class,
                () -> shortProgram.validateAgainst(renamedSource));
        expectThrows(IllegalArgumentException.class,
                () -> new GrammarDescriptor(
                        ProductionKind.PROGRAM,
                        0,
                        3,
                        List.of(
                                new GrammarDescriptor(ProductionKind.IDENTIFIER, 0, 2, List.of()),
                                new GrammarDescriptor(ProductionKind.LITERAL, 1, 3, List.of()))));
    }

    private static void commaScopesAndAnnotationTriviaAreRetained() {
        GrammarProgram program = success(
                "let f :Fn<I32,String;I32> = (=> |left :I32,right :String| "
                        + "(+ left, right))");
        GrammarDescriptor let = program.forms().getFirst();
        GrammarDescriptor typeArguments = findFirst(let, ProductionKind.TYPE_ARGUMENT_LIST);
        GrammarDescriptor parameters = findFirst(let, ProductionKind.PARAMETER_LIST);
        GrammarDescriptor operands = findFirst(let, ProductionKind.OPERATOR_OPERANDS);
        check(typeArguments.metadata().commaTokenIndices().size() == 1,
                "Fn type comma belongs to its type-list descriptor");
        check(parameters.metadata().commaTokenIndices().size() == 1,
                "parameter comma belongs to its parameter-list descriptor");
        check(operands.metadata().commaTokenIndices().size() == 1,
                "operator comma belongs to its operand-list descriptor");
        check(typeArguments.metadata().commaTokenIndices().stream()
                        .noneMatch(parameters.metadata().commaTokenIndices()::contains),
                "comma metadata is scoped rather than globally ignored");

        success("let value :I32 = 1");
        expectFailure("let value:I32 = 1", CompilerDiagnosticCodes.PARSE_INVALID_ANNOTATION_SPACING);
        expectFailure("let value : I32 = 1", CompilerDiagnosticCodes.PARSE_INVALID_ANNOTATION_SPACING);
        expectFailure("let value/*comment*/:I32 = 1",
                CompilerDiagnosticCodes.PARSE_INVALID_ANNOTATION_SPACING);
        expectFailure("let value :/*comment*/ I32 = 1",
                CompilerDiagnosticCodes.PARSE_INVALID_ANNOTATION_SPACING);
        success("let value /*comment*/ :/*comment*/I32 = 1");
        success("let f = (=> :/*comment*/I32 |value :/*comment*/I32| value)");
    }

    private static void matchingIsDeterministicAndDeeplyImmutable() {
        String source = "import std->io->{println readLine} "
                + "let value :Array<@nil String> = Array[#NIL, \"ok\"]";
        LexedSource lexed = lex(source);
        GrammarProgram first = success(lexed);
        GrammarProgram second = success(lexed);
        check(first.root().equals(second.root()),
                "repeated matching produces the same descriptor tree");
        check(first.root().children() != second.root().children(),
                "each match owns its published child list");
        expectUnsupported(() -> first.root().children().clear());
        expectUnsupported(() -> first.root().children().getFirst().children().clear());
        expectUnsupported(() -> first.root().children().getFirst().metadata().commaTokenIndices().clear());
        expectThrows(IllegalArgumentException.class,
                () -> new DescriptorMetadata(0, 1, -1, List.of(2, 1), List.of(), List.of()));
    }

    private static void allOperatorSpellingsAndNestedBracketForms() {
        for (String operator : List.of(
                "+", "*", "<", "<=", ">", ">=", "==", "!=", "eq?", "!eq?",
                "and", "or", "xor")) {
            success("let value = (" + operator + " 1, 2)");
            success("let value = " + operator + "[1, 2]");
        }
        for (String operator : List.of("-", "/")) {
            success("let value = (" + operator + " 1)");
            success("let value = " + operator + "[1]");
        }
        for (String operator : List.of("%", "^")) {
            success("let value = (" + operator + " 1 2)");
            success("let value = " + operator + "[1 2]");
        }
        for (String operator : List.of("not", "++", "--")) {
            success("let value = (" + operator + " 1)");
            success("let value = " + operator + "[1]");
        }
        success("let value = (not[1] -> 2)");
        success("let value = (#T -> +[1 2] : *[2 3])");
    }

    private static void typeCloseBeforeAssignmentMatches() {
        GrammarProgram simple = success("let f :Array<I32>=Array<I32>[1]");
        check(simple.forms().getFirst().kind() == ProductionKind.LET_BINDING,
                "an adjacent type close and assignment matches as a let binding");
        GrammarProgram nested = success(
                "let f :Array<Tuple<I32,Array<String>>>=Array<Tuple<I32,Array<String>>>[]");
        nested.validateAgainst(lex(
                "let f :Array<Tuple<I32,Array<String>>>=Array<Tuple<I32,Array<String>>>[]"));
    }

    private static void letPrimaryTokenInvariant() {
        LexedSource lexed = lex("let value :I32 = 1");
        GrammarProgram valid = success(lexed);
        GrammarDescriptor originalLet = valid.forms().getFirst();
        int identifierIndex = findFirst(originalLet, ProductionKind.IDENTIFIER).startTokenIndex();
        DescriptorMetadata original = originalLet.metadata();
        GrammarDescriptor invalidLet = new GrammarDescriptor(
                originalLet.kind(),
                originalLet.startTokenIndex(),
                originalLet.endTokenIndex(),
                originalLet.children(),
                new DescriptorMetadata(
                        original.openingTokenIndex(),
                        original.closingTokenIndex(),
                        identifierIndex,
                        original.commaTokenIndices(),
                        original.modifierTokenIndices(),
                        original.operatorTokenIndices()));
        GrammarDescriptor invalidRoot = new GrammarDescriptor(
                ProductionKind.PROGRAM,
                valid.root().startTokenIndex(),
                valid.root().endTokenIndex(),
                List.of(invalidLet, valid.eof()),
                valid.root().metadata());
        GrammarProgram invalid = new GrammarProgram(
                valid.sourceId(), valid.sourceRevision(), valid.tokenCount(), invalidRoot);
        expectThrows(IllegalArgumentException.class, () -> invalid.validateAgainst(lexed));
        valid.validateAgainst(lexed);
    }

    private static void descriptorMetadataInvariants() {
        LexedSource unitSource = lex("let value = Array[]");
        GrammarProgram unitProgram = success(unitSource);
        GrammarDescriptor originalUnit = findFirst(unitProgram.root(), ProductionKind.UNIT_LITERAL);
        GrammarDescriptor invalidUnit = new GrammarDescriptor(
                originalUnit.kind(), originalUnit.startTokenIndex(), originalUnit.endTokenIndex(),
                originalUnit.children(), new DescriptorMetadata(
                        originalUnit.metadata().openingTokenIndex(),
                        originalUnit.metadata().closingTokenIndex(), -1,
                        originalUnit.metadata().commaTokenIndices(),
                        originalUnit.metadata().modifierTokenIndices(),
                        originalUnit.metadata().operatorTokenIndices()));
        expectThrows(IllegalArgumentException.class,
                () -> replaceFirstForm(unitProgram, invalidUnit).validateAgainst(unitSource));

        LexedSource reassignmentSource = lex("let value = (value := 1)");
        GrammarProgram reassignmentProgram = success(reassignmentSource);
        GrammarDescriptor originalReassignment = findFirst(
                reassignmentProgram.root(), ProductionKind.REASSIGNMENT);
        GrammarDescriptor missingDelimiters = new GrammarDescriptor(
                originalReassignment.kind(), originalReassignment.startTokenIndex(),
                originalReassignment.endTokenIndex(), originalReassignment.children(),
                new DescriptorMetadata(
                        -1, -1, originalReassignment.metadata().primaryTokenIndex(),
                        List.of(), List.of(), List.of()));
        expectThrows(IllegalArgumentException.class,
                () -> replaceFirstForm(reassignmentProgram, missingDelimiters)
                        .validateAgainst(reassignmentSource));

        LexedSource commaSource = lex("let value = Array[1, 2]");
        GrammarProgram commaProgram = success(commaSource);
        GrammarDescriptor originalLet = commaProgram.forms().getFirst();
        GrammarDescriptor arguments = findFirst(originalLet, ProductionKind.ARGUMENT_LIST);
        GrammarDescriptor invalidCommaMetadata = new GrammarDescriptor(
                originalLet.kind(), originalLet.startTokenIndex(), originalLet.endTokenIndex(),
                originalLet.children(), new DescriptorMetadata(
                        originalLet.metadata().openingTokenIndex(),
                        originalLet.metadata().closingTokenIndex(),
                        originalLet.metadata().primaryTokenIndex(),
                        arguments.metadata().commaTokenIndices(),
                        originalLet.metadata().modifierTokenIndices(),
                        originalLet.metadata().operatorTokenIndices()));
        expectThrows(IllegalArgumentException.class,
                () -> replaceFirstForm(commaProgram, invalidCommaMetadata)
                        .validateAgainst(commaSource));
    }

    private static void parenthesizedTargetTopLevelReassignment() {
        GrammarProgram unparenthesized = success("(x) := 1");
        GrammarDescriptor reassignment = unparenthesized.forms().getFirst();
        check(reassignment.kind() == ProductionKind.REASSIGNMENT,
                "a parenthesized target may begin an unparenthesized top-level reassignment");
        check(!reassignment.metadata().hasDelimiters(),
                "top-level infix reassignment does not invent assignment delimiters");
        check(reassignment.children().getFirst().kind() == ProductionKind.CALLABLE_CALL,
                "the target retains its independently parenthesized child shape");
        unparenthesized.validateAgainst(lex("(x) := 1"));

        GrammarProgram parenthesized = success("((x) := 1)");
        GrammarDescriptor enclosed = parenthesized.forms().getFirst();
        check(enclosed.metadata().hasDelimiters(),
                "an enclosing reassignment pair remains explicit delimiter metadata");
        parenthesized.validateAgainst(lex("((x) := 1)"));
    }

    private static void exactPrimaryMetadataPositions() {
        assertNestedPrimaryRejected(
                "let value = ((#T -> 1) -> 2)", ProductionKind.CONDITIONAL);
        assertNestedPrimaryRejected(
                "let value = ((#NIL : 1) : 2)", ProductionKind.COALESCE);
        assertNestedPrimaryRejected(
                "let value = ::outer[::inner[]]", ProductionKind.DIRECT_CALL);
        assertNestedPrimaryRejected(
                "let value = receiver::outer[::inner[]]", ProductionKind.DIRECT_CALL);
        assertNestedPrimaryRejected(
                "let value = (inner:.field):.outer", ProductionKind.MEMBER_ACCESS);
        assertNestedPrimaryRejected(
                "let value = ns->::outer[::inner[]]",
                ProductionKind.NAMESPACE_DIRECT_CALL, ProductionKind.DIRECT_CALL);

        LexedSource namespaceSource = lex("let value = ns->:.inner:.outer");
        GrammarProgram namespaceProgram = success(namespaceSource);
        GrammarDescriptor namespaceMember = findFirst(
                namespaceProgram.root(), ProductionKind.NAMESPACE_MEMBER_ACCESS);
        check(namespaceMember.metadata().primaryTokenIndex()
                        == namespaceMember.children().getFirst().endTokenIndex() + 1,
                "namespace member primary follows its terminal namespace arrow");
        GrammarDescriptor outerMember = findFirst(
                namespaceProgram.root(), ProductionKind.MEMBER_ACCESS);
        GrammarDescriptor invalidNamespaceMember = new GrammarDescriptor(
                ProductionKind.NAMESPACE_MEMBER_ACCESS,
                outerMember.startTokenIndex(), outerMember.endTokenIndex(),
                outerMember.children(), outerMember.metadata());
        expectThrows(IllegalArgumentException.class,
                () -> replaceDescriptor(namespaceProgram, outerMember, invalidNamespaceMember)
                        .validateAgainst(namespaceSource));
    }

    private static void aggregateAndMemberRoleValidation() {
        LexedSource tupleSource = lex("let value = Tuple[1]");
        GrammarProgram tupleProgram = success(tupleSource);
        GrammarDescriptor tuple = findFirst(tupleProgram.root(), ProductionKind.TUPLE_LITERAL);
        GrammarDescriptor falseArray = new GrammarDescriptor(
                ProductionKind.ARRAY_LITERAL,
                tuple.startTokenIndex(), tuple.endTokenIndex(), tuple.children(), tuple.metadata());
        expectThrows(IllegalArgumentException.class,
                () -> replaceDescriptor(tupleProgram, tuple, falseArray).validateAgainst(tupleSource));

        success("let arrayUnit = Array[] let tupleUnit = Tuple[] ");
        success("let array = Array<I32>[] let tuple = Tuple<I32>[1]");
        success("let field = tuple:.0");
        expectFailure("let field = tuple:.1I32", CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR);

        LexedSource suffixedSource = lex("1I32");
        GrammarDescriptor member = new GrammarDescriptor(
                ProductionKind.MEMBER_NAME, 0, 1, List.of(), DescriptorMetadata.NONE);
        GrammarDescriptor eof = new GrammarDescriptor(
                ProductionKind.EOF, 1, 2, List.of(), DescriptorMetadata.NONE);
        GrammarDescriptor root = new GrammarDescriptor(
                ProductionKind.PROGRAM, 0, 2, List.of(member, eof), DescriptorMetadata.NONE);
        GrammarProgram suffixedMember = new GrammarProgram(
                suffixedSource.snapshot().sourceId(), suffixedSource.snapshot().sha256(), 2, root);
        expectThrows(IllegalArgumentException.class,
                () -> suffixedMember.validateAgainst(suffixedSource));
    }

    private static void modifierAndOperatorRoleValidation() {
        LexedSource modifierSource = lex("let @mut value = 1");
        GrammarProgram modifierProgram = success(modifierSource);
        int modifierIndex = findFirst(
                modifierProgram.root(), ProductionKind.MODIFIER).startTokenIndex();
        GrammarDescriptor invalidRoot = withMetadata(
                modifierProgram.root(), new DescriptorMetadata(
                        -1, -1, -1, List.of(), List.of(modifierIndex), List.of()));
        GrammarProgram invalidModifierRole = new GrammarProgram(
                modifierProgram.sourceId(), modifierProgram.sourceRevision(),
                modifierProgram.tokenCount(), invalidRoot);
        expectThrows(IllegalArgumentException.class,
                () -> invalidModifierRole.validateAgainst(modifierSource));

        LexedSource operatorSource = lex("let value = (+ 1 2)");
        GrammarProgram operatorProgram = success(operatorSource);
        GrammarDescriptor let = operatorProgram.forms().getFirst();
        int plusIndex = findFirst(
                operatorProgram.root(), ProductionKind.OPERATOR).startTokenIndex();
        GrammarDescriptor invalidLet = withMetadata(let, new DescriptorMetadata(
                let.metadata().openingTokenIndex(), let.metadata().closingTokenIndex(),
                let.metadata().primaryTokenIndex(), let.metadata().commaTokenIndices(),
                let.metadata().modifierTokenIndices(), List.of(plusIndex)));
        expectThrows(IllegalArgumentException.class,
                () -> replaceDescriptor(operatorProgram, let, invalidLet)
                        .validateAgainst(operatorSource));

        LexedSource importSource = lex("import a->b->{value}");
        GrammarProgram importProgram = success(importSource);
        GrammarDescriptor importDeclaration = importProgram.forms().getFirst();
        GrammarDescriptor importPath = findFirst(importDeclaration, ProductionKind.IMPORT_PATH);
        int nestedImportArrow = importPath.metadata().operatorTokenIndices().getFirst();
        GrammarDescriptor invalidImport = withMetadata(importDeclaration, new DescriptorMetadata(
                -1, -1, importDeclaration.metadata().primaryTokenIndex(), List.of(),
                importDeclaration.metadata().modifierTokenIndices(), List.of(nestedImportArrow)));
        expectThrows(IllegalArgumentException.class,
                () -> replaceDescriptor(importProgram, importDeclaration, invalidImport)
                        .validateAgainst(importSource));

        LexedSource namespaceSource = lex("let value = a->b->:.field");
        GrammarProgram namespaceProgram = success(namespaceSource);
        GrammarDescriptor namespace = findFirst(
                namespaceProgram.root(), ProductionKind.NAMESPACE_MEMBER_ACCESS);
        GrammarDescriptor namespacePath = findFirst(namespace, ProductionKind.NAMESPACE_PATH);
        GrammarDescriptor invalidPath = withMetadata(namespacePath, new DescriptorMetadata(
                -1, -1, -1, List.of(), List.of(), List.of()));
        expectThrows(IllegalArgumentException.class,
                () -> replaceDescriptor(namespaceProgram, namespacePath, invalidPath)
                        .validateAgainst(namespaceSource));
    }

    private static void assertNestedPrimaryRejected(String source, ProductionKind kind) {
        assertNestedPrimaryRejected(source, kind, kind);
    }

    private static void assertNestedPrimaryRejected(
            String source, ProductionKind outerKind, ProductionKind nestedKind) {
        LexedSource lexed = lex(source);
        GrammarProgram program = success(lexed);
        GrammarDescriptor outer = findFirst(program.root(), outerKind);
        GrammarDescriptor nested = null;
        for (GrammarDescriptor child : outer.children()) {
            nested = findFirstOrNull(child, nestedKind);
            if (nested != null) {
                break;
            }
        }
        if (nested == null) {
            throw new AssertionError("descriptor has no nested " + nestedKind);
        }
        DescriptorMetadata metadata = outer.metadata();
        GrammarDescriptor invalid = withMetadata(outer, new DescriptorMetadata(
                metadata.openingTokenIndex(), metadata.closingTokenIndex(),
                nested.metadata().primaryTokenIndex(), metadata.commaTokenIndices(),
                metadata.modifierTokenIndices(), metadata.operatorTokenIndices()));
        expectThrows(IllegalArgumentException.class,
                () -> replaceDescriptor(program, outer, invalid).validateAgainst(lexed));
    }

    private static GrammarDescriptor withMetadata(
            GrammarDescriptor descriptor, DescriptorMetadata metadata) {
        return new GrammarDescriptor(
                descriptor.kind(), descriptor.startTokenIndex(), descriptor.endTokenIndex(),
                descriptor.children(), metadata);
    }

    private static GrammarProgram replaceDescriptor(
            GrammarProgram program,
            GrammarDescriptor target,
            GrammarDescriptor replacement) {
        GrammarDescriptor root = replaceDescriptor(program.root(), target, replacement);
        return new GrammarProgram(
                program.sourceId(), program.sourceRevision(), program.tokenCount(), root);
    }

    private static GrammarDescriptor replaceDescriptor(
            GrammarDescriptor descriptor,
            GrammarDescriptor target,
            GrammarDescriptor replacement) {
        if (descriptor == target) {
            return replacement;
        }
        List<GrammarDescriptor> children = descriptor.children().stream()
                .map(child -> replaceDescriptor(child, target, replacement))
                .toList();
        if (children.equals(descriptor.children())) {
            return descriptor;
        }
        return new GrammarDescriptor(
                descriptor.kind(), descriptor.startTokenIndex(), descriptor.endTokenIndex(),
                children, descriptor.metadata());
    }

    private static GrammarProgram replaceFirstForm(GrammarProgram program, GrammarDescriptor replacement) {
        List<GrammarDescriptor> children = new java.util.ArrayList<>(program.root().children());
        children.set(0, replacement);
        GrammarDescriptor root = new GrammarDescriptor(
                ProductionKind.PROGRAM, 0, program.tokenCount(), children, program.root().metadata());
        return new GrammarProgram(program.sourceId(), program.sourceRevision(), program.tokenCount(), root);
    }

    private static void deferredProductionsAreAbsent() {
        GrammarProgram program = success(
                "let className = Class let matchName = Match let iterName = Iter "
                        + "let notOperator = (nor 1 2)");
        Set<ProductionKind> kinds = collectKinds(program.root());
        check(kinds.stream().noneMatch(kind -> kind.name().equals("MATCH") || kind.name().equals("ITER")),
                "deferred Match and Iter productions are absent");
        check(kinds.contains(ProductionKind.IDENTIFIER),
                "deferred words remain ordinary identifier tokens");
        check(kinds.stream().noneMatch(kind -> kind.name().contains("BITWISE")
                        || kind.name().contains("SHIFT")),
                "deferred bitwise and shift productions are absent");
        success("let x = (Match value)");
        success("let x = (Iter value)");
    }

    @SuppressWarnings("unchecked")
    private static GrammarProgram success(String source) {
        return success(lex(source));
    }

    @SuppressWarnings("unchecked")
    private static GrammarProgram success(LexedSource source) {
        PhaseResult<GrammarProgram> result = GrammarMatcher.match(source);
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("expected grammar success: "
                    + result.diagnostics().stream().map(Diagnostic::render).toList());
        }
        return ((PhaseResult.Success<GrammarProgram>) success).value();
    }

    private static void expectFailure(String source, io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code) {
        LexedSource lexed = lex(source);
        PhaseResult<GrammarProgram> result = GrammarMatcher.match(lexed);
        check(result instanceof PhaseResult.Failure<?>, "expected grammar failure for " + source);
        check(result.optionalValue().isEmpty(), "grammar failure publishes no partial descriptor tree");
        check(result.diagnostics().getFirst().code().equals(code),
                "unexpected grammar diagnostic for " + source + ": "
                        + result.diagnostics().getFirst().render());
    }

    @SuppressWarnings("unchecked")
    private static LexedSource lex(String source) {
        PhaseResult<SourceSnapshot> captured = SourceSnapshot.capture(
                SourceId.path("grammar-test.lyra"),
                PhysicalSourceKey.uri(URI.create("memory:grammar-test")),
                source.getBytes(StandardCharsets.UTF_8));
        if (!(captured instanceof PhaseResult.Success<?> capturedSuccess)) {
            throw new AssertionError("test source did not capture");
        }
        SourceSnapshot snapshot = ((PhaseResult.Success<SourceSnapshot>) capturedSuccess).value();
        PhaseResult<LexedSource> lexed = Lexer.lex(snapshot);
        if (!(lexed instanceof PhaseResult.Success<?> lexedSuccess)) {
            throw new AssertionError("test source did not lex: "
                    + lexed.diagnostics().stream().map(Diagnostic::render).toList());
        }
        return ((PhaseResult.Success<LexedSource>) lexedSuccess).value();
    }

    private static Set<ProductionKind> collectKinds(GrammarDescriptor descriptor) {
        Set<ProductionKind> kinds = EnumSet.of(descriptor.kind());
        for (GrammarDescriptor child : descriptor.children()) {
            kinds.addAll(collectKinds(child));
        }
        return kinds;
    }

    private static GrammarDescriptor findFirst(GrammarDescriptor descriptor, ProductionKind kind) {
        GrammarDescriptor found = findFirstOrNull(descriptor, kind);
        if (found == null) {
            throw new AssertionError("descriptor tree has no " + kind);
        }
        return found;
    }

    private static GrammarDescriptor findFirstOrNull(GrammarDescriptor descriptor, ProductionKind kind) {
        if (descriptor.kind() == kind) {
            return descriptor;
        }
        for (GrammarDescriptor child : descriptor.children()) {
            GrammarDescriptor found = findFirstOrNull(child, kind);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void assertNoIllegalSiblingOverlap(GrammarDescriptor descriptor) {
        int previousEnd = descriptor.startTokenIndex();
        for (GrammarDescriptor child : descriptor.children()) {
            check(child.startTokenIndex() >= previousEnd,
                    "nested siblings do not overlap in " + descriptor.kind());
            check(child.endTokenIndex() <= descriptor.endTokenIndex(),
                    "nested child remains inside " + descriptor.kind());
            previousEnd = child.endTokenIndex();
            assertNoIllegalSiblingOverlap(child);
        }
    }

    private static void expectUnsupported(Runnable action) {
        expectThrows(UnsupportedOperationException.class, action);
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
}
