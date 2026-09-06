package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.runtime.ArrayType;
import io.mindspice.lyra.runtime.BindingMutability;
import io.mindspice.lyra.runtime.FunctionType;
import io.mindspice.lyra.runtime.PrimitiveType;
import io.mindspice.lyra.runtime.QualifiedType;
import io.mindspice.lyra.runtime.TupleType;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplContractsTest {
    @Test
    void sourceOriginMapsDecodedUtf16OffsetsWithoutTreatingSurrogatePairsAsOneOffset() {
        String text = "a😀b";
        SourceOrigin origin = new SourceOrigin(
                "editor-buffer",
                Optional.of(URI.create("file:///workspace/demo.lyra")),
                Optional.of(9L),
                20,
                24);
        EvaluationSource source = new EvaluationSource(origin, text);

        assertEquals(4, text.length());
        assertEquals(20, source.mapOriginOffset(0));
        assertEquals(22, source.mapOriginOffset(2));
        assertEquals(new Utf16Range(21, 24), source.mapOriginRange(new Utf16Range(1, 4)));
        assertEquals(Optional.of(9L), origin.documentVersion());
        assertEquals("file:///workspace/demo.lyra", origin.uri().orElseThrow().toString());

        assertThrows(IllegalArgumentException.class,
                () -> source.mapOriginOffset(5));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationSource(origin, "short"));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationSource(SourceOrigin.forText("repl", 1), "\uD800"));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceOrigin("editor", Optional.empty(), Optional.of(-1L), 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceOrigin("editor", Optional.empty(), Optional.empty(), 4, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceOrigin("editor", Optional.of(URI.create("relative")),
                        Optional.empty(), 0, 0));
    }

    @Test
    void requestIdentityRevisionAndTerminalResultsAreImmutableAndStatusSafe() {
        EvaluationId id = EvaluationId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        EvaluationSource source = EvaluationSource.of("repl.lyra", "count");
        EvaluationRequest request = new EvaluationRequest(id, SessionRevision.initial(), source);
        Diagnostic error = error(source);

        EvaluationResult.Success success = new EvaluationResult.Success(
                request, request.revision().next(), Optional.empty(), List.of());
        assertEquals(EvaluationStatus.SUCCESS, success.status());
        assertTrue(success.isTerminal());
        assertTrue(success.value().isEmpty());
        assertEquals(List.of(), success.diagnostics());

        EvaluationResult.CompilationFailure failure = new EvaluationResult.CompilationFailure(
                request, request.revision(), List.of(error));
        assertEquals(EvaluationStatus.COMPILATION_FAILURE, failure.status());
        assertThrows(UnsupportedOperationException.class, () -> failure.diagnostics().clear());

        EvaluationResult.RuntimeFailure runtimeFailure = new EvaluationResult.RuntimeFailure(
                request, request.revision(), "runtime failed", List.of());
        assertEquals(Optional.of("runtime failed"), runtimeFailure.failureSummary());

        Cancellation observed = Cancellation.requested(id).observe();
        EvaluationResult.Cancelled cancelled = new EvaluationResult.Cancelled(
                request, request.revision(), observed);
        assertEquals(EvaluationStatus.CANCELLED, cancelled.status());
        assertEquals(Optional.of(observed), cancelled.cancellation());

        EvaluationResult.Busy busy = new EvaluationResult.Busy(
                request, request.revision(), Optional.of(EvaluationId.create()));
        assertEquals(EvaluationStatus.BUSY, busy.status());
        EvaluationResult.Closed closed = new EvaluationResult.Closed(
                request, request.revision(), SessionLifecycleState.CLOSED);
        assertEquals(EvaluationStatus.CLOSED, closed.status());

        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationResult.Success(request, request.revision(), Optional.empty(), List.of(error)));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationResult.CompilationFailure(request, request.revision(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationResult.Cancelled(
                        request, request.revision(), Cancellation.requested(id)));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationResult.Cancelled(
                        request, request.revision(), Cancellation.observed(EvaluationId.create())));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationResult.Busy(request, request.revision(), Optional.of(id)));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationResult.Closed(
                        request, request.revision(), SessionLifecycleState.OPEN));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationResult.Success(
                        request, new SessionRevision(-1), Optional.empty(), List.of()));
    }

    @Test
    void cancellationAndLifecycleStatesDoNotExposeMutableControlObjects() {
        EvaluationId id = EvaluationId.create();
        Cancellation requested = Cancellation.requested(id);
        Cancellation observed = requested.observe();

        assertEquals(CancellationState.REQUESTED, requested.state());
        assertEquals(CancellationState.OBSERVED, observed.state());
        assertNotSame(requested, observed);
        assertThrows(IllegalStateException.class, observed::observe);
        assertTrue(SessionLifecycleState.OPEN.acceptsEvaluation());
        assertFalse(SessionLifecycleState.CLOSED.acceptsEvaluation());
        assertTrue(SessionLifecycleState.FAILED.isTerminal());
    }

    @Test
    void bindingAndWorkspaceMetadataCopyCollectionsAndPreserveIdentityContracts() {
        BindingMetadata immutable = new BindingMetadata(
                "answer",
                new BindingIdentity(1),
                PrimitiveType.I32,
                BindingVisibility.PRIVATE,
                BindingMutability.IMMUTABLE,
                Optional.empty());
        BindingMetadata mutable = new BindingMetadata(
                "counter",
                new BindingIdentity(2),
                PrimitiveType.I32,
                BindingVisibility.PUBLIC,
                BindingMutability.MUTABLE,
                Optional.of(new StorageIdentity(7)));
        String revision = "a".repeat(64);
        Map<String, BindingMetadata> bindings = new HashMap<>();
        bindings.put("answer", immutable);
        bindings.put("counter", mutable);
        Map<String, String> modules = new HashMap<>();
        modules.put("std/io", revision);

        WorkspaceState.Committed committed = new WorkspaceState.Committed(
                SessionRevision.initial(), bindings, modules);
        bindings.clear();
        modules.clear();

        assertEquals(2, committed.bindings().size());
        assertEquals(Map.of("std/io", revision), committed.moduleRevisions());
        assertThrows(UnsupportedOperationException.class, () -> committed.bindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> committed.moduleRevisions().clear());
        assertEquals("I32", mutable.canonicalType());
        assertTrue(mutable.isMutable());
        assertTrue(mutable.isPublic());
        assertEquals(Optional.of(new StorageIdentity(7)), mutable.storageIdentity());

        WorkspaceState.Pending pending = new WorkspaceState.Pending(
                SessionRevision.initial(), committed.revision().next(),
                committed.bindings(), committed.moduleRevisions());
        assertTrue(pending.isPending());
        assertFalse(pending.isCommitted());
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceState.Pending(
                        committed.revision().next(), SessionRevision.initial(),
                        Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BindingMetadata(
                        "answer", new BindingIdentity(3), PrimitiveType.I32,
                        BindingVisibility.PRIVATE, BindingMutability.IMMUTABLE,
                        Optional.of(new StorageIdentity(3))));
        assertThrows(IllegalArgumentException.class,
                () -> new BindingMetadata(
                        "counter", new BindingIdentity(4), PrimitiveType.I32,
                        BindingVisibility.PUBLIC, BindingMutability.MUTABLE,
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new BindingMetadata(
                        "bad", new BindingIdentity(5), QualifiedType.mutable(PrimitiveType.I32),
                        BindingVisibility.PRIVATE, BindingMutability.IMMUTABLE,
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceState.Committed(
                        SessionRevision.initial(), Map.of("wrong", immutable), Map.of()));
    }

    @Test
    void snapshotsPreserveTypedDataOrderingAndBoundedTruncation() {
        ValueSnapshot first = ValueSnapshot.scalar(PrimitiveType.I32, ScalarKind.SIGNED_INTEGER, "1");
        ValueSnapshot second = ValueSnapshot.scalar(PrimitiveType.I32, ScalarKind.SIGNED_INTEGER, "2");
        ArrayType arrayType = new ArrayType(PrimitiveType.I32);
        List<ValueSnapshot> elements = new ArrayList<>(List.of(first, second));
        ValueSnapshot array = new ValueSnapshot(
                arrayType,
                new ValueSnapshot.Aggregate(
                        AggregateKind.ARRAY,
                        "array#1",
                        Optional.of("numbers"),
                        elements,
                        Optional.empty()),
                new SnapshotLimits(2, 2, 1024));
        elements.clear();

        assertEquals("Array<I32>", array.canonicalType());
        ValueSnapshot.Aggregate aggregate = assertInstanceOf(
                ValueSnapshot.Aggregate.class, array.data());
        assertEquals(List.of(first, second), aggregate.elements());
        assertEquals(Optional.of("numbers"), aggregate.alias());
        assertThrows(UnsupportedOperationException.class, () -> aggregate.elements().clear());
        assertEquals(2, aggregate.elements().size());

        ValueSnapshot unsigned = ValueSnapshot.scalar(
                PrimitiveType.U64, ScalarKind.UNSIGNED_INTEGER, "18446744073709551615");
        ValueSnapshot string = ValueSnapshot.scalar(
                PrimitiveType.STRING, ScalarKind.STRING, "line\n\uD800");
        ValueSnapshot nil = ValueSnapshot.nil(QualifiedType.nilable(PrimitiveType.STRING));
        ValueSnapshot unit = ValueSnapshot.unit(PrimitiveType.UNIT);
        ValueSnapshot function = ValueSnapshot.of(
                new FunctionType(List.of(PrimitiveType.I32), PrimitiveType.STRING),
                new ValueSnapshot.Function("closure#1", Optional.of("Fn< I32;String >")));
        assertEquals("18446744073709551615", ((ValueSnapshot.Scalar) unsigned.data()).value());
        assertEquals("line\n\uD800", ((ValueSnapshot.Scalar) string.data()).value());
        assertTrue(nil.type().isNilable());
        assertInstanceOf(ValueSnapshot.Unit.class, unit.data());
        assertEquals("Fn<I32;String>", function.canonicalType());
        assertTrue(string.renderedCharacterCount() > string.type().canonicalSpelling().length());

        ValueSnapshot truncated = new ValueSnapshot(
                new ArrayType(PrimitiveType.I32),
                new ValueSnapshot.Aggregate(
                        AggregateKind.ARRAY,
                        "array#large",
                        List.of(),
                        Optional.of(TruncationReason.AGGREGATE_ELEMENTS)),
                new SnapshotLimits(0, 0, 128));
        assertTrue(truncated.isTruncated());
        assertTrue(truncated.containsTruncation());
        assertEquals(TruncationReason.AGGREGATE_ELEMENTS,
                truncated.data() instanceof ValueSnapshot.Aggregate value
                        ? value.truncation().orElseThrow()
                        : null);

        assertThrows(IllegalArgumentException.class,
                () -> ValueSnapshot.scalar(PrimitiveType.U64, ScalarKind.UNSIGNED_INTEGER, "-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new ValueSnapshot(
                        arrayType,
                        new ValueSnapshot.Aggregate(
                                AggregateKind.ARRAY, "array#too-deep", List.of(first)),
                        new SnapshotLimits(0, 1, 1024)));
        assertThrows(IllegalArgumentException.class,
                () -> ValueSnapshot.scalar(
                        PrimitiveType.STRING, ScalarKind.STRING, "this does not fit",
                        new SnapshotLimits(1, 1, 8)));
    }

    @Test
    void snapshotValidationRejectsWrongShapesAndCopiesNestedLists() {
        TupleType tupleType = new TupleType(List.of(PrimitiveType.I32, PrimitiveType.STRING));
        ValueSnapshot tuple = new ValueSnapshot(
                tupleType,
                new ValueSnapshot.Aggregate(
                        AggregateKind.TUPLE,
                        "tuple#1",
                        List.of(
                                ValueSnapshot.scalar(PrimitiveType.I32, ScalarKind.SIGNED_INTEGER, "4"),
                                ValueSnapshot.scalar(PrimitiveType.STRING, ScalarKind.STRING, "ok"))),
                new SnapshotLimits(3, 10, 1024));
        assertEquals(2, ((ValueSnapshot.Aggregate) tuple.data()).elements().size());

        assertThrows(IllegalArgumentException.class,
                () -> new ValueSnapshot(
                        tupleType,
                        new ValueSnapshot.Aggregate(
                                AggregateKind.TUPLE, "tuple#bad", List.of(
                                        ValueSnapshot.scalar(PrimitiveType.STRING, ScalarKind.STRING, "wrong"),
                                        ValueSnapshot.scalar(PrimitiveType.STRING, ScalarKind.STRING, "ok")))));
        assertThrows(IllegalArgumentException.class,
                () -> new ValueSnapshot(
                        tupleType,
                        new ValueSnapshot.Aggregate(
                                AggregateKind.TUPLE, "tuple#short", List.of(
                                        ValueSnapshot.scalar(PrimitiveType.I32, ScalarKind.SIGNED_INTEGER, "4")))));
        assertThrows(IllegalArgumentException.class,
                () -> new ValueSnapshot(
                        PrimitiveType.I32,
                        new ValueSnapshot.Nil()));
        assertThrows(IllegalArgumentException.class,
                () -> new ValueSnapshot(
                        new ArrayType(PrimitiveType.I32),
                        new ValueSnapshot.Function("not-a-function")));
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotLimits(-1, 1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotLimits(1, 1, 0));
    }

    @Test
    void snapshotsRequireUtf16CharactersCanonicalFloatsAndProtectedImports() {
        assertThrows(IllegalArgumentException.class,
                () -> ValueSnapshot.scalar(
                        PrimitiveType.CHAR, ScalarKind.CHARACTER, "😀"));
        ValueSnapshot unpaired = ValueSnapshot.scalar(
                PrimitiveType.CHAR, ScalarKind.CHARACTER, "\uD800");
        assertEquals("\uD800", ((ValueSnapshot.Scalar) unpaired.data()).value());

        assertEquals("1.0", ((ValueSnapshot.Scalar) ValueSnapshot.scalar(
                PrimitiveType.F64, ScalarKind.FLOAT, "1.0").data()).value());
        assertEquals("1.0e-4", ((ValueSnapshot.Scalar) ValueSnapshot.scalar(
                PrimitiveType.F64, ScalarKind.FLOAT, "1.0e-4").data()).value());
        assertThrows(IllegalArgumentException.class,
                () -> ValueSnapshot.scalar(PrimitiveType.F64, ScalarKind.FLOAT, "01.0"));
        assertThrows(IllegalArgumentException.class,
                () -> ValueSnapshot.scalar(PrimitiveType.F64, ScalarKind.FLOAT, "1E+00"));
        assertThrows(IllegalArgumentException.class,
                () -> ValueSnapshot.scalar(PrimitiveType.F32, ScalarKind.FLOAT, "1.0f"));

        assertThrows(IllegalArgumentException.class,
                () -> new BindingMetadata(
                        "imported", new BindingIdentity(8), PrimitiveType.I32,
                        BindingVisibility.IMPORTED, BindingMutability.MUTABLE,
                        Optional.of(new StorageIdentity(8))));
    }

    private static Diagnostic error(EvaluationSource source) {
        return Diagnostic.error(
                CompilerDiagnosticCodes.PARSE_UNEXPECTED_TOKEN,
                SourceSpan.of(SourceId.path(source.origin().label()), 0, 0),
                "invalid source");
    }
}
