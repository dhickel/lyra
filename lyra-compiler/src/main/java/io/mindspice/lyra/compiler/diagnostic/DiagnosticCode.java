package io.mindspice.lyra.compiler.diagnostic;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A validated, stable compiler diagnostic identifier. */
public record DiagnosticCode(String value, Phase phase) {
    private static final Pattern FORMAT =
            Pattern.compile("^LYC-([A-Z][A-Z0-9_]*)-([0-9]+)$");

    public DiagnosticCode {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(phase, "phase");
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "compiler diagnostic code must match LYC-<PHASE>-<NUMBER>: " + value);
        }
        if (!phase.codeName().equals(matcher.group(1))) {
            throw new IllegalArgumentException(
                    "diagnostic code phase does not match its identifier: " + value);
        }
        try {
            int number = Integer.parseInt(matcher.group(2));
            if (number < 1) {
                throw new IllegalArgumentException("diagnostic code number must be positive: " + value);
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("diagnostic code number is too large: " + value, exception);
        }
    }

    public static DiagnosticCode of(Phase phase, int number) {
        Objects.requireNonNull(phase, "phase");
        if (number < 1) {
            throw new IllegalArgumentException("diagnostic code number must be positive: " + number);
        }
        return new DiagnosticCode(
                "LYC-" + phase.codeName() + "-" + String.format(Locale.ROOT, "%03d", number), phase);
    }

    /** Parses a code and derives its phase from the identifier. */
    public static DiagnosticCode of(String value) {
        Objects.requireNonNull(value, "value");
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "compiler diagnostic code must match LYC-<PHASE>-<NUMBER>: " + value);
        }
        Phase phase = Phase.fromCodeName(matcher.group(1))
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown compiler diagnostic phase: " + matcher.group(1)));
        return new DiagnosticCode(value, phase);
    }

    public String id() {
        return value;
    }

    public int number() {
        int separator = value.lastIndexOf('-');
        return Integer.parseInt(value.substring(separator + 1));
    }

    @Override
    public String toString() {
        return value;
    }
}
