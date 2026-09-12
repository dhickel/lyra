# Nominal construction authority self-review

## Scope

Runtime primitives for final typed nominal objects; no compiler-emission claim.

## Findings

- A construction capability is bound once to an exact final direct subclass and
  receiver. The subclass constructor checks its own embedded nominal contract.
  A caller-supplied representation class alone cannot supply that invariant.
- Source values live only in typed subclass fields. The base owns schema/authority
  and temporary initialization bits, releasing the bits on completion or failure.
- Partial reads, incomplete completion, repeated immutable initialization, invalid
  field indices, foreign/reused capabilities and exceptional construction reject.
- Public access preserves OPEN lifecycle and visibility/mutability. Generated
  access permits module initialization but not an incomplete object. Exact-domain
  value authentication checks nominal identity and the caller's schema contract.
- Runtime private access permits distinct producer instances of the same declaring
  module in one authenticated domain. Instance-token equality would incorrectly
  reject valid same-class private access across those instances. Lexical class
  privacy remains a compiler proof, not an authority obtained from a module ID.
- Tests cover zero-field structs, wrong-thread initialization/read, failed producers,
  failed constructors, wrong embedded contracts and foreign artifact values.
- Two seeded independent models track initialization bits and primitive slot values;
  they do not use production helpers to calculate expected values.

## Risk Assessment

Factories must finish value evaluation/authentication before marking an initialization
slot, immediately store it, and invalidate partial receivers on every exceptional
exit. This protocol still needs source-executed emitter tests. Loader class/schema
inventory and actual retained defining-producer linkage are separate remaining gates.

## Recommendations

Implement exact nominal class/member plans and typed factories next. Preserve source
privacy, constructor evaluation order and failure edges when wiring these runtime
checks. Do not replace nominal storage with a runtime value map or generic array.

## Follow-ups

Extended `tools/fuzz-language.sh -q`, final `mvn -q test`, and diff checks passed.
The full nominal objective remains active, including generated classes/factories,
semantic transfer, equality, Java artifacts and sessions.
