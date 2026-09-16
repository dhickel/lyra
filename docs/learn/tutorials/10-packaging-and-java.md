# Tutorial 10: Package code and call it from Java

**Outcome:** build class, thin-JAR, and bundled-JAR outputs, then invoke an exact Lyra export through the Java runtime API.

## Prerequisites

Complete [Tutorial 6](06-modules.md). Run `mvn package` from the repository root.

## 1. Create `library.lyra`

```lyra
let @pub double :Fn<I32;I32> = (=> |value| (* value 2))
let @pub main :Fn<Array<String>;I32> = (=> |args| 0)
```

## 2. Build each output form

```sh
lyra compile library.lyra --format classes --output build/library-classes
lyra compile library.lyra --format thin-jar --output build/library-thin.jar
lyra compile library.lyra --format bundled-jar --output build/library.jar
java -jar build/library.jar
```

Classes and thin JARs are reusable library layouts. A thin JAR needs a compatible `lyra-runtime` on its class path. A bundled JAR contains the launcher and runtime and requires the exact public `main` contract.

## 3. Create `UseLyra.java`

```java
import io.mindspice.lyra.runtime.ExportHandle;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;

import java.nio.file.Path;

public final class UseLyra {
    public static void main(String[] args) throws Throwable {
        LoadedArtifact loaded = LyraRuntime.load(Path.of("build/library-thin.jar"));
        ModuleHandle module = null;
        try {
            module = loaded.instantiate();
            ExportHandle doubleValue = module.export("double", "Fn<I32;I32>");
            int result = (int) doubleValue.methodHandle().invokeExact(21);
            System.out.println(result);
        } finally {
            if (module != null) {
                module.close();
            }
            loaded.close();
        }
    }
}
```

Compile and run from the repository root:

```sh
javac -cp lyra-runtime/target/lyra-runtime-0.1.1.jar UseLyra.java
java -cp .:build/library-thin.jar:lyra-runtime/target/lyra-runtime-0.1.1.jar UseLyra
```

Expected output:

```text
42
```

On Windows, replace `:` in the class path with `;`.

The export lookup includes the exact canonical Lyra signature. The returned `MethodHandle` is already bound to the module instance. Module handles are owner-thread confined. Close the module before its `LoadedArtifact`; otherwise context close fails with a lifecycle error.

Continue with the [how-to guides](../index.md#how-to-guides) or the [reference map](../reference.md).
