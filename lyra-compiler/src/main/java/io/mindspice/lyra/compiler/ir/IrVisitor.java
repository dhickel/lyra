package io.mindspice.lyra.compiler.ir;

/** Exhaustive visitor for every closed IR node. */
public interface IrVisitor<R> {
    R visitConstant(IrNode.Constant node);
    R visitReference(IrNode.Reference node);
    R visitCaptureReference(IrNode.CaptureReference node);
    R visitDeclaration(IrNode.Declaration node);
    R visitRebinding(IrNode.Rebinding node);
    R visitSequence(IrNode.Sequence node);
    R visitBlock(IrNode.Block node);
    R visitArrayLiteral(IrNode.ArrayLiteral node);
    R visitTupleLiteral(IrNode.TupleLiteral node);
    R visitIndexAccess(IrNode.IndexAccess node);
    R visitOperator(IrNode.Operator node);
    R visitShortCircuit(IrNode.ShortCircuit node);
    R visitConversion(IrNode.Conversion node);
    R visitNarrowing(IrNode.Narrowing node);
    R visitBranch(IrNode.Branch node);
    R visitCoalesce(IrNode.Coalesce node);
    R visitMatch(IrNode.Match node);
    R visitRange(IrNode.Range node);
    R visitDirectCall(IrNode.DirectCall node);
    R visitCallableCall(IrNode.CallableCall node);
    R visitLambda(IrNode.Lambda node);
    R visitAccess(IrNode.Access node);
    R visitRuntimeCheck(IrNode.RuntimeCheck node);

    /** Visits one raw node tree in explicit evaluation/pre-order. */
    static <R> void walk(IrNode root, IrVisitor<R> visitor) {
        IrTraversal.walk(root, node -> node.accept(visitor));
    }

    /** Visits a published artifact only after the typed-IR consumer gate. */
    static <R> void walk(TypedIr ir, IrVisitor<R> visitor) {
        IrTraversal.walk(ir, node -> node.accept(visitor));
    }
}
