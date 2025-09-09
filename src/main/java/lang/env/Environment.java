package lang.env;

import util.Result;
import compile.Compiler;
import util.exceptions.CError;

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


    public Result<Void, CError> compileModulesWith(Compiler.ModuleTransform func) {
        List<Result<Void, CError>> results = allNamespaces.stream()
                .map(n -> n.applyModuleTransform(func))
                .toList();

        return results.stream().allMatch(Result::isOk)
                ? Result.okVoid()
                : results.stream().filter(Result::isErr).findFirst().get();
    }

    public void addTextUnitToGlobalNS(Compiler.Unit unit) {
        rootNamespace.addUnit(unit);
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

            var dirContents = listDirectoryContents(rootPath);

            List<Namespace> children = new ArrayList<>();
            for (Path subDir : dirContents.directories()) {
                children.add(buildRecursive(subDir));
            }

            this.rootNamespace = new Namespace(
                    Path.of("root"),
                    dirContents.files(),
                    children,
                    new SymbolTable.MapTable(10),
                    nextNamespaceId++
            );

            allNamespaces.add(rootNamespace);

            return Result.okVoid();
        } catch (IOException e) {
            return Result.err(e);
        }
    }

    private record DirectoryContents(List<Path> files, List<Path> directories) {}

    private DirectoryContents listDirectoryContents(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();

        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                if (Files.isDirectory(p)) directories.add(p);
                else if (Files.isRegularFile(p)) files.add(p);
            });
        }

        return new DirectoryContents(files, directories);
    }

    private Namespace buildRecursive(Path dir) throws IOException {
        var dirContents = listDirectoryContents(dir);

        List<Namespace> children = new ArrayList<>();
        for (Path subDir : dirContents.directories()) {
            children.add(buildRecursive(subDir));
        }

        Namespace namespace = new Namespace(
                dir,
                dirContents.files(),
                children,
                new SymbolTable.MapTable(40),
                nextNamespaceId++
        );

        allNamespaces.add(namespace);
        return namespace;
    }


    @Override
    public String toString() {
        return "Environment{" +
               "rootNamespace=" + rootNamespace +
               ", allNamespaces=" + allNamespaces +
               ", nextNamespaceId=" + nextNamespaceId +
               '}';
    }
}
