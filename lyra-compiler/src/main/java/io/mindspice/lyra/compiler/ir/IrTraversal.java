package io.mindspice.lyra.compiler.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Read-only exhaustive traversal utility for the closed IR hierarchy. */
public final class IrTraversal {
    private IrTraversal() {
    }

    /** Visits a node and every descendant in explicit structural evaluation order. */
    public static void walk(IrNode root, Consumer<? super IrNode> visitor) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(visitor, "visitor");
        visitor.accept(root);
        for (IrNode child : root.childrenInEvaluationOrder()) {
            walk(child, visitor);
        }
    }

    /** Traverses every module root only after the typed-IR publication gate. */
    public static void walk(TypedIr ir, Consumer<? super IrNode> visitor) {
        Objects.requireNonNull(ir, "ir").requireValidated();
        Objects.requireNonNull(visitor, "visitor");
        for (IrModule module : ir.modules()) {
            walk(module.body(), visitor);
        }
    }

    /** Returns a defensive immutable pre-order traversal. */
    public static List<IrNode> preOrder(IrNode root) {
        ArrayList<IrNode> result = new ArrayList<>();
        walk(root, result::add);
        return List.copyOf(result);
    }

    /** Traverses all module roots in module/source order. */
    public static List<IrNode> preOrder(TypedIr ir) {
        ArrayList<IrNode> result = new ArrayList<>();
        walk(ir, result::add);
        return List.copyOf(result);
    }
}
