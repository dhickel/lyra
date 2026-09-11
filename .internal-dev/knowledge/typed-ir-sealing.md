# Typed IR Sealing

## Topic

Phase-12 closed typed-IR publication and validation invariants.

## Source References

- `.internal-dev/specifications/language-core.md`
- `.internal-dev/specifications/backend-runtime.md`
- `.internal-dev/plans/20260830-194512-implement-the-complete-lyra-language-jvm-backend-runtime/plan.md`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/TypedIrBuilder.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrValidator.java`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/ir/IrProgramMetadata.java`

## Key Takeaways

- A source expression and a synthetic runtime check are not separate source sites. The outer node owns the expression `FlowSiteId`; synthetic inner operator/conversion/index/check nodes remain site-less and retain explicit failure metadata where applicable.
- A complete IR needs immutable module-level projections in addition to its expression tree: declarations, references, lambdas, captures, shared cells, imports, exports, function-signature SCCs, closure initialization, explicit evaluation edges, failure-site identities, flow provenance, and the initialization schedule.
- `SemanticFlowFacts` and `InitializationPlan` are consumed artifacts. IR lowering and validation must not rerun `SemanticFlowAnalyzer` or `InitializationAnalyzer`, and validation must not infer ownership or callable flow from spans/types.
- Runtime arithmetic, division, bounds, and explicit numeric conversion checks map to the semantic failure-site records. Nil coalescing remains an explicit narrowing/control check but is not a failure site in the current Phase-11 semantic contract. Deterministic `String[value]` conversion is also not a runtime-failure site.
- Callable summary call identities are issued in `CallableCallReference` records, not in the compact `SemanticFlowEvent` for an eager call. IR lowering must match a call expression's producer site against the solved summary records before leaving its call identity optional.
- Producer source-site paths are mandatory for published eager dependencies/effect witnesses. Compatibility constructors may represent older site-less records, but Phase-12 sealing must reject them rather than silently dropping provenance.
- Flow events may share one `FlowSiteId` because one source event can have multiple compact representations. Event indexes therefore map a site to an immutable list, not one event.
- Source order is represented by explicit producer-site evaluation edges. Span ordering is only supplementary evidence and cannot disambiguate repeated or nested spans.
- Constructor-created `TypedIr` values are negative-test candidates. Only the package-owned builder publication path sets the validated bit; downstream gates reject unvalidated values.

## Project Relevance

These boundaries keep the typed IR closed, deterministic, deeply immutable, JVM-independent, and safe for the later emitter without turning the semantic graph or compact flow artifact into a discarded hidden dependency.

## Open Questions

None for the current language slice. Future runtime/JVM phases consume this internal artifact but must not add source semantics or broaden the IR into a public serialized/backend SPI.
