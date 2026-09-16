# Compile, load, and invoke Lyra from Java

## Prerequisites

Build `lyra-compiler` and `lyra-runtime` with Java 25:

```sh
mvn -pl lyra-compiler -am package
```

Create `math.lyra`:

```lyra
let @pub double :Fn<I32;I32> = (=> |value| (* value 2))
```

## Compile and invoke

Create `CompileAndRun.java`:

```java
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.ExportHandle;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;

import java.nio.file.Path;

public final class CompileAndRun {
    public static void main(String[] args) throws Throwable {
        CompileRequest request = CompileRequest.builder()
                .root(Path.of("math.lyra"))
                .build();

        CompileResult result = LyraCompiler.compile(request);
        if (result instanceof CompileResult.Failure failure) {
            failure.diagnostics().forEach(System.err::println);
            System.exit(1);
        }

        var artifact = ((CompileResult.Success) result).artifact();
        LoadedArtifact loaded = LyraRuntime.load(artifact);
        ModuleHandle module = null;
        try {
            module = loaded.instantiate();
            ExportHandle function = module.export("double", "Fn<I32;I32>");
            int value = (int) function.methodHandle().invokeExact(21);
            System.out.println(value);
        } finally {
            if (module != null) {
                module.close();
            }
            loaded.close();
        }
    }
}
```

Compile and run:

```sh
javac -cp lyra-compiler/target/lyra-compiler-0.1.1.jar:lyra-runtime/target/lyra-runtime-0.1.1.jar CompileAndRun.java
java -cp .:lyra-compiler/target/lyra-compiler-0.1.1.jar:lyra-runtime/target/lyra-runtime-0.1.1.jar CompileAndRun
```

Expected output:

```text
42
```

Use `;` instead of `:` in a Windows class path.

## Write the compiled artifact

`CompileResult.Success.artifact()` is a `CompiledArtifact`. It can publish a class directory or JAR:

```java
artifact.writeClasses(Path.of("build/classes"));
artifact.writeJar(Path.of("build/library.jar"),
        io.mindspice.lyra.compiler.api.JarMode.THIN_JAR);
```

Pass `WriteOptions.builder().force(true).build()` only when replacement is intentional.

## Exact handles and lifecycle

`ModuleHandle.export(name, signature)` validates both the export name and complete canonical Lyra signature once. `ExportHandle.methodHandle()` has the exact JVM parameter and return types and is already bound to the module instance.

The module and its closures are confined to the thread that instantiated it. Close the `ModuleHandle` on that thread before closing `LoadedArtifact`. Closing a loaded context while a module remains open throws a lifecycle failure and leaves the context open.

`LyraRuntimeException` carries `code()`, `summary()`, source frames, related sources, and `render()`. Do not pass an arbitrary Java lambda where Lyra expects a function value. Java callbacks are not a current interop surface.
