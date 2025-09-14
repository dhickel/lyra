package lang.ast;

import lang.LangType;
import lang.LineChar;
import lang.env.TypeRef;

import java.util.Optional;

public class MetaData {
    private LineChar lineChar;
    private TypeRef typeRef = null;

    public MetaData(LineChar lineChar) {
        this.lineChar = lineChar;
    }

    public static MetaData of(LineChar lineChar) { return new MetaData(lineChar); }

    public boolean isTypeResolved() { return typeRef != null && typeRef.isResolved(); }

    public Optional<TypeRef> getTypeRef() { return typeRef == null ? Optional.empty() : Optional.of(typeRef); }

    public void setTypeRef(TypeRef typeRef) { this.typeRef = typeRef; }
}
