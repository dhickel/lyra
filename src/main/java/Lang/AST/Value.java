package Lang.AST;

public sealed interface Value {
    record I32(int i) implements Value { }

    record I64(long i) implements Value { }

    record F32(float f32) implements Value { }

    record F64(double f64) implements Value { }

    record Bool(boolean b) implements Value { }

    record Quote(ASTNode quotedNode) implements Value { }

    record Nil() implements Value { }

    record Array() implements Value { }

    record Str(String s) implements Value { }

    record Tuple() implements Value { }

    record Identifier(ASTNode.Symbol symbol) implements Value { }

}
