package lang.types;

import lang.LangType;

public class TypeCompatibility {
    
    public record Result(boolean compatible, TypeConversion conversion) {
        public static Result compatible(TypeConversion conversion) {
            return new Result(true, conversion);
        }
        
        public static Result incompatible() {
            return new Result(false, TypeConversion.none());
        }
    }
    
    public static Result check(LangType source, LangType target) {
        if (source.equals(target)) {
            return Result.compatible(TypeConversion.none());
        }
        
        if (source instanceof LangType.Primitive srcPrim && 
            target instanceof LangType.Primitive dstPrim) {
            
            if (canWidenPrimitive(srcPrim, dstPrim)) {
                return Result.compatible(TypeConversion.primitive(dstPrim));
            }
        }
        
        return Result.incompatible();
    }
    
    private static boolean canWidenPrimitive(LangType.Primitive source, LangType.Primitive target) {
        if (source.equals(target)) return true;
        
        int srcPrec = getPrecedence(source);
        int dstPrec = getPrecedence(target);
        
        // > 1 ensures that Nil and Bool cannot be cast to numeric types
        return srcPrec <= dstPrec && srcPrec > 1 && dstPrec > 1;
    }
    
    public static int getPrecedence(LangType.Primitive primitive) {
        return switch (primitive) {
            case LangType.Primitive.Nil nil -> 0;
            case LangType.Primitive.Bool bool -> 1;
            case LangType.Primitive.I8 i8 -> 2;
            case LangType.Primitive.I16 i16 -> 3;
            case LangType.Primitive.I32 i32 -> 4;
            case LangType.Primitive.I64 i64 -> 5;
            case LangType.Primitive.F32 f32 -> 6;
            case LangType.Primitive.F64 f64 -> 7;
        };
    }
}