package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One immutable generated class shape, with no emitted method bodies. */
record GeneratedClassPlan(
        String binaryName,
        GeneratedClassKind kind,
        String stableKey,
        Optional<ModuleId> moduleId,
        boolean finalClass,
        boolean functionalInterface,
        List<String> interfaces,
        List<String> annotations,
        List<GeneratedClassDependency> dependencies,
        List<GeneratedMemberPlan> members) {
    public GeneratedClassPlan {
        JvmNames.requireBinaryName(binaryName, "generated class name");
        String simpleName = binaryName.substring(binaryName.lastIndexOf('.') + 1);
        if (!simpleName.startsWith("$lyra$") || simpleName.length() == "$lyra$".length()) {
            throw new IllegalArgumentException(
                    "generated class name must use the $lyra$ infrastructure namespace: "
                            + binaryName);
        }
        JvmDescriptors.requireTypeDescriptor("L" + binaryName.replace('.', '/') + ";");
        Objects.requireNonNull(kind, "kind");
        String requiredPrefix = switch (kind) {
            case TUPLE_VALUE -> "$lyra$tuple$";
            case FUNCTION_INTERFACE -> "$lyra$fn$";
            case CELL -> "$lyra$cell$";
            case CLOSURE -> "$lyra$closure$";
            case MODULE_STATE -> "$lyra$state$";
            case MODULE_FACADE -> "$lyra$facade$";
        };
        if (!simpleName.startsWith(requiredPrefix)
                || simpleName.length() == requiredPrefix.length()) {
            throw new IllegalArgumentException(
                    "generated class name disagrees with its role: " + binaryName);
        }
        moduleId = Objects.requireNonNull(moduleId, "moduleId");
        boolean needsModule = kind == GeneratedClassKind.CELL
                || kind == GeneratedClassKind.CLOSURE
                || kind == GeneratedClassKind.MODULE_STATE
                || kind == GeneratedClassKind.MODULE_FACADE;
        if (moduleId.isPresent() != needsModule) {
            throw new IllegalArgumentException("generated class module metadata disagrees with its kind");
        }
        if (finalClass != (kind != GeneratedClassKind.FUNCTION_INTERFACE)) {
            throw new IllegalArgumentException("generated class finality disagrees with its kind");
        }
        if (Objects.requireNonNull(stableKey, "stableKey").isBlank()) {
            throw new IllegalArgumentException("generated class stable key must not be blank");
        }
        if (functionalInterface != (kind == GeneratedClassKind.FUNCTION_INTERFACE)) {
            throw new IllegalArgumentException("functional-interface flag disagrees with class kind");
        }
        if (finalClass == (kind == GeneratedClassKind.FUNCTION_INTERFACE)) {
            throw new IllegalArgumentException("generated interface/final-class flags disagree");
        }
        interfaces = sortedBinaryNames(interfaces, "interfaces");
        annotations = sortedBinaryNames(annotations, "annotations");
        dependencies = sortedDependencies(dependencies);
        members = copyMembers(members);
        validateClassShape(kind, interfaces, annotations, members);
        if (kind == GeneratedClassKind.MODULE_FACADE) {
            validateFacadeMemberNames(binaryName, members);
        }
    }

    public String name() {
        return binaryName;
    }

    public String internalName() {
        return binaryName.replace('.', '/');
    }

    public String descriptor() {
        return "L" + internalName() + ";";
    }

    public List<GeneratedClassDependency> dependencyEdges() {
        return dependencies;
    }

    public List<GeneratedMemberPlan> memberPlans() {
        return members;
    }

    public boolean isFinal() {
        return finalClass;
    }

    public boolean isInterface() {
        return kind == GeneratedClassKind.FUNCTION_INTERFACE;
    }

    /** Class-file access planned independently from member visibility. */
    public GeneratedMemberVisibility visibility() {
        return switch (kind) {
            case TUPLE_VALUE, FUNCTION_INTERFACE, MODULE_FACADE ->
                    GeneratedMemberVisibility.PUBLIC;
            case CELL, CLOSURE, MODULE_STATE -> GeneratedMemberVisibility.PACKAGE;
        };
    }

    public boolean isPublic() {
        return visibility().isPublic();
    }

    private static void validateClassShape(
            GeneratedClassKind kind,
            List<String> interfaces,
            List<String> annotations,
            List<GeneratedMemberPlan> members) {
        Set<GeneratedMemberKind> memberKinds = members.stream()
                .map(GeneratedMemberPlan::kind).collect(java.util.stream.Collectors.toSet());
        switch (kind) {
            case TUPLE_VALUE -> {
                if (!interfaces.isEmpty() || !annotations.isEmpty()
                        || members.stream().noneMatch(value -> value.kind() == GeneratedMemberKind.TUPLE_FIELD)
                        || members.stream().filter(value -> value.kind() == GeneratedMemberKind.TUPLE_CONSTRUCTOR)
                        .count() != 1
                        || !memberKinds.stream().allMatch(value -> value == GeneratedMemberKind.TUPLE_FIELD
                        || value == GeneratedMemberKind.TUPLE_COMPONENT_GET
                        || value == GeneratedMemberKind.TUPLE_CONSTRUCTOR)) {
                    throw new IllegalArgumentException("invalid tuple generated class shape");
                }
                validateTupleShape(members);
            }
            case FUNCTION_INTERFACE -> {
                if (!interfaces.isEmpty()
                        || !annotations.equals(List.of("java.lang.FunctionalInterface"))
                        || members.size() != 1
                        || members.getFirst().kind() != GeneratedMemberKind.FUNCTION_INVOKE
                        || !members.getFirst().name().equals("invoke")) {
                    throw new IllegalArgumentException("invalid function-interface generated class shape");
                }
            }
            case CELL -> {
                if (!interfaces.isEmpty() || !annotations.isEmpty()) {
                    throw new IllegalArgumentException("cell class cannot have interfaces or annotations");
                }
                requireKinds(memberKinds, Set.of(
                        GeneratedMemberKind.CELL_VALUE_FIELD,
                        GeneratedMemberKind.CELL_PRESENCE_FIELD,
                        GeneratedMemberKind.CELL_CONSTRUCTOR,
                        GeneratedMemberKind.CELL_GET,
                        GeneratedMemberKind.CELL_SET,
                        GeneratedMemberKind.CELL_PRESENCE_GET,
                        GeneratedMemberKind.CELL_PAYLOAD_GET), "cell");
                requireExactlyOne(members, GeneratedMemberKind.CELL_CONSTRUCTOR, "cell constructor");
                if (members.stream().noneMatch(value -> value.kind() == GeneratedMemberKind.CELL_VALUE_FIELD)) {
                    throw new IllegalArgumentException("cell needs a value field");
                }
                validateCellShape(members);
            }
            case CLOSURE -> {
                if (!annotations.isEmpty() || interfaces.size() != 1) {
                    throw new IllegalArgumentException("closure must implement exactly one function interface");
                }
                requireKinds(memberKinds, Set.of(
                        GeneratedMemberKind.CLOSURE_AUTHORITY_FIELD,
                        GeneratedMemberKind.CLOSURE_STATE_FIELD,
                        GeneratedMemberKind.CLOSURE_CAPTURE_FIELD,
                        GeneratedMemberKind.CLOSURE_CAPTURE_PRESENCE_FIELD,
                        GeneratedMemberKind.CLOSURE_CAPTURE_PAYLOAD_FIELD,
                        GeneratedMemberKind.CLOSURE_CONSTRUCTOR,
                        GeneratedMemberKind.CLOSURE_INVOKE), "closure");
                requireExactlyOne(members, GeneratedMemberKind.CLOSURE_AUTHORITY_FIELD,
                        "closure authority field");
                requireExactlyOne(members, GeneratedMemberKind.CLOSURE_STATE_FIELD,
                        "closure module-state field");
                requireExactlyOne(members, GeneratedMemberKind.CLOSURE_CONSTRUCTOR,
                        "closure constructor");
                requireExactlyOne(members, GeneratedMemberKind.CLOSURE_INVOKE,
                        "closure invocation");
                validateClosureShape(interfaces, members);
            }
            case MODULE_STATE -> {
                if (!interfaces.isEmpty() || !annotations.isEmpty()) {
                    throw new IllegalArgumentException("module-state class cannot have interfaces or annotations");
                }
                requireKinds(memberKinds, Set.of(
                        GeneratedMemberKind.STATE_LIFECYCLE_FIELD,
                        GeneratedMemberKind.STATE_BINDING_FIELD,
                        GeneratedMemberKind.STATE_PRESENCE_FIELD,
                        GeneratedMemberKind.STATE_PAYLOAD_FIELD,
                        GeneratedMemberKind.STATE_CELL_FIELD,
                        GeneratedMemberKind.STATE_IMPORT_FIELD,
                        GeneratedMemberKind.STATE_IMPORT_LINK,
                        GeneratedMemberKind.STATE_COMPONENT_GET,
                        GeneratedMemberKind.STATE_COMPONENT_SET,
                        GeneratedMemberKind.STATE_CONSTRUCTOR,
                        GeneratedMemberKind.STATE_AUTHORITY_GET,
                        GeneratedMemberKind.STATE_CHECK_OPEN,
                        GeneratedMemberKind.STATE_CLOSE), "module-state");
                GeneratedMemberPlan constructor = requireFacadeMember(
                        members, GeneratedMemberKind.STATE_CONSTRUCTOR,
                        member -> member.name().equals("<init>")
                                && member.descriptor().equals("(Lio/mindspice/lyra/runtime/LyraArtifactKey;)V"));
                if (constructor.isStatic()) {
                    throw new IllegalArgumentException("module-state constructor cannot be static");
                }
                validateStateShape(members);
            }
            case MODULE_FACADE -> {
                if (!annotations.isEmpty()) {
                    throw new IllegalArgumentException("facade class cannot have annotations");
                }
                requireKinds(memberKinds, Set.of(
                    GeneratedMemberKind.FACADE_STATE_FIELD,
                    GeneratedMemberKind.FACADE_CONSTRUCTOR,
                    GeneratedMemberKind.FUNCTION_INVOCATION,
                    GeneratedMemberKind.VALUE_GETTER,
                    GeneratedMemberKind.FUNCTION_VALUE_GETTER,
                    GeneratedMemberKind.SETTER,
                    GeneratedMemberKind.FACTORY,
                    GeneratedMemberKind.FACTORY_WITH_OPTIONS,
                    GeneratedMemberKind.METADATA,
                    GeneratedMemberKind.CLOSE), "facade");
            }
        }
        if (kind == GeneratedClassKind.FUNCTION_INTERFACE
                && members.getFirst().visibility() != GeneratedMemberVisibility.PUBLIC) {
            throw new IllegalArgumentException("function-interface invocation must be public");
        }
        if (kind == GeneratedClassKind.MODULE_FACADE
                && !interfaces.equals(List.of("java.lang.AutoCloseable"))) {
            throw new IllegalArgumentException("facade must implement AutoCloseable");
        }
        if (kind != GeneratedClassKind.FUNCTION_INTERFACE) {
            for (GeneratedMemberPlan member : members) {
                if (member.isField() && !member.isPrivate()) {
                    throw new IllegalArgumentException("generated representation fields must be private");
                }
            }
        }
    }

    private static void validateTupleShape(List<GeneratedMemberPlan> members) {
        List<GeneratedMemberPlan> fields = members.stream()
                .filter(value -> value.kind() == GeneratedMemberKind.TUPLE_FIELD)
                .sorted(java.util.Comparator.comparingInt(GeneratedMemberPlan::componentIndex))
                .toList();
        for (int index = 0; index < fields.size(); index++) {
            GeneratedMemberPlan field = fields.get(index);
            requireValueMetadata(field, "tuple field");
            if (field.componentIndex() != index
                    || !field.name().equals("$lyra$" + index)) {
                throw new IllegalArgumentException("tuple fields must have contiguous canonical indexes");
            }
        }
        String expectedConstructor = "(" + fields.stream()
                .map(GeneratedMemberPlan::descriptor).collect(java.util.stream.Collectors.joining()) + ")V";
        GeneratedMemberPlan constructor = members.stream()
                .filter(value -> value.kind() == GeneratedMemberKind.TUPLE_CONSTRUCTOR)
                .findFirst().orElseThrow();
        if (!constructor.descriptor().equals(expectedConstructor)
                || constructor.isStatic() || !constructor.isPublic()) {
            throw new IllegalArgumentException("tuple constructor does not match its fields");
        }
        List<GeneratedMemberPlan> getters = members.stream()
                .filter(value -> value.kind() == GeneratedMemberKind.TUPLE_COMPONENT_GET)
                .toList();
        if (getters.size() != fields.size()) {
            throw new IllegalArgumentException("tuple needs one accessor per component");
        }
        for (int index = 0; index < fields.size(); index++) {
            GeneratedMemberPlan field = fields.get(index);
            boolean present = getters.stream().anyMatch(getter ->
                    getter.name().equals("$lyra$get$" + field.componentIndex())
                            && getter.descriptor().equals("()" + field.descriptor())
                            && !getter.isStatic());
            if (!present) {
                throw new IllegalArgumentException("tuple accessor does not match its component");
            }
        }
    }

    private static void validateCellShape(List<GeneratedMemberPlan> members) {
        boolean split = members.stream().anyMatch(value ->
                value.kind() == GeneratedMemberKind.CELL_PRESENCE_FIELD);
        Set<GeneratedMemberKind> expected = split
                ? Set.of(GeneratedMemberKind.CELL_VALUE_FIELD,
                GeneratedMemberKind.CELL_PRESENCE_FIELD,
                GeneratedMemberKind.CELL_CONSTRUCTOR,
                GeneratedMemberKind.CELL_PRESENCE_GET,
                GeneratedMemberKind.CELL_PAYLOAD_GET,
                GeneratedMemberKind.CELL_SET)
                : Set.of(GeneratedMemberKind.CELL_VALUE_FIELD,
                GeneratedMemberKind.CELL_CONSTRUCTOR,
                GeneratedMemberKind.CELL_GET,
                GeneratedMemberKind.CELL_SET);
        Set<GeneratedMemberKind> actual = members.stream()
                .map(GeneratedMemberPlan::kind).collect(java.util.stream.Collectors.toSet());
        if (!actual.equals(expected) || members.size() != expected.size()) {
            throw new IllegalArgumentException("cell storage/accessor shape does not match its representation");
        }
        GeneratedMemberPlan value = members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.CELL_VALUE_FIELD)
                .findFirst().orElseThrow();
        requireValueMetadata(value, "cell value field");
        GeneratedMemberPlan constructor = members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.CELL_CONSTRUCTOR)
                .findFirst().orElseThrow();
        GeneratedMemberPlan setter = members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.CELL_SET)
                .findFirst().orElseThrow();
        if (split) {
            GeneratedMemberPlan presence = members.stream()
                    .filter(member -> member.kind() == GeneratedMemberKind.CELL_PRESENCE_FIELD)
                    .findFirst().orElseThrow();
            requireValueMetadata(presence, "cell presence field");
            if (!presence.valueType().orElseThrow().equals(value.valueType().orElseThrow())) {
                throw new IllegalArgumentException("cell presence and payload fields disagree on their value plan");
            }
            String parameters = presence.descriptor() + value.descriptor();
            if (!presence.name().equals("$lyra$value$present")
                    || !value.name().equals("$lyra$value$payload")
                    || !presence.descriptor().equals("Z")
                    || !constructor.descriptor().equals("(" + parameters + ")V")
                    || !setter.name().equals("$lyra$set")
                    || !setter.descriptor().equals("(" + parameters + ")V")
                    || members.stream().noneMatch(member ->
                    member.kind() == GeneratedMemberKind.CELL_PRESENCE_GET
                            && member.name().equals("$lyra$isPresent")
                            && member.descriptor().equals("()Z"))
                    || members.stream().noneMatch(member ->
                    member.kind() == GeneratedMemberKind.CELL_PAYLOAD_GET
                            && member.name().equals("$lyra$payload")
                            && member.descriptor().equals("()" + value.descriptor()))) {
                throw new IllegalArgumentException("split cell methods do not match its fields");
            }
        } else if (!value.valueType().orElseThrow().isSingleValue()
                || !value.name().equals("$lyra$value")
                || !constructor.descriptor().equals("(" + value.descriptor() + ")V")
                || !setter.name().equals("$lyra$set")
                || !setter.descriptor().equals("(" + value.descriptor() + ")V")
                || members.stream().noneMatch(member -> member.kind() == GeneratedMemberKind.CELL_GET
                && member.name().equals("$lyra$get")
                && member.descriptor().equals("()" + value.descriptor()))) {
            throw new IllegalArgumentException("cell methods do not match its value field");
        }
    }

    private static void validateStateShape(List<GeneratedMemberPlan> members) {
        String fieldPrefix = "$lyra$binding$";
        java.util.TreeMap<String, List<GeneratedMemberPlan>> bindingGroups = new java.util.TreeMap<>();
        for (GeneratedMemberPlan field : members.stream()
                .filter(GeneratedMemberPlan::isField)
                .filter(member -> member.kind() != GeneratedMemberKind.STATE_LIFECYCLE_FIELD)
                .toList()) {
            if (!field.name().startsWith(fieldPrefix)) {
                throw new IllegalArgumentException("module-state field has a noncanonical name");
            }
            String suffix = field.name().substring(fieldPrefix.length());
            String bindingId = suffix;
            if (suffix.endsWith("$present")) {
                bindingId = suffix.substring(0, suffix.length() - "$present".length());
            } else if (suffix.endsWith("$payload")) {
                bindingId = suffix.substring(0, suffix.length() - "$payload".length());
            }
            if (!isCanonicalOrdinal(bindingId)) {
                throw new IllegalArgumentException("module-state field has a noncanonical declaration identity");
            }
            if (field.kind() == GeneratedMemberKind.STATE_BINDING_FIELD
                    || field.kind() == GeneratedMemberKind.STATE_PRESENCE_FIELD
                    || field.kind() == GeneratedMemberKind.STATE_PAYLOAD_FIELD) {
                requireValueMetadata(field, "module-state binding field");
            }
            bindingGroups.computeIfAbsent(bindingId, ignored -> new ArrayList<>()).add(field);
        }

        Set<String> expectedGetters = new java.util.TreeSet<>();
        Set<String> expectedImportLinks = new java.util.TreeSet<>();
        for (Map.Entry<String, List<GeneratedMemberPlan>> entry : bindingGroups.entrySet()) {
            String bindingId = entry.getKey();
            List<GeneratedMemberPlan> group = entry.getValue();
            if (group.size() == 1) {
                GeneratedMemberPlan field = group.getFirst();
                if (field.kind() == GeneratedMemberKind.STATE_BINDING_FIELD) {
                    if (!field.valueType().orElseThrow().isSingleValue()
                            || !field.name().equals(fieldPrefix + bindingId)) {
                        throw new IllegalArgumentException(
                                "module-state binding field has a noncanonical storage name or ABI");
                    }
                    expectedGetters.add("$lyra$get$binding$" + bindingId
                            + "()" + field.descriptor());
                } else if (field.kind() == GeneratedMemberKind.STATE_CELL_FIELD
                        || field.kind() == GeneratedMemberKind.STATE_IMPORT_FIELD) {
                    if (!field.name().equals(fieldPrefix + bindingId)) {
                        throw new IllegalArgumentException(
                                "module-state linkage field has a noncanonical storage name");
                    }
                    expectedGetters.add("$lyra$get$binding$" + bindingId
                            + "()" + field.descriptor());
                    if (field.kind() == GeneratedMemberKind.STATE_IMPORT_FIELD) {
                        expectedImportLinks.add("$lyra$link$binding$" + bindingId
                                + "(" + field.descriptor() + ")V");
                    }
                } else {
                    throw new IllegalArgumentException("module-state split storage needs both components");
                }
            } else if (group.size() == 2) {
                GeneratedMemberPlan presence = group.stream()
                        .filter(field -> field.kind() == GeneratedMemberKind.STATE_PRESENCE_FIELD)
                        .findFirst().orElse(null);
                GeneratedMemberPlan payload = group.stream()
                        .filter(field -> field.kind() == GeneratedMemberKind.STATE_PAYLOAD_FIELD)
                        .findFirst().orElse(null);
                if (presence == null || payload == null
                        || !presence.valueType().orElseThrow().equals(payload.valueType().orElseThrow())
                        || !presence.descriptor().equals("Z")
                        || presence.componentIndex() != 0 || payload.componentIndex() != 1
                        || !presence.name().equals(fieldPrefix + bindingId + "$present")
                        || !payload.name().equals(fieldPrefix + bindingId + "$payload")) {
                    throw new IllegalArgumentException("module-state split storage is incomplete or noncanonical");
                }
                expectedGetters.add("$lyra$isPresent$binding$" + bindingId + "()Z");
                expectedGetters.add("$lyra$payload$binding$" + bindingId
                        + "()" + payload.descriptor());
            } else {
                throw new IllegalArgumentException("module-state binding has duplicate storage components");
            }
        }
        Set<String> actualGetters = members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.STATE_COMPONENT_GET)
                .map(GeneratedMemberPlan::declarationKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if (!actualGetters.equals(expectedGetters)) {
            throw new IllegalArgumentException("module-state accessors do not match its fields");
        }
        Set<String> actualImportLinks = members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.STATE_IMPORT_LINK)
                .map(GeneratedMemberPlan::declarationKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if (!actualImportLinks.equals(expectedImportLinks)) {
            throw new IllegalArgumentException("module-state import links do not match its fields");
        }

        for (GeneratedMemberPlan setter : members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.STATE_COMPONENT_SET).toList()) {
            String setterPrefix = "$lyra$set$binding$";
            if (!setter.name().startsWith(setterPrefix)) {
                throw new IllegalArgumentException("module-state setter has a noncanonical name");
            }
            String bindingId = setter.name().substring(setterPrefix.length());
            if (!isCanonicalOrdinal(bindingId)) {
                throw new IllegalArgumentException("module-state setter has a noncanonical declaration identity");
            }
            List<GeneratedMemberPlan> group = bindingGroups.get(bindingId);
            if (group == null) {
                throw new IllegalArgumentException("module-state setter has no matching field");
            }
            String expectedParameters;
            if (group.size() == 1 && group.getFirst().kind() == GeneratedMemberKind.STATE_BINDING_FIELD) {
                expectedParameters = group.getFirst().descriptor();
            } else if (group.size() == 2
                    && group.stream().allMatch(field -> field.kind() == GeneratedMemberKind.STATE_PRESENCE_FIELD
                    || field.kind() == GeneratedMemberKind.STATE_PAYLOAD_FIELD)) {
                GeneratedMemberPlan presence = group.stream()
                        .filter(field -> field.kind() == GeneratedMemberKind.STATE_PRESENCE_FIELD)
                        .findFirst().orElseThrow();
                GeneratedMemberPlan payload = group.stream()
                        .filter(field -> field.kind() == GeneratedMemberKind.STATE_PAYLOAD_FIELD)
                        .findFirst().orElseThrow();
                expectedParameters = presence.descriptor() + payload.descriptor();
            } else {
                throw new IllegalArgumentException("module-state setter targets non-mutable or incomplete storage");
            }
            if (!setter.descriptor().equals("(" + expectedParameters + ")V")) {
                throw new IllegalArgumentException("module-state setter does not match its field");
            }
        }
    }

    private static void validateClosureShape(
            List<String> interfaces, List<GeneratedMemberPlan> members) {
        String implemented = interfaces.getFirst();
        if (!implemented.substring(implemented.lastIndexOf('.') + 1).startsWith("$lyra$fn$")) {
            throw new IllegalArgumentException("closure must implement a generated function interface");
        }
        GeneratedMemberPlan authority = members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_AUTHORITY_FIELD)
                .findFirst().orElseThrow();
        if (!authority.name().equals("$lyra$authority")
                || !authority.descriptor().equals(
                "Lio/mindspice/lyra/runtime/LyraClosureAuthority;")) {
            throw new IllegalArgumentException("closure authority field has the wrong ABI");
        }
        GeneratedMemberPlan state = members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_STATE_FIELD)
                .findFirst().orElseThrow();
        String stateInternalName = state.descriptor().startsWith("L")
                && state.descriptor().endsWith(";")
                ? state.descriptor().substring(1, state.descriptor().length() - 1) : "";
        String stateSimpleName = stateInternalName.substring(stateInternalName.lastIndexOf('/') + 1);
        if (!state.name().equals("$lyra$state")
                || !stateSimpleName.startsWith("$lyra$state$")) {
            throw new IllegalArgumentException("closure module-state field has the wrong ABI");
        }

        String capturePrefix = "$lyra$capture$";
        java.util.TreeMap<String, List<GeneratedMemberPlan>> captureGroups = new java.util.TreeMap<>();
        ArrayList<String> captureOrder = new ArrayList<>();
        List<GeneratedMemberPlan> captures = members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_FIELD
                        || member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PRESENCE_FIELD
                        || member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PAYLOAD_FIELD)
                .toList();
        for (GeneratedMemberPlan capture : captures) {
            String base = captureBase(capture.name(), capturePrefix);
            if (!captureGroups.containsKey(base)) {
                captureOrder.add(base);
            }
            captureGroups.computeIfAbsent(base, ignored -> new ArrayList<>()).add(capture);
            if (capture.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_FIELD) {
                String descriptor = capture.descriptor();
                boolean functionSlot = descriptor.startsWith("[L")
                        && descriptor.endsWith(";");
                String internalName = functionSlot
                        ? descriptor.substring(2, descriptor.length() - 1)
                        : descriptor.startsWith("L") && descriptor.endsWith(";")
                        ? descriptor.substring(1, descriptor.length() - 1) : "";
                String simpleName = internalName.substring(internalName.lastIndexOf('/') + 1);
                boolean validCell = simpleName.startsWith("$lyra$cell$")
                        && simpleName.length() > "$lyra$cell$".length();
                boolean validFunctionSlot = functionSlot
                        && simpleName.startsWith("$lyra$fn$")
                        && simpleName.length() > "$lyra$fn$".length();
                if (!validCell && !validFunctionSlot) {
                    throw new IllegalArgumentException(
                            "linked closure capture must reference a generated cell or function slot");
                }
            }
            if (capture.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PRESENCE_FIELD) {
                requireValueMetadata(capture, "closure capture presence field");
                if (!capture.name().endsWith("$present") || !capture.descriptor().equals("Z")
                        || capture.componentIndex() != 0) {
                    throw new IllegalArgumentException("closure capture presence field has the wrong ABI");
                }
            }
            if (capture.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PAYLOAD_FIELD) {
                requireValueMetadata(capture, "closure capture payload field");
            }
        }
        if (!captureOrder.equals(captureOrder.stream()
                .sorted(Comparator.comparingLong(GeneratedClassPlan::captureOrdinal)).toList())) {
            throw new IllegalArgumentException("closure captures must be in canonical identity order");
        }
        for (List<GeneratedMemberPlan> group : captureGroups.values()) {
            if (group.size() == 1) {
                GeneratedMemberPlan only = group.getFirst();
                if (only.kind() != GeneratedMemberKind.CLOSURE_CAPTURE_FIELD
                        && only.kind() != GeneratedMemberKind.CLOSURE_CAPTURE_PAYLOAD_FIELD) {
                    throw new IllegalArgumentException("closure capture storage is incomplete");
                }
                if (only.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PAYLOAD_FIELD
                        && (!only.valueType().orElseThrow().isSingleValue()
                        || only.componentIndex() != 0 || !only.name().equals(groupName(group)))) {
                    throw new IllegalArgumentException("single closure capture payload has the wrong component");
                }
                if (only.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_FIELD
                        && !only.name().equals(groupName(group))) {
                    throw new IllegalArgumentException("linked closure capture has a noncanonical name");
                }
            } else if (group.size() == 2) {
                GeneratedMemberPlan presence = group.stream()
                        .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PRESENCE_FIELD)
                        .findFirst().orElse(null);
                GeneratedMemberPlan payload = group.stream()
                        .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_PAYLOAD_FIELD)
                        .findFirst().orElse(null);
                if (presence == null || payload == null
                        || !presence.valueType().orElseThrow().equals(payload.valueType().orElseThrow())
                        || !presence.name().equals(groupName(group) + "$present")
                        || !payload.name().equals(groupName(group) + "$payload")
                        || payload.componentIndex() != 1) {
                    throw new IllegalArgumentException("split closure capture storage is incomplete");
                }
            } else {
                throw new IllegalArgumentException("closure capture has duplicate storage components");
            }
        }

        GeneratedMemberPlan constructor = members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_CONSTRUCTOR)
                .findFirst().orElseThrow();
        GeneratedMemberPlan invoke = members.stream()
                .filter(member -> member.kind() == GeneratedMemberKind.CLOSURE_INVOKE)
                .findFirst().orElseThrow();
        int authorityIndex = members.indexOf(authority);
        int stateIndex = members.indexOf(state);
        int constructorIndex = members.indexOf(constructor);
        int invokeIndex = members.indexOf(invoke);
        if (authorityIndex != 0 || stateIndex != 1 || constructorIndex != captures.size() + 2
                || invokeIndex != constructorIndex + 1 || !invoke.isPublic()) {
            throw new IllegalArgumentException("closure members are not in canonical order or visibility");
        }
        String expectedConstructor = "(" + authority.descriptor() + state.descriptor()
                + captures.stream().map(GeneratedMemberPlan::descriptor)
                .collect(java.util.stream.Collectors.joining()) + ")V";
        if (!constructor.descriptor().equals(expectedConstructor)
                || !invoke.name().equals("invoke")) {
            throw new IllegalArgumentException("closure constructor/invocation shape is incomplete");
        }
    }

    private static String groupName(List<GeneratedMemberPlan> group) {
        return captureBase(group.getFirst().name(), "$lyra$capture$");
    }

    private static String captureBase(String name, String prefix) {
        if (!name.startsWith(prefix)) {
            throw new IllegalArgumentException("closure capture has a noncanonical name: " + name);
        }
        String suffix = name.substring(prefix.length());
        if (suffix.endsWith("$present")) {
            suffix = suffix.substring(0, suffix.length() - "$present".length());
        } else if (suffix.endsWith("$payload")) {
            suffix = suffix.substring(0, suffix.length() - "$payload".length());
        }
        if (!isCanonicalOrdinal(suffix)) {
            throw new IllegalArgumentException("closure capture has a noncanonical identity: " + name);
        }
        return prefix + suffix;
    }

    private static long captureOrdinal(String base) {
        return Long.parseLong(base.substring("$lyra$capture$".length()));
    }

    private static boolean isCanonicalOrdinal(String value) {
        if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static void requireValueMetadata(
            GeneratedMemberPlan member, String role) {
        if (member.valueType().isEmpty() || member.canonicalLyraContract().isEmpty()) {
            throw new IllegalArgumentException(role + " lacks its immutable value metadata");
        }
    }

    private static void requireExactlyOne(
            List<GeneratedMemberPlan> members,
            GeneratedMemberKind kind,
            String role) {
        if (members.stream().filter(value -> value.kind() == kind).count() != 1) {
            throw new IllegalArgumentException("generated class needs exactly one " + role);
        }
    }

    private static void requireKinds(
            Set<GeneratedMemberKind> actual,
            Set<GeneratedMemberKind> allowed,
            String role) {
        if (!actual.stream().allMatch(allowed::contains)) {
            throw new IllegalArgumentException("invalid " + role + " member kind");
        }
    }

    private static void validateFacadeMemberNames(
            String binaryName, List<GeneratedMemberPlan> members) {
        HashMap<String, GeneratedMemberPlan> byName = new HashMap<>();
        for (GeneratedMemberPlan member : members) {
            GeneratedMemberPlan previous = byName.putIfAbsent(member.name(), member);
            if (previous != null && !member.name().equals("$lyra$create")) {
                throw new IllegalArgumentException(
                        "facade export/infrastructure member names collide: " + member.name());
            }
            if (previous != null
                    && (!(previous.kind() == GeneratedMemberKind.FACTORY
                    || previous.kind() == GeneratedMemberKind.FACTORY_WITH_OPTIONS)
                    || !(member.kind() == GeneratedMemberKind.FACTORY
                    || member.kind() == GeneratedMemberKind.FACTORY_WITH_OPTIONS))) {
                throw new IllegalArgumentException("facade factory name collides: " + member.name());
            }
        }

        GeneratedMemberPlan state = requireFacadeMember(members, GeneratedMemberKind.FACADE_STATE_FIELD,
                member -> member.name().equals("$lyra$state") && member.isField()
                        && member.isPrivate() && member.isFinal() && !member.isStatic());
        requireFacadeMember(members, GeneratedMemberKind.FACADE_CONSTRUCTOR,
                member -> member.name().equals("<init>") && member.isMethod()
                        && !member.isStatic()
                        && member.descriptor().equals("(" + state.descriptor() + ")V"));
        requireFacadeMember(members, GeneratedMemberKind.FACTORY,
                member -> member.name().equals("$lyra$create") && member.isStatic()
                        && member.descriptor().equals("()L" + binaryName.replace('.', '/') + ";"));
        requireFacadeMember(members, GeneratedMemberKind.FACTORY_WITH_OPTIONS,
                member -> member.name().equals("$lyra$create") && member.isStatic()
                        && member.descriptor().equals(
                        "(Lio/mindspice/lyra/runtime/RuntimeOptions;)L"
                                + binaryName.replace('.', '/') + ";"));
        requireFacadeMember(members, GeneratedMemberKind.METADATA,
                member -> member.name().equals("$lyra$metadata") && member.isStatic()
                        && member.isPublic()
                        && member.descriptor().equals(
                        "()Lio/mindspice/lyra/runtime/ArtifactMetadata;"));
        requireFacadeMember(members, GeneratedMemberKind.CLOSE,
                member -> member.name().equals("close") && !member.isStatic()
                        && member.isPublic() && member.descriptor().equals("()V"));
        for (GeneratedMemberPlan member : members) {
            if (member.kind().isExportMember()
                    && io.mindspice.lyra.compiler.identity.JavaNameMangler
                    .isFacadeInvocationReserved(member.name())) {
                throw new IllegalArgumentException(
                        "facade export member uses a reserved member name: " + member.name());
            }
            if (member.kind().isExportMember()) {
                if (member.exportId().isEmpty() || member.sourceName().isEmpty()
                        || member.canonicalLyraContract().isEmpty()) {
                    throw new IllegalArgumentException("facade export member lacks identity metadata");
                }
            }
        }
    }

    private static GeneratedMemberPlan requireFacadeMember(
            List<GeneratedMemberPlan> members,
            GeneratedMemberKind kind,
            java.util.function.Predicate<GeneratedMemberPlan> shape) {
        List<GeneratedMemberPlan> matches = members.stream()
                .filter(member -> member.kind() == kind)
                .toList();
        if (matches.size() != 1 || !shape.test(matches.getFirst())) {
            throw new IllegalArgumentException("facade needs exactly one valid " + kind + " member");
        }
        return matches.getFirst();
    }

    private static List<GeneratedMemberPlan> copyMembers(List<GeneratedMemberPlan> values) {
        Objects.requireNonNull(values, "members");
        ArrayList<GeneratedMemberPlan> copy = new ArrayList<>(values.size());
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        LinkedHashSet<String> fieldNames = new LinkedHashSet<>();
        for (GeneratedMemberPlan value : values) {
            GeneratedMemberPlan member = Objects.requireNonNull(value, "members must not contain null");
            if (!keys.add(member.declarationKey())) {
                throw new IllegalArgumentException("duplicate generated member: " + member.declarationKey());
            }
            if (member.isField() && !fieldNames.add(member.name())) {
                throw new IllegalArgumentException("duplicate generated field: " + member.name());
            }
            copy.add(member);
        }
        return List.copyOf(copy);
    }

    private static List<String> sortedBinaryNames(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(JvmNames.requireBinaryName(value, name));
        }
        copy.sort(Comparator.naturalOrder());
        if (new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        return List.copyOf(copy);
    }

    private static List<GeneratedClassDependency> sortedDependencies(
            List<GeneratedClassDependency> values) {
        Objects.requireNonNull(values, "dependencies");
        ArrayList<GeneratedClassDependency> copy = new ArrayList<>(values.size());
        Map<String, GeneratedClassDependency> byIdentity = new java.util.TreeMap<>();
        for (GeneratedClassDependency value : values) {
            GeneratedClassDependency dependency = Objects.requireNonNull(
                    value, "dependencies must not contain null");
            String identity = dependency.targetBinaryName() + "\u0000"
                    + dependency.kind().name();
            GeneratedClassDependency previous = byIdentity.putIfAbsent(identity, dependency);
            if (previous != null) {
                throw new IllegalArgumentException(
                        previous.equals(dependency)
                                ? "duplicate generated dependency: "
                                : "ambiguous generated dependency: "
                                + dependency.targetBinaryName() + " / " + dependency.kind());
            }
        }
        copy.addAll(byIdentity.values());
        copy.sort(Comparator.naturalOrder());
        return List.copyOf(copy);
    }
}
