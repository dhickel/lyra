package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.types.BindingMutability;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Complete typed facade member plan for one public export. */
record GeneratedExportPlan(
        JvmExportId exportId,
        JvmExportId originExportId,
        ModuleId moduleId,
        DeclarationId declarationId,
        String sourceName,
        boolean reExport,
        ModuleId originModule,
        String originName,
        DeclarationId originDeclaration,
        BindingMutability bindingMutability,
        JvmTypePlan valueType,
        Optional<JvmSignaturePlan> functionSignature,
        String javaName,
        Optional<String> invocationName,
        Optional<String> getterName,
        Optional<String> functionValueName,
        Optional<String> setterName,
        List<GeneratedMemberPlan> members,
        Optional<ExportId> semanticExportId) {
    public GeneratedExportPlan {
        Objects.requireNonNull(exportId, "exportId");
        Objects.requireNonNull(originExportId, "originExportId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(declarationId, "declarationId");
        if (Objects.requireNonNull(sourceName, "sourceName").isBlank()) {
            throw new IllegalArgumentException("export source name must not be blank");
        }
        Objects.requireNonNull(originModule, "originModule");
        if (Objects.requireNonNull(originName, "originName").isBlank()) {
            throw new IllegalArgumentException("export origin name must not be blank");
        }
        Objects.requireNonNull(originDeclaration, "originDeclaration");
        Objects.requireNonNull(bindingMutability, "bindingMutability");
        Objects.requireNonNull(valueType, "valueType");
        if (!exportId.canonicalContract().equals(valueType.canonicalLyraType())) {
            throw new IllegalArgumentException(
                    "ABI export identity contract disagrees with its value type");
        }
        functionSignature = Objects.requireNonNull(functionSignature, "functionSignature");
        javaName = JvmNames.requireMemberName(javaName, "export Java name");
        if (javaName.startsWith("$lyra$")) {
            throw new IllegalArgumentException("export Java name uses reserved Lyra infrastructure prefix");
        }
        invocationName = Objects.requireNonNull(invocationName, "invocationName")
                .map(value -> JvmNames.requireMemberName(value, "invocation name"));
        getterName = Objects.requireNonNull(getterName, "getterName")
                .map(value -> JvmNames.requireMemberName(value, "getter name"));
        functionValueName = Objects.requireNonNull(functionValueName, "functionValueName")
                .map(value -> JvmNames.requireMemberName(value, "function-value getter name"));
        setterName = Objects.requireNonNull(setterName, "setterName")
                .map(value -> JvmNames.requireMemberName(value, "setter name"));
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        semanticExportId = Objects.requireNonNull(semanticExportId, "semanticExportId");
        if (!semanticExportId.equals(exportId.semanticExportId())) {
            throw new IllegalArgumentException("semantic export identity linkage disagrees with ABI identity");
        }
        if (!exportId.moduleId().equals(moduleId) || !exportId.exportName().equals(sourceName)
                || !originExportId.moduleId().equals(originModule)
                || !originExportId.exportName().equals(originName)
                || !originExportId.canonicalContract().equals(exportId.canonicalContract())) {
            throw new IllegalArgumentException("ABI export identity disagrees with export plan");
        }
        if (exportId.semanticExportId().isPresent() != originExportId.semanticExportId().isPresent()) {
            throw new IllegalArgumentException("callable export and origin identities must agree");
        }
        if (!reExport && (!originExportId.equals(exportId)
                || !originModule.equals(moduleId) || !originName.equals(sourceName)
                || !originDeclaration.equals(declarationId))) {
            throw new IllegalArgumentException("local export must originate at itself");
        }
        if (reExport && originModule.equals(moduleId)) {
            throw new IllegalArgumentException("re-export origin must come from another module");
        }
        if (!valueType.context().equals(JvmMappingContext.EXPORTED_VALUE)) {
            throw new IllegalArgumentException("export values need the exported-value ABI context");
        }
        boolean function = functionSignature.isPresent();
        if (function != valueType.baseCanonicalLyraType().startsWith("Fn<")) {
            throw new IllegalArgumentException("function signature presence disagrees with export type");
        }
        if (function && (exportId.semanticExportId().isEmpty()
                || originExportId.semanticExportId().isEmpty())) {
            throw new IllegalArgumentException("function export origins need semantic identities");
        }
        if (function && !functionSignature.orElseThrow().canonicalLyraSignature()
                .equals(valueType.baseCanonicalLyraType())) {
            throw new IllegalArgumentException("export function signature disagrees with value type");
        }
        functionSignature.ifPresent(signature -> {
            if (signature.boundary() != JvmAbiBoundary.JAVA_VISIBLE
                    || signature.descriptor().equals("")) {
                throw new IllegalArgumentException("export function signature has invalid ABI boundary");
            }
        });
        if (!function && functionValueName.isPresent()) {
            throw new IllegalArgumentException("scalar export cannot have a function-value getter");
        }
        if (function && getterName.isPresent()) {
            throw new IllegalArgumentException("function export cannot have a scalar getter");
        }
        if (bindingMutability == BindingMutability.MUTABLE != setterName.isPresent()) {
            throw new IllegalArgumentException("setter presence disagrees with binding mutability");
        }
        if (function) {
            String expectedInvocation = io.mindspice.lyra.compiler.identity.JavaNameMangler
                    .isFacadeInvocationReserved(sourceName)
                    ? "invoke$" + javaName : javaName;
            if (!invocationName.orElseThrow().equals(expectedInvocation)
                    || !functionValueName.orElseThrow().equals("value$" + javaName)) {
                throw new IllegalArgumentException("function export member names are not deterministic");
            }
        } else if (!getterName.orElseThrow().equals("get$" + javaName)) {
            throw new IllegalArgumentException("value export getter name is not deterministic");
        }
        if (setterName.isPresent() && !setterName.orElseThrow().equals("set$" + javaName)) {
            throw new IllegalArgumentException("export setter name is not deterministic");
        }
        HashSet<String> keys = new HashSet<>();
        HashSet<GeneratedMemberKind> kinds = new HashSet<>();
        for (GeneratedMemberPlan member : members) {
            if (!keys.add(member.declarationKey())) {
                throw new IllegalArgumentException("duplicate export member: " + member.declarationKey());
            }
            if (!kinds.add(member.kind())) {
                throw new IllegalArgumentException("duplicate export member kind: " + member.kind());
            }
            if (member.exportId().filter(id -> id.equals(exportId.id())).isEmpty()
                    || member.sourceName().filter(name -> name.equals(sourceName)).isEmpty()) {
                throw new IllegalArgumentException("export member linkage disagrees with its export");
            }
        }
        validateMembers(exportId, sourceName, kinds, functionSignature, valueType,
                invocationName, getterName, functionValueName, setterName, members,
                bindingMutability);
    }

    public boolean isFunction() {
        return functionSignature.isPresent();
    }

    public boolean isMutable() {
        return bindingMutability.isMutable();
    }

    public String stableId() {
        return exportId.id();
    }

    public String exportName() {
        return sourceName;
    }

    public Optional<GeneratedMemberPlan> invocation() {
        return member(members, GeneratedMemberKind.FUNCTION_INVOCATION);
    }

    public Optional<GeneratedMemberPlan> getter() {
        return member(members, GeneratedMemberKind.VALUE_GETTER);
    }

    public Optional<GeneratedMemberPlan> functionValueGetter() {
        return member(members, GeneratedMemberKind.FUNCTION_VALUE_GETTER);
    }

    public Optional<GeneratedMemberPlan> setter() {
        return member(members, GeneratedMemberKind.SETTER);
    }

    public String canonicalSpelling() {
        return exportId.canonicalInput() + "|origin=" + originExportId.canonicalInput()
                + "|reExport=" + reExport + "|java=" + javaName
                + "|invocation=" + invocationName.orElse("")
                + "|getter=" + getterName.orElse("")
                + "|functionGetter=" + functionValueName.orElse("")
                + "|setter=" + setterName.orElse("");
    }

    private static void validateMembers(
            JvmExportId exportId,
            String sourceName,
            HashSet<GeneratedMemberKind> kinds,
            Optional<JvmSignaturePlan> functionSignature,
            JvmTypePlan valueType,
            Optional<String> invocationName,
            Optional<String> getterName,
            Optional<String> functionValueName,
            Optional<String> setterName,
            List<GeneratedMemberPlan> members,
            BindingMutability bindingMutability) {
        boolean function = functionSignature.isPresent();
        Set<GeneratedMemberKind> expected = new HashSet<>();
        if (function) {
            expected.add(GeneratedMemberKind.FUNCTION_INVOCATION);
            expected.add(GeneratedMemberKind.FUNCTION_VALUE_GETTER);
        } else {
            expected.add(GeneratedMemberKind.VALUE_GETTER);
        }
        if (bindingMutability == BindingMutability.MUTABLE) {
            expected.add(GeneratedMemberKind.SETTER);
        }
        if (!kinds.equals(expected)) {
            throw new IllegalArgumentException("export member kinds are incomplete or unexpected");
        }
        GeneratedMemberPlan invocation = member(members, GeneratedMemberKind.FUNCTION_INVOCATION).orElse(null);
        if (function && (invocation == null
                || invocation.isStatic()
                || !invocation.isPublic()
                || !invocation.name().equals(invocationName.orElseThrow())
                || !invocation.descriptor().equals(functionSignature.orElseThrow().descriptor())
                || invocation.signature().filter(functionSignature.orElseThrow()::equals).isEmpty())) {
            throw new IllegalArgumentException("function invocation member does not match its signature");
        }
        GeneratedMemberPlan getter = member(members, GeneratedMemberKind.VALUE_GETTER).orElse(null);
        if (!function && (getter == null || getter.isStatic() || !getter.isPublic()
                || !getter.name().equals(getterName.orElseThrow())
                || !getter.descriptor().equals("()" + valueType.descriptor())
                || getter.valueType().filter(valueType::equals).isEmpty())) {
            throw new IllegalArgumentException("value getter member does not match its type");
        }
        GeneratedMemberPlan functionGetter = member(members, GeneratedMemberKind.FUNCTION_VALUE_GETTER).orElse(null);
        if (function && (functionGetter == null || functionGetter.isStatic()
                || !functionGetter.isPublic()
                || !functionGetter.name().equals(functionValueName.orElseThrow())
                || !functionGetter.descriptor().equals("()" + valueType.descriptor())
                || functionGetter.valueType().isEmpty()
                || !functionGetter.valueType().orElseThrow().canonicalLyraType()
                .equals(valueType.canonicalLyraType())
                || !functionGetter.valueType().orElseThrow().context()
                .equals(JvmMappingContext.JAVA_FUNCTION_VALUE))) {
            throw new IllegalArgumentException("function-value getter does not match its type");
        }
        GeneratedMemberPlan setter = member(members, GeneratedMemberKind.SETTER).orElse(null);
        boolean setterTypeMatches = setter != null && (function
                ? functionGetter != null
                && setter.valueType().filter(functionGetter.valueType().orElseThrow()::equals).isPresent()
                : setter.valueType().filter(valueType::equals).isPresent());
        if (bindingMutability == BindingMutability.MUTABLE
                && (setter == null || setter.isStatic() || !setter.isPublic()
                || !setter.name().equals(setterName.orElseThrow())
                || !setter.descriptor().equals("(" + valueType.descriptor() + ")V")
                || !setterTypeMatches)) {
            throw new IllegalArgumentException("setter member does not match its type");
        }
        for (GeneratedMemberPlan member : members) {
            if (member.exportId().filter(id -> id.equals(exportId.id())).isEmpty()
                    || member.sourceName().filter(sourceName::equals).isEmpty()
                    || member.canonicalLyraContract().isEmpty()) {
                throw new IllegalArgumentException("export member metadata is incomplete");
            }
        }
    }

    private static Optional<GeneratedMemberPlan> member(
            List<GeneratedMemberPlan> members, GeneratedMemberKind kind) {
        return members.stream().filter(member -> member.kind() == kind).findFirst();
    }

}
