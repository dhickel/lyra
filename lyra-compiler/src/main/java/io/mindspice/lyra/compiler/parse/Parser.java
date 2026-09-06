package io.mindspice.lyra.compiler.parse;

import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarDescriptor;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.grammar.ProductionKind;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.LiteralValue;
import io.mindspice.lyra.compiler.lex.ModifierKind;
import io.mindspice.lyra.compiler.lex.Token;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Deterministic replay of an immutable {@link GrammarProgram}. */
public final class Parser {
    private Parser() {
    }

    /**
     * Replays {@code grammar} against the exact lexical artifact that produced
     * it.  Grammar validation happens before the first AST node is published.
     */
    public static PhaseResult<SyntaxProgram> parse(
            LexedSource source, GrammarProgram grammar) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(grammar, "grammar");

        if (source.hasErrors()) {
            return PhaseResult.failure(source.diagnostics());
        }

        try {
            grammar.validateAgainst(source);
        } catch (RuntimeException failure) {
            throw new ParserInvariantException(
                    "grammar program is not a valid replay input", failure);
        }

        try {
            SyntaxProgram program = new Replay(source, grammar).run();
            return PhaseResult.success(program, source.diagnostics());
        } catch (ParserInvariantException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ParserInvariantException(
                    "grammar replay violated a compiler invariant", failure);
        }
    }

    /** Phase-operation spelling retained for compiler pipelines. */
    public static PhaseResult<SyntaxProgram> process(
            LexedSource source, GrammarProgram grammar) {
        return parse(source, grammar);
    }

    private static final class Replay {
        private final LexedSource source;
        private final GrammarProgram grammar;
        private final ReplaySource sourceView;
        private final Cursor cursor;

        private Replay(LexedSource source, GrammarProgram grammar) {
            this.source = source;
            this.grammar = grammar;
            this.sourceView = new ReplaySource(source);
            this.cursor = new Cursor(sourceView);
        }

        private SyntaxProgram run() {
            Object value = replay(grammar.root());
            SyntaxProgram program = cast(value, SyntaxProgram.class, grammar.root(), "program");
            grammar.assertReplayConsumed(0, cursor.position());
            if (cursor.position() != source.tokens().size()) {
                throw invariant("program replay did not consume the lexical EOF", grammar.root());
            }
            return program;
        }

        /** Every descriptor, including punctuation/helper descriptors, is replayed here. */
        private Object replay(GrammarDescriptor descriptor) {
            cursor.begin(descriptor);
            Object value = switch (descriptor.kind()) {
                case PROGRAM -> replayProgram(descriptor);
                case EOF -> replayEof(descriptor);

                case IMPORT_DECLARATION -> replayImportDeclaration(descriptor);
                case IMPORT_PATH -> replayImportPath(descriptor);
                case IMPORT_ALIAS -> replayImportAlias(descriptor);
                case IMPORT_SELECTION -> replayImportSelection(descriptor);
                case IMPORT_ITEM -> replayImportItem(descriptor);

                case LET_BINDING -> replayLetBinding(descriptor);
                case REASSIGNMENT -> replayReassignment(descriptor);
                case PREFIX_ASSIGNMENT -> replayPrefixAssignment(descriptor);
                case BLOCK -> replayBlock(descriptor);
                case CONDITIONAL -> replayConditional(descriptor);
                case PREDICATE_BINDING -> replayPredicateBinding(descriptor);
                case COALESCE -> replayCoalesce(descriptor);

                case IDENTIFIER -> replayIdentifier(descriptor);
                case LITERAL -> replayLiteral(descriptor);
                case TYPE_NAME -> replayTypeName(descriptor);
                case UNIT_LITERAL -> replayUnitLiteral(descriptor);

                case LAMBDA -> replayLambda(descriptor);
                case COMPACT_LAMBDA -> replayCompactLambda(descriptor);
                case PARAMETER_LIST -> replayParameterList(descriptor);
                case PARAMETER -> replayParameter(descriptor);

                case TYPE_ANNOTATION -> replayTypeAnnotation(descriptor);
                case RETURN_ANNOTATION -> replayReturnAnnotation(descriptor);
                case TYPE_CONTRACT -> replayTypeContract(descriptor);
                case PRIMITIVE_TYPE -> replayPrimitiveType(descriptor);
                case ARRAY_TYPE -> replayArrayType(descriptor);
                case TUPLE_TYPE -> replayTupleType(descriptor);
                case FUNCTION_TYPE -> replayFunctionType(descriptor);
                case TYPE_ARGUMENT_LIST -> replayTypeArgumentList(descriptor);
                case TYPE_SEPARATOR -> replayTokenSpan(descriptor, TokenKind.SEMICOLON);

                case CALLABLE_CALL -> replayCallableCall(descriptor);
                case CALL_CONTENT -> replayCallContent(descriptor);
                case CALL_TARGET -> replayCallTarget(descriptor);
                case DIRECT_CALL -> replayDirectCall(descriptor);
                case MEMBER_ACCESS -> replayMemberAccess(descriptor);
                case NAMESPACE_MEMBER_ACCESS -> replayNamespaceMemberAccess(descriptor);
                case NAMESPACE_DIRECT_CALL -> replayNamespaceDirectCall(descriptor);
                case NAMESPACE_PATH -> replayNamespacePath(descriptor);
                case INDEX_ACCESS -> replayIndexAccess(descriptor);

                case ARGUMENT_LIST -> replayArgumentList(descriptor);
                case ARGUMENT -> replayArgument(descriptor);
                case OPERATOR_OPERANDS -> replayOperatorOperands(descriptor);
                case OPERATOR -> replayOperator(descriptor);
                case OPERATOR_S_EXPRESSION -> replayOperatorSExpression(descriptor);
                case OPERATOR_BRACKET -> replayOperatorBracket(descriptor);

                case ARRAY_LITERAL -> replayArrayLiteral(descriptor);
                case TUPLE_LITERAL -> replayTupleLiteral(descriptor);
                case TYPE_CONVERSION -> replayTypeConversion(descriptor);

                case MEMBER_NAME -> replayMemberName(descriptor);
                case MODIFIER -> replayModifier(descriptor);
                case COMMA -> replayTokenSpan(descriptor, TokenKind.COMMA);
            };
            try {
                descriptor.assertConsumed(cursor.position());
            } catch (RuntimeException failure) {
                throw new ParserInvariantException(
                        "descriptor replay range mismatch for " + descriptor, failure);
            }
            return value;
        }

        private Object replayProgram(GrammarDescriptor descriptor) {
            List<SyntaxNode.ImportDeclaration> imports = new ArrayList<>();
            List<SyntaxNode.Form> forms = new ArrayList<>();
            for (GrammarDescriptor child : descriptor.children()) {
                Object value = replay(child);
                if (child.kind() == ProductionKind.EOF) {
                    if (value != null) {
                        throw invariant("EOF replay produced a node", child);
                    }
                } else if (value instanceof SyntaxNode.ImportDeclaration declaration) {
                    imports.add(declaration);
                } else if (value instanceof SyntaxNode.Form form) {
                    forms.add(form);
                } else {
                    throw invariant(
                            "program child did not replay to an import or form", child);
                }
            }
            return new SyntaxProgram(
                    grammar.sourceId(),
                    grammar.sourceRevision(),
                    imports,
                    forms,
                    sourceView.span(descriptor));
        }

        private Object replayEof(GrammarDescriptor descriptor) {
            cursor.consume(TokenKind.EOF, descriptor);
            return null;
        }

        private Object replayImportDeclaration(GrammarDescriptor descriptor) {
            SourceSpan importSpan = cursor.consume(TokenKind.IMPORT, descriptor);
            List<GrammarDescriptor> children = descriptor.children();
            int childIndex = 0;
            List<SyntaxNode.Modifier> modifiers = new ArrayList<>();
            while (childIndex < children.size()
                    && children.get(childIndex).kind() == ProductionKind.MODIFIER) {
                modifiers.add(asModifier(replay(children.get(childIndex)), children.get(childIndex)));
                childIndex++;
            }
            if (childIndex >= children.size()
                    || children.get(childIndex).kind() != ProductionKind.IMPORT_PATH) {
                throw invariant("import declaration lacks its replay path", descriptor);
            }
            SyntaxNode.ImportPath path = asImportPath(
                    replay(children.get(childIndex)), children.get(childIndex));
            childIndex++;

            SyntaxNode.ImportAlias alias = null;
            SyntaxNode.ImportSelection selection = null;
            SourceSpan selectionArrow = null;
            if (childIndex < children.size()) {
                GrammarDescriptor suffix = children.get(childIndex++);
                if (suffix.kind() == ProductionKind.IMPORT_ALIAS) {
                    alias = asImportAlias(replay(suffix), suffix);
                } else if (suffix.kind() == ProductionKind.IMPORT_SELECTION) {
                    selectionArrow = cursor.consume(TokenKind.ARROW, descriptor);
                    selection = asImportSelection(replay(suffix), suffix);
                } else {
                    throw invariant("import declaration has an unknown suffix", suffix);
                }
            }
            if (childIndex != children.size()) {
                throw invariant("import declaration has unconsumed children", descriptor);
            }
            return new SyntaxNode.ImportDeclaration(
                    modifiers,
                    path,
                    Optional.ofNullable(alias),
                    Optional.ofNullable(selection),
                    importSpan,
                    Optional.ofNullable(selectionArrow),
                    sourceView.span(descriptor));
        }

        private Object replayImportPath(GrammarDescriptor descriptor) {
            List<SyntaxNode.Identifier> segments = new ArrayList<>();
            List<SourceSpan> arrows = new ArrayList<>();
            List<GrammarDescriptor> children = descriptor.children();
            for (int index = 0; index < children.size(); index++) {
                GrammarDescriptor child = children.get(index);
                segments.add(asIdentifier(replay(child), child));
                if (index + 1 < children.size()) {
                    arrows.add(cursor.consume(TokenKind.ARROW, descriptor));
                }
            }
            return new SyntaxNode.ImportPath(segments, arrows, sourceView.span(descriptor));
        }

        private Object replayImportAlias(GrammarDescriptor descriptor) {
            SourceSpan asSpan = cursor.consume(TokenKind.AS, descriptor);
            if (descriptor.children().size() != 1) {
                throw invariant("import alias must contain one name", descriptor);
            }
            GrammarDescriptor child = descriptor.children().getFirst();
            SyntaxNode.Identifier alias = asIdentifier(replay(child), child);
            return new SyntaxNode.ImportAlias(alias, asSpan, sourceView.span(descriptor));
        }

        private Object replayImportSelection(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.LEFT_BRACE, descriptor);
            List<SyntaxNode.ImportItem> items = new ArrayList<>();
            for (GrammarDescriptor child : descriptor.children()) {
                if (child.kind() != ProductionKind.IMPORT_ITEM) {
                    throw invariant("selective import has a non-item child", child);
                }
                items.add(asImportItem(replay(child), child));
            }
            SourceSpan closing = cursor.consume(TokenKind.RIGHT_BRACE, descriptor);
            return new SyntaxNode.ImportSelection(items, opening, closing, sourceView.span(descriptor));
        }

        private Object replayImportItem(GrammarDescriptor descriptor) {
            List<GrammarDescriptor> children = descriptor.children();
            if (children.isEmpty() || children.getFirst().kind() != ProductionKind.IDENTIFIER) {
                throw invariant("import item lacks its imported name", descriptor);
            }
            GrammarDescriptor nameDescriptor = children.getFirst();
            SyntaxNode.Identifier name = asIdentifier(replay(nameDescriptor), nameDescriptor);
            SyntaxNode.ImportAlias alias = null;
            if (children.size() == 2) {
                GrammarDescriptor aliasDescriptor = children.get(1);
                if (aliasDescriptor.kind() != ProductionKind.IMPORT_ALIAS) {
                    throw invariant("import item has an invalid alias child", aliasDescriptor);
                }
                alias = asImportAlias(replay(aliasDescriptor), aliasDescriptor);
            } else if (children.size() != 1) {
                throw invariant("import item has too many children", descriptor);
            }
            return new SyntaxNode.ImportItem(
                    name, Optional.ofNullable(alias), sourceView.span(descriptor));
        }

        private Object replayLetBinding(GrammarDescriptor descriptor) {
            SourceSpan letSpan = cursor.consume(TokenKind.LET, descriptor);
            List<GrammarDescriptor> children = descriptor.children();
            int childIndex = 0;
            List<SyntaxNode.Modifier> modifiers = new ArrayList<>();
            while (childIndex < children.size()
                    && children.get(childIndex).kind() == ProductionKind.MODIFIER) {
                GrammarDescriptor modifier = children.get(childIndex++);
                modifiers.add(asModifier(replay(modifier), modifier));
            }
            if (childIndex >= children.size()
                    || children.get(childIndex).kind() != ProductionKind.IDENTIFIER) {
                throw invariant("let binding lacks its name", descriptor);
            }
            GrammarDescriptor nameDescriptor = children.get(childIndex++);
            SyntaxNode.Identifier name = asIdentifier(replay(nameDescriptor), nameDescriptor);
            SyntaxNode.TypeAnnotation annotation = null;
            if (childIndex < children.size()
                    && children.get(childIndex).kind() == ProductionKind.TYPE_ANNOTATION) {
                GrammarDescriptor annotationDescriptor = children.get(childIndex++);
                annotation = asTypeAnnotation(replay(annotationDescriptor), annotationDescriptor);
            }
            SourceSpan equalsSpan = cursor.consume(TokenKind.EQUAL, descriptor);
            if (childIndex >= children.size()) {
                throw invariant("let binding lacks its initializer", descriptor);
            }
            GrammarDescriptor initializerDescriptor = children.get(childIndex++);
            SyntaxNode.Expression initializer = asExpression(
                    replay(initializerDescriptor), initializerDescriptor);
            if (childIndex != children.size()) {
                throw invariant("let binding has unconsumed children", descriptor);
            }
            return new SyntaxNode.LetBinding(
                    modifiers,
                    name,
                    Optional.ofNullable(annotation),
                    initializer,
                    letSpan,
                    equalsSpan,
                    sourceView.span(descriptor));
        }

        private Object replayReassignment(GrammarDescriptor descriptor) {
            List<GrammarDescriptor> children = descriptor.children();
            if (children.size() != 2) {
                throw invariant("reassignment must have target and value", descriptor);
            }
            boolean enclosed = descriptor.metadata().openingTokenIndex() >= 0;
            SourceSpan opening = enclosed
                    ? cursor.consume(TokenKind.LEFT_PAREN, descriptor)
                    : null;
            GrammarDescriptor targetDescriptor = children.getFirst();
            SyntaxNode.Expression target = asExpression(replay(targetDescriptor), targetDescriptor);
            SourceSpan assignment = cursor.consume(TokenKind.COLON_EQUAL, descriptor);
            GrammarDescriptor valueDescriptor = children.get(1);
            SyntaxNode.Expression value = asExpression(replay(valueDescriptor), valueDescriptor);
            SourceSpan closing = enclosed
                    ? cursor.consume(TokenKind.RIGHT_PAREN, descriptor)
                    : null;
            return new SyntaxNode.Reassignment(
                    target,
                    value,
                    enclosed,
                    assignment,
                    Optional.ofNullable(opening),
                    Optional.ofNullable(closing),
                    sourceView.span(descriptor));
        }

        private Object replayPrefixAssignment(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.LEFT_PAREN, descriptor);
            SourceSpan assignment = cursor.consume(TokenKind.COLON_EQUAL, descriptor);
            if (descriptor.children().size() != 2) {
                throw invariant("prefix assignment must have two operands", descriptor);
            }
            GrammarDescriptor targetDescriptor = descriptor.children().getFirst();
            SyntaxNode.Expression target = asExpression(replay(targetDescriptor), targetDescriptor);
            GrammarDescriptor valueDescriptor = descriptor.children().get(1);
            SyntaxNode.Expression value = asExpression(replay(valueDescriptor), valueDescriptor);
            SourceSpan closing = cursor.consume(TokenKind.RIGHT_PAREN, descriptor);
            return new SyntaxNode.PrefixAssignment(
                    target, value, opening, assignment, closing, sourceView.span(descriptor));
        }

        private Object replayBlock(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.LEFT_BRACE, descriptor);
            List<SyntaxNode.Form> forms = new ArrayList<>();
            for (GrammarDescriptor child : descriptor.children()) {
                Object value = replay(child);
                if (!(value instanceof SyntaxNode.Form form)) {
                    throw invariant("block child did not replay to a form", child);
                }
                forms.add(form);
            }
            SourceSpan closing = cursor.consume(TokenKind.RIGHT_BRACE, descriptor);
            return new SyntaxNode.Block(forms, opening, closing, sourceView.span(descriptor));
        }

        private Object replayConditional(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.LEFT_PAREN, descriptor);
            List<GrammarDescriptor> children = descriptor.children();
            if (children.size() < 2 || children.size() > 4) {
                throw invariant("conditional has an invalid child count", descriptor);
            }
            int childIndex = 0;
            GrammarDescriptor predicateDescriptor = children.get(childIndex++);
            SyntaxNode.Expression predicate = asExpression(
                    replay(predicateDescriptor), predicateDescriptor);
            SyntaxNode.PredicateBinding binding = null;
            if (childIndex < children.size()
                    && children.get(childIndex).kind() == ProductionKind.PREDICATE_BINDING) {
                GrammarDescriptor bindingDescriptor = children.get(childIndex++);
                binding = asPredicateBinding(replay(bindingDescriptor), bindingDescriptor);
            }
            SourceSpan arrow = cursor.consume(TokenKind.ARROW, descriptor);
            if (childIndex >= children.size()) {
                throw invariant("conditional lacks its then branch", descriptor);
            }
            GrammarDescriptor thenDescriptor = children.get(childIndex++);
            SyntaxNode.Expression thenBranch = asExpression(replay(thenDescriptor), thenDescriptor);
            SourceSpan colon = null;
            SyntaxNode.Expression elseBranch = null;
            if (childIndex < children.size()) {
                colon = cursor.consume(TokenKind.COLON, descriptor);
                GrammarDescriptor elseDescriptor = children.get(childIndex++);
                elseBranch = asExpression(replay(elseDescriptor), elseDescriptor);
            }
            if (childIndex != children.size()) {
                throw invariant("conditional has unconsumed children", descriptor);
            }
            SourceSpan closing = cursor.consume(TokenKind.RIGHT_PAREN, descriptor);
            return new SyntaxNode.Conditional(
                    predicate,
                    Optional.ofNullable(binding),
                    thenBranch,
                    Optional.ofNullable(elseBranch),
                    opening,
                    arrow,
                    Optional.ofNullable(colon),
                    closing,
                    sourceView.span(descriptor));
        }

        private Object replayPredicateBinding(GrammarDescriptor descriptor) {
            if (descriptor.children().size() != 1) {
                throw invariant("predicate binding must contain one identifier", descriptor);
            }
            GrammarDescriptor child = descriptor.children().getFirst();
            SyntaxNode.Identifier name = asIdentifier(replay(child), child);
            return new SyntaxNode.PredicateBinding(name, sourceView.span(descriptor));
        }

        private Object replayCoalesce(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.LEFT_PAREN, descriptor);
            if (descriptor.children().size() != 2) {
                throw invariant("coalescing must have value and fallback", descriptor);
            }
            GrammarDescriptor valueDescriptor = descriptor.children().getFirst();
            SyntaxNode.Expression value = asExpression(replay(valueDescriptor), valueDescriptor);
            SourceSpan colon = cursor.consume(TokenKind.COLON, descriptor);
            GrammarDescriptor fallbackDescriptor = descriptor.children().get(1);
            SyntaxNode.Expression fallback = asExpression(
                    replay(fallbackDescriptor), fallbackDescriptor);
            SourceSpan closing = cursor.consume(TokenKind.RIGHT_PAREN, descriptor);
            return new SyntaxNode.Coalesce(
                    value, fallback, opening, colon, closing, sourceView.span(descriptor));
        }

        private Object replayIdentifier(GrammarDescriptor descriptor) {
            Token token = cursor.currentToken(descriptor);
            cursor.consume(TokenKind.IDENTIFIER, descriptor);
            String name = token.identifier().orElseThrow(() ->
                    invariant("identifier token lacks its decoded name", descriptor));
            return new SyntaxNode.Identifier(name, token.lexeme(), token.span());
        }

        private Object replayLiteral(GrammarDescriptor descriptor) {
            Token token = cursor.currentToken(descriptor);
            if (!token.kind().isLiteral()) {
                throw invariant("literal descriptor points to a non-literal token", descriptor);
            }
            cursor.consume(token.kind(), descriptor);
            LiteralValue literal = token.literal().orElseThrow(() ->
                    invariant("literal token lacks its decoded value", descriptor));
            SourceSpan span = token.span();
            return switch (literal) {
                case LiteralValue.BooleanLiteral value ->
                        new SyntaxNode.BooleanLiteral(value.value(), token.lexeme(), span);
                case LiteralValue.NilLiteral value ->
                        new SyntaxNode.NilLiteral(token.lexeme(), span);
                case LiteralValue.IntegerLiteral value ->
                        new SyntaxNode.IntegerLiteral(
                                value.value(), value.suffix(), token.lexeme(), span);
                case LiteralValue.FloatLiteral value ->
                        new SyntaxNode.FloatLiteral(
                                value.value(), value.suffix(), token.lexeme(), span);
                case LiteralValue.StringLiteral value ->
                        new SyntaxNode.StringLiteral(value.value(), token.lexeme(), span);
                case LiteralValue.CharLiteral value ->
                        new SyntaxNode.CharacterLiteral(value.value(), token.lexeme(), span);
            };
        }

        private Object replayTypeName(GrammarDescriptor descriptor) {
            Token token = cursor.currentToken(descriptor);
            cursor.consume(TokenKind.TYPE_NAME, descriptor);
            return token;
        }

        private Object replayUnitLiteral(GrammarDescriptor descriptor) {
            int primary = descriptor.metadata().primaryTokenIndex();
            if (primary < 0) {
                cursor.consume(TokenKind.LEFT_PAREN, descriptor);
                cursor.consume(TokenKind.RIGHT_PAREN, descriptor);
                return new SyntaxNode.UnitLiteral(
                        SyntaxNode.UnitForm.PARENTHESIZED,
                        sourceView.sourceText(descriptor),
                        sourceView.span(descriptor));
            }
            if (descriptor.children().size() != 2) {
                throw invariant("typed Unit must have a type prefix and argument list", descriptor);
            }
            Object prefixValue = replay(descriptor.children().getFirst());
            Token prefix = cast(prefixValue, Token.class, descriptor.children().getFirst(), "Unit prefix");
            SyntaxNode.ArgumentList arguments = asArgumentList(
                    replay(descriptor.children().get(1)), descriptor.children().get(1));
            if (!arguments.expressions().isEmpty()) {
                throw invariant("typed Unit argument list is not empty", descriptor);
            }
            SyntaxNode.UnitForm form = switch (prefix.lexeme()) {
                case "Array" -> SyntaxNode.UnitForm.ARRAY;
                case "Tuple" -> SyntaxNode.UnitForm.TUPLE;
                default -> throw invariant("unknown typed Unit prefix", descriptor);
            };
            return new SyntaxNode.UnitLiteral(
                    form, sourceView.sourceText(descriptor), sourceView.span(descriptor));
        }

        private Object replayLambda(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.LEFT_PAREN, descriptor);
            SourceSpan arrow = cursor.consume(TokenKind.LAMBDA_ARROW, descriptor);
            List<GrammarDescriptor> children = descriptor.children();
            int childIndex = 0;
            List<SyntaxNode.Modifier> returnModifiers = new ArrayList<>();
            while (childIndex < children.size()
                    && children.get(childIndex).kind() == ProductionKind.MODIFIER) {
                GrammarDescriptor modifier = children.get(childIndex++);
                returnModifiers.add(asModifier(replay(modifier), modifier));
            }
            SyntaxNode.ReturnAnnotation annotation = null;
            if (childIndex < children.size()
                    && children.get(childIndex).kind() == ProductionKind.RETURN_ANNOTATION) {
                GrammarDescriptor annotationDescriptor = children.get(childIndex++);
                annotation = asReturnAnnotation(replay(annotationDescriptor), annotationDescriptor);
            }
            if (childIndex >= children.size()
                    || children.get(childIndex).kind() != ProductionKind.PARAMETER_LIST) {
                throw invariant("lambda lacks its parameter list", descriptor);
            }
            GrammarDescriptor parametersDescriptor = children.get(childIndex++);
            SyntaxNode.ParameterList parameters = asParameterList(
                    replay(parametersDescriptor), parametersDescriptor);
            if (childIndex >= children.size()) {
                throw invariant("lambda lacks its body", descriptor);
            }
            GrammarDescriptor bodyDescriptor = children.get(childIndex++);
            SyntaxNode.Expression body = asExpression(replay(bodyDescriptor), bodyDescriptor);
            if (childIndex != children.size()) {
                throw invariant("lambda has unconsumed children", descriptor);
            }
            SourceSpan closing = cursor.consume(TokenKind.RIGHT_PAREN, descriptor);
            return new SyntaxNode.Lambda(
                    returnModifiers,
                    Optional.ofNullable(annotation),
                    parameters,
                    body,
                    opening,
                    arrow,
                    closing,
                    sourceView.span(descriptor));
        }

        private Object replayCompactLambda(GrammarDescriptor descriptor) {
            if (descriptor.children().size() != 2) {
                throw invariant("compact lambda must have parameters and body", descriptor);
            }
            GrammarDescriptor parametersDescriptor = descriptor.children().getFirst();
            SyntaxNode.ParameterList parameters = asParameterList(
                    replay(parametersDescriptor), parametersDescriptor);
            GrammarDescriptor bodyDescriptor = descriptor.children().get(1);
            SyntaxNode.Expression body = asExpression(replay(bodyDescriptor), bodyDescriptor);
            return new SyntaxNode.CompactLambda(parameters, body, sourceView.span(descriptor));
        }

        private Object replayParameterList(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.BAR, descriptor);
            List<SyntaxNode.Parameter> parameters = new ArrayList<>();
            List<SourceSpan> commas = new ArrayList<>();
            for (GrammarDescriptor child : descriptor.children()) {
                switch (child.kind()) {
                    case PARAMETER -> parameters.add(asParameter(replay(child), child));
                    case COMMA -> commas.add(asSpan(replay(child), child, "parameter comma"));
                    default -> throw invariant("parameter list has an invalid child", child);
                }
            }
            SourceSpan closing = cursor.consume(TokenKind.BAR, descriptor);
            return new SyntaxNode.ParameterList(
                    parameters, commas, opening, closing, sourceView.span(descriptor));
        }

        private Object replayParameter(GrammarDescriptor descriptor) {
            List<GrammarDescriptor> children = descriptor.children();
            int childIndex = 0;
            List<SyntaxNode.Modifier> modifiers = new ArrayList<>();
            while (childIndex < children.size()
                    && children.get(childIndex).kind() == ProductionKind.MODIFIER) {
                GrammarDescriptor modifier = children.get(childIndex++);
                modifiers.add(asModifier(replay(modifier), modifier));
            }
            if (childIndex >= children.size()
                    || children.get(childIndex).kind() != ProductionKind.IDENTIFIER) {
                throw invariant("parameter lacks its name", descriptor);
            }
            GrammarDescriptor nameDescriptor = children.get(childIndex++);
            SyntaxNode.Identifier name = asIdentifier(replay(nameDescriptor), nameDescriptor);
            SyntaxNode.TypeAnnotation annotation = null;
            if (childIndex < children.size()) {
                if (children.get(childIndex).kind() != ProductionKind.TYPE_ANNOTATION) {
                    throw invariant("parameter has an invalid trailing child", descriptor);
                }
                GrammarDescriptor annotationDescriptor = children.get(childIndex++);
                annotation = asTypeAnnotation(replay(annotationDescriptor), annotationDescriptor);
            }
            if (childIndex != children.size()) {
                throw invariant("parameter has unconsumed children", descriptor);
            }
            return new SyntaxNode.Parameter(
                    modifiers, name, Optional.ofNullable(annotation), sourceView.span(descriptor));
        }

        private Object replayTypeAnnotation(GrammarDescriptor descriptor) {
            SourceSpan colon = cursor.consume(TokenKind.COLON, descriptor);
            if (descriptor.children().size() != 1) {
                throw invariant("type annotation must contain one type", descriptor);
            }
            GrammarDescriptor typeDescriptor = descriptor.children().getFirst();
            SyntaxNode.Type type = asType(replay(typeDescriptor), typeDescriptor);
            return new SyntaxNode.TypeAnnotation(type, colon, sourceView.span(descriptor));
        }

        private Object replayReturnAnnotation(GrammarDescriptor descriptor) {
            SourceSpan colon = cursor.consume(TokenKind.COLON, descriptor);
            if (descriptor.children().size() != 1) {
                throw invariant("return annotation must contain one type", descriptor);
            }
            GrammarDescriptor typeDescriptor = descriptor.children().getFirst();
            SyntaxNode.Type type = asType(replay(typeDescriptor), typeDescriptor);
            return new SyntaxNode.ReturnAnnotation(type, colon, sourceView.span(descriptor));
        }

        private Object replayTypeContract(GrammarDescriptor descriptor) {
            List<SyntaxNode.Modifier> modifiers = new ArrayList<>();
            SyntaxNode.Type base = null;
            for (GrammarDescriptor child : descriptor.children()) {
                if (child.kind() == ProductionKind.MODIFIER) {
                    modifiers.add(asModifier(replay(child), child));
                } else if (base == null) {
                    base = asType(replay(child), child);
                } else {
                    throw invariant("type contract has more than one base type", descriptor);
                }
            }
            if (base == null) {
                throw invariant("type contract lacks a base type", descriptor);
            }
            return new SyntaxNode.TypeContract(modifiers, base, sourceView.span(descriptor));
        }

        private Object replayPrimitiveType(GrammarDescriptor descriptor) {
            Token token = cursor.currentToken(descriptor);
            cursor.consume(TokenKind.TYPE_NAME, descriptor);
            if (!isPrimitiveTypeSpelling(token.lexeme())) {
                throw invariant(
                        "primitive type descriptor points to a composite type name", descriptor);
            }
            return new SyntaxNode.PrimitiveType(token.lexeme(), token.span());
        }

        private Object replayArrayType(GrammarDescriptor descriptor) {
            return replayCompositeType(descriptor, "Array");
        }

        private Object replayTupleType(GrammarDescriptor descriptor) {
            return replayCompositeType(descriptor, "Tuple");
        }

        private Object replayFunctionType(GrammarDescriptor descriptor) {
            return replayCompositeType(descriptor, "Fn");
        }

        private Object replayCompositeType(GrammarDescriptor descriptor, String expectedName) {
            if (descriptor.children().size() != 2) {
                throw invariant(expectedName + " type must have a prefix and type arguments", descriptor);
            }
            GrammarDescriptor prefixDescriptor = descriptor.children().getFirst();
            Token prefix = cast(
                    replay(prefixDescriptor), Token.class, prefixDescriptor, expectedName + " prefix");
            if (!prefix.lexeme().equals(expectedName)) {
                throw invariant("composite type prefix spelling does not match its descriptor", descriptor);
            }
            GrammarDescriptor argumentsDescriptor = descriptor.children().get(1);
            SyntaxNode.TypeArgumentList arguments = asTypeArgumentList(
                    replay(argumentsDescriptor), argumentsDescriptor);
            return switch (expectedName) {
                case "Array" -> new SyntaxNode.ArrayType(
                        prefix.lexeme(), arguments, sourceView.span(descriptor));
                case "Tuple" -> new SyntaxNode.TupleType(
                        prefix.lexeme(), arguments, sourceView.span(descriptor));
                case "Fn" -> new SyntaxNode.FunctionType(
                        prefix.lexeme(), arguments, sourceView.span(descriptor));
                default -> throw invariant("unknown composite type", descriptor);
            };
        }

        private Object replayTypeArgumentList(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.LESS, descriptor);
            List<SyntaxNode.Type> types = new ArrayList<>();
            List<SourceSpan> commas = new ArrayList<>();
            OptionalInt separatorPosition = OptionalInt.empty();
            Optional<SourceSpan> separatorSpan = Optional.empty();
            for (GrammarDescriptor child : descriptor.children()) {
                switch (child.kind()) {
                    case COMMA -> commas.add(asSpan(replay(child), child, "type comma"));
                    case TYPE_SEPARATOR -> {
                        if (separatorPosition.isPresent()) {
                            throw invariant("type argument list has repeated function separators", descriptor);
                        }
                        separatorPosition = OptionalInt.of(types.size());
                        separatorSpan = Optional.of(asSpan(replay(child), child, "type separator"));
                    }
                    default -> types.add(asType(replay(child), child));
                }
            }
            SourceSpan closing = cursor.consume(TokenKind.GREATER, descriptor);
            return new SyntaxNode.TypeArgumentList(
                    types,
                    commas,
                    separatorPosition,
                    separatorSpan,
                    opening,
                    closing,
                    sourceView.span(descriptor));
        }

        private Object replayCallableCall(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.LEFT_PAREN, descriptor);
            if (descriptor.children().size() != 1
                    || descriptor.children().getFirst().kind() != ProductionKind.CALL_CONTENT) {
                throw invariant("callable call must have one call-content child", descriptor);
            }
            GrammarDescriptor contentDescriptor = descriptor.children().getFirst();
            CallContent content = cast(
                    replay(contentDescriptor), CallContent.class, contentDescriptor, "call content");
            SourceSpan closing = cursor.consume(TokenKind.RIGHT_PAREN, descriptor);
            return new SyntaxNode.CallableCall(
                    content.target,
                    content.arguments,
                    content.commaSpans,
                    opening,
                    closing,
                    sourceView.span(descriptor));
        }

        private Object replayCallContent(GrammarDescriptor descriptor) {
            List<GrammarDescriptor> children = descriptor.children();
            if (children.isEmpty() || children.getFirst().kind() != ProductionKind.CALL_TARGET) {
                throw invariant("call content lacks its target", descriptor);
            }
            GrammarDescriptor targetDescriptor = children.getFirst();
            SyntaxNode.Expression target = asExpression(replay(targetDescriptor), targetDescriptor);
            List<SyntaxNode.Expression> arguments = new ArrayList<>();
            List<SourceSpan> commas = new ArrayList<>();
            for (int index = 1; index < children.size(); index++) {
                GrammarDescriptor child = children.get(index);
                switch (child.kind()) {
                    case ARGUMENT -> arguments.add(asExpression(replay(child), child));
                    case COMMA -> commas.add(asSpan(replay(child), child, "call comma"));
                    default -> throw invariant("call content has an invalid child", child);
                }
            }
            return new CallContent(target, arguments, commas);
        }

        private Object replayCallTarget(GrammarDescriptor descriptor) {
            if (descriptor.children().size() != 1) {
                throw invariant("call target must wrap one expression", descriptor);
            }
            GrammarDescriptor child = descriptor.children().getFirst();
            return asExpression(replay(child), child);
        }

        private Object replayDirectCall(GrammarDescriptor descriptor) {
            List<GrammarDescriptor> children = descriptor.children();
            Optional<SyntaxNode.Expression> receiver = Optional.empty();
            int nameIndex;
            int argumentsIndex;
            if (children.size() == 2) {
                cursor.consume(TokenKind.DOUBLE_COLON, descriptor);
                nameIndex = 0;
                argumentsIndex = 1;
            } else if (children.size() == 3) {
                GrammarDescriptor receiverDescriptor = children.getFirst();
                receiver = Optional.of(asExpression(replay(receiverDescriptor), receiverDescriptor));
                cursor.consume(TokenKind.DOUBLE_COLON, descriptor);
                nameIndex = 1;
                argumentsIndex = 2;
            } else {
                throw invariant("direct call has an invalid child count", descriptor);
            }
            GrammarDescriptor nameDescriptor = children.get(nameIndex);
            SyntaxNode.Identifier name = asIdentifier(replay(nameDescriptor), nameDescriptor);
            GrammarDescriptor argumentsDescriptor = children.get(argumentsIndex);
            SyntaxNode.ArgumentList arguments = asArgumentList(
                    replay(argumentsDescriptor), argumentsDescriptor);
            return new SyntaxNode.DirectCall(
                    receiver,
                    name,
                    arguments,
                    spanAtPrimary(descriptor),
                    sourceView.span(descriptor));
        }

        private Object replayMemberAccess(GrammarDescriptor descriptor) {
            if (descriptor.children().size() != 2) {
                throw invariant("member access must have receiver and member", descriptor);
            }
            GrammarDescriptor receiverDescriptor = descriptor.children().getFirst();
            SyntaxNode.Expression receiver = asExpression(replay(receiverDescriptor), receiverDescriptor);
            SourceSpan accessor = cursor.consume(TokenKind.COLON_DOT, descriptor);
            GrammarDescriptor memberDescriptor = descriptor.children().get(1);
            SyntaxNode.MemberName member = asMemberName(replay(memberDescriptor), memberDescriptor);
            return new SyntaxNode.MemberAccess(
                    receiver, member, accessor, sourceView.span(descriptor));
        }

        private Object replayNamespaceMemberAccess(GrammarDescriptor descriptor) {
            if (descriptor.children().size() != 2) {
                throw invariant("namespace member access must have path and member", descriptor);
            }
            GrammarDescriptor pathDescriptor = descriptor.children().getFirst();
            SyntaxNode.NamespacePath path = asNamespacePath(
                    replay(pathDescriptor), pathDescriptor);
            SourceSpan terminalArrow = cursor.consume(TokenKind.ARROW, descriptor);
            SourceSpan accessor = cursor.consume(TokenKind.COLON_DOT, descriptor);
            GrammarDescriptor memberDescriptor = descriptor.children().get(1);
            SyntaxNode.MemberName member = asMemberName(replay(memberDescriptor), memberDescriptor);
            return new SyntaxNode.NamespaceMemberAccess(
                    path, member, terminalArrow, accessor, sourceView.span(descriptor));
        }

        private Object replayNamespaceDirectCall(GrammarDescriptor descriptor) {
            if (descriptor.children().size() != 3) {
                throw invariant("namespace direct call must have path, name, and arguments", descriptor);
            }
            GrammarDescriptor pathDescriptor = descriptor.children().getFirst();
            SyntaxNode.NamespacePath path = asNamespacePath(
                    replay(pathDescriptor), pathDescriptor);
            SourceSpan terminalArrow = cursor.consume(TokenKind.ARROW, descriptor);
            SourceSpan accessor = cursor.consume(TokenKind.DOUBLE_COLON, descriptor);
            GrammarDescriptor nameDescriptor = descriptor.children().get(1);
            SyntaxNode.Identifier name = asIdentifier(replay(nameDescriptor), nameDescriptor);
            GrammarDescriptor argumentsDescriptor = descriptor.children().get(2);
            SyntaxNode.ArgumentList arguments = asArgumentList(
                    replay(argumentsDescriptor), argumentsDescriptor);
            return new SyntaxNode.NamespaceDirectCall(
                    path,
                    name,
                    arguments,
                    terminalArrow,
                    accessor,
                    sourceView.span(descriptor));
        }

        private Object replayNamespacePath(GrammarDescriptor descriptor) {
            List<SyntaxNode.Identifier> segments = new ArrayList<>();
            List<SourceSpan> arrows = new ArrayList<>();
            List<GrammarDescriptor> children = descriptor.children();
            for (int index = 0; index < children.size(); index++) {
                GrammarDescriptor child = children.get(index);
                segments.add(asIdentifier(replay(child), child));
                if (index + 1 < children.size()) {
                    arrows.add(cursor.consume(TokenKind.ARROW, descriptor));
                }
            }
            return new SyntaxNode.NamespacePath(segments, arrows, sourceView.span(descriptor));
        }

        private Object replayIndexAccess(GrammarDescriptor descriptor) {
            if (descriptor.children().size() != 2) {
                throw invariant("index access must have receiver and argument list", descriptor);
            }
            GrammarDescriptor receiverDescriptor = descriptor.children().getFirst();
            SyntaxNode.Expression receiver = asExpression(replay(receiverDescriptor), receiverDescriptor);
            GrammarDescriptor argumentsDescriptor = descriptor.children().get(1);
            SyntaxNode.ArgumentList arguments = asArgumentList(
                    replay(argumentsDescriptor), argumentsDescriptor);
            if (arguments.expressions().size() != 1) {
                throw invariant("index access argument list is not unary", descriptor);
            }
            return new SyntaxNode.IndexAccess(
                    receiver,
                    arguments.expressions().getFirst(),
                    arguments.openingBracketSpan(),
                    arguments.closingBracketSpan(),
                    sourceView.span(descriptor));
        }

        private Object replayArgumentList(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.LEFT_BRACKET, descriptor);
            List<SyntaxNode.Expression> expressions = new ArrayList<>();
            List<SourceSpan> commas = new ArrayList<>();
            for (GrammarDescriptor child : descriptor.children()) {
                switch (child.kind()) {
                    case ARGUMENT -> expressions.add(asExpression(replay(child), child));
                    case COMMA -> commas.add(asSpan(replay(child), child, "argument comma"));
                    default -> throw invariant("argument list has an invalid child", child);
                }
            }
            SourceSpan closing = cursor.consume(TokenKind.RIGHT_BRACKET, descriptor);
            return new SyntaxNode.ArgumentList(
                    expressions, commas, opening, closing, sourceView.span(descriptor));
        }

        private Object replayArgument(GrammarDescriptor descriptor) {
            if (descriptor.children().size() != 1) {
                throw invariant("argument must wrap one expression", descriptor);
            }
            GrammarDescriptor child = descriptor.children().getFirst();
            return asExpression(replay(child), child);
        }

        private Object replayOperatorOperands(GrammarDescriptor descriptor) {
            List<SyntaxNode.Expression> operands = new ArrayList<>();
            List<SourceSpan> commas = new ArrayList<>();
            for (GrammarDescriptor child : descriptor.children()) {
                switch (child.kind()) {
                    case ARGUMENT -> operands.add(asExpression(replay(child), child));
                    case COMMA -> commas.add(asSpan(replay(child), child, "operator comma"));
                    default -> throw invariant("operator operands have an invalid child", child);
                }
            }
            return new ExpressionList(operands, commas);
        }

        private Object replayOperator(GrammarDescriptor descriptor) {
            Token token = cursor.currentToken(descriptor);
            if (!token.kind().isOperator()) {
                throw invariant("operator descriptor points to a non-operator token", descriptor);
            }
            cursor.consume(token.kind(), descriptor);
            return new SyntaxNode.Operator(token.kind(), token.lexeme(), token.span());
        }

        private Object replayOperatorSExpression(GrammarDescriptor descriptor) {
            SourceSpan opening = cursor.consume(TokenKind.LEFT_PAREN, descriptor);
            if (descriptor.children().size() != 2) {
                throw invariant("operator S-expression must have operator and operands", descriptor);
            }
            GrammarDescriptor operatorDescriptor = descriptor.children().getFirst();
            SyntaxNode.Operator operator = asOperator(replay(operatorDescriptor), operatorDescriptor);
            GrammarDescriptor operandsDescriptor = descriptor.children().get(1);
            ExpressionList operands = cast(
                    replay(operandsDescriptor), ExpressionList.class, operandsDescriptor, "operator operands");
            SourceSpan closing = cursor.consume(TokenKind.RIGHT_PAREN, descriptor);
            return new SyntaxNode.OperatorSExpression(
                    operator,
                    operands.expressions,
                    operands.commaSpans,
                    opening,
                    closing,
                    sourceView.span(descriptor));
        }

        private Object replayOperatorBracket(GrammarDescriptor descriptor) {
            if (descriptor.children().size() != 2) {
                throw invariant("operator bracket form must have operator and arguments", descriptor);
            }
            GrammarDescriptor operatorDescriptor = descriptor.children().getFirst();
            SyntaxNode.Operator operator = asOperator(replay(operatorDescriptor), operatorDescriptor);
            GrammarDescriptor argumentsDescriptor = descriptor.children().get(1);
            SyntaxNode.ArgumentList arguments = asArgumentList(
                    replay(argumentsDescriptor), argumentsDescriptor);
            return new SyntaxNode.OperatorBracket(operator, arguments, sourceView.span(descriptor));
        }

        private Object replayArrayLiteral(GrammarDescriptor descriptor) {
            return replayAggregateLiteral(descriptor, ProductionKind.ARRAY_TYPE, "Array", true);
        }

        private Object replayTupleLiteral(GrammarDescriptor descriptor) {
            return replayAggregateLiteral(descriptor, ProductionKind.TUPLE_TYPE, "Tuple", false);
        }

        private Object replayAggregateLiteral(
                GrammarDescriptor descriptor,
                ProductionKind typedKind,
                String spelling,
                boolean array) {
            if (descriptor.children().size() != 2) {
                throw invariant(spelling + " literal must have prefix and arguments", descriptor);
            }
            GrammarDescriptor prefixDescriptor = descriptor.children().getFirst();
            Object prefixValue = replay(prefixDescriptor);
            Optional<SyntaxNode.Type> explicitType;
            if (prefixDescriptor.kind() == typedKind) {
                explicitType = Optional.of(asType(prefixValue, prefixDescriptor));
            } else if (prefixDescriptor.kind() == ProductionKind.TYPE_NAME) {
                Token prefix = cast(prefixValue, Token.class, prefixDescriptor, spelling + " prefix");
                if (!prefix.lexeme().equals(spelling)) {
                    throw invariant("aggregate prefix spelling does not match its descriptor", descriptor);
                }
                explicitType = Optional.empty();
            } else {
                throw invariant("aggregate literal has an invalid prefix descriptor", descriptor);
            }
            GrammarDescriptor argumentsDescriptor = descriptor.children().get(1);
            SyntaxNode.ArgumentList arguments = asArgumentList(
                    replay(argumentsDescriptor), argumentsDescriptor);
            return array
                    ? new SyntaxNode.ArrayLiteral(explicitType, arguments, sourceView.span(descriptor))
                    : new SyntaxNode.TupleLiteral(explicitType, arguments, sourceView.span(descriptor));
        }

        private Object replayTypeConversion(GrammarDescriptor descriptor) {
            if (descriptor.children().size() != 2) {
                throw invariant("conversion must have target type and arguments", descriptor);
            }
            GrammarDescriptor targetDescriptor = descriptor.children().getFirst();
            SyntaxNode.Type target = asType(replay(targetDescriptor), targetDescriptor);
            GrammarDescriptor argumentsDescriptor = descriptor.children().get(1);
            SyntaxNode.ArgumentList arguments = asArgumentList(
                    replay(argumentsDescriptor), argumentsDescriptor);
            if (arguments.expressions().size() != 1) {
                throw invariant("conversion argument list is not unary", descriptor);
            }
            return new SyntaxNode.TypeConversion(
                    target,
                    arguments.expressions().getFirst(),
                    arguments,
                    sourceView.span(descriptor));
        }

        private Object replayMemberName(GrammarDescriptor descriptor) {
            Token token = cursor.currentToken(descriptor);
            if (token.kind() == TokenKind.IDENTIFIER) {
                cursor.consume(TokenKind.IDENTIFIER, descriptor);
                return SyntaxNode.MemberName.identifier(token.lexeme(), token.span());
            }
            if (token.kind() == TokenKind.INTEGER_LITERAL
                    && token.value() instanceof LiteralValue.IntegerLiteral integer
                    && integer.suffix().spelling().isEmpty()
                    && integer.value().signum() >= 0) {
                cursor.consume(TokenKind.INTEGER_LITERAL, descriptor);
                return SyntaxNode.MemberName.tupleIndex(
                        token.lexeme(), integer.value(), token.span());
            }
            throw invariant("member name is neither identifier nor tuple position", descriptor);
        }

        private Object replayModifier(GrammarDescriptor descriptor) {
            Token token = cursor.currentToken(descriptor);
            cursor.consume(TokenKind.MODIFIER, descriptor);
            ModifierKind kind = token.modifier().orElseThrow(() ->
                    invariant("modifier token lacks its decoded kind", descriptor));
            return new SyntaxNode.Modifier(kind, token.lexeme(), token.span());
        }

        private Object replayTokenSpan(GrammarDescriptor descriptor, TokenKind expected) {
            return cursor.consume(expected, descriptor);
        }

        private SourceSpan spanAtPrimary(GrammarDescriptor descriptor) {
            int primary = descriptor.metadata().primaryTokenIndex();
            if (primary < 0) {
                throw invariant("descriptor has no primary accessor token", descriptor);
            }
            return sourceView.tokenSpan(primary);
        }

        private static final class Cursor {
            private final ReplaySource source;
            private int current;

            private Cursor(ReplaySource source) {
                this.source = source;
            }

            private int position() {
                return current;
            }

            private void begin(GrammarDescriptor descriptor) {
                if (current != descriptor.startTokenIndex()) {
                    throw new ParserInvariantException(
                            "replay cursor is at " + current + " but " + descriptor
                                    + " starts at " + descriptor.startTokenIndex());
                }
                if (descriptor.endTokenIndex() > source.tokens().size()) {
                    throw new ParserInvariantException(
                            "descriptor ends outside the lexical artifact: " + descriptor);
                }
            }

            private Token currentToken(GrammarDescriptor descriptor) {
                if (current >= descriptor.endTokenIndex()) {
                    throw new ParserInvariantException(
                            "descriptor replay attempted to read past its end: " + descriptor);
                }
                return source.token(current);
            }

            private SourceSpan consume(TokenKind expected, GrammarDescriptor descriptor) {
                Token token = currentToken(descriptor);
                if (token.kind() != expected) {
                    throw new ParserInvariantException(
                            descriptor.kind() + " replay expected " + expected
                                    + " at token " + current + " but found " + token.kind());
                }
                current++;
                return token.span();
            }
        }

        private record CallContent(
                SyntaxNode.Expression target,
                List<SyntaxNode.Expression> arguments,
                List<SourceSpan> commaSpans) {
            private CallContent {
                arguments = List.copyOf(arguments);
                commaSpans = List.copyOf(commaSpans);
            }
        }

        private record ExpressionList(
                List<SyntaxNode.Expression> expressions,
                List<SourceSpan> commaSpans) {
            private ExpressionList {
                expressions = List.copyOf(expressions);
                commaSpans = List.copyOf(commaSpans);
            }
        }

        private static boolean isPrimitiveTypeSpelling(String spelling) {
            return switch (spelling) {
                case "I8", "I16", "I32", "I64",
                        "U8", "U16", "U32", "U64",
                        "F32", "F64", "Bool", "Char", "String", "Unit" -> true;
                default -> false;
            };
        }

        private static SyntaxNode.Expression asExpression(Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.Expression.class, descriptor, "expression");
        }

        private static SyntaxNode.Type asType(Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.Type.class, descriptor, "type");
        }

        private static SyntaxNode.Identifier asIdentifier(Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.Identifier.class, descriptor, "identifier");
        }

        private static SyntaxNode.Modifier asModifier(Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.Modifier.class, descriptor, "modifier");
        }

        private static SyntaxNode.MemberName asMemberName(Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.MemberName.class, descriptor, "member name");
        }

        private static SyntaxNode.Operator asOperator(Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.Operator.class, descriptor, "operator");
        }

        private static SyntaxNode.ImportPath asImportPath(Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.ImportPath.class, descriptor, "import path");
        }

        private static SyntaxNode.ImportAlias asImportAlias(Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.ImportAlias.class, descriptor, "import alias");
        }

        private static SyntaxNode.ImportSelection asImportSelection(
                Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.ImportSelection.class, descriptor, "import selection");
        }

        private static SyntaxNode.ImportItem asImportItem(Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.ImportItem.class, descriptor, "import item");
        }

        private static SyntaxNode.TypeAnnotation asTypeAnnotation(
                Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.TypeAnnotation.class, descriptor, "type annotation");
        }

        private static SyntaxNode.ReturnAnnotation asReturnAnnotation(
                Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.ReturnAnnotation.class, descriptor, "return annotation");
        }

        private static SyntaxNode.ParameterList asParameterList(
                Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.ParameterList.class, descriptor, "parameter list");
        }

        private static SyntaxNode.Parameter asParameter(Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.Parameter.class, descriptor, "parameter");
        }

        private static SyntaxNode.ArgumentList asArgumentList(
                Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.ArgumentList.class, descriptor, "argument list");
        }

        private static SyntaxNode.TypeArgumentList asTypeArgumentList(
                Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.TypeArgumentList.class, descriptor, "type argument list");
        }

        private static SyntaxNode.PredicateBinding asPredicateBinding(
                Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.PredicateBinding.class, descriptor, "predicate binding");
        }

        private static SyntaxNode.NamespacePath asNamespacePath(
                Object value, GrammarDescriptor descriptor) {
            return cast(value, SyntaxNode.NamespacePath.class, descriptor, "namespace path");
        }

        private static SourceSpan asSpan(Object value, GrammarDescriptor descriptor, String role) {
            return cast(value, SourceSpan.class, descriptor, role);
        }

        private static <T> T cast(
                Object value, Class<T> type, GrammarDescriptor descriptor, String role) {
            if (!type.isInstance(value)) {
                throw invariant(
                        descriptor.kind() + " did not replay to a " + role + " (got "
                                + (value == null ? "null" : value.getClass().getName()) + ")",
                        descriptor);
            }
            return type.cast(value);
        }

        private static ParserInvariantException invariant(String message, GrammarDescriptor descriptor) {
            return new ParserInvariantException(message + ": " + descriptor);
        }
    }
}
