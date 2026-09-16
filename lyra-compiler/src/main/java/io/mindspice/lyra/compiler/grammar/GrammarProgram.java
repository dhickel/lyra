package io.mindspice.lyra.compiler.grammar;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.LiteralValue;
import io.mindspice.lyra.compiler.lex.ModifierKind;
import io.mindspice.lyra.compiler.lex.NumericSuffix;
import io.mindspice.lyra.compiler.lex.Token;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.source.SourceId;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Complete immutable output of the grammar-matching phase. */
public record GrammarProgram(
        SourceId sourceId,
        /** Canonical BOM-free UTF-8 SHA-256; empty only for the structural compatibility constructor. */
        String sourceRevision,
        int tokenCount,
        GrammarDescriptor root) implements ImmutablePhaseArtifact {
    public GrammarProgram {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        if (tokenCount < 1) {
            throw new IllegalArgumentException("a grammar program must contain EOF");
        }
        Objects.requireNonNull(root, "root");
        if (root.kind() != ProductionKind.PROGRAM
                || root.startTokenIndex() != 0
                || root.endTokenIndex() != tokenCount) {
            throw new IllegalArgumentException(
                    "program root must cover the complete token stream including EOF");
        }
        root.validateStructure();
        validateProgramStructure(root, tokenCount);
        validateStructuralSequences(root);
    }

    /** Compatibility constructor for callers that only have structural identity. */
    public GrammarProgram(SourceId sourceId, int tokenCount, GrammarDescriptor root) {
        this(sourceId, "", tokenCount, root);
    }

    /** Top-level descriptors, excluding the final EOF descriptor. */
    public List<GrammarDescriptor> forms() {
        List<GrammarDescriptor> children = root.children();
        if (children.isEmpty() || children.getLast().kind() != ProductionKind.EOF) {
            throw new IllegalStateException("program root has no final EOF descriptor");
        }
        return List.copyOf(children.subList(0, children.size() - 1));
    }

    /** The descriptor for the final token. */
    public GrammarDescriptor eof() {
        List<GrammarDescriptor> children = root.children();
        if (children.isEmpty()) {
            throw new IllegalStateException("program root has no children");
        }
        return children.getLast();
    }

    /** Validates this descriptor tree against the exact lexical artifact it replays. */
    public void validateAgainst(LexedSource lexedSource) {
        Objects.requireNonNull(lexedSource, "lexedSource");
        if (!sourceId.equals(lexedSource.snapshot().sourceId())) {
            throw new IllegalArgumentException("grammar and lexical source identities differ");
        }
        if (!sourceRevision.isEmpty()
                && !sourceRevision.equals(lexedSource.snapshot().sha256())) {
            throw new IllegalArgumentException("grammar and lexical source revisions differ");
        }
        if (tokenCount != lexedSource.tokens().size()) {
            throw new IllegalArgumentException("grammar token count differs from lexical token count");
        }

        root.validateStructure();
        validateProgramStructure(root, tokenCount);
        validateStructuralSequences(root);
        requireRange(root, 0, tokenCount, "program root");
        List<GrammarDescriptor> programChildren = root.children();
        if (programChildren.isEmpty()) {
            throw new IllegalArgumentException("program must contain an EOF descriptor");
        }
        int expectedStart = 0;
        for (GrammarDescriptor child : programChildren) {
            if (child.startTokenIndex() != expectedStart) {
                throw new IllegalArgumentException(
                        "program descriptors have a gap or illegal overlap at token " + expectedStart);
            }
            requireRange(child, expectedStart, tokenCount, "program child");
            expectedStart = child.endTokenIndex();
        }
        if (expectedStart != tokenCount) {
            throw new IllegalArgumentException("program descriptors do not consume every token");
        }

        GrammarDescriptor eof = programChildren.getLast();
        requireRange(eof, tokenCount - 1, tokenCount, "EOF descriptor");
        if (eof.kind() != ProductionKind.EOF
                || lexedSource.tokens().get(tokenCount - 1).kind() != TokenKind.EOF) {
            throw new IllegalArgumentException("EOF must be the final descriptor and token");
        }
        validateDescriptor(root, lexedSource);
    }

    /** Convenience spelling used by phase-boundary callers. */
    public void validate(LexedSource lexedSource) {
        validateAgainst(lexedSource);
    }

    /** Lets a parser replay assert that it consumed the complete program. */
    public void assertReplayConsumed(int consumedStartTokenIndex, int consumedEndTokenIndex) {
        root.assertConsumed(consumedStartTokenIndex, consumedEndTokenIndex);
    }

    private static void validateProgramStructure(GrammarDescriptor root, int tokenCount) {
        List<GrammarDescriptor> children = root.children();
        if (children.isEmpty()) {
            throw new IllegalArgumentException("program must contain an EOF descriptor");
        }
        int expected = 0;
        for (GrammarDescriptor child : children) {
            if (child.startTokenIndex() != expected) {
                throw new IllegalArgumentException("program descriptors are not contiguous");
            }
            expected = child.endTokenIndex();
        }
        if (expected != tokenCount) {
            throw new IllegalArgumentException("program descriptors do not cover the token count");
        }
        GrammarDescriptor eof = children.getLast();
        if (eof.kind() != ProductionKind.EOF
                || eof.startTokenIndex() != tokenCount - 1
                || eof.endTokenIndex() != tokenCount) {
            throw new IllegalArgumentException("EOF descriptor must be final");
        }
    }

    private static void validateStructuralSequences(GrammarDescriptor descriptor) {
        validateDelimitedMetadataShape(descriptor);
        switch (descriptor.kind()) {
            case PROGRAM -> requireContiguousChildren(
                    descriptor.children(), descriptor.startTokenIndex(), descriptor.endTokenIndex(), "program");
            case BLOCK, PARAMETER_LIST, ARGUMENT_LIST, IMPORT_SELECTION, TYPE_ARGUMENT_LIST -> {
                int opening = descriptor.metadata().openingTokenIndex();
                int closing = descriptor.metadata().closingTokenIndex();
                if (opening < descriptor.startTokenIndex()
                        || closing < opening
                        || closing >= descriptor.endTokenIndex()
                        || descriptor.startTokenIndex() != opening
                        || descriptor.endTokenIndex() != closing + 1) {
                    throw new IllegalArgumentException(
                            descriptor.kind() + " has inconsistent delimiter metadata");
                }
                requireContiguousChildren(
                        descriptor.children(), opening + 1, closing, descriptor.kind().name().toLowerCase());
            }
            case OPERATOR_OPERANDS, CALL_CONTENT -> requireContiguousChildren(
                    descriptor.children(), descriptor.startTokenIndex(), descriptor.endTokenIndex(),
                    descriptor.kind().name().toLowerCase());
            default -> {
                // Non-sequence productions may intentionally have punctuation gaps.
            }
        }
        for (GrammarDescriptor child : descriptor.children()) {
            validateStructuralSequences(child);
        }
    }

    private static void validateDelimitedMetadataShape(GrammarDescriptor descriptor) {
        boolean requiresDelimiters = switch (descriptor.kind()) {
            case NOMINAL_DECLARATION, LAMBDA, CALLABLE_CALL, CONDITIONAL, COALESCE, MATCH, COND, CONSTRUCTION,
                    RANGE, PREFIX_ASSIGNMENT, OPERATOR_S_EXPRESSION, ARRAY_LITERAL, TUPLE_LITERAL,
                    UNIT_LITERAL, CALLBACK_LOOP_BRACKET, PARENTHESIZED_DIRECT_CALL -> true;
            default -> false;
        };
        if (!requiresDelimiters) {
            return;
        }
        int opening = descriptor.metadata().openingTokenIndex();
        int closing = descriptor.metadata().closingTokenIndex();
        boolean prefixMayPrecedeOpening = switch (descriptor.kind()) {
            case NOMINAL_DECLARATION, ARRAY_LITERAL, TUPLE_LITERAL, UNIT_LITERAL, MATCH, COND, CONSTRUCTION,
                    CALLBACK_LOOP_BRACKET -> true;
            default -> false;
        };
        if (opening < descriptor.startTokenIndex()
                || closing < opening
                || (!prefixMayPrecedeOpening && descriptor.startTokenIndex() != opening)
                || descriptor.endTokenIndex() != closing + 1) {
            throw new IllegalArgumentException(
                    descriptor.kind() + " must record matching opening and closing delimiters");
        }
    }

    private static void validateDescriptor(GrammarDescriptor descriptor, LexedSource source) {
        requireRange(descriptor, 0, source.tokens().size(), descriptor.kind().name());
        validateMetadataTokens(descriptor, source);
        validateProductionChildShape(descriptor, source);
        validateProductionMetadata(descriptor, source);
        validateDelimiterTokens(descriptor, source);
        validateLeaf(descriptor, source);
        validateSequence(descriptor, source);
        for (GrammarDescriptor child : descriptor.children()) {
            if (child.kind() == ProductionKind.NOMINAL_DECLARATION && descriptor.kind() != ProductionKind.PROGRAM
                    || (child.kind() == ProductionKind.MEMBER_DECLARATION
                    || child.kind() == ProductionKind.CONSTRUCTOR_DECLARATION)
                    && descriptor.kind() != ProductionKind.NOMINAL_DECLARATION) {
                throw new IllegalArgumentException("nominal declaration/member appears outside its syntactic scope");
            }
            if (child.kind() == ProductionKind.IDENTIFIER
                    && source.tokens().get(child.startTokenIndex()).kind().isCallbackLoopKeyword()
                    && !(descriptor.kind() == ProductionKind.CALL_TARGET
                    || descriptor.kind() == ProductionKind.DIRECT_CALL
                    && descriptor.children().size() == 2
                    && descriptor.children().getFirst() == child)) {
                throw new IllegalArgumentException("reserved callback loop is only an unqualified call target");
            }
            validateDescriptor(child, source);
        }
    }

    private static void validateMetadataTokens(GrammarDescriptor descriptor, LexedSource source) {
        for (Integer index : descriptor.metadata().commaTokenIndices()) {
            if (source.tokens().get(index).kind() != TokenKind.COMMA) {
                throw new IllegalArgumentException("comma metadata points to a non-comma token");
            }
        }
        for (Integer index : descriptor.metadata().modifierTokenIndices()) {
            if (source.tokens().get(index).kind() != TokenKind.MODIFIER) {
                throw new IllegalArgumentException("modifier metadata points to a non-modifier token");
            }
        }
        for (Integer index : descriptor.metadata().operatorTokenIndices()) {
            TokenKind kind = source.tokens().get(index).kind();
            if (!kind.isOperator() && !kind.isAccessor()) {
                throw new IllegalArgumentException("operator metadata points to a non-operator token");
            }
        }
    }

    private static void validateProductionMetadata(
            GrammarDescriptor descriptor, LexedSource source) {
        TokenKind expectedPrimary = switch (descriptor.kind()) {
            case LET_BINDING -> TokenKind.LET;
            case NOMINAL_DECLARATION -> source.tokens().get(descriptor.startTokenIndex()).kind();
            case CONSTRUCTOR_DECLARATION -> TokenKind.EQUAL;
            case IMPORT_DECLARATION -> TokenKind.IMPORT;
            case IMPORT_ALIAS -> TokenKind.AS;
            case REASSIGNMENT, PREFIX_ASSIGNMENT -> TokenKind.COLON_EQUAL;
            case CONDITIONAL -> TokenKind.ARROW;
            case COALESCE, TYPE_ANNOTATION, RETURN_ANNOTATION -> TokenKind.COLON;
            case MATCH -> TokenKind.MATCH;
            case COND -> TokenKind.COND;
            case NEGATIVE_LITERAL -> TokenKind.MINUS;
            case CALLBACK_LOOP_BRACKET -> source.tokens().get(descriptor.startTokenIndex()).kind();
            case CONSTRUCTION -> source.tokens().get(descriptor.startTokenIndex()).kind() == TokenKind.COLON
                    ? TokenKind.COLON : null;
            case MATCH_ARM -> TokenKind.ARROW;
            case RANGE -> source.tokens().get(descriptor.children().getFirst().endTokenIndex()).kind()
                    == TokenKind.RANGE_INCLUSIVE ? TokenKind.RANGE_INCLUSIVE : TokenKind.RANGE_EXCLUSIVE;
            case LAMBDA -> TokenKind.LAMBDA_ARROW;
            case DIRECT_CALL, NAMESPACE_DIRECT_CALL -> TokenKind.DOUBLE_COLON;
            case MEMBER_ACCESS, NAMESPACE_MEMBER_ACCESS -> TokenKind.COLON_DOT;
            case ARRAY_LITERAL, TUPLE_LITERAL -> TokenKind.TYPE_NAME;
            case OPERATOR_S_EXPRESSION, OPERATOR_BRACKET -> null;
            case UNIT_LITERAL -> descriptor.metadata().primaryTokenIndex() >= 0
                    ? TokenKind.TYPE_NAME : null;
            default -> null;
        };
        int primary = descriptor.metadata().primaryTokenIndex();
        boolean requiresOperatorPrimary = descriptor.kind() == ProductionKind.OPERATOR_S_EXPRESSION
                || descriptor.kind() == ProductionKind.OPERATOR_BRACKET;
        boolean allowsNoPrimary = descriptor.kind() == ProductionKind.UNIT_LITERAL
                || descriptor.kind() == ProductionKind.CONSTRUCTION
                || descriptor.kind() == ProductionKind.MEMBER_DECLARATION;
        if (expectedPrimary != null) {
            if (primary < 0) {
                throw new IllegalArgumentException(descriptor.kind() + " must record its primary token");
            }
            requireToken(source.tokens().get(primary), expectedPrimary, descriptor.kind());
        } else if (requiresOperatorPrimary) {
            if (primary < 0 || !source.tokens().get(primary).kind().isOperator()) {
                throw new IllegalArgumentException(descriptor.kind() + " must record its operator token");
            }
        } else if (!allowsNoPrimary && primary >= 0) {
            throw new IllegalArgumentException(descriptor.kind() + " must not record a primary token");
        }
        int exactPrimary = switch (descriptor.kind()) {
            case NOMINAL_DECLARATION, LET_BINDING, IMPORT_DECLARATION, IMPORT_ALIAS,
                    TYPE_ANNOTATION, RETURN_ANNOTATION,
                    ARRAY_LITERAL, TUPLE_LITERAL, OPERATOR_BRACKET -> descriptor.startTokenIndex();
            case LAMBDA, PREFIX_ASSIGNMENT, OPERATOR_S_EXPRESSION ->
                    descriptor.metadata().openingTokenIndex() + 1;
            case REASSIGNMENT, COALESCE, RANGE -> descriptor.children().getFirst().endTokenIndex();
            case CONSTRUCTOR_DECLARATION -> descriptor.children().getFirst().endTokenIndex();
            case CONDITIONAL -> conditionalArrowIndex(descriptor);
            case MATCH -> keywordIndex(descriptor, source, TokenKind.MATCH);
            case COND -> keywordIndex(descriptor, source, TokenKind.COND);
            case CONSTRUCTION -> source.tokens().get(descriptor.startTokenIndex()).kind() == TokenKind.COLON
                    ? descriptor.startTokenIndex() : -1;
            case NEGATIVE_LITERAL, CALLBACK_LOOP_BRACKET -> descriptor.startTokenIndex();
            case MATCH_ARM -> descriptor.children().getLast().startTokenIndex() - 1;
            case DIRECT_CALL, MEMBER_ACCESS -> directAccessorIndex(descriptor);
            case NAMESPACE_DIRECT_CALL, NAMESPACE_MEMBER_ACCESS ->
                    namespaceAccessorIndex(descriptor, source);
            case UNIT_LITERAL -> primary >= 0 ? descriptor.startTokenIndex() : -1;
            default -> -1;
        };
        if (exactPrimary >= 0 && primary != exactPrimary) {
            throw new IllegalArgumentException(
                    descriptor.kind() + " primary metadata does not identify its syntactic primary token");
        }

        if (descriptor.kind() == ProductionKind.UNIT_LITERAL) {
            if (primary < 0) {
                if (descriptor.startTokenIndex() != descriptor.metadata().openingTokenIndex()
                        || descriptor.metadata().closingTokenIndex()
                        != descriptor.metadata().openingTokenIndex() + 1
                        || source.tokens().get(descriptor.metadata().openingTokenIndex()).kind()
                        != TokenKind.LEFT_PAREN
                        || !descriptor.children().isEmpty()) {
                    throw new IllegalArgumentException(
                            "parenthesized Unit must be an empty pair of parentheses");
                }
            } else {
                if (primary != descriptor.startTokenIndex()
                        || descriptor.children().size() != 2
                        || descriptor.children().getFirst().kind() != ProductionKind.TYPE_NAME
                        || descriptor.children().get(1).kind() != ProductionKind.ARGUMENT_LIST
                        || (source.tokens().get(primary).lexeme().equals("Array")
                        || source.tokens().get(primary).lexeme().equals("Tuple")) == false
                        || descriptor.metadata().openingTokenIndex() != primary + 1) {
                    throw new IllegalArgumentException(
                            "typed Unit must be Array[] or Tuple[] with its type prefix");
                }
            }
        }

        int opening = descriptor.metadata().openingTokenIndex();
        int closing = descriptor.metadata().closingTokenIndex();
        if ((opening < 0) != (closing < 0)) {
            throw new IllegalArgumentException(descriptor.kind() + " must record both delimiters or neither");
        }
        boolean allowsDelimiters = switch (descriptor.kind()) {
            case NOMINAL_DECLARATION -> true;
            case LAMBDA, CALLABLE_CALL, CONDITIONAL, COALESCE, MATCH, COND, CONSTRUCTION, RANGE,
                    PREFIX_ASSIGNMENT, OPERATOR_S_EXPRESSION, ARRAY_LITERAL, TUPLE_LITERAL,
                    UNIT_LITERAL, BLOCK, PARAMETER_LIST, ARGUMENT_LIST, IMPORT_SELECTION,
                    TYPE_ARGUMENT_LIST, REASSIGNMENT, CALLBACK_LOOP_BRACKET,
                    PARENTHESIZED_DIRECT_CALL -> true;
            default -> false;
        };
        if (!allowsDelimiters && opening >= 0) {
            throw new IllegalArgumentException(descriptor.kind() + " must not record delimiters");
        }

        boolean commaMetadataAllowed = switch (descriptor.kind()) {
            case PARAMETER_LIST, ARGUMENT_LIST, TYPE_ARGUMENT_LIST,
                    OPERATOR_OPERANDS, CALL_CONTENT, CALLABLE_CALL, MATCH, COND -> true;
            default -> false;
        };
        if (!commaMetadataAllowed && !descriptor.metadata().commaTokenIndices().isEmpty()) {
            throw new IllegalArgumentException(
                    descriptor.kind() + " must not carry comma metadata");
        }

        List<Integer> expectedModifiers = switch (descriptor.kind()) {
            case NOMINAL_DECLARATION, MEMBER_DECLARATION, LET_BINDING, IMPORT_DECLARATION, LAMBDA, PARAMETER, TYPE_CONTRACT ->
                    childTokenIndices(descriptor, ProductionKind.MODIFIER);
            case PARAMETER_LIST -> descriptor.children().stream()
                    .filter(child -> child.kind() == ProductionKind.PARAMETER)
                    .flatMap(child -> child.metadata().modifierTokenIndices().stream())
                    .toList();
            default -> List.of();
        };
        if (!descriptor.metadata().modifierTokenIndices().equals(expectedModifiers)) {
            throw new IllegalArgumentException(
                    descriptor.kind() + " modifier metadata does not match its syntactic role");
        }

        List<Integer> expectedOperators = switch (descriptor.kind()) {
            case NAMED_TYPE, IMPORT_PATH, NAMESPACE_PATH -> separatingArrowIndices(descriptor);
            case IMPORT_DECLARATION -> importSelectionArrowIndices(descriptor);
            case MATCH_ARM -> List.of(descriptor.children().getLast().startTokenIndex() - 1);
            case CONSTRUCTION -> List.of();
            case NAMESPACE_DIRECT_CALL, NAMESPACE_MEMBER_ACCESS ->
                    namespaceAccessorIndex(descriptor, source) == descriptor.children().getFirst().endTokenIndex()
                            ? List.of() : List.of(descriptor.children().getFirst().endTokenIndex());
            default -> List.of();
        };
        if (!descriptor.metadata().operatorTokenIndices().equals(expectedOperators)) {
            throw new IllegalArgumentException(
                    descriptor.kind() + " operator metadata does not match its syntactic role");
        }
        for (Integer arrow : expectedOperators) {
            requireToken(source.tokens().get(arrow), TokenKind.ARROW, descriptor.kind());
        }

        if (switch (descriptor.kind()) {
            case PARAMETER_LIST, ARGUMENT_LIST, TYPE_ARGUMENT_LIST,
                    OPERATOR_OPERANDS, CALL_CONTENT, MATCH, COND -> true;
            default -> false;
        } && !descriptor.metadata().commaTokenIndices().equals(
                childTokenIndices(descriptor, ProductionKind.COMMA))) {
            throw new IllegalArgumentException(
                    descriptor.kind() + " comma metadata does not match its comma children");
        }
        if (descriptor.kind() == ProductionKind.CALLABLE_CALL) {
            if (descriptor.children().size() != 1
                    || descriptor.children().getFirst().kind() != ProductionKind.CALL_CONTENT) {
                throw new IllegalArgumentException("callable call must contain exactly one call-content child");
            }
            if (!descriptor.metadata().commaTokenIndices().equals(
                    descriptor.children().getFirst().metadata().commaTokenIndices())) {
                throw new IllegalArgumentException(
                        "callable-call comma metadata does not match its call content");
            }
        }
    }

    private static void validateProductionChildShape(
            GrammarDescriptor descriptor, LexedSource source) {
        switch (descriptor.kind()) {
            case NOMINAL_DECLARATION -> validateNominalDeclaration(descriptor, source);
            case MEMBER_DECLARATION -> validateMemberDeclaration(descriptor, source);
            case CONSTRUCTOR_DECLARATION -> {
                requireChildCount(descriptor, 2);
                var name = descriptor.children().getFirst();
                var lambda = descriptor.children().getLast();
                if (name.kind() != ProductionKind.IDENTIFIER || lambda.kind() != ProductionKind.LAMBDA
                        || name.startTokenIndex() != descriptor.startTokenIndex()
                        || lambda.startTokenIndex() != name.endTokenIndex() + 1
                        || lambda.endTokenIndex() != descriptor.endTokenIndex()) {
                    throw new IllegalArgumentException("constructor must contain a name, '=' and lambda");
                }
            }
            case INDEX_ACCESS, BRACKET_APPLICATION -> {
                requireChildCount(descriptor, 2);
                var target = descriptor.children().getFirst();
                var arguments = descriptor.children().getLast();
                long arity = arguments.children().stream()
                        .filter(child -> child.kind() == ProductionKind.ARGUMENT).count();
                if (arguments.kind() != ProductionKind.ARGUMENT_LIST
                        || target.startTokenIndex() != descriptor.startTokenIndex()
                        || target.endTokenIndex() != arguments.startTokenIndex()
                        || arguments.endTokenIndex() != descriptor.endTokenIndex()
                        || (descriptor.kind() == ProductionKind.INDEX_ACCESS) != (arity == 1)) {
                    throw new IllegalArgumentException("bracket application has invalid target/argument roles");
                }
            }
            case REASSIGNMENT -> validateReassignmentShape(descriptor);
            case PREFIX_ASSIGNMENT -> validatePrefixAssignmentShape(descriptor);
            case CONDITIONAL -> validateConditionalShape(descriptor);
            case COALESCE -> validateCoalesceShape(descriptor);
            case RANGE -> {
                requireChildCount(descriptor, 3);
                var start = descriptor.children().get(0);
                var end = descriptor.children().get(1);
                var step = descriptor.children().get(2);
                if (start.startTokenIndex() != descriptor.startTokenIndex() + 1
                        || start.endTokenIndex() + 1 != end.startTokenIndex()
                        || end.endTokenIndex() + 1 != step.startTokenIndex()
                        || step.endTokenIndex() != descriptor.endTokenIndex() - 1) {
                    throw new IllegalArgumentException("range must contain exactly start, end and step");
                }
                requireToken(source.tokens().get(end.endTokenIndex()), TokenKind.COLON, descriptor.kind());
            }
            case MATCH -> validateMatchShape(descriptor, source);
            case NEGATIVE_LITERAL -> {
                requireChildCount(descriptor, 1);
                GrammarDescriptor magnitude = descriptor.children().getFirst();
                if (magnitude.kind() != ProductionKind.LITERAL
                        || magnitude.startTokenIndex() != descriptor.startTokenIndex() + 1
                        || magnitude.endTokenIndex() != descriptor.endTokenIndex()
                        || source.tokens().get(magnitude.startTokenIndex()).hasLeadingTrivia()) {
                    throw new IllegalArgumentException(
                            "a bare negative literal must be an adjacent literal magnitude");
                }
            }
            case COND -> validateCondShape(descriptor, source);
            case CALLBACK_LOOP_BRACKET -> {
                requireChildCount(descriptor, 1);
                GrammarDescriptor arguments = descriptor.children().getFirst();
                TokenKind head = source.tokens().get(descriptor.startTokenIndex()).kind();
                if (head != TokenKind.ITER && head != TokenKind.WHILE
                        || arguments.kind() != ProductionKind.ARGUMENT_LIST
                        || arguments.startTokenIndex() != descriptor.metadata().openingTokenIndex()
                        || arguments.endTokenIndex() != descriptor.endTokenIndex()) {
                    throw new IllegalArgumentException(
                            "a bare callback loop must be a reserved keyword and its bracket argument list");
                }
            }
            case PARENTHESIZED_DIRECT_CALL -> {
                requireChildCount(descriptor, 1);
                GrammarDescriptor inner = descriptor.children().getFirst();
                if (inner.kind() != ProductionKind.DIRECT_CALL
                        || inner.startTokenIndex() != descriptor.metadata().openingTokenIndex() + 1
                        || inner.endTokenIndex() != descriptor.metadata().closingTokenIndex()
                        || inner.children().size() != 2) {
                    throw new IllegalArgumentException(
                            "parenthesized direct call must preserve one unqualified direct call");
                }
            }
            case CONSTRUCTION -> validateConstructionShape(descriptor, source);
            case MATCH_ARM -> validateMatchArmShape(descriptor, source);
            case DIRECT_CALL -> validateDirectCallShape(descriptor);
            case MEMBER_ACCESS -> validateMemberAccessShape(descriptor);
            case NAMESPACE_DIRECT_CALL -> validateNamespaceAccessShape(descriptor, source, true);
            case NAMESPACE_MEMBER_ACCESS -> validateNamespaceAccessShape(descriptor, source, false);
            case IMPORT_DECLARATION -> validateImportDeclarationShape(descriptor);
            case NAMED_TYPE, IMPORT_PATH, NAMESPACE_PATH -> validateArrowPathShape(descriptor);
            case ARRAY_LITERAL -> validateAggregateLiteralShape(descriptor, source, "Array");
            case TUPLE_LITERAL -> validateAggregateLiteralShape(descriptor, source, "Tuple");
            case ARRAY_TYPE -> validateCompositeTypeShape(descriptor, source, "Array");
            case RANGE_TYPE -> validateCompositeTypeShape(descriptor, source, "Range");
            case TUPLE_TYPE -> validateCompositeTypeShape(descriptor, source, "Tuple");
            case FUNCTION_TYPE -> validateCompositeTypeShape(descriptor, source, "Fn");
            case UNIT_LITERAL -> validateUnitShape(descriptor, source);
            default -> {
                // Other production layouts are validated by their sequence and role checks.
            }
        }
    }

    private static void validateNominalDeclaration(GrammarDescriptor descriptor, LexedSource source) {
        TokenKind keyword = source.tokens().get(descriptor.startTokenIndex()).kind();
        if (keyword != TokenKind.STRUCT && keyword != TokenKind.CLASS) {
            throw new IllegalArgumentException("nominal declaration must begin with struct or class");
        }
        int index = 0;
        int position = descriptor.startTokenIndex() + 1;
        var children = descriptor.children();
        while (index < children.size() && children.get(index).kind() == ProductionKind.MODIFIER) {
            var modifier = children.get(index++);
            if (index > 1 || modifier.startTokenIndex() != position++
                    || !source.tokens().get(modifier.startTokenIndex()).lexeme().equals("@pub")) {
                throw new IllegalArgumentException("nominal declaration permits only one @pub modifier");
            }
        }
        if (index >= children.size()) throw new IllegalArgumentException("nominal declaration lacks a name");
        var name = children.get(index++);
        String spelling = source.tokens().get(name.startTokenIndex()).lexeme();
        if (name.kind() != ProductionKind.IDENTIFIER || name.startTokenIndex() != position
                || !spelling.matches("[A-Z][A-Za-z0-9_]*")
                || name.endTokenIndex() != descriptor.metadata().openingTokenIndex()) {
            throw new IllegalArgumentException("nominal declaration has invalid name/opening roles");
        }
        position = descriptor.metadata().openingTokenIndex() + 1;
        boolean constructorSeen = false;
        for (; index < children.size(); index++) {
            var member = children.get(index);
            if (member.startTokenIndex() != position) {
                throw new IllegalArgumentException("nominal members must be contiguous");
            }
            if (member.kind() == ProductionKind.CONSTRUCTOR_DECLARATION) {
                if (keyword == TokenKind.STRUCT || constructorSeen
                        || !source.tokens().get(member.startTokenIndex()).lexeme().equals(spelling)) {
                    throw new IllegalArgumentException("invalid or duplicate same-name class constructor");
                }
                constructorSeen = true;
            } else if (member.kind() != ProductionKind.MEMBER_DECLARATION) {
                throw new IllegalArgumentException("nominal body contains a non-member");
            }
            position = member.endTokenIndex();
        }
        if (position != descriptor.metadata().closingTokenIndex()) {
            throw new IllegalArgumentException("nominal declaration has trailing unaccounted tokens");
        }
    }

    private static void validateMemberDeclaration(GrammarDescriptor descriptor, LexedSource source) {
        var children = descriptor.children();
        int index = 0;
        int position = descriptor.startTokenIndex();
        var modifiers = EnumSet.noneOf(ModifierKind.class);
        while (index < children.size() && children.get(index).kind() == ProductionKind.MODIFIER) {
            var child = children.get(index++);
            var modifier = source.tokens().get(child.startTokenIndex()).modifier().orElse(null);
            if (child.startTokenIndex() != position++ || modifier == null || !modifiers.add(modifier)) {
                throw new IllegalArgumentException("member modifiers must be unique and precede the member name");
            }
        }
        if (children.size() - index < 2 || children.size() - index > 3) {
            throw new IllegalArgumentException("member needs a name, type and optional initializer");
        }
        var name = children.get(index++);
        var annotation = children.get(index++);
        if (name.kind() != ProductionKind.IDENTIFIER || name.startTokenIndex() != position
                || annotation.kind() != ProductionKind.TYPE_ANNOTATION
                || annotation.startTokenIndex() != name.endTokenIndex()) {
            throw new IllegalArgumentException("invalid member name/type roles");
        }
        position = annotation.endTokenIndex();
        if (index < children.size()) {
            var initializer = children.get(index);
            requireToken(source.tokens().get(position), TokenKind.EQUAL, descriptor.kind());
            if (initializer.startTokenIndex() != position + 1
                    || initializer.kind() == ProductionKind.COMPACT_LAMBDA) {
                throw new IllegalArgumentException("invalid member initializer role");
            }
            position = initializer.endTokenIndex();
        }
        if (position != descriptor.endTokenIndex()) {
            throw new IllegalArgumentException("member contains unaccounted tokens");
        }
    }

    private static void validateReassignmentShape(GrammarDescriptor descriptor) {
        requireChildCount(descriptor, 2);
        GrammarDescriptor target = descriptor.children().getFirst();
        GrammarDescriptor value = descriptor.children().get(1);
        int opening = descriptor.metadata().openingTokenIndex();
        int closing = descriptor.metadata().closingTokenIndex();
        if (target.endTokenIndex() + 1 != value.startTokenIndex()) {
            throw new IllegalArgumentException(
                    "reassignment children must be separated only by ':='");
        }
        if (opening >= 0) {
            if (opening != descriptor.startTokenIndex()
                    || closing != descriptor.endTokenIndex() - 1
                    || target.startTokenIndex() != opening + 1
                    || value.endTokenIndex() != closing) {
                throw new IllegalArgumentException(
                        "parenthesized reassignment delimiters must bound its child expressions");
            }
        } else if (target.startTokenIndex() != descriptor.startTokenIndex()
                || value.endTokenIndex() != descriptor.endTokenIndex()) {
            throw new IllegalArgumentException(
                    "unparenthesized reassignment children must cover its complete range");
        }
    }

    private static void validatePrefixAssignmentShape(GrammarDescriptor descriptor) {
        requireChildCount(descriptor, 2);
        int opening = descriptor.metadata().openingTokenIndex();
        int closing = descriptor.metadata().closingTokenIndex();
        GrammarDescriptor target = descriptor.children().getFirst();
        GrammarDescriptor value = descriptor.children().get(1);
        if (opening != descriptor.startTokenIndex()
                || closing != descriptor.endTokenIndex() - 1
                || target.startTokenIndex() != opening + 2
                || target.endTokenIndex() != value.startTokenIndex()
                || value.endTokenIndex() != closing) {
            throw new IllegalArgumentException(
                    "prefix assignment children do not match its delimiter and operator positions");
        }
    }

    private static void validateConditionalShape(GrammarDescriptor descriptor) {
        List<GrammarDescriptor> children = descriptor.children();
        if (children.size() < 2 || children.size() > 4) {
            throw new IllegalArgumentException("conditional must contain predicate and branch children");
        }
        boolean hasBinding = children.size() >= 3
                && children.get(1).kind() == ProductionKind.PREDICATE_BINDING;
        int thenIndex = hasBinding ? 2 : 1;
        boolean hasElse = children.size() > thenIndex + 1;
        if (children.size() != thenIndex + (hasElse ? 2 : 1)) {
            throw new IllegalArgumentException("conditional children have an invalid layout");
        }
        int opening = descriptor.metadata().openingTokenIndex();
        int closing = descriptor.metadata().closingTokenIndex();
        GrammarDescriptor predicate = children.getFirst();
        GrammarDescriptor beforeArrow = children.get(thenIndex - 1);
        GrammarDescriptor thenBranch = children.get(thenIndex);
        if (opening != descriptor.startTokenIndex()
                || closing != descriptor.endTokenIndex() - 1
                || predicate.startTokenIndex() != opening + 1
                || (hasBinding && predicate.endTokenIndex()
                != children.get(1).startTokenIndex())
                || thenBranch.startTokenIndex() != beforeArrow.endTokenIndex() + 1) {
            throw new IllegalArgumentException(
                    "conditional children do not identify the exact predicate and arrow positions");
        }
        if (hasElse) {
            GrammarDescriptor elseBranch = children.get(thenIndex + 1);
            if (elseBranch.startTokenIndex() != thenBranch.endTokenIndex() + 1
                    || elseBranch.endTokenIndex() != closing) {
                throw new IllegalArgumentException(
                        "conditional branches do not identify the exact ':' position");
            }
        } else if (thenBranch.endTokenIndex() != closing) {
            throw new IllegalArgumentException(
                    "then-only conditional branch must end at the closing delimiter");
        }
    }

    /** Locates the reserved keyword token that precedes a special form's delimiters. */
    private static int keywordIndex(
            GrammarDescriptor descriptor, LexedSource source, TokenKind keyword) {
        int limit = Math.min(
                descriptor.metadata().openingTokenIndex() + 1,
                descriptor.endTokenIndex() - 1);
        for (int index = descriptor.startTokenIndex(); index <= limit; index++) {
            if (source.tokens().get(index).kind() == keyword) {
                return index;
            }
        }
        throw new IllegalArgumentException(
                descriptor.kind() + " does not contain its reserved keyword token");
    }

    /** True when this arm is the unconditional wildcard fallback. */
    private static boolean matchArmIsUnguardedWildcard(
            GrammarDescriptor arm, LexedSource source) {
        if (arm.kind() != ProductionKind.MATCH_ARM || arm.children().isEmpty()) {
            return false;
        }
        boolean wildcard = arm.children().getFirst().startTokenIndex() != arm.startTokenIndex();
        if (!wildcard) {
            return false;
        }
        return source.tokens().get(arm.startTokenIndex() + 1).kind() == TokenKind.ARROW;
    }

    private static void validateMatchShape(
            GrammarDescriptor descriptor, LexedSource source) {
        int opening = descriptor.metadata().openingTokenIndex();
        int closing = descriptor.metadata().closingTokenIndex();
        int start = descriptor.startTokenIndex();
        boolean bracketed = source.tokens().get(opening).kind() == TokenKind.LEFT_BRACKET;
        int keyword = keywordIndex(descriptor, source, TokenKind.MATCH);
        boolean parenthesized = !bracketed && start == opening;
        boolean accessor = bracketed && source.tokens().get(start).kind() == TokenKind.DOUBLE_COLON;
        boolean bare = bracketed && source.tokens().get(start).kind() == TokenKind.MATCH;
        int expectedKeyword = parenthesized ? opening + 1 : accessor ? start + 1 : start;
        if (closing != descriptor.endTokenIndex() - 1
                || (!parenthesized && !accessor && !bare)
                || keyword != expectedKeyword) {
            throw new IllegalArgumentException("match has inconsistent surface delimiters");
        }
        List<GrammarDescriptor> children = descriptor.children();
        if (children.isEmpty()) {
            throw new IllegalArgumentException("match must contain a subject and a fallback arm");
        }
        GrammarDescriptor subject = children.getFirst();
        if (subject.kind() == ProductionKind.MATCH_ARM
                || subject.kind() == ProductionKind.COMMA
                || subject.startTokenIndex() != (bracketed ? opening + 1 : keyword + 1)) {
            throw new IllegalArgumentException("match requires a value subject");
        }
        validateArmSequence(children, 1, subject.endTokenIndex(), closing, source, "match");
    }

    private static void validateCondShape(
            GrammarDescriptor descriptor, LexedSource source) {
        int opening = descriptor.metadata().openingTokenIndex();
        int closing = descriptor.metadata().closingTokenIndex();
        int start = descriptor.startTokenIndex();
        int keyword = keywordIndex(descriptor, source, TokenKind.COND);
        boolean bracketed = source.tokens().get(opening).kind() == TokenKind.LEFT_BRACKET;
        boolean parenthesized = !bracketed && start == opening;
        boolean bare = bracketed && source.tokens().get(start).kind() == TokenKind.COND;
        int expectedKeyword = parenthesized ? opening + 1 : start;
        if (closing != descriptor.endTokenIndex() - 1
                || (!parenthesized && !bare)
                || keyword != expectedKeyword
                || descriptor.children().isEmpty()) {
            throw new IllegalArgumentException("cond has inconsistent surface delimiters");
        }
        validateArmSequence(descriptor.children(), 0,
                bracketed ? opening + 1 : keyword + 1, closing, source, "cond");
    }

    /** Validates a marker-free arm sequence with its optional sibling-:: commas. */
    private static void validateArmSequence(
            List<GrammarDescriptor> children,
            int firstChildIndex,
            int expectedStart,
            int closing,
            LexedSource source,
            String form) {
        int cursor = expectedStart;
        int armCount = 0;
        boolean fallbackSeen = false;
        for (int index = firstChildIndex; index < children.size(); index++) {
            GrammarDescriptor child = children.get(index);
            if (child.kind() == ProductionKind.COMMA) {
                if (fallbackSeen
                        || child.startTokenIndex() != cursor
                        || index + 1 >= children.size()
                        || children.get(index + 1).kind() != ProductionKind.MATCH_ARM) {
                    throw new IllegalArgumentException(
                            form + " comma child is not between arms");
                }
                cursor = child.endTokenIndex();
                continue;
            }
            if (child.kind() != ProductionKind.MATCH_ARM) {
                throw new IllegalArgumentException(
                        form + " children must be ordered match arms");
            }
            if (fallbackSeen) {
                throw new IllegalArgumentException(
                        form + " cannot continue after an unconditional wildcard fallback");
            }
            if (child.startTokenIndex() != cursor) {
                throw new IllegalArgumentException(
                        form + " arms are not contiguous with their subject/results");
            }
            fallbackSeen = matchArmIsUnguardedWildcard(child, source);
            cursor = child.endTokenIndex();
            armCount++;
        }
        if (armCount == 0 || !fallbackSeen) {
            throw new IllegalArgumentException(
                    form + " must end in an unguarded wildcard fallback");
        }
        if (cursor != closing) {
            throw new IllegalArgumentException(
                    form + " arms must end at the closing delimiter");
        }
    }

    private static void validateConstructionShape(
            GrammarDescriptor descriptor, LexedSource source) {
        int start = descriptor.startTokenIndex();
        boolean prefixed = source.tokens().get(start).kind() == TokenKind.COLON;
        if (!prefixed && source.tokens().get(start).kind() != TokenKind.IDENTIFIER) {
            throw new IllegalArgumentException("construction must begin with a nominal type name");
        }
        int childCount = descriptor.children().size();
        if (childCount != 2 && childCount != 3) {
            throw new IllegalArgumentException(
                    "construction must contain a type target and its argument list");
        }
        GrammarDescriptor typeName = descriptor.children().get(childCount - 2);
        GrammarDescriptor arguments = descriptor.children().get(childCount - 1);
        if (typeName.kind() != ProductionKind.IDENTIFIER
                || source.tokens().get(typeName.startTokenIndex()).kind() != TokenKind.IDENTIFIER
                || arguments.kind() != ProductionKind.ARGUMENT_LIST
                || arguments.startTokenIndex() != descriptor.metadata().openingTokenIndex()
                || arguments.endTokenIndex() != descriptor.endTokenIndex()
                || (prefixed && source.tokens().get(typeName.startTokenIndex()).hasLeadingTrivia())) {
            throw new IllegalArgumentException(
                    "construction must name a nominal type and its bracket arguments");
        }
        if (childCount == 3) {
            GrammarDescriptor path = descriptor.children().getFirst();
            int expectedStart = prefixed ? start + 1 : start;
            if (path.kind() != ProductionKind.NAMESPACE_PATH
                    || path.startTokenIndex() != expectedStart
                    || path.endTokenIndex() + 1 != typeName.startTokenIndex()) {
                throw new IllegalArgumentException(
                        "qualified construction must name its namespace path before the type");
            }
        } else {
            int expectedStart = prefixed ? start + 1 : start;
            if (typeName.startTokenIndex() != expectedStart) {
                throw new IllegalArgumentException(
                        "unqualified construction target must immediately precede its arguments");
            }
        }
    }

    private static void validateMatchArmShape(
            GrammarDescriptor descriptor, LexedSource source) {
        if (descriptor.children().isEmpty()
                || descriptor.children().getLast().endTokenIndex() != descriptor.endTokenIndex()) {
            throw new IllegalArgumentException("match arm has an invalid result layout");
        }
        boolean wildcard = descriptor.children().getFirst().startTokenIndex()
                != descriptor.startTokenIndex();
        int guardOrArrow;
        if (wildcard) {
            guardOrArrow = descriptor.startTokenIndex() + 1;
        } else {
            GrammarDescriptor pattern = descriptor.children().getFirst();
            if (pattern.startTokenIndex() != descriptor.startTokenIndex()) {
                throw new IllegalArgumentException("match pattern does not start its arm");
            }
            guardOrArrow = pattern.endTokenIndex();
        }
        boolean guarded = source.tokens().get(guardOrArrow).kind() == TokenKind.WHEN;
        int expectedChildren = (wildcard ? 0 : 1) + (guarded ? 1 : 0) + 1;
        if (descriptor.children().size() != expectedChildren) {
            throw new IllegalArgumentException("match arm children do not match wildcard/guard roles");
        }
        if (guarded) {
            int guardIndex = wildcard ? 0 : 1;
            if (descriptor.children().get(guardIndex).startTokenIndex() != guardOrArrow + 1) {
                throw new IllegalArgumentException("match guard does not follow 'when'");
            }
        } else if (source.tokens().get(guardOrArrow).kind() != TokenKind.ARROW) {
            throw new IllegalArgumentException("match arm lacks its arrow");
        }
        GrammarDescriptor result = descriptor.children().getLast();
        int arrow = result.startTokenIndex() - 1;
        if (source.tokens().get(arrow).kind() != TokenKind.ARROW
                || descriptor.metadata().operatorTokenIndices().size() != 1
                || descriptor.metadata().operatorTokenIndices().getFirst() != arrow) {
            throw new IllegalArgumentException("match arm metadata does not identify its exact arrow");
        }
    }

    private static void validateCoalesceShape(GrammarDescriptor descriptor) {
        requireChildCount(descriptor, 2);
        GrammarDescriptor value = descriptor.children().getFirst();
        GrammarDescriptor fallback = descriptor.children().get(1);
        int opening = descriptor.metadata().openingTokenIndex();
        int closing = descriptor.metadata().closingTokenIndex();
        if (opening != descriptor.startTokenIndex()
                || closing != descriptor.endTokenIndex() - 1
                || value.startTokenIndex() != opening + 1
                || fallback.startTokenIndex() != value.endTokenIndex() + 1
                || fallback.endTokenIndex() != closing) {
            throw new IllegalArgumentException(
                    "coalescing children do not identify the exact ':' position");
        }
    }

    private static void validateDirectCallShape(GrammarDescriptor descriptor) {
        List<GrammarDescriptor> children = descriptor.children();
        if (children.size() == 2) {
            requireChildKinds(descriptor, ProductionKind.IDENTIFIER, ProductionKind.ARGUMENT_LIST);
            if (children.getFirst().startTokenIndex() != descriptor.startTokenIndex() + 1) {
                throw new IllegalArgumentException("unqualified direct call must begin with '::'");
            }
        } else if (children.size() == 3) {
            if (children.get(1).kind() != ProductionKind.IDENTIFIER
                    || children.get(2).kind() != ProductionKind.ARGUMENT_LIST
                    || children.getFirst().startTokenIndex() != descriptor.startTokenIndex()
                    || children.get(1).startTokenIndex()
                    != children.getFirst().endTokenIndex() + 1) {
                throw new IllegalArgumentException("receiver direct call has an invalid child layout");
            }
        } else {
            throw new IllegalArgumentException("direct call has an invalid child count");
        }
        GrammarDescriptor name = children.get(children.size() - 2);
        GrammarDescriptor arguments = children.getLast();
        if (arguments.startTokenIndex() != name.endTokenIndex()
                || arguments.endTokenIndex() != descriptor.endTokenIndex()) {
            throw new IllegalArgumentException("direct call name and arguments are not contiguous");
        }
    }

    private static void validateMemberAccessShape(GrammarDescriptor descriptor) {
        requireChildCount(descriptor, 2);
        if (descriptor.children().get(1).kind() != ProductionKind.MEMBER_NAME) {
            throw new IllegalArgumentException("member access must end with a member name");
        }
        GrammarDescriptor receiver = descriptor.children().getFirst();
        GrammarDescriptor member = descriptor.children().get(1);
        if (receiver.startTokenIndex() != descriptor.startTokenIndex()
                || member.startTokenIndex() != receiver.endTokenIndex() + 1
                || member.endTokenIndex() != descriptor.endTokenIndex()) {
            throw new IllegalArgumentException("member access has an invalid child layout");
        }
    }

    private static int namespaceAccessorIndex(GrammarDescriptor descriptor, LexedSource source) {
        int end = descriptor.children().getFirst().endTokenIndex();
        return source.tokens().get(end).kind() == TokenKind.ARROW ? end + 1 : end;
    }

    private static void validateNamespaceAccessShape(
            GrammarDescriptor descriptor, LexedSource source, boolean directCall) {
        if (directCall) {
            requireChildKinds(descriptor, ProductionKind.NAMESPACE_PATH,
                    ProductionKind.IDENTIFIER, ProductionKind.ARGUMENT_LIST);
        } else {
            requireChildKinds(descriptor, ProductionKind.NAMESPACE_PATH,
                    ProductionKind.MEMBER_NAME);
        }
        GrammarDescriptor path = descriptor.children().getFirst();
        GrammarDescriptor name = descriptor.children().get(1);
        int accessor = namespaceAccessorIndex(descriptor, source);
        if (path.startTokenIndex() != descriptor.startTokenIndex()
                || accessor == path.endTokenIndex() && path.children().size() < 2
                || name.startTokenIndex() != accessor + 1) {
            throw new IllegalArgumentException("namespace access has an invalid terminal accessor layout");
        }
        if (directCall) {
            GrammarDescriptor arguments = descriptor.children().get(2);
            if (arguments.startTokenIndex() != name.endTokenIndex()
                    || arguments.endTokenIndex() != descriptor.endTokenIndex()) {
                throw new IllegalArgumentException(
                        "namespace direct call name and arguments are not contiguous");
            }
        } else if (name.endTokenIndex() != descriptor.endTokenIndex()) {
            throw new IllegalArgumentException("namespace member must end its access descriptor");
        }
    }

    private static void validateImportDeclarationShape(GrammarDescriptor descriptor) {
        List<GrammarDescriptor> children = descriptor.children();
        int childIndex = 0;
        int expectedStart = descriptor.startTokenIndex() + 1;
        while (childIndex < children.size()
                && children.get(childIndex).kind() == ProductionKind.MODIFIER) {
            GrammarDescriptor modifier = children.get(childIndex++);
            if (modifier.startTokenIndex() != expectedStart
                    || modifier.endTokenIndex() != expectedStart + 1) {
                throw new IllegalArgumentException("import modifiers must immediately follow 'import'");
            }
            expectedStart++;
        }
        if (childIndex >= children.size()
                || children.get(childIndex).kind() != ProductionKind.IMPORT_PATH) {
            throw new IllegalArgumentException("import declaration must contain one import path");
        }
        GrammarDescriptor path = children.get(childIndex++);
        if (path.startTokenIndex() != expectedStart || childIndex + 1 < children.size()) {
            throw new IllegalArgumentException("import declaration has an invalid child layout");
        }
        if (childIndex == children.size()) {
            if (path.endTokenIndex() != descriptor.endTokenIndex()) {
                throw new IllegalArgumentException("direct import must end with its path");
            }
            return;
        }
        GrammarDescriptor suffix = children.get(childIndex);
        if (suffix.kind() == ProductionKind.IMPORT_ALIAS) {
            if (suffix.startTokenIndex() != path.endTokenIndex()
                    || suffix.endTokenIndex() != descriptor.endTokenIndex()) {
                throw new IllegalArgumentException("import alias must immediately follow its path");
            }
        } else if (suffix.kind() == ProductionKind.IMPORT_SELECTION) {
            if (suffix.startTokenIndex() != path.endTokenIndex() + 1
                    || suffix.endTokenIndex() != descriptor.endTokenIndex()) {
                throw new IllegalArgumentException("import selection must follow exactly one terminal arrow");
            }
        } else {
            throw new IllegalArgumentException("import declaration has an invalid suffix");
        }
    }

    private static void validateArrowPathShape(GrammarDescriptor descriptor) {
        List<GrammarDescriptor> children = descriptor.children();
        if (children.isEmpty()) {
            throw new IllegalArgumentException(descriptor.kind() + " must contain a path segment");
        }
        int expectedStart = descriptor.startTokenIndex();
        for (GrammarDescriptor child : children) {
            if (child.kind() != ProductionKind.IDENTIFIER
                    || child.startTokenIndex() != expectedStart
                    || child.endTokenIndex() != expectedStart + 1) {
                throw new IllegalArgumentException(descriptor.kind() + " has an invalid segment layout");
            }
            expectedStart = child.endTokenIndex() + 1;
        }
        if (children.getLast().endTokenIndex() != descriptor.endTokenIndex()) {
            throw new IllegalArgumentException(descriptor.kind() + " must end with its final segment");
        }
    }

    private static void validateAggregateLiteralShape(
            GrammarDescriptor descriptor, LexedSource source, String spelling) {
        ProductionKind typedPrefix = spelling.equals("Array")
                ? ProductionKind.ARRAY_TYPE : ProductionKind.TUPLE_TYPE;
        if (descriptor.children().size() != 2) {
            throw new IllegalArgumentException(spelling + " literal must contain prefix and arguments");
        }
        GrammarDescriptor prefix = descriptor.children().getFirst();
        GrammarDescriptor arguments = descriptor.children().get(1);
        if ((prefix.kind() != ProductionKind.TYPE_NAME && prefix.kind() != typedPrefix)
                || arguments.kind() != ProductionKind.ARGUMENT_LIST
                || prefix.startTokenIndex() != descriptor.startTokenIndex()
                || arguments.startTokenIndex() != prefix.endTokenIndex()
                || arguments.endTokenIndex() != descriptor.endTokenIndex()
                || descriptor.metadata().openingTokenIndex()
                != arguments.metadata().openingTokenIndex()
                || descriptor.metadata().closingTokenIndex()
                != arguments.metadata().closingTokenIndex()
                || !source.tokens().get(prefix.startTokenIndex()).lexeme().equals(spelling)) {
            throw new IllegalArgumentException(spelling + " literal has an invalid aggregate prefix or layout");
        }
        if (prefix.kind() == ProductionKind.TYPE_NAME && prefix.tokenLength() != 1) {
            throw new IllegalArgumentException("bare aggregate prefix must cover exactly its type name");
        }
    }

    private static void validateCompositeTypeShape(
            GrammarDescriptor descriptor, LexedSource source, String spelling) {
        requireChildKinds(descriptor, ProductionKind.TYPE_NAME, ProductionKind.TYPE_ARGUMENT_LIST);
        GrammarDescriptor name = descriptor.children().getFirst();
        GrammarDescriptor arguments = descriptor.children().get(1);
        if (name.startTokenIndex() != descriptor.startTokenIndex()
                || name.tokenLength() != 1
                || arguments.startTokenIndex() != name.endTokenIndex()
                || arguments.endTokenIndex() != descriptor.endTokenIndex()
                || !source.tokens().get(name.startTokenIndex()).lexeme().equals(spelling)) {
            throw new IllegalArgumentException(spelling + " type has an invalid prefix or child layout");
        }
    }

    private static void validateUnitShape(GrammarDescriptor descriptor, LexedSource source) {
        int primary = descriptor.metadata().primaryTokenIndex();
        if (primary < 0) {
            if (!descriptor.children().isEmpty()) {
                throw new IllegalArgumentException("parenthesized Unit must not contain children");
            }
            return;
        }
        requireChildKinds(descriptor, ProductionKind.TYPE_NAME, ProductionKind.ARGUMENT_LIST);
        GrammarDescriptor prefix = descriptor.children().getFirst();
        GrammarDescriptor arguments = descriptor.children().get(1);
        String spelling = source.tokens().get(primary).lexeme();
        if ((!spelling.equals("Array") && !spelling.equals("Tuple"))
                || prefix.startTokenIndex() != descriptor.startTokenIndex()
                || prefix.tokenLength() != 1
                || arguments.startTokenIndex() != prefix.endTokenIndex()
                || arguments.endTokenIndex() != descriptor.endTokenIndex()
                || !arguments.children().isEmpty()
                || descriptor.metadata().openingTokenIndex()
                != arguments.metadata().openingTokenIndex()
                || descriptor.metadata().closingTokenIndex()
                != arguments.metadata().closingTokenIndex()) {
            throw new IllegalArgumentException("typed Unit must be exactly Array[] or Tuple[]");
        }
    }

    private static int conditionalArrowIndex(GrammarDescriptor descriptor) {
        return descriptor.children().get(1).kind() == ProductionKind.PREDICATE_BINDING
                ? descriptor.children().get(1).endTokenIndex()
                : descriptor.children().getFirst().endTokenIndex();
    }

    private static int directAccessorIndex(GrammarDescriptor descriptor) {
        if (descriptor.kind() == ProductionKind.MEMBER_ACCESS) {
            return descriptor.children().getFirst().endTokenIndex();
        }
        return descriptor.children().size() == 2
                ? descriptor.startTokenIndex()
                : descriptor.children().getFirst().endTokenIndex();
    }

    private static List<Integer> separatingArrowIndices(GrammarDescriptor descriptor) {
        return descriptor.children().subList(0, descriptor.children().size() - 1).stream()
                .map(GrammarDescriptor::endTokenIndex)
                .toList();
    }

    private static List<Integer> importSelectionArrowIndices(GrammarDescriptor descriptor) {
        return descriptor.children().getLast().kind() == ProductionKind.IMPORT_SELECTION
                ? List.of(descriptor.children().getLast().startTokenIndex() - 1)
                : List.of();
    }

    private static void requireChildCount(GrammarDescriptor descriptor, int expectedCount) {
        if (descriptor.children().size() != expectedCount) {
            throw new IllegalArgumentException(descriptor.kind() + " has an invalid child count");
        }
    }

    private static void requireChildKinds(
            GrammarDescriptor descriptor, ProductionKind... expectedKinds) {
        requireChildCount(descriptor, expectedKinds.length);
        for (int index = 0; index < expectedKinds.length; index++) {
            ProductionKind expected = expectedKinds[index];
            if (descriptor.children().get(index).kind() != expected) {
                throw new IllegalArgumentException(
                        descriptor.kind() + " child " + index + " must be " + expected);
            }
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

    private static boolean isAsciiDecimal(String spelling) {
        if (spelling.isEmpty()) {
            return false;
        }
        for (int index = 0; index < spelling.length(); index++) {
            char character = spelling.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> childTokenIndices(
            GrammarDescriptor descriptor, ProductionKind childKind) {
        return descriptor.children().stream()
                .filter(child -> child.kind() == childKind)
                .map(GrammarDescriptor::startTokenIndex)
                .toList();
    }

    private static void validateDelimiterTokens(
            GrammarDescriptor descriptor, LexedSource source) {
        int opening = descriptor.metadata().openingTokenIndex();
        int closing = descriptor.metadata().closingTokenIndex();
        if (opening < 0 || closing < 0) {
            return;
        }
        TokenKind openingKind = source.tokens().get(opening).kind();
        TokenKind closingKind = source.tokens().get(closing).kind();
        switch (descriptor.kind()) {
            case NOMINAL_DECLARATION -> {
                requireToken(source.tokens().get(opening), TokenKind.LEFT_BRACE, descriptor.kind());
                requireToken(source.tokens().get(closing), TokenKind.RIGHT_BRACE, descriptor.kind());
            }
            case LAMBDA, CALLABLE_CALL, CONDITIONAL, COALESCE, RANGE, PREFIX_ASSIGNMENT,
                    OPERATOR_S_EXPRESSION, REASSIGNMENT -> {
                requireToken(source.tokens().get(opening), TokenKind.LEFT_PAREN, descriptor.kind());
                requireToken(source.tokens().get(closing), TokenKind.RIGHT_PAREN, descriptor.kind());
            }
            case CALLBACK_LOOP_BRACKET -> {
                requireToken(source.tokens().get(opening), TokenKind.LEFT_BRACKET, descriptor.kind());
                requireToken(source.tokens().get(closing), TokenKind.RIGHT_BRACKET, descriptor.kind());
            }
            case PARENTHESIZED_DIRECT_CALL -> {
                requireToken(source.tokens().get(opening), TokenKind.LEFT_PAREN, descriptor.kind());
                requireToken(source.tokens().get(closing), TokenKind.RIGHT_PAREN, descriptor.kind());
            }
            case MATCH -> {
                int matchOpening = descriptor.metadata().openingTokenIndex();
                boolean matchBracketed = source.tokens().get(matchOpening).kind()
                        == TokenKind.LEFT_BRACKET;
                requireToken(source.tokens().get(matchOpening),
                        matchBracketed ? TokenKind.LEFT_BRACKET : TokenKind.LEFT_PAREN,
                        descriptor.kind());
                requireToken(source.tokens().get(descriptor.metadata().closingTokenIndex()),
                        matchBracketed ? TokenKind.RIGHT_BRACKET : TokenKind.RIGHT_PAREN,
                        descriptor.kind());
            }
            case COND -> {
                boolean bracketed = openingKind == TokenKind.LEFT_BRACKET;
                requireToken(source.tokens().get(opening),
                        bracketed ? TokenKind.LEFT_BRACKET : TokenKind.LEFT_PAREN, descriptor.kind());
                requireToken(source.tokens().get(closing),
                        bracketed ? TokenKind.RIGHT_BRACKET : TokenKind.RIGHT_PAREN, descriptor.kind());
            }
            case CONSTRUCTION -> {
                requireToken(source.tokens().get(opening), TokenKind.LEFT_BRACKET, descriptor.kind());
                requireToken(source.tokens().get(closing), TokenKind.RIGHT_BRACKET, descriptor.kind());
            }
            case ARRAY_LITERAL, TUPLE_LITERAL, ARGUMENT_LIST -> {
                requireToken(source.tokens().get(opening), TokenKind.LEFT_BRACKET, descriptor.kind());
                requireToken(source.tokens().get(closing), TokenKind.RIGHT_BRACKET, descriptor.kind());
            }
            case UNIT_LITERAL -> {
                boolean parentheses = openingKind == TokenKind.LEFT_PAREN
                        && closingKind == TokenKind.RIGHT_PAREN;
                boolean brackets = openingKind == TokenKind.LEFT_BRACKET
                        && closingKind == TokenKind.RIGHT_BRACKET;
                if (!parentheses && !brackets) {
                    throw new IllegalArgumentException("Unit descriptor has invalid delimiters");
                }
            }
            default -> {
                // Other productions either have no singular delimiter pair or
                // are checked by validateDelimitedSequence below.
            }
        }
    }

    private static void validateLeaf(GrammarDescriptor descriptor, LexedSource source) {
        boolean singleTokenProduction = switch (descriptor.kind()) {
            case EOF, IDENTIFIER, LITERAL, TYPE_NAME, PRIMITIVE_TYPE, MODIFIER,
                    COMMA, TYPE_SEPARATOR, OPERATOR, MEMBER_NAME -> true;
            default -> false;
        };
        if (!singleTokenProduction) {
            return;
        }
        if (descriptor.startTokenIndex() + 1 != descriptor.endTokenIndex()) {
            throw new IllegalArgumentException(
                    descriptor.kind() + " must cover exactly one token");
        }
        Token token = source.tokens().get(descriptor.startTokenIndex());
        switch (descriptor.kind()) {
            case EOF -> requireToken(token, TokenKind.EOF, descriptor.kind());
            case IDENTIFIER -> {
                if (!token.kind().isCallbackLoopKeyword()) {
                    requireToken(token, TokenKind.IDENTIFIER, descriptor.kind());
                }
            }
            case LITERAL -> {
                if (!token.kind().isLiteral()) {
                    throw new IllegalArgumentException("literal descriptor points to a non-literal token");
                }
            }
            case TYPE_NAME -> requireToken(token, TokenKind.TYPE_NAME, descriptor.kind());
            case PRIMITIVE_TYPE -> {
                requireToken(token, TokenKind.TYPE_NAME, descriptor.kind());
                if (!isPrimitiveTypeSpelling(token.lexeme())) {
                    throw new IllegalArgumentException(
                            "primitive type descriptor points to a composite type name: "
                                    + token.lexeme());
                }
            }
            case MODIFIER -> requireToken(token, TokenKind.MODIFIER, descriptor.kind());
            case COMMA -> requireToken(token, TokenKind.COMMA, descriptor.kind());
            case TYPE_SEPARATOR -> requireToken(token, TokenKind.SEMICOLON, descriptor.kind());
            case OPERATOR -> {
                if (!token.kind().isOperator()) {
                    throw new IllegalArgumentException("operator descriptor points to a non-operator token");
                }
            }
            case MEMBER_NAME -> {
                if (token.kind() == TokenKind.IDENTIFIER) {
                    break;
                }
                if (token.kind() != TokenKind.INTEGER_LITERAL
                        || !(token.value() instanceof LiteralValue.IntegerLiteral integer)
                        || integer.suffix() != NumericSuffix.NONE
                        || integer.value().signum() < 0
                        || !isAsciiDecimal(token.lexeme())) {
                    throw new IllegalArgumentException(
                            "numeric member descriptor must identify an unsuffixed nonnegative decimal position");
                }
            }
            default -> {
                // Composite productions may also have a one-token range.
            }
        }
    }

    private static void validateSequence(GrammarDescriptor descriptor, LexedSource source) {
        switch (descriptor.kind()) {
            case PROGRAM -> requireContiguousChildren(
                    descriptor.children(), descriptor.startTokenIndex(), descriptor.endTokenIndex(), "program");
            case BLOCK -> validateDelimitedSequence(
                    descriptor, source, TokenKind.LEFT_BRACE, TokenKind.RIGHT_BRACE, "block");
            case PARAMETER_LIST -> validateDelimitedSequence(
                    descriptor, source, TokenKind.BAR, TokenKind.BAR, "parameter list");
            case ARGUMENT_LIST -> validateDelimitedSequence(
                    descriptor, source, TokenKind.LEFT_BRACKET, TokenKind.RIGHT_BRACKET, "argument list");
            case IMPORT_SELECTION -> validateDelimitedSequence(
                    descriptor, source, TokenKind.LEFT_BRACE, TokenKind.RIGHT_BRACE, "import selection");
            case TYPE_ARGUMENT_LIST -> validateDelimitedSequence(
                    descriptor, source, TokenKind.LESS, TokenKind.GREATER, "type argument list");
            case OPERATOR_OPERANDS, CALL_CONTENT -> requireContiguousChildren(
                    descriptor.children(), descriptor.startTokenIndex(), descriptor.endTokenIndex(),
                    descriptor.kind().name().toLowerCase());
            default -> {
                // These productions intentionally retain punctuation as metadata
                // rather than pretending that every token is an AST child.
            }
        }
    }

    private static void validateDelimitedSequence(
            GrammarDescriptor descriptor,
            LexedSource source,
            TokenKind openingKind,
            TokenKind closingKind,
            String label) {
        int opening = descriptor.metadata().openingTokenIndex();
        int closing = descriptor.metadata().closingTokenIndex();
        if (opening < descriptor.startTokenIndex()
                || closing < opening
                || closing >= descriptor.endTokenIndex()
                || descriptor.startTokenIndex() != opening
                || descriptor.endTokenIndex() != closing + 1) {
            throw new IllegalArgumentException(label + " has inconsistent delimiter metadata");
        }
        requireToken(source.tokens().get(opening), openingKind, descriptor.kind());
        requireToken(source.tokens().get(closing), closingKind, descriptor.kind());
        requireContiguousChildren(descriptor.children(), opening + 1, closing, label + " body");
    }

    private static void requireContiguousChildren(
            List<GrammarDescriptor> children, int start, int end, String label) {
        int expected = start;
        for (GrammarDescriptor child : children) {
            if (child.startTokenIndex() != expected) {
                throw new IllegalArgumentException(
                        label + " child sequence has a gap or illegal overlap at token " + expected);
            }
            expected = child.endTokenIndex();
        }
        if (expected != end) {
            throw new IllegalArgumentException(
                    label + " child sequence ends at " + expected + " instead of " + end);
        }
    }

    private static void requireRange(
            GrammarDescriptor descriptor, int start, int end, String label) {
        if (descriptor.startTokenIndex() < start || descriptor.endTokenIndex() > end) {
            throw new IllegalArgumentException(label + " lies outside the token stream");
        }
    }

    private static void requireToken(Token token, TokenKind expected, ProductionKind descriptorKind) {
        if (token.kind() != expected) {
            throw new IllegalArgumentException(
                    descriptorKind + " points to " + token.kind() + " instead of " + expected);
        }
    }
}
