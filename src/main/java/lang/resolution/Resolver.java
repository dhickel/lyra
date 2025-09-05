package lang.resolution;

import lang.LangType;
import lang.Symbol;
import lang.ast.ASTNode;
import lang.ast.MetaData;
import lang.types.*;
import util.Result;
import util.exceptions.CompExcept;

import java.util.*;

public class Resolver {
    private final SubEnvironment environment;
    private final List<CompExcept> warnings;
    private boolean fullyResolved;
    
    public Resolver(SubEnvironment environment) {
        this.environment = environment;
        this.warnings = new ArrayList<>();
        this.fullyResolved = false;
    }
    
    /**
     * Multi-pass resolution with configurable attempts
     */
    public Result<ResolutionResult, ResolutionError> resolve(
        List<ASTNode> nodes, int maxAttempts) {
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            environment.resetScopeForNextIteration();
            
            boolean allResolved = true;
            for (ASTNode node : nodes) {
                Result<Boolean, ResolutionError> result = resolveTopNode(node);
                if (result.isErr()) return result.castErr();
                allResolved = allResolved && result.unwrap();
            }
            
            if (allResolved) {
                fullyResolved = true;
                return Result.ok(ResolutionResult.success(nodes, warnings));
            }
        }
        
        return Result.ok(ResolutionResult.partial(nodes, warnings));
    }
    
    private Result<Boolean, ResolutionError> resolveTopNode(ASTNode node) {
        return switch (node) {
            case ASTNode.Statement stmt -> resolveStatement(stmt);
            case ASTNode.Expression expr -> resolveExpression(expr);
        };
    }
    
    private Result<Boolean, ResolutionError> resolveStatement(ASTNode.Statement stmt) {
        return switch (stmt) {
            case ASTNode.Statement.Let letStmt -> resolveLet(letStmt);
            case ASTNode.Statement.Assign assignStmt -> resolveAssign(assignStmt);
        };
    }
    
    private Result<Boolean, ResolutionError> resolveExpression(ASTNode.Expression expr) {
        if (expr.metaData().isResolved()) {
            return Result.ok(true);
        }
        
        return switch (expr) {
            case ASTNode.Expression.SExpr sexpr -> resolveSExpr(sexpr);
            case ASTNode.Expression.VExpr vexpr -> resolveValue(vexpr);
            case ASTNode.Expression.BExpr bexpr -> {
                environment.pushScope();
                Result<Boolean, ResolutionError> result = resolveBlock(bexpr);
                environment.popScope();
                yield result;
            }
            case ASTNode.Expression.LExpr lexpr -> {
                environment.pushScope();
                Result<Boolean, ResolutionError> result = resolveLambda(lexpr);
                environment.popScope();
                yield result;
            }
            case ASTNode.Expression.OExpr oexpr -> resolveOperation(oexpr);
            case ASTNode.Expression.PExpr pexpr -> resolvePredicate(pexpr);
            case ASTNode.Expression.MExpr mexpr -> resolveMethodChain(mexpr);
            case ASTNode.Expression.PredicateForm predForm -> Result.ok(false); // TODO: implement
        };
    }
    
    // Statement Resolution
    
    private Result<Boolean, ResolutionError> resolveLet(ASTNode.Statement.Let letStmt) {
        MetaData metaData = letStmt.metaData();
        if (metaData.isResolved()) {
            return Result.ok(true);
        }
        
        // First resolve the assignment expression
        Result<Boolean, ResolutionError> assignmentResult = 
            resolveExpression(letStmt.assignment());
        if (assignmentResult.isErr()) return assignmentResult;
        
        if (!assignmentResult.unwrap()) {
            return Result.ok(false); // Assignment not yet resolved
        }
        
        // Get the type from the assignment
        MetaData.ResolutionState assignmentState = letStmt.assignment().metaData().resolutionState();
        if (!(assignmentState instanceof MetaData.ResolutionState.Resolved resolved)) {
            return Result.ok(false);
        }
        
        LangType assignmentType = resolved.type();
        
        // Check if we have a type annotation
        LangType symbolType = metaData.resolutionState().type();
        if (symbolType != LangType.UNDEFINED) {
            // Verify type compatibility
            TypeCompatibility.Result compatibility = 
                environment.checkTypeCompatibility(assignmentType, symbolType);
            
            if (!compatibility.compatible()) {
                return Result.err(ResolutionError.typeMismatch(
                    metaData.lineChar(), 
                    symbolType.toString(), 
                    assignmentType.toString()));
            }
            
            // Apply type conversion if needed
            if (!(compatibility.conversion() instanceof TypeConversion.None)) {
                letStmt.assignment().metaData().setTypeConversion(compatibility.conversion());
            }
        } else {
            // Infer type from assignment
            symbolType = assignmentType;
        }
        
        // Resolve the symbol type
        Optional<TypeEntry> typeEntry = environment.resolveType(symbolType);
        if (typeEntry.isEmpty()) {
            return Result.ok(false); // Type not yet resolvable
        }
        
        // Add symbol to environment
        Set<ASTNode.Modifier> modifiers = Set.copyOf(letStmt.modifiers());
        Result<Void, ResolutionError> addResult = 
            environment.addSymbol(letStmt.identifier(), typeEntry.get().id(), modifiers);
        
        if (addResult.isErr()) return addResult.castErr();
        
        // Mark let statement as resolved
        metaData.setResolved(symbolType, typeEntry.get().id());
        metaData.setResolveContext(environment.getCurrentContext());
        
        return Result.ok(true);
    }
    
    private Result<Boolean, ResolutionError> resolveAssign(ASTNode.Statement.Assign assignStmt) {
        MetaData metaData = assignStmt.metaData();
        if (metaData.isResolved()) {
            return Result.ok(true);
        }
        
        // Find the symbol being assigned to
        String identifier = assignStmt.target().identifier();
        Optional<SymbolContext> symbolContext = environment.findSymbolInScope(identifier);
        
        if (symbolContext.isEmpty()) {
            return Result.err(ResolutionError.unresolvedSymbol(
                metaData.lineChar(), identifier));
        }
        
        // Check if symbol is mutable
        if (!symbolContext.get().isMutable()) {
            return Result.err(ResolutionError.invalidOperation(
                metaData.lineChar(), "assignment", "Symbol is not mutable"));
        }
        
        // Resolve the assignment expression
        Result<Boolean, ResolutionError> exprResult = resolveExpression(assignStmt.assignment());
        if (exprResult.isErr()) return exprResult;
        if (!exprResult.unwrap()) return Result.ok(false);
        
        // Check type compatibility
        MetaData.ResolutionState exprState = assignStmt.assignment().metaData().resolutionState();
        if (!(exprState instanceof MetaData.ResolutionState.Resolved resolved)) {
            return Result.ok(false);
        }
        
        TypeCompatibility.Result compatibility = environment.checkTypeCompatibility(
            resolved.typeId(), symbolContext.get().typeId());
        
        if (!compatibility.compatible()) {
            Optional<TypeEntry> symbolTypeEntry = environment.getTypeEntry(symbolContext.get().typeId());
            return Result.err(ResolutionError.typeMismatch(
                metaData.lineChar(),
                symbolTypeEntry.map(e -> e.type().toString()).orElse("unknown"),
                resolved.type().toString()));
        }
        
        // Apply type conversion if needed
        if (!(compatibility.conversion() instanceof TypeConversion.None)) {
            assignStmt.assignment().metaData().setTypeConversion(compatibility.conversion());
        }
        
        // Assignment statements resolve to Nil type
        metaData.setResolved(LangType.NIL, environment.getNilType().id());
        metaData.setResolveContext(environment.getCurrentContext());
        
        return Result.ok(true);
    }
    
    // Expression Resolution
    
    private Result<Boolean, ResolutionError> resolveValue(ASTNode.Expression.VExpr vexpr) {
        MetaData metaData = vexpr.metaData();
        if (metaData.isResolved()) {
            return Result.ok(true);
        }
        
        return switch (vexpr.value()) {
            case ASTNode.Value.I32 i32 -> {
                metaData.setResolved(LangType.I32, environment.getI32Type().id());
                yield Result.ok(true);
            }
            case ASTNode.Value.I64 i64 -> {
                metaData.setResolved(LangType.I64, environment.getI64Type().id());
                yield Result.ok(true);
            }
            case ASTNode.Value.F32 f32 -> {
                metaData.setResolved(LangType.F32, environment.getF32Type().id());
                yield Result.ok(true);
            }
            case ASTNode.Value.F64 f64 -> {
                metaData.setResolved(LangType.F64, environment.getF64Type().id());
                yield Result.ok(true);
            }
            case ASTNode.Value.Bool bool -> {
                metaData.setResolved(LangType.BOOL, environment.getBoolType().id());
                yield Result.ok(true);
            }
            case ASTNode.Value.Nil nil -> {
                metaData.setResolved(LangType.NIL, environment.getNilType().id());
                yield Result.ok(true);
            }
            case ASTNode.Value.Identifier identifier -> resolveIdentifierValue(identifier, metaData);
            case ASTNode.Value.Str str -> {
                // String type resolution - need to handle composite type
                Optional<TypeEntry> stringType = environment.resolveType(new LangType.Composite.String());
                if (stringType.isPresent()) {
                    metaData.setResolved(stringType.get().type(), stringType.get().id());
                    yield Result.ok(true);
                } else {
                    yield Result.ok(false);
                }
            }
            default -> Result.ok(false); // Other value types not yet implemented
        };
    }
    
    private Result<Boolean, ResolutionError> resolveIdentifierValue(
        ASTNode.Value.Identifier identifier, MetaData metaData) {
        
        String name = identifier.symbol().identifier();
        Optional<SymbolContext> symbolContext = environment.findSymbolInScope(name);
        
        if (symbolContext.isEmpty()) {
            return Result.ok(false); // Symbol not yet defined, might be defined later
        }
        
        Optional<TypeEntry> typeEntry = environment.getTypeEntry(symbolContext.get().typeId());
        if (typeEntry.isEmpty()) {
            return Result.err(new ResolutionError.InvalidSymbol("Symbol has invalid type"));
        }
        
        metaData.setResolved(typeEntry.get().type(), typeEntry.get().id());
        metaData.setResolveContext(environment.getCurrentContext());
        
        return Result.ok(true);
    }
    
    // Stub implementations for other expression types
    // These will be implemented based on the specific needs
    
    private Result<Boolean, ResolutionError> resolveSExpr(ASTNode.Expression.SExpr sexpr) {
        // TODO: Implement S-expression resolution
        return Result.ok(false);
    }
    
    private Result<Boolean, ResolutionError> resolveBlock(ASTNode.Expression.BExpr bexpr) {
        // TODO: Implement block expression resolution
        return Result.ok(false);
    }
    
    private Result<Boolean, ResolutionError> resolveLambda(ASTNode.Expression.LExpr lexpr) {
        // TODO: Implement lambda expression resolution
        return Result.ok(false);
    }
    
    private Result<Boolean, ResolutionError> resolveOperation(ASTNode.Expression.OExpr oexpr) {
        // TODO: Implement operation expression resolution
        return Result.ok(false);
    }
    
    private Result<Boolean, ResolutionError> resolvePredicate(ASTNode.Expression.PExpr pexpr) {
        // TODO: Implement predicate expression resolution
        return Result.ok(false);
    }
    
    private Result<Boolean, ResolutionError> resolveMethodChain(ASTNode.Expression.MExpr mexpr) {
        // TODO: Implement method chain expression resolution
        return Result.ok(false);
    }
}