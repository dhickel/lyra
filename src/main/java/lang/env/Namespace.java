package lang.env;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class Namespace {
    private final Namespace parent;
    private final String name;
    private final Path directory;
    private final List<File> files;
    private final List<Namespace> children;
    private final SymbolTable symbolTable;
    private boolean isResolved;


    public Namespace(
            Namespace parent,
            String name,
            Path directory,
            List<File> files,
            List<Namespace> children,
            SymbolTable symbolTable
    ) {
        this.parent = parent;
        this.name = name.toLowerCase();
        this.directory = directory;
        this.files = files;
        this.children = children;
        this.symbolTable = symbolTable;
        this.isResolved = files.isEmpty();
    }


    public String name() { return this.name; }

    public SymbolTable symbolTable() { return symbolTable; }

    public Optional<Namespace> getChild(String name) {
        return children.stream()
                .filter(c -> c.name.equals(name.toLowerCase()))
                .findAny();
    }
}
