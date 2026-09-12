package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Generated value-boundary authentication, independent of field representation. */
public final class LyraNominalSupport {
    private LyraNominalSupport() { }

    public static LyraNominalObject requireAuthenticated(Object candidate, LyraClosureAuthority caller,
                                                         NominalType expected) {
        Objects.requireNonNull(caller, "caller").checkOpen();
        return require(candidate, caller, expected, false);
    }

    public static LyraNominalObject requireAuthenticatedForGeneratedInvocation(
            Object candidate, LyraClosureAuthority caller, NominalType expected) {
        Objects.requireNonNull(caller, "caller").token().checkGeneratedInvocation();
        return require(candidate, caller, expected, true);
    }

    private static LyraNominalObject require(Object candidate, LyraClosureAuthority caller,
                                             NominalType expected, boolean generated) {
        Objects.requireNonNull(expected, "expected");
        if (!(candidate instanceof LyraNominalObject value)) {
            throw new LyraLinkException("value is not an authenticated Lyra nominal object");
        }
        value.checkValueContract(caller, expected, generated);
        return value;
    }
}
