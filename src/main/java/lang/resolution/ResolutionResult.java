package lang.resolution;

import lang.ast.ASTNode;
import util.exceptions.CompExcept;

import java.util.ArrayList;
import java.util.List;

public record ResolutionResult(
    boolean fullyResolved,
    List<ASTNode> resolvedNodes,
    List<CompExcept> warnings
) {
    
    public static ResolutionResult success(List<ASTNode> nodes) {
        return new ResolutionResult(true, nodes, new ArrayList<>());
    }
    
    public static ResolutionResult success(List<ASTNode> nodes, List<CompExcept> warnings) {
        return new ResolutionResult(true, nodes, warnings);
    }
    
    public static ResolutionResult partial(List<ASTNode> nodes) {
        return new ResolutionResult(false, nodes, new ArrayList<>());
    }
    
    public static ResolutionResult partial(List<ASTNode> nodes, List<CompExcept> warnings) {
        return new ResolutionResult(false, nodes, warnings);
    }
    
    public ResolutionResult withWarning(CompExcept warning) {
        List<CompExcept> newWarnings = new ArrayList<>(warnings);
        newWarnings.add(warning);
        return new ResolutionResult(fullyResolved, resolvedNodes, newWarnings);
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    public int warningCount() {
        return warnings.size();
    }
}