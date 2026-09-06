package io.mindspice.lyra.compiler.ast;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;

/** Complete immutable syntax-phase output for one source module. */
public record SyntaxProgram(
        SourceId sourceId,
        String sourceRevision,
        List<SyntaxNode.ImportDeclaration> imports,
        List<SyntaxNode.Form> forms,
        SourceSpan span) implements SyntaxNode, ImmutablePhaseArtifact {
    public SyntaxProgram {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        imports = copy(imports, "imports");
        forms = copy(forms, "forms");
        Objects.requireNonNull(span, "span");
        if (!sourceId.equals(span.sourceId())) {
            throw new IllegalArgumentException("program span belongs to a different source");
        }
        validateChildren(imports, sourceId, span);
        validateChildren(forms, sourceId, span);
    }

    public SyntaxProgram(
            SourceId sourceId,
            List<SyntaxNode.ImportDeclaration> imports,
            List<SyntaxNode.Form> forms,
            SourceSpan span) {
        this(sourceId, "", imports, forms, span);
    }

    public List<SyntaxNode.ImportDeclaration> importDeclarations() {
        return imports;
    }

    public List<SyntaxNode.Form> topLevelForms() {
        return forms;
    }

    public SourceSpan sourceSpan() {
        return span;
    }

    @Override
    public <R> R accept(SyntaxVisitor<R> visitor) {
        return Objects.requireNonNull(visitor, "visitor").visitProgram(this);
    }

    private static <T extends SyntaxNode> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }

    private static void validateChildren(
            List<? extends SyntaxNode> children, SourceId sourceId, SourceSpan parentSpan) {
        for (SyntaxNode child : children) {
            SourceSpan childSpan = child.span();
            if (!sourceId.equals(childSpan.sourceId())) {
                throw new IllegalArgumentException("program child belongs to a different source");
            }
            if (childSpan.startOffset() < parentSpan.startOffset()
                    || childSpan.endOffset() > parentSpan.endOffset()) {
                throw new IllegalArgumentException("program child lies outside the program span");
            }
        }
    }
}
