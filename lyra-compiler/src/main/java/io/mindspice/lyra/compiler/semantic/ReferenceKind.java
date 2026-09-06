/** The source-level operation represented by a resolved name reference. */
package io.mindspice.lyra.compiler.semantic;

public enum ReferenceKind {
    VALUE,
    DIRECT_CALL_TARGET,
    MODULE_NAMESPACE,
    NAMESPACE_MEMBER,
    NAMESPACE_DIRECT_CALL
}
