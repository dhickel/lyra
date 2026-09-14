package io.mindspice.lyra.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NominalConstructionTest {
    private static final ModuleId MODULE = ModuleId.path("objects.lyra");
    private static final NominalType TYPE = new NominalType(new NominalTypeId(MODULE, "a".repeat(64), "Record", 0));
    private static final NominalSchema SCHEMA = new NominalSchema(TYPE, NominalSchema.Kind.CLASS, List.of(
            new NominalSchema.Member("fixed", PrimitiveType.I32, true, false, false),
            new NominalSchema.Member("changing", PrimitiveType.I32, true, true, false),
            new NominalSchema.Member("hidden", PrimitiveType.I32, false, true, false)), List.of());

    private static LyraArtifactKey key() {
        return new LyraArtifactKey(OwnerThread.capture(), RuntimeIoEnvironment.defaults(), null, false, null,
                new NominalTypeEnvironment(List.of(SCHEMA)));
    }

    /** Typed representation fixture, not a source-to-JVM execution claim. */
    private static final class Value extends LyraNominalObject {
        private int fixed;
        private int changing;
        private int hidden;
        Value(LyraNominalConstruction construction) { super(construction, TYPE.canonicalSpelling()); }
        int get(int field) { checkPublicRead(field); return raw(field); }
        int generatedGet(LyraClosureAuthority caller, int field) { checkGeneratedRead(caller, field); return raw(field); }
        void set(int field, int value) { checkPublicWrite(field); store(field, value); }
        void generatedSet(LyraClosureAuthority caller, int field, int value) {
            checkGeneratedWrite(caller, field); store(field, value);
        }
        void initialize(LyraNominalConstruction ticket, int field, int value) {
            ticket.initializeField(this, field); store(field, value);
        }
        int initializedGet(LyraNominalConstruction ticket, int field) {
            ticket.checkRead(this, field); return raw(field);
        }
        private int raw(int field) { return switch (field) { case 0 -> fixed; case 1 -> changing; case 2 -> hidden; default -> throw new AssertionError(); }; }
        private void store(int field, int value) {
            switch (field) { case 0 -> fixed = value; case 1 -> changing = value; case 2 -> hidden = value; default -> throw new AssertionError(); }
        }
    }

    private static final class OtherValue extends LyraNominalObject {
        OtherValue(LyraNominalConstruction construction) { super(construction, TYPE.canonicalSpelling()); }
    }

    private static final NominalType EMPTY_TYPE = new NominalType(new NominalTypeId(MODULE, "a".repeat(64), "Empty", 0));
    private static final class Empty extends LyraNominalObject {
        Empty(LyraNominalConstruction construction) { super(construction, EMPTY_TYPE.canonicalSpelling()); }
    }

    @Test void zeroFieldStructStillRequiresAuthenticatedCompletion() {
        var emptySchema = new NominalSchema(EMPTY_TYPE, NominalSchema.Kind.STRUCT, List.of(), List.of());
        var key = new LyraArtifactKey(OwnerThread.capture(), RuntimeIoEnvironment.defaults(), null, false, null,
                new NominalTypeEnvironment(List.of(emptySchema)));
        var producer = ModuleLifecycle.forArtifact(MODULE, key);
        var ticket = LyraNominalConstruction.begin(producer.closureAuthority(), EMPTY_TYPE.canonicalSpelling(), Empty.class);
        var value = new Empty(ticket);
        assertThrows(LyraInitializationException.class, () -> LyraNominalSupport.requireAuthenticatedForGeneratedInvocation(
                value, producer.closureAuthority(), EMPTY_TYPE));
        ticket.complete(value);
        producer.open();
        assertSame(value, LyraNominalSupport.requireAuthenticated(value, producer.closureAuthority(), EMPTY_TYPE));
        producer.close();
        assertThrows(LyraClosedException.class, () -> LyraNominalConstruction.begin(
                producer.closureAuthority(), EMPTY_TYPE.canonicalSpelling(), Empty.class));
    }

    private static LyraNominalConstruction begin(ModuleLifecycle producer) {
        return LyraNominalConstruction.begin(producer.closureAuthority(), TYPE.canonicalSpelling(), Value.class);
    }

    @Test void constructionTracksExactReceiverFieldsAndPublication() {
        var producer = ModuleLifecycle.forArtifact(MODULE, key());
        var ticket = begin(producer);
        assertThrows(LyraLinkException.class, () -> new OtherValue(ticket));
        var value = new Value(ticket);
        assertEquals(TYPE, value.nominalType());
        assertSame(value, ticket.requireFieldValue(value, TYPE));
        assertThrows(LyraLinkException.class, () -> new Value(ticket));
        assertThrows(LyraInitializationException.class, () -> value.initializedGet(ticket, 0));
        assertThrows(LyraInitializationException.class, () -> ticket.complete(value));
        assertThrows(LyraLinkException.class, () -> value.initialize(ticket, -1, 9));
        assertThrows(LyraLinkException.class, () -> value.initialize(ticket, 3, 9));
        value.initialize(ticket, 0, 17);
        assertEquals(17, value.initializedGet(ticket, 0));
        assertThrows(LyraInitializationException.class, () -> value.initialize(ticket, 0, 23));
        value.initialize(ticket, 1, 31);
        value.initialize(ticket, 1, 37);
        value.initialize(ticket, 2, 41);
        assertThrows(LyraInitializationException.class, () -> value.generatedGet(producer.closureAuthority(), 0));
        assertThrows(LyraInitializationException.class, () -> LyraNominalSupport.requireAuthenticatedForGeneratedInvocation(
                value, producer.closureAuthority(), TYPE));
        ticket.complete(value);
        assertThrows(LyraLinkException.class, () -> ticket.requireFieldValue(value, TYPE));
        assertSame(value, LyraNominalSupport.requireAuthenticatedForGeneratedInvocation(value, producer.closureAuthority(), TYPE));
        assertEquals(17, value.generatedGet(producer.closureAuthority(), 0));
        assertThrows(LyraLinkException.class, () -> ticket.complete(value));
        assertThrows(LyraLinkException.class, () -> value.initialize(ticket, 1, 100));
        assertThrows(LyraLinkException.class, () -> ticket.fail(new IllegalStateException()));
        assertThrows(LyraLifecycleException.class, () -> value.get(0));
        producer.open();
        assertSame(value, LyraNominalSupport.requireAuthenticated(value, producer.closureAuthority(), TYPE));
        assertThrows(LyraLinkException.class, () -> LyraNominalSupport.requireAuthenticated(new Object(), producer.closureAuthority(), TYPE));
        assertThrows(LyraLinkException.class, () -> LyraNominalSupport.requireAuthenticated(null, producer.closureAuthority(), TYPE));
        var unknown = new NominalType(new NominalTypeId(MODULE, "b".repeat(64), "Record", 0));
        assertThrows(LyraLinkException.class, () -> LyraNominalSupport.requireAuthenticated(value, producer.closureAuthority(), unknown));
        assertEquals(37, value.get(1));
        value.set(1, 43);
        assertEquals(43, value.get(1));
        assertThrows(LyraLinkException.class, () -> value.set(0, 100));
        assertThrows(LyraLinkException.class, () -> value.get(2));
        value.generatedSet(producer.closureAuthority(), 2, 47);
        assertEquals(47, value.generatedGet(producer.closureAuthority(), 2));
        producer.close();
        assertThrows(LyraClosedException.class, () -> value.get(0));
    }

    @Test void constructionRejectsForeignAuthorityAndPreservesOwnerChecks() throws Exception {
        var shared = key();
        var producer = ModuleLifecycle.forArtifact(MODULE, shared);
        var otherInstance = ModuleLifecycle.forArtifact(MODULE, shared);
        var otherModule = ModuleLifecycle.forArtifact(ModuleId.path("other.lyra"), shared);
        var unrelated = ModuleLifecycle.forArtifact(MODULE, key());
        var ticket = begin(producer);
        var value = new Value(ticket);
        var otherTicket = begin(otherInstance);
        var incompleteOther = new Value(otherTicket);
        assertThrows(LyraInitializationException.class, () -> ticket.requireFieldValue(incompleteOther, TYPE));
        assertThrows(LyraLinkException.class, () -> otherTicket.initializeField(value, 0));
        assertThrows(LyraLinkException.class, () -> begin(otherModule));
        assertThrows(LyraLinkException.class, () -> LyraNominalConstruction.begin(producer.closureAuthority(),
                TYPE.canonicalSpelling(), LyraNominalObject.class));
        assertThrows(LyraLinkException.class, () -> LyraNominalConstruction.begin(producer.closureAuthority(),
                "Nominal<" + "0".repeat(64) + ">", Value.class));
        for (int field = 0; field < 3; field++) value.initialize(ticket, field, field + 1);
        ticket.complete(value);
        producer.open(); otherInstance.open(); otherModule.open(); unrelated.open();
        assertEquals(3, value.generatedGet(otherInstance.closureAuthority(), 2));
        assertEquals(2, value.generatedGet(otherModule.closureAuthority(), 1));
        assertThrows(LyraLinkException.class, () -> value.generatedGet(otherModule.closureAuthority(), 2));
        assertThrows(LyraLinkException.class, () -> value.generatedGet(unrelated.closureAuthority(), 0));
        assertThrows(LyraLinkException.class, () -> LyraNominalSupport.requireAuthenticated(value, unrelated.closureAuthority(), TYPE));
        assertSame(value, LyraNominalSupport.requireAuthenticated(value, otherInstance.closureAuthority(), TYPE));
        var failure = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { value.get(0); } catch (Throwable thrown) { failure.set(thrown); }
        });
        thread.start(); thread.join();
        assertInstanceOf(LyraThreadException.class, failure.get());
        otherTicket.fail(new IllegalStateException("unused"));
        producer.close(); otherInstance.close(); otherModule.close(); unrelated.close();
    }

    @Test void failedConstructionNeverPublishesEvenWhileModuleRemainsOpen() {
        var producer = ModuleLifecycle.forArtifact(MODULE, key());
        var ticket = begin(producer);
        var value = new Value(ticket);
        value.initialize(ticket, 0, 11);
        var cause = new IllegalArgumentException("constructor failed");
        ticket.fail(cause);
        producer.open();
        var failure = assertThrows(LyraInitializationException.class, () -> value.get(0));
        assertSame(cause, failure.getCause());
        assertThrows(LyraLinkException.class, () -> ticket.complete(value));
        assertThrows(LyraLinkException.class, () -> value.initialize(ticket, 1, 12));
        producer.close();
    }

    @Test void representationContractCannotBeRetypedByTheConstructionCaller() {
        var otherType = new NominalType(new NominalTypeId(MODULE, "a".repeat(64), "Other", 0));
        var otherSchema = new NominalSchema(otherType, NominalSchema.Kind.CLASS, SCHEMA.members(), List.of());
        var key = new LyraArtifactKey(OwnerThread.capture(), RuntimeIoEnvironment.defaults(), null, false, null,
                new NominalTypeEnvironment(List.of(SCHEMA, otherSchema)));
        var producer = ModuleLifecycle.forArtifact(MODULE, key);
        var wrong = LyraNominalConstruction.begin(producer.closureAuthority(), otherType.canonicalSpelling(), Value.class);
        assertThrows(LyraLinkException.class, () -> new Value(wrong));
        wrong.fail(new IllegalStateException("wrong representation contract"));
        producer.open(); producer.close();
    }

    @Test void initializationCapabilityChecksThreadAndTerminalProducerState() throws Exception {
        var producer = ModuleLifecycle.forArtifact(MODULE, key());
        var ticket = begin(producer);
        var value = new Value(ticket);
        var failure = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { value.initialize(ticket, 0, 9); } catch (Throwable thrown) { failure.set(thrown); }
        });
        thread.start(); thread.join();
        assertInstanceOf(LyraThreadException.class, failure.get());
        assertThrows(LyraInitializationException.class, () -> value.initializedGet(ticket, 0));
        producer.fail(new IllegalStateException("module failed"));
        assertThrows(LyraInitializationException.class, () -> value.initialize(ticket, 0, 9));
        assertThrows(LyraInitializationException.class, () -> ticket.complete(value));
        assertThrows(LyraInitializationException.class, () -> value.get(0));
    }

    @Test void seededInitializationAndMutationModel() {
        int cases = Integer.getInteger("lyra.fuzz.cases", 80);
        for (long seed : new long[] { 613, 20260912 }) {
            var random = new java.util.Random(seed);
            for (int index = 0; index < cases; index++) {
                String replay = "seed=" + seed + ", index=" + index;
                var producer = ModuleLifecycle.forArtifact(MODULE, key());
                var ticket = begin(producer);
                var value = new Value(ticket);
                var initialized = new boolean[3];
                var expected = new int[3];
                for (int step = 0; step < 18; step++) {
                    int field = random.nextInt(3);
                    int next = random.nextInt();
                    if (field == 0 && initialized[field]) {
                        assertThrows(LyraInitializationException.class, () -> value.initialize(ticket, field, next), replay);
                    } else {
                        value.initialize(ticket, field, next);
                        expected[field] = next; initialized[field] = true;
                    }
                    assertEquals(expected[field], value.initializedGet(ticket, field), replay);
                }
                for (int field = 0; field < 3; field++) if (!initialized[field]) value.initialize(ticket, field, 0);
                ticket.complete(value);
                assertSame(value, LyraNominalSupport.requireAuthenticatedForGeneratedInvocation(value, producer.closureAuthority(), TYPE), replay);
                producer.open();
                assertEquals(expected[0], value.get(0), replay);
                assertEquals(expected[1], value.get(1), replay);
                int next = random.nextInt();
                value.set(1, next);
                assertEquals(next, value.get(1), replay);
                producer.close();
                assertThrows(LyraClosedException.class, () -> value.get(1), replay);
            }
        }
    }
}
