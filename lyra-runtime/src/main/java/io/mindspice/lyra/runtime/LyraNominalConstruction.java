package io.mindspice.lyra.runtime;

import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Single-use initialization capability for a generated typed object factory.
 * Holds no field values; those live only in the exact generated representation.
 */
public final class LyraNominalConstruction {
    private final LyraClosureAuthority authority;
    private final NominalSchema schema;
    private final Class<? extends LyraNominalObject> representation;
    private LyraNominalObject receiver;
    private boolean consumed;

    private LyraNominalConstruction(LyraClosureAuthority authority, NominalSchema schema,
                                   Class<? extends LyraNominalObject> representation) {
        this.authority = authority;
        this.schema = schema;
        this.representation = representation;
    }

    /** Called by the declaring module's generated factory, never by a raw constructor. */
    public static LyraNominalConstruction begin(LyraClosureAuthority authority, String canonical,
                                                Class<? extends LyraNominalObject> representation) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(representation, "representation");
        NominalSchema schema = authority.token().resolveNominalSchema(canonical);
        if (!authority.token().lifecycle().moduleId().filter(schema.type().id().module()::equals).isPresent()) {
            throw new LyraLinkException("nominal construction requires its declaring module producer");
        }
        if (!Modifier.isFinal(representation.getModifiers())
                || representation.getSuperclass() != LyraNominalObject.class) {
            throw new LyraLinkException("nominal representation must be a final direct object subclass");
        }
        return new LyraNominalConstruction(authority, schema, representation);
    }

    void bind(LyraNominalObject value) {
        authority.ensureCreationAllowed();
        if (consumed || receiver != null || value.getClass() != representation) {
            throw new LyraLinkException("construction capability has a different or already bound receiver");
        }
        receiver = value;
    }

    LyraClosureAuthority authority() { return authority; }
    NominalSchema schema() { return schema; }

    private void checkReceiver(LyraNominalObject value) {
        authority.ensureCreationAllowed();
        if (consumed || receiver == null || receiver != value) {
            throw new LyraLinkException("construction capability is not active for this receiver");
        }
    }

    /** Checks a default/constructor read of an already initialized field. */
    public void checkRead(LyraNominalObject value, int field) {
        checkReceiver(value);
        value.checkInitializationRead(field);
    }

    /** A receiver may refer to itself internally before publication; other values must be complete. */
    public LyraNominalObject requireFieldValue(Object candidate, NominalType expected) {
        checkReceiver(receiver);
        Objects.requireNonNull(expected, "expected");
        if (candidate == receiver && schema.type().equals(expected)) return receiver;
        return LyraNominalSupport.requireAuthenticatedForGeneratedInvocation(candidate, authority, expected);
    }

    /**
     * Generated initialization validates/evaluates the value first, calls this
     * method, then immediately stores the exact typed field without calling user
     * code in between. Any exceptional factory exit must call {@link #fail}.
     */
    public void initializeField(LyraNominalObject value, int field) {
        checkReceiver(value);
        value.initializeField(field);
    }

    /** Publishes only a receiver whose entire schema has been initialized. */
    public void complete(LyraNominalObject value) {
        checkReceiver(value);
        value.completeInitialization();
        consumed = true;
        receiver = null;
    }

    /** Invalidates partial construction; never changes an already completed object. */
    public void fail(Throwable cause) {
        authority.ensureCreationAllowed();
        Objects.requireNonNull(cause, "cause");
        if (consumed) throw new LyraLinkException("construction capability has already been consumed");
        if (receiver != null) receiver.failInitialization(cause);
        consumed = true;
        receiver = null;
    }
}
