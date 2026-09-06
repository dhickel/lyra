package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.runtime.*;
import org.junit.jupiter.api.Test;

import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionTypeAdmissionTest {
    @Test
    void failedArtifactDefinitionDoesNotPublishStructuralTypes() throws Exception {
        var result = assertInstanceOf(SessionCompileResult.Success.class, LyraCompiler.compileSession(
                new SessionCompileRequest("type-admission.lyra",
                        "let @pub pair :Tuple<I32,String> = Tuple[42 \"value\"]", SessionSnapshot.empty())));
        var original = result.artifact();
        Map<String, byte[]> entries = new LinkedHashMap<>(original.entries());
        var facadeEntry = entries.keySet().stream().filter(name -> name.contains("/$lyra$facade$")
                && name.endsWith(".class")).findFirst().orElseThrow();
        ClassFile classFile = ClassFile.of();
        entries.put(facadeEntry, classFile.transformClass(classFile.parse(entries.get(facadeEntry)), (builder, element) -> {
            if (element instanceof AccessFlags flags) builder.withFlags(flags.flagsMask() & ~ClassFile.ACC_PUBLIC);
            else builder.with(element);
        }));
        ArtifactSource rejected = new ArtifactSource() {
            @Override public ArtifactMetadata metadata() { return original.metadata(); }
            @Override public Map<String, byte[]> entries() { return entries; }
        };
        try (var domain = new SessionStorageDomain()) {
            var types = SessionStorageDomain.class.getDeclaredField("types");
            types.setAccessible(true);
            Object before = types.get(domain);
            var rejectedLink = domain.link(rejected, 0, List.of(), List.of());
            assertThrows(LyraRuntimeException.class,
                    () -> LyraRuntime.loadSubmission(rejected, LoadOptions.defaults(), rejectedLink));
            assertSame(before, types.get(domain), "failed loading must not publish a structural loader");
            var link = domain.link(original, 0, List.of(), List.of());
            try (var loaded = LyraRuntime.loadSubmission(original, LoadOptions.defaults(), link);
                 var module = LyraRuntime.prepareSubmission(loaded)) {
                assertNotSame(before, types.get(domain));
                LyraRuntime.executeSubmission(module);
                assertFalse(module.isClosed());
            }
        }
    }
}
