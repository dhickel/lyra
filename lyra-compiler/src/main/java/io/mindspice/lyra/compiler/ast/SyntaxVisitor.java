package io.mindspice.lyra.compiler.ast;

/**
 * Exhaustive visitor for every concrete published syntax node.
 *
 * <p>There is intentionally no default method.  Adding a syntax node requires
 * adding a visitor operation and implementing it in every visitor.</p>
 */
public interface SyntaxVisitor<R> {
    R visitProgram(SyntaxProgram node);

    R visitImportDeclaration(SyntaxNode.ImportDeclaration node);
    R visitImportPath(SyntaxNode.ImportPath node);
    R visitImportAlias(SyntaxNode.ImportAlias node);
    R visitImportSelection(SyntaxNode.ImportSelection node);
    R visitImportItem(SyntaxNode.ImportItem node);

    R visitModifier(SyntaxNode.Modifier node);
    R visitIdentifier(SyntaxNode.Identifier node);
    R visitMemberName(SyntaxNode.MemberName node);
    R visitOperator(SyntaxNode.Operator node);

    R visitTypeContract(SyntaxNode.TypeContract node);
    R visitPrimitiveType(SyntaxNode.PrimitiveType node);
    R visitArrayType(SyntaxNode.ArrayType node);
    R visitTupleType(SyntaxNode.TupleType node);
    R visitFunctionType(SyntaxNode.FunctionType node);
    R visitTypeAnnotation(SyntaxNode.TypeAnnotation node);
    R visitReturnAnnotation(SyntaxNode.ReturnAnnotation node);
    R visitParameter(SyntaxNode.Parameter node);
    R visitParameterList(SyntaxNode.ParameterList node);
    R visitArgumentList(SyntaxNode.ArgumentList node);
    R visitTypeArgumentList(SyntaxNode.TypeArgumentList node);
    R visitPredicateBinding(SyntaxNode.PredicateBinding node);
    R visitMatchArm(SyntaxNode.MatchArm node);

    R visitLetBinding(SyntaxNode.LetBinding node);
    R visitReassignment(SyntaxNode.Reassignment node);

    R visitBooleanLiteral(SyntaxNode.BooleanLiteral node);
    R visitNilLiteral(SyntaxNode.NilLiteral node);
    R visitIntegerLiteral(SyntaxNode.IntegerLiteral node);
    R visitFloatLiteral(SyntaxNode.FloatLiteral node);
    R visitStringLiteral(SyntaxNode.StringLiteral node);
    R visitCharacterLiteral(SyntaxNode.CharacterLiteral node);
    R visitUnitLiteral(SyntaxNode.UnitLiteral node);
    R visitBlock(SyntaxNode.Block node);
    R visitConditional(SyntaxNode.Conditional node);
    R visitMatch(SyntaxNode.Match node);
    R visitCoalesce(SyntaxNode.Coalesce node);
    R visitPrefixAssignment(SyntaxNode.PrefixAssignment node);
    R visitLambda(SyntaxNode.Lambda node);
    R visitCompactLambda(SyntaxNode.CompactLambda node);
    R visitCallableCall(SyntaxNode.CallableCall node);
    R visitDirectCall(SyntaxNode.DirectCall node);
    R visitMemberAccess(SyntaxNode.MemberAccess node);
    R visitNamespaceMemberAccess(SyntaxNode.NamespaceMemberAccess node);
    R visitNamespaceDirectCall(SyntaxNode.NamespaceDirectCall node);
    R visitNamespacePath(SyntaxNode.NamespacePath node);
    R visitIndexAccess(SyntaxNode.IndexAccess node);
    R visitOperatorSExpression(SyntaxNode.OperatorSExpression node);
    R visitOperatorBracket(SyntaxNode.OperatorBracket node);
    R visitArrayLiteral(SyntaxNode.ArrayLiteral node);
    R visitTupleLiteral(SyntaxNode.TupleLiteral node);
    R visitTypeConversion(SyntaxNode.TypeConversion node);
}
