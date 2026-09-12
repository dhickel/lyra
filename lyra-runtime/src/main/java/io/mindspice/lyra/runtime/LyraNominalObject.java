package io.mindspice.lyra.runtime;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Authority and initialization state for final, exact typed nominal classes.
 * This base owns no source field values and performs no reflective field access.
 */
public abstract class LyraNominalObject {
    private final LyraClosureAuthority authority;
    private final NominalSchema schema;
    private BitSet initialized;
    private boolean complete;
    private Throwable failure;

    protected LyraNominalObject(LyraNominalConstruction construction, String canonical) {
        Objects.requireNonNull(construction, "construction");
        if (!construction.schema().type().canonicalSpelling().equals(Objects.requireNonNull(canonical, "canonical"))) {
            throw new LyraLinkException("construction schema differs from the representation's nominal contract");
        }
        construction.bind(this);
        authority = construction.authority();
        schema = construction.schema();
        initialized = new BitSet(schema.members().size());
    }

    public final NominalType nominalType() { return schema.type(); }

    /** Authority used only by final generated subclasses at typed value boundaries. */
    protected final LyraClosureAuthority nominalAuthority() {
        authority.ensureCreationAllowed();
        return authority;
    }

    private NominalSchema.Member field(int index) {
        if (index < 0 || index >= schema.members().size()) {
            throw new LyraLinkException("nominal field index is outside its exact schema");
        }
        return schema.members().get(index);
    }

    private void requireComplete() {
        if (failure != null) throw new LyraInitializationException("nominal construction failed", List.of(), failure);
        if (!complete) throw new LyraInitializationException("nominal object is not fully initialized");
    }

    /** Java-facing generated getters must preserve declared public visibility. */
    protected final void checkPublicRead(int index) {
        authority.checkOpen();
        requireComplete();
        if (!field(index).publicAccess()) throw new LyraLinkException("nominal member is private");
    }

    /** Java-facing generated setters exist only for public mutable fields. */
    protected final void checkPublicWrite(int index) {
        checkPublicRead(index);
        requireMutable(index);
    }

    /** Compiler-certified lexical access, additionally confined to its producer domain. */
    protected final void checkGeneratedRead(LyraClosureAuthority caller, int index) {
        checkOwnership(caller, true);
        if (!field(index).publicAccess() && !caller.token().lifecycle().moduleId()
                .equals(authority.token().lifecycle().moduleId())) {
            throw new LyraLinkException("private nominal member belongs to another module producer");
        }
    }

    protected final void checkGeneratedWrite(LyraClosureAuthority caller, int index) {
        checkGeneratedRead(caller, index);
        requireMutable(index);
    }

    /** Validates both operands before generated cycle-safe struct traversal. */
    protected final void checkStructuralEquality(
            LyraClosureAuthority caller, LyraNominalObject other) {
        Objects.requireNonNull(other, "other");
        checkOwnership(caller, true);
        other.checkOwnership(caller, true);
        if (!schema.type().equals(other.schema.type()) || !schema.equals(other.schema)) {
            throw new LyraLinkException("struct equality requires one exact nominal schema");
        }
    }

    private void checkOwnership(LyraClosureAuthority caller, boolean generated) {
        Objects.requireNonNull(caller, "caller");
        if (generated) {
            caller.token().checkGeneratedInvocation();
            authority.token().checkGeneratedInvocation();
        } else {
            caller.checkOpen();
            authority.checkOpen();
        }
        requireComplete();
        if (!caller.token().sameArtifact(authority.token()) && !caller.token().sameSession(authority.token())) {
            throw new LyraLinkException("nominal object belongs to an unrelated artifact or session");
        }
    }

    void checkValueContract(LyraClosureAuthority caller, NominalType expected, boolean generated) {
        checkOwnership(caller, generated);
        if (!schema.type().equals(Objects.requireNonNull(expected, "expected"))
                || !schema.equals(caller.token().resolveNominalSchema(expected.canonicalSpelling()))) {
            throw new LyraLinkException("nominal object does not match the exact required schema");
        }
    }

    private void requireMutable(int index) {
        if (!field(index).mutable()) throw new LyraLinkException("nominal member is immutable");
    }

    void checkInitializationRead(int index) {
        field(index);
        if (!initialized.get(index)) throw new LyraInitializationException("nominal field is not initialized");
    }

    void initializeField(int index) {
        var member = field(index);
        if (initialized.get(index) && !member.mutable()) {
            throw new LyraInitializationException("immutable nominal field is already initialized");
        }
        initialized.set(index);
    }

    void completeInitialization() {
        if (initialized.cardinality() != schema.members().size()) {
            throw new LyraInitializationException("nominal construction has uninitialized fields");
        }
        complete = true;
        initialized = null;
    }

    void failInitialization(Throwable cause) {
        failure = cause;
        initialized = null;
    }
}
