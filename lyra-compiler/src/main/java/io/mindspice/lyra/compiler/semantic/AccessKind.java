/** Access distinction retained for later type checking and lowering. */
package io.mindspice.lyra.compiler.semantic;

public enum AccessKind {
    MEMBER_VALUE,
    MEMBER_CALL,
    NAMESPACE_VALUE,
    NAMESPACE_DIRECT_CALL
}
