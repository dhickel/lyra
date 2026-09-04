package io.mindspice.lyra.compiler.artifact;

import io.mindspice.lyra.compiler.backend.jvm.JvmBytecodeArtifact;
import io.mindspice.lyra.compiler.ir.IrDeclaration;
import io.mindspice.lyra.compiler.ir.IrExpressionSite;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.runtime.DebugMapEntry;
import io.mindspice.lyra.runtime.DebugMapMetadata;
import io.mindspice.lyra.runtime.SourceFrame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Builds the schema-1 runtime source map from class line/BCI attributes. */
final class DebugMapBuilder {
    private DebugMapBuilder() {
    }

    static DebugMapMetadata build(JvmBytecodeArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        Map<ModuleId, SourceSnapshot> snapshots = new TreeMap<>();
        for (var module : artifact.typedIr().modules()) {
            snapshots.put(module.moduleId(), artifact.typedIr().sourceSnapshot(module.moduleId())
                    .orElseThrow(() -> new ArtifactAssemblyException(
                            "typed IR is missing source snapshot: " + module.moduleId())));
        }
        List<Origin> origins = origins(artifact);
        ArrayList<DebugMapEntry> entries = new ArrayList<>();
        Map<MethodKey, JvmBytecodeArtifact.EmittedMethod> methods = new TreeMap<>(
                Comparator.comparing(MethodKey::className)
                        .thenComparing(MethodKey::methodName)
                        .thenComparing(MethodKey::descriptor));
        java.util.HashSet<MethodKey> actualMethods = new java.util.HashSet<>();
        for (JvmBytecodeArtifact.EmittedMethod method : artifact.emittedMethods()) {
            MethodKey key = new MethodKey(method.internalClassName(), method.methodName(),
                    method.methodDescriptor());
            if (methods.put(key, method) != null) {
                throw new ArtifactAssemblyException("duplicate emitted method metadata: " + key);
            }
        }

        for (String binaryName : artifact.classNames()) {
            ClassFileReader.ParsedClass classFile = ClassFileReader.read(binaryName,
                    artifact.bytes(binaryName));
            for (ClassFileReader.ParsedMethod parsed : classFile.methods()) {
                MethodKey key = new MethodKey(binaryName.replace('.', '/'), parsed.name(),
                        parsed.descriptor());
                if (!actualMethods.add(key)) {
                    throw new ArtifactAssemblyException("duplicate class method: " + key);
                }
                JvmBytecodeArtifact.EmittedMethod method = methods.get(key);
                if (method == null) {
                    throw new ArtifactAssemblyException("class method has no emission origin: " + key);
                }
                if (parsed.codeLength() <= 0) {
                    continue;
                }
                SourceSnapshot snapshot = snapshots.get(method.moduleId());
                if (snapshot == null) {
                    throw new ArtifactAssemblyException("debug method refers to absent source snapshot: "
                            + method.moduleId());
                }
                snapshot.validateSpan(method.originSpan());
                if (!method.moduleId().sourceId().equals(method.originSpan().sourceId())) {
                    throw new ArtifactAssemblyException("debug method origin belongs to another module: "
                            + key);
                }
                List<ClassFileReader.Line> lines = coverageLines(parsed.lines(), parsed.codeLength(),
                        snapshot.positionAt(method.originSpan().startOffset()).line());
                for (int index = 0; index < lines.size(); index++) {
                    ClassFileReader.Line line = lines.get(index);
                    int start = line.startBci();
                    int end = index + 1 < lines.size()
                            ? lines.get(index + 1).startBci() : parsed.codeLength();
                    if (start >= end) {
                        continue;
                    }
                    SourceSpan origin = selectOrigin(method.moduleId(), line.line(),
                            method.originSpan(), snapshot, origins);
                    SourceFrame frame = frame(method, origin, snapshot);
                    entries.add(new DebugMapEntry(key.className(), key.methodName(), key.descriptor(),
                            start, end, frame));
                }
            }
        }
        if (!actualMethods.equals(methods.keySet())) {
            java.util.HashSet<MethodKey> missing = new java.util.HashSet<>(methods.keySet());
            missing.removeAll(actualMethods);
            throw new ArtifactAssemblyException("planned methods are absent from class files: " + missing);
        }
        return new DebugMapMetadata(DebugMapMetadata.SCHEMA_VERSION, entries);
    }

    private static List<ClassFileReader.Line> coverageLines(List<ClassFileReader.Line> values,
                                                              int codeLength,
                                                              int fallbackLine) {
        ArrayList<ClassFileReader.Line> result = new ArrayList<>();
        for (ClassFileReader.Line value : values) {
            if (value.startBci() < codeLength) {
                result.add(value);
            }
        }
        if (result.isEmpty()) {
            return List.of(new ClassFileReader.Line(0, fallbackLine));
        }
        if (result.getFirst().startBci() > 0) {
            result.addFirst(new ClassFileReader.Line(0, fallbackLine));
        }
        return List.copyOf(result);
    }

    private static SourceFrame frame(JvmBytecodeArtifact.EmittedMethod method,
                                     SourceSpan span, SourceSnapshot snapshot) {
        io.mindspice.lyra.runtime.ModuleId module = method.moduleId().isUri()
                ? io.mindspice.lyra.runtime.ModuleId.uri(method.moduleId().asUri())
                : io.mindspice.lyra.runtime.ModuleId.path(method.moduleId().value());
        io.mindspice.lyra.runtime.SourceId source = span.sourceId().isUri()
                ? io.mindspice.lyra.runtime.SourceId.uri(span.sourceId().asUri())
                : io.mindspice.lyra.runtime.SourceId.path(span.sourceId().value());
        io.mindspice.lyra.runtime.SourceSpan runtimeSpan = new io.mindspice.lyra.runtime.SourceSpan(
                source, span.startOffset(), span.endOffset());
        String label = snapshot.sourceId().value();
        SourceFrame origin = new SourceFrame(module, method.functionName(), runtimeSpan, label);
        return method.synthetic()
                ? new SourceFrame(module, method.functionName(), runtimeSpan,
                java.util.Optional.empty(), java.util.Optional.of(label), true,
                java.util.Optional.of(origin))
                : origin;
    }

    private static SourceSpan selectOrigin(ModuleId module, int line, SourceSpan fallback,
                                           SourceSnapshot snapshot, List<Origin> origins) {
        return origins.stream()
                .filter(origin -> origin.module().equals(module))
                .filter(origin -> origin.span().sourceId().equals(fallback.sourceId())
                        && origin.span().startOffset() >= fallback.startOffset()
                        && origin.span().endOffset() <= fallback.endOffset())
                .filter(origin -> {
                    snapshot.validateSpan(origin.span());
                    return lineAt(snapshot, origin.span()) == line;
                })
                .sorted(Comparator.comparingInt((Origin origin) -> origin.span().length())
                        .thenComparingInt(origin -> origin.span().startOffset())
                        .thenComparing(origin -> origin.key()))
                .map(Origin::span)
                .findFirst()
                .orElse(fallback);
    }

    private static int lineAt(SourceSnapshot snapshot, SourceSpan span) {
        int offset = Math.min(Math.max(span.startOffset(), 0), snapshot.utf16Length());
        return snapshot.positionAt(offset).line();
    }

    private static List<Origin> origins(JvmBytecodeArtifact artifact) {
        ArrayList<Origin> result = new ArrayList<>();
        for (IrExpressionSite site : artifact.typedIr().expressionSites()) {
            result.add(new Origin(site.moduleId(), site.span(), "expression:" + site.siteId()));
        }
        for (IrDeclaration declaration : artifact.typedIr().declarations()) {
            result.add(new Origin(declaration.moduleId(), declaration.span(),
                    "declaration:" + declaration.id()));
            result.add(new Origin(declaration.moduleId(), declaration.nameSpan(),
                    "name:" + declaration.id()));
        }
        for (var module : artifact.typedIr().modules()) {
            result.add(new Origin(module.moduleId(), module.span(), "module:" + module.moduleId()));
        }
        return List.copyOf(result);
    }

    private record MethodKey(String className, String methodName, String descriptor) {
        private MethodKey {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    private record Origin(ModuleId module, SourceSpan span, String key) {
        private Origin {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(key, "key");
        }
    }
}
