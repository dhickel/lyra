package io.mindspice.lyra.compiler.diagnostic;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Stable compiler diagnostic codes owned by the source/diagnostic foundation. */
public final class CompilerDiagnosticCodes {
    public static final DiagnosticCode SOURCE_MALFORMED_UTF8 =
            DiagnosticCode.of(Phase.SOURCE, 1);
    public static final DiagnosticCode SOURCE_INVALID_ID =
            DiagnosticCode.of(Phase.SOURCE, 2);
    public static final DiagnosticCode SOURCE_INVALID_PHYSICAL_KEY =
            DiagnosticCode.of(Phase.SOURCE, 3);
    public static final DiagnosticCode SOURCE_INVALID_SPAN =
            DiagnosticCode.of(Phase.SOURCE, 4);

    public static final DiagnosticCode DIAGNOSTIC_INVALID_CODE =
            DiagnosticCode.of(Phase.SOURCE, 5);
    public static final DiagnosticCode DIAGNOSTIC_INVALID_SPAN =
            DiagnosticCode.of(Phase.SOURCE, 6);

    public static final DiagnosticCode LEX_INVALID_CHARACTER =
            DiagnosticCode.of(Phase.LEX, 1);
    public static final DiagnosticCode LEX_INVALID_OPERATOR =
            DiagnosticCode.of(Phase.LEX, 2);
    public static final DiagnosticCode LEX_INVALID_MODIFIER =
            DiagnosticCode.of(Phase.LEX, 3);
    public static final DiagnosticCode LEX_INVALID_LITERAL =
            DiagnosticCode.of(Phase.LEX, 4);
    public static final DiagnosticCode LEX_INVALID_ESCAPE =
            DiagnosticCode.of(Phase.LEX, 5);
    public static final DiagnosticCode LEX_UNTERMINATED_STRING =
            DiagnosticCode.of(Phase.LEX, 6);
    public static final DiagnosticCode LEX_UNTERMINATED_CHAR =
            DiagnosticCode.of(Phase.LEX, 7);
    public static final DiagnosticCode LEX_INVALID_CHAR_LENGTH =
            DiagnosticCode.of(Phase.LEX, 8);
    public static final DiagnosticCode LEX_UNTERMINATED_BLOCK_COMMENT =
            DiagnosticCode.of(Phase.LEX, 9);
    public static final DiagnosticCode LEX_INVALID_NUMBER =
            DiagnosticCode.of(Phase.LEX, 10);
    public static final DiagnosticCode LEX_NUMERIC_OUT_OF_RANGE =
            DiagnosticCode.of(Phase.LEX, 11);

    public static final DiagnosticCode PARSE_UNEXPECTED_TOKEN =
            DiagnosticCode.of(Phase.PARSE, 1);
    public static final DiagnosticCode PARSE_MISSING_DELIMITER =
            DiagnosticCode.of(Phase.PARSE, 2);
    public static final DiagnosticCode PARSE_INVALID_FORM =
            DiagnosticCode.of(Phase.PARSE, 3);
    public static final DiagnosticCode PARSE_INVALID_COMMA =
            DiagnosticCode.of(Phase.PARSE, 4);
    public static final DiagnosticCode PARSE_INVALID_ANNOTATION_SPACING =
            DiagnosticCode.of(Phase.PARSE, 5);
    public static final DiagnosticCode PARSE_INVALID_MODIFIER =
            DiagnosticCode.of(Phase.PARSE, 6);
    public static final DiagnosticCode PARSE_INVALID_OPERATOR_ARITY =
            DiagnosticCode.of(Phase.PARSE, 7);
    public static final DiagnosticCode PARSE_INVALID_ACCESSOR =
            DiagnosticCode.of(Phase.PARSE, 8);
    public static final DiagnosticCode PARSE_IMPORT_HEADER =
            DiagnosticCode.of(Phase.PARSE, 9);
    public static final DiagnosticCode PARSE_INVALID_TYPE_FORM =
            DiagnosticCode.of(Phase.PARSE, 10);
    /** Obsolete '??' arm markers. */
    public static final DiagnosticCode PARSE_OBSOLETE_ARM_MARKER =
            DiagnosticCode.of(Phase.PARSE, 11);
    /** Obsolete '::match[...]', '::cond[...]', '::iter[...]' and '::while[...]' spellings. */
    public static final DiagnosticCode PARSE_OBSOLETE_DIRECT_SPECIAL_FORM =
            DiagnosticCode.of(Phase.PARSE, 12);
    /** Obsolete conditional '(match _ ...)' special case. */
    public static final DiagnosticCode PARSE_OBSOLETE_CONDITIONAL_MATCH =
            DiagnosticCode.of(Phase.PARSE, 13);
    /** Misuse of the explicit ':Type[...]' construction marker. */
    public static final DiagnosticCode PARSE_INVALID_CONSTRUCTION =
            DiagnosticCode.of(Phase.PARSE, 14);

    public static final DiagnosticCode MODULE_INVALID_CONFIGURATION =
            DiagnosticCode.of(Phase.MODULE, 1);
    public static final DiagnosticCode MODULE_INVALID_IMPORT_PATH =
            DiagnosticCode.of(Phase.MODULE, 2);
    public static final DiagnosticCode MODULE_DUPLICATE_IDENTITY =
            DiagnosticCode.of(Phase.MODULE, 3);
    public static final DiagnosticCode MODULE_EAGER_INITIALIZATION_CYCLE =
            DiagnosticCode.of(Phase.MODULE, 4);

    public static final DiagnosticCode RESOLVE_MISSING_MODULE =
            DiagnosticCode.of(Phase.RESOLVE, 1);
    public static final DiagnosticCode RESOLVE_DUPLICATE_MATCH =
            DiagnosticCode.of(Phase.RESOLVE, 2);
    public static final DiagnosticCode RESOLVE_DUPLICATE_PHYSICAL_SOURCE =
            DiagnosticCode.of(Phase.RESOLVE, 3);
    public static final DiagnosticCode RESOLVE_INVALID_ROOT =
            DiagnosticCode.of(Phase.RESOLVE, 4);
    public static final DiagnosticCode RESOLVE_SOURCE_IO =
            DiagnosticCode.of(Phase.RESOLVE, 5);
    public static final DiagnosticCode RESOLVE_INTRINSIC_RESERVED =
            DiagnosticCode.of(Phase.RESOLVE, 6);
    public static final DiagnosticCode RESOLVE_INVALID_CANDIDATE =
            DiagnosticCode.of(Phase.RESOLVE, 7);

    public static final DiagnosticCode RESOLVE_DUPLICATE_NAME =
            DiagnosticCode.of(Phase.RESOLVE, 8);
    public static final DiagnosticCode RESOLVE_PUBLIC_REDECLARATION =
            DiagnosticCode.of(Phase.RESOLVE, 9);
    public static final DiagnosticCode RESOLVE_IMPORT_MODIFIER =
            DiagnosticCode.of(Phase.RESOLVE, 10);
    public static final DiagnosticCode RESOLVE_IMPORT_NAME_CONFLICT =
            DiagnosticCode.of(Phase.RESOLVE, 11);
    public static final DiagnosticCode RESOLVE_IMPORT_NOT_PUBLIC =
            DiagnosticCode.of(Phase.RESOLVE, 12);
    public static final DiagnosticCode RESOLVE_UNRESOLVED_NAME =
            DiagnosticCode.of(Phase.RESOLVE, 13);
    public static final DiagnosticCode RESOLVE_FORWARD_REFERENCE =
            DiagnosticCode.of(Phase.RESOLVE, 14);
    public static final DiagnosticCode RESOLVE_INVALID_MODIFIER =
            DiagnosticCode.of(Phase.RESOLVE, 15);
    public static final DiagnosticCode RESOLVE_INVALID_SIGNATURE =
            DiagnosticCode.of(Phase.RESOLVE, 16);
    public static final DiagnosticCode RESOLVE_SIGNATURE_REQUIRED =
            DiagnosticCode.of(Phase.RESOLVE, 17);
    public static final DiagnosticCode RESOLVE_DUPLICATE_PARAMETER =
            DiagnosticCode.of(Phase.RESOLVE, 18);
    public static final DiagnosticCode RESOLVE_INVALID_MODULE_ACCESS =
            DiagnosticCode.of(Phase.RESOLVE, 19);
    public static final DiagnosticCode RESOLVE_PRIVATE_MEMBER =
            DiagnosticCode.of(Phase.RESOLVE, 20);
    public static final DiagnosticCode RESOLVE_MUTATION_NOT_ALLOWED =
            DiagnosticCode.of(Phase.RESOLVE, 21);
    public static final DiagnosticCode RESOLVE_IMPORTED_MUTATION =
            DiagnosticCode.of(Phase.RESOLVE, 22);
    public static final DiagnosticCode RESOLVE_DUPLICATE_EXPORT =
            DiagnosticCode.of(Phase.RESOLVE, 23);
    public static final DiagnosticCode RESOLVE_FUNCTION_LINKAGE =
            DiagnosticCode.of(Phase.RESOLVE, 24);
    public static final DiagnosticCode RESOLVE_IMPORT_HEADER =
            DiagnosticCode.of(Phase.RESOLVE, 25);
    /**
     * Retired nominal implementation boundary, retained only so the public
     * diagnostic-code inventory does not reuse or renumber LYC-RESOLVE-026.
     */
    @Deprecated(forRemoval = false)
    public static final DiagnosticCode RESOLVE_NOMINAL_NOT_IMPLEMENTED =
            DiagnosticCode.of(Phase.RESOLVE, 26);
    /** Obsolete qualified namespace-value nominal construction 'module->:.Type[...]'. */
    public static final DiagnosticCode RESOLVE_OBSOLETE_CONSTRUCTION =
            DiagnosticCode.of(Phase.RESOLVE, 27);

    public static final DiagnosticCode TYPE_MISMATCH =
            DiagnosticCode.of(Phase.TYPE, 1);
    public static final DiagnosticCode TYPE_UNRESOLVED_LINK =
            DiagnosticCode.of(Phase.TYPE, 2);
    public static final DiagnosticCode TYPE_UNTYPED_EXPRESSION =
            DiagnosticCode.of(Phase.TYPE, 3);
    public static final DiagnosticCode TYPE_INVALID_LITERAL =
            DiagnosticCode.of(Phase.TYPE, 4);
    public static final DiagnosticCode TYPE_NIL_CONTEXT =
            DiagnosticCode.of(Phase.TYPE, 5);
    public static final DiagnosticCode TYPE_INVALID_ARITY =
            DiagnosticCode.of(Phase.TYPE, 6);
    public static final DiagnosticCode TYPE_NOT_CALLABLE =
            DiagnosticCode.of(Phase.TYPE, 7);
    public static final DiagnosticCode TYPE_INVALID_OPERATOR =
            DiagnosticCode.of(Phase.TYPE, 8);
    public static final DiagnosticCode TYPE_INVALID_CONVERSION =
            DiagnosticCode.of(Phase.TYPE, 9);
    public static final DiagnosticCode TYPE_INVALID_NARROWING =
            DiagnosticCode.of(Phase.TYPE, 10);
    public static final DiagnosticCode TYPE_UNSUPPORTED_CONSTRUCT =
            DiagnosticCode.of(Phase.TYPE, 11);
    public static final DiagnosticCode TYPE_INVALID_REBINDING =
            DiagnosticCode.of(Phase.TYPE, 12);
    public static final DiagnosticCode TYPE_INVALID_ACCESS =
            DiagnosticCode.of(Phase.TYPE, 13);
    public static final DiagnosticCode TYPE_INVALID_INDEX = TYPE_INVALID_ACCESS;
    public static final DiagnosticCode TYPE_INVALID_BRANCH =
            DiagnosticCode.of(Phase.TYPE, 14);
    public static final DiagnosticCode TYPE_INCOMPLETE_LAMBDA =
            DiagnosticCode.of(Phase.TYPE, 15);
    public static final DiagnosticCode TYPE_INVALID_BINDING =
            DiagnosticCode.of(Phase.TYPE, 16);
    public static final DiagnosticCode TYPE_INVALID_TRUTH_TEST =
            DiagnosticCode.of(Phase.TYPE, 17);
    public static final DiagnosticCode TYPE_NO_COMMON_NUMERIC_TYPE =
            DiagnosticCode.of(Phase.TYPE, 18);

    public static final DiagnosticCode IR_MISSING_TYPE =
            DiagnosticCode.of(Phase.IR, 1);
    public static final DiagnosticCode IR_MISSING_SPAN =
            DiagnosticCode.of(Phase.IR, 2);
    public static final DiagnosticCode IR_UNRESOLVED_LINK =
            DiagnosticCode.of(Phase.IR, 3);
    public static final DiagnosticCode IR_UNRECORDED_CONVERSION =
            DiagnosticCode.of(Phase.IR, 4);
    public static final DiagnosticCode IR_UNSUPPORTED_NODE =
            DiagnosticCode.of(Phase.IR, 5);
    public static final DiagnosticCode IR_EVALUATION_ORDER =
            DiagnosticCode.of(Phase.IR, 6);
    public static final DiagnosticCode IR_INVALID_ARITY =
            DiagnosticCode.of(Phase.IR, 7);
    public static final DiagnosticCode IR_INVALID_GRAPH =
            DiagnosticCode.of(Phase.IR, 8);

    public static final DiagnosticCode EMIT_UNSUPPORTED_FEATURE =
            DiagnosticCode.of(Phase.EMIT, 1);
    public static final DiagnosticCode EMIT_INVALID_PLAN =
            DiagnosticCode.of(Phase.EMIT, 2);

    public static final DiagnosticCode PACKAGE_INVALID_MAIN =
            DiagnosticCode.of(Phase.PACKAGE, 1);

    public static final DiagnosticCode SESSION_EXTERNAL_BINDING_UNSUPPORTED =
            DiagnosticCode.of(Phase.SESSION, 1);
    public static final DiagnosticCode SESSION_NAME_CONFLICT =
            DiagnosticCode.of(Phase.SESSION, 2);

    /** Descriptive aliases retained for semantic callers. */
    public static final DiagnosticCode RESOLVE_DUPLICATE_DECLARATION = RESOLVE_DUPLICATE_NAME;
    public static final DiagnosticCode RESOLVE_IMPORT_VISIBILITY = RESOLVE_IMPORT_NOT_PUBLIC;
    public static final DiagnosticCode RESOLVE_UNRESOLVED_REFERENCE = RESOLVE_UNRESOLVED_NAME;
    public static final DiagnosticCode RESOLVE_INVALID_LAMBDA_SIGNATURE = RESOLVE_INVALID_SIGNATURE;
    public static final DiagnosticCode RESOLVE_MUTATION_ROOT = RESOLVE_MUTATION_NOT_ALLOWED;
    public static final DiagnosticCode RESOLVE_REEXPORT_CONFLICT = RESOLVE_DUPLICATE_EXPORT;
    public static final DiagnosticCode MODULE_INITIALIZATION_CYCLE = MODULE_EAGER_INITIALIZATION_CYCLE;
    public static final DiagnosticCode RESOLVE_EAGER_INITIALIZATION_CYCLE =
            MODULE_EAGER_INITIALIZATION_CYCLE;

    /** More specific aliases used by source-resolution callers. */
    public static final DiagnosticCode MODULE_INVALID_ROOT = RESOLVE_INVALID_ROOT;
    public static final DiagnosticCode MODULE_MISSING_MODULE = RESOLVE_MISSING_MODULE;
    public static final DiagnosticCode MODULE_DUPLICATE_MATCH = RESOLVE_DUPLICATE_MATCH;
    public static final DiagnosticCode MODULE_DUPLICATE_PHYSICAL_SOURCE =
            RESOLVE_DUPLICATE_PHYSICAL_SOURCE;
    public static final DiagnosticCode MODULE_INTRINSIC_RESERVED = RESOLVE_INTRINSIC_RESERVED;

    /** Aliases retained for callers that prefer the longer diagnostic names. */
    public static final DiagnosticCode MALFORMED_UTF8 = SOURCE_MALFORMED_UTF8;
    public static final DiagnosticCode LEX_UNTERMINATED_CHARACTER = LEX_UNTERMINATED_CHAR;
    public static final DiagnosticCode LEX_UNTERMINATED_COMMENT = LEX_UNTERMINATED_BLOCK_COMMENT;
    public static final DiagnosticCode LEX_NUMBER_OUT_OF_RANGE = LEX_NUMERIC_OUT_OF_RANGE;

    private static final List<DiagnosticCode> ALL = List.of(
            SOURCE_MALFORMED_UTF8,
            SOURCE_INVALID_ID,
            SOURCE_INVALID_PHYSICAL_KEY,
            SOURCE_INVALID_SPAN,
            DIAGNOSTIC_INVALID_CODE,
            DIAGNOSTIC_INVALID_SPAN,
            LEX_INVALID_CHARACTER,
            LEX_INVALID_OPERATOR,
            LEX_INVALID_MODIFIER,
            LEX_INVALID_LITERAL,
            LEX_INVALID_ESCAPE,
            LEX_UNTERMINATED_STRING,
            LEX_UNTERMINATED_CHAR,
            LEX_INVALID_CHAR_LENGTH,
            LEX_UNTERMINATED_BLOCK_COMMENT,
            LEX_INVALID_NUMBER,
            LEX_NUMERIC_OUT_OF_RANGE,
            PARSE_UNEXPECTED_TOKEN,
            PARSE_MISSING_DELIMITER,
            PARSE_INVALID_FORM,
            PARSE_INVALID_COMMA,
            PARSE_INVALID_ANNOTATION_SPACING,
            PARSE_INVALID_MODIFIER,
            PARSE_INVALID_OPERATOR_ARITY,
            PARSE_INVALID_ACCESSOR,
            PARSE_IMPORT_HEADER,
            PARSE_INVALID_TYPE_FORM,
            PARSE_OBSOLETE_ARM_MARKER,
            PARSE_OBSOLETE_DIRECT_SPECIAL_FORM,
            PARSE_OBSOLETE_CONDITIONAL_MATCH,
            PARSE_INVALID_CONSTRUCTION,
            MODULE_INVALID_CONFIGURATION,
            MODULE_INVALID_IMPORT_PATH,
            MODULE_DUPLICATE_IDENTITY,
            MODULE_EAGER_INITIALIZATION_CYCLE,
            RESOLVE_MISSING_MODULE,
            RESOLVE_DUPLICATE_MATCH,
            RESOLVE_DUPLICATE_PHYSICAL_SOURCE,
            RESOLVE_INVALID_ROOT,
            RESOLVE_SOURCE_IO,
            RESOLVE_INTRINSIC_RESERVED,
            RESOLVE_INVALID_CANDIDATE,
            RESOLVE_DUPLICATE_NAME,
            RESOLVE_PUBLIC_REDECLARATION,
            RESOLVE_IMPORT_MODIFIER,
            RESOLVE_IMPORT_NAME_CONFLICT,
            RESOLVE_IMPORT_NOT_PUBLIC,
            RESOLVE_UNRESOLVED_NAME,
            RESOLVE_FORWARD_REFERENCE,
            RESOLVE_INVALID_MODIFIER,
            RESOLVE_INVALID_SIGNATURE,
            RESOLVE_SIGNATURE_REQUIRED,
            RESOLVE_DUPLICATE_PARAMETER,
            RESOLVE_INVALID_MODULE_ACCESS,
            RESOLVE_PRIVATE_MEMBER,
            RESOLVE_MUTATION_NOT_ALLOWED,
            RESOLVE_IMPORTED_MUTATION,
            RESOLVE_DUPLICATE_EXPORT,
            RESOLVE_FUNCTION_LINKAGE,
            RESOLVE_IMPORT_HEADER,
            RESOLVE_NOMINAL_NOT_IMPLEMENTED,
            RESOLVE_OBSOLETE_CONSTRUCTION,
            TYPE_MISMATCH,
            TYPE_UNRESOLVED_LINK,
            TYPE_UNTYPED_EXPRESSION,
            TYPE_INVALID_LITERAL,
            TYPE_NIL_CONTEXT,
            TYPE_INVALID_ARITY,
            TYPE_NOT_CALLABLE,
            TYPE_INVALID_OPERATOR,
            TYPE_INVALID_CONVERSION,
            TYPE_INVALID_NARROWING,
            TYPE_UNSUPPORTED_CONSTRUCT,
            TYPE_INVALID_REBINDING,
            TYPE_INVALID_ACCESS,
            TYPE_INVALID_BRANCH,
            TYPE_INCOMPLETE_LAMBDA,
            TYPE_INVALID_BINDING,
            TYPE_INVALID_TRUTH_TEST,
            TYPE_NO_COMMON_NUMERIC_TYPE,
            IR_MISSING_TYPE,
            IR_MISSING_SPAN,
            IR_UNRESOLVED_LINK,
            IR_UNRECORDED_CONVERSION,
            IR_UNSUPPORTED_NODE,
            IR_EVALUATION_ORDER,
            IR_INVALID_ARITY,
            IR_INVALID_GRAPH,
            EMIT_UNSUPPORTED_FEATURE,
            EMIT_INVALID_PLAN,
            PACKAGE_INVALID_MAIN,
            SESSION_EXTERNAL_BINDING_UNSUPPORTED,
            SESSION_NAME_CONFLICT);
    private static final Map<String, DiagnosticCode> BY_ID = ALL.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    DiagnosticCode::value, code -> code));

    private CompilerDiagnosticCodes() {
    }

    public static List<DiagnosticCode> all() {
        return ALL;
    }

    public static Optional<DiagnosticCode> lookup(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(value));
    }

    public static DiagnosticCode require(String value) {
        Objects.requireNonNull(value, "value");
        return lookup(value).orElseThrow(() -> new IllegalArgumentException(
                "unknown compiler diagnostic code: " + value));
    }
}
