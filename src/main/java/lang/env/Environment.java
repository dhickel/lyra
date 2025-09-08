package lang.env;

import util.Result;
import compile.Compiler;
import util.exceptions.Error;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class Environment {
    private Namespace rootNamespace;
    private final List<Namespace> allNamespaces;
    private int nextNamespaceId;

    public Environment() {
        this.allNamespaces = new ArrayList<>();
        this.nextNamespaceId = 0;
        this.rootNamespace = new Namespace(
                Path.of("root"),
                List.of(),
                List.of(),
                new SymbolTable.MapTable(10),
                nextNamespaceId++
        );
        allNamespaces.add(rootNamespace);
    }


    public Result<Void, Error> applyCompilerStep(Compiler.Step func) {
        List<Result<Void, Error>> results = allNamespaces.stream()
                .map(n -> n.applyCompilerStep(func))
                .toList();

        return results.stream().allMatch(Result::isOk)
                ? Result.okVoid()
                : results.stream().filter(Result::isErr).findFirst().get();
    }


    public Optional<Namespace> lookupQualifier(String qualifier) {
        return recursiveNSLookUp(Arrays.asList(qualifier.split("\\.")), rootNamespace);
    }

    private Optional<Namespace> recursiveNSLookUp(List<String> path, Namespace current) {
        if (path.isEmpty()) {
            return Optional.of(current);
        }

        return current.children().stream()
                .filter(ns -> ns.name().equals(path.getFirst()))
                .findFirst()
                .flatMap(ns -> recursiveNSLookUp(path.subList(1, path.size()), ns));
    }

    public Result<Void, IOException> buildNamespaceTree(String rootDir) {
        try {
            Path rootPath = Path.of(rootDir);
            allNamespaces.clear();
            nextNamespaceId = 0;

            Namespace tree = buildRecursive(rootPath);

            this.rootNamespace = new Namespace(
                    Path.of("root"),
                    List.of(),
                    List.of(tree),
                    new SymbolTable.MapTable(10),
                    nextNamespaceId++
            );

            allNamespaces.add(rootNamespace);

            return Result.okVoid();
        } catch (IOException e) {
            return Result.err(e);
        }
    }


    private Namespace buildRecursive(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();

        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                if (Files.isDirectory(p)) directories.add(p);
                else if (Files.isRegularFile(p)) files.add(p);
            });
        }

        List<Namespace> children = new ArrayList<>();
        for (Path subDir : directories) {
            children.add(buildRecursive(subDir));
        }

        Namespace namespace = new Namespace(
                dir,
                files,
                children,
                new SymbolTable.MapTable(40),
                nextNamespaceId++
        );

        allNamespaces.add(namespace);
        return namespace;
    }

}
