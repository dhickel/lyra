package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionDeclarationWriteFlowTest {
    @Test
    void invokedBodiesTransferCurrentModuleFunctionStorageWrites() {
        var result = LyraCompiler.compileSession(new SessionCompileRequest("module-writes.lyra", """
                let @mut selected :Fn<;I32> = (=> || 1)
                let replacement :Fn<;I32> = (=> || 2)
                let set :Fn<;Bool> = (=> || { selected := replacement #T })
                (set)
                """, SessionSnapshot.empty()));
        var success = assertInstanceOf(SessionCompileResult.Success.class, result, result.diagnostics().toString());
        var snapshot = success.stagedSnapshot();
        var proof = success.flowCertificate();
        assertEquals(proof.value(snapshot.binding("replacement").orElseThrow().declarationId()).orElseThrow(),
                proof.value(snapshot.binding("selected").orElseThrow().declarationId()).orElseThrow());
    }
}
