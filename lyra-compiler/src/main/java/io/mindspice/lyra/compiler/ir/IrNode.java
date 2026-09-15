package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.semantic.AccessKind;
import io.mindspice.lyra.compiler.semantic.DeclarationKind;
import io.mindspice.lyra.compiler.semantic.MutationKind;
import io.mindspice.lyra.compiler.semantic.ReferenceKind;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.ConversionKind;
import io.mindspice.lyra.compiler.types.ConversionStep;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Closed, JVM-independent typed IR nodes.
 *
 * <p>Every node that represents a source expression carries the producer-issued
 * {@link FlowSiteId}.  Synthetic nodes used to spell one source operation (for
 * example the narrowing edge inside coalescing) deliberately leave the
 * expression site empty.  Runtime checks retain their owning failure site
 * explicitly, keeping the source-expression index one-to-one while making
 * generated checks explicit.</p>
 */
public sealed interface IrNode extends ImmutablePhaseArtifact
        permits IrNode.Constant,
                IrNode.Reference,
                IrNode.CaptureReference,
                IrNode.Declaration,
                IrNode.NominalDeclaration,
                IrNode.Construction,
                IrNode.Rebinding,
                IrNode.Sequence,
                IrNode.Block,
                IrNode.ArrayLiteral,
                IrNode.TupleLiteral,
                IrNode.IndexAccess,
                IrNode.Operator,
                IrNode.ShortCircuit,
                IrNode.Conversion,
                IrNode.Narrowing,
                IrNode.Branch,
                IrNode.Coalesce,
                IrNode.Match,
                IrNode.Range,
                IrNode.Loop,
                IrNode.DirectCall,
                IrNode.CallableCall,
                IrNode.Lambda,
                IrNode.Access,
                IrNode.RuntimeCheck {
    SourceSpan span();

    LyraType type();

    /** The exact typed-expression site represented by this node, if any. */
    Optional<FlowSiteId> siteId();

    default Optional<FlowSiteId> flowSiteId() {
        return siteId();
    }

    default SourceSpan sourceSpan() {
        return span();
    }

    default LyraType valueType() {
        return type();
    }

    /**
     * Direct children in the semantic evaluation order.  Branch alternatives
     * are listed predicate, then, else; the evaluation metadata records which
     * alternative is selected at runtime.
     */
    default List<IrNode> evaluationChildren() {
        return childrenInEvaluationOrder();
    }

    default List<IrNode> childrenInEvaluationOrder() {
        return switch (this) {
            case Constant ignored -> List.of();
            case Reference ignored -> List.of();
            case CaptureReference ignored -> List.of();
            case Declaration declaration -> List.of(declaration.initializer());
            case NominalDeclaration declaration -> declaration.initializers();
            case Construction construction -> construction.arguments();
            case Rebinding rebinding -> List.of(rebinding.target(), rebinding.value());
            case Sequence sequence -> sequence.forms();
            case Block block -> block.forms();
            case ArrayLiteral array -> array.elements();
            case TupleLiteral tuple -> tuple.elements();
            case Range range -> List.of(range.start(), range.end(), range.step());
            case Loop loop -> List.of(loop.input(), loop.action());
            case IndexAccess index -> List.of(index.receiver(), index.index());
            case Operator operator -> operator.operands();
            case ShortCircuit shortCircuit -> shortCircuit.operands();
            case Conversion conversion -> List.of(conversion.operand());
            case Narrowing narrowing -> List.of(narrowing.operand());
            case Branch branch -> {
                ArrayList<IrNode> children = new ArrayList<>();
                children.add(branch.predicate());
                children.add(branch.thenBranch());
                branch.elseBranch().ifPresent(children::add);
                yield List.copyOf(children);
            }
            case Coalesce coalesce -> List.of(coalesce.value(), coalesce.fallback());
            case Match match -> {
                ArrayList<IrNode> children = new ArrayList<>();
                match.subject().ifPresent(children::add);
                for (MatchArm arm : match.arms()) {
                    arm.pattern().ifPresent(children::add);
                    arm.guard().ifPresent(children::add);
                    children.add(arm.result());
                }
                yield List.copyOf(children);
            }
            case DirectCall call -> {
                ArrayList<IrNode> children = new ArrayList<>();
                call.receiver().ifPresent(children::add);
                children.addAll(call.arguments());
                yield List.copyOf(children);
            }
            case CallableCall call -> {
                ArrayList<IrNode> children = new ArrayList<>();
                children.add(call.target());
                children.addAll(call.arguments());
                yield List.copyOf(children);
            }
            case Lambda lambda -> List.of(lambda.body());
            case Access access -> access.receiver().map(List::of).orElseGet(List::of);
            case RuntimeCheck check -> List.of(check.operand());
        };
    }

    <R> R accept(IrVisitor<R> visitor);

    /** Deferred instance initialization, certified before lowering; not module-time field execution. */
    record NominalDeclaration(SourceSpan span, LyraType type, DeclarationId declarationId,
                              DeclarationId self, io.mindspice.lyra.compiler.types.NominalSchema schema,
                              List<DeclarationId> members, Optional<LambdaId> constructor,
                              List<IrNode> initializers, List<DeclarationId> initializedFields,
                              Optional<FlowSiteId> siteId) implements IrNode {
        public NominalDeclaration {
            requireSpanAndType(span, type);
            Objects.requireNonNull(declarationId, "declarationId");
            Objects.requireNonNull(self, "self");
            Objects.requireNonNull(schema, "schema");
            members = List.copyOf(members);
            Objects.requireNonNull(constructor, "constructor");
            initializers = List.copyOf(initializers);
            initializedFields = List.copyOf(initializedFields);
            requireSite(siteId);
            if (type != io.mindspice.lyra.compiler.types.PrimitiveType.UNIT
                    || members.size() != schema.members().size() || !initializedFields.equals(members)
                    || initializers.size() != schema.members().stream().filter(
                            io.mindspice.lyra.compiler.types.NominalSchema.Member::hasInitializer).count()
                    + (constructor.isPresent() ? 1 : 0)) {
                throw new IllegalArgumentException("nominal initialization roles or completion facts are inconsistent");
            }
        }
        @Override public <R> R accept(IrVisitor<R> visitor) { return visitor.visitNominalDeclaration(this); }
    }

    record Construction(SourceSpan span, io.mindspice.lyra.compiler.types.NominalType type,
                        DeclarationId declarationId, Optional<ReferenceId> referenceId,
                        List<IrNode> arguments, Optional<FlowSiteId> siteId) implements IrNode {
        public Construction {
            requireSpanAndType(span, type);
            Objects.requireNonNull(declarationId, "declarationId");
            Objects.requireNonNull(referenceId, "referenceId");
            arguments = List.copyOf(arguments);
            requireSite(siteId);
        }
        @Override public <R> R accept(IrVisitor<R> visitor) { return visitor.visitConstruction(this); }
    }

    record Constant(
            SourceSpan span,
            LyraType type,
            IrConstantValue value,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Constant {
            requireSpanAndType(span, type);
            Objects.requireNonNull(value, "value");
            requireSite(siteId);
        }

        public Constant(SourceSpan span, LyraType type, IrConstantValue value) {
            this(span, type, value, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitConstant(this);
        }
    }

    record Reference(
            SourceSpan span,
            LyraType type,
            Optional<ReferenceId> referenceId,
            Optional<DeclarationId> targetDeclaration,
            Optional<CaptureId> capture,
            ReferenceKind referenceKind,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Reference {
            requireSpanAndType(span, type);
            Objects.requireNonNull(referenceId, "referenceId");
            Objects.requireNonNull(targetDeclaration, "targetDeclaration");
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(referenceKind, "referenceKind");
            requireSite(siteId);
        }

        public Reference(
                SourceSpan span,
                LyraType type,
                Optional<ReferenceId> referenceId,
                Optional<DeclarationId> targetDeclaration,
                Optional<CaptureId> capture,
                ReferenceKind referenceKind) {
            this(span, type, referenceId, targetDeclaration, capture, referenceKind,
                    Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitReference(this);
        }
    }

    record CaptureReference(
            SourceSpan span,
            LyraType type,
            Optional<ReferenceId> referenceId,
            Optional<CaptureId> captureId,
            Optional<DeclarationId> declarationId,
            ReferenceKind referenceKind,
            Optional<FlowSiteId> siteId) implements IrNode {
        public CaptureReference {
            requireSpanAndType(span, type);
            Objects.requireNonNull(referenceId, "referenceId");
            Objects.requireNonNull(captureId, "captureId");
            Objects.requireNonNull(declarationId, "declarationId");
            Objects.requireNonNull(referenceKind, "referenceKind");
            requireSite(siteId);
        }

        public CaptureReference(
                SourceSpan span,
                LyraType type,
                Optional<ReferenceId> referenceId,
                Optional<CaptureId> captureId,
                Optional<DeclarationId> declarationId,
                ReferenceKind referenceKind) {
            this(span, type, referenceId, captureId, declarationId, referenceKind,
                    Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitCaptureReference(this);
        }
    }

    record Declaration(
            SourceSpan span,
            LyraType type,
            Optional<DeclarationId> declarationId,
            DeclarationKind declarationKind,
            Optional<BindingContract> contract,
            IrNode initializer,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Declaration {
            requireSpanAndType(span, type);
            Objects.requireNonNull(declarationId, "declarationId");
            Objects.requireNonNull(declarationKind, "declarationKind");
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(initializer, "initializer");
            requireSite(siteId);
        }

        public Declaration(
                SourceSpan span,
                LyraType type,
                Optional<DeclarationId> declarationId,
                DeclarationKind declarationKind,
                Optional<BindingContract> contract,
                IrNode initializer) {
            this(span, type, declarationId, declarationKind, contract, initializer,
                    Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitDeclaration(this);
        }
    }

    record Rebinding(
            SourceSpan span,
            LyraType type,
            Optional<DeclarationId> targetDeclaration,
            Optional<ReferenceId> rootReference,
            Optional<MutationKind> mutationKind,
            io.mindspice.lyra.compiler.semantic.flow.ProjectionPath route,
            IrNode target,
            IrNode value,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Rebinding {
            requireSpanAndType(span, type);
            Objects.requireNonNull(targetDeclaration, "targetDeclaration");
            Objects.requireNonNull(rootReference, "rootReference");
            Objects.requireNonNull(mutationKind, "mutationKind");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(value, "value");
            requireSite(siteId);
        }

        public Rebinding(
                SourceSpan span,
                LyraType type,
                Optional<DeclarationId> targetDeclaration,
                IrNode target,
                IrNode value) {
            this(span, type, targetDeclaration, Optional.empty(), Optional.empty(),
                    io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.root(),
                    target, value, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitRebinding(this);
        }
    }

    record Sequence(
            SourceSpan span,
            LyraType type,
            List<IrNode> forms,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Sequence {
            requireSpanAndType(span, type);
            forms = copy(forms, "forms");
            requireSite(siteId);
        }

        public Sequence(SourceSpan span, LyraType type, List<IrNode> forms) {
            this(span, type, forms, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitSequence(this);
        }
    }

    record Block(
            SourceSpan span,
            LyraType type,
            Optional<ScopeId> scopeId,
            List<IrNode> forms,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Block {
            requireSpanAndType(span, type);
            Objects.requireNonNull(scopeId, "scopeId");
            forms = copy(forms, "forms");
            requireSite(siteId);
        }

        public Block(
                SourceSpan span,
                LyraType type,
                Optional<ScopeId> scopeId,
                List<IrNode> forms) {
            this(span, type, scopeId, forms, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitBlock(this);
        }
    }

    record ArrayLiteral(
            SourceSpan span,
            LyraType type,
            List<IrNode> elements,
            Optional<FlowSiteId> allocationSite,
            Optional<FlowSiteId> siteId) implements IrNode {
        public ArrayLiteral {
            requireSpanAndType(span, type);
            elements = copy(elements, "elements");
            Objects.requireNonNull(allocationSite, "allocationSite");
            requireSite(siteId);
        }

        public ArrayLiteral(SourceSpan span, LyraType type, List<IrNode> elements) {
            this(span, type, elements, Optional.empty(), Optional.empty());
        }

        public ArrayLiteral(
                SourceSpan span,
                LyraType type,
                List<IrNode> elements,
                Optional<FlowSiteId> siteId) {
            this(span, type, elements, siteId, siteId);
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitArrayLiteral(this);
        }
    }

    record Range(SourceSpan span, LyraType type, IrNode start, IrNode end, IrNode step,
                 boolean inclusive, Optional<FlowSiteId> siteId) implements IrNode {
        public Range {
            requireSpanAndType(span, type);
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(step, "step");
            requireSite(siteId);
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitRange(this);
        }
    }

    /** Two callback-loop arguments, evaluated once before repetition. */
    record Loop(SourceSpan span, LyraType type, boolean conditionControlled,
                IrNode input, IrNode action,
                Optional<io.mindspice.lyra.compiler.semantic.flow.SummaryCallId> callId,
                Optional<FlowSiteId> siteId) implements IrNode {
        public Loop {
            requireSpanAndType(span, type);
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(callId, "callId");
            requireSite(siteId);
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitLoop(this);
        }
    }

    record TupleLiteral(
            SourceSpan span,
            LyraType type,
            List<IrNode> elements,
            Optional<FlowSiteId> siteId) implements IrNode {
        public TupleLiteral {
            requireSpanAndType(span, type);
            elements = copy(elements, "elements");
            requireSite(siteId);
        }

        public TupleLiteral(SourceSpan span, LyraType type, List<IrNode> elements) {
            this(span, type, elements, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitTupleLiteral(this);
        }
    }

    record IndexAccess(
            SourceSpan span,
            LyraType type,
            IrNode receiver,
            IrNode index,
            io.mindspice.lyra.compiler.semantic.flow.ProjectionPath route,
            Optional<FlowSiteId> siteId) implements IrNode {
        public IndexAccess {
            requireSpanAndType(span, type);
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(route, "route");
            requireSite(siteId);
        }

        public IndexAccess(SourceSpan span, LyraType type, IrNode receiver, IrNode index) {
            this(span, type, receiver, index,
                    io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.root(),
                    Optional.empty());
        }

        public IndexAccess(
                SourceSpan span,
                LyraType type,
                IrNode receiver,
                IrNode index,
                io.mindspice.lyra.compiler.semantic.flow.ProjectionPath route) {
            this(span, type, receiver, index, route, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitIndexAccess(this);
        }
    }

    record Operator(
            SourceSpan span,
            LyraType type,
            TokenKind operator,
            List<IrNode> operands,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Operator {
            requireSpanAndType(span, type);
            Objects.requireNonNull(operator, "operator");
            operands = copy(operands, "operands");
            requireSite(siteId);
        }

        public Operator(SourceSpan span, LyraType type, TokenKind operator, List<IrNode> operands) {
            this(span, type, operator, operands, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitOperator(this);
        }
    }

    record ShortCircuit(
            SourceSpan span,
            LyraType type,
            TokenKind operator,
            List<IrNode> operands,
            Optional<FlowSiteId> siteId) implements IrNode {
        public ShortCircuit {
            requireSpanAndType(span, type);
            Objects.requireNonNull(operator, "operator");
            operands = copy(operands, "operands");
            requireSite(siteId);
        }

        public ShortCircuit(
                SourceSpan span,
                LyraType type,
                TokenKind operator,
                List<IrNode> operands) {
            this(span, type, operator, operands, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitShortCircuit(this);
        }
    }

    record Conversion(
            SourceSpan span,
            LyraType type,
            LyraType sourceType,
            ConversionKind kind,
            ConversionStep step,
            IrNode operand,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Conversion {
            requireSpanAndType(span, type);
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(operand, "operand");
            requireSite(siteId);
        }

        public Conversion(
                SourceSpan span,
                LyraType type,
                LyraType sourceType,
                ConversionKind kind,
                ConversionStep step,
                IrNode operand) {
            this(span, type, sourceType, kind, step, operand, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitConversion(this);
        }
    }

    record Narrowing(
            SourceSpan span,
            LyraType type,
            LyraType sourceType,
            IrNode operand,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Narrowing {
            requireSpanAndType(span, type);
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(operand, "operand");
            requireSite(siteId);
        }

        public Narrowing(SourceSpan span, LyraType type, LyraType sourceType, IrNode operand) {
            this(span, type, sourceType, operand, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitNarrowing(this);
        }
    }

    record Branch(
            SourceSpan span,
            LyraType type,
            IrNode predicate,
            IrNode thenBranch,
            Optional<IrNode> elseBranch,
            Optional<DeclarationId> predicateBinding,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Branch {
            requireSpanAndType(span, type);
            Objects.requireNonNull(predicate, "predicate");
            Objects.requireNonNull(thenBranch, "thenBranch");
            Objects.requireNonNull(elseBranch, "elseBranch");
            Objects.requireNonNull(predicateBinding, "predicateBinding");
            requireSite(siteId);
        }

        public Branch(
                SourceSpan span,
                LyraType type,
                IrNode predicate,
                IrNode thenBranch,
                Optional<IrNode> elseBranch,
                Optional<DeclarationId> predicateBinding) {
            this(span, type, predicate, thenBranch, elseBranch, predicateBinding,
                    Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitBranch(this);
        }
    }

    record Coalesce(
            SourceSpan span,
            LyraType type,
            LyraType sourceType,
            IrNode value,
            IrNode fallback,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Coalesce {
            requireSpanAndType(span, type);
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(fallback, "fallback");
            requireSite(siteId);
        }

        public Coalesce(
                SourceSpan span,
                LyraType type,
                LyraType sourceType,
                IrNode value,
                IrNode fallback) {
            this(span, type, sourceType, value, fallback, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitCoalesce(this);
        }
    }

    enum MatchMode {
        TRADITIONAL,
        CONDITIONAL
    }

    record MatchArm(
            SourceSpan span,
            boolean wildcard,
            Optional<IrNode> pattern,
            Optional<IrNode> guard,
            IrNode result,
            Optional<LyraType> comparisonType) implements ImmutablePhaseArtifact {
        public MatchArm {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(guard, "guard");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(comparisonType, "comparisonType");
            if (wildcard == pattern.isPresent()) {
                throw new IllegalArgumentException("IR match arm must have exactly one wildcard or pattern");
            }
            if (wildcard && comparisonType.isPresent()) {
                throw new IllegalArgumentException("IR wildcard cannot carry an equality type");
            }
        }
    }

    record Match(
            SourceSpan span,
            LyraType type,
            MatchMode mode,
            Optional<IrNode> subject,
            List<MatchArm> arms,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Match {
            requireSpanAndType(span, type);
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(subject, "subject");
            arms = copy(arms, "arms");
            requireSite(siteId);
            if ((mode == MatchMode.TRADITIONAL) != subject.isPresent()) {
                throw new IllegalArgumentException("traditional IR match alone has a subject");
            }
            if (arms.isEmpty()) {
                throw new IllegalArgumentException("IR match requires a fallback arm");
            }
            for (int index = 0; index < arms.size(); index++) {
                MatchArm arm = arms.get(index);
                if (mode == MatchMode.CONDITIONAL
                        && (arm.guard().isPresent() || arm.comparisonType().isPresent())) {
                    throw new IllegalArgumentException("conditional IR match cannot carry guard/equality metadata");
                }
                if (mode == MatchMode.TRADITIONAL && !arm.wildcard()
                        && arm.comparisonType().isEmpty()) {
                    throw new IllegalArgumentException("traditional IR match pattern lacks equality type");
                }
                if (arm.wildcard() && arm.guard().isEmpty() && index != arms.size() - 1) {
                    throw new IllegalArgumentException("unconditional wildcard must be the final IR match arm");
                }
            }
            MatchArm fallback = arms.getLast();
            if (!fallback.wildcard() || fallback.guard().isPresent()) {
                throw new IllegalArgumentException("IR match requires a final unconditional wildcard");
            }
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitMatch(this);
        }
    }

    record DirectCall(
            SourceSpan span,
            LyraType type,
            Optional<ReferenceId> referenceId,
            Optional<DeclarationId> targetDeclaration,
            Optional<ModuleId> targetModule,
            Optional<ExportId> targetExport,
            Optional<AccessKind> accessKind,
            Optional<IrNode> receiver,
            List<IrNode> arguments,
            Optional<io.mindspice.lyra.compiler.semantic.flow.SummaryCallId> callId,
            Optional<FlowSiteId> siteId,
            Optional<CallableStorageRouteProof> storageRouteProof) implements IrNode {
        public DirectCall {
            requireSpanAndType(span, type);
            Objects.requireNonNull(referenceId, "referenceId");
            Objects.requireNonNull(targetDeclaration, "targetDeclaration");
            Objects.requireNonNull(targetModule, "targetModule");
            Objects.requireNonNull(targetExport, "targetExport");
            Objects.requireNonNull(accessKind, "accessKind");
            Objects.requireNonNull(receiver, "receiver");
            arguments = copy(arguments, "arguments");
            Objects.requireNonNull(callId, "callId");
            requireSite(siteId);
            Objects.requireNonNull(storageRouteProof, "storageRouteProof");
        }

        public DirectCall(
                SourceSpan span,
                LyraType type,
                Optional<ReferenceId> referenceId,
                Optional<DeclarationId> targetDeclaration,
                Optional<ModuleId> targetModule,
                Optional<ExportId> targetExport,
                Optional<AccessKind> accessKind,
                Optional<IrNode> receiver,
                List<IrNode> arguments) {
            this(span, type, referenceId, targetDeclaration, targetModule, targetExport,
                    accessKind, receiver, arguments, Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        public DirectCall(
                SourceSpan span,
                LyraType type,
                Optional<ReferenceId> referenceId,
                Optional<DeclarationId> targetDeclaration,
                Optional<ModuleId> targetModule,
                Optional<ExportId> targetExport,
                Optional<AccessKind> accessKind,
                Optional<IrNode> receiver,
                List<IrNode> arguments,
                Optional<io.mindspice.lyra.compiler.semantic.flow.SummaryCallId> callId,
                Optional<FlowSiteId> siteId) {
            this(span, type, referenceId, targetDeclaration, targetModule, targetExport,
                    accessKind, receiver, arguments, callId, siteId, Optional.empty());
        }

        /** Proof metadata is validated independently and is not a semantic lowering difference. */
        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof DirectCall call
                    && span.equals(call.span)
                    && type.equals(call.type)
                    && referenceId.equals(call.referenceId)
                    && targetDeclaration.equals(call.targetDeclaration)
                    && targetModule.equals(call.targetModule)
                    && targetExport.equals(call.targetExport)
                    && accessKind.equals(call.accessKind)
                    && receiver.equals(call.receiver)
                    && arguments.equals(call.arguments)
                    && callId.equals(call.callId)
                    && siteId.equals(call.siteId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(span, type, referenceId, targetDeclaration,
                    targetModule, targetExport, accessKind, receiver, arguments,
                    callId, siteId);
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitDirectCall(this);
        }
    }

    record CallableCall(
            SourceSpan span,
            LyraType type,
            IrNode target,
            List<IrNode> arguments,
            Optional<io.mindspice.lyra.compiler.semantic.flow.SummaryCallId> callId,
            Optional<FlowSiteId> siteId,
            Optional<CallableStorageRouteProof> storageRouteProof) implements IrNode {
        public CallableCall {
            requireSpanAndType(span, type);
            Objects.requireNonNull(target, "target");
            arguments = copy(arguments, "arguments");
            Objects.requireNonNull(callId, "callId");
            requireSite(siteId);
            Objects.requireNonNull(storageRouteProof, "storageRouteProof");
        }

        public CallableCall(SourceSpan span, LyraType type, IrNode target, List<IrNode> arguments) {
            this(span, type, target, arguments, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public CallableCall(SourceSpan span, LyraType type, IrNode target, List<IrNode> arguments,
                            Optional<io.mindspice.lyra.compiler.semantic.flow.SummaryCallId> callId,
                            Optional<FlowSiteId> siteId) {
            this(span, type, target, arguments, callId, siteId, Optional.empty());
        }

        /** Proof metadata is validated independently and is not a semantic lowering difference. */
        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof CallableCall call
                    && span.equals(call.span)
                    && type.equals(call.type)
                    && target.equals(call.target)
                    && arguments.equals(call.arguments)
                    && callId.equals(call.callId)
                    && siteId.equals(call.siteId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(span, type, target, arguments, callId, siteId);
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitCallableCall(this);
        }
    }

    record Lambda(
            SourceSpan span,
            LyraType type,
            Optional<LambdaId> lambdaId,
            Optional<LyraSignature> signature,
            List<CaptureId> captures,
            Optional<ScopeId> scopeId,
            Optional<DeclarationId> ownerDeclaration,
            IrNode body,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Lambda {
            requireSpanAndType(span, type);
            Objects.requireNonNull(lambdaId, "lambdaId");
            Objects.requireNonNull(signature, "signature");
            captures = copy(captures, "captures");
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(ownerDeclaration, "ownerDeclaration");
            Objects.requireNonNull(body, "body");
            requireSite(siteId);
        }

        public Lambda(
                SourceSpan span,
                LyraType type,
                Optional<LambdaId> lambdaId,
                Optional<LyraSignature> signature,
                List<CaptureId> captures,
                IrNode body) {
            this(span, type, lambdaId, signature, captures, Optional.empty(), Optional.empty(),
                    body, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitLambda(this);
        }
    }

    record Access(
            SourceSpan span,
            LyraType type,
            AccessKind accessKind,
            Optional<IrNode> receiver,
            Optional<ReferenceId> referenceId,
            Optional<DeclarationId> declarationId,
            Optional<ModuleId> moduleId,
            Optional<ExportId> exportId,
            Optional<String> memberName,
            Optional<BigInteger> tupleIndex,
            Optional<FlowSiteId> siteId) implements IrNode {
        public Access {
            requireSpanAndType(span, type);
            Objects.requireNonNull(accessKind, "accessKind");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(referenceId, "referenceId");
            Objects.requireNonNull(declarationId, "declarationId");
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(exportId, "exportId");
            Objects.requireNonNull(memberName, "memberName");
            Objects.requireNonNull(tupleIndex, "tupleIndex");
            requireSite(siteId);
        }

        public Access(
                SourceSpan span,
                LyraType type,
                AccessKind accessKind,
                Optional<IrNode> receiver,
                Optional<ReferenceId> referenceId,
                Optional<DeclarationId> declarationId,
                Optional<ModuleId> moduleId,
                Optional<ExportId> exportId,
                Optional<String> memberName,
                Optional<BigInteger> tupleIndex) {
            this(span, type, accessKind, receiver, referenceId, declarationId, moduleId,
                    exportId, memberName, tupleIndex, Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitAccess(this);
        }
    }

    record RuntimeCheck(
            SourceSpan span,
            LyraType type,
            IrCheckKind checkKind,
            String failureCode,
            IrNode operand,
            Optional<FlowSiteId> failureSiteId,
            Optional<FlowSiteId> siteId) implements IrNode {
        public RuntimeCheck {
            requireSpanAndType(span, type);
            Objects.requireNonNull(checkKind, "checkKind");
            Objects.requireNonNull(failureCode, "failureCode");
            if (failureCode.isEmpty()) {
                throw new IllegalArgumentException("runtime failure code must not be empty");
            }
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(failureSiteId, "failureSiteId");
            requireSite(siteId);
        }

        public RuntimeCheck(
                SourceSpan span,
                LyraType type,
                IrCheckKind checkKind,
                String failureCode,
                IrNode operand) {
            this(span, type, checkKind, failureCode, operand, Optional.empty(), Optional.empty());
        }

        @Override
        public <R> R accept(IrVisitor<R> visitor) {
            return Objects.requireNonNull(visitor, "visitor").visitRuntimeCheck(this);
        }
    }

    private static void requireSpanAndType(SourceSpan span, LyraType type) {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(type, "type");
    }

    private static void requireSite(Optional<FlowSiteId> siteId) {
        Objects.requireNonNull(siteId, "siteId");
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
