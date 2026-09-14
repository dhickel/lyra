# Retained nominal nilable member-read contracts (issue #8)

## Date

2026-09-14

## Git Commit

dff9ae9c7d0f6dbd4c3bc283ba7a493f5831a11f

## Change Summary

Derived nominal member-read contracts independently at typed semantic
provenance sealing and IR validation from the exact resolved schema member,
including nilability. Same- and cross-generation explicit `@nil` annotations,
nil coalescing, predicate narrowing and `#NIL` value match over nilable member
reads now compile and execute under current nil rules; no valid case reaches
`LYC-IR-003`. Nilable receivers, mismatched annotations/contracts, forged
member links and invented match narrowing remain structured source failures.
Retained transfer certification is preserved: producer-generation `#NIL`
provenance of a retained member read is accepted at the IR boundary only
through the compiler-issued session certificate, and the runtime
sourceLocal/general authentication and exact typed storage/authority
boundaries are unchanged.

Production changes:

- `TypedSemanticProvenance.memberType` now resolves nominal member reads
  through a new `nominalMemberContract` derivation that reads the exact
  resolved schema member type (name and declared nilability), so coalesce
  values, predicate bindings, tuple/structural inference and the other
  sealing consumers can no longer see a missing nominal member contract. The
  existing `validateMemberContract` slot-equality and private-access checks
  remain the rejection gate for forged published types.
- `IrValidator.retainedNilSite` accepts a nil provenance whose flow site is
  absent from the current graph only when `SessionFlowCertificate.certifiesNil`
  matches the exact site/span/route in the boundary state, retained nominal
  transfers or producer callable summaries. A mismatched span for a
  current-graph site is never certified, so forged or foreign nil provenance
  still fails with `LYC-IR-003`.

Tests, independent validators and records:

- New `NilableMemberReadContractTest` (compiler): sealing/IR positives for
  annotated, widened-annotation, coalesce, narrowing, `#NIL` and value match,
  tuple-inference and self member reads; structured negatives for nilable
  receivers, mismatched annotations, coalesce over non-nilable members,
  invented match narrowing and unknown members; sealing and IR forgery tests
  that alter the member contract type, member name and declaration link and
  prove the schema-slot derivations reject them.
- `NominalBytecodeTest.nilableMemberReadContractsCompileAndExecuteInOneGeneration`
  executes every same-generation consumer through generated JVM bytecode.
- `NominalSessionTest` gains cross-generation positive execution coverage
  (annotation, coalesce, narrowing, `#NIL` match over nil and non-nil member
  values) and cross-generation structured negatives (mismatched annotation,
  invented match narrowing, nilable receiver).
- `RetainedNominalModel` `alternatives` profile now models four retained
  nilable member-read operations (`nil-member-annotate`,
  `nil-member-coalesce`, `nil-member-narrow`, `nil-member-match`) with
  reference and `#NIL`-value member transfers; expected values come from
  plain-Java arithmetic only. The profile's member/variant inventory and
  operation counts stay balanced so the fuzz driver's exact-op accounting
  holds; generated sources were validated through the real session storage
  domain and certificate variant checks.
- `language-core.md` documents the schema-derived member contract and the
  certificate-only retained nil provenance rule; `docs/language-testing.md`
  replaces the issue-#8 open-limitation note with the implemented coverage;
  `typed-ir-sealing.md` records the derivation/certification lessons; the bug
  report status/next-action now reflects the fix.

## Files

- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/semantic/TypedSemanticProvenance.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java`
- `lyra-compiler/src/test/java/NilableMemberReadContractTest.java` (new)
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/backend/jvm/NominalBytecodeTest.java`
- `lyra-compiler/src/test/java/io/mindspice/lyra/compiler/conformance/RetainedNominalModel.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/NominalSessionTest.java`
- `.internal-dev/specifications/language-core.md`
- `.internal-dev/knowledge/typed-ir-sealing.md`
- `.internal-dev/bugs/.archive/retained-nominal/nilable-member-read-contract/report.md`
- `docs/language-testing.md`

## Behavioral Impact

- Valid nilable nominal member reads compile and execute in one generation and
  across session generations for annotations, coalesce, predicate narrowing
  and `#NIL` value match; the annotated cross-generation read no longer fails
  with `LYC-IR-003`.
- Fail-closed behavior is unchanged for invalid source: nilable receivers,
  mismatched annotations/contracts, forged member links and invented match
  narrowing keep their structured diagnostics, and the IR certificate
  carve-out accepts only exact producer-certified nil provenance.
- No runtime, protocol v2, JavaFX, language-version or ABI surface changed.

## Specification Impact

`language-core.md` now states that nominal member reads derive their contract
from the exact resolved schema member (including `@nil`) and that retained nil
provenance is accepted at the IR boundary only through the compiler-issued
session certificate. No other specification changed.

## Risks

- The fuzz suites that execute the updated `RetainedNominalModel` rotation
  (`LanguageFuzzTest`, `SessionStateFuzzTest`, `tools/fuzz-language.sh`) were
  intentionally not run for this unit; the model was validated instead by
  executing generated plans through the real session storage domain and by
  certificate variant/structure checks in scratch harnesses. The compiler
  module was fully built excluding the forbidden fuzz tests.
- The pre-existing callback-loop module-level `@mut`-write invariant
  (`.internal-dev/bugs/callback-loop-module-level-mut-write-invariant/report.md`)
  still reproduces unchanged and is intentionally not absorbed here.

## Follow-up Items

- Run the default and extended fuzz campaigns (including the new
  `nil-member-*` retained operations) in the Tranche 3 qualification step
  before closing issue #8, together with issue #7's route-delegation work.
- The fixed nilable-member-read report is archived under `.internal-dev/bugs/.archive/retained-nominal/`.
