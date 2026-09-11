# Scope

Reserved iter syntax checkpoint, against f6e9190fd0a572dd34973ad46289a0f5f86937d2.

# Findings

- The reserved keyword is accepted at unqualified bracket/parenthesized call
  heads. Declaration and ordinary expression parsing still require identifiers,
  rejecting shadowing, bare-value use and qualified namespace spelling.
- Direct-call postfix and conditional-arrow lookahead both recognize iter as a
  built-in expression boundary. The original range-initializer counterexample
  is now a permanent parser regression.
- Grammar replay normalizes the reserved call head to the existing immutable
  identifier AST shape; descriptor validation confines this exception to call
  heads. This is not a general relaxation of identifier-token validation.
- Editor highlighting uses the compiler's new token, not a separate lexer rule.
- Generated parser checks vary whitespace/comments, endpoint kind and callback
  arity with a fixed seed; expected initializer shape is independently asserted.

# Risk Assessment

The name policy is implemented, but iteration is not executable. Resolver and
checker specialization, repeated effect transfer, IR/emission and runtime/session
integration remain outstanding. Existing range-foundation limitations still apply.

# Recommendations

Continue the phase-02 plan; do not certify a single-call flow approximation for
repeated callbacks or advertise executable iteration from these syntax tests.

# Follow-ups

See the active range-iter plan and this checkpoint's changelog for validation.
