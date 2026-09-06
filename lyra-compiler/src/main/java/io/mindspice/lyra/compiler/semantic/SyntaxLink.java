package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;
import java.util.Optional;

/** A source-span link retained for later phases without changing the syntax AST. */
public record SyntaxLink(
        SourceSpan span,
        SyntaxLinkKind kind,
        Optional<ReferenceId> referenceId,
        Optional<DeclarationId> declarationId,
        Optional<LambdaId> lambdaId,
        Optional<ModuleId> moduleId,
        Optional<ExportId> exportId,
        Optional<AccessKind> accessKind) {
    public SyntaxLink {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(lambdaId, "lambdaId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(exportId, "exportId");
        Objects.requireNonNull(accessKind, "accessKind");
    }

    public SyntaxLink(SourceSpan span, SyntaxLinkKind kind) {
        this(span, kind, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static SyntaxLink declaration(SourceSpan span, DeclarationId declarationId) {
        return new SyntaxLink(span, SyntaxLinkKind.DECLARATION, Optional.empty(),
                Optional.of(Objects.requireNonNull(declarationId, "declarationId")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static SyntaxLink parameter(SourceSpan span, DeclarationId declarationId) {
        return new SyntaxLink(span, SyntaxLinkKind.PARAMETER, Optional.empty(),
                Optional.of(Objects.requireNonNull(declarationId, "declarationId")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static SyntaxLink predicateBinding(SourceSpan span, DeclarationId declarationId) {
        return new SyntaxLink(span, SyntaxLinkKind.PREDICATE_BINDING, Optional.empty(),
                Optional.of(Objects.requireNonNull(declarationId, "declarationId")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static SyntaxLink reference(
            SourceSpan span, ReferenceId referenceId, Optional<DeclarationId> declarationId,
            Optional<ModuleId> moduleId, Optional<ExportId> exportId) {
        return new SyntaxLink(span, SyntaxLinkKind.REFERENCE,
                Optional.of(Objects.requireNonNull(referenceId, "referenceId")),
                Objects.requireNonNull(declarationId, "declarationId"), Optional.empty(),
                Objects.requireNonNull(moduleId, "moduleId"), Objects.requireNonNull(exportId, "exportId"),
                Optional.empty());
    }

    public static SyntaxLink lambda(SourceSpan span, LambdaId lambdaId) {
        return new SyntaxLink(span, SyntaxLinkKind.LAMBDA, Optional.empty(), Optional.empty(),
                Optional.of(Objects.requireNonNull(lambdaId, "lambdaId")), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    public static SyntaxLink access(
            SourceSpan span, AccessKind accessKind, Optional<ModuleId> moduleId,
            Optional<ExportId> exportId) {
        return new SyntaxLink(span, SyntaxLinkKind.ACCESS, Optional.empty(), Optional.empty(),
                Optional.empty(), Objects.requireNonNull(moduleId, "moduleId"),
                Objects.requireNonNull(exportId, "exportId"),
                Optional.of(Objects.requireNonNull(accessKind, "accessKind")));
    }

    public static SyntaxLink call(
            SourceSpan span,
            Optional<ReferenceId> referenceId,
            Optional<DeclarationId> declarationId,
            Optional<ModuleId> moduleId,
            Optional<ExportId> exportId) {
        return new SyntaxLink(
                span,
                SyntaxLinkKind.CALL,
                Objects.requireNonNull(referenceId, "referenceId"),
                Objects.requireNonNull(declarationId, "declarationId"),
                Optional.empty(),
                Objects.requireNonNull(moduleId, "moduleId"),
                Objects.requireNonNull(exportId, "exportId"),
                Optional.empty());
    }
}
