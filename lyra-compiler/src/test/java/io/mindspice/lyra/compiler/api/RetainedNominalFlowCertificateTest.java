package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.semantic.TypedDeclaration;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact;
import io.mindspice.lyra.compiler.semantic.flow.NilProvenance;
import io.mindspice.lyra.compiler.semantic.flow.NominalObjectFact;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternative;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public certificate coverage for closed retained-nominal initializer proofs. */
final class RetainedNominalFlowCertificateTest {
    @Test
    void unsupportedInitializerCompositionReturnsStructuredSessionDiagnostic() {
        String source = "class C { let x :I32 = (+ 1 2) }";

        SessionCompileResult result = LyraCompiler.compileSession(
                new SessionCompileRequest("unsupported-transfer.lyra", source,
                        SessionSnapshot.empty()));

        SessionCompileResult.Failure failure = assertInstanceOf(
                SessionCompileResult.Failure.class, result);
        assertEquals(1, failure.diagnostics().size());
        var diagnostic = failure.diagnostics().getFirst();
        assertEquals(CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                diagnostic.code());
        assertEquals(SourceId.path("unsupported-transfer.lyra"),
                diagnostic.primarySpan().sourceId());
        assertEquals(source.indexOf("(+ 1 2)"), diagnostic.primarySpan().startOffset());
        assertEquals(source.indexOf("(+ 1 2)") + "(+ 1 2)".length(),
                diagnostic.primarySpan().endOffset());
        assertTrue(diagnostic.summary().contains("'x'"));
    }

    @Test
    void retainedCallableEffectsAreAttributedToTheConsumerConstructionSite() {
        SessionCompileResult.Success producer = compile("effect-producer.lyra", """
                import std->io
                let initialize :Fn<;I32> = (=> || { io->::println["retained"] 7 })
                class Box { let value :I32 = ::initialize[] }
                """, SessionSnapshot.empty());

        SessionCompileResult.Success consumer = compile(
                "effect-consumer.lyra", "let box :Box = Box[]", producer.stagedSnapshot());

        var effects = consumer.typedGraph().semanticFlowFacts().eagerEffectFacts();
        assertEquals(1, effects.size());
        var witness = effects.getFirst().witness();
        assertEquals(consumer.moduleGraph().rootModule(), witness.fromModule());
        assertEquals(SourceId.path("effect-consumer.lyra"),
                witness.sourcePath().getFirst().sourceId());
        assertEquals(SourceId.path("effect-producer.lyra"),
                witness.effectSpan().sourceId());
        assertEquals(witness.effectSpan(), witness.sourcePath().getLast());
        assertEquals(witness.effectSite().orElseThrow(),
                witness.sourceSitePath().getLast());
    }

    @Test
    void issuesClosedTransfersForEveryInitializedClassAndStructMember() {
        SessionCompileResult.Success producer = producer();
        SessionFlowCertificate certificate = certificate(producer);
        SessionFlowCertificate.RetainedNominal defaults = nominal(certificate, "Defaults");
        SessionFlowCertificate.RetainedNominal data = nominal(certificate, "Data");

        assertCoverage(defaults);
        assertCoverage(data);
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Value.class,
                transfer(defaults, "literal"));
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Reference.class,
                transfer(defaults, "alias"));
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Lambda.class,
                transfer(defaults, "lambda"));
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                transfer(defaults, "called"));
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Value.class,
                transfer(data, "array"));
        assertTrue(memberInitializer(data, "required").isEmpty());
    }

    @Test
    void lambdaAndDirectCallTransfersKeepTypedGraphIdentities() {
        SessionCompileResult.Success producer = producer();
        SessionFlowCertificate.RetainedNominal defaults = nominal(certificate(producer), "Defaults");
        TypedDeclaration lambda = member(producer, defaults, "lambda");
        TypedDeclaration called = member(producer, defaults, "called");
        TypedExpression callExpression = called.initializer().orElseThrow();

        var lambdaTransfer = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Lambda.class,
                transfer(defaults, "lambda"));
        var callTransfer = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                transfer(defaults, "called"));
        assertEquals(lambda.initializerLambda().orElseThrow(), lambdaTransfer.lambda());
        assertEquals(callExpression.link().orElseThrow().declarationId().orElseThrow(),
                callTransfer.target());
        assertEquals(callExpression.children().size(), callTransfer.function().arity());
    }

    @Test
    void nilCertificationAcceptsOnlyExactProducerProvenance() {
        SessionCompileResult.Success producer = compile("nil-proof.lyra",
                "class Maybe { let @nil value :I32 = #NIL }", SessionSnapshot.empty());
        SessionFlowCertificate certificate = certificate(producer);
        var value = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Value.class,
                transfer(nominal(certificate, "Maybe"), "value"));
        NilProvenance nil = value.value().alternatives().getFirst()
                .nilProvenance().getFirst();
        NilProvenance foreign = new NilProvenance(
                nil.sourceSite(), SourceSpan.at(SourceId.path("foreign.lyra"), 0),
                nil.route());

        assertTrue(certificate.certifiesNil(nil));
        assertFalse(certificate.certifiesNil(foreign));
    }

    @Test
    void arrayLiteralTransferKeepsProducerModuleAndFlowSiteIdentity() {
        SessionCompileResult.Success producer = producer();
        SessionFlowCertificate.RetainedNominal data = nominal(certificate(producer), "Data");
        TypedExpression initializer = member(producer, data, "array").initializer().orElseThrow();
        var value = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Value.class,
                transfer(data, "array"));

        List<AggregateIdentityFact> facts = value.value().alternatives().stream()
                .flatMap(alternative -> alternative.aggregateIdentities().stream()).toList();
        assertFalse(facts.isEmpty());
        assertTrue(facts.stream().anyMatch(fact ->
                fact.identity().ownerModule().equals(producer.moduleGraph().rootModule())
                        && fact.witness().originSite().equals(
                        Optional.of(producer.typedGraph().flowSiteId(initializer)))));
    }

    @Test
    void laterConstructionCarriesProducerInitializerTransfersForward() {
        SessionCompileResult.Success first = producer();
        String canonical = nominal(certificate(first), "Defaults").nominal().schema().type()
                .canonicalSpelling();
        List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> expected =
                certificate(first).retainedNominals().get(canonical).memberInitializers();
        SessionCompileResult.Success second = compile("second.lyra", "let value :Defaults = Defaults[]",
                first.stagedSnapshot());
        SessionCompileResult.Success third = compile("third.lyra", "let value :Defaults = Defaults[]",
                second.stagedSnapshot());

        assertEquals(expected, certificate(third).retainedNominals().get(canonical)
                .memberInitializers());
    }

    @Test
    void certificateCollectionsAndTransferRecordsAreDeeplyImmutable() {
        SessionCompileResult.Success producer = producer();
        SessionFlowCertificate certificate = certificate(producer);
        SessionFlowCertificate.RetainedNominal defaults = nominal(certificate, "Defaults");
        var call = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                transfer(defaults, "called"));
        Map<String, SessionFlowCertificate.RetainedNominal> nominals = certificate.retainedNominals();
        List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> initializers =
                defaults.memberInitializers();
        var allocations = certificate.allocationProvenances();

        assertThrows(UnsupportedOperationException.class, nominals::clear);
        assertThrows(UnsupportedOperationException.class, () -> initializers.clear());
        assertThrows(UnsupportedOperationException.class, allocations::clear);
        allocations.forEach((site, value) ->
                assertEquals(value, certificate.allocationProvenance(site).orElseThrow()));
        assertThrows(UnsupportedOperationException.class,
                () -> call.arguments().add(call.arguments().getFirst()));

        List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> suppliedInitializers =
                new ArrayList<>(initializers);
        SessionFlowCertificate.RetainedNominal copiedNominal =
                new SessionFlowCertificate.RetainedNominal(defaults.name(), defaults.nominal(),
                        defaults.visibility(), defaults.constructorLambda(), suppliedInitializers);
        List<SessionFlowCertificate.RetainedInitializerTransfer> suppliedArguments =
                new ArrayList<>(call.arguments());
        List<Optional<io.mindspice.lyra.compiler.semantic.flow.WriteTarget>> suppliedTargets =
                new ArrayList<>(call.argumentTargets());
        var copiedCall = new SessionFlowCertificate.RetainedInitializerTransfer.Call(
                call.target(), call.function(), suppliedArguments, suppliedTargets);
        suppliedInitializers.clear();
        suppliedArguments.clear();
        suppliedTargets.clear();

        assertEquals(initializers, copiedNominal.memberInitializers());
        assertEquals(call.arguments(), copiedCall.arguments());
        assertEquals(call.argumentTargets(), copiedCall.argumentTargets());
        assertEquals(allocations, certificate.allocationProvenances());
    }

    @Test
    void closedProofRecordsRejectInvalidConstruction() {
        SessionCompileResult.Success producer = producer();
        SessionFlowCertificate certificate = certificate(producer);
        SessionFlowCertificate.RetainedNominal defaults = nominal(certificate, "Defaults");
        var provenance = certificate.allocationProvenances().values().stream().findFirst().orElseThrow();
        var call = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                transfer(defaults, "called"));
        List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> absent =
                new ArrayList<>(defaults.memberInitializers());
        absent.set(0, Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedNominal(defaults.name(), defaults.nominal(),
                        defaults.visibility(), defaults.constructorLambda(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedNominal(defaults.name(), defaults.nominal(),
                        defaults.visibility(), defaults.constructorLambda(), absent));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedNominal("Other", defaults.nominal(),
                        defaults.visibility(), defaults.constructorLambda(),
                        defaults.memberInitializers()));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.AllocationProvenance(provenance.moduleId(),
                        provenance.scopeId(), SourceSpan.at(SourceId.path("foreign.lyra"), 0),
                        provenance.originSite(), provenance.arrayType(), provenance.allocation()));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Call(call.target(),
                        call.function(), List.of(), call.argumentTargets()));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                        ValueAlternatives.empty()));
    }

    @Test
    void certifiedProofPredicatesRejectForgedWitnessesAndUseSites() {
        SessionCompileResult.Success producer = compile("holder.lyra", """
                class Holder { let @pub values :Array<I32> = Array<I32>[1 2] }
                let @mut holder :Holder = Holder[]
                """, SessionSnapshot.empty());
        SessionFlowCertificate certificate = certificate(producer);
        List<ValueAlternative> values = new ArrayList<>();
        certificate.boundaryState().bindings().values().forEach(value ->
                values.addAll(value.alternatives().alternatives()));
        certificate.boundaryState().objects().values().forEach(object ->
                object.fields().values().forEach(field ->
                        values.addAll(field.alternatives())));
        NominalObjectFact object = values.stream().flatMap(value -> value.objects().stream())
                .findFirst().orElseThrow();
        AggregateIdentityFact aggregate = values.stream()
                .flatMap(value -> value.aggregateIdentities().stream())
                .findFirst().orElseThrow();

        assertTrue(certificate.certifiesObject(object));
        OwnershipWitness objectWitness = object.ownership();
        NominalObjectFact forgedObject = new NominalObjectFact(object.identity(), object.route(),
                OwnershipWitness.local(objectWitness.ownerModule(),
                                new DeclarationId(objectWitness.originDeclaration().ordinal() + 1),
                                objectWitness.scopeId(), objectWitness.sourceSpan())
                        .withOriginSite(objectWitness.originSite().orElseThrow()));
        assertFalse(certificate.certifiesObject(forgedObject));

        assertTrue(certificate.certifiesAggregate(aggregate));
        assertTrue(certificate.certifiesAggregateUse(aggregate));
        AggregateIdentityFact foreignUse = new AggregateIdentityFact(aggregate.identity(),
                aggregate.route(), aggregate.witness().atUse(
                        SourceSpan.at(SourceId.path("foreign.lyra"), 0)));
        assertTrue(certificate.certifiesAggregate(foreignUse));
        assertFalse(certificate.certifiesAggregateUse(foreignUse));

        // A use span that names a real certified generation source but is not a
        // certified use site must not be accepted either.
        SourceId certifiedSource = aggregate.witness().sourceSpan().sourceId();
        assertTrue(certificate.containsSourceId(certifiedSource));
        AggregateIdentityFact inventedUse = new AggregateIdentityFact(aggregate.identity(),
                aggregate.route(), aggregate.witness().atUse(
                        SourceSpan.at(certifiedSource, 999)));
        assertTrue(certificate.certifiesAggregate(inventedUse));
        assertFalse(certificate.certifiesAggregateUse(inventedUse));
    }

    private static SessionCompileResult.Success producer() {
        return compile("producer.lyra", """
                let increment :Fn<I32;I32> = (=> |value| (+ value 1))
                class Defaults {
                    let literal :I32 = 7
                    let alias :I32 = self:.literal
                    let lambda :Fn<;Array<I32>> = (=> || Array<I32>[3])
                    let called :I32 = ::increment[4]
                }
                struct Data {
                    let required :I32
                    let array :Array<I32> = Array<I32>[1 2]
                }
                """, SessionSnapshot.empty());
    }

    private static SessionCompileResult.Success compile(
            String label, String source, SessionSnapshot snapshot) {
        SessionCompileResult result = LyraCompiler.compileSession(
                new SessionCompileRequest(label, source, snapshot));
        return assertInstanceOf(SessionCompileResult.Success.class, result,
                result.diagnostics().toString());
    }

    private static SessionFlowCertificate certificate(SessionCompileResult.Success compiled) {
        return compiled.flowCertificate();
    }

    private static SessionFlowCertificate.RetainedNominal nominal(
            SessionFlowCertificate certificate, String name) {
        return certificate.retainedNominals().values().stream()
                .filter(value -> value.name().equals(name)).findFirst().orElseThrow();
    }

    private static TypedDeclaration member(SessionCompileResult.Success compiled,
            SessionFlowCertificate.RetainedNominal nominal, String name) {
        return nominal.nominal().members().stream().map(compiled.typedGraph()::declaration)
                .flatMap(Optional::stream).filter(value -> value.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static SessionFlowCertificate.RetainedInitializerTransfer transfer(
            SessionFlowCertificate.RetainedNominal nominal, String memberName) {
        return memberInitializer(nominal, memberName).orElseThrow();
    }

    private static Optional<SessionFlowCertificate.RetainedInitializerTransfer> memberInitializer(
            SessionFlowCertificate.RetainedNominal nominal, String memberName) {
        for (int index = 0; index < nominal.nominal().members().size(); index++) {
            if (memberName.equals(nominal.nominal().schema().members().get(index).name())) {
                return nominal.memberInitializer(index);
            }
        }
        throw new IllegalArgumentException("unknown member: " + memberName);
    }

    private static void assertCoverage(SessionFlowCertificate.RetainedNominal nominal) {
        assertEquals(nominal.nominal().schema().members().size(),
                nominal.memberInitializers().size());
        for (int index = 0; index < nominal.memberInitializers().size(); index++) {
            assertEquals(nominal.nominal().schema().members().get(index).hasInitializer(),
                    nominal.memberInitializer(index).isPresent());
        }
    }
}
