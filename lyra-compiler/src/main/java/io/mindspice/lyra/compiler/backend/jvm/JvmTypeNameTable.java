package io.mindspice.lyra.compiler.backend.jvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable generated-name table shared by the mapper and type planner.
 * Tuple/function names depend only on their canonical Lyra contracts and the
 * configured base package.
 */
final class JvmTypeNameTable {
    public static final String DEFAULT_BASE_PACKAGE = "lyra.generated";
    private static final Pattern PACKAGE_PART = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final java.util.Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null", "_", "record",
            "sealed", "permits", "non-sealed", "var", "yield",
            "module", "open", "opens", "requires", "transitive", "exports", "to",
            "uses", "provides", "with", "when");

    private final String basePackage;
    private final Map<String, String> tupleNames;
    private final Map<String, String> functionNames;

    public JvmTypeNameTable(
            String basePackage,
            Map<String, String> tupleNames,
            Map<String, String> functionNames) {
        this.basePackage = validatePackage(basePackage);
        this.tupleNames = validateNames(tupleNames, "Tuple<", "tupleNames");
        this.functionNames = validateNames(functionNames, "Fn<", "functionNames");
        if (!Collections.disjoint(this.tupleNames.values(), this.functionNames.values())) {
            throw new IllegalArgumentException("tuple and function generated names collide");
        }
    }

    public static JvmTypeNameTable empty() {
        return new JvmTypeNameTable(DEFAULT_BASE_PACKAGE, Map.of(), Map.of());
    }

    public static JvmTypeNameTable forPackage(String basePackage) {
        return new JvmTypeNameTable(basePackage, Map.of(), Map.of());
    }

    public static JvmTypeNameTable of(
            String basePackage,
            Map<String, String> tupleNames,
            Map<String, String> functionNames) {
        return new JvmTypeNameTable(basePackage, tupleNames, functionNames);
    }

    public String basePackage() {
        return basePackage;
    }

    public Map<String, String> tupleNames() {
        return tupleNames;
    }

    public Map<String, String> functionNames() {
        return functionNames;
    }

    public String tupleBinaryName(String canonicalType) {
        return nameFor(canonicalType, tupleNames, "tuple");
    }

    public String functionBinaryName(String canonicalSignature) {
        return nameFor(canonicalSignature, functionNames, "function");
    }

    public String nominalBinaryName(String canonicalType) {
        JvmTypePlan.requireCanonicalCompositeSpelling(canonicalType, "Nominal<");
        return basePackage + ".$lyra$nominal$" + canonicalType.substring(8, canonicalType.length() - 1);
    }

    /**
     * Occurrence-scoped callable member route delegate, keyed by the exact
     * nominal declaration identity and declaration-order field index.  Only
     * session artifacts emit these shared structural classes.
     */
    public String nominalMemberDelegateBinaryName(String nominalCanonicalType, int fieldIndex) {
        if (fieldIndex < 0) {
            throw new IllegalArgumentException("nominal member delegate field index must be non-negative");
        }
        String nominal = nominalBinaryName(nominalCanonicalType);
        String hash = nominal.substring(nominal.lastIndexOf('.') + "$lyra$nominal$".length() + 1);
        return basePackage + ".$lyra$delegate$" + hash + "$" + fieldIndex;
    }

    public boolean hasTuple(String canonicalType) {
        return tupleNames.containsKey(Objects.requireNonNull(canonicalType, "canonicalType"));
    }

    public boolean hasFunction(String canonicalSignature) {
        return functionNames.containsKey(Objects.requireNonNull(canonicalSignature, "canonicalSignature"));
    }

    public String tupleDescriptor(String canonicalType) {
        return "L" + tupleBinaryName(canonicalType).replace('.', '/') + ";";
    }

    public String functionDescriptor(String canonicalSignature) {
        return "L" + functionBinaryName(canonicalSignature).replace('.', '/') + ";";
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof JvmTypeNameTable table
                && basePackage.equals(table.basePackage)
                && tupleNames.equals(table.tupleNames)
                && functionNames.equals(table.functionNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(basePackage, tupleNames, functionNames);
    }

    @Override
    public String toString() {
        return "JvmTypeNameTable[basePackage=" + basePackage
                + ", tuples=" + tupleNames.size()
                + ", functions=" + functionNames.size() + "]";
    }

    private String nameFor(String canonical, Map<String, String> names, String kind) {
        Objects.requireNonNull(canonical, "canonical");
        JvmTypePlan.requireCanonicalCompositeSpelling(canonical,
                kind.equals("tuple") ? "Tuple<" : "Fn<");
        String explicit = names.get(canonical);
        if (explicit != null) {
            return explicit;
        }
        String prefix = kind.equals("tuple") ? "$lyra$tuple$" : "$lyra$fn$";
        return basePackage + "." + prefix
                + JvmStableHash.sha256("LYRA-JVM-GENERATED-TYPE", kind, canonical).substring(0, 16);
    }

    private Map<String, String> validateNames(
            Map<String, String> values, String requiredPrefix, String field) {
        Objects.requireNonNull(values, field);
        ArrayList<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            String key = Objects.requireNonNull(entry.getKey(), field + " key");
            String value = Objects.requireNonNull(entry.getValue(), field + " value");
            try {
                JvmTypePlan.requireCanonicalCompositeSpelling(key, requiredPrefix);
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException(field + " key is not canonical: " + key,
                        failure);
            }
            validateBinaryName(value, basePackage);
            String simpleName = value.substring(value.lastIndexOf('.') + 1);
            String generatedPrefix = requiredPrefix.equals("Tuple<")
                    ? "$lyra$tuple$" : "$lyra$fn$";
            if (!simpleName.startsWith(generatedPrefix)
                    || simpleName.length() == generatedPrefix.length()) {
                throw new IllegalArgumentException(
                        field + " name disagrees with its generated type family: " + value);
            }
            if (copied.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate " + field + " key: " + key);
            }
        }
        if (copied.values().stream().distinct().count() != copied.size()) {
            throw new IllegalArgumentException(field + " contain duplicate generated names");
        }
        return Collections.unmodifiableMap(copied);
    }

    private static String validatePackage(String value) {
        Objects.requireNonNull(value, "basePackage");
        if (value.isBlank() || value.startsWith("/") || value.contains("\\")
                || value.contains("..") || value.contains("/")) {
            throw new IllegalArgumentException("base package must be a relative Java package: " + value);
        }
        for (String part : value.split("\\.", -1)) {
            if (!PACKAGE_PART.matcher(part).matches() || JAVA_KEYWORDS.contains(part)) {
                throw new IllegalArgumentException("invalid Java package component: " + part);
            }
        }
        return value;
    }

    private static void validateBinaryName(String value, String basePackage) {
        JvmNames.requireBinaryName(value, "generated name");
        if (!value.startsWith(basePackage + ".")) {
            throw new IllegalArgumentException("generated name is outside the base package: " + value);
        }
        String simple = value.substring(value.lastIndexOf('.') + 1);
        if (!simple.startsWith("$lyra$") || simple.length() == "$lyra$".length()) {
            throw new IllegalArgumentException("generated class name must use $lyra$: " + value);
        }
    }
}
