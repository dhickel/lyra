package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.types.BindingContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Production Phase-14 ABI mapper.  It plans descriptors and materialization
 * only; it never emits a class or lowers a method body.
 */
final class JvmAbiMapper {
    private static final String STRING_CLASS = "java.lang.String";

    private final JvmTypeNameTable names;

    public JvmAbiMapper() {
        this(JvmTypeNameTable.empty());
    }

    public JvmAbiMapper(String basePackage) {
        this(JvmTypeNameTable.forPackage(basePackage));
    }

    public JvmAbiMapper(JvmTypeNameTable names) {
        this.names = Objects.requireNonNull(names, "names");
    }

    public JvmTypeNameTable names() {
        return names;
    }

    public JvmTypePlan map(io.mindspice.lyra.compiler.types.LyraType type) {
        return map(type, JvmMappingContext.INTERNAL_VALUE);
    }

    public JvmTypePlan map(io.mindspice.lyra.runtime.LyraType type) {
        return map(type, JvmMappingContext.INTERNAL_VALUE);
    }

    public JvmTypePlan map(
            io.mindspice.lyra.compiler.types.LyraType type,
            JvmMappingContext context) {
        return mapView(compilerView(type), context);
    }

    public JvmTypePlan map(
            io.mindspice.lyra.runtime.LyraType type,
            JvmMappingContext context) {
        return mapView(runtimeView(type), context);
    }

    public JvmTypePlan mapType(
            io.mindspice.lyra.compiler.types.LyraType type,
            JvmMappingContext context) {
        return map(type, context);
    }

    public JvmTypePlan mapType(
            io.mindspice.lyra.runtime.LyraType type,
            JvmMappingContext context) {
        return map(type, context);
    }

    public JvmTypePlan mapCompiler(
            io.mindspice.lyra.compiler.types.LyraType type,
            JvmMappingContext context) {
        return map(type, context);
    }

    public String descriptor(
            io.mindspice.lyra.compiler.types.LyraType type,
            JvmMappingContext context) {
        return map(type, context).descriptor();
    }

    public String descriptor(
            io.mindspice.lyra.runtime.LyraType type,
            JvmMappingContext context) {
        return map(type, context).descriptor();
    }

    public JvmTypePlan mapRuntime(
            io.mindspice.lyra.runtime.LyraType type,
            JvmMappingContext context) {
        return map(type, context);
    }

    /** Maps a declaration contract while retaining binding-local @mut. */
    public JvmBindingPlan mapBinding(BindingContract contract) {
        return mapBinding(contract, JvmAbiBoundary.INTERNAL);
    }

    /** Binding storage is compiler-owned; Java-visible callers use exported values. */
    public JvmBindingPlan mapBinding(BindingContract contract, JvmAbiBoundary boundary) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(boundary, "boundary");
        if (boundary != JvmAbiBoundary.INTERNAL) {
            throw new IllegalArgumentException("binding storage must use the internal ABI boundary");
        }
        JvmTypePlan value = map(contract.valueType(), JvmMappingContext.INTERNAL_BINDING);
        return JvmBindingPlan.of(contract, value);
    }

    public JvmSignaturePlan mapSignature(
            io.mindspice.lyra.compiler.types.LyraSignature signature) {
        return mapSignature(signature, JvmAbiBoundary.JAVA_VISIBLE);
    }

    public JvmSignaturePlan mapSignature(
            io.mindspice.lyra.runtime.LyraSignature signature) {
        return mapSignature(signature, JvmAbiBoundary.JAVA_VISIBLE);
    }

    public JvmSignaturePlan mapSignature(
            io.mindspice.lyra.compiler.types.LyraSignature signature,
            JvmAbiBoundary boundary) {
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(boundary, "boundary");
        List<JvmTypePlan> parameters = signature.parameterTypes().stream()
                .map(type -> map(type, JvmMappingContext.of(
                        boundary, JvmValuePosition.FUNCTION_PARAMETER)))
                .toList();
        JvmTypePlan returnValue = map(signature.returnType(), JvmMappingContext.of(
                boundary, JvmValuePosition.FUNCTION_RETURN));
        return signaturePlan(signature.canonicalSpelling(), boundary, parameters, returnValue);
    }

    public JvmSignaturePlan mapSignature(
            io.mindspice.lyra.runtime.LyraSignature signature,
            JvmAbiBoundary boundary) {
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(boundary, "boundary");
        List<JvmTypePlan> parameters = signature.parameterTypes().stream()
                .map(type -> map(type, JvmMappingContext.of(
                        boundary, JvmValuePosition.FUNCTION_PARAMETER)))
                .toList();
        JvmTypePlan returnValue = map(signature.returnType(), JvmMappingContext.of(
                boundary, JvmValuePosition.FUNCTION_RETURN));
        return signaturePlan(signature.canonicalSpelling(), boundary, parameters, returnValue);
    }

    public JvmSignaturePlan mapSignature(
            io.mindspice.lyra.compiler.types.FunctionType function,
            JvmAbiBoundary boundary) {
        Objects.requireNonNull(function, "function");
        return mapSignature(function.signature(), boundary);
    }

    public JvmSignaturePlan mapSignature(
            io.mindspice.lyra.runtime.FunctionType function,
            JvmAbiBoundary boundary) {
        Objects.requireNonNull(function, "function");
        return mapSignature(function.signature(), boundary);
    }

    public String methodDescriptor(
            io.mindspice.lyra.compiler.types.LyraSignature signature,
            JvmAbiBoundary boundary) {
        return mapSignature(signature, boundary).descriptor();
    }

    public String methodDescriptor(
            io.mindspice.lyra.runtime.LyraSignature signature,
            JvmAbiBoundary boundary) {
        return mapSignature(signature, boundary).descriptor();
    }

    /** Compares compiler and runtime foundations without changing either model. */
    public JvmTypeParity compare(
            io.mindspice.lyra.compiler.types.LyraType compilerType,
            io.mindspice.lyra.runtime.LyraType runtimeType,
            JvmMappingContext context) {
        JvmTypePlan compilerPlan = map(compilerType, context);
        JvmTypePlan runtimePlan = map(runtimeType, context);
        ArrayList<String> differences = new ArrayList<>();
        if (!compilerPlan.canonicalLyraType().equals(runtimePlan.canonicalLyraType())) {
            differences.add("canonical Lyra type differs");
        }
        if (!compilerPlan.representation().equals(runtimePlan.representation())) {
            differences.add("JVM representation differs");
        }
        if (!compilerPlan.materialization().equals(runtimePlan.materialization())) {
            differences.add("materialization differs");
        }
        return new JvmTypeParity(differences.isEmpty(), compilerPlan.canonicalLyraType(),
                runtimePlan.canonicalLyraType(), compilerPlan.representation().canonicalSpelling(),
                runtimePlan.representation().canonicalSpelling(), differences);
    }

    public void requireTypeParity(
            io.mindspice.lyra.compiler.types.LyraType compilerType,
            io.mindspice.lyra.runtime.LyraType runtimeType,
            JvmMappingContext context) {
        compare(compilerType, runtimeType, context).requireMatch();
    }

    /** Compares canonical Lyra signatures and exact JVM descriptors independently. */
    public JvmSignatureParity compareSignatures(
            io.mindspice.lyra.compiler.types.LyraSignature compilerSignature,
            io.mindspice.lyra.runtime.LyraSignature runtimeSignature,
            JvmAbiBoundary boundary) {
        JvmSignaturePlan compilerPlan = mapSignature(compilerSignature, boundary);
        JvmSignaturePlan runtimePlan = mapSignature(runtimeSignature, boundary);
        ArrayList<String> differences = new ArrayList<>();
        if (!compilerPlan.canonicalLyraSignature().equals(runtimePlan.canonicalLyraSignature())) {
            differences.add("canonical Lyra signature differs");
        }
        if (!compilerPlan.descriptor().equals(runtimePlan.descriptor())) {
            differences.add("JVM method descriptor differs");
        }
        if (compilerPlan.parameters().size() != runtimePlan.parameters().size()) {
            differences.add("parameter arity differs");
        } else {
            for (int index = 0; index < compilerPlan.parameters().size(); index++) {
                if (!compilerPlan.parameters().get(index).equals(runtimePlan.parameters().get(index))) {
                    differences.add("parameter mapping differs at index " + index);
                }
            }
        }
        if (!compilerPlan.returnValue().equals(runtimePlan.returnValue())) {
            differences.add("return mapping differs");
        }
        return new JvmSignatureParity(differences.isEmpty(), compilerPlan.canonicalLyraSignature(),
                runtimePlan.canonicalLyraSignature(), compilerPlan.descriptor(),
                runtimePlan.descriptor(), differences);
    }

    public JvmSignatureParity signatureParity(
            io.mindspice.lyra.compiler.types.LyraSignature compilerSignature,
            io.mindspice.lyra.runtime.LyraSignature runtimeSignature,
            JvmAbiBoundary boundary) {
        return compareSignatures(compilerSignature, runtimeSignature, boundary);
    }

    public void requireSignatureParity(
            io.mindspice.lyra.compiler.types.LyraSignature compilerSignature,
            io.mindspice.lyra.runtime.LyraSignature runtimeSignature,
            JvmAbiBoundary boundary) {
        compareSignatures(compilerSignature, runtimeSignature, boundary).requireMatch();
    }

    private static JvmSignaturePlan signaturePlan(
            String canonical,
            JvmAbiBoundary boundary,
            List<JvmTypePlan> parameters,
            JvmTypePlan returnValue) {
        String descriptor = "(" + parameters.stream().map(JvmTypePlan::descriptor)
                .reduce("", String::concat) + ")" + returnValue.descriptor();
        return new JvmSignaturePlan(canonical, boundary, parameters, returnValue, descriptor);
    }

    private JvmTypePlan mapView(TypeView view, JvmMappingContext context) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(context, "context");
        if (view.mutable() && !context.permitsMutableQualifier()) {
            throw new IllegalArgumentException("@mut is invalid at " + context.position());
        }

        return switch (view.shape()) {
            case PRIMITIVE -> mapPrimitive(view, context);
            case ARRAY -> mapArray(view, context);
            case TUPLE -> mapTuple(view, context);
            case FUNCTION -> mapFunctionValue(view, context);
        };
    }

    private JvmTypePlan mapPrimitive(TypeView view, JvmMappingContext context) {
        String spelling = view.primitiveSpelling();
        if (spelling.equals("String")) {
            JvmType type = JvmType.reference(STRING_CLASS);
            return singlePlan(view, context, type,
                    view.nilable() ? JvmMaterializationKind.NULLABLE_REFERENCE
                            : JvmMaterializationKind.DIRECT,
                    view.nilable() ? "@nil reference uses JVM null" : "concrete String reference");
        }
        if (spelling.equals("Unit")) {
            if (!view.nilable() && context.position().isFunctionReturn()) {
                JvmType type = JvmType.primitive("V");
                return singlePlan(view, context, type,
                        JvmMaterializationKind.LANGUAGE_UNIT_RETURN_VOID,
                        "language-function Unit return is JVM void");
            }
            JvmType type = JvmType.unit();
            return singlePlan(view, context, type,
                    view.nilable() ? JvmMaterializationKind.NULLABLE_REFERENCE
                            : JvmMaterializationKind.UNIT_SINGLETON,
                    view.nilable() ? "@nil Unit uses nullable LyraUnit"
                            : "Unit value uses LyraUnit.INSTANCE");
        }

        JvmType primitive = JvmType.primitive(primitiveDescriptor(spelling));
        if (!view.nilable()) {
            return singlePlan(view, context, primitive, JvmMaterializationKind.DIRECT,
                    "non-nil primitive remains unboxed");
        }
        if (context.permitsInternalPresencePayload()) {
            JvmPresencePayload representation = new JvmPresencePayload(
                    JvmType.primitive("Z"), primitive);
            return plan(view, context, representation,
                    JvmMaterializationKind.INTERNAL_PRESENCE_PAYLOAD,
                    "internal @nil primitive uses presence bit plus raw payload");
        }
        JvmType wrapper = JvmType.wrapper(wrapperClass(spelling));
        return singlePlan(view, context, wrapper,
                JvmMaterializationKind.NULLABLE_PRIMITIVE_WRAPPER,
                "@nil primitive uses its Java wrapper at a fixed or Java-visible boundary");
    }

    private JvmTypePlan mapArray(TypeView view, JvmMappingContext context) {
        TypeView element = view.children().getFirst();
        JvmTypePlan elementPlan = mapView(element,
                JvmMappingContext.of(context.boundary(), JvmValuePosition.ARRAY_ELEMENT));
        if (!elementPlan.isSingleValue() || elementPlan.descriptor().equals("V")) {
            throw new IllegalArgumentException("array element has no single JVM descriptor: "
                    + element.canonical());
        }
        JvmType array = JvmType.array(elementPlan.descriptor());
        boolean wrappedElements = elementPlan.materialization().kind()
                == JvmMaterializationKind.NULLABLE_PRIMITIVE_WRAPPER;
        JvmMaterializationKind materialization = wrappedElements
                ? view.nilable() ? JvmMaterializationKind.NULLABLE_ARRAY_WRAPPER_ELEMENTS
                : JvmMaterializationKind.ARRAY_WRAPPER_ELEMENTS
                : view.nilable() ? JvmMaterializationKind.NULLABLE_REFERENCE
                : JvmMaterializationKind.DIRECT;
        String reason = wrappedElements
                ? view.nilable()
                ? "@nil array uses JVM null and nilable primitive elements use wrappers"
                : "array of nilable primitives uses wrapper elements to preserve array identity"
                : elementPlan.materialization().kind() == JvmMaterializationKind.UNIT_SINGLETON
                ? "array elements materialize Unit as LyraUnit.INSTANCE"
                : elementPlan.materialization().kind() == JvmMaterializationKind.NULLABLE_REFERENCE
                ? "nilable reference array elements use JVM null"
                : view.nilable() ? "@nil array reference uses JVM null"
                : "array uses the exact JVM primitive/reference array descriptor";
        return singlePlan(view, context, array, materialization, reason);
    }

    private JvmTypePlan mapTuple(TypeView view, JvmMappingContext context) {
        for (TypeView member : view.children()) {
            mapView(member, JvmMappingContext.of(context.boundary(), JvmValuePosition.TUPLE_FIELD));
        }
        String binaryName = names.tupleBinaryName(view.baseCanonical());
        JvmType type = JvmType.reference(binaryName, JvmTypeKind.TUPLE);
        return singlePlan(view, context, type,
                view.nilable() ? JvmMaterializationKind.NULLABLE_REFERENCE
                        : JvmMaterializationKind.DIRECT,
                view.nilable() ? "@nil tuple uses JVM null" : "deterministic generated tuple value class");
    }

    private JvmTypePlan mapFunctionValue(TypeView view, JvmMappingContext context) {
        for (int index = 0; index < view.children().size() - 1; index++) {
            mapView(view.children().get(index), JvmMappingContext.of(
                    context.boundary(), JvmValuePosition.FUNCTION_PARAMETER));
        }
        mapView(view.children().getLast(), JvmMappingContext.of(
                context.boundary(), JvmValuePosition.FUNCTION_RETURN));
        String binaryName = names.functionBinaryName(view.baseCanonical());
        JvmType type = JvmType.reference(binaryName, JvmTypeKind.FUNCTION);
        return singlePlan(view, context, type,
                view.nilable() ? JvmMaterializationKind.NULLABLE_REFERENCE
                        : JvmMaterializationKind.DIRECT,
                view.nilable() ? "@nil function value uses JVM null"
                        : "deterministic generated typed functional interface");
    }

    private static JvmTypePlan singlePlan(
            TypeView view,
            JvmMappingContext context,
            JvmType type,
            JvmMaterializationKind kind,
            String reason) {
        return plan(view, context, new JvmSingleValue(type), kind, reason);
    }

    private static JvmTypePlan plan(
            TypeView view,
            JvmMappingContext context,
            JvmValueRepresentation representation,
            JvmMaterializationKind kind,
            String reason) {
        return new JvmTypePlan(view.canonical(), view.baseCanonical(), view.qualifiers(), context,
                representation, new JvmMaterializationPlan(kind, context, reason,
                        representation.components().stream().map(JvmType::descriptor).toList()));
    }

    private static String primitiveDescriptor(String spelling) {
        return switch (spelling) {
            case "I8", "U8" -> "B";
            case "I16", "U16" -> "S";
            case "I32", "U32" -> "I";
            case "I64", "U64" -> "J";
            case "F32" -> "F";
            case "F64" -> "D";
            case "Bool" -> "Z";
            case "Char" -> "C";
            default -> throw new IllegalArgumentException("not a JVM primitive Lyra type: " + spelling);
        };
    }

    private static String wrapperClass(String spelling) {
        return switch (spelling) {
            case "I8", "U8" -> "java.lang.Byte";
            case "I16", "U16" -> "java.lang.Short";
            case "I32", "U32" -> "java.lang.Integer";
            case "I64", "U64" -> "java.lang.Long";
            case "F32" -> "java.lang.Float";
            case "F64" -> "java.lang.Double";
            case "Bool" -> "java.lang.Boolean";
            case "Char" -> "java.lang.Character";
            default -> throw new IllegalArgumentException("not a nullable primitive: " + spelling);
        };
    }

    private static List<String> qualifiers(io.mindspice.lyra.compiler.types.LyraType type) {
        ArrayList<String> result = new ArrayList<>(2);
        if (type.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.MUT)) {
            result.add("@mut");
        }
        if (type.hasQualifier(io.mindspice.lyra.compiler.types.TypeQualifier.NIL)) {
            result.add("@nil");
        }
        return List.copyOf(result);
    }

    private static List<String> qualifiers(io.mindspice.lyra.runtime.LyraType type) {
        ArrayList<String> result = new ArrayList<>(2);
        if (type.hasQualifier(io.mindspice.lyra.runtime.TypeQualifier.MUT)) {
            result.add("@mut");
        }
        if (type.hasQualifier(io.mindspice.lyra.runtime.TypeQualifier.NIL)) {
            result.add("@nil");
        }
        return List.copyOf(result);
    }

    private static TypeView compilerView(io.mindspice.lyra.compiler.types.LyraType type) {
        Objects.requireNonNull(type, "type");
        io.mindspice.lyra.compiler.types.LyraType base = type.withoutQualifiers();
        List<String> qualifiers = qualifiers(type);
        if (base instanceof io.mindspice.lyra.compiler.types.PrimitiveType primitive) {
            return new TypeView(type.canonicalSpelling(), base.canonicalSpelling(), qualifiers,
                    Shape.PRIMITIVE, primitive.canonicalSpelling(), List.of());
        }
        if (base instanceof io.mindspice.lyra.compiler.types.ArrayType array) {
            return new TypeView(type.canonicalSpelling(), base.canonicalSpelling(), qualifiers,
                    Shape.ARRAY, null, List.of(compilerView(array.elementType())));
        }
        if (base instanceof io.mindspice.lyra.compiler.types.TupleType tuple) {
            return new TypeView(type.canonicalSpelling(), base.canonicalSpelling(), qualifiers,
                    Shape.TUPLE, null, tuple.memberTypes().stream()
                    .map(JvmAbiMapper::compilerView).toList());
        }
        if (base instanceof io.mindspice.lyra.compiler.types.FunctionType function) {
            ArrayList<TypeView> children = new ArrayList<>();
            function.parameterTypes().stream().map(JvmAbiMapper::compilerView).forEach(children::add);
            children.add(compilerView(function.returnType()));
            return new TypeView(type.canonicalSpelling(), base.canonicalSpelling(), qualifiers,
                    Shape.FUNCTION, null, children);
        }
        throw new IllegalArgumentException("unsupported compiler Lyra type: " + type.getClass().getName());
    }

    private static TypeView runtimeView(io.mindspice.lyra.runtime.LyraType type) {
        Objects.requireNonNull(type, "type");
        io.mindspice.lyra.runtime.LyraType base = type.withoutQualifiers();
        List<String> qualifiers = qualifiers(type);
        if (base instanceof io.mindspice.lyra.runtime.PrimitiveType primitive) {
            return new TypeView(type.canonicalSpelling(), base.canonicalSpelling(), qualifiers,
                    Shape.PRIMITIVE, primitive.canonicalSpelling(), List.of());
        }
        if (base instanceof io.mindspice.lyra.runtime.ArrayType array) {
            return new TypeView(type.canonicalSpelling(), base.canonicalSpelling(), qualifiers,
                    Shape.ARRAY, null, List.of(runtimeView(array.elementType())));
        }
        if (base instanceof io.mindspice.lyra.runtime.TupleType tuple) {
            return new TypeView(type.canonicalSpelling(), base.canonicalSpelling(), qualifiers,
                    Shape.TUPLE, null, tuple.memberTypes().stream()
                    .map(JvmAbiMapper::runtimeView).toList());
        }
        if (base instanceof io.mindspice.lyra.runtime.FunctionType function) {
            ArrayList<TypeView> children = new ArrayList<>();
            function.parameterTypes().stream().map(JvmAbiMapper::runtimeView).forEach(children::add);
            children.add(runtimeView(function.returnType()));
            return new TypeView(type.canonicalSpelling(), base.canonicalSpelling(), qualifiers,
                    Shape.FUNCTION, null, children);
        }
        throw new IllegalArgumentException("unsupported runtime Lyra type: " + type.getClass().getName());
    }

    private enum Shape {
        PRIMITIVE,
        ARRAY,
        TUPLE,
        FUNCTION
    }

    private record TypeView(
            String canonical,
            String baseCanonical,
            List<String> qualifiers,
            Shape shape,
            String primitiveSpelling,
            List<TypeView> children) {
        private TypeView {
            Objects.requireNonNull(canonical, "canonical");
            Objects.requireNonNull(baseCanonical, "baseCanonical");
            qualifiers = List.copyOf(qualifiers);
            Objects.requireNonNull(shape, "shape");
            children = List.copyOf(children);
            if (shape == Shape.PRIMITIVE && primitiveSpelling == null) {
                throw new IllegalArgumentException("primitive view has no spelling");
            }
            if (shape == Shape.ARRAY && children.size() != 1) {
                throw new IllegalArgumentException("array view needs one element");
            }
            if (shape == Shape.FUNCTION && children.isEmpty()) {
                throw new IllegalArgumentException("function view needs a return child");
            }
        }

        private boolean mutable() {
            return qualifiers.contains("@mut");
        }

        private boolean nilable() {
            return qualifiers.contains("@nil");
        }
    }
}
