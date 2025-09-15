package lang.ast;

import lang.LangType;
import lang.LineChar;
import lang.env.TypeRef;

import java.util.Optional;

import lang.env.SymbolRef;

public class MetaData {
    private LineChar lineChar;
    private TypeRef typeRef = null;
    private SymbolRef symbolRef = null;

    public MetaData(LineChar lineChar) {
        this.lineChar = lineChar;
    }

    public static MetaData of(LineChar lineChar) { return new MetaData(lineChar); }

    public boolean isTypeResolved() { return typeRef != null && typeRef.isResolved(); }

    public boolean isSymbolResolved() { return symbolRef != null && symbolRef.resolved(); }

    public Optional<TypeRef> getTypeRef() { return typeRef == null ? Optional.empty() : Optional.of(typeRef); }

    public void setTypeRef(TypeRef typeRef) { this.typeRef = typeRef; }

    public Optional<SymbolRef> getSymbolRef() { return symbolRef == null ? Optional.empty() : Optional.of(symbolRef); }

    public void setSymbolRef(SymbolRef symbolRef) { this.symbolRef = symbolRef; }

    public LineChar getLineChar() {
        return lineChar;
    }
}
