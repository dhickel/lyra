package io.mindspice.lyra.compiler.ast;

import io.mindspice.lyra.compiler.lex.ModifierKind;
import io.mindspice.lyra.compiler.lex.NumericSuffix;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The syntax-only Lyra tree.
 *
 * <p>Every concrete node owns its source span and all collection-valued
 * components are copied before publication.  This tree deliberately contains
 * spelling and structural information only.  It does not contain symbols,
 * inferred types, JVM descriptors, or mutable resolution state.</p>
 */
public sealed interface SyntaxNode
        permits SyntaxProgram,
                SyntaxNode.Form,
                SyntaxNode.ImportDeclaration,
                SyntaxNode.ImportPath,
                SyntaxNode.ImportAlias,
                SyntaxNode.ImportSelection,
                SyntaxNode.ImportItem,
                SyntaxNode.Modifier,
                SyntaxNode.MemberName,
                SyntaxNode.NamespacePath,
                SyntaxNode.Operator,
                SyntaxNode.Type,
                SyntaxNode.TypeAnnotation,
                SyntaxNode.ReturnAnnotation,
                SyntaxNode.Parameter,
                SyntaxNode.ParameterList,
                SyntaxNode.ArgumentList,
                SyntaxNode.TypeArgumentList,
                SyntaxNode.PredicateBinding,
                SyntaxNode.MemberDeclaration,
                SyntaxNode.ConstructorDeclaration,
                SyntaxNode.MatchArm {

    SourceSpan span();

    <R> R accept(SyntaxVisitor<R> visitor);

    /** A top-level or block form. */
    sealed interface Form extends SyntaxNode permits Statement, Expression {
    }

    /** A declaration or rebinding form. */
    sealed interface Statement extends Form permits LetBinding, Reassignment, NominalDeclaration {
    }

    /** An expression form. */
    sealed interface Expression extends Form
            permits Identifier,
                    Literal,
                    Block,
                    Conditional,
                    Coalesce,
                    Match,
                    Cond,
                    ExplicitConstruction,
                    Range,
                    PrefixAssignment,
                    Lambda,
                    CompactLambda,
                    CallableCall,
                    DirectCall,
                    MemberAccess,
                    NamespaceMemberAccess,
                    NamespaceDirectCall,
                    IndexAccess,
                    BracketApplication,
                    OperatorSExpression,
                    OperatorBracket,
                    ArrayLiteral,
                    TupleLiteral,
                    TypeConversion,
                    Reassignment {
    }

    /** A literal expression with both source spelling and decoded value. */
    sealed interface Literal extends Expression
            permits BooleanLiteral,
                    NilLiteral,
                    IntegerLiteral,
                    FloatLiteral,
                    StringLiteral,
                    CharacterLiteral,
                    UnitLiteral {
        String spelling();
    }

    /** A syntax type, before semantic type construction. */
    sealed interface Type extends SyntaxNode
            permits TypeContract, PrimitiveType, ArrayType, RangeType, TupleType, FunctionType, NamedType {
    }

    enum UnitForm {
        PARENTHESIZED,
        ARRAY,
        TUPLE
    }

    record Modifier(ModifierKind kind, String spelling, SourceSpan span) implements SyntaxNode {
        public Modifier {
            Objects.requireNonNull(kind, "kind");
            requireText(spelling, "spelling");
            requireSpan(span);
        }

        public String lexeme() {
            return spelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitModifier(this);
        }
    }

    /** An identifier token used both as an expression and as a syntax name. */
    record Identifier(String name, String spelling, SourceSpan span) implements Expression {
        public Identifier {
            requireText(name, "name");
            requireText(spelling, "spelling");
            requireSpan(span);
        }

        public String lexeme() {
            return spelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitIdentifier(this);
        }
    }

    record MemberName(
            String spelling,
            Optional<String> identifier,
            Optional<BigInteger> tupleIndex,
            SourceSpan span) implements SyntaxNode {
        public MemberName {
            requireText(spelling, "spelling");
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(tupleIndex, "tupleIndex");
            requireSpan(span);
            if (identifier.isEmpty() == tupleIndex.isEmpty()) {
                throw new IllegalArgumentException(
                        "a member name must be either an identifier or a tuple index");
            }
            tupleIndex.ifPresent(index -> {
                if (index.signum() < 0) {
                    throw new IllegalArgumentException("tuple member index must be non-negative");
                }
            });
            identifier.ifPresent(value -> requireText(value, "identifier"));
        }

        public static MemberName identifier(String spelling, SourceSpan span) {
            return new MemberName(spelling, Optional.of(spelling), Optional.empty(), span);
        }

        public static MemberName tupleIndex(String spelling, BigInteger index, SourceSpan span) {
            return new MemberName(spelling, Optional.empty(), Optional.of(index), span);
        }

        public boolean isIdentifier() {
            return identifier.isPresent();
        }

        public boolean isTupleIndex() {
            return tupleIndex.isPresent();
        }

        public String name() {
            return identifier.orElseThrow(() -> new IllegalStateException("member is a tuple index"));
        }

        public BigInteger index() {
            return tupleIndex.orElseThrow(() -> new IllegalStateException("member is an identifier"));
        }

        public String lexeme() {
            return spelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitMemberName(this);
        }
    }

    record Operator(TokenKind tokenKind, String spelling, SourceSpan span) implements SyntaxNode {
        public Operator {
            Objects.requireNonNull(tokenKind, "tokenKind");
            requireText(spelling, "spelling");
            requireSpan(span);
            if (!tokenKind.isOperator()) {
                throw new IllegalArgumentException("operator node requires an operator token kind");
            }
        }

        public String lexeme() {
            return spelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitOperator(this);
        }
    }

    record TypeContract(List<Modifier> modifiers, Type base, SourceSpan span) implements Type {
        public TypeContract {
            modifiers = copy(modifiers, "modifiers");
            Objects.requireNonNull(base, "base");
            requireSpan(span);
        }

        public Type baseType() {
            return base;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitTypeContract(this);
        }
    }

    record PrimitiveType(String name, SourceSpan span) implements Type {
        public PrimitiveType {
            requireText(name, "name");
            requireSpan(span);
        }

        public String spelling() {
            return name;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitPrimitiveType(this);
        }
    }

    record ArrayType(String name, TypeArgumentList arguments, SourceSpan span) implements Type {
        public ArrayType {
            requireText(name, "name");
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(span);
            if (arguments.types().size() != 1 || arguments.hasFunctionSeparator()) {
                throw new IllegalArgumentException("Array type requires one element type");
            }
        }

        public Type elementType() {
            return arguments.types().getFirst();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitArrayType(this);
        }
    }

    record RangeType(String name, TypeArgumentList arguments, SourceSpan span) implements Type {
        public RangeType {
            if (!"Range".equals(name)) {
                throw new IllegalArgumentException("range type name must be Range");
            }
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(span);
            if (arguments.types().size() != 1 || arguments.hasFunctionSeparator()) {
                throw new IllegalArgumentException("Range type requires one element type");
            }
        }

        public Type elementType() {
            return arguments.types().getFirst();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitRangeType(this);
        }
    }

    record Range(Expression start, Expression end, Expression step, boolean inclusive,
                 SourceSpan operatorSpan, SourceSpan colonSpan, SourceSpan span) implements Expression {
        public Range {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(step, "step");
            requireSpan(operatorSpan);
            requireSpan(colonSpan);
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitRange(this);
        }
    }

    record TupleType(String name, TypeArgumentList arguments, SourceSpan span) implements Type {
        public TupleType {
            requireText(name, "name");
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(span);
            if (arguments.types().isEmpty() || arguments.hasFunctionSeparator()) {
                throw new IllegalArgumentException("Tuple type requires at least one element type");
            }
        }

        public List<Type> elementTypes() {
            return arguments.types();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitTupleType(this);
        }
    }

    record FunctionType(String name, TypeArgumentList arguments, SourceSpan span) implements Type {
        public FunctionType {
            requireText(name, "name");
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(span);
            if (!arguments.hasFunctionSeparator()
                    || arguments.returnType().isEmpty()) {
                throw new IllegalArgumentException("Fn type requires a parameter/return separator and return type");
            }
        }

        public List<Type> parameterTypes() {
            return arguments.parameterTypes();
        }

        public Type returnType() {
            return arguments.returnType().orElseThrow();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitFunctionType(this);
        }
    }

    record TypeAnnotation(Type type, SourceSpan colonSpan, SourceSpan span) implements SyntaxNode {
        public TypeAnnotation {
            Objects.requireNonNull(type, "type");
            requireSpan(colonSpan);
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitTypeAnnotation(this);
        }
    }

    record ReturnAnnotation(Type type, SourceSpan colonSpan, SourceSpan span) implements SyntaxNode {
        public ReturnAnnotation {
            Objects.requireNonNull(type, "type");
            requireSpan(colonSpan);
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitReturnAnnotation(this);
        }
    }

    record Parameter(
            List<Modifier> modifiers,
            Identifier name,
            Optional<TypeAnnotation> annotation,
            SourceSpan span) implements SyntaxNode {
        public Parameter {
            modifiers = copy(modifiers, "modifiers");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(annotation, "annotation");
            requireSpan(span);
        }

        public Identifier identifier() {
            return name;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitParameter(this);
        }
    }

    record ParameterList(
            List<Parameter> parameters,
            List<SourceSpan> commaSpans,
            SourceSpan openingBarSpan,
            SourceSpan closingBarSpan,
            SourceSpan span) implements SyntaxNode {
        public ParameterList {
            parameters = copy(parameters, "parameters");
            commaSpans = copy(commaSpans, "commaSpans");
            requireSpan(openingBarSpan, "openingBarSpan");
            requireSpan(closingBarSpan, "closingBarSpan");
            requireSpan(span);
            if (commaSpans.size() > Math.max(0, parameters.size() - 1)) {
                throw new IllegalArgumentException("parameter comma count exceeds parameter count");
            }
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitParameterList(this);
        }
    }

    record ArgumentList(
            List<Expression> expressions,
            List<SourceSpan> commaSpans,
            SourceSpan openingBracketSpan,
            SourceSpan closingBracketSpan,
            SourceSpan span) implements SyntaxNode {
        public ArgumentList {
            expressions = copy(expressions, "expressions");
            commaSpans = copy(commaSpans, "commaSpans");
            requireSpan(openingBracketSpan, "openingBracketSpan");
            requireSpan(closingBracketSpan, "closingBracketSpan");
            requireSpan(span);
            if (commaSpans.size() > Math.max(0, expressions.size() - 1)) {
                throw new IllegalArgumentException("argument comma count exceeds expression count");
            }
        }

        public List<Expression> arguments() {
            return expressions;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitArgumentList(this);
        }
    }

    record TypeArgumentList(
            List<Type> types,
            List<SourceSpan> commaSpans,
            OptionalInt functionSeparatorPosition,
            Optional<SourceSpan> functionSeparatorSpan,
            SourceSpan openingAngleSpan,
            SourceSpan closingAngleSpan,
            SourceSpan span) implements SyntaxNode {
        public TypeArgumentList {
            types = copy(types, "types");
            commaSpans = copy(commaSpans, "commaSpans");
            Objects.requireNonNull(functionSeparatorPosition, "functionSeparatorPosition");
            Objects.requireNonNull(functionSeparatorSpan, "functionSeparatorSpan");
            requireSpan(openingAngleSpan, "openingAngleSpan");
            requireSpan(closingAngleSpan, "closingAngleSpan");
            requireSpan(span);
            if (functionSeparatorPosition.isPresent() != functionSeparatorSpan.isPresent()) {
                throw new IllegalArgumentException("function separator position and span must agree");
            }
            if (functionSeparatorPosition.isPresent()) {
                int position = functionSeparatorPosition.getAsInt();
                if (position < 0 || position >= types.size()) {
                    throw new IllegalArgumentException("function separator must precede a return type");
                }
            }
            int expectedCommas = functionSeparatorPosition.isPresent()
                    ? Math.max(0, functionSeparatorPosition.getAsInt() - 1)
                    : Math.max(0, types.size() - 1);
            if (commaSpans.size() > expectedCommas) {
                throw new IllegalArgumentException("type comma count exceeds type arguments");
            }
        }

        public boolean hasFunctionSeparator() {
            return functionSeparatorPosition.isPresent();
        }

        public List<Type> parameterTypes() {
            if (functionSeparatorPosition.isEmpty()) {
                return types;
            }
            return List.copyOf(types.subList(0, functionSeparatorPosition.getAsInt()));
        }

        public Optional<Type> returnType() {
            return functionSeparatorPosition.isPresent()
                    ? Optional.of(types.get(functionSeparatorPosition.getAsInt()))
                    : Optional.empty();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitTypeArgumentList(this);
        }
    }

    record PredicateBinding(Identifier name, SourceSpan span) implements SyntaxNode {
        public PredicateBinding {
            Objects.requireNonNull(name, "name");
            requireSpan(span);
        }

        public Identifier identifier() {
            return name;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitPredicateBinding(this);
        }
    }

    enum NominalKind { STRUCT, CLASS }

    /** A nominal type reference; qualification is resolved, never lexically guessed. */
    record NamedType(NamespacePath path, SourceSpan span) implements Type {
        public NamedType {
            Objects.requireNonNull(path, "path");
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitNamedType(this);
        }
    }

    record NominalDeclaration(
            NominalKind kind, List<Modifier> modifiers, Identifier name,
            List<MemberDeclaration> members, Optional<ConstructorDeclaration> constructor,
            SourceSpan keywordSpan, SourceSpan openingBraceSpan, SourceSpan closingBraceSpan,
            SourceSpan span) implements Statement {
        public NominalDeclaration {
            Objects.requireNonNull(kind, "kind");
            modifiers = copy(modifiers, "modifiers");
            Objects.requireNonNull(name, "name");
            members = copy(members, "members");
            Objects.requireNonNull(constructor, "constructor");
            requireSpan(keywordSpan, "keywordSpan");
            requireSpan(openingBraceSpan, "openingBraceSpan");
            requireSpan(closingBraceSpan, "closingBraceSpan");
            requireSpan(span);
            if (kind == NominalKind.STRUCT && constructor.isPresent()) {
                throw new IllegalArgumentException("structs cannot declare constructors");
            }
            if (constructor.isPresent() && !constructor.orElseThrow().name().name().equals(name.name())) {
                throw new IllegalArgumentException("constructor name must match its class");
            }
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitNominalDeclaration(this);
        }
    }

    record MemberDeclaration(
            List<Modifier> modifiers, Identifier name, TypeAnnotation annotation,
            Optional<Expression> initializer, SourceSpan letKeywordSpan,
            Optional<SourceSpan> equalsSpan, SourceSpan span) implements SyntaxNode {
        public MemberDeclaration {
            modifiers = copy(modifiers, "modifiers");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(annotation, "annotation");
            Objects.requireNonNull(initializer, "initializer");
            requireSpan(letKeywordSpan, "letKeywordSpan");
            Objects.requireNonNull(equalsSpan, "equalsSpan");
            requireSpan(span);
            if (initializer.isPresent() != equalsSpan.isPresent()) {
                throw new IllegalArgumentException("member initializer and equals span must agree");
            }
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitMemberDeclaration(this);
        }
    }

    record ConstructorDeclaration(Identifier name, Lambda initializer,
                                  SourceSpan equalsSpan, SourceSpan span) implements SyntaxNode {
        public ConstructorDeclaration {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(initializer, "initializer");
            requireSpan(equalsSpan, "equalsSpan");
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitConstructorDeclaration(this);
        }
    }

    /** Non-unary bracket syntax, before resolving construction versus invalid indexing. */
    record BracketApplication(Expression target, ArgumentList arguments, SourceSpan span)
            implements Expression {
        public BracketApplication {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitBracketApplication(this);
        }
    }

    record LetBinding(
            List<Modifier> modifiers,
            Identifier name,
            Optional<TypeAnnotation> annotation,
            Expression initializer,
            SourceSpan letKeywordSpan,
            SourceSpan equalsSpan,
            SourceSpan span) implements Statement {
        public LetBinding {
            modifiers = copy(modifiers, "modifiers");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(annotation, "annotation");
            Objects.requireNonNull(initializer, "initializer");
            requireSpan(letKeywordSpan, "letKeywordSpan");
            requireSpan(equalsSpan, "equalsSpan");
            requireSpan(span);
        }

        public Identifier identifier() {
            return name;
        }

        public Expression value() {
            return initializer;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitLetBinding(this);
        }
    }

    record Reassignment(
            Expression target,
            Expression value,
            boolean parenthesized,
            SourceSpan assignmentOperatorSpan,
            Optional<SourceSpan> openingDelimiterSpan,
            Optional<SourceSpan> closingDelimiterSpan,
            SourceSpan span) implements Statement, Expression {
        public Reassignment {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(value, "value");
            requireSpan(assignmentOperatorSpan, "assignmentOperatorSpan");
            Objects.requireNonNull(openingDelimiterSpan, "openingDelimiterSpan");
            Objects.requireNonNull(closingDelimiterSpan, "closingDelimiterSpan");
            requireSpan(span);
            if (parenthesized != (openingDelimiterSpan.isPresent() && closingDelimiterSpan.isPresent())) {
                throw new IllegalArgumentException("reassignment enclosure state is inconsistent");
            }
        }

        public Expression assignment() {
            return value;
        }

        public boolean enclosed() {
            return parenthesized;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitReassignment(this);
        }
    }

    record Block(
            List<Form> forms,
            SourceSpan openingBraceSpan,
            SourceSpan closingBraceSpan,
            SourceSpan span) implements Expression {
        public Block {
            forms = copy(forms, "forms");
            requireSpan(openingBraceSpan, "openingBraceSpan");
            requireSpan(closingBraceSpan, "closingBraceSpan");
            requireSpan(span);
        }

        public List<Form> members() {
            return forms;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitBlock(this);
        }
    }

    record Conditional(
            Expression predicate,
            Optional<PredicateBinding> binding,
            Expression thenBranch,
            Optional<Expression> elseBranch,
            SourceSpan openingParenSpan,
            SourceSpan arrowSpan,
            Optional<SourceSpan> elseColonSpan,
            SourceSpan closingParenSpan,
            SourceSpan span) implements Expression {
        public Conditional {
            Objects.requireNonNull(predicate, "predicate");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(thenBranch, "thenBranch");
            Objects.requireNonNull(elseBranch, "elseBranch");
            requireSpan(openingParenSpan, "openingParenSpan");
            requireSpan(arrowSpan, "arrowSpan");
            elseColonSpan.ifPresent(value -> requireSpan(value, "elseColonSpan"));
            requireSpan(closingParenSpan, "closingParenSpan");
            requireSpan(span);
            if (elseBranch.isPresent() != elseColonSpan.isPresent()) {
                throw new IllegalArgumentException("conditional else branch and colon must agree");
            }
        }

        public Expression thenExpression() {
            return thenBranch;
        }

        public Optional<Expression> elseExpression() {
            return elseBranch;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitConditional(this);
        }
    }

    record MatchArm(
            Optional<Expression> pattern,
            Optional<Expression> guard,
            Expression result,
            Optional<SourceSpan> wildcardSpan,
            Optional<SourceSpan> whenSpan,
            SourceSpan arrowSpan,
            SourceSpan span) implements SyntaxNode {
        public MatchArm {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(guard, "guard");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(wildcardSpan, "wildcardSpan");
            Objects.requireNonNull(whenSpan, "whenSpan");
            requireSpan(arrowSpan, "arrowSpan");
            requireSpan(span);
            if (pattern.isPresent() == wildcardSpan.isPresent()) {
                throw new IllegalArgumentException("match arm must have exactly one pattern or wildcard");
            }
            if (guard.isPresent() != whenSpan.isPresent()) {
                throw new IllegalArgumentException("match guard and 'when' span must agree");
            }
        }

        public boolean wildcard() {
            return wildcardSpan.isPresent();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitMatchArm(this);
        }
    }

    record Match(
            Expression subject,
            List<MatchArm> arms,
            SourceSpan matchKeywordSpan,
            Optional<SourceSpan> directAccessorSpan,
            SourceSpan openingDelimiterSpan,
            SourceSpan closingDelimiterSpan,
            SourceSpan span) implements Expression {
        public Match {
            Objects.requireNonNull(subject, "subject");
            arms = copy(arms, "arms");
            requireSpan(matchKeywordSpan, "matchKeywordSpan");
            Objects.requireNonNull(directAccessorSpan, "directAccessorSpan");
            requireSpan(openingDelimiterSpan, "openingDelimiterSpan");
            requireSpan(closingDelimiterSpan, "closingDelimiterSpan");
            requireSpan(span);
            if (arms.isEmpty()) {
                throw new IllegalArgumentException("match requires a fallback arm");
            }
            for (int index = 0; index < arms.size(); index++) {
                MatchArm arm = arms.get(index);
                if (arm.wildcard() && arm.guard().isEmpty() && index != arms.size() - 1) {
                    throw new IllegalArgumentException("unconditional wildcard must be the final match arm");
                }
            }
            MatchArm fallback = arms.getLast();
            if (!fallback.wildcard() || fallback.guard().isPresent()) {
                throw new IllegalArgumentException("match requires a final unguarded wildcard fallback");
            }
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitMatch(this);
        }
    }

    /**
     * Subjectless conditional expression. {@code cond} is a dedicated source
     * form; its arms are ordered truthiness tests with a mandatory final
     * unconditional wildcard fallback and no {@code when} guards.
     */
    record Cond(
            List<MatchArm> arms,
            SourceSpan condKeywordSpan,
            SourceSpan openingDelimiterSpan,
            SourceSpan closingDelimiterSpan,
            SourceSpan span) implements Expression {
        public Cond {
            arms = copy(arms, "arms");
            requireSpan(condKeywordSpan, "condKeywordSpan");
            requireSpan(openingDelimiterSpan, "openingDelimiterSpan");
            requireSpan(closingDelimiterSpan, "closingDelimiterSpan");
            requireSpan(span);
            if (arms.isEmpty()) {
                throw new IllegalArgumentException("cond requires a fallback arm");
            }
            for (int index = 0; index < arms.size(); index++) {
                MatchArm arm = arms.get(index);
                if (arm.guard().isPresent()) {
                    throw new IllegalArgumentException("cond arms cannot have guards");
                }
                if (arm.wildcard() && index != arms.size() - 1) {
                    throw new IllegalArgumentException("unconditional wildcard must be the final cond arm");
                }
            }
            if (!arms.getLast().wildcard()) {
                throw new IllegalArgumentException("cond requires a final unconditional wildcard fallback");
            }
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitCond(this);
        }
    }

    /**
     * Explicit nominal construction {@code :Type[arguments]} or
     * {@code :module->Type[arguments]}.
     */
    record ExplicitConstruction(
            Optional<NamespacePath> namespacePath,
            Identifier typeName,
            ArgumentList arguments,
            SourceSpan colonSpan,
            SourceSpan span) implements Expression {
        public ExplicitConstruction {
            Objects.requireNonNull(namespacePath, "namespacePath");
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(colonSpan, "colonSpan");
            requireSpan(span);
            if (!typeName.span().sourceId().equals(colonSpan.sourceId())) {
                throw new IllegalArgumentException("construction target belongs to a different source");
            }
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitExplicitConstruction(this);
        }
    }

    record Coalesce(
            Expression value,
            Expression fallback,
            SourceSpan openingParenSpan,
            SourceSpan colonSpan,
            SourceSpan closingParenSpan,
            SourceSpan span) implements Expression {
        public Coalesce {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(fallback, "fallback");
            requireSpan(openingParenSpan, "openingParenSpan");
            requireSpan(colonSpan, "colonSpan");
            requireSpan(closingParenSpan, "closingParenSpan");
            requireSpan(span);
        }

        public Expression nilableValue() {
            return value;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitCoalesce(this);
        }
    }

    record PrefixAssignment(
            Expression target,
            Expression value,
            SourceSpan openingParenSpan,
            SourceSpan assignmentOperatorSpan,
            SourceSpan closingParenSpan,
            SourceSpan span) implements Expression {
        public PrefixAssignment {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(value, "value");
            requireSpan(openingParenSpan, "openingParenSpan");
            requireSpan(assignmentOperatorSpan, "assignmentOperatorSpan");
            requireSpan(closingParenSpan, "closingParenSpan");
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitPrefixAssignment(this);
        }
    }

    record Lambda(
            List<Modifier> returnModifiers,
            Optional<ReturnAnnotation> returnAnnotation,
            ParameterList parameterList,
            Expression body,
            SourceSpan openingParenSpan,
            SourceSpan lambdaArrowSpan,
            SourceSpan closingParenSpan,
            SourceSpan span) implements Expression {
        public Lambda {
            returnModifiers = copy(returnModifiers, "returnModifiers");
            Objects.requireNonNull(returnAnnotation, "returnAnnotation");
            Objects.requireNonNull(parameterList, "parameterList");
            Objects.requireNonNull(body, "body");
            requireSpan(openingParenSpan, "openingParenSpan");
            requireSpan(lambdaArrowSpan, "lambdaArrowSpan");
            requireSpan(closingParenSpan, "closingParenSpan");
            requireSpan(span);
        }

        public List<Parameter> parameters() {
            return parameterList.parameters();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitLambda(this);
        }
    }

    record CompactLambda(ParameterList parameterList, Expression body, SourceSpan span)
            implements Expression {
        public CompactLambda {
            Objects.requireNonNull(parameterList, "parameterList");
            Objects.requireNonNull(body, "body");
            requireSpan(span);
        }

        public List<Parameter> parameters() {
            return parameterList.parameters();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitCompactLambda(this);
        }
    }

    record CallableCall(
            Expression target,
            List<Expression> arguments,
            List<SourceSpan> commaSpans,
            SourceSpan openingParenSpan,
            SourceSpan closingParenSpan,
            SourceSpan span) implements Expression {
        public CallableCall {
            Objects.requireNonNull(target, "target");
            arguments = copy(arguments, "arguments");
            commaSpans = copy(commaSpans, "commaSpans");
            requireSpan(openingParenSpan, "openingParenSpan");
            requireSpan(closingParenSpan, "closingParenSpan");
            requireSpan(span);
            if (commaSpans.size() > Math.max(0, arguments.size() - 1)) {
                throw new IllegalArgumentException("call comma count exceeds arguments");
            }
        }

        public List<Expression> argumentExpressions() {
            return arguments;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitCallableCall(this);
        }
    }

    record DirectCall(
            Optional<Expression> receiver,
            Identifier name,
            ArgumentList arguments,
            SourceSpan accessorSpan,
            SourceSpan span) implements Expression {
        public DirectCall {
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(accessorSpan, "accessorSpan");
            requireSpan(span);
        }

        public boolean isQualifiedByReceiver() {
            return receiver.isPresent();
        }

        public List<Expression> argumentExpressions() {
            return arguments.expressions();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitDirectCall(this);
        }
    }

    record MemberAccess(
            Expression receiver,
            MemberName member,
            SourceSpan accessorSpan,
            SourceSpan span) implements Expression {
        public MemberAccess {
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(member, "member");
            requireSpan(accessorSpan, "accessorSpan");
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitMemberAccess(this);
        }
    }

    record NamespacePath(List<Identifier> segments, List<SourceSpan> arrowSpans, SourceSpan span)
            implements SyntaxNode {
        public NamespacePath {
            segments = copy(segments, "segments");
            arrowSpans = copy(arrowSpans, "arrowSpans");
            requireSpan(span);
            if (segments.isEmpty() || arrowSpans.size() != segments.size() - 1) {
                throw new IllegalArgumentException("namespace path segments and arrows do not agree");
            }
        }

        public String finalSegment() {
            return segments.getLast().name();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitNamespacePath(this);
        }
    }

    record NamespaceMemberAccess(
            NamespacePath path,
            MemberName member,
            Optional<SourceSpan> terminalArrowSpan,
            SourceSpan accessorSpan,
            SourceSpan span) implements Expression {
        public NamespaceMemberAccess {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(member, "member");
            Objects.requireNonNull(terminalArrowSpan, "terminalArrowSpan");
            if (terminalArrowSpan.isEmpty() && path.segments().size() < 2) {
                throw new IllegalArgumentException("an unqualified namespace alias requires a terminal arrow");
            }
            requireSpan(accessorSpan, "accessorSpan");
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitNamespaceMemberAccess(this);
        }
    }

    record NamespaceDirectCall(
            NamespacePath path,
            Identifier name,
            ArgumentList arguments,
            Optional<SourceSpan> terminalArrowSpan,
            SourceSpan accessorSpan,
            SourceSpan span) implements Expression {
        public NamespaceDirectCall {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(terminalArrowSpan, "terminalArrowSpan");
            if (terminalArrowSpan.isEmpty() && path.segments().size() < 2) {
                throw new IllegalArgumentException("an unqualified namespace alias requires a terminal arrow");
            }
            requireSpan(accessorSpan, "accessorSpan");
            requireSpan(span);
        }

        public List<Expression> argumentExpressions() {
            return arguments.expressions();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitNamespaceDirectCall(this);
        }
    }

    record IndexAccess(
            Expression receiver,
            Expression index,
            SourceSpan openingBracketSpan,
            SourceSpan closingBracketSpan,
            SourceSpan span) implements Expression {
        public IndexAccess {
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(index, "index");
            requireSpan(openingBracketSpan, "openingBracketSpan");
            requireSpan(closingBracketSpan, "closingBracketSpan");
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitIndexAccess(this);
        }
    }

    record OperatorSExpression(
            Operator operator,
            List<Expression> operands,
            List<SourceSpan> commaSpans,
            SourceSpan openingParenSpan,
            SourceSpan closingParenSpan,
            SourceSpan span) implements Expression {
        public OperatorSExpression {
            Objects.requireNonNull(operator, "operator");
            operands = copy(operands, "operands");
            commaSpans = copy(commaSpans, "commaSpans");
            requireSpan(openingParenSpan, "openingParenSpan");
            requireSpan(closingParenSpan, "closingParenSpan");
            requireSpan(span);
            if (commaSpans.size() > Math.max(0, operands.size() - 1)) {
                throw new IllegalArgumentException("operator comma count exceeds operands");
            }
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitOperatorSExpression(this);
        }
    }

    record OperatorBracket(Operator operator, ArgumentList arguments, SourceSpan span)
            implements Expression {
        public OperatorBracket {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(span);
        }

        public List<Expression> operands() {
            return arguments.expressions();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitOperatorBracket(this);
        }
    }

    record ArrayLiteral(
            Optional<Type> explicitType,
            ArgumentList arguments,
            SourceSpan span) implements Expression {
        public ArrayLiteral {
            Objects.requireNonNull(explicitType, "explicitType");
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(span);
            explicitType.ifPresent(type -> {
                if (!(type instanceof ArrayType)) {
                    throw new IllegalArgumentException("an array literal requires an Array type prefix");
                }
            });
        }

        public List<Expression> elements() {
            return arguments.expressions();
        }

        public Optional<Type> typePrefix() {
            return explicitType;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitArrayLiteral(this);
        }
    }

    record TupleLiteral(
            Optional<Type> explicitType,
            ArgumentList arguments,
            SourceSpan span) implements Expression {
        public TupleLiteral {
            Objects.requireNonNull(explicitType, "explicitType");
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(span);
            explicitType.ifPresent(type -> {
                if (!(type instanceof TupleType)) {
                    throw new IllegalArgumentException("a tuple literal requires a Tuple type prefix");
                }
            });
        }

        public List<Expression> elements() {
            return arguments.expressions();
        }

        public Optional<Type> typePrefix() {
            return explicitType;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitTupleLiteral(this);
        }
    }

    record TypeConversion(
            Type targetType,
            Expression value,
            ArgumentList arguments,
            SourceSpan span) implements Expression {
        public TypeConversion {
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(arguments, "arguments");
            requireSpan(span);
            if (!(targetType instanceof PrimitiveType)
                    || arguments.expressions().size() != 1) {
                throw new IllegalArgumentException("a syntax conversion requires one primitive target and value");
            }
        }

        public PrimitiveType targetPrimitive() {
            return (PrimitiveType) targetType;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitTypeConversion(this);
        }
    }

    record BooleanLiteral(boolean value, String spelling, SourceSpan span) implements Literal {
        public BooleanLiteral {
            requireText(spelling, "spelling");
            requireSpan(span);
        }

        public String lexeme() {
            return spelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitBooleanLiteral(this);
        }
    }

    record NilLiteral(String spelling, SourceSpan span) implements Literal {
        public NilLiteral {
            requireText(spelling, "spelling");
            requireSpan(span);
        }

        public String lexeme() {
            return spelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitNilLiteral(this);
        }
    }

    record IntegerLiteral(
            BigInteger value,
            NumericSuffix suffix,
            String spelling,
            SourceSpan span) implements Literal {
        public IntegerLiteral {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(suffix, "suffix");
            requireText(spelling, "spelling");
            requireSpan(span);
            if (value.signum() < 0) {
                throw new IllegalArgumentException("syntax integer literals do not include a leading sign");
            }
        }

        public BigInteger exactValue() {
            return value;
        }

        public String lexeme() {
            return spelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitIntegerLiteral(this);
        }
    }

    record FloatLiteral(
            BigDecimal value,
            NumericSuffix suffix,
            String spelling,
            SourceSpan span) implements Literal {
        public FloatLiteral {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(suffix, "suffix");
            requireText(spelling, "spelling");
            requireSpan(span);
        }

        public BigDecimal exactValue() {
            return value;
        }

        public String lexeme() {
            return spelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitFloatLiteral(this);
        }
    }

    record StringLiteral(String value, String spelling, SourceSpan span) implements Literal {
        public StringLiteral {
            Objects.requireNonNull(value, "value");
            requireText(spelling, "spelling");
            requireSpan(span);
        }

        public String decodedValue() {
            return value;
        }

        public String lexeme() {
            return spelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitStringLiteral(this);
        }
    }

    record CharacterLiteral(char value, String spelling, SourceSpan span) implements Literal {
        public CharacterLiteral {
            requireText(spelling, "spelling");
            requireSpan(span);
        }

        public char codeUnit() {
            return value;
        }

        public String lexeme() {
            return spelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitCharacterLiteral(this);
        }
    }

    record UnitLiteral(UnitForm form, String sourceSpelling, SourceSpan span) implements Literal {
        public UnitLiteral {
            Objects.requireNonNull(form, "form");
            requireText(sourceSpelling, "sourceSpelling");
            requireSpan(span);
        }

        public String spelling() {
            return sourceSpelling;
        }

        public String lexeme() {
            return sourceSpelling;
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitUnitLiteral(this);
        }
    }

    record ImportPath(List<Identifier> segments, List<SourceSpan> arrowSpans, SourceSpan span)
            implements SyntaxNode {
        public ImportPath {
            segments = copy(segments, "segments");
            arrowSpans = copy(arrowSpans, "arrowSpans");
            requireSpan(span);
            if (segments.isEmpty() || arrowSpans.size() != segments.size() - 1) {
                throw new IllegalArgumentException("import path segments and arrows do not agree");
            }
        }

        public String finalSegment() {
            return segments.getLast().name();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitImportPath(this);
        }
    }

    record ImportAlias(Identifier alias, SourceSpan asKeywordSpan, SourceSpan span)
            implements SyntaxNode {
        public ImportAlias {
            Objects.requireNonNull(alias, "alias");
            requireSpan(asKeywordSpan, "asKeywordSpan");
            requireSpan(span);
        }

        public String name() {
            return alias.name();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitImportAlias(this);
        }
    }

    record ImportItem(
            Identifier name,
            Optional<ImportAlias> alias,
            SourceSpan span) implements SyntaxNode {
        public ImportItem {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(alias, "alias");
            requireSpan(span);
        }

        public String importedName() {
            return name.name();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitImportItem(this);
        }
    }

    record ImportSelection(
            List<ImportItem> items,
            SourceSpan openingBraceSpan,
            SourceSpan closingBraceSpan,
            SourceSpan span) implements SyntaxNode {
        public ImportSelection {
            items = copy(items, "items");
            requireSpan(openingBraceSpan, "openingBraceSpan");
            requireSpan(closingBraceSpan, "closingBraceSpan");
            requireSpan(span);
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitImportSelection(this);
        }
    }

    record ImportDeclaration(
            List<Modifier> modifiers,
            ImportPath path,
            Optional<ImportAlias> alias,
            Optional<ImportSelection> selection,
            SourceSpan importKeywordSpan,
            Optional<SourceSpan> selectionArrowSpan,
            SourceSpan span) implements SyntaxNode {
        public ImportDeclaration {
            modifiers = copy(modifiers, "modifiers");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(selection, "selection");
            requireSpan(importKeywordSpan, "importKeywordSpan");
            Objects.requireNonNull(selectionArrowSpan, "selectionArrowSpan");
            requireSpan(span);
            if (alias.isPresent() && selection.isPresent()) {
                throw new IllegalArgumentException("an import cannot have both alias and selection");
            }
            if (selectionArrowSpan.isPresent() != selection.isPresent()) {
                throw new IllegalArgumentException("selective import arrow and selection must agree");
            }
        }

        public boolean isSelective() {
            return selection.isPresent();
        }

        @Override
        public <R> R accept(SyntaxVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitImportDeclaration(this);
        }
    }

    // Kept private so every public constructor above has the same null/copy policy.
    private static void requireSpan(SourceSpan span) {
        Objects.requireNonNull(span, "span");
    }

    private static void requireSpan(SourceSpan span, String name) {
        Objects.requireNonNull(span, name);
    }

    private static void requireText(String text, String name) {
        Objects.requireNonNull(text, name);
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
