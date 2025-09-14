package lang.env;

import compile.Compiler;
import util.Result;
import util.exceptions.CError;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class Namespace {
    private final String name;
    private final Path directory;
    private final List<Namespace> children;
    private final SymbolTable symbolTable;
    private final Compiler.Module compModule;
    private final int id;


    public Namespace(
            Path directory,
            List<Path> files,
            List<Namespace> children,
            SymbolTable symbolTable,
            int id
    ) {
        this.name = directory.getFileName().toString();
        this.directory = directory;
        this.children = children;
        this.symbolTable = symbolTable;
        this.compModule = Compiler.Module.of(files.stream().map(Compiler.Unit::of).toList());
        this.id = id;
    }

    public Result<Void, CError> applyModuleTransform(Compiler.ModuleTransform func) {
        return func.apply(compModule);
    }


    public String name() { return this.name; }

    public void addUnit(Compiler.Unit unit) {
        this.compModule.addUnit(unit);
    }

    public Compiler.Module getCompModule() {
        return this.compModule;
    }


    public List<Namespace> children() { return children; }

    public boolean hasChildren() { return !children.isEmpty(); }

    public Compiler.State getCompileState() { return compModule.getState(); }

    public SymbolTable symbolTable() { return symbolTable; }

    public Optional<Namespace> getChild(String name) {
        return children.stream()
                .filter(c -> c.name.equals(name.toLowerCase()))
                .findAny();
    }

    public int id() { return id; }


    @Override
    public String toString() {
        return "Namespace{" +
               "name='" + name + '\'' +
               ", directory=" + directory +
               ", children=" + children +
               ", symbolTable=" + symbolTable +
               ", compModule=" + compModule +
               ", id=" + id +
               '}';
    }
}
