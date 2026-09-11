package io.mindspice.lyra.compiler.grammar;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.LiteralValue;
import io.mindspice.lyra.compiler.lex.ModifierKind;
import io.mindspice.lyra.compiler.lex.NumericSuffix;
import io.mindspice.lyra.compiler.lex.Token;
import io.mindspice.lyra.compiler.lex.TokenKind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Syntax-only first phase of the Lyra front end.
 *
 * <p>The implementation uses a bounded cursor while matching, but publishes
 * only immutable descriptors.  No symbol, member, or type lookup is performed
 * here.  The later parser can replay each descriptor against the same token
 * stream and assert the exact recorded range.</p>
 */
public final class GrammarMatcher {
    private GrammarMatcher() {
    }

    /** Matches one successful lexical artifact into an immutable descriptor tree. */
    public static PhaseResult<GrammarProgram> match(LexedSource source) {
        Objects.requireNonNull(source, "source");
        if (source.hasErrors()) {
            return PhaseResult.failure(source.diagnostics());
        }
        return new Cursor(source).run();
    }

    /** Phase-operation spelling retained for callers that use process pipelines. */
    public static PhaseResult<GrammarProgram> process(LexedSource source) {
        return match(source);
    }

    private static final class Cursor {
        private final LexedSource source;
        private final List<Token> tokens;
        private int current;
        private Diagnostic failure;

        private Cursor(LexedSource source) {
            this.source = source;
            this.tokens = source.tokens();
        }

        private PhaseResult<GrammarProgram> run() {
            List<GrammarDescriptor> forms = new ArrayList<>();
            boolean executableFormSeen = false;

            while (!at(TokenKind.EOF)) {
                if (at(TokenKind.IMPORT)) {
                    if (executableFormSeen) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_IMPORT_HEADER,
                                current,
                                "imports must appear in the module header before other forms");
                        return failureResult();
                    }
                    GrammarDescriptor importDeclaration = parseImport();
                    if (importDeclaration == null) {
                        return failureResult();
                    }
                    forms.add(importDeclaration);
                    continue;
                }

                executableFormSeen = true;
                GrammarDescriptor form = parseForm(true);
                if (form == null) {
                    return failureResult();
                }
                forms.add(form);
            }

            int eofIndex = current;
            advance();
            GrammarDescriptor eof = descriptor(
                    ProductionKind.EOF,
                    eofIndex,
                    eofIndex + 1,
                    List.of(),
                    DescriptorMetadata.NONE);
            List<GrammarDescriptor> children = new ArrayList<>(forms);
            children.add(eof);
            GrammarDescriptor root = descriptor(
                    ProductionKind.PROGRAM,
                    0,
                    tokens.size(),
                    children,
                    DescriptorMetadata.NONE);
            GrammarProgram program = new GrammarProgram(
                    source.snapshot().sourceId(),
                    source.snapshot().sha256(),
                    tokens.size(),
                    root);
            program.validateAgainst(source);
            return PhaseResult.success(program, source.diagnostics());
        }

        private GrammarDescriptor parseForm(boolean inTopLevel) {
            if (at(TokenKind.STRUCT) || at(TokenKind.CLASS)) {
                if (!inTopLevel) {
                    fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                            "struct and class declarations are module-level forms");
                    return null;
                }
                return parseNominalDeclaration();
            }
            if (at(TokenKind.IMPORT)) {
                fail(
                        CompilerDiagnosticCodes.PARSE_IMPORT_HEADER,
                        current,
                        "imports are allowed only in the module header");
                return null;
            }
            if (at(TokenKind.LET)) {
                return parseLet(inTopLevel);
            }

            GrammarDescriptor expression = parseExpression();
            if (expression == null) {
                return null;
            }
            if (at(TokenKind.COLON_EQUAL)) {
                int assignment = advance();
                GrammarDescriptor value = parseExpression();
                if (value == null) {
                    return null;
                }
                return descriptor(
                        ProductionKind.REASSIGNMENT,
                        expression.startTokenIndex(),
                        value.endTokenIndex(),
                        List.of(expression, value),
                        metadata(-1, -1, assignment, List.of(), List.of(), List.of()));
            }
            return expression;
        }

        private GrammarDescriptor parseLet(boolean inTopLevel) {
            return parseLet(inTopLevel, false);
        }

        private GrammarDescriptor parseNominalDeclaration() {
            int keyword = advance();
            boolean struct = token(keyword).kind() == TokenKind.STRUCT;
            List<Integer> modifiers = readModifiers(EnumSet.of(ModifierKind.PUBLIC), true, "type declaration");
            if (modifiers == null) return null;
            if (!at(TokenKind.IDENTIFIER) || !token(current).lexeme().matches("[A-Z][A-Za-z0-9_]*")) {
                failExpected("a capitalized type name");
                return null;
            }
            GrammarDescriptor name = leaf(ProductionKind.IDENTIFIER, advance());
            if (!at(TokenKind.LEFT_BRACE)) {
                failExpected("'{' after a type name");
                return null;
            }
            int opening = advance();
            List<GrammarDescriptor> children = new ArrayList<>();
            modifiers.forEach(index -> children.add(leaf(ProductionKind.MODIFIER, index)));
            children.add(name);
            boolean constructorSeen = false;
            while (!at(TokenKind.RIGHT_BRACE)) {
                if (at(TokenKind.EOF)) {
                    failMissingDelimiter("'}' to close a type declaration");
                    return null;
                }
                if (at(TokenKind.LET)) {
                    GrammarDescriptor member = parseLet(true, true);
                    if (member == null) return null;
                    children.add(member);
                } else if (!struct && !constructorSeen && at(TokenKind.IDENTIFIER)
                        && token(current).lexeme().equals(token(name.startTokenIndex()).lexeme())) {
                    int start = current;
                    GrammarDescriptor constructorName = leaf(ProductionKind.IDENTIFIER, advance());
                    if (!at(TokenKind.EQUAL)) {
                        failExpected("'=' after the constructor name");
                        return null;
                    }
                    int equals = advance();
                    GrammarDescriptor lambda = parseExpression();
                    if (lambda == null) return null;
                    if (lambda.kind() != ProductionKind.LAMBDA) {
                        fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, lambda.startTokenIndex(),
                                "a constructor must be a full lambda expression");
                        return null;
                    }
                    children.add(descriptor(ProductionKind.CONSTRUCTOR_DECLARATION, start,
                            lambda.endTokenIndex(), List.of(constructorName, lambda),
                            metadata(-1, -1, equals, List.of(), List.of(), List.of())));
                    constructorSeen = true;
                } else {
                    fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                            "expected a let member or the single same-name class constructor");
                    return null;
                }
            }
            int closing = advance();
            return descriptor(ProductionKind.NOMINAL_DECLARATION, keyword, closing + 1, children,
                    metadata(opening, closing, keyword, List.of(), modifiers, List.of()));
        }

        private GrammarDescriptor parseLet(boolean inTopLevel, boolean member) {
            int start = current;
            int letToken = advance();
            List<Integer> modifierIndices = readModifiers(
                    EnumSet.allOf(ModifierKind.class),
                    inTopLevel,
                    "binding");
            if (modifierIndices == null) {
                return null;
            }

            if (!at(TokenKind.IDENTIFIER)) {
                failExpected("an identifier after let modifiers");
                return null;
            }
            GrammarDescriptor name = leaf(ProductionKind.IDENTIFIER, advance());
            GrammarDescriptor annotation = null;
            if (at(TokenKind.COLON)) {
                annotation = parseNamedTypeAnnotation();
                if (annotation == null) {
                    return null;
                }
            }
            if (member && annotation == null) {
                fail(CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM, name.startTokenIndex(),
                        "a member requires an explicit type annotation");
                return null;
            }
            if (member && !at(TokenKind.EQUAL)) {
                List<GrammarDescriptor> children = new ArrayList<>();
                modifierIndices.forEach(index -> children.add(leaf(ProductionKind.MODIFIER, index)));
                children.add(name);
                children.add(annotation);
                return descriptor(ProductionKind.MEMBER_DECLARATION, start, annotation.endTokenIndex(), children,
                        metadata(-1, -1, letToken, List.of(), modifierIndices, List.of()));
            }
            if (!at(TokenKind.EQUAL)) {
                failExpected("'=' after a binding name");
                return null;
            }
            advance();
            GrammarDescriptor initializer = parseExpression();
            if (initializer == null) {
                return null;
            }
            if (initializer.kind() == ProductionKind.COMPACT_LAMBDA) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_FORM,
                        initializer.startTokenIndex(),
                        "a compact lambda cannot directly initialize a named binding");
                return null;
            }

            List<GrammarDescriptor> children = new ArrayList<>();
            for (Integer index : modifierIndices) {
                children.add(leaf(ProductionKind.MODIFIER, index));
            }
            children.add(name);
            if (annotation != null) {
                children.add(annotation);
            }
            children.add(initializer);
            return descriptor(
                    member ? ProductionKind.MEMBER_DECLARATION : ProductionKind.LET_BINDING,
                    start,
                    initializer.endTokenIndex(),
                    children,
                    metadata(-1, -1, letToken, List.of(), modifierIndices, List.of()));
        }

        private GrammarDescriptor parseImport() {
            int start = current;
            int importToken = advance();
            List<Integer> modifierIndices = new ArrayList<>();
            if (at(TokenKind.MODIFIER)) {
                if (token(current).modifier().orElse(null) != ModifierKind.PUBLIC) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_MODIFIER,
                            current,
                            "only @pub may qualify an import");
                    return null;
                }
                modifierIndices.add(advance());
                if (at(TokenKind.MODIFIER)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_MODIFIER,
                            current,
                            "an import may have at most one @pub modifier");
                    return null;
                }
            }

            if (!at(TokenKind.IDENTIFIER)) {
                failExpected("an import path");
                return null;
            }
            List<GrammarDescriptor> segments = new ArrayList<>();
            List<Integer> arrows = new ArrayList<>();
            segments.add(leaf(ProductionKind.IDENTIFIER, advance()));
            int selectionArrow = -1;
            while (at(TokenKind.ARROW)) {
                int arrow = advance();
                if (at(TokenKind.IDENTIFIER)) {
                    arrows.add(arrow);
                    segments.add(leaf(ProductionKind.IDENTIFIER, advance()));
                } else if (at(TokenKind.LEFT_BRACE)) {
                    selectionArrow = arrow;
                    break;
                } else {
                    failExpected("an identifier or '{' after '->' in an import path");
                    return null;
                }
            }

            GrammarDescriptor path = descriptor(
                    ProductionKind.IMPORT_PATH,
                    segments.getFirst().startTokenIndex(),
                    segments.getLast().endTokenIndex(),
                    segments,
                    metadata(-1, -1, -1, List.of(), List.of(), arrows));
            GrammarDescriptor alias = null;
            GrammarDescriptor selection = null;
            if (selectionArrow >= 0) {
                selection = parseImportSelection();
                if (selection == null) {
                    return null;
                }
            } else if (at(TokenKind.AS)) {
                int asToken = advance();
                if (!at(TokenKind.IDENTIFIER)) {
                    failExpected("an identifier after 'as'");
                    return null;
                }
                GrammarDescriptor aliasName = leaf(ProductionKind.IDENTIFIER, advance());
                alias = descriptor(
                        ProductionKind.IMPORT_ALIAS,
                        asToken,
                        aliasName.endTokenIndex(),
                        List.of(aliasName),
                        metadata(-1, -1, asToken, List.of(), List.of(), List.of()));
            } else if (at(TokenKind.LEFT_BRACE)) {
                fail(
                        CompilerDiagnosticCodes.PARSE_UNEXPECTED_TOKEN,
                        current,
                        "a selective import must use '-> {'");
                return null;
            }

            if (!selectionIsAllowed(modifierIndices, selection)) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_MODIFIER,
                        modifierIndices.getFirst(),
                        "@pub imports must be selective imports");
                return null;
            }

            List<GrammarDescriptor> children = new ArrayList<>();
            for (Integer index : modifierIndices) {
                children.add(leaf(ProductionKind.MODIFIER, index));
            }
            children.add(path);
            if (alias != null) {
                children.add(alias);
            }
            if (selection != null) {
                children.add(selection);
            }
            int end = selection != null
                    ? selection.endTokenIndex()
                    : alias != null ? alias.endTokenIndex() : path.endTokenIndex();
            return descriptor(
                    ProductionKind.IMPORT_DECLARATION,
                    start,
                    end,
                    children,
                    metadata(-1, -1, importToken, List.of(), modifierIndices,
                            selectionArrow >= 0 ? List.of(selectionArrow) : List.of()));
        }

        private boolean selectionIsAllowed(List<Integer> modifierIndices, GrammarDescriptor selection) {
            if (modifierIndices.isEmpty()) {
                return true;
            }
            return selection != null;
        }

        private GrammarDescriptor parseImportSelection() {
            int open = current;
            advance();
            List<GrammarDescriptor> children = new ArrayList<>();
            if (at(TokenKind.RIGHT_BRACE)) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_FORM,
                        current,
                        "a selective import must contain at least one name");
                return null;
            }
            while (!at(TokenKind.RIGHT_BRACE)) {
                if (at(TokenKind.EOF)) {
                    failMissingDelimiter("'}' to close a selective import");
                    return null;
                }
                if (at(TokenKind.COMMA)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                            current,
                            "a comma must separate two import names");
                    return null;
                }
                if (!at(TokenKind.IDENTIFIER)) {
                    failExpected("an imported identifier");
                    return null;
                }
                GrammarDescriptor name = leaf(ProductionKind.IDENTIFIER, advance());
                List<GrammarDescriptor> itemChildren = new ArrayList<>();
                itemChildren.add(name);
                int end = name.endTokenIndex();
                if (at(TokenKind.AS)) {
                    int as = advance();
                    if (!at(TokenKind.IDENTIFIER)) {
                        failExpected("an identifier after 'as'");
                        return null;
                    }
                    GrammarDescriptor aliasName = leaf(ProductionKind.IDENTIFIER, advance());
                    itemChildren.add(descriptor(
                            ProductionKind.IMPORT_ALIAS,
                            as,
                            aliasName.endTokenIndex(),
                            List.of(aliasName),
                            metadata(-1, -1, as, List.of(), List.of(), List.of())));
                    end = aliasName.endTokenIndex();
                }
                children.add(descriptor(
                        ProductionKind.IMPORT_ITEM,
                        name.startTokenIndex(),
                        end,
                        itemChildren,
                        DescriptorMetadata.NONE));

                if (at(TokenKind.COMMA)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                            current,
                            "commas are not separators in import selections");
                    return null;
                } else if (!at(TokenKind.RIGHT_BRACE)) {
                    if (!at(TokenKind.IDENTIFIER)) {
                        failExpected("another imported name or '}'");
                        return null;
                    }
                }
            }
            int close = advance();
            return descriptor(
                    ProductionKind.IMPORT_SELECTION,
                    open,
                    close + 1,
                    children,
                    metadata(open, close, -1, List.of(), List.of(), List.of()));
        }

        private GrammarDescriptor parseExpression() {
            return parseExpression(false);
        }

        private GrammarDescriptor parseMatchHeadExpression() {
            return parseExpression(true);
        }

        private GrammarDescriptor parseExpression(boolean stopBeforeArmArrow) {
            if (failure != null) {
                return null;
            }
            if (at(TokenKind.EOF)) {
                failExpected("an expression");
                return null;
            }

            GrammarDescriptor result;
            TokenKind kind = token(current).kind();
            if (kind == TokenKind.LEFT_PAREN) {
                result = parseParenthesized();
            } else if (kind == TokenKind.LEFT_BRACE) {
                result = parseBlock();
            } else if (kind == TokenKind.BAR) {
                result = parseCompactLambda();
            } else if (kind == TokenKind.DOUBLE_COLON) {
                result = parseUnqualifiedDirectCall();
            } else if (kind == TokenKind.IDENTIFIER) {
                result = leaf(ProductionKind.IDENTIFIER, advance());
            } else if (kind.isLiteral()) {
                result = leaf(ProductionKind.LITERAL, advance());
            } else if (kind == TokenKind.TYPE_NAME) {
                result = parseTypedExpression();
            } else if (kind.isOperator()) {
                result = parseOperatorBracket();
            } else {
                failExpected("an expression");
                return null;
            }
            if (result == null) {
                return null;
            }
            return parsePostfix(result, stopBeforeArmArrow);
        }

        private GrammarDescriptor parseParenthesized() {
            int open = advance();
            if (at(TokenKind.RIGHT_PAREN)) {
                int close = advance();
                return descriptor(
                        ProductionKind.UNIT_LITERAL,
                        open,
                        close + 1,
                        List.of(),
                        metadata(open, close, -1, List.of(), List.of(), List.of()));
            }
            if (at(TokenKind.LAMBDA_ARROW)) {
                return parseLambda(open);
            }
            if (at(TokenKind.MATCH)) {
                int keyword = advance();
                return parseMatch(open, open, keyword, TokenKind.RIGHT_PAREN);
            }
            if (at(TokenKind.COLON_EQUAL)) {
                return parsePrefixAssignment(open);
            }
            if (token(current).kind().isOperator()
                    && peekKind(1) != TokenKind.LEFT_BRACKET) {
                return parseOperatorSExpression(open);
            }

            // The reserved built-in is a call target, never an ordinary value name.
            boolean iteration = token(current).kind().isCallbackLoopKeyword();
            GrammarDescriptor predicate = iteration
                    ? leaf(ProductionKind.IDENTIFIER, advance()) : parseExpression();
            if (predicate == null) {
                return null;
            }

            GrammarDescriptor predicateBinding = null;
            if (!iteration && (at(TokenKind.RANGE_EXCLUSIVE) || at(TokenKind.RANGE_INCLUSIVE))) {
                int rangeOperator = advance();
                GrammarDescriptor end = parseExpression();
                if (end == null) {
                    return null;
                }
                if (!at(TokenKind.COLON)) {
                    failExpected("':' followed by a range step expression");
                    return null;
                }
                advance();
                GrammarDescriptor step = parseExpression();
                if (step == null) {
                    return null;
                }
                if (!at(TokenKind.RIGHT_PAREN)) {
                    failExpected("')' after a range step expression");
                    return null;
                }
                int close = advance();
                return descriptor(ProductionKind.RANGE, open, close + 1,
                        List.of(predicate, end, step),
                        metadata(open, close, rangeOperator, List.of(), List.of(), List.of()));
            }
            if (!iteration && at(TokenKind.IDENTIFIER) && peekKind(1) == TokenKind.ARROW) {
                GrammarDescriptor bindingName = leaf(ProductionKind.IDENTIFIER, advance());
                predicateBinding = descriptor(
                        ProductionKind.PREDICATE_BINDING,
                        bindingName.startTokenIndex(),
                        bindingName.endTokenIndex(),
                        List.of(bindingName),
                        DescriptorMetadata.NONE);
            }
            if (!iteration && at(TokenKind.ARROW)) {
                int arrow = advance();
                GrammarDescriptor thenBranch = parseExpression();
                if (thenBranch == null) {
                    return null;
                }
                GrammarDescriptor elseBranch = null;
                if (at(TokenKind.COLON)) {
                    advance();
                    elseBranch = parseExpression();
                    if (elseBranch == null) {
                        return null;
                    }
                }
                if (!at(TokenKind.RIGHT_PAREN)) {
                    failMissingDelimiter("')' to close a conditional");
                    return null;
                }
                int close = advance();
                List<GrammarDescriptor> children = new ArrayList<>();
                children.add(predicate);
                if (predicateBinding != null) {
                    children.add(predicateBinding);
                }
                children.add(thenBranch);
                if (elseBranch != null) {
                    children.add(elseBranch);
                }
                return descriptor(
                        ProductionKind.CONDITIONAL,
                        open,
                        close + 1,
                        children,
                        metadata(open, close, arrow, List.of(), List.of(), List.of()));
            }
            if (!iteration && at(TokenKind.COLON_EQUAL)) {
                int assignment = advance();
                GrammarDescriptor value = parseExpression();
                if (value == null) {
                    return null;
                }
                if (!at(TokenKind.RIGHT_PAREN)) {
                    failExpected("')' after an infix assignment");
                    return null;
                }
                int close = advance();
                return descriptor(
                        ProductionKind.REASSIGNMENT,
                        open,
                        close + 1,
                        List.of(predicate, value),
                        metadata(open, close, assignment, List.of(), List.of(), List.of()));
            }
            if (!iteration && at(TokenKind.COLON)) {
                int colon = advance();
                GrammarDescriptor fallback = parseExpression();
                if (fallback == null) {
                    return null;
                }
                if (!at(TokenKind.RIGHT_PAREN)) {
                    failMissingDelimiter("')' to close nil-only coalescing");
                    return null;
                }
                int close = advance();
                return descriptor(
                        ProductionKind.COALESCE,
                        open,
                        close + 1,
                        List.of(predicate, fallback),
                        metadata(open, close, colon, List.of(), List.of(), List.of()));
            }

            List<GrammarDescriptor> contentChildren = new ArrayList<>();
            contentChildren.add(descriptor(
                    ProductionKind.CALL_TARGET,
                    predicate.startTokenIndex(),
                    predicate.endTokenIndex(),
                    List.of(predicate),
                    DescriptorMetadata.NONE));
            List<Integer> commas = new ArrayList<>();
            while (!at(TokenKind.RIGHT_PAREN)) {
                if (at(TokenKind.EOF)) {
                    failMissingDelimiter("')' to close a callable call");
                    return null;
                }
                if (at(TokenKind.COMMA)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                            current,
                            "a comma must follow a call argument");
                    return null;
                }
                GrammarDescriptor argumentExpression = parseExpression();
                if (argumentExpression == null) {
                    return null;
                }
                contentChildren.add(descriptor(
                        ProductionKind.ARGUMENT,
                        argumentExpression.startTokenIndex(),
                        argumentExpression.endTokenIndex(),
                        List.of(argumentExpression),
                        DescriptorMetadata.NONE));
                if (at(TokenKind.COMMA)) {
                    int comma = advance();
                    commas.add(comma);
                    contentChildren.add(leaf(ProductionKind.COMMA, comma));
                    if (at(TokenKind.RIGHT_PAREN) || at(TokenKind.COMMA)) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                                current,
                                "a call cannot have a trailing or repeated comma");
                        return null;
                    }
                } else if (!at(TokenKind.RIGHT_PAREN) && !expressionStart()) {
                    failExpected("an argument, ',' or ')'");
                    return null;
                }
            }
            int close = advance();
            GrammarDescriptor content = descriptor(
                    ProductionKind.CALL_CONTENT,
                    predicate.startTokenIndex(),
                    close,
                    contentChildren,
                    metadata(-1, -1, -1, commas, List.of(), List.of()));
            return descriptor(
                    ProductionKind.CALLABLE_CALL,
                    open,
                    close + 1,
                    List.of(content),
                    metadata(open, close, -1, commas, List.of(), List.of()));
        }

        private GrammarDescriptor parseMatch(
                int start,
                int opening,
                int keyword,
                TokenKind closingKind) {
            boolean conditionalMode = at(TokenKind.IDENTIFIER)
                    && token(current).lexeme().equals("_")
                    && peekKind(1) == TokenKind.DOUBLE_QUESTION;
            List<GrammarDescriptor> children = new ArrayList<>();
            if (conditionalMode) {
                advance();
            } else {
                if (at(TokenKind.DOUBLE_QUESTION) || at(closingKind) || at(TokenKind.EOF)) {
                    fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                            "match requires a traditional value or conditional '_' subject");
                    return null;
                }
                GrammarDescriptor subject = parseExpression();
                if (subject == null) {
                    return null;
                }
                children.add(subject);
            }

            boolean finalFallback = false;
            int armCount = 0;
            while (at(TokenKind.DOUBLE_QUESTION)) {
                if (finalFallback) {
                    fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                            "a match arm cannot follow an unconditional wildcard fallback");
                    return null;
                }
                int armStart = current;
                int separator = advance();
                boolean wildcard = at(TokenKind.IDENTIFIER)
                        && token(current).lexeme().equals("_")
                        && (peekKind(1) == TokenKind.WHEN
                        || peekKind(1) == TokenKind.ARROW);
                List<GrammarDescriptor> armChildren = new ArrayList<>();
                if (wildcard) {
                    advance();
                } else {
                    if (matchArmExpressionMissing(closingKind)) {
                        fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                                "match arm marker must be followed by a pattern or condition");
                        return null;
                    }
                    if (bareTypePattern()) {
                        fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                                "match supports value equality, not type or destructuring patterns");
                        return null;
                    }
                    GrammarDescriptor pattern = parseMatchHeadExpression();
                    if (pattern == null) {
                        return null;
                    }
                    armChildren.add(pattern);
                }
                boolean guarded = false;
                if (at(TokenKind.WHEN)) {
                    if (conditionalMode) {
                        fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                                "conditional match arms do not use 'when'");
                        return null;
                    }
                    guarded = true;
                    advance();
                    if (matchArmExpressionMissing(closingKind)) {
                        fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                                "'when' must be followed by a guard expression");
                        return null;
                    }
                    GrammarDescriptor guard = parseMatchHeadExpression();
                    if (guard == null) {
                        return null;
                    }
                    armChildren.add(guard);
                }
                if (at(TokenKind.COMMA)) {
                    fail(CompilerDiagnosticCodes.PARSE_INVALID_COMMA, current,
                            "match arms do not use comma separators");
                    return null;
                }
                if (!at(TokenKind.ARROW)) {
                    fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                            "expected '->' after a match pattern or condition");
                    return null;
                }
                int arrow = advance();
                if (matchArmExpressionMissing(closingKind)) {
                    fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                            "match arm arrow must be followed by a result expression");
                    return null;
                }
                GrammarDescriptor result = parseExpression();
                if (result == null) {
                    return null;
                }
                if (at(TokenKind.COMMA)) {
                    fail(CompilerDiagnosticCodes.PARSE_INVALID_COMMA, current,
                            "match arms do not use comma separators");
                    return null;
                }
                armChildren.add(result);
                children.add(descriptor(
                        ProductionKind.MATCH_ARM,
                        armStart,
                        result.endTokenIndex(),
                        armChildren,
                        metadata(-1, -1, separator, List.of(), List.of(), List.of(arrow))));
                armCount++;
                finalFallback = wildcard && !guarded;
            }
            if (armCount == 0) {
                fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                        "a match requires at least one '??' arm");
                return null;
            }
            if (!finalFallback) {
                fail(CompilerDiagnosticCodes.PARSE_INVALID_FORM, current,
                        "a match requires a final unguarded '?? _ ->' fallback");
                return null;
            }
            if (!at(closingKind)) {
                failMissingDelimiter(closingKind == TokenKind.RIGHT_PAREN
                        ? "')' to close match" : "']' to close match");
                return null;
            }
            int close = advance();
            return descriptor(
                    ProductionKind.MATCH,
                    start,
                    close + 1,
                    children,
                    metadata(opening, close, keyword, List.of(), List.of(), List.of()));
        }

        private boolean matchArmExpressionMissing(TokenKind closingKind) {
            return at(TokenKind.ARROW) || at(TokenKind.WHEN)
                    || at(TokenKind.DOUBLE_QUESTION) || at(TokenKind.COMMA)
                    || at(closingKind) || at(TokenKind.EOF);
        }

        private boolean bareTypePattern() {
            if (!at(TokenKind.TYPE_NAME)) {
                return false;
            }
            if (peekKind(1) == TokenKind.LEFT_BRACKET) {
                return false;
            }
            if (peekKind(1) != TokenKind.LESS) {
                return true;
            }
            int depth = 0;
            for (int index = current + 1; index < source.tokens().size(); index++) {
                TokenKind kind = token(index).kind();
                if (kind == TokenKind.LESS) {
                    depth++;
                } else if (kind == TokenKind.GREATER && --depth == 0) {
                    return index + 1 >= source.tokens().size()
                            || token(index + 1).kind() != TokenKind.LEFT_BRACKET;
                } else if (kind == TokenKind.EOF) {
                    return true;
                }
            }
            return true;
        }

        private GrammarDescriptor parsePrefixAssignment(int open) {
            int assignment = advance();
            GrammarDescriptor target = parseExpression();
            if (target == null) {
                return null;
            }
            GrammarDescriptor value = parseExpression();
            if (value == null) {
                return null;
            }
            if (!at(TokenKind.RIGHT_PAREN)) {
                failExpected("')' after the two assignment operands");
                return null;
            }
            int close = advance();
            return descriptor(
                    ProductionKind.PREFIX_ASSIGNMENT,
                    open,
                    close + 1,
                    List.of(target, value),
                    metadata(open, close, assignment, List.of(), List.of(), List.of()));
        }

        private GrammarDescriptor parseOperatorSExpression(int open) {
            int operator = advance();
            TokenKind operatorKind = tokens.get(operator).kind();
            GrammarDescriptor operatorDescriptor = leaf(ProductionKind.OPERATOR, operator);
            List<GrammarDescriptor> operands = new ArrayList<>();
            List<Integer> commas = new ArrayList<>();
            int operandStart = current;
            while (!at(TokenKind.RIGHT_PAREN)) {
                if (at(TokenKind.EOF)) {
                    failMissingDelimiter("')' to close an operator expression");
                    return null;
                }
                if (at(TokenKind.COMMA)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                            current,
                            "a comma must follow an operator operand");
                    return null;
                }
                GrammarDescriptor operand = parseExpression();
                if (operand == null) {
                    return null;
                }
                operands.add(descriptor(
                        ProductionKind.ARGUMENT,
                        operand.startTokenIndex(),
                        operand.endTokenIndex(),
                        List.of(operand),
                        DescriptorMetadata.NONE));
                if (at(TokenKind.COMMA)) {
                    int comma = advance();
                    commas.add(comma);
                    operands.add(leaf(ProductionKind.COMMA, comma));
                    if (at(TokenKind.RIGHT_PAREN) || at(TokenKind.COMMA)) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                                current,
                                "an operator cannot have a trailing or repeated comma");
                        return null;
                    }
                } else if (!at(TokenKind.RIGHT_PAREN) && !expressionStart()) {
                    failExpected("an operator operand, ',' or ')'");
                    return null;
                }
            }
            int close = advance();
            int operandCount = countKind(operands, ProductionKind.ARGUMENT);
            if (!validArity(operatorKind, operandCount)) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_OPERATOR_ARITY,
                        operator,
                        "operator " + tokens.get(operator).lexeme() + " has an invalid operand count");
                return null;
            }
            GrammarDescriptor operandList = descriptor(
                    ProductionKind.OPERATOR_OPERANDS,
                    operandStart,
                    close,
                    operands,
                    metadata(-1, -1, -1, commas, List.of(), List.of()));
            return descriptor(
                    ProductionKind.OPERATOR_S_EXPRESSION,
                    open,
                    close + 1,
                    List.of(operatorDescriptor, operandList),
                    metadata(open, close, operator, List.of(), List.of(), List.of()));
        }

        private GrammarDescriptor parseOperatorBracket() {
            int operator = advance();
            TokenKind operatorKind = tokens.get(operator).kind();
            if (!at(TokenKind.LEFT_BRACKET)) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_FORM,
                        operator,
                        "an operator must use a bracket argument form outside an S-expression");
                return null;
            }
            GrammarDescriptor arguments = parseArgumentList();
            if (arguments == null) {
                return null;
            }
            int argumentCount = countKind(arguments.children(), ProductionKind.ARGUMENT);
            if (!validArity(operatorKind, argumentCount)) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_OPERATOR_ARITY,
                        operator,
                        "operator " + tokens.get(operator).lexeme() + " has an invalid operand count");
                return null;
            }
            GrammarDescriptor operatorDescriptor = leaf(ProductionKind.OPERATOR, operator);
            return descriptor(
                    ProductionKind.OPERATOR_BRACKET,
                    operator,
                    arguments.endTokenIndex(),
                    List.of(operatorDescriptor, arguments),
                    metadata(-1, -1, operator, List.of(), List.of(), List.of()));
        }

        private GrammarDescriptor parseBlock() {
            int open = advance();
            List<GrammarDescriptor> forms = new ArrayList<>();
            while (!at(TokenKind.RIGHT_BRACE)) {
                if (at(TokenKind.EOF)) {
                    failMissingDelimiter("'}' to close a block");
                    return null;
                }
                GrammarDescriptor form = parseForm(false);
                if (form == null) {
                    return null;
                }
                forms.add(form);
            }
            int close = advance();
            return descriptor(
                    ProductionKind.BLOCK,
                    open,
                    close + 1,
                    forms,
                    metadata(open, close, -1, List.of(), List.of(), List.of()));
        }

        private GrammarDescriptor parseLambda(int open) {
            int arrow = advance();
            List<Integer> returnModifiers = readModifiers(
                    EnumSet.of(ModifierKind.NILABLE),
                    true,
                    "lambda return");
            if (returnModifiers == null) {
                return null;
            }
            GrammarDescriptor returnAnnotation = null;
            if (at(TokenKind.COLON)) {
                returnAnnotation = parseReturnAnnotation();
                if (returnAnnotation == null) {
                    return null;
                }
            }
            if (!at(TokenKind.BAR)) {
                failExpected("'|' starting a lambda parameter list");
                return null;
            }
            GrammarDescriptor parameters = parseParameterList();
            if (parameters == null) {
                return null;
            }
            GrammarDescriptor body = parseExpression();
            if (body == null) {
                return null;
            }
            if (!at(TokenKind.RIGHT_PAREN)) {
                failMissingDelimiter("')' to close a lambda");
                return null;
            }
            int close = advance();
            List<GrammarDescriptor> children = new ArrayList<>();
            for (Integer index : returnModifiers) {
                children.add(leaf(ProductionKind.MODIFIER, index));
            }
            if (returnAnnotation != null) {
                children.add(returnAnnotation);
            }
            children.add(parameters);
            children.add(body);
            return descriptor(
                    ProductionKind.LAMBDA,
                    open,
                    close + 1,
                    children,
                    metadata(open, close, arrow, List.of(), returnModifiers, List.of()));
        }

        private GrammarDescriptor parseCompactLambda() {
            int start = current;
            GrammarDescriptor parameters = parseParameterList();
            if (parameters == null) {
                return null;
            }
            GrammarDescriptor body = parseExpression();
            if (body == null) {
                return null;
            }
            return descriptor(
                    ProductionKind.COMPACT_LAMBDA,
                    start,
                    body.endTokenIndex(),
                    List.of(parameters, body),
                    DescriptorMetadata.NONE);
        }

        private GrammarDescriptor parseParameterList() {
            int open = current;
            if (!at(TokenKind.BAR)) {
                failExpected("'|' starting a parameter list");
                return null;
            }
            advance();
            List<GrammarDescriptor> children = new ArrayList<>();
            List<Integer> commas = new ArrayList<>();
            List<Integer> modifiers = new ArrayList<>();
            while (!at(TokenKind.BAR)) {
                if (at(TokenKind.EOF)) {
                    failMissingDelimiter("'|' to close a parameter list");
                    return null;
                }
                if (at(TokenKind.COMMA)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                            current,
                            "a comma must follow a parameter");
                    return null;
                }
                GrammarDescriptor parameter = parseParameter();
                if (parameter == null) {
                    return null;
                }
                children.add(parameter);
                modifiers.addAll(parameter.metadata().modifierTokenIndices());
                if (at(TokenKind.COMMA)) {
                    int comma = advance();
                    commas.add(comma);
                    children.add(leaf(ProductionKind.COMMA, comma));
                    if (at(TokenKind.BAR) || at(TokenKind.COMMA)) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                                current,
                                "a parameter list cannot have a trailing or repeated comma");
                        return null;
                    }
                } else if (!at(TokenKind.BAR) && !parameterStart()) {
                    failExpected("a parameter, ',' or '|'");
                    return null;
                }
            }
            int close = advance();
            return descriptor(
                    ProductionKind.PARAMETER_LIST,
                    open,
                    close + 1,
                    children,
                    metadata(open, close, -1, commas, modifiers, List.of()));
        }

        private GrammarDescriptor parseParameter() {
            int start = current;
            List<Integer> modifiers = readModifiers(
                    EnumSet.of(ModifierKind.MUTABLE, ModifierKind.NILABLE),
                    true,
                    "parameter");
            if (modifiers == null) {
                return null;
            }
            if (!at(TokenKind.IDENTIFIER)) {
                failExpected("a parameter identifier");
                return null;
            }
            GrammarDescriptor name = leaf(ProductionKind.IDENTIFIER, advance());
            GrammarDescriptor annotation = null;
            if (at(TokenKind.COLON)) {
                annotation = parseNamedTypeAnnotation();
                if (annotation == null) {
                    return null;
                }
            }
            List<GrammarDescriptor> children = new ArrayList<>();
            for (Integer index : modifiers) {
                children.add(leaf(ProductionKind.MODIFIER, index));
            }
            children.add(name);
            if (annotation != null) {
                children.add(annotation);
            }
            int end = annotation == null ? name.endTokenIndex() : annotation.endTokenIndex();
            return descriptor(
                    ProductionKind.PARAMETER,
                    start,
                    end,
                    children,
                    metadata(-1, -1, -1, List.of(), modifiers, List.of()));
        }

        private GrammarDescriptor parseNamedTypeAnnotation() {
            int colon = current;
            if (!token(colon).hasLeadingWhitespace()) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_ANNOTATION_SPACING,
                        colon,
                        "a named type annotation requires whitespace before ':'");
                return null;
            }
            advance();
            if (at(TokenKind.EOF) || token(current).hasLeadingWhitespace()) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_ANNOTATION_SPACING,
                        current,
                        "a type annotation may not have whitespace after ':'");
                return null;
            }
            GrammarDescriptor type = parseType();
            if (type == null) {
                return null;
            }
            return descriptor(
                    ProductionKind.TYPE_ANNOTATION,
                    colon,
                    type.endTokenIndex(),
                    List.of(type),
                    metadata(-1, -1, colon, List.of(), List.of(), List.of()));
        }

        private GrammarDescriptor parseReturnAnnotation() {
            int colon = advance();
            if (at(TokenKind.EOF) || token(current).hasLeadingWhitespace()) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_ANNOTATION_SPACING,
                        current,
                        "a return annotation may not have whitespace after ':'");
                return null;
            }
            GrammarDescriptor type = parseType();
            if (type == null) {
                return null;
            }
            return descriptor(
                    ProductionKind.RETURN_ANNOTATION,
                    colon,
                    type.endTokenIndex(),
                    List.of(type),
                    metadata(-1, -1, colon, List.of(), List.of(), List.of()));
        }

        private GrammarDescriptor parseType() {
            int start = current;
            List<Integer> modifiers = readModifiers(
                    EnumSet.of(ModifierKind.MUTABLE, ModifierKind.NILABLE),
                    true,
                    "type contract");
            if (modifiers == null) {
                return null;
            }
            if (!at(TokenKind.TYPE_NAME) && !at(TokenKind.IDENTIFIER)) {
                failExpected("a type name");
                return null;
            }
            int baseToken = advance();
            String spelling = tokens.get(baseToken).lexeme();
            GrammarDescriptor base;
            if (token(baseToken).kind() == TokenKind.IDENTIFIER) {
                List<GrammarDescriptor> segments = new ArrayList<>();
                List<Integer> arrows = new ArrayList<>();
                segments.add(leaf(ProductionKind.IDENTIFIER, baseToken));
                while (at(TokenKind.ARROW)) {
                    arrows.add(advance());
                    if (!at(TokenKind.IDENTIFIER)) {
                        failExpected("a type namespace segment after '->'");
                        return null;
                    }
                    segments.add(leaf(ProductionKind.IDENTIFIER, advance()));
                }
                base = descriptor(ProductionKind.NAMED_TYPE, baseToken, current, segments,
                        metadata(-1, -1, -1, List.of(), List.of(), arrows));
            } else if (spelling.equals("Array") || spelling.equals("Range")
                    || spelling.equals("Tuple") || spelling.equals("Fn")) {
                if (!at(TokenKind.LESS)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM,
                            baseToken,
                            spelling + " types require '<...>'");
                    return null;
                }
                GrammarDescriptor arguments = parseTypeArgumentList(spelling.equals("Fn"));
                if (arguments == null) {
                    return null;
                }
                if (spelling.equals("Array") || spelling.equals("Range")) {
                    if (countTypeChildren(arguments) != 1) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM,
                                baseToken,
                                spelling + " requires exactly one element type");
                        return null;
                    }
                    base = descriptor(
                            spelling.equals("Range") ? ProductionKind.RANGE_TYPE : ProductionKind.ARRAY_TYPE,
                            baseToken,
                            arguments.endTokenIndex(),
                            List.of(leaf(ProductionKind.TYPE_NAME, baseToken), arguments),
                            DescriptorMetadata.NONE);
                } else if (spelling.equals("Tuple")) {
                    if (countTypeChildren(arguments) < 1) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM,
                                baseToken,
                                "Tuple requires at least one element type");
                        return null;
                    }
                    base = descriptor(
                            ProductionKind.TUPLE_TYPE,
                            baseToken,
                            arguments.endTokenIndex(),
                            List.of(leaf(ProductionKind.TYPE_NAME, baseToken), arguments),
                            DescriptorMetadata.NONE);
                } else {
                    base = descriptor(
                            ProductionKind.FUNCTION_TYPE,
                            baseToken,
                            arguments.endTokenIndex(),
                            List.of(leaf(ProductionKind.TYPE_NAME, baseToken), arguments),
                            DescriptorMetadata.NONE);
                }
            } else {
                base = descriptor(
                        ProductionKind.PRIMITIVE_TYPE,
                        baseToken,
                        baseToken + 1,
                        List.of(),
                        DescriptorMetadata.NONE);
            }

            if (modifiers.isEmpty()) {
                return base;
            }
            List<GrammarDescriptor> children = new ArrayList<>();
            for (Integer index : modifiers) {
                children.add(leaf(ProductionKind.MODIFIER, index));
            }
            children.add(base);
            return descriptor(
                    ProductionKind.TYPE_CONTRACT,
                    start,
                    base.endTokenIndex(),
                    children,
                    metadata(-1, -1, -1, List.of(), modifiers, List.of()));
        }

        private GrammarDescriptor parseTypeArgumentList(boolean function) {
            int open = advance();
            List<GrammarDescriptor> children = new ArrayList<>();
            List<Integer> commas = new ArrayList<>();
            if (function) {
                while (!at(TokenKind.SEMICOLON)) {
                    if (at(TokenKind.EOF) || at(TokenKind.GREATER)) {
                        failExpected("';' separating function parameters from the return type");
                        return null;
                    }
                    if (at(TokenKind.COMMA)) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                                current,
                                "a comma must follow a function parameter type");
                        return null;
                    }
                    GrammarDescriptor type = parseType();
                    if (type == null) {
                        return null;
                    }
                    children.add(type);
                    if (at(TokenKind.COMMA)) {
                        int comma = advance();
                        commas.add(comma);
                        children.add(leaf(ProductionKind.COMMA, comma));
                        if (at(TokenKind.SEMICOLON) || at(TokenKind.COMMA)) {
                            fail(
                                    CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                                    current,
                                    "a function type cannot have a trailing or repeated parameter comma");
                            return null;
                        }
                    } else if (!at(TokenKind.SEMICOLON) && at(TokenKind.EOF)) {
                        failMissingDelimiter("'>' to close a function type");
                        return null;
                    } else if (!at(TokenKind.SEMICOLON) && !typeStart()) {
                        failExpected("a function type parameter, ',' or ';'");
                        return null;
                    }
                }
                int separator = advance();
                children.add(leaf(ProductionKind.TYPE_SEPARATOR, separator));
                GrammarDescriptor returnType = parseType();
                if (returnType == null) {
                    return null;
                }
                children.add(returnType);
            } else {
                while (!at(TokenKind.GREATER)) {
                    if (at(TokenKind.EOF)) {
                        failMissingDelimiter("'>' to close a type argument list");
                        return null;
                    }
                    if (at(TokenKind.COMMA)) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                                current,
                                "a comma must follow a type argument");
                        return null;
                    }
                    GrammarDescriptor type = parseType();
                    if (type == null) {
                        return null;
                    }
                    children.add(type);
                    if (at(TokenKind.COMMA)) {
                        int comma = advance();
                        commas.add(comma);
                        children.add(leaf(ProductionKind.COMMA, comma));
                        if (at(TokenKind.GREATER) || at(TokenKind.COMMA)) {
                            fail(
                                    CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                                    current,
                                    "a type argument list cannot have a trailing or repeated comma");
                            return null;
                        }
                    } else if (!at(TokenKind.GREATER) && at(TokenKind.EOF)) {
                        failMissingDelimiter("'>' to close a type argument list");
                        return null;
                    } else if (!at(TokenKind.GREATER) && !typeStart()) {
                        failExpected("a type argument, ',' or '>'");
                        return null;
                    }
                }
            }
            if (!at(TokenKind.GREATER)) {
                failMissingDelimiter("'>' to close a type argument list");
                return null;
            }
            int close = advance();
            return descriptor(
                    ProductionKind.TYPE_ARGUMENT_LIST,
                    open,
                    close + 1,
                    children,
                    metadata(open, close, -1, commas, List.of(), List.of()));
        }

        private GrammarDescriptor parseTypedExpression() {
            int start = current;
            String spelling = token(current).lexeme();
            if (spelling.equals("Array") || spelling.equals("Tuple")) {
                GrammarDescriptor typePrefix = null;
                if (peekKind(1) == TokenKind.LESS) {
                    typePrefix = parseType();
                    if (typePrefix == null) {
                        return null;
                    }
                } else {
                    typePrefix = leaf(ProductionKind.TYPE_NAME, advance());
                }
                if (!at(TokenKind.LEFT_BRACKET)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM,
                            start,
                            spelling + " expressions require '[...]'");
                    return null;
                }
                GrammarDescriptor arguments = parseArgumentList();
                if (arguments == null) {
                    return null;
                }
                int count = countKind(arguments.children(), ProductionKind.ARGUMENT);
                if (typePrefix.kind() == ProductionKind.TYPE_NAME && count == 0) {
                    return descriptor(
                            ProductionKind.UNIT_LITERAL,
                            start,
                            arguments.endTokenIndex(),
                            List.of(typePrefix, arguments),
                            metadata(
                                    arguments.metadata().openingTokenIndex(),
                                    arguments.metadata().closingTokenIndex(),
                                    start,
                                    List.of(),
                                    List.of(),
                                    List.of()));
                }
                ProductionKind kind = spelling.equals("Array")
                        ? ProductionKind.ARRAY_LITERAL
                        : ProductionKind.TUPLE_LITERAL;
                return descriptor(
                        kind,
                        start,
                        arguments.endTokenIndex(),
                        List.of(typePrefix, arguments),
                        metadata(
                                arguments.metadata().openingTokenIndex(),
                                arguments.metadata().closingTokenIndex(),
                                start,
                                List.of(),
                                List.of(),
                                List.of()));
            }
            if (spelling.equals("Fn") || spelling.equals("Range")) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM,
                        current,
                        spelling + " is a type name, not a value constructor");
                return null;
            }
            GrammarDescriptor type = descriptor(
                    ProductionKind.PRIMITIVE_TYPE,
                    advance(),
                    current,
                    List.of(),
                    DescriptorMetadata.NONE);
            if (!at(TokenKind.LEFT_BRACKET)) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM,
                        start,
                        "a type application requires '[value]'");
                return null;
            }
            GrammarDescriptor arguments = parseArgumentList();
            if (arguments == null) {
                return null;
            }
            if (countKind(arguments.children(), ProductionKind.ARGUMENT) != 1) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_TYPE_FORM,
                        start,
                        "a conversion requires exactly one value");
                return null;
            }
            return descriptor(
                    ProductionKind.TYPE_CONVERSION,
                    start,
                    arguments.endTokenIndex(),
                    List.of(type, arguments),
                    DescriptorMetadata.NONE);
        }

        private GrammarDescriptor parseArgumentList() {
            int open = current;
            advance();
            List<GrammarDescriptor> children = new ArrayList<>();
            List<Integer> commas = new ArrayList<>();
            while (!at(TokenKind.RIGHT_BRACKET)) {
                if (at(TokenKind.EOF)) {
                    failMissingDelimiter("']' to close an argument list");
                    return null;
                }
                if (at(TokenKind.COMMA)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                            current,
                            "a comma must follow an argument");
                    return null;
                }
                GrammarDescriptor expression = parseExpression();
                if (expression == null) {
                    return null;
                }
                children.add(descriptor(
                        ProductionKind.ARGUMENT,
                        expression.startTokenIndex(),
                        expression.endTokenIndex(),
                        List.of(expression),
                        DescriptorMetadata.NONE));
                if (at(TokenKind.COMMA)) {
                    int comma = advance();
                    commas.add(comma);
                    children.add(leaf(ProductionKind.COMMA, comma));
                    if (at(TokenKind.RIGHT_BRACKET) || at(TokenKind.COMMA)) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_INVALID_COMMA,
                                current,
                                "an argument list cannot have a trailing or repeated comma");
                        return null;
                    }
                } else if (!at(TokenKind.RIGHT_BRACKET) && !expressionStart()) {
                    failExpected("an argument, ',' or ']'");
                    return null;
                }
            }
            int close = advance();
            return descriptor(
                    ProductionKind.ARGUMENT_LIST,
                    open,
                    close + 1,
                    children,
                    metadata(open, close, -1, commas, List.of(), List.of()));
        }

        private GrammarDescriptor parseUnqualifiedDirectCall() {
            int start = current;
            int accessor = advance();
            if (at(TokenKind.MATCH)) {
                int keyword = advance();
                if (!at(TokenKind.LEFT_BRACKET)) {
                    fail(CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR, current,
                            "'::match' must be followed by bracket match contents");
                    return null;
                }
                int opening = advance();
                return parseMatch(start, opening, keyword, TokenKind.RIGHT_BRACKET);
            }
            if (!at(TokenKind.IDENTIFIER) && !token(current).kind().isCallbackLoopKeyword()) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR,
                        current,
                        "'::' must be followed by a call target name");
                return null;
            }
            GrammarDescriptor name = leaf(ProductionKind.IDENTIFIER, advance());
            if (!at(TokenKind.LEFT_BRACKET)) {
                fail(
                        CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR,
                        current,
                        "a '::' call must be followed by bracket arguments");
                return null;
            }
            GrammarDescriptor arguments = parseArgumentList();
            if (arguments == null) {
                return null;
            }
            return descriptor(
                    ProductionKind.DIRECT_CALL,
                    start,
                    arguments.endTokenIndex(),
                    List.of(name, arguments),
                    metadata(-1, -1, accessor, List.of(), List.of(), List.of()));
        }

        private GrammarDescriptor parsePostfix(
                GrammarDescriptor base,
                boolean stopBeforeArmArrow) {
            while (true) {
                if (stopBeforeArmArrow && at(TokenKind.ARROW)
                        && !(base.kind() == ProductionKind.IDENTIFIER
                        && looksLikeNamespaceAccess()
                        && namespaceSuffixIsCompleteMatchHead())) {
                    return base;
                }
                if (at(TokenKind.LEFT_BRACKET)) {
                    GrammarDescriptor arguments = parseArgumentList();
                    if (arguments == null) {
                        return null;
                    }
                    base = descriptor(
                            countKind(arguments.children(), ProductionKind.ARGUMENT) == 1
                                    ? ProductionKind.INDEX_ACCESS : ProductionKind.BRACKET_APPLICATION,
                            base.startTokenIndex(),
                            arguments.endTokenIndex(),
                            List.of(base, arguments),
                            DescriptorMetadata.NONE);
                    continue;
                }
                if (at(TokenKind.COLON_DOT)) {
                    int accessor = advance();
                    GrammarDescriptor member = parseMemberName();
                    if (member == null) {
                        return null;
                    }
                    base = descriptor(
                            ProductionKind.MEMBER_ACCESS,
                            base.startTokenIndex(),
                            member.endTokenIndex(),
                            List.of(base, member),
                            metadata(-1, -1, accessor, List.of(), List.of(), List.of()));
                    continue;
                }
                if (at(TokenKind.DOUBLE_COLON)) {
                    // Reserved built-ins begin the next expression, never a receiver method call.
                    if (peekKind(1) == TokenKind.MATCH || peekKind(1).isCallbackLoopKeyword()) {
                        return base;
                    }
                    int accessor = advance();
                    if (!at(TokenKind.IDENTIFIER)) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR,
                                current,
                                "'::' must be followed by a method name");
                        return null;
                    }
                    GrammarDescriptor method = leaf(ProductionKind.IDENTIFIER, advance());
                    if (!at(TokenKind.LEFT_BRACKET)) {
                        fail(
                                CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR,
                                current,
                                "a '::' call must be followed by bracket arguments");
                        return null;
                    }
                    GrammarDescriptor arguments = parseArgumentList();
                    if (arguments == null) {
                        return null;
                    }
                    base = descriptor(
                            ProductionKind.DIRECT_CALL,
                            base.startTokenIndex(),
                            arguments.endTokenIndex(),
                            List.of(base, method, arguments),
                            metadata(-1, -1, accessor, List.of(), List.of(), List.of()));
                    continue;
                }
                if (at(TokenKind.ARROW)) {
                    if (base.kind() == ProductionKind.IDENTIFIER
                            && looksLikeNamespaceAccess()) {
                        base = parseNamespaceSuffix(base);
                        if (base == null) {
                            return null;
                        }
                        continue;
                    }
                    return base;
                }
                return base;
            }
        }

        private boolean looksLikeNamespaceAccess() {
            int index = current;
            if (index >= tokens.size() || tokens.get(index).kind() != TokenKind.ARROW) {
                return false;
            }
            // In (predicate -> ::match[...]), the arrow starts a branch, not a namespace suffix.
            if (peekKind(1) == TokenKind.DOUBLE_COLON
                    && (peekKind(2) == TokenKind.MATCH || peekKind(2).isCallbackLoopKeyword())) {
                return false;
            }
            index++;
            while (index < tokens.size()) {
                TokenKind kind = tokens.get(index).kind();
                if (kind == TokenKind.IDENTIFIER) {
                    index++;
                    if (index < tokens.size() && tokens.get(index).kind() == TokenKind.ARROW) {
                        index++;
                        continue;
                    }
                    return index < tokens.size()
                            && (tokens.get(index).kind() == TokenKind.COLON_DOT
                            || tokens.get(index).kind() == TokenKind.DOUBLE_COLON);
                }
                return kind == TokenKind.COLON_DOT || kind == TokenKind.DOUBLE_COLON;
            }
            return false;
        }

        private boolean namespaceSuffixIsCompleteMatchHead() {
            int index = current + 1;
            while (index + 1 < tokens.size()
                    && tokens.get(index).kind() == TokenKind.IDENTIFIER
                    && tokens.get(index + 1).kind() == TokenKind.ARROW) {
                index += 2;
            }
            // The final path segment may adjoin the accessor: game->constants::value[].
            if (index < tokens.size() && tokens.get(index).kind() == TokenKind.IDENTIFIER) {
                index++;
            }
            if (index >= tokens.size()) {
                return false;
            }
            if (tokens.get(index).kind() == TokenKind.COLON_DOT) {
                return matchHeadEndsAtArmBoundary(matchHeadPostfixEnd(index + 2));
            }
            if (tokens.get(index).kind() != TokenKind.DOUBLE_COLON
                    || index + 2 >= tokens.size()
                    || tokens.get(index + 1).kind() != TokenKind.IDENTIFIER
                    || tokens.get(index + 2).kind() != TokenKind.LEFT_BRACKET) {
                return false;
            }
            int depth = 0;
            for (int cursor = index + 2; cursor < tokens.size(); cursor++) {
                TokenKind kind = tokens.get(cursor).kind();
                if (kind == TokenKind.LEFT_BRACKET) {
                    depth++;
                } else if (kind == TokenKind.RIGHT_BRACKET && --depth == 0) {
                    return matchHeadEndsAtArmBoundary(matchHeadPostfixEnd(cursor + 1));
                } else if (kind == TokenKind.EOF) {
                    return false;
                }
            }
            return false;
        }

        private int matchHeadPostfixEnd(int index) {
            int cursor = index;
            while (cursor < tokens.size()) {
                TokenKind kind = tokens.get(cursor).kind();
                if (kind == TokenKind.LEFT_BRACKET) {
                    cursor = bracketEnd(cursor);
                    if (cursor < 0) {
                        return -1;
                    }
                } else if (kind == TokenKind.COLON_DOT) {
                    if (cursor + 1 >= tokens.size()
                            || (tokens.get(cursor + 1).kind() != TokenKind.IDENTIFIER
                            && tokens.get(cursor + 1).kind() != TokenKind.INTEGER_LITERAL)) {
                        return -1;
                    }
                    cursor += 2;
                } else if (kind == TokenKind.DOUBLE_COLON) {
                    if (cursor + 2 >= tokens.size()
                            || tokens.get(cursor + 1).kind() != TokenKind.IDENTIFIER
                            || tokens.get(cursor + 2).kind() != TokenKind.LEFT_BRACKET) {
                        return -1;
                    }
                    cursor = bracketEnd(cursor + 2);
                    if (cursor < 0) {
                        return -1;
                    }
                } else {
                    return cursor;
                }
            }
            return cursor;
        }

        private int bracketEnd(int opening) {
            int depth = 0;
            for (int cursor = opening; cursor < tokens.size(); cursor++) {
                TokenKind kind = tokens.get(cursor).kind();
                if (kind == TokenKind.LEFT_BRACKET) {
                    depth++;
                } else if (kind == TokenKind.RIGHT_BRACKET && --depth == 0) {
                    return cursor + 1;
                } else if (kind == TokenKind.EOF) {
                    return -1;
                }
            }
            return -1;
        }

        private boolean matchHeadEndsAtArmBoundary(int index) {
            return index >= 0 && index < tokens.size()
                    && (tokens.get(index).kind() == TokenKind.WHEN
                    || tokens.get(index).kind() == TokenKind.ARROW);
        }

        private GrammarDescriptor parseNamespaceSuffix(GrammarDescriptor base) {
            List<GrammarDescriptor> segments = new ArrayList<>();
            segments.add(base);
            List<Integer> arrows = new ArrayList<>();
            int terminalArrow = -1;
            while (at(TokenKind.ARROW)) {
                int arrow = advance();
                if (at(TokenKind.IDENTIFIER)) {
                    arrows.add(arrow);
                    segments.add(leaf(ProductionKind.IDENTIFIER, advance()));
                    if (!at(TokenKind.ARROW)) {
                        break;
                    }
                } else if (at(TokenKind.COLON_DOT) || at(TokenKind.DOUBLE_COLON)) {
                    terminalArrow = arrow;
                    break;
                } else {
                    failExpected("an identifier or terminal accessor after '->'");
                    return null;
                }
            }
            GrammarDescriptor path = descriptor(
                    ProductionKind.NAMESPACE_PATH,
                    segments.getFirst().startTokenIndex(),
                    segments.getLast().endTokenIndex(),
                    segments,
                    metadata(-1, -1, -1, List.of(), List.of(), arrows));
            if (at(TokenKind.COLON_DOT)) {
                int accessor = advance();
                GrammarDescriptor member = parseMemberName();
                if (member == null) {
                    return null;
                }
                return descriptor(
                        ProductionKind.NAMESPACE_MEMBER_ACCESS,
                        base.startTokenIndex(),
                        member.endTokenIndex(),
                        List.of(path, member),
                        metadata(-1, -1, accessor, List.of(), List.of(),
                                terminalArrow >= 0 ? List.of(terminalArrow) : List.of()));
            }
            if (at(TokenKind.DOUBLE_COLON)) {
                int accessor = advance();
                if (!at(TokenKind.IDENTIFIER)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR,
                            current,
                            "'::' must be followed by a qualified method name");
                    return null;
                }
                GrammarDescriptor method = leaf(ProductionKind.IDENTIFIER, advance());
                if (!at(TokenKind.LEFT_BRACKET)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR,
                            current,
                            "a qualified '::' call must be followed by bracket arguments");
                    return null;
                }
                GrammarDescriptor arguments = parseArgumentList();
                if (arguments == null) {
                    return null;
                }
                return descriptor(
                        ProductionKind.NAMESPACE_DIRECT_CALL,
                        base.startTokenIndex(),
                        arguments.endTokenIndex(),
                        List.of(path, method, arguments),
                        metadata(-1, -1, accessor, List.of(), List.of(),
                                terminalArrow >= 0 ? List.of(terminalArrow) : List.of()));
            }
            fail(
                    CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR,
                    current,
                    "a namespace-qualified access must end with ':.' or '::'");
            return null;
        }

        private GrammarDescriptor parseMemberName() {
            if (at(TokenKind.IDENTIFIER)) {
                return leaf(ProductionKind.MEMBER_NAME, advance());
            }
            if (at(TokenKind.INTEGER_LITERAL)) {
                Token token = token(current);
                if (token.value() instanceof LiteralValue.IntegerLiteral integer
                        && integer.suffix() == NumericSuffix.NONE) {
                    return leaf(ProductionKind.MEMBER_NAME, advance());
                }
            }
            fail(
                    CompilerDiagnosticCodes.PARSE_INVALID_ACCESSOR,
                    current,
                    "a member accessor requires an identifier or tuple field position");
            return null;
        }

        private List<Integer> readModifiers(
                Set<ModifierKind> allowed,
                boolean publicContext,
                String context) {
            List<Integer> indices = new ArrayList<>();
            Set<ModifierKind> seen = EnumSet.noneOf(ModifierKind.class);
            while (at(TokenKind.MODIFIER)) {
                int index = current;
                ModifierKind modifier = token(index).modifier().orElse(null);
                if (modifier == null) {
                    failExpected("a valid modifier");
                    return null;
                }
                if (!allowed.contains(modifier)
                        || (modifier == ModifierKind.PUBLIC && !publicContext)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_MODIFIER,
                            index,
                            "modifier " + modifier.spelling() + " is not valid in a " + context);
                    return null;
                }
                if (!seen.add(modifier)) {
                    fail(
                            CompilerDiagnosticCodes.PARSE_INVALID_MODIFIER,
                            index,
                            "duplicate modifier " + modifier.spelling());
                    return null;
                }
                indices.add(advance());
            }
            return indices;
        }

        private boolean validArity(TokenKind kind, int count) {
            return switch (kind) {
                case PLUS, ASTERISK, EQUAL_EQUAL, NOT_EQUAL, IDENTITY_EQUAL,
                        IDENTITY_NOT_EQUAL, AND, OR, XOR, LESS, LESS_EQUAL,
                        GREATER, GREATER_EQUAL -> count >= 2;
                case MINUS, SLASH -> count >= 1;
                case PERCENT, CARET -> count == 2;
                case NOT, INCREMENT, DECREMENT -> count == 1;
                default -> false;
            };
        }

        private int countTypeChildren(GrammarDescriptor typeArguments) {
            int count = 0;
            for (GrammarDescriptor child : typeArguments.children()) {
                if (child.kind() != ProductionKind.COMMA
                        && child.kind() != ProductionKind.TYPE_SEPARATOR) {
                    count++;
                }
            }
            return count;
        }

        private int countKind(List<GrammarDescriptor> descriptors, ProductionKind kind) {
            int count = 0;
            for (GrammarDescriptor descriptor : descriptors) {
                if (descriptor.kind() == kind) {
                    count++;
                }
            }
            return count;
        }

        private boolean expressionStart() {
            TokenKind kind = token(current).kind();
            return kind == TokenKind.LEFT_PAREN
                    || kind == TokenKind.LEFT_BRACE
                    || kind == TokenKind.BAR
                    || kind == TokenKind.DOUBLE_COLON
                    || kind == TokenKind.IDENTIFIER
                    || kind == TokenKind.TYPE_NAME
                    || kind.isLiteral()
                    || kind.isOperator();
        }

        private boolean parameterStart() {
            return at(TokenKind.IDENTIFIER) || at(TokenKind.MODIFIER);
        }

        private boolean typeStart() {
            return at(TokenKind.TYPE_NAME) || at(TokenKind.IDENTIFIER) || at(TokenKind.MODIFIER);
        }

        private TokenKind peekKind(int lookahead) {
            int index = current + lookahead;
            return index >= tokens.size() ? TokenKind.EOF : tokens.get(index).kind();
        }

        private boolean at(TokenKind kind) {
            return token(current).kind() == kind;
        }

        private Token token(int index) {
            if (index < 0 || index >= tokens.size()) {
                return tokens.getLast();
            }
            return tokens.get(index);
        }

        private int advance() {
            int result = current;
            if (current < tokens.size()) {
                current++;
            }
            return result;
        }

        private void failExpected(String expected) {
            fail(
                    CompilerDiagnosticCodes.PARSE_UNEXPECTED_TOKEN,
                    current,
                    "expected " + expected + " but found '" + token(current).lexeme() + "'");
        }

        private void failMissingDelimiter(String expected) {
            fail(CompilerDiagnosticCodes.PARSE_MISSING_DELIMITER, current, "expected " + expected);
        }

        private void fail(
                io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code,
                int index,
                String message) {
            if (failure != null) {
                return;
            }
            failure = Diagnostic.error(code, token(index).span(), message);
        }

        private PhaseResult<GrammarProgram> failureResult() {
            return PhaseResult.failure(failure);
        }
    }

    private static GrammarDescriptor leaf(ProductionKind kind, int tokenIndex) {
        return descriptor(kind, tokenIndex, tokenIndex + 1, List.of(), DescriptorMetadata.NONE);
    }

    private static GrammarDescriptor descriptor(
            ProductionKind kind,
            int start,
            int end,
            List<GrammarDescriptor> children,
            DescriptorMetadata metadata) {
        return new GrammarDescriptor(kind, start, end, children, metadata);
    }

    private static DescriptorMetadata metadata(
            int opening,
            int closing,
            int primary,
            List<Integer> commas,
            List<Integer> modifiers,
            List<Integer> operators) {
        return new DescriptorMetadata(opening, closing, primary, commas, modifiers, operators);
    }
}
