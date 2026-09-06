/** Storage semantics selected for a closure capture. */
package io.mindspice.lyra.compiler.semantic;

public enum CaptureMode {
    IMMUTABLE_VALUE,
    SHARED_MUTABLE_CELL
}
