# 2-Stage Symbol Resolution Implementation Specification

## Executive Summary

This document provides a comprehensive implementation specification for a 2-stage symbol resolution system for a functional language compiler targeting the JVM. The design integrates with the existing `Compiler.Step` pipeline while supporting incremental compilation, multithreading, and circular dependency handling.

## 1. Architecture Overview

### 1.1 Current System Analysis

The existing architecture provides:
- **Environment**: Manages namespace tree with `applyCompilerStep(Step func)` for pipeline integration
- **Namespace**: Contains `SymbolTable` and `CompModule`, represents compilation units organized by directory structure  
- **Compiler.Step**: `Function<Unit, Result<Unit, CError>>` interface for transformation pipeline
- **SymbolTable**: Eclipse Collections-based `IntObjectHashMap<Map<String, Symbol>>` with scope-based lookups
- **Symbol**: Records with `NsScope` tracking and metadata

### 1.2 Design Principles

1. **Integration**: Seamless integration with existing `Compiler.Step` pipeline
2. **Modularity**: Clear separation between Stage 1 (declarations/imports) and Stage 2 (types/usage)
3. **Performance**: Support for incremental compilation and multithreading
4. **Robustness**: Comprehensive error handling and circular dependency resolution
5. **Maintainability**: Clean interfaces and extensible architecture

## 2. 2-Stage Resolution Design

### 2.1 Stage 1: Declaration Collection & Import Resolution

**Purpose**: Collect all symbol declarations and resolve import dependencies before type checking.

**Key Operations**:
1. **Declaration Collection**: Walk AST to find all `let`, function, and type declarations
2. **Import Resolution**: Resolve `->` namespace operators and build import dependency graph
3. **Scope Setup**: Initialize scope chains for each compilation unit
4. **Circular Dependency Detection**: Identify import cycles for later handling

**State Transitions**:
- `PARSED` → `PARTIALLY_RESOLVED`

#### 2.1.1 Declaration Collection Methodology

**Processing Algorithm**:
```
FOR each namespace in environment:
    CREATE SubEnvironment with namespace root scope
    FOR each compilation unit in namespace.compModule:
        FOR each root expression in unit:
            CALL walkAndCollectDeclarations(expression, subEnvironment)
            
walkAndCollectDeclarations(node, env):
    SWITCH node type:
        CASE Let statement:
            symbol = createSymbolFromLet(letStmt, env)
            INSERT symbol into current scope
            RECURSIVELY process assignment expression
            
        CASE Block expression:
            blockEnv = env.pushScope("block_" + blockId)
            FOR each expression in block:
                CALL walkAndCollectDeclarations(expression, blockEnv)
            RETURN to original environment (blockEnv discarded)
            
        CASE Lambda expression:
            lambdaEnv = env.pushScope("lambda_" + lambdaId)
            FOR each parameter:
                paramSymbol = createSymbolFromParameter(param, lambdaEnv)
                INSERT paramSymbol into lambda scope
            CALL walkAndCollectDeclarations(body, lambdaEnv)
            RETURN to original environment
            
        CASE Function call (:: operator):
            EXTRACT function identifier
            DEFER resolution to Stage 2 (usage verification)
            RECURSIVELY process arguments
            
        CASE Member access (:. operator):
            EXTRACT member identifier
            DEFER resolution to Stage 2 (usage verification)
            RECURSIVELY process base expression
            
        DEFAULT:
            RECURSIVELY process child nodes
```

**Symbol Creation Rules**:
- **Let Statements**: Create symbol with identifier, modifiers, and placeholder type
- **Parameters**: Create symbols in lambda/function scope with parameter types
- **Implicit Declarations**: Functions declared through lambda expressions
- **Scope Association**: Each symbol tagged with current scope ID and namespace

**Error Handling During Declaration Collection**:
- **Duplicate Symbols**: Same identifier declared multiple times in same scope
- **Invalid Modifiers**: Conflicting modifiers (e.g., CONST + MUTABLE)
- **Scope Violations**: Attempting to declare in inappropriate scope contexts

#### 2.1.2 Import Resolution Methodology

**Processing Algorithm**:
```
FOR each namespace in dependency-ordered list:
    CREATE ImportResolver for namespace
    FOR each compilation unit in namespace:
        FOR each root expression in unit:
            CALL findAndResolveImports(expression, importResolver)
            
findAndResolveImports(node, resolver):
    SWITCH node type:
        CASE Member access with -> operator:
            EXTRACT namespace qualifier from access chain
            ATTEMPT to resolve namespace path
            IF successful:
                REGISTER import dependency
                UPDATE dependency graph
            ELSE:
                RECORD unresolved import error
                
        CASE Explicit import statement (future):
            PARSE import path and optional alias
            RESOLVE namespace path through Environment
            REGISTER alias mapping
            UPDATE dependency graph
            
        DEFAULT:
            RECURSIVELY process child expressions
```

**Import Resolution Rules**:
1. **Namespace Path Resolution**: 
   - Split qualified names on "." separator  
   - Traverse namespace tree from root
   - Validate namespace exists and is accessible
   
2. **Dependency Graph Construction**:
   - Add edge from current namespace to imported namespace
   - Track direct and transitive dependencies
   - Maintain import order for deterministic resolution

3. **Alias Management**:
   - Map alias identifiers to full namespace paths
   - Validate alias doesn't conflict with local symbols
   - Support nested namespace aliases

#### 2.1.3 Circular Dependency Detection Methodology

**Detection Algorithm**:
```
detectCircularDependencies(dependencyGraph):
    FOR each namespace in graph:
        IF namespace.status == UNVISITED:
            path = []
            result = dfsDetectCycle(namespace, path, graph)
            IF result.isError():
                RECORD circular dependency
                
dfsDetectCycle(current, path, graph):
    IF current.status == VISITING:
        cycleStart = path.indexOf(current)
        cycle = path[cycleStart..end] + [current]
        RETURN CircularDependencyError(cycle)
        
    IF current.status == VISITED:
        RETURN success
        
    current.status = VISITING
    path.add(current)
    
    FOR each dependency of current:
        result = dfsDetectCycle(dependency, path, graph)
        IF result.isError():
            RETURN result
            
    path.removeLast()
    current.status = VISITED
    RETURN success
```

**Resolution Strategies for Circular Dependencies**:

1. **Forward Declaration Strategy**:
   ```
   FOR each cycle in detected cycles:
       SELECT cycle break point (usually smallest namespace)
       MARK symbols in break point as "forward declared"
       DEFER type resolution for forward declared symbols
       CONTINUE with normal resolution for other namespaces
       RESOLVE forward declared symbols in second pass
   ```

2. **Lazy Resolution Strategy**:
   ```
   FOR each cycle in detected cycles:
       CREATE lazy resolution thunks for cyclic references
       PROCEED with declaration collection
       DEFER actual symbol resolution until usage
       RESOLVE thunks on-demand during Stage 2
   ```

3. **Topological Sort Strategy**:
   ```
   FOR each cycle in detected cycles:
       REMOVE minimum set of edges to break cycle
       PERFORM topological sort on remaining graph
       PROCESS namespaces in sorted order
       HANDLE removed edges as special case imports
   ```

### 2.2 Stage 2: Type Resolution & Usage Verification

**Purpose**: Resolve types and verify all symbol usage is valid.

**Key Operations**:
1. **Type Resolution**: Resolve all `LangType` references using collected declarations
2. **Usage Verification**: Verify all identifier references resolve to valid symbols
3. **Modifier Checking**: Enforce visibility and mutability rules
4. **Cross-Module Reference Validation**: Verify symbols used across namespace boundaries

**State Transitions**:
- `PARTIALLY_RESOLVED` → `FULLY_RESOLVED`

#### 2.2.1 Type Resolution Methodology

**Processing Algorithm**:
```
FOR each namespace in dependency order:
    CREATE ResolutionContext with collected declarations
    FOR each compilation unit in namespace:
        FOR each root expression in unit:
            CALL walkAndResolveTypes(expression, context)
            
walkAndResolveTypes(node, context):
    SWITCH node type:
        CASE Value expression with identifier:
            symbol = lookupSymbol(identifier, context.environment)
            IF symbol found:
                RESOLVE type from symbol metadata
                CACHE resolution result
                VALIDATE type compatibility
            ELSE:
                RECORD undefined symbol error
                
        CASE Member access expression:
            baseResult = walkAndResolveTypes(baseExpr, context)
            IF baseResult.isOk():
                baseType = baseResult.type
                memberType = resolveMemberAccess(baseType, memberName)
                RETURN memberType
            ELSE:
                PROPAGATE error
                
        CASE Function call expression:
            funcResult = walkAndResolveTypes(funcExpr, context)
            argResults = [walkAndResolveTypes(arg, context) for arg in args]
            IF all results ok:
                VALIDATE function signature compatibility
                RETURN function return type
            ELSE:
                PROPAGATE first error
                
        CASE Operation expression:
            operandResults = [walkAndResolveTypes(op, context) for op in operands]
            IF all results ok:
                resultType = resolveOperationType(operation, operandTypes)
                RETURN resultType
            ELSE:
                PROPAGATE first error
                
        CASE Lambda expression:
            paramEnv = context.pushScope("lambda_types")
            FOR each parameter:
                RESOLVE parameter type from declaration
                ADD parameter to lambda environment
            bodyResult = walkAndResolveTypes(body, paramEnv)
            IF bodyResult.isOk():
                RETURN FunctionType(paramTypes, bodyResult.type)
            ELSE:
                PROPAGATE error
```

**Type Resolution Rules**:

1. **Primitive Type Resolution**:
   - Direct mapping from AST value nodes to `LangType.Primitive`
   - Numeric literal type inference (I32, I64, F32, F64)
   - Boolean and Nil literal handling

2. **Composite Type Resolution**:
   - Array types: `LangType.ofArray(elementType)`
   - Function types: `LangType.ofFunction(paramTypes, returnType)`
   - Tuple types: `LangType.ofTuple(memberTypes)`
   - User-defined types: Resolution through symbol table lookup

3. **Type Compatibility Checking**:
   ```
   checkTypeCompatibility(expected, actual):
       IF expected == actual:
           RETURN compatible
       IF expected is supertype of actual:
           RETURN compatible with implicit conversion
       IF explicit conversion exists:
           RETURN compatible with explicit conversion required
       ELSE:
           RETURN incompatible
   ```

#### 2.2.2 Usage Verification Methodology

**Processing Algorithm**:
```
FOR each namespace in dependency order:
    FOR each compilation unit in namespace:
        FOR each root expression in unit:
            CALL walkAndVerifyUsage(expression, context)
            
walkAndVerifyUsage(node, context):
    SWITCH node type:
        CASE Identifier reference:
            symbol = lookupSymbolInScope(identifier, context.currentScope)
            IF symbol not found:
                symbol = lookupSymbolInParentScopes(identifier, context)
                IF still not found:
                    RECORD undefined symbol error
                    SUGGEST similar identifiers
            ELSE:
                VERIFY symbol is accessible from current context
                CHECK mutability constraints if assignment target
                RECORD symbol usage for dependency tracking
                
        CASE Member access:
            baseSymbol = resolveBaseExpression(baseExpr, context)
            IF baseSymbol accessible:
                memberSymbol = resolveMemberAccess(baseSymbol, memberName)
                IF memberSymbol accessible:
                    CHECK visibility rules (PUBLIC/PRIVATE)
                    RECORD cross-namespace access if applicable
                ELSE:
                    RECORD undefined member error
            ELSE:
                PROPAGATE base resolution error
                
        CASE Assignment expression:
            targetSymbol = resolveAssignmentTarget(target, context)
            valueType = resolveValueExpression(value, context)
            IF targetSymbol.isMutable() or targetSymbol.isUninitialized():
                IF valueType compatible with targetSymbol.type:
                    MARK symbol as initialized
                ELSE:
                    RECORD type mismatch error
            ELSE:
                RECORD immutability violation error
```

**Usage Verification Rules**:

1. **Symbol Accessibility**:
   - Local symbols: Always accessible within declaring scope
   - Parent scope symbols: Accessible through scope chain traversal
   - Cross-namespace symbols: Must be PUBLIC and imported
   - Private symbols: Only accessible within same namespace

2. **Mutability Verification**:
   ```
   verifyMutability(symbol, operation, context):
       SWITCH operation:
           CASE read:
               RETURN always allowed
           CASE write:
               IF symbol.hasMODIFIER(MUTABLE):
                   RETURN allowed
               IF symbol.hasMODIFIER(CONST):
                   RETURN error("Cannot modify const symbol")
               IF symbol is parameter:
                   RETURN error("Cannot modify parameter")
               ELSE:
                   RETURN error("Symbol not declared mutable")
   ```

3. **Initialization Tracking**:
   - Track which symbols have been assigned values
   - Verify all symbols are initialized before use
   - Handle conditional initialization (if/else branches)
   - Detect potential uninitialized usage

#### 2.2.3 Cross-Module Reference Validation Methodology

**Processing Algorithm**:
```
FOR each namespace in environment:
    crossModuleRefs = extractCrossModuleReferences(namespace)
    FOR each reference in crossModuleRefs:
        sourceNamespace = reference.source
        targetNamespace = reference.target
        targetSymbol = reference.symbol
        
        IF not hasImportDependency(sourceNamespace, targetNamespace):
            RECORD missing import error
            
        IF not isSymbolPublic(targetSymbol):
            RECORD accessibility violation error
            
        IF hasCircularReference(sourceNamespace, targetNamespace):
            APPLY circular reference resolution strategy
            
        VALIDATE type compatibility across namespace boundary
        RECORD successful cross-module reference
```

**Cross-Module Validation Rules**:

1. **Import Dependency Verification**:
   - Source namespace must have import dependency on target namespace
   - Transitive imports allowed through dependency chain
   - Circular imports handled by resolution strategy

2. **Symbol Visibility Enforcement**:
   - Only PUBLIC symbols accessible across namespaces
   - PRIVATE symbols only accessible within declaring namespace
   - Package-level symbols accessible within same package tree

3. **Type Consistency Validation**:
   - Ensure type definitions are consistent across namespaces
   - Validate generic type parameter bindings
   - Check for conflicting type aliases

## 2.3 AST Traversal Algorithms and Decision Trees

### 2.3.1 Generic AST Traversal Framework

**Base Traversal Pattern**:
```
traverseAST(node, context, visitor):
    preVisit = visitor.preVisit(node, context)
    IF preVisit == SKIP:
        RETURN context
    IF preVisit == ABORT:
        RETURN error context
        
    newContext = preVisit.context || context
    
    SWITCH node type:
        CASE Expression nodes:
            childrenResults = []
            FOR each child in node.children():
                childResult = traverseAST(child, newContext, visitor)
                IF childResult.hasError() AND visitor.stopOnError():
                    RETURN childResult
                childrenResults.add(childResult)
                newContext = mergeContexts(newContext, childResult.context)
                
        CASE Statement nodes:
            childrenResults = []
            FOR each child in node.children():
                childResult = traverseAST(child, newContext, visitor)
                IF childResult.hasError() AND visitor.stopOnError():
                    RETURN childResult
                childrenResults.add(childResult)
                newContext = updateStatementContext(newContext, childResult.context)
    
    postVisit = visitor.postVisit(node, newContext, childrenResults)
    RETURN postVisit
```

### 2.3.2 Declaration Collection Traversal Decision Tree

**Decision Tree for AST Node Processing**:
```
processNode(node):
    ┌─ NODE_TYPE? ────────────────────────────────────────────┐
    │                                                         │
    ├─ Let Statement ──→ CREATE_SYMBOL ──→ INSERT_SCOPE ──────┤
    │                     ↓                                   │
    │                   PROCESS_ASSIGNMENT ──→ RECURSE       │
    │                                                         │
    ├─ Block Expression ──→ PUSH_SCOPE ──→ PROCESS_CHILDREN ─┤
    │                        ↓               ↓               │
    │                     GENERATE_ID    POP_SCOPE           │
    │                                                         │
    ├─ Lambda Expression ──→ PUSH_SCOPE ──→ PROCESS_PARAMS ──┤
    │                         ↓               ↓               │
    │                     GENERATE_ID    PROCESS_BODY        │
    │                                     ↓                   │
    │                                 POP_SCOPE               │
    │                                                         │
    ├─ Function Call (::) ──→ EXTRACT_IDENTIFIER ──→ DEFER ──┤
    │                          ↓                             │
    │                      PROCESS_ARGUMENTS                 │
    │                                                         │
    ├─ Member Access (:.) ──→ EXTRACT_MEMBER ──→ DEFER ──────┤
    │                         ↓                               │
    │                     PROCESS_BASE                       │
    │                                                         │
    ├─ Operation Expression ──→ EXTRACT_OPERATOR ──→ DEFER ──┤
    │                           ↓                             │
    │                       PROCESS_OPERANDS                 │
    │                                                         │
    ├─ Value Expression ──────→ CHECK_IDENTIFIER ──→ DEFER ──┤
    │                           ↓                             │
    │                       NO_PROCESSING                    │
    │                                                         │
    └─ Other Nodes ──────────→ RECURSE_CHILDREN ─────────────┘
```

**Detailed Processing Logic for Each Node Type**:

```java
// Declaration Collection Visitor Implementation
public class DeclarationCollectionVisitor implements ASTVisitor {
    
    public TraversalResult visitLetStatement(ASTNode.Stmt.Let letStmt, TraversalContext ctx) {
        // Decision: Create symbol and insert into current scope
        Symbol symbol = createSymbolFromLet(letStmt, ctx.environment);
        
        InsertResult insertResult = ctx.environment.insertSymbol(symbol);
        if (insertResult.isError()) {
            return TraversalResult.error(insertResult.error);
        }
        
        // Process assignment expression
        TraversalContext assignmentCtx = ctx.withNewSymbol(symbol);
        return TraversalResult.continueWith(assignmentCtx);
    }
    
    public TraversalResult visitBlockExpression(ASTNode.Expr.B blockExpr, TraversalContext ctx) {
        // Decision: Create new scope for block
        String scopeId = "block_" + ctx.generateId();
        SubEnvironment blockEnv = ctx.environment.pushScope(scopeId);
        TraversalContext blockCtx = ctx.withEnvironment(blockEnv);
        
        return TraversalResult.continueWith(blockCtx);
    }
    
    public TraversalResult visitLambdaExpression(ASTNode.Expr.L lambdaExpr, TraversalContext ctx) {
        // Decision: Create lambda scope and process parameters
        String lambdaId = "lambda_" + ctx.generateId();
        SubEnvironment lambdaEnv = ctx.environment.pushScope(lambdaId);
        
        // Process parameters first
        for (ASTNode.Parameter param : lambdaExpr.parameters()) {
            Symbol paramSymbol = createSymbolFromParameter(param, lambdaEnv);
            InsertResult result = lambdaEnv.insertSymbol(paramSymbol);
            if (result.isError()) {
                return TraversalResult.error(result.error);
            }
        }
        
        TraversalContext lambdaCtx = ctx.withEnvironment(lambdaEnv);
        return TraversalResult.continueWith(lambdaCtx);
    }
}
```

### 2.3.3 Type Resolution Traversal Decision Tree

**Decision Tree for Type Resolution**:
```
resolveTypes(node):
    ┌─ NODE_TYPE? ──────────────────────────────────────────┐
    │                                                       │
    ├─ Value with Identifier ──→ LOOKUP_SYMBOL ──→ RESOLVE ┤
    │                             ↓            ↗           │
    │                         CACHE_CHECK ────┘            │
    │                             ↓                         │
    │                         NOT_FOUND? ──→ ERROR         │
    │                                                       │
    ├─ Member Access ──→ RESOLVE_BASE ──→ RESOLVE_MEMBER ──┤
    │                    ↓                 ↓               │
    │                BASE_ERROR? ──→ PROPAGATE             │
    │                                                       │
    ├─ Function Call ──→ RESOLVE_FUNCTION ──→ CHECK_ARGS ──┤
    │                    ↓                   ↓             │
    │                VALIDATE_SIGNATURE ──→ RETURN_TYPE   │
    │                    ↓                                 │
    │                SIGNATURE_MISMATCH? ──→ ERROR        │
    │                                                       │
    ├─ Operation ──────→ RESOLVE_OPERANDS ──→ INFER_TYPE ──┤
    │                    ↓                   ↓             │
    │                OPERAND_ERROR? ──→ PROPAGATE         │
    │                                                       │
    ├─ Lambda ─────────→ CREATE_FUNCTION_TYPE ─────────────┤
    │                    ↓                                 │
    │                RESOLVE_BODY ──→ COMBINE_TYPES       │
    │                                                       │
    └─ Literal Values ──→ DIRECT_TYPE_MAPPING ─────────────┘
```

**Type Resolution Algorithm Implementation**:

```java
public class TypeResolutionVisitor implements ASTVisitor {
    
    public TraversalResult visitValueExpression(ASTNode.Expr.V valueExpr, TraversalContext ctx) {
        if (valueExpr.value() instanceof ASTNode.Value.Identifier identifier) {
            // Decision tree: Identifier → Lookup → Cache → Resolve
            String id = identifier.name();
            
            // Check cache first
            Optional<TypeInfo> cached = ctx.getTypeCache().get(id);
            if (cached.isPresent()) {
                return TraversalResult.withType(cached.get().type);
            }
            
            // Lookup symbol in environment
            LookupResult lookup = ctx.environment.lookupSymbol(id);
            if (lookup.isError()) {
                return TraversalResult.error(ResolutionError.undefinedSymbol(id, valueExpr.metaData().lineChar()));
            }
            
            if (lookup.symbol.isEmpty()) {
                return TraversalResult.error(ResolutionError.undefinedSymbol(id, valueExpr.metaData().lineChar()));
            }
            
            // Resolve type from symbol
            Symbol symbol = lookup.symbol.get();
            LangType resolvedType = resolveSymbolType(symbol, ctx);
            
            // Cache result
            ctx.getTypeCache().put(id, new TypeInfo(resolvedType, symbol));
            
            return TraversalResult.withType(resolvedType);
        }
        
        // Direct literal type mapping
        return TraversalResult.withType(mapLiteralToType(valueExpr.value()));
    }
    
    public TraversalResult visitMemberAccess(ASTNode.Expr.M accessExpr, TraversalContext ctx) {
        List<ASTNode.Access> chain = accessExpr.expressionChain();
        if (chain.isEmpty()) {
            return TraversalResult.error(ResolutionError.emptyAccessChain(accessExpr.metaData().lineChar()));
        }
        
        // Resolve base access
        ASTNode.Access base = chain.get(0);
        TypeResolutionResult baseResult = resolveAccessBase(base, ctx);
        if (baseResult.isError()) {
            return TraversalResult.error(baseResult.error);
        }
        
        // Chain resolution for remaining accesses
        LangType currentType = baseResult.type;
        for (int i = 1; i < chain.size(); i++) {
            ASTNode.Access access = chain.get(i);
            TypeResolutionResult chainResult = resolveMemberAccess(currentType, access, ctx);
            if (chainResult.isError()) {
                return TraversalResult.error(chainResult.error);
            }
            currentType = chainResult.type;
        }
        
        return TraversalResult.withType(currentType);
    }
    
    public TraversalResult visitFunctionCall(ASTNode.Access.FuncCall funcCall, TraversalContext ctx) {
        // Decision tree: Function → Lookup → Validate Signature → Return Type
        String funcId = funcCall.identifier();
        
        // Lookup function symbol
        LookupResult lookup = ctx.environment.lookupSymbol(funcId);
        if (lookup.isError() || lookup.symbol.isEmpty()) {
            return TraversalResult.error(ResolutionError.undefinedFunction(funcId, ctx.getCurrentLocation()));
        }
        
        Symbol funcSymbol = lookup.symbol.get();
        if (!funcSymbol.metaData() instanceof Symbol.Meta.Function) {
            return TraversalResult.error(ResolutionError.notAFunction(funcId, ctx.getCurrentLocation()));
        }
        
        // Resolve argument types
        List<LangType> argTypes = new ArrayList<>();
        for (ASTNode.Argument arg : funcCall.arguments()) {
            TraversalResult argResult = visitExpression(arg.expr(), ctx);
            if (argResult.isError()) {
                return argResult;
            }
            argTypes.add(argResult.type);
        }
        
        // Validate function signature
        LangType funcType = resolveSymbolType(funcSymbol, ctx);
        if (funcType instanceof LangType.Composite.Function funcTypeComposite) {
            SignatureValidationResult validation = validateFunctionSignature(funcTypeComposite, argTypes);
            if (validation.isError()) {
                return TraversalResult.error(validation.error);
            }
            
            return TraversalResult.withType(funcTypeComposite.rtnType());
        } else {
            return TraversalResult.error(ResolutionError.invalidFunctionType(funcId, ctx.getCurrentLocation()));
        }
    }
}
```

### 2.3.4 Usage Verification Traversal Decision Tree

**Decision Tree for Usage Verification**:
```
verifyUsage(node):
    ┌─ NODE_TYPE? ─────────────────────────────────────────┐
    │                                                      │
    ├─ Identifier Reference ──→ SCOPE_LOOKUP ──→ VERIFY ──┤
    │                           ↓             ↗           │
    │                       FOUND? ──────────┘            │
    │                           ↓                         │
    │                       PARENT_LOOKUP ──→ SUGGEST    │
    │                           ↓                         │
    │                       STILL_NOT_FOUND ──→ ERROR    │
    │                                                      │
    ├─ Member Access ──────→ RESOLVE_BASE ──→ CHECK_MEMBER┤
    │                        ↓              ↓             │
    │                    BASE_ERROR? ──→ PROPAGATE        │
    │                                   ↓                 │
    │                               VISIBILITY_CHECK      │
    │                                   ↓                 │
    │                               RECORD_ACCESS         │
    │                                                      │
    ├─ Assignment ─────────→ RESOLVE_TARGET ──→ CHECK_MUT ┤
    │                        ↓                ↓           │
    │                    RESOLVE_VALUE ──→ TYPE_COMPAT   │
    │                                     ↓               │
    │                                 MARK_INITIALIZED    │
    │                                                      │
    ├─ Function Call ─────→ VERIFY_CALLABLE ──────────────┤
    │                      ↓                              │
    │                  RECORD_DEPENDENCY                  │
    │                                                      │
    └─ Other Expressions ──→ RECURSE_VERIFY ──────────────┘
```

**Usage Verification Implementation**:

```java
public class UsageVerificationVisitor implements ASTVisitor {
    
    public TraversalResult visitIdentifierReference(String identifier, TraversalContext ctx) {
        // Decision tree: Identifier → Scope Lookup → Parent Lookup → Error/Suggest
        
        // Step 1: Current scope lookup
        LookupResult currentScopeResult = ctx.environment.lookupSymbolInCurrentScope(identifier);
        if (currentScopeResult.isSuccess() && currentScopeResult.symbol.isPresent()) {
            Symbol symbol = currentScopeResult.symbol.get();
            
            // Verify accessibility
            AccessibilityResult accessResult = verifySymbolAccessibility(symbol, ctx);
            if (accessResult.isError()) {
                return TraversalResult.error(accessResult.error);
            }
            
            // Record usage
            ctx.recordSymbolUsage(symbol, ctx.getCurrentLocation());
            return TraversalResult.success();
        }
        
        // Step 2: Parent scopes lookup
        LookupResult parentScopesResult = ctx.environment.lookupSymbolInParentScopes(identifier);
        if (parentScopesResult.isSuccess() && parentScopesResult.symbol.isPresent()) {
            Symbol symbol = parentScopesResult.symbol.get();
            
            // Verify cross-scope accessibility
            AccessibilityResult accessResult = verifyCrossScopeAccess(symbol, ctx);
            if (accessResult.isError()) {
                return TraversalResult.error(accessResult.error);
            }
            
            // Record usage
            ctx.recordSymbolUsage(symbol, ctx.getCurrentLocation());
            return TraversalResult.success();
        }
        
        // Step 3: Generate suggestions and error
        List<String> suggestions = generateSuggestions(identifier, ctx);
        ResolutionError error = ResolutionError.undefinedSymbolWithSuggestions(
            identifier, 
            ctx.getCurrentLocation(), 
            suggestions
        );
        
        return TraversalResult.error(error);
    }
    
    public TraversalResult visitAssignmentExpression(ASTNode.Stmt.Assign assignment, TraversalContext ctx) {
        // Decision tree: Assignment → Resolve Target → Check Mutability → Type Compatibility
        
        String target = assignment.target();
        
        // Step 1: Resolve assignment target
        LookupResult targetLookup = ctx.environment.lookupSymbol(target);
        if (targetLookup.isError() || targetLookup.symbol.isEmpty()) {
            return TraversalResult.error(ResolutionError.undefinedAssignmentTarget(target, assignment.metaData().lineChar()));
        }
        
        Symbol targetSymbol = targetLookup.symbol.get();
        
        // Step 2: Check mutability
        MutabilityResult mutabilityCheck = verifyMutability(targetSymbol, MutabilityOperation.WRITE, ctx);
        if (mutabilityCheck.isError()) {
            return TraversalResult.error(mutabilityCheck.error);
        }
        
        // Step 3: Resolve value expression type
        TraversalResult valueResult = visitExpression(assignment.assignment(), ctx);
        if (valueResult.isError()) {
            return valueResult;
        }
        
        // Step 4: Check type compatibility
        LangType targetType = resolveSymbolType(targetSymbol, ctx);
        LangType valueType = valueResult.type;
        
        CompatibilityResult compatibility = checkTypeCompatibility(targetType, valueType);
        if (compatibility.isError()) {
            return TraversalResult.error(ResolutionError.typeMismatch(
                targetType.toString(),
                valueType.toString(),
                assignment.metaData().lineChar()
            ));
        }
        
        // Step 5: Mark symbol as initialized
        ctx.markSymbolInitialized(targetSymbol);
        
        return TraversalResult.success();
    }
    
    private List<String> generateSuggestions(String identifier, TraversalContext ctx) {
        // Collect all visible symbols
        List<String> availableSymbols = ctx.environment.getAllVisibleSymbols()
            .stream()
            .map(Symbol::identifier)
            .toList();
        
        // Calculate edit distance and suggest closest matches
        return availableSymbols.stream()
            .filter(symbol -> editDistance(identifier, symbol) <= 2)
            .sorted((a, b) -> Integer.compare(editDistance(identifier, a), editDistance(identifier, b)))
            .limit(3)
            .toList();
    }
    
    private int editDistance(String s1, String s2) {
        // Levenshtein distance implementation for suggestions
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(
                        Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1),
                        dp[i-1][j-1] + (s1.charAt(i-1) == s2.charAt(j-1) ? 0 : 1)
                    );
                }
            }
        }
        
        return dp[s1.length()][s2.length()];
    }
}
```

### 2.3.5 Traversal Context and State Management

**Traversal Context Implementation**:

```java
public class TraversalContext {
    private final SubEnvironment environment;
    private final TypeCache typeCache;
    private final UsageTracker usageTracker;
    private final ErrorCollector errorCollector;
    private final Stack<ScopeInfo> scopeStack;
    private final Map<String, InitializationState> initializationMap;
    private int idCounter;
    
    public TraversalContext withEnvironment(SubEnvironment newEnv) {
        return new TraversalContext(
            newEnv, 
            this.typeCache, 
            this.usageTracker, 
            this.errorCollector,
            this.scopeStack,
            this.initializationMap,
            this.idCounter
        );
    }
    
    public TraversalContext withNewSymbol(Symbol symbol) {
        TraversalContext newCtx = new TraversalContext(this);
        newCtx.initializationMap.put(symbol.identifier(), InitializationState.DECLARED);
        return newCtx;
    }
    
    public String generateId() {
        return String.valueOf(++idCounter);
    }
    
    public void recordSymbolUsage(Symbol symbol, LineChar location) {
        usageTracker.recordUsage(symbol, location, getCurrentScope());
    }
    
    public void markSymbolInitialized(Symbol symbol) {
        initializationMap.put(symbol.identifier(), InitializationState.INITIALIZED);
    }
    
    public boolean isSymbolInitialized(String identifier) {
        InitializationState state = initializationMap.get(identifier);
        return state == InitializationState.INITIALIZED;
    }
    
    public ScopeInfo getCurrentScope() {
        return scopeStack.isEmpty() ? ScopeInfo.ROOT : scopeStack.peek();
    }
    
    public LineChar getCurrentLocation() {
        return getCurrentScope().currentLocation;
    }
    
    public enum InitializationState {
        DECLARED,     // Symbol declared but not assigned
        INITIALIZED,  // Symbol has been assigned a value
        CONDITIONALLY_INITIALIZED  // Symbol initialized in some branches
    }
    
    public record ScopeInfo(String name, int id, LineChar currentLocation, Set<String> declaredSymbols) {
        public static final ScopeInfo ROOT = new ScopeInfo("root", 0, LineChar.unknown(), Set.of());
    }
}

public class TraversalResult {
    private final boolean isError;
    private final ResolutionError error;
    private final LangType type;
    private final TraversalContext updatedContext;
    
    public static TraversalResult success() {
        return new TraversalResult(false, null, null, null);
    }
    
    public static TraversalResult withType(LangType type) {
        return new TraversalResult(false, null, type, null);
    }
    
    public static TraversalResult error(ResolutionError error) {
        return new TraversalResult(true, error, null, null);
    }
    
    public static TraversalResult continueWith(TraversalContext context) {
        return new TraversalResult(false, null, null, context);
    }
    
    // Getters and utility methods...
}
```

## 3. Core Implementation Components

### 3.1 SubEnvironment for Scope Tracking

The `SubEnvironment` provides scope chain management during compilation:

```java
public class SubEnvironment {
    private final Environment globalEnv;
    private final Namespace currentNamespace; 
    private final List<Integer> scopeStack;  // Use Java 21+ List methods
    private final Map<Integer, String> scopeNames; // For debugging
    
    public SubEnvironment(Environment globalEnv, Namespace currentNamespace) {
        this.globalEnv = globalEnv;
        this.currentNamespace = currentNamespace;
        this.scopeStack = new ArrayList<>();
        this.scopeNames = new HashMap<>();
        
        // Initialize with namespace root scope
        pushScope("namespace:" + currentNamespace.name());
    }
    
    public SubEnvironment pushScope(String scopeName) {
        int newScopeId = generateScopeId();
        List<Integer> newStack = new ArrayList<>(scopeStack);
        newStack.add(newScopeId);
        
        SubEnvironment newEnv = new SubEnvironment(globalEnv, currentNamespace);
        newEnv.scopeStack.addAll(newStack);
        newEnv.scopeNames.putAll(this.scopeNames);
        newEnv.scopeNames.put(newScopeId, scopeName);
        
        return newEnv;
    }
    
    public SubEnvironment popScope() {
        if (scopeStack.size() <= 1) {
            throw new IllegalStateException("Cannot pop namespace root scope");
        }
        
        List<Integer> newStack = scopeStack.subList(0, scopeStack.size() - 1);
        SubEnvironment newEnv = new SubEnvironment(globalEnv, currentNamespace);
        newEnv.scopeStack.addAll(newStack);
        newEnv.scopeNames.putAll(this.scopeNames);
        
        return newEnv;
    }
    
    public Result<Optional<Symbol>, ResolutionError> lookupSymbol(String identifier) {
        // First check local scopes using Eclipse Collections IntList
        IntList scopeIds = IntLists.mutable.of(scopeStack.toArray(Integer[]::new));
        var localResult = currentNamespace.symbolTable().lookup(scopeIds, identifier);
        
        if (localResult.isErr() || localResult.unwrap().isPresent()) {
            return localResult;
        }
        
        // Then check parent namespaces and imports
        return lookupInParentNamespaces(identifier);
    }
    
    public Result<Void, ResolutionError> insertSymbol(Symbol symbol) {
        int currentScope = scopeStack.get(scopeStack.size() - 1);
        return currentNamespace.symbolTable().insert(currentScope, symbol);
    }
    
    private Result<Optional<Symbol>, ResolutionError> lookupInParentNamespaces(String identifier) {
        // Implementation for cross-namespace symbol lookup
        // Uses globalEnv.lookupQualifier() for namespace traversal
        return Result.ok(Optional.empty()); // Placeholder
    }
    
    private int generateScopeId() {
        // Implementation for generating unique scope IDs
        return System.identityHashCode(this) + scopeStack.size();
    }
}
```

**Key Design Features**:
- **Immutable Operations**: Each scope push/pop creates new `SubEnvironment` instances
- **Efficient Lookups**: Uses Eclipse Collections `IntList` for scope chain lookups
- **Debug Support**: Maintains scope names for debugging and error reporting
- **Global Access**: Maintains reference to global environment for cross-namespace lookups

### 3.2 Resolution Context and State Management

```java
public class ResolutionContext {
    private final CompilationContext compilationCtx;
    private final DependencyGraph dependencyGraph;
    private final ImportResolver importResolver;
    private final Set<String> resolvedImports;
    private final Map<String, ResolutionCache> cache;
    
    public static ResolutionContext of(CompilationContext compilationCtx) {
        return new ResolutionContext(
            compilationCtx,
            new DependencyGraph(),
            new ImportResolver(compilationCtx.globalEnvironment()),
            new HashSet<>(),
            new HashMap<>()
        );
    }
    
    public CompilationContext compilationContext() { return compilationCtx; }
    public DependencyGraph dependencyGraph() { return dependencyGraph; }
    public ImportResolver importResolver() { return importResolver; }
    public Set<String> resolvedImports() { return resolvedImports; }
    
    public void cacheResolution(String key, Symbol symbol) {
        cache.computeIfAbsent(key, k -> new ResolutionCache()).addSymbol(symbol);
    }
    
    public Optional<Symbol> getCachedResolution(String key) {
        return Optional.ofNullable(cache.get(key))
                .flatMap(ResolutionCache::getSymbol);
    }
}

public class CompilationContext {
    private final Namespace targetNamespace;
    private final SubEnvironment subEnvironment;
    private final Environment globalEnvironment;
    
    public static CompilationContext of(Namespace namespace, SubEnvironment subEnv) {
        return new CompilationContext(namespace, subEnv, subEnv.globalEnvironment());
    }
    
    public Namespace targetNamespace() { return targetNamespace; }
    public SubEnvironment subEnvironment() { return subEnvironment; }
    public Environment globalEnvironment() { return globalEnvironment; }
}

private static class ResolutionCache {
    private final Map<String, Symbol> symbolCache = new HashMap<>();
    private final long timestamp = System.currentTimeMillis();
    
    public void addSymbol(Symbol symbol) { symbolCache.put(symbol.identifier(), symbol); }
    public Optional<Symbol> getSymbol(String identifier) { return Optional.ofNullable(symbolCache.get(identifier)); }
    public boolean isExpired(long maxAge) { return (System.currentTimeMillis() - timestamp) > maxAge; }
}
```

### 3.3 Enhanced Resolution Step Interface

```java
@FunctionalInterface
public interface ResolutionStep {
    Result<Void, CError> apply(ResolutionContext context);
}

// Bridge to existing Compiler.Step interface
public class ResolutionBridge {
    public static Compiler.Step wrapAsUnitStep(ResolutionStep resolutionStep) {
        return unit -> {
            // Individual units progress through resolution phases
            return switch (unit.state()) {
                case PARSED -> Result.ok(unit.asPartiallyResolved());
                case PARTIALLY_RESOLVED -> Result.ok(unit.asFullyResolved());
                default -> Result.ok(unit);
            };
        };
    }
    
    // Enhanced Environment method for resolution steps
    public static Result<Void, CError> applyResolutionStep(Environment env, ResolutionStep step) {
        List<Result<Void, CError>> results = new ArrayList<>();
        
        for (Namespace namespace : env.getAllNamespaces()) {
            SubEnvironment subEnv = new SubEnvironment(env, namespace);
            CompilationContext compilationCtx = CompilationContext.of(namespace, subEnv);
            ResolutionContext resolutionCtx = ResolutionContext.of(compilationCtx);
            
            results.add(step.apply(resolutionCtx));
        }
        
        return results.stream().allMatch(Result::isOk) 
            ? Result.okVoid()
            : results.stream().filter(Result::isErr).findFirst().get();
    }
}
```

### 3.4 Circular Dependency Handling

```java
public class DependencyGraph {
    private final Map<String, Set<String>> dependencies = new HashMap<>();
    private final Map<String, DependencyStatus> status = new HashMap<>();
    
    public enum DependencyStatus { UNVISITED, VISITING, VISITED }
    
    public void addDependency(String from, String to) {
        dependencies.computeIfAbsent(from, k -> new HashSet<>()).add(to);
        status.putIfAbsent(from, DependencyStatus.UNVISITED);
        status.putIfAbsent(to, DependencyStatus.UNVISITED);
    }
    
    public Result<List<String>, ResolutionError> detectCircularDependencies() {
        List<String> cycles = new ArrayList<>();
        
        for (String namespace : dependencies.keySet()) {
            if (status.get(namespace) == DependencyStatus.UNVISITED) {
                var result = dfsDetectCycle(namespace, new ArrayList<>());
                if (result.isErr()) {
                    cycles.add(result.unwrapErr().getMessage());
                }
            }
        }
        
        return cycles.isEmpty() ? Result.ok(List.of()) : Result.err(ResolutionError.circularDependency(cycles));
    }
    
    private Result<Void, ResolutionError> dfsDetectCycle(String current, List<String> path) {
        if (status.get(current) == DependencyStatus.VISITING) {
            // Found cycle
            int cycleStart = path.indexOf(current);
            List<String> cycle = path.subList(cycleStart, path.size());
            cycle.add(current); // Complete the cycle
            return Result.err(ResolutionError.circularDependency(cycle));
        }
        
        if (status.get(current) == DependencyStatus.VISITED) {
            return Result.okVoid();
        }
        
        status.put(current, DependencyStatus.VISITING);
        path.add(current);
        
        Set<String> deps = dependencies.getOrDefault(current, Set.of());
        for (String dep : deps) {
            var result = dfsDetectCycle(dep, path);
            if (result.isErr()) return result;
        }
        
        path.remove(path.size() - 1);
        status.put(current, DependencyStatus.VISITED);
        return Result.okVoid();
    }
    
    // Three strategies for handling circular dependencies:
    public enum CircularResolutionStrategy {
        FORWARD_DECLARATION,  // Allow forward references within cycles
        LAZY_RESOLUTION,      // Defer resolution of cyclic references  
        TOPOLOGICAL_SORT      // Break cycles by dependency ordering
    }
    
    public Result<Void, ResolutionError> resolveCircularDependencies(
            List<String> cycle, 
            CircularResolutionStrategy strategy) {
        
        return switch (strategy) {
            case FORWARD_DECLARATION -> handleForwardDeclarations(cycle);
            case LAZY_RESOLUTION -> handleLazyResolution(cycle);
            case TOPOLOGICAL_SORT -> handleTopologicalSort(cycle);
        };
    }
    
    private Result<Void, ResolutionError> handleForwardDeclarations(List<String> cycle) {
        // Implementation for forward declaration strategy
        return Result.okVoid(); // Placeholder
    }
    
    private Result<Void, ResolutionError> handleLazyResolution(List<String> cycle) {
        // Implementation for lazy resolution strategy
        return Result.okVoid(); // Placeholder
    }
    
    private Result<Void, ResolutionError> handleTopologicalSort(List<String> cycle) {
        // Implementation for topological sort strategy
        return Result.okVoid(); // Placeholder
    }
}
```

### 3.5 Import Resolution System

```java
public class ImportResolver {
    private final Environment environment;
    private final Map<String, String> aliasMap = new HashMap<>();
    private final Map<String, Namespace> resolvedNamespaces = new HashMap<>();
    
    public ImportResolver(Environment environment) {
        this.environment = environment;
    }
    
    public Result<Void, ResolutionError> resolveImport(String qualifiedName, Optional<String> alias) {
        // Handle namespace path like "com.example.module"
        Optional<Namespace> targetNamespace = environment.lookupQualifier(qualifiedName);
        
        if (targetNamespace.isEmpty()) {
            return Result.err(ResolutionError.unresolvedImport(qualifiedName));
        }
        
        // Cache resolved namespace
        resolvedNamespaces.put(qualifiedName, targetNamespace.get());
        
        // Register alias if provided
        alias.ifPresent(aliasName -> aliasMap.put(aliasName, qualifiedName));
        
        return Result.okVoid();
    }
    
    public Result<String, ResolutionError> resolveAlias(String possibleAlias) {
        return aliasMap.containsKey(possibleAlias) 
            ? Result.ok(aliasMap.get(possibleAlias))
            : Result.ok(possibleAlias); // Not an alias, return as-is
    }
    
    public Result<Optional<Namespace>, ResolutionError> getNamespaceForQualifier(String qualifier) {
        // First try direct lookup
        if (resolvedNamespaces.containsKey(qualifier)) {
            return Result.ok(Optional.of(resolvedNamespaces.get(qualifier)));
        }
        
        // Try alias resolution
        var aliasResult = resolveAlias(qualifier);
        if (aliasResult.isErr()) return aliasResult.castErr();
        
        String resolvedQualifier = aliasResult.unwrap();
        if (resolvedNamespaces.containsKey(resolvedQualifier)) {
            return Result.ok(Optional.of(resolvedNamespaces.get(resolvedQualifier)));
        }
        
        return Result.ok(Optional.empty());
    }
    
    public Set<String> getAllImports() {
        return new HashSet<>(resolvedNamespaces.keySet());
    }
    
    public Map<String, String> getAllAliases() {
        return new HashMap<>(aliasMap);
    }
}
```

## 4. Integration with Existing Pipeline

### 4.1 Enhanced SymbolTable Interface

Complete the existing `SymbolTable.lookup(IntList scopeIds, String identifier)` method:

```java
// Add to SymbolTable.MapTable class
@Override
public Result<Optional<Symbol>, ResolutionError> lookup(IntList scopeIds, String identifier) {
    // Search from most recent scope to oldest (LIFO order)
    for (int i = scopeIds.size() - 1; i >= 0; i--) {
        int scopeId = scopeIds.get(i);
        var result = lookup(scopeId, identifier);
        
        if (result.isErr()) return result;
        if (result.unwrap().isPresent()) return result;
    }
    
    return Result.ok(Optional.empty());
}

// Add method for bulk symbol insertion during declaration collection
public Result<Void, ResolutionError> insertBatch(int scopeId, List<Symbol> symbols) {
    var innerMap = table.getIfAbsentPut(scopeId, new HashMap<>());
    
    for (Symbol symbol : symbols) {
        var existing = innerMap.putIfAbsent(symbol.identifier(), symbol);
        if (existing != null) {
            return Result.err(ResolutionError.duplicateSymbol(existing));
        }
    }
    
    return Result.okVoid();
}

// Add method for scope cleanup during incremental compilation
public void clearScope(int scopeId) {
    table.remove(scopeId);
}
```

### 4.2 Enhanced Environment Methods

```java
// Add to Environment.java
public Result<Void, CError> applyResolutionStep(ResolutionStep step) {
    List<Result<Void, CError>> results = new ArrayList<>();
    
    for (Namespace namespace : allNamespaces) {
        SubEnvironment subEnv = new SubEnvironment(this, namespace);
        CompilationContext compilationCtx = CompilationContext.of(namespace, subEnv);
        ResolutionContext resolutionCtx = ResolutionContext.of(compilationCtx);
        
        results.add(step.apply(resolutionCtx));
    }
    
    return results.stream().allMatch(Result::isOk) 
        ? Result.okVoid()
        : results.stream().filter(Result::isErr).findFirst().get();
}

// Add method for dependency-ordered namespace processing
public Result<Void, CError> applyResolutionStepOrdered(ResolutionStep step, DependencyGraph depGraph) {
    // Get topologically sorted namespace order
    var sortResult = depGraph.topologicalSort();
    if (sortResult.isErr()) return sortResult.castErr();
    
    List<String> sortedNamespaces = sortResult.unwrap();
    List<Result<Void, CError>> results = new ArrayList<>();
    
    for (String nsName : sortedNamespaces) {
        Optional<Namespace> namespace = lookupQualifier(nsName);
        if (namespace.isEmpty()) continue;
        
        SubEnvironment subEnv = new SubEnvironment(this, namespace.get());
        CompilationContext compilationCtx = CompilationContext.of(namespace.get(), subEnv);
        ResolutionContext resolutionCtx = ResolutionContext.of(compilationCtx);
        
        results.add(step.apply(resolutionCtx));
    }
    
    return results.stream().allMatch(Result::isOk) 
        ? Result.okVoid()
        : results.stream().filter(Result::isErr).findFirst().get();
}

// Add accessor for all namespaces (needed by ResolutionBridge)
public List<Namespace> getAllNamespaces() {
    return new ArrayList<>(allNamespaces);
}
```

## 5. Resolution Pipeline Construction

### 5.1 Stage 1 Resolvers

```java
public class Stage1Resolver {
    
    public static Result<Void, CError> collectDeclarations(ResolutionContext context) {
        CompilationContext compCtx = context.compilationContext();
        Namespace namespace = compCtx.targetNamespace();
        SubEnvironment subEnv = compCtx.subEnvironment();
        
        List<Symbol> collectedSymbols = new ArrayList<>();
        
        // Process each compilation unit in the namespace
        for (Compiler.Unit unit : namespace.compModule().units()) {
            for (ASTNode rootExpr : unit.rootExpressions()) {
                var result = walkAndCollectDeclarations(rootExpr, subEnv, collectedSymbols);
                if (result.isErr()) return result;
            }
        }
        
        return Result.okVoid();
    }
    
    private static Result<Void, CError> walkAndCollectDeclarations(
            ASTNode node, 
            SubEnvironment env, 
            List<Symbol> collectedSymbols) {
        
        return switch (node) {
            case ASTNode.Stmt.Let letStmt -> {
                Symbol symbol = createSymbolFromLet(letStmt, env);
                var insertResult = env.insertSymbol(symbol);
                if (insertResult.isErr()) yield insertResult.castErr();
                collectedSymbols.add(symbol);
                yield Result.okVoid();
            }
            
            case ASTNode.Expr.B blockExpr -> {
                // Block creates new scope
                SubEnvironment blockEnv = env.pushScope("block");
                
                for (ASTNode expr : blockExpr.expressions()) {
                    var result = walkAndCollectDeclarations(expr, blockEnv, collectedSymbols);
                    if (result.isErr()) yield result;
                }
                
                yield Result.okVoid();
            }
            
            case ASTNode.Expr.L lambdaExpr -> {
                // Lambda creates new scope for parameters
                SubEnvironment lambdaEnv = env.pushScope("lambda");
                
                for (ASTNode.Parameter param : lambdaExpr.parameters()) {
                    Symbol paramSymbol = createSymbolFromParameter(param, lambdaEnv);
                    var insertResult = lambdaEnv.insertSymbol(paramSymbol);
                    if (insertResult.isErr()) yield insertResult.castErr();
                    collectedSymbols.add(paramSymbol);
                }
                
                var bodyResult = walkAndCollectDeclarations(lambdaExpr.body(), lambdaEnv, collectedSymbols);
                yield bodyResult.isOk() ? Result.okVoid() : bodyResult;
            }
            
            default -> {
                // Recursively process child expressions
                var childResult = processChildExpressions(node, env, collectedSymbols);
                yield childResult;
            }
        };
    }
    
    public static Result<Void, CError> resolveImports(ResolutionContext context) {
        CompilationContext compCtx = context.compilationContext();
        ImportResolver importResolver = context.importResolver();
        DependencyGraph depGraph = context.dependencyGraph();
        
        String currentNamespace = compCtx.targetNamespace().name();
        
        for (Compiler.Unit unit : compCtx.targetNamespace().compModule().units()) {
            for (ASTNode rootExpr : unit.rootExpressions()) {
                var result = findAndResolveImports(rootExpr, importResolver, depGraph, currentNamespace);
                if (result.isErr()) return result;
            }
        }
        
        return Result.okVoid();
    }
    
    private static Result<Void, CError> findAndResolveImports(
            ASTNode node, 
            ImportResolver resolver, 
            DependencyGraph depGraph,
            String currentNamespace) {
        
        return switch (node) {
            case ASTNode.Expr.M accessExpr -> {
                // Look for namespace access patterns (->)
                for (ASTNode.Access access : accessExpr.expressionChain()) {
                    if (access instanceof ASTNode.Access.Namespace nsAccess) {
                        var result = resolver.resolveImport(nsAccess.identifier(), Optional.empty());
                        if (result.isErr()) yield result.castErr();
                        
                        // Track dependency
                        depGraph.addDependency(currentNamespace, nsAccess.identifier());
                    }
                }
                yield Result.okVoid();
            }
            
            default -> {
                // Recursively process child nodes
                yield processChildImports(node, resolver, depGraph, currentNamespace);
            }
        };
    }
    
    public static Result<Void, CError> detectCircularDependencies(ResolutionContext context) {
        DependencyGraph depGraph = context.dependencyGraph();
        
        var cycleResult = depGraph.detectCircularDependencies();
        if (cycleResult.isErr()) {
            // Attempt to resolve circular dependencies using configured strategy
            var cycles = cycleResult.unwrapErr().getCycles();
            for (List<String> cycle : cycles) {
                var resolutionResult = depGraph.resolveCircularDependencies(
                    cycle, 
                    DependencyGraph.CircularResolutionStrategy.FORWARD_DECLARATION
                );
                if (resolutionResult.isErr()) return resolutionResult;
            }
        }
        
        return Result.okVoid();
    }
    
    // Helper methods for symbol creation
    private static Symbol createSymbolFromLet(ASTNode.Stmt.Let letStmt, SubEnvironment env) {
        // Implementation for creating Symbol from Let statement
        return Symbol.of(
            letStmt.identifier(),
            0, // typeId to be resolved in Stage 2
            Set.copyOf(letStmt.modifiers()),
            determineSymbolMeta(letStmt),
            letStmt.metaData().lineChar(),
            NsScope.of(env.currentNamespace().id(), env.getCurrentScopeId())
        );
    }
    
    private static Symbol createSymbolFromParameter(ASTNode.Parameter param, SubEnvironment env) {
        // Implementation for creating Symbol from Parameter
        return Symbol.of(
            param.identifier(),
            0, // typeId to be resolved in Stage 2
            Set.copyOf(param.modifiers()),
            Symbol.Meta.Field(), // Parameters are fields
            LineChar.unknown(), // Parameters don't have direct line chars
            NsScope.of(env.currentNamespace().id(), env.getCurrentScopeId())
        );
    }
}
```

### 5.2 Stage 2 Resolvers

```java
public class Stage2Resolver {
    
    public static Result<Void, CError> resolveTypes(ResolutionContext context) {
        CompilationContext compCtx = context.compilationContext();
        SubEnvironment subEnv = compCtx.subEnvironment();
        
        for (Compiler.Unit unit : compCtx.targetNamespace().compModule().units()) {
            for (ASTNode rootExpr : unit.rootExpressions()) {
                var result = walkAndResolveTypes(rootExpr, subEnv, context);
                if (result.isErr()) return result;
            }
        }
        
        return Result.okVoid();
    }
    
    private static Result<Void, CError> walkAndResolveTypes(
            ASTNode node, 
            SubEnvironment env, 
            ResolutionContext context) {
        
        return switch (node) {
            case ASTNode.Expr.V valueExpr -> {
                if (valueExpr.value() instanceof ASTNode.Value.Identifier identifier) {
                    // Check cache first
                    var cached = context.getCachedResolution(identifier.name());
                    if (cached.isPresent()) {
                        yield Result.okVoid();
                    }
                    
                    var lookupResult = env.lookupSymbol(identifier.name());
                    if (lookupResult.isErr()) yield lookupResult.castErr();
                    if (lookupResult.unwrap().isEmpty()) {
                        yield Result.err(ResolutionError.undefinedSymbol(
                            identifier.name(), 
                            valueExpr.metaData().lineChar()
                        ));
                    }
                    
                    // Cache successful resolution
                    Symbol symbol = lookupResult.unwrap().get();
                    context.cacheResolution(identifier.name(), symbol);
                }
                yield Result.okVoid();
            }
            
            case ASTNode.Expr.M accessExpr -> {
                yield resolveAccessChain(accessExpr.expressionChain(), env, context, accessExpr.metaData());
            }
            
            case ASTNode.Expr.O operationExpr -> {
                // Resolve operand types
                for (ASTNode.Expr operand : operationExpr.operands()) {
                    var result = walkAndResolveTypes(operand, env, context);
                    if (result.isErr()) yield result;
                }
                yield Result.okVoid();
            }
            
            case ASTNode.Expr.L lambdaExpr -> {
                // Lambda creates new scope
                SubEnvironment lambdaEnv = env.pushScope("lambda_type_resolution");
                var result = walkAndResolveTypes(lambdaExpr.body(), lambdaEnv, context);
                yield result;
            }
            
            default -> {
                yield processChildTypeResolution(node, env, context);
            }
        };
    }
    
    private static Result<Void, CError> resolveAccessChain(
            List<ASTNode.Access> accessChain, 
            SubEnvironment env, 
            ResolutionContext context,
            MetaData metaData) {
        
        if (accessChain.isEmpty()) return Result.okVoid();
        
        // First access determines the base type
        ASTNode.Access firstAccess = accessChain.get(0);
        
        return switch (firstAccess) {
            case ASTNode.Access.Identifier identifier -> {
                var lookupResult = env.lookupSymbol(identifier.identifier());
                if (lookupResult.isErr()) yield lookupResult.castErr();
                if (lookupResult.unwrap().isEmpty()) {
                    yield Result.err(ResolutionError.undefinedSymbol(
                        identifier.identifier(), 
                        metaData.lineChar()
                    ));
                }
                
                // Resolve chained accesses based on base symbol type
                Symbol baseSymbol = lookupResult.unwrap().get();
                yield resolveChainedAccesses(accessChain.subList(1, accessChain.size()), baseSymbol, context);
            }
            
            case ASTNode.Access.Namespace namespace -> {
                // Cross-namespace access
                var nsResult = context.importResolver().getNamespaceForQualifier(namespace.identifier());
                if (nsResult.isErr()) yield nsResult.castErr();
                if (nsResult.unwrap().isEmpty()) {
                    yield Result.err(ResolutionError.unresolvedImport(namespace.identifier()));
                }
                
                yield Result.okVoid(); // Namespace access resolved
            }
            
            case ASTNode.Access.FuncCall funcCall -> {
                // Function call resolution
                var lookupResult = env.lookupSymbol(funcCall.identifier());
                if (lookupResult.isErr()) yield lookupResult.castErr();
                if (lookupResult.unwrap().isEmpty()) {
                    yield Result.err(ResolutionError.undefinedSymbol(
                        funcCall.identifier(), 
                        metaData.lineChar()
                    ));
                }
                
                Symbol funcSymbol = lookupResult.unwrap().get();
                yield validateFunctionCall(funcCall, funcSymbol, context);
            }
        };
    }
    
    public static Result<Void, CError> verifyUsage(ResolutionContext context) {
        CompilationContext compCtx = context.compilationContext();
        SubEnvironment subEnv = compCtx.subEnvironment();
        
        for (Compiler.Unit unit : compCtx.targetNamespace().compModule().units()) {
            for (ASTNode rootExpr : unit.rootExpressions()) {
                var result = walkAndVerifyUsage(rootExpr, subEnv, context);
                if (result.isErr()) return result;
            }
        }
        
        return Result.okVoid();
    }
    
    private static Result<Void, CError> walkAndVerifyUsage(
            ASTNode node, 
            SubEnvironment env, 
            ResolutionContext context) {
        
        // Implementation for usage verification
        // Check that all symbol references are valid
        // Verify type compatibility
        // Check mutability constraints
        return Result.okVoid(); // Placeholder
    }
    
    public static Result<Void, CError> enforceAccessibility(ResolutionContext context) {
        CompilationContext compCtx = context.compilationContext();
        
        // Implementation for accessibility checking
        // Verify PUBLIC/PRIVATE modifiers are respected
        // Check cross-namespace access permissions
        return Result.okVoid(); // Placeholder
    }
}
```

### 5.3 Resolution Pipeline Factory

```java
public class ResolutionPipeline {
    
    public static List<ResolutionStep> createStage1Pipeline() {
        return List.of(
            Stage1Resolver::collectDeclarations,
            Stage1Resolver::resolveImports,
            Stage1Resolver::detectCircularDependencies
        );
    }
    
    public static List<ResolutionStep> createStage2Pipeline() {
        return List.of(
            Stage2Resolver::resolveTypes,
            Stage2Resolver::verifyUsage,
            Stage2Resolver::enforceAccessibility
        );
    }
    
    public static ResolutionStep createResolutionPipeline() {
        var stage1Steps = createStage1Pipeline();
        var stage2Steps = createStage2Pipeline();
        
        return context -> {
            // Execute Stage 1
            for (ResolutionStep step : stage1Steps) {
                var result = step.apply(context);
                if (result.isErr()) return result;
            }
            
            // Mark as partially resolved
            markNamespacePartiallyResolved(context.compilationContext().targetNamespace());
            
            // Execute Stage 2
            for (ResolutionStep step : stage2Steps) {
                var result = step.apply(context);
                if (result.isErr()) return result;
            }
            
            // Mark as fully resolved
            markNamespaceFullyResolved(context.compilationContext().targetNamespace());
            
            return Result.okVoid();
        };
    }
    
    public static Compiler.Step createIntegratedCompilerStep() {
        ResolutionStep resolutionPipeline = createResolutionPipeline();
        return ResolutionBridge.wrapAsUnitStep(resolutionPipeline);
    }
    
    private static void markNamespacePartiallyResolved(Namespace namespace) {
        // Implementation for marking namespace resolution state
        // This would involve updating the CompModule state
    }
    
    private static void markNamespaceFullyResolved(Namespace namespace) {
        // Implementation for marking namespace resolution state
        // This would involve updating the CompModule state
    }
}
```

## 2.4 Error Handling Workflows and Recovery Procedures

### 2.4.1 Error Classification and Priority System

**Error Severity Levels**:
```
CRITICAL    - Prevents compilation from proceeding (circular dependencies, missing imports)
HIGH        - Prevents unit resolution (undefined symbols, type mismatches)  
MEDIUM      - Prevents optimization (accessibility violations, unused symbols)
LOW         - Style and convention warnings (naming conventions, code style)
```

**Error Handling Decision Tree**:
```
handleError(error, context):
    ┌─ ERROR_SEVERITY? ─────────────────────────────────────────┐
    │                                                            │
    ├─ CRITICAL ──→ ABORT_RESOLUTION ──→ REPORT_AND_EXIT ───────┤
    │               ↓                                            │
    │           LOG_ERROR_DETAILS                                │
    │                                                            │
    ├─ HIGH ──────→ COLLECT_ERROR ──→ CONTINUE_IF_POSSIBLE ─────┤
    │               ↓                ↓                          │
    │           INCREMENT_COUNT   MAX_ERRORS_REACHED? ──→ ABORT │
    │                                                            │
    ├─ MEDIUM ────→ COLLECT_WARNING ──→ CONTINUE ───────────────┤
    │               ↓                                            │
    │           LOG_WARNING                                      │
    │                                                            │
    └─ LOW ───────→ COLLECT_INFO ──→ CONTINUE ──────────────────┘
                    ↓
                LOG_INFO
```

### 2.4.2 Stage-Specific Error Recovery Strategies

#### Stage 1 Error Recovery

**Declaration Collection Recovery**:
```
handleDeclarationError(error, context):
    SWITCH error.type:
        CASE DuplicateSymbol:
            // Strategy: Keep first declaration, warn about duplicate
            existingSymbol = context.getExistingSymbol(error.symbol.identifier)
            LOG_WARNING("Duplicate symbol, keeping first declaration", error)
            CONTINUE with existing symbol
            
        CASE InvalidModifier:
            // Strategy: Remove invalid modifier, continue with valid ones
            validModifiers = error.modifiers.filter(isValid)
            correctedSymbol = createSymbol(error.identifier, validModifiers)
            LOG_WARNING("Invalid modifiers removed", error)
            CONTINUE with corrected symbol
            
        CASE ScopeViolation:
            // Strategy: Move declaration to valid scope
            validScope = findValidScopeForDeclaration(error.symbol, context)
            IF validScope exists:
                moveSymbolToScope(error.symbol, validScope)
                LOG_WARNING("Symbol moved to valid scope", error)
                CONTINUE
            ELSE:
                LOG_ERROR("Cannot find valid scope", error)
                COLLECT_ERROR(error)
```

**Import Resolution Recovery**:
```
handleImportError(error, context):
    SWITCH error.type:
        CASE UnresolvedImport:
            // Strategy: Try alternative resolution paths
            alternatives = findSimilarNamespaces(error.importPath, context)
            IF alternatives.isNotEmpty():
                suggestedPath = alternatives.first()
                LOG_ERROR("Import not found, did you mean: " + suggestedPath, error)
                
                // Optional: Auto-correct if confidence is high
                IF confidence(error.importPath, suggestedPath) > 0.8:
                    correctedImport = resolveImport(suggestedPath)
                    LOG_WARNING("Auto-corrected import path", error)
                    RETURN correctedImport
            
            // Create placeholder namespace for partial compilation
            placeholderNS = createPlaceholderNamespace(error.importPath)
            LOG_ERROR("Created placeholder for unresolved import", error)
            COLLECT_ERROR(error)
            CONTINUE with placeholder
            
        CASE CircularDependency:
            // Strategy: Apply resolution strategy based on cycle characteristics
            cycle = error.cycle
            strategy = selectCircularResolutionStrategy(cycle)
            
            SWITCH strategy:
                CASE FORWARD_DECLARATION:
                    breakPoint = selectBreakPoint(cycle)
                    markForwardDeclarations(breakPoint, cycle)
                    LOG_WARNING("Applied forward declarations to break cycle", error)
                    
                CASE LAZY_RESOLUTION:
                    createLazyThunks(cycle)
                    LOG_WARNING("Created lazy resolution thunks", error)
                    
                CASE TOPOLOGICAL_SORT:
                    sortedOrder = performTopologicalSort(cycle)
                    LOG_WARNING("Applied topological sort to resolve cycle", error)
```

#### Stage 2 Error Recovery

**Type Resolution Recovery**:
```
handleTypeError(error, context):
    SWITCH error.type:
        CASE UndefinedSymbol:
            // Strategy: Progressive lookup with suggestions
            suggestions = generateSuggestions(error.symbol, context)
            
            // Try fuzzy matching first
            fuzzyMatch = findFuzzyMatch(error.symbol, context, threshold=0.7)
            IF fuzzyMatch.isPresent():
                LOG_ERROR("Symbol not found, did you mean: " + fuzzyMatch.get(), error)
                
                // In interactive mode, could prompt for confirmation
                IF isInteractiveMode():
                    confirmation = promptUser("Use suggested symbol?", fuzzyMatch.get())
                    IF confirmation:
                        RETURN resolveSymbol(fuzzyMatch.get())
            
            // Create placeholder symbol for partial compilation
            placeholderType = inferPlaceholderType(error.context)
            placeholderSymbol = createPlaceholderSymbol(error.symbol, placeholderType)
            LOG_ERROR("Created placeholder symbol", error)
            COLLECT_ERROR(error)
            CONTINUE with placeholder
            
        CASE TypeMismatch:
            // Strategy: Try implicit conversions and coercions
            expectedType = error.expected
            actualType = error.actual
            
            // Check for implicit conversions
            conversion = findImplicitConversion(actualType, expectedType)
            IF conversion.isPresent():
                LOG_WARNING("Applied implicit conversion", error)
                applyConversion(conversion.get())
                CONTINUE
            
            // Check for safe coercions (numeric widening, etc.)
            coercion = findSafeCoercion(actualType, expectedType)
            IF coercion.isPresent():
                LOG_WARNING("Applied safe type coercion", error)
                applyCoercion(coercion.get())
                CONTINUE
            
            // Try generic type inference
            IF bothTypesAreGeneric(expectedType, actualType):
                unifiedType = attemptTypeUnification(expectedType, actualType)
                IF unifiedType.isPresent():
                    LOG_WARNING("Unified generic types", error)
                    applyUnifiedType(unifiedType.get())
                    CONTINUE
            
            // Last resort: use expected type and continue
            LOG_ERROR("Type mismatch, using expected type", error)
            forceType(error.expression, expectedType)
            COLLECT_ERROR(error)
            CONTINUE
```

### 2.4.3 Error Recovery Context Management

**Recovery Context Implementation**:
```java
public class ErrorRecoveryContext {
    private final Map<ErrorType, RecoveryStrategy> strategies;
    private final ErrorCollector errorCollector;
    private final RecoveryStatistics stats;
    private final Set<Symbol> placeholderSymbols;
    private final Map<String, List<String>> suggestionCache;
    
    public RecoveryResult attemptRecovery(ResolutionError error, ResolutionContext context) {
        RecoveryStrategy strategy = strategies.get(error.getClass());
        if (strategy == null) {
            return RecoveryResult.noRecovery(error);
        }
        
        try {
            RecoveryResult result = strategy.recover(error, context, this);
            stats.recordRecovery(error.getClass(), result.wasSuccessful());
            
            if (result.wasSuccessful()) {
                logSuccessfulRecovery(error, result);
            } else {
                logFailedRecovery(error, result);
            }
            
            return result;
            
        } catch (Exception recoveryException) {
            logRecoveryException(error, recoveryException);
            return RecoveryResult.recoveryFailed(error, recoveryException);
        }
    }
    
    public void createPlaceholderSymbol(String identifier, LangType inferredType, ResolutionContext context) {
        Symbol placeholder = Symbol.of(
            identifier,
            0, // typeId for placeholder
            Set.of(ASTNode.Modifier.PLACEHOLDER), // Custom modifier for placeholders
            Symbol.Meta.Field(), // Default to field
            LineChar.unknown(),
            context.getCurrentNamespaceScope()
        );
        
        placeholderSymbols.add(placeholder);
        context.environment().insertSymbol(placeholder);
        
        // Track for later resolution
        context.addDeferredResolution(placeholder, inferredType);
    }
    
    public List<String> getSuggestions(String identifier, ResolutionContext context) {
        return suggestionCache.computeIfAbsent(identifier, id -> {
            List<String> suggestions = new ArrayList<>();
            
            // Collect from current scope
            suggestions.addAll(context.environment().getAllVisibleSymbols()
                .stream()
                .map(Symbol::identifier)
                .filter(sym -> editDistance(id, sym) <= 2)
                .sorted((a, b) -> Integer.compare(editDistance(id, a), editDistance(id, b)))
                .limit(3)
                .toList());
            
            // Collect from imported namespaces
            suggestions.addAll(context.importResolver().getAllImports()
                .stream()
                .flatMap(nsName -> getExportedSymbols(nsName).stream())
                .map(Symbol::identifier)
                .filter(sym -> editDistance(id, sym) <= 2)
                .sorted((a, b) -> Integer.compare(editDistance(id, a), editDistance(id, b)))
                .limit(2)
                .toList());
            
            return suggestions;
        });
    }
    
    private int editDistance(String s1, String s2) {
        // Levenshtein distance implementation
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(
                        Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1),
                        dp[i-1][j-1] + (s1.charAt(i-1) == s2.charAt(j-1) ? 0 : 1)
                    );
                }
            }
        }
        
        return dp[s1.length()][s2.length()];
    }
}

public class RecoveryResult {
    private final boolean wasSuccessful;
    private final ResolutionError originalError;
    private final Optional<Object> recoveredValue;
    private final List<ResolutionError> additionalErrors;
    private final String recoveryMethod;
    
    public static RecoveryResult success(ResolutionError error, Object recoveredValue, String method) {
        return new RecoveryResult(true, error, Optional.of(recoveredValue), List.of(), method);
    }
    
    public static RecoveryResult partialSuccess(ResolutionError error, Object recoveredValue, 
                                                List<ResolutionError> additionalErrors, String method) {
        return new RecoveryResult(true, error, Optional.of(recoveredValue), additionalErrors, method);
    }
    
    public static RecoveryResult noRecovery(ResolutionError error) {
        return new RecoveryResult(false, error, Optional.empty(), List.of(), "no_strategy");
    }
    
    public static RecoveryResult recoveryFailed(ResolutionError error, Exception cause) {
        ResolutionError recoveryError = ResolutionError.recoveryFailed(error, cause.getMessage());
        return new RecoveryResult(false, error, Optional.empty(), List.of(recoveryError), "recovery_exception");
    }
}
```

### 2.4.4 Comprehensive Error Reporting

**Error Report Generation**:
```java
public class ErrorReportGenerator {
    
    public ErrorReport generateComprehensiveReport(List<ResolutionError> errors, ResolutionContext context) {
        Map<ErrorCategory, List<ResolutionError>> categorizedErrors = categorizeErrors(errors);
        
        ErrorReport report = new ErrorReport();
        
        // Generate summary
        report.setSummary(generateSummary(categorizedErrors));
        
        // Generate detailed sections for each category
        for (Map.Entry<ErrorCategory, List<ResolutionError>> entry : categorizedErrors.entrySet()) {
            ErrorCategory category = entry.getKey();
            List<ResolutionError> categoryErrors = entry.getValue();
            
            ErrorSection section = generateSection(category, categoryErrors, context);
            report.addSection(section);
        }
        
        // Generate suggestions and fixes
        report.setSuggestions(generateGlobalSuggestions(errors, context));
        
        // Generate recovery recommendations
        report.setRecoveryPlan(generateRecoveryPlan(errors, context));
        
        return report;
    }
    
    private ErrorSection generateSection(ErrorCategory category, List<ResolutionError> errors, ResolutionContext context) {
        ErrorSection section = new ErrorSection(category);
        
        // Group related errors
        Map<String, List<ResolutionError>> groupedErrors = groupRelatedErrors(errors);
        
        for (Map.Entry<String, List<ResolutionError>> group : groupedErrors.entrySet()) {
            String groupName = group.getKey();
            List<ResolutionError> groupErrors = group.getValue();
            
            ErrorGroup errorGroup = new ErrorGroup(groupName);
            
            for (ResolutionError error : groupErrors) {
                ErrorDetail detail = createErrorDetail(error, context);
                errorGroup.addError(detail);
            }
            
            section.addGroup(errorGroup);
        }
        
        return section;
    }
    
    private ErrorDetail createErrorDetail(ResolutionError error, ResolutionContext context) {
        ErrorDetail detail = new ErrorDetail();
        
        detail.setError(error);
        detail.setLocation(error.getLocation());
        detail.setMessage(error.getMessage());
        detail.setSourceContext(extractSourceContext(error.getLocation(), context));
        detail.setSuggestions(generateSpecificSuggestions(error, context));
        detail.setRelatedErrors(findRelatedErrors(error, context));
        
        // Add fix suggestions if available
        List<FixSuggestion> fixes = generateFixSuggestions(error, context);
        detail.setFixSuggestions(fixes);
        
        return detail;
    }
    
    private List<FixSuggestion> generateFixSuggestions(ResolutionError error, ResolutionContext context) {
        List<FixSuggestion> suggestions = new ArrayList<>();
        
        switch (error) {
            case ResolutionError.UndefinedSymbol undefinedError -> {
                // Suggest similar symbols
                List<String> similar = context.recoveryContext().getSuggestions(undefinedError.symbol(), context);
                for (String suggestion : similar) {
                    suggestions.add(new FixSuggestion(
                        "Replace with similar symbol",
                        String.format("Did you mean '%s'?", suggestion),
                        FixType.SYMBOL_REPLACEMENT,
                        Map.of("original", undefinedError.symbol(), "replacement", suggestion)
                    ));
                }
                
                // Suggest adding import if symbol exists in other namespaces
                List<String> availableNamespaces = findNamespacesContaining(undefinedError.symbol(), context);
                for (String namespace : availableNamespaces) {
                    suggestions.add(new FixSuggestion(
                        "Add import statement",
                        String.format("Import from namespace '%s'", namespace),
                        FixType.ADD_IMPORT,
                        Map.of("namespace", namespace, "symbol", undefinedError.symbol())
                    ));
                }
                
                // Suggest declaring the symbol
                suggestions.add(new FixSuggestion(
                    "Declare symbol",
                    String.format("Declare '%s' in current scope", undefinedError.symbol()),
                    FixType.DECLARE_SYMBOL,
                    Map.of("symbol", undefinedError.symbol(), "location", undefinedError.location())
                ));
            }
            
            case ResolutionError.TypeMismatch typeMismatchError -> {
                // Suggest type conversions
                List<TypeConversion> conversions = findAvailableConversions(
                    typeMismatchError.actual(), 
                    typeMismatchError.expected()
                );
                
                for (TypeConversion conversion : conversions) {
                    suggestions.add(new FixSuggestion(
                        "Apply type conversion",
                        String.format("Convert %s to %s using %s", 
                            typeMismatchError.actual(), 
                            typeMismatchError.expected(),
                            conversion.method()),
                        FixType.TYPE_CONVERSION,
                        Map.of("conversion", conversion)
                    ));
                }
            }
            
            case ResolutionError.CircularDependency circularError -> {
                suggestions.add(new FixSuggestion(
                    "Break circular dependency",
                    "Consider using forward declarations or restructuring dependencies",
                    FixType.RESTRUCTURE_DEPENDENCIES,
                    Map.of("cycle", circularError.cycle())
                ));
            }
        }
        
        return suggestions;
    }
    
    public enum FixType {
        SYMBOL_REPLACEMENT,
        ADD_IMPORT,
        DECLARE_SYMBOL,
        TYPE_CONVERSION,
        RESTRUCTURE_DEPENDENCIES,
        MODIFY_ACCESSIBILITY,
        ADD_MODIFIER
    }
    
    public record FixSuggestion(
        String title,
        String description,
        FixType type,
        Map<String, Object> parameters
    ) {}
}
```

### 2.4.5 Interactive Error Resolution

**Interactive Resolution Framework**:
```java
public class InteractiveErrorResolver {
    private final Scanner inputScanner;
    private final ErrorRecoveryContext recoveryContext;
    
    public InteractiveResolutionResult resolveInteractively(
            List<ResolutionError> errors, 
            ResolutionContext context) {
        
        InteractiveResolutionResult result = new InteractiveResolutionResult();
        
        System.out.println(String.format("Found %d resolution errors. Enter interactive resolution mode.", errors.size()));
        
        for (ResolutionError error : errors) {
            InteractiveErrorResult errorResult = resolveErrorInteractively(error, context);
            result.addErrorResult(errorResult);
            
            if (errorResult.action == InteractiveAction.ABORT) {
                System.out.println("Resolution aborted by user.");
                break;
            }
        }
        
        return result;
    }
    
    private InteractiveErrorResult resolveErrorInteractively(ResolutionError error, ResolutionContext context) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("ERROR: " + error.getMessage());
        System.out.println("Location: " + error.getLocation());
        
        // Show source context
        String sourceContext = extractSourceContext(error.getLocation(), context);
        System.out.println("\nSource context:");
        System.out.println(sourceContext);
        
        // Generate and display fix suggestions
        List<FixSuggestion> suggestions = generateFixSuggestions(error, context);
        if (!suggestions.isEmpty()) {
            System.out.println("\nSuggested fixes:");
            for (int i = 0; i < suggestions.size(); i++) {
                FixSuggestion suggestion = suggestions.get(i);
                System.out.println(String.format("  %d. %s", i + 1, suggestion.title()));
                System.out.println(String.format("     %s", suggestion.description()));
            }
        }
        
        // Present options to user
        System.out.println("\nOptions:");
        System.out.println("  s) Skip this error");
        System.out.println("  i) Ignore all errors of this type");
        System.out.println("  a) Abort resolution");
        if (!suggestions.isEmpty()) {
            System.out.println("  1-" + suggestions.size() + ") Apply suggested fix");
        }
        System.out.println("  c) Create custom fix");
        
        System.out.print("\nYour choice: ");
        String choice = inputScanner.nextLine().trim().toLowerCase();
        
        return processUserChoice(choice, error, suggestions, context);
    }
    
    private InteractiveErrorResult processUserChoice(
            String choice, 
            ResolutionError error, 
            List<FixSuggestion> suggestions,
            ResolutionContext context) {
        
        switch (choice) {
            case "s":
                return new InteractiveErrorResult(error, InteractiveAction.SKIP, null);
                
            case "i":
                return new InteractiveErrorResult(error, InteractiveAction.IGNORE_TYPE, null);
                
            case "a":
                return new InteractiveErrorResult(error, InteractiveAction.ABORT, null);
                
            case "c":
                return handleCustomFix(error, context);
                
            default:
                // Try to parse as suggestion number
                try {
                    int suggestionIndex = Integer.parseInt(choice) - 1;
                    if (suggestionIndex >= 0 && suggestionIndex < suggestions.size()) {
                        FixSuggestion selectedFix = suggestions.get(suggestionIndex);
                        AppliedFix appliedFix = applyFix(selectedFix, error, context);
                        return new InteractiveErrorResult(error, InteractiveAction.APPLY_FIX, appliedFix);
                    }
                } catch (NumberFormatException ignored) {}
                
                System.out.println("Invalid choice. Skipping error.");
                return new InteractiveErrorResult(error, InteractiveAction.SKIP, null);
        }
    }
    
    private AppliedFix applyFix(FixSuggestion suggestion, ResolutionError error, ResolutionContext context) {
        switch (suggestion.type()) {
            case SYMBOL_REPLACEMENT:
                String original = (String) suggestion.parameters().get("original");
                String replacement = (String) suggestion.parameters().get("replacement");
                return applySymbolReplacement(original, replacement, error, context);
                
            case ADD_IMPORT:
                String namespace = (String) suggestion.parameters().get("namespace");
                return applyAddImport(namespace, error, context);
                
            case DECLARE_SYMBOL:
                String symbol = (String) suggestion.parameters().get("symbol");
                return applyDeclareSymbol(symbol, error, context);
                
            default:
                System.out.println("Fix type not yet implemented: " + suggestion.type());
                return AppliedFix.failed("Not implemented");
        }
    }
    
    public enum InteractiveAction {
        SKIP,
        IGNORE_TYPE,
        ABORT,
        APPLY_FIX
    }
    
    public record InteractiveErrorResult(
        ResolutionError error,
        InteractiveAction action,
        AppliedFix appliedFix
    ) {}
    
    public record AppliedFix(
        boolean successful,
        String description,
        Map<String, Object> changes
    ) {
        public static AppliedFix success(String description, Map<String, Object> changes) {
            return new AppliedFix(true, description, changes);
        }
        
        public static AppliedFix failed(String reason) {
            return new AppliedFix(false, reason, Map.of());
        }
    }
}
```

This completes the comprehensive error handling section with detailed workflows, recovery procedures, and interactive resolution capabilities.

## 6. Comprehensive Error Handling

### 6.1 Resolution Error Types

```java
public sealed interface ResolutionError extends CError {
    
    // Symbol-related errors
    record UndefinedSymbol(String symbol, LineChar location) implements ResolutionError {
        @Override
        public String getMessage() {
            return String.format("Undefined symbol '%s' at %s", symbol, location);
        }
    }
    
    record DuplicateSymbol(Symbol existing, Symbol duplicate) implements ResolutionError {
        @Override
        public String getMessage() {
            return String.format("Duplicate symbol '%s' - already defined at %s", 
                duplicate.identifier(), existing.lineChar());
        }
    }
    
    // Dependency-related errors
    record CircularDependency(List<String> cycle) implements ResolutionError {
        @Override
        public String getMessage() {
            return String.format("Circular dependency detected: %s", String.join(" -> ", cycle));
        }
        
        public List<List<String>> getCycles() {
            // Parse multiple cycles from the error message
            return List.of(cycle); // Simplified
        }
    }
    
    record UnresolvedImport(String importPath) implements ResolutionError {
        @Override
        public String getMessage() {
            return String.format("Cannot resolve import: %s", importPath);
        }
    }
    
    // Type-related errors
    record TypeMismatch(String expected, String actual, LineChar location) implements ResolutionError {
        @Override
        public String getMessage() {
            return String.format("Type mismatch at %s: expected %s, got %s", location, expected, actual);
        }
    }
    
    // Access-related errors
    record AccessibilityViolation(Symbol symbol, String violationType) implements ResolutionError {
        @Override
        public String getMessage() {
            return String.format("Accessibility violation: %s for symbol '%s' at %s", 
                violationType, symbol.identifier(), symbol.lineChar());
        }
    }
    
    record ScopeViolation(String symbol, String scopeName, LineChar location) implements ResolutionError {
        @Override
        public String getMessage() {
            return String.format("Symbol '%s' not accessible in scope '%s' at %s", symbol, scopeName, location);
        }
    }
    
    // Factory methods
    static ResolutionError undefinedSymbol(String symbol, LineChar location) {
        return new UndefinedSymbol(symbol, location);
    }
    
    static ResolutionError duplicateSymbol(Symbol existing) {
        return new DuplicateSymbol(existing, existing); // Placeholder for duplicate
    }
    
    static ResolutionError circularDependency(List<String> cycle) {
        return new CircularDependency(cycle);
    }
    
    static ResolutionError unresolvedImport(String importPath) {
        return new UnresolvedImport(importPath);
    }
}
```

### 6.2 Error Recovery Strategies

```java
public class ResolutionErrorRecovery {
    
    public static class ErrorCollector {
        private final List<ResolutionError> errors = new ArrayList<>();
        private final int maxErrors;
        
        public ErrorCollector(int maxErrors) {
            this.maxErrors = maxErrors;
        }
        
        public void addError(ResolutionError error) {
            if (errors.size() < maxErrors) {
                errors.add(error);
            }
        }
        
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
        
        public List<ResolutionError> getErrors() {
            return Collections.unmodifiableList(errors);
        }
        
        public boolean shouldStop() {
            return errors.size() >= maxErrors;
        }
    }
    
    public static Result<Void, CError> executeWithErrorCollection(
            List<ResolutionStep> steps, 
            ResolutionContext context,
            int maxErrors) {
        
        ErrorCollector collector = new ErrorCollector(maxErrors);
        
        for (ResolutionStep step : steps) {
            var result = step.apply(context);
            if (result.isErr()) {
                if (result.unwrapErr() instanceof ResolutionError resError) {
                    collector.addError(resError);
                    if (collector.shouldStop()) break;
                } else {
                    // Non-recoverable error
                    return result;
                }
            }
        }
        
        if (collector.hasErrors()) {
            return Result.err(new CompoundResolutionError(collector.getErrors()));
        }
        
        return Result.okVoid();
    }
    
    public static class CompoundResolutionError implements CError {
        private final List<ResolutionError> errors;
        
        public CompoundResolutionError(List<ResolutionError> errors) {
            this.errors = Collections.unmodifiableList(errors);
        }
        
        public List<ResolutionError> getErrors() { return errors; }
        
        @Override
        public String getMessage() {
            return String.format("Resolution failed with %d errors:\n%s",
                errors.size(),
                errors.stream()
                    .map(ResolutionError::getMessage)
                    .collect(Collectors.joining("\n  ", "  ", ""))
            );
        }
    }
}
```

## 7. Performance Optimizations

### 7.1 Caching Strategy

```java
public class ResolutionCache {
    private final Map<String, CacheEntry> symbolCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> dependencyCache = new ConcurrentHashMap<>();
    private final long maxCacheAge;
    
    public ResolutionCache(long maxCacheAgeMs) {
        this.maxCacheAge = maxCacheAgeMs;
    }
    
    public void cacheSymbol(String identifier, Symbol symbol) {
        symbolCache.put(identifier, new CacheEntry(symbol, System.currentTimeMillis()));
    }
    
    public Optional<Symbol> getCachedSymbol(String identifier) {
        CacheEntry entry = symbolCache.get(identifier);
        if (entry != null && !entry.isExpired(maxCacheAge)) {
            return Optional.of(entry.symbol);
        }
        return Optional.empty();
    }
    
    public void cacheDependencies(String namespace, Set<String> dependencies) {
        dependencyCache.put(namespace, new HashSet<>(dependencies));
    }
    
    public Optional<Set<String>> getCachedDependencies(String namespace) {
        return Optional.ofNullable(dependencyCache.get(namespace))
                .map(HashSet::new);
    }
    
    public void invalidateNamespace(String namespace) {
        symbolCache.entrySet().removeIf(entry -> 
            entry.getValue().symbol.nsScope().ns() == namespace.hashCode()
        );
        dependencyCache.remove(namespace);
    }
    
    public void cleanup() {
        long now = System.currentTimeMillis();
        symbolCache.entrySet().removeIf(entry -> 
            entry.getValue().isExpired(maxCacheAge, now)
        );
    }
    
    private record CacheEntry(Symbol symbol, long timestamp) {
        boolean isExpired(long maxAge) {
            return isExpired(maxAge, System.currentTimeMillis());
        }
        
        boolean isExpired(long maxAge, long currentTime) {
            return (currentTime - timestamp) > maxAge;
        }
    }
}
```

### 7.2 Incremental Compilation Support

```java
public class IncrementalResolver {
    private final Map<String, CompilationSnapshot> snapshots = new HashMap<>();
    private final ResolutionCache cache;
    
    public IncrementalResolver(ResolutionCache cache) {
        this.cache = cache;
    }
    
    public Result<Void, CError> resolveIncremental(Environment env, Set<String> changedNamespaces) {
        // Determine which namespaces need re-resolution
        Set<String> namespacesToResolve = calculateAffectedNamespaces(changedNamespaces);
        
        // Invalidate cache for affected namespaces
        namespacesToResolve.forEach(cache::invalidateNamespace);
        
        // Create resolution steps for affected namespaces only
        List<ResolutionStep> steps = ResolutionPipeline.createResolutionPipeline();
        
        // Execute resolution in dependency order
        for (String namespace : getResolutionOrder(namespacesToResolve)) {
            Optional<Namespace> ns = env.lookupQualifier(namespace);
            if (ns.isEmpty()) continue;
            
            SubEnvironment subEnv = new SubEnvironment(env, ns.get());
            CompilationContext compCtx = CompilationContext.of(ns.get(), subEnv);
            ResolutionContext resolCtx = ResolutionContext.of(compCtx);
            
            for (ResolutionStep step : steps) {
                var result = step.apply(resolCtx);
                if (result.isErr()) return result;
            }
            
            // Update snapshot
            snapshots.put(namespace, CompilationSnapshot.create(ns.get()));
        }
        
        return Result.okVoid();
    }
    
    private Set<String> calculateAffectedNamespaces(Set<String> changedNamespaces) {
        Set<String> affected = new HashSet<>(changedNamespaces);
        
        // Add dependent namespaces
        for (String changed : changedNamespaces) {
            affected.addAll(getDependentNamespaces(changed));
        }
        
        return affected;
    }
    
    private Set<String> getDependentNamespaces(String namespace) {
        return snapshots.values().stream()
            .filter(snapshot -> snapshot.dependencies().contains(namespace))
            .map(CompilationSnapshot::namespaceName)
            .collect(Collectors.toSet());
    }
    
    private List<String> getResolutionOrder(Set<String> namespaces) {
        // Topological sort of namespaces based on dependencies
        return new ArrayList<>(namespaces); // Simplified
    }
    
    private record CompilationSnapshot(
        String namespaceName,
        Set<String> dependencies,
        long timestamp,
        Map<String, Symbol> exportedSymbols
    ) {
        static CompilationSnapshot create(Namespace namespace) {
            // Create snapshot of namespace state
            return new CompilationSnapshot(
                namespace.name(),
                Set.of(), // Dependencies would be extracted from ImportResolver
                System.currentTimeMillis(),
                Map.of() // Exported symbols would be extracted from SymbolTable
            );
        }
    }
}
```

### 7.3 Multithreading Support

```java
public class ParallelResolver {
    private final ExecutorService executor;
    private final ResolutionCache sharedCache;
    
    public ParallelResolver(int threadCount, ResolutionCache sharedCache) {
        this.executor = Executors.newFixedThreadPool(threadCount);
        this.sharedCache = sharedCache;
    }
    
    public Result<Void, CError> resolveParallel(Environment env, DependencyGraph depGraph) {
        // Get dependency layers for parallel execution
        List<Set<String>> dependencyLayers = depGraph.getDependencyLayers();
        
        try {
            for (Set<String> layer : dependencyLayers) {
                // Resolve each layer in parallel
                var layerResult = resolveLayer(env, layer);
                if (layerResult.isErr()) return layerResult;
            }
            
            return Result.okVoid();
            
        } finally {
            executor.shutdown();
        }
    }
    
    private Result<Void, CError> resolveLayer(Environment env, Set<String> namespaceNames) {
        List<Future<Result<Void, CError>>> futures = new ArrayList<>();
        
        // Submit resolution tasks for each namespace in the layer
        for (String namespaceName : namespaceNames) {
            Future<Result<Void, CError>> future = executor.submit(() -> {
                Optional<Namespace> namespace = env.lookupQualifier(namespaceName);
                if (namespace.isEmpty()) return Result.okVoid();
                
                return resolveNamespace(env, namespace.get());
            });
            futures.add(future);
        }
        
        // Wait for all tasks to complete and collect results
        try {
            for (Future<Result<Void, CError>> future : futures) {
                var result = future.get();
                if (result.isErr()) return result;
            }
            return Result.okVoid();
            
        } catch (InterruptedException | ExecutionException e) {
            return Result.err(InternalError.of("Parallel resolution failed: " + e.getMessage()));
        }
    }
    
    private Result<Void, CError> resolveNamespace(Environment env, Namespace namespace) {
        SubEnvironment subEnv = new SubEnvironment(env, namespace);
        CompilationContext compCtx = CompilationContext.of(namespace, subEnv);
        ResolutionContext resolCtx = ResolutionContext.of(compCtx);
        
        // Use shared cache for thread-safe caching
        resolCtx.setCache(sharedCache);
        
        ResolutionStep pipeline = ResolutionPipeline.createResolutionPipeline();
        return pipeline.apply(resolCtx);
    }
}
```

## 8. Testing Strategy

### 8.1 Unit Test Structure

```java
public class SubEnvironmentTest {
    
    @Test
    public void testScopePushPop() {
        Environment env = new Environment();
        Namespace ns = createTestNamespace();
        SubEnvironment subEnv = new SubEnvironment(env, ns);
        
        // Test scope push
        SubEnvironment blockEnv = subEnv.pushScope("test_block");
        assertNotEquals(subEnv.getCurrentScopeId(), blockEnv.getCurrentScopeId());
        
        // Test scope pop
        SubEnvironment poppedEnv = blockEnv.popScope();
        assertEquals(subEnv.getCurrentScopeId(), poppedEnv.getCurrentScopeId());
    }
    
    @Test
    public void testSymbolLookupChain() {
        // Test symbol resolution across multiple scopes
        SubEnvironment env = createNestedScopeEnvironment();
        
        // Insert symbol in outer scope
        Symbol outerSymbol = createTestSymbol("outer_var");
        env.insertSymbol(outerSymbol);
        
        // Create inner scope and lookup
        SubEnvironment innerEnv = env.pushScope("inner");
        var result = innerEnv.lookupSymbol("outer_var");
        
        assertTrue(result.isOk());
        assertTrue(result.unwrap().isPresent());
        assertEquals("outer_var", result.unwrap().get().identifier());
    }
}

public class DependencyGraphTest {
    
    @Test
    public void testCircularDependencyDetection() {
        DependencyGraph graph = new DependencyGraph();
        
        // Create circular dependency: A -> B -> C -> A
        graph.addDependency("A", "B");
        graph.addDependency("B", "C");
        graph.addDependency("C", "A");
        
        var result = graph.detectCircularDependencies();
        assertTrue(result.isErr());
        
        List<String> cycle = result.unwrapErr().getCycles().get(0);
        assertTrue(cycle.contains("A"));
        assertTrue(cycle.contains("B"));
        assertTrue(cycle.contains("C"));
    }
    
    @Test
    public void testTopologicalSort() {
        DependencyGraph graph = new DependencyGraph();
        
        // Create acyclic dependency: A -> B, A -> C, B -> D, C -> D
        graph.addDependency("A", "B");
        graph.addDependency("A", "C");
        graph.addDependency("B", "D");
        graph.addDependency("C", "D");
        
        var result = graph.topologicalSort();
        assertTrue(result.isOk());
        
        List<String> sorted = result.unwrap();
        int aIndex = sorted.indexOf("A");
        int bIndex = sorted.indexOf("B");
        int cIndex = sorted.indexOf("C");
        int dIndex = sorted.indexOf("D");
        
        assertTrue(aIndex < bIndex);
        assertTrue(aIndex < cIndex);
        assertTrue(bIndex < dIndex);
        assertTrue(cIndex < dIndex);
    }
}
```

### 8.2 Integration Test Framework

```java
public class ResolutionIntegrationTest {
    
    @Test
    public void testFullResolutionPipeline() {
        // Create test project structure
        Environment env = createTestEnvironment();
        populateTestNamespaces(env);
        
        // Execute full resolution pipeline
        ResolutionStep pipeline = ResolutionPipeline.createResolutionPipeline();
        var result = ResolutionBridge.applyResolutionStep(env, pipeline);
        
        assertTrue(result.isOk());
        
        // Verify all namespaces are fully resolved
        for (Namespace ns : env.getAllNamespaces()) {
            assertEquals(Compiler.State.FULLY_RESOLVED, ns.getCompileState());
        }
    }
    
    @Test
    public void testIncrementalResolution() {
        Environment env = createTestEnvironment();
        IncrementalResolver resolver = new IncrementalResolver(new ResolutionCache(60000));
        
        // Initial full resolution
        var result = resolver.resolveIncremental(env, env.getAllNamespaces().stream()
            .map(Namespace::name)
            .collect(Collectors.toSet()));
        assertTrue(result.isOk());
        
        // Modify single namespace
        Set<String> changed = Set.of("test.modified");
        result = resolver.resolveIncremental(env, changed);
        assertTrue(result.isOk());
        
        // Verify only affected namespaces were re-resolved
        // (This would require additional tracking in the implementation)
    }
    
    private Environment createTestEnvironment() {
        Environment env = new Environment();
        // Populate with test data
        return env;
    }
    
    private void populateTestNamespaces(Environment env) {
        // Create test namespace structure with various dependency patterns
    }
}
```

## 9. Implementation Roadmap

### Phase 1: Core Infrastructure (Weeks 1-2)
**Deliverables**:
- [ ] `SubEnvironment` class with scope stack management
- [ ] Complete `SymbolTable.lookup(IntList, String)` implementation
- [ ] `ResolutionContext` and `CompilationContext` classes
- [ ] Basic `DependencyGraph` with cycle detection
- [ ] Unit tests for core components

**Acceptance Criteria**:
- SubEnvironment can push/pop scopes correctly
- Symbol lookups work across scope chains
- Circular dependency detection identifies cycles
- All unit tests pass

### Phase 2: Stage 1 Resolution (Weeks 3-4)
**Deliverables**:
- [ ] `Stage1Resolver.collectDeclarations()` implementation
- [ ] `ImportResolver` with namespace path handling
- [ ] Integration with existing `Environment.applyCompilerStep()`
- [ ] `ResolutionBridge` for pipeline integration
- [ ] Stage 1 integration tests

**Acceptance Criteria**:
- Declaration collection walks AST correctly
- Import resolution handles namespace operators
- Integration with existing pipeline works
- Stage 1 tests pass with sample code

### Phase 3: Stage 2 Resolution (Weeks 5-6)
**Deliverables**:
- [ ] `Stage2Resolver.resolveTypes()` implementation
- [ ] Usage verification and accessibility checking
- [ ] Comprehensive error handling and reporting
- [ ] `ResolutionPipeline` factory methods
- [ ] End-to-end integration tests

**Acceptance Criteria**:
- Type resolution works for all expression types
- Usage verification catches undefined symbols
- Error messages are clear and actionable
- Full pipeline resolves complex projects

### Phase 4: Optimization and Production (Weeks 7-8)
**Deliverables**:
- [ ] `ResolutionCache` implementation
- [ ] `IncrementalResolver` for selective re-resolution
- [ ] `ParallelResolver` for multithreaded resolution
- [ ] Performance benchmarks and optimizations
- [ ] Production deployment testing

**Acceptance Criteria**:
- Caching improves resolution performance by 50%+
- Incremental compilation only re-resolves changed components
- Parallel resolution utilizes multiple CPU cores
- Memory usage remains reasonable for large projects

## 10. Risk Mitigation

### 10.1 Technical Risks

| Risk | Impact | Mitigation |
|------|---------|------------|
| Memory usage from SubEnvironment caching | High | Implement LRU eviction, monitor memory usage |
| Circular dependencies causing deadlock | High | Multiple resolution strategies, timeout detection |
| Performance degradation with large projects | Medium | Lazy evaluation, incremental compilation, profiling |
| Integration complexity with existing pipeline | Medium | Gradual rollout, comprehensive testing |
| Thread safety issues in parallel resolution | Medium | Use concurrent collections, careful synchronization |

### 10.2 Implementation Risks

| Risk | Impact | Mitigation |
|------|---------|------------|
| AST structure changes during implementation | Medium | Abstract AST access through interfaces |
| Error handling complexity | Medium | Use Result type consistently, comprehensive testing |
| Testing coverage gaps | Medium | Automated test generation, integration test suite |
| Performance regression | Low | Continuous benchmarking, performance tests |

## 11. Success Metrics

### 11.1 Functional Metrics
- **Resolution Accuracy**: 100% of valid programs resolve correctly
- **Error Detection**: 100% of invalid programs produce meaningful errors
- **Circular Dependency Handling**: All circular dependency patterns handled gracefully

### 11.2 Performance Metrics
- **Resolution Speed**: Sub-second resolution for projects up to 10K lines
- **Memory Usage**: Linear growth with project size, < 100MB for typical projects
- **Incremental Performance**: < 10% overhead for incremental vs full resolution

### 11.3 Maintainability Metrics
- **Test Coverage**: > 90% line coverage, > 95% branch coverage
- **Code Complexity**: Average cyclomatic complexity < 10
- **Documentation**: All public APIs documented with examples

## 12. File Structure

The implementation will create the following new files:

```
src/main/java/compile/resolve/
├── SubEnvironment.java              # Scope chain management
├── ResolutionContext.java           # Resolution state and context
├── CompilationContext.java          # Compilation-specific context
├── ResolutionStep.java              # Resolution step interface
├── ResolutionBridge.java            # Integration with existing pipeline
├── DependencyGraph.java             # Circular dependency detection
├── ImportResolver.java              # Import and aliasing resolution
├── Stage1Resolver.java              # Declaration collection and imports
├── Stage2Resolver.java              # Type resolution and verification
├── ResolutionPipeline.java          # Pipeline factory methods
├── ResolutionCache.java             # Performance caching
├── IncrementalResolver.java         # Incremental compilation
├── ParallelResolver.java            # Multithreaded resolution
└── ResolutionErrorRecovery.java     # Error handling and recovery
```

Additional modifications to existing files:
- `Environment.java`: Add resolution step methods
- `SymbolTable.java`: Complete lookup implementation, add batch operations
- `ResolutionError.java`: Comprehensive error type definitions (new file)

This specification provides a complete roadmap for implementing the 2-stage symbol resolution system while maintaining integration with your existing architecture and supporting advanced features like incremental compilation and multithreading.