package lang.types;

import lang.LangType;

public sealed interface TypeConversion {
    
    record None() implements TypeConversion {}
    
    record Primitive(LangType.Primitive targetType) implements TypeConversion {}
    
    record Composite(LangType.Composite targetType) implements TypeConversion {}
    
    TypeConversion NONE = new None();
    
    static TypeConversion none() { 
        return NONE; 
    }
    
    static TypeConversion primitive(LangType.Primitive target) { 
        return new Primitive(target); 
    }
    
    static TypeConversion composite(LangType.Composite target) { 
        return new Composite(target); 
    }
}