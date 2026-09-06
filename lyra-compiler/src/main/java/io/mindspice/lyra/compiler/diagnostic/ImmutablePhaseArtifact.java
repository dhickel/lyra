package io.mindspice.lyra.compiler.diagnostic;

/**
 * Marker for a fully frozen value published across a compiler phase boundary.
 * Implementations must defensively own all nested state before construction.
 */
public interface ImmutablePhaseArtifact {
}
