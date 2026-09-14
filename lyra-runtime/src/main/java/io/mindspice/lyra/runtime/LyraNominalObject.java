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
    private final SessionStorageDomain.NominalAnchor anchor;
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
        anchor = SessionStorageDomain.NominalAnchor.of(authority.token());
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
        if (caller.token().sameArtifact(authority.token())) return;
        if (anchor != null && anchor.authenticates(caller.token())) return;
        if (caller.token().sameSession(authority.token())) return;
        throw new LyraLinkException("nominal object belongs to an unrelated artifact or session");
    }

    void checkValueContract(LyraClosureAuthority caller, NominalType expected, boolean generated) {
        checkOwnership(caller, generated);
        if (!schema.type().equals(Objects.requireNonNull(expected, "expected"))
                || !schema.equals(caller.token().resolveNominalSchema(expected.canonicalSpelling()))) {
            throw new LyraLinkException("nominal object does not match the exact required schema");
        }
    }

    private LyraSignature callableFieldSignature(int index) {
        LyraType type = field(index).type().baseType();
        if (!(type instanceof FunctionType function)) {
            throw new LyraLinkException("delegated nominal member is not callable");
        }
        return function.signature();
    }

    private void requireMutable(int index) {
        if (!field(index).mutable()) throw new LyraLinkException("nominal member is immutable");
    }

    void checkInitializationRead(int index) {
        field(index);
        if (!initialized.get(index)) throw new LyraInitializationException("nominal field is not initialized");
    }

    /**
     * Issues one opaque, single-use occurrence route after the generated
     * getter has authenticated the exact object and selected its field value.
     * Existing delegates are normalized to their one raw selected closure and
     * immutable route dependencies; no field is re-read.
     */
    protected final Object issueCallableMemberRoute(LyraClosureAuthority caller, int index,
                                                     Object selected, String canonicalSignature) {
        checkGeneratedRead(Objects.requireNonNull(caller, "caller"), index);
        authority.checkOpen();
        LyraSignature declared = requireCallableFieldSignature(
                index, canonicalSignature, authority);
        CallableSelection selection = authenticateSelection(selected, authority, declared, false);
        return CallableMemberRoute.extend(this, index, declared, selection);
    }

    /**
     * Issues the exact writable-field route used by a generated callable
     * setter. The replacement is authenticated under the actual generated
     * caller; a raw value already owned by the nominal producer is admitted
     * only through this exact writable route. The returned evidence is wrapped
     * in the field's generated delegate before storage.
     */
    protected final Object issueCallableMemberWriteRoute(
            LyraClosureAuthority caller, int index, Object replacement,
            String canonicalSignature) {
        Objects.requireNonNull(caller, "caller");
        checkGeneratedWrite(caller, index);
        authority.checkOpen();
        LyraSignature declared = requireCallableFieldSignature(
                index, canonicalSignature, caller);
        CallableSelection selection = authenticateWritableSelection(
                replacement, caller, declared);
        return CallableMemberRoute.extend(this, index, declared, selection);
    }

    private CallableSelection authenticateWritableSelection(
            Object candidate, LyraClosureAuthority caller, LyraSignature signature) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate instanceof LyraNominalMemberDelegate delegate) {
            return delegate.requireSelection(caller, signature, true);
        }
        if (!(candidate instanceof LyraClosure closure)) {
            throw new LyraLinkException("function value is not an authenticated Lyra closure");
        }
        // The actual generated caller is the primary write authority. A raw
        // value already owned by the nominal producer remains legal through
        // this exact writable field route without widening general session
        // authentication for that raw occurrence.
        LyraClosure target = caller.authenticates(closure)
                ? LyraClosureSupport.requireAuthenticatedForGeneratedInvocation(
                        closure, caller, signature)
                : LyraClosureSupport.requireAuthenticated(closure, authority, signature);
        return new CallableSelection(target, List.of());
    }

    private LyraSignature requireCallableFieldSignature(
            int index, String canonicalSignature, LyraClosureAuthority resolver) {
        Objects.requireNonNull(canonicalSignature, "canonicalSignature");
        LyraSignature declared = callableFieldSignature(index);
        LyraSignature expected = resolver.resolveSignature(canonicalSignature);
        if (!declared.equals(expected)) {
            throw new LyraLinkException("delegated member route differs from its exact schema field");
        }
        return declared;
    }

    private static CallableSelection authenticateSelection(
            Object candidate, LyraClosureAuthority caller,
            LyraSignature signature, boolean generated) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate instanceof LyraNominalMemberDelegate delegate) {
            return delegate.requireSelection(caller, signature, generated);
        }
        LyraClosure target = generated
                ? LyraClosureSupport.requireAuthenticatedForGeneratedInvocation(
                        candidate, caller, signature)
                : LyraClosureSupport.requireAuthenticated(candidate, caller, signature);
        return new CallableSelection(target, List.of());
    }

    /**
     * Re-authenticates a saved field-derived occurrence without re-reading a
     * mutable slot or requiring the later caller to hold the original lexical
     * private-member privilege.
     */
    void checkDelegatedRoute(LyraClosureAuthority caller, int index,
                             LyraSignature expectedSignature, boolean generated) {
        Objects.requireNonNull(caller, "caller");
        if (generated) caller.token().checkGeneratedInvocation();
        else caller.checkOpen();
        authority.checkOpen();
        requireComplete();
        if (!caller.token().sameArtifact(authority.token())
                && (anchor == null || !anchor.authenticates(caller.token()))
                && !caller.token().sameSession(authority.token())) {
            throw new LyraLinkException("nominal object belongs to an unrelated artifact or session");
        }
        if (!callableFieldSignature(index).equals(
                Objects.requireNonNull(expectedSignature, "expectedSignature"))) {
            throw new LyraLinkException("delegated member route differs from its exact schema field");
        }
    }

    /** No-caller route check used by direct invocation and bounded snapshots. */
    void checkDelegatedRoute(int index, LyraSignature expectedSignature) {
        authority.checkOpen();
        requireComplete();
        if (anchor != null) anchor.checkActive();
        if (!callableFieldSignature(index).equals(
                Objects.requireNonNull(expectedSignature, "expectedSignature"))) {
            throw new LyraLinkException("delegated member route differs from its exact schema field");
        }
    }

    static CallableMemberRoute claimCallableMemberRoute(Object evidence) {
        if (!(evidence instanceof CallableMemberRoute route)) {
            throw new LyraLinkException("nominal member delegate lacks compiler-issued route evidence");
        }
        route.claim();
        return route;
    }

    /** One normalized callable selection and all exact routes that authorized it. */
    static record CallableSelection(
            LyraClosure target, List<CallableMemberDependency> dependencies) {
        CallableSelection {
            Objects.requireNonNull(target, "target");
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        }
    }

    /** Identity-sensitive route obligation; equal object shapes are not interchangeable. */
    static record CallableMemberDependency(
            LyraNominalObject source, int fieldIndex, LyraSignature signature) {
        CallableMemberDependency {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(signature, "signature");
        }

        boolean sameRoute(CallableMemberDependency other) {
            return source == other.source && fieldIndex == other.fieldIndex
                    && signature.equals(other.signature);
        }
    }

    /** Package-private immutable carrier; generated code sees only Object. */
    static final class CallableMemberRoute {
        private final CallableSelection selection;
        private final LyraSignature signature;
        private boolean claimed;

        private CallableMemberRoute(CallableSelection selection, LyraSignature signature) {
            this.selection = Objects.requireNonNull(selection, "selection");
            this.signature = Objects.requireNonNull(signature, "signature");
        }

        static CallableMemberRoute extend(
                LyraNominalObject source, int fieldIndex, LyraSignature signature,
                CallableSelection selected) {
            CallableMemberDependency dependency = new CallableMemberDependency(
                    source, fieldIndex, signature);
            List<CallableMemberDependency> current = selected.dependencies();
            boolean duplicate = current.stream().anyMatch(dependency::sameRoute);
            if (duplicate) return new CallableMemberRoute(selected, signature);
            var extended = new java.util.ArrayList<CallableMemberDependency>(current.size() + 1);
            extended.addAll(current);
            extended.add(dependency);
            return new CallableMemberRoute(
                    new CallableSelection(selected.target(), extended), signature);
        }

        private void claim() {
            checkUsable(signature);
            if (claimed) {
                throw new LyraLinkException("nominal member route evidence was already claimed");
            }
            claimed = true;
        }

        CallableSelection requireSelection(
                LyraClosureAuthority caller, LyraSignature expectedSignature,
                boolean generated) {
            Objects.requireNonNull(caller, "caller");
            requireSignature(expectedSignature);
            if (generated) caller.token().checkGeneratedInvocation();
            else caller.checkOpen();
            for (CallableMemberDependency dependency : selection.dependencies()) {
                dependency.source().checkDelegatedRoute(caller, dependency.fieldIndex(),
                        dependency.signature(), generated);
            }
            requireTargetUsable(expectedSignature);
            return selection;
        }

        void checkUsable() {
            checkUsable(signature);
        }

        void checkUsable(LyraSignature expectedSignature) {
            requireSignature(expectedSignature);
            for (CallableMemberDependency dependency : selection.dependencies()) {
                dependency.source().checkDelegatedRoute(
                        dependency.fieldIndex(), dependency.signature());
            }
            selection.target().checkInvocation(signature);
        }

        private void requireTargetUsable(LyraSignature expectedSignature) {
            // Route delegation never upgrades the selected producer's
            // lifecycle. Even a generated consumer may use only an OPEN
            // replacement producer through a saved field route.
            selection.target().checkInvocation(expectedSignature);
        }

        private void requireSignature(LyraSignature expectedSignature) {
            if (!signature.equals(Objects.requireNonNull(
                    expectedSignature, "expectedSignature"))) {
                throw new LyraLinkException(
                        "delegated member route has a different callable contract");
            }
        }

        LyraClosure target() { return selection.target(); }
        List<CallableMemberDependency> dependencies() { return selection.dependencies(); }
    }

    /**
     * Generated member reads of callable fields need occurrence-scoped
     * delegation only when neither artifact identity nor the ordinary
     * source-local session bridge authenticates the caller.  Same-artifact
     * and same-session reads keep returning the exact stored closure, so
     * existing identity and invocation behavior is unchanged there.
     */
    protected final boolean generatedReadRequiresDelegation(LyraClosureAuthority caller) {
        Objects.requireNonNull(caller, "caller");
        return !caller.token().sameArtifact(authority.token())
                && !caller.token().sameSession(authority.token());
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
