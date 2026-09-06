package io.mindspice.lyra.compiler.semantic.flow;

import java.util.Objects;

/**
 * Finite limits for the internal callable-summary abstract domain.
 *
 * <p>The limits are a safety boundary, not a precision knob.  Producers must
 * report an internal failure when a limit would be exceeded; they must not
 * silently drop alternatives, operations, or witnesses.</p>
 */
public record SummaryLimits(
        int maxFormulaAlternatives,
        int maxCallReferences,
        int maxWrites,
        int maxEffects,
        int maxProjectionDepth,
        int maxWitnessPathDepth,
        int maxFixedPointIterations) {
    public static final SummaryLimits DEFAULT = new SummaryLimits(
            256, 256, 256, 512, 64, 64, 512);

    public SummaryLimits {
        requirePositive(maxFormulaAlternatives, "maxFormulaAlternatives");
        requirePositive(maxCallReferences, "maxCallReferences");
        requirePositive(maxWrites, "maxWrites");
        requirePositive(maxEffects, "maxEffects");
        requirePositive(maxProjectionDepth, "maxProjectionDepth");
        requirePositive(maxWitnessPathDepth, "maxWitnessPathDepth");
        requirePositive(maxFixedPointIterations, "maxFixedPointIterations");
    }

    public static SummaryLimits of(
            int maxFormulaAlternatives,
            int maxCallReferences,
            int maxWrites,
            int maxEffects,
            int maxProjectionDepth,
            int maxWitnessPathDepth,
            int maxFixedPointIterations) {
        return new SummaryLimits(maxFormulaAlternatives, maxCallReferences, maxWrites,
                maxEffects, maxProjectionDepth, maxWitnessPathDepth, maxFixedPointIterations);
    }

    /** A compact default suitable for direct summary tests. */
    public static SummaryLimits defaults() {
        return DEFAULT;
    }

    void requireFormulaAlternatives(int actual) {
        requireWithin(actual, maxFormulaAlternatives, "formula alternatives");
    }

    void requireCallReferences(int actual) {
        requireWithin(actual, maxCallReferences, "call references");
    }

    void requireWrites(int actual) {
        requireWithin(actual, maxWrites, "captured-cell writes");
    }

    void requireOwnershipRequirements(int actual) {
        requireWithin(actual, maxWrites, "ownership requirements");
    }

    void requireEffects(int actual) {
        requireWithin(actual, maxEffects, "eager-effect witnesses");
    }

    void requireProjectionDepth(int actual) {
        requireWithin(actual, maxProjectionDepth, "projection depth");
    }

    void requireWitnessPathDepth(int actual) {
        requireWithin(actual, maxWitnessPathDepth, "eager-effect witness path depth");
    }

    void requireFixedPointIteration(int actual) {
        requireWithin(actual, maxFixedPointIterations, "callable-summary fixed-point iterations");
    }

    private static void requireWithin(int actual, int maximum, String subject) {
        if (actual > maximum) {
            throw new SummaryDomainException(subject + " exceed the finite summary domain: "
                    + actual + " > " + maximum);
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Internal control exception converted to an explicit summary failure. */
    static final class SummaryDomainException extends RuntimeException {
        SummaryDomainException(String message) {
            super(Objects.requireNonNull(message, "message"));
        }
    }
}
