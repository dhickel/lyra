package lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public sealed interface LangType {
    LangType UNDEFINED = new Undefined();
    Primitive I32 = new Primitive.I32();
    Primitive I64 = new Primitive.I64();
    Primitive F32 = new Primitive.F32();
    Primitive F64 = new Primitive.F64();
    Primitive BOOL = new Primitive.Bool();
    Primitive NIL = new Primitive.Nil();

    List<Primitive> allPrimitives = List.of(
            LangType.I32, LangType.I64,
            LangType.F32, LangType.F64,
            LangType.BOOL, LangType.NIL
    );

    record Undefined() implements LangType { }

    record UserType(String identifier) implements LangType { }


    static LangType ofUser(String s) { return new UserType(s); }

    static LangType ofArray(LangType type) { return new Composite.Array(type); }

    static LangType ofFunction(List<LangType> params, LangType rtn) { return new Composite.Function(params, rtn); }

    static LangType ofTuple(List<LangType> types) { return new Composite.Tuple(types); }

    static List<LangType> ofUndefinedList(int length) {
        return new ArrayList<>(Collections.nCopies(length, LangType.UNDEFINED));
    }


    sealed interface Primitive extends LangType {
        default int getPrecedence() {
            return switch (this) {
                case Nil nil -> 0;
                case Bool bool -> 1;
                case I32 i32 -> 3;
                case I64 i64 -> 4;
                case F32 f32 -> 5;
                case F64 f64 -> 6;
            };
        }


        record I32() implements Primitive { }

        record I64() implements Primitive { }

        record F32() implements Primitive { }

        record F64() implements Primitive { }

        record Bool() implements Primitive { }

        record Nil() implements Primitive { }
    }

    sealed interface Composite extends LangType {
        record Function(List<LangType> parameters, LangType rtnType) implements Composite { }

        record Array(LangType elementType) implements Composite { }

        record Tuple(List<LangType> memberTypes) implements Composite { }

        record String() implements Composite { }

        record Quote() implements Composite { }
    }


}
