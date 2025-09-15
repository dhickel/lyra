package compile.resolve;

import lang.LangType;
import lang.ast.ASTNode;
import lang.ast.MetaData;
import lang.env.ModuleEnv;
import lang.env.SymbolRef;
import lang.env.TypeRef;
import util.Result;
import util.exceptions.CError;

import java.util.List;

public class Resolver {
    private final ModuleEnv env;

    public Resolver(ModuleEnv env) {
        this.env = env;
    }

    public Result<Void, CError> resolve() {
        List<ASTNode> expressions;
        switch (env.getExpressions()) {
            case Result.Err<List<ASTNode>, CError> err -> { return err.castErr(); }
            case Result.Ok(List<ASTNode> exprs) -> { expressions = exprs; }
        }

        for (ASTNode expr : expressions) {
            switch (pass1(expr)) {
                case Result.Err<Void, CError> err -> { return err; }
                case Result.Ok<Void, CError> ok -> { }
            }
        }

        for (ASTNode expr : expressions) {
            switch (pass2(expr)) {
                case Result.Err<Void, CError> err -> { return err; }
                case Result.Ok<Void, CError> ok -> { }
            }
        }

        return Result.okVoid();
    }

    private Result<Void, CError> pass1(ASTNode node) {
        switch (node) {
            case ASTNode.Expr.B(var expressions, var meta) -> {
                env.enterScope();
                for (ASTNode expression : expressions) {
                    var result = pass1(expression);
                    if (result.isErr()) return result;
                }
                env.exitScope();
            }
            case ASTNode.Expr.L(var params, var rtn, var body, var isForm, var meta) -> {
                env.enterScope();
                for (var param : params) {
                    var symbolData = new SymbolRef.SymbolData(
                            param.identifier(),
                            // TODO: Modifiers for parameters
                            java.util.Collections.emptySet(),
                            TypeRef.ofResolved(param.typ()),
                            new SymbolRef.SymbolData.Field(),
                            meta.lineChar(),
                            env.getCurrentNsScope()
                    );
                    env.define(param.identifier(), symbolData);
                }
                var result = pass1(body);
                if (result.isErr()) return result;
                env.exitScope();
            }
            case ASTNode.Stmt.Let(var identifier, var modifiers, var assignment, var meta) -> {
                var typeResult = inferType(assignment);
                if (typeResult.isErr()) return typeResult.castErr();

                var symbolData = new SymbolRef.SymbolData(
                        identifier.name(),
                        new java.util.HashSet<>(modifiers),
                        TypeRef.ofResolved(typeResult.unwrap()),
                        new SymbolRef.SymbolData.Field(), // Assume Field for now
                        meta.lineChar(),
                        env.getCurrentNsScope()
                );

                var defineResult = env.define(identifier.name(), symbolData);
                if (defineResult.isErr()) return defineResult.castErr();

                return pass1(assignment);
            }
            case ASTNode.Expr.S(var op, var operands, var meta) -> {
                var result = pass1(op);
                if (result.isErr()) return result;
                for (ASTNode operand : operands) {
                    result = pass1(operand);
                    if (result.isErr()) return result;
                }
            }
            // Other cases will be added as needed
            default -> { }
        }
        return Result.okVoid();
    }

    private Result<LangType, CError> inferType(ASTNode.Expr expr) {
        return switch (expr) {
            case ASTNode.Expr.V(var value, var meta) ->
                switch (value) {
                    case ASTNode.Value.I32 _ -> Result.ok(LangType.I32);
                    case ASTNode.Value.I64 _ -> Result.ok(LangType.I64);
                    case ASTNode.Value.F32 _ -> Result.ok(LangType.F32);
                    case ASTNode.Value.F64 _ -> Result.ok(LangType.F64);
                    case ASTNode.Value.Bool _ -> Result.ok(LangType.BOOL);
                    case ASTNode.Value.Nil _ -> Result.ok(LangType.NIL);
                    case ASTNode.Value.Str _ -> Result.ok(new LangType.Composite.String());
                    default -> Result.ok(LangType.UNDEFINED);
                };
            case ASTNode.Expr.L(var params, var rtnType, var body, var isForm, var meta) -> {
                var paramTypes = params.stream().map(p -> p.typ()).collect(java.util.stream.Collectors.toList());
                yield Result.ok(LangType.ofFunction(paramTypes, rtnType));
            }
            default -> Result.ok(LangType.UNDEFINED);
        };
    }

    private Result<Void, CError> pass2(ASTNode node) {
        switch (node) {
            case ASTNode.Expr.B(var expressions, var meta) -> {
                env.enterScope();
                for (ASTNode expression : expressions) {
                    var result = pass2(expression);
                    if (result.isErr()) return result;
                }
                env.exitScope();
            }
            case ASTNode.Expr.L(var params, var rtn, var body, var isForm, var meta) -> {
                env.enterScope();
                var result = pass2(body);
                if (result.isErr()) return result;
                env.exitScope();
            }
            case ASTNode.Stmt.Let(var identifier, var modifiers, var assignment, var meta) -> {
                return pass2(assignment);
            }
            case ASTNode.Expr.S(var op, var operands, var meta) -> {
                var result = pass2(op);
                if (result.isErr()) return result;
                for (ASTNode operand : operands) {
                    result = pass2(operand);
                    if (result.isErr()) return result;
                }
            }
            case ASTNode.Expr.M(var expressionChain, var meta) -> {
                for (var access : expressionChain) {
                    var result = resolveAccess(access, meta);
                    if (result.isErr()) return result.castErr();
                }
            }
            case ASTNode.Expr.V(ASTNode.Value.Identifier(var id), var meta) -> {
                var symbol = env.lookup(id.name());
                if (symbol.isEmpty()) {
                    return Result.err(util.exceptions.ResolutionError.undefinedSymbol(id.name(), meta.lineChar()));
                }
                meta.setSymbolRef(symbol.get());
            }
            default -> { }
        }
        return Result.okVoid();
    }

    private Result<Void, CError> resolveAccess(ASTNode.Access access, MetaData meta) {
        switch (access) {
            case ASTNode.Access.Identifier(var id) -> {
                var symbol = env.lookup(id.name());
                if (symbol.isEmpty()) {
                    return Result.err(util.exceptions.ResolutionError.undefinedSymbol(id.name(), meta.lineChar()));
                }
                meta.setSymbolRef(symbol.get());
            }
            // Other access types will be handled as needed
            default -> { }
        }
        return Result.okVoid();
    }
}
