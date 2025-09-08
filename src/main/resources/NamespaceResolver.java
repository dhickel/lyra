import util.Result;
import java.util.*;

public class NamespaceResolver {
    private final NamespaceTree namespaceTree;
    private final SymbolTable symbolTable;
    
    public NamespaceResolver(NamespaceTree namespaceTree, SymbolTable symbolTable) {
        this.namespaceTree = namespaceTree;
        this.symbolTable = symbolTable;
    }
    
    public Result<ResolutionResult, ResolutionError> resolveNamespaceChain(
            NamespaceTree.NamespaceNode currentNamespace, 
            List<String> chain) {
        
        if (chain.isEmpty()) {
            return Result.err(ResolutionError.emptyChain());
        }
        
        StringBuilder pathBuilder = new StringBuilder();
        NamespaceTree.NamespaceNode targetNamespace = currentNamespace;
        int namespaceParts = 0;
        
        for (int i = 0; i < chain.size(); i++) {
            String part = chain.get(i);
            
            if (i > 0) pathBuilder.append(".");
            pathBuilder.append(part);
            
            Optional<NamespaceTree.NamespaceNode> childNamespace = 
                namespaceTree.resolveRelativePath(targetNamespace, pathBuilder.toString());
            
            if (childNamespace.isPresent()) {
                targetNamespace = childNamespace.get();
                namespaceParts = i + 1;
            } else {
                break;
            }
        }
        
        if (namespaceParts > 0) {
            List<String> remainingChain = chain.subList(namespaceParts, chain.size());
            
            if (remainingChain.isEmpty()) {
                return Result.ok(new ResolutionResult.Namespace(targetNamespace));
            } else {
                return Result.ok(new ResolutionResult.NamespacedSymbol(
                    targetNamespace, remainingChain));
            }
        }
        
        return Result.ok(new ResolutionResult.LocalSymbol(chain));
    }
    
    public sealed interface ResolutionResult {
        record Namespace(NamespaceTree.NamespaceNode namespace) implements ResolutionResult {}
        record NamespacedSymbol(NamespaceTree.NamespaceNode namespace, List<String> symbolChain) implements ResolutionResult {}
        record LocalSymbol(List<String> symbolChain) implements ResolutionResult {}
    }
}