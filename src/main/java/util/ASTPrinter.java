package util;


import lang.ast.ASTNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.util.Iterator;

public class ASTPrinter {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new Jdk8Module())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    // Alternative if you can't add Jdk8Module dependency
    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper()
            .disable(MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    public static void debugPrint(ASTNode node) {
        try {
            String json = MAPPER.writeValueAsString(node);
            JsonNode tree = MAPPER.readTree(json);
            System.out.println(formatTreeStructure(tree, 0));
        } catch (Exception e) {
            System.err.println("Debug serialization failed: " + e.getMessage());
            fallbackPrint(node);
        }
    }

    public static void debugPrint(ASTNode.CompilationUnit unit) {
        try {
            String json = MAPPER.writeValueAsString(unit);
            JsonNode tree = MAPPER.readTree(json);
            System.out.println(formatTreeStructure(tree, 0));
        } catch (Exception e) {
            System.err.println("Debug serialization failed: " + e.getMessage());
            fallbackPrint(unit);
        }
    }

    private static String formatTreeStructure(JsonNode node, int depth) {
        StringBuilder sb = new StringBuilder();
        String indent = "  ".repeat(depth);

        if (node.isObject()) {
            // For Value and Operation nodes, show complete info in header and skip redundant fields
            String nodeType = getNodeTypeHeader(node);

            if (isCompleteNodeType(node)) {
                // Complete nodes like Value[I32=42] or Operation[Plus] don't need field expansion
                sb.append(indent).append(nodeType).append("\n");

                // Only show non-redundant fields
                Iterator<String> fieldNames = node.fieldNames();
                while (fieldNames.hasNext()) {
                    String fieldName = fieldNames.next();
                    JsonNode fieldValue = node.get(fieldName);

                    if (shouldSkipField(fieldName, fieldValue) || isRedundantField(fieldName, node)) {
                        continue;
                    }

                    sb.append(formatFieldValue(fieldName, fieldValue, depth + 1));
                }
            } else {
                // Complex nodes need field expansion
                sb.append(indent).append(nodeType).append("\n");

                Iterator<String> fieldNames = node.fieldNames();
                while (fieldNames.hasNext()) {
                    String fieldName = fieldNames.next();
                    JsonNode fieldValue = node.get(fieldName);

                    if (shouldSkipField(fieldName, fieldValue)) {
                        continue;
                    }

                    sb.append(formatFieldValue(fieldName, fieldValue, depth + 1));
                }
            }
        }
        else if (node.isArray()) {
            if (node.isEmpty()) {
                sb.append(indent).append("[]");
            } else {
                for (int i = 0; i < node.size(); i++) {
                    JsonNode element = node.get(i);
                    sb.append(indent).append("[").append(i).append("]: ");

                    if (isSimpleValue(element)) {
                        sb.append(formatSimpleValue(element));
                    } else if (element.isObject()) {
                        // Put object type on same line as array index
                        String nodeType = getNodeTypeHeader(element);
                        sb.append(nodeType);

                        // Add fields on subsequent lines if needed
                        Iterator<String> fieldNames = element.fieldNames();
                        while (fieldNames.hasNext()) {
                            String fieldName = fieldNames.next();
                            JsonNode fieldValue = element.get(fieldName);

                            if (shouldSkipField(fieldName, fieldValue) || isRedundantField(fieldName, element)) {
                                continue;
                            }

                            sb.append("\n").append(formatFieldValue(fieldName, fieldValue, depth + 1));
                        }
                    }
                    sb.append("\n");
                }
            }
        }
        else {
            sb.append(indent).append(formatSimpleValue(node));
        }

        return sb.toString();
    }

    private static String formatFieldValue(String fieldName, JsonNode fieldValue, int depth) {
        StringBuilder sb = new StringBuilder();
        String indent = "  ".repeat(depth);

        sb.append(indent).append(fieldName).append(": ");

        if (isSimpleValue(fieldValue)) {
            sb.append(formatSimpleValue(fieldValue)).append("\n");
        } else {
            sb.append("\n").append(formatTreeStructure(fieldValue, depth + 1));
        }

        return sb.toString();
    }

    private static boolean isCompleteNodeType(JsonNode node) {
        // Value and simple Operation nodes are complete in their header
        return node.has("value") ||
               (node.has("op") && (!node.has("operands") || node.get("operands").size() <= 2));
    }

    private static boolean isRedundantField(String fieldName, JsonNode parentNode) {
        // Skip fields that are already shown in the node header
        if ("op".equals(fieldName) && parentNode.has("op")) return true;
        if ("value".equals(fieldName) && parentNode.has("value")) return true;
        return false;
    }

    private static String getNodeTypeHeader(JsonNode node) {
        // Create meaningful headers for different AST node types
        if (node.has("op")) {
            return "Operation[" + node.get("op").asText() + "]";
        }
        if (node.has("value")) {
            JsonNode value = node.get("value");
            return "Value[" + getValueTypeDescription(value) + "]";
        }
        if (node.has("parameters") && node.has("body")) {
            int paramCount = node.get("parameters").isArray() ? node.get("parameters").size() : 0;
            boolean isForm = node.has("isForm") && node.get("isForm").asBoolean();
            return "Lambda[params=" + paramCount + ", form=" + isForm + "]";
        }
        if (node.has("predExpr") && node.has("predForm")) {
            return "Predicate";
        }
        if (node.has("identifier") && node.get("identifier").has("identifier")) {
            return "Identifier[" + node.get("identifier").get("identifier").asText() + "]";
        }
        if (node.has("modifiers")) {
            return "Parameter";
        }
        if (node.has("topMost")) {
            int count = node.get("topMost").isArray() ? node.get("topMost").size() : 0;
            return "CompilationUnit[expressions=" + count + "]";
        }
        if (node.has("thenExpr") || node.has("elseExpr")) {
            return "PredicateForm";
        }
        if (node.has("expressionChain")) {
            return "MemberExpression";
        }
        if (node.has("expressions")) {
            return "Block";
        }
        if (node.has("assignment")) {
            return "Statement[" + (node.has("identifier") ? "Let" : "Assign") + "]";
        }

        return "Object";
    }

    private static String getValueTypeDescription(JsonNode value) {
        if (value.has("i32")) return "I32=" + value.get("i32").asInt();
        if (value.has("i64")) return "I64=" + value.get("i64").asLong();
        if (value.has("f32")) return "F32=" + value.get("f32").asDouble();
        if (value.has("f64")) return "F64=" + value.get("f64").asDouble();
        if (value.has("b")) return "Bool=" + value.get("b").asBoolean();
        if (value.has("s")) return "Str=\"" + value.get("s").asText() + "\"";
        if (value.has("symbol")) {
            JsonNode symbol = value.get("symbol");
            if (symbol.has("identifier")) {
                return "Symbol=" + symbol.get("identifier").asText();
            }
        }
        return "Unknown";
    }

    private static boolean shouldSkipField(String fieldName, JsonNode value) {
        // Skip metadata unless it has meaningful content
        if ("metaData".equals(fieldName)) {
            return value.isObject() && value.size() == 0;
        }

        // Skip empty arrays and objects for cleaner output
        if ((value.isArray() && value.size() == 0) ||
            (value.isObject() && value.size() == 0)) {
            return true;
        }

        return false;
    }

    private static boolean isSimpleValue(JsonNode node) {
        if (node.isValueNode() || node.isTextual() || node.isNumber() || node.isBoolean()) {
            return true;
        }

        // Single-field objects with simple values can be inline
        if (node.isObject() && node.size() == 1) {
            JsonNode firstValue = node.elements().next();
            return firstValue.isValueNode() || firstValue.isTextual() || firstValue.isNumber() || firstValue.isBoolean();
        }

        // Empty collections are simple
        if ((node.isArray() || node.isObject()) && node.size() == 0) {
            return true;
        }

        return false;
    }

    private static String formatSimpleValue(JsonNode node) {
        if (node.isTextual()) {
            return "\"" + node.asText() + "\"";
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.toString();
        }
        if (node.isObject() && node.size() == 1) {
            Iterator<String> fields = node.fieldNames();
            String key = fields.next();
            JsonNode value = node.get(key);
            return key + "=" + formatSimpleValue(value);
        }
        if (node.isArray() && node.isEmpty()) {
            return "[]";
        }
        if (node.isObject() && node.isEmpty()) {
            return "{}";
        }

        return node.toString();
    }

    private static void fallbackPrint(Object obj) {
        try {
            String json = FALLBACK_MAPPER.writeValueAsString(obj);
            JsonNode tree = FALLBACK_MAPPER.readTree(json);
            System.out.println(formatTreeStructure(tree, 0));
        } catch (Exception e2) {
            System.err.println("Fallback also failed: " + e2.getMessage());
            System.out.println("AST toString(): " + obj.toString());
        }
    }

    // Minimal variant for quick debugging
    public static void debugPrintCompact(ASTNode node) {
        try {
            String json = MAPPER.writeValueAsString(node);
            JsonNode tree = MAPPER.readTree(json);
            System.out.println(formatCompactTree(tree, 0));
        } catch (Exception e) {
            System.err.println("Compact debug failed: " + e.getMessage());
        }
    }

    private static String formatCompactTree(JsonNode node, int depth) {
        StringBuilder sb = new StringBuilder();
        String indent = "  ".repeat(depth);

        if (node.isObject()) {
            String header = getNodeTypeHeader(node);
            sb.append(indent).append(header);

            // Show key fields inline when possible
            if (node.has("op")) {
                sb.append(" {");
                if (node.has("operands") && node.get("operands").isArray()) {
                    sb.append(" operands=").append(node.get("operands").size());
                }
                sb.append(" }");
            }
            sb.append("\n");

            // Only show non-trivial child nodes
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                JsonNode value = node.get(field);

                if (shouldSkipField(field, value) || "op".equals(field)) {
                    continue;
                }

                if (!isSimpleValue(value)) {
                    sb.append(formatCompactTree(value, depth + 1));
                }
            }
        }
        else if (node.isArray() && node.size() > 0) {
            sb.append(indent).append("Array[").append(node.size()).append("]\n");
            for (JsonNode element : node) {
                sb.append(formatCompactTree(element, depth + 1));
            }
        }

        return sb.toString();
    }
}