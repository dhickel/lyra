package compile.resolution;

import lang.types.TypeTable;
import util.Result;

import java.util.*;

public class Environment {
    private final List<String> namespaceNames;
    private final Map<String, Integer> namespaceMap;
    private final NamespaceTree namespaceTree;
    private final SymbolTable symbolTable;
    private final TypeTable typeTable;
    private int nextNamespaceId;
    
    public Environment() {
        this.namespaceNames = new ArrayList<>();
        this.namespaceMap = new HashMap<>();
        this.namespaceTree = new NamespaceTree();
        this.symbolTable = new SymbolTable();
        this.typeTable = new TypeTable();
        this.nextNamespaceId = 0;
        
        // Register the main namespace
        registerNamespace("main");
    }
    
    @Deprecated
    public int registerNamespace(String name) {
        // Delegate to hierarchical system for backward compatibility
        Result<NamespaceTree.NamespaceNode, NamespaceError> result = registerNamespacePath(name);
        if (result.isErr()) {
            return -1;
        }
        
        NamespaceTree.NamespaceNode node = result.unwrap();
        
        // Maintain old flat map for existing compatibility
        if (!namespaceMap.containsKey(name)) {
            int namespaceId = nextNamespaceId++;
            namespaceNames.add(name);
            namespaceMap.put(name, namespaceId);
        }
        
        return node.getId();
    }
    
    public Optional<Integer> getNamespaceId(String name) {
        return Optional.ofNullable(namespaceMap.get(name));
    }
    
    public Optional<String> getNamespaceName(int id) {
        if (id >= 0 && id < namespaceNames.size()) {
            return Optional.of(namespaceNames.get(id));
        }
        return Optional.empty();
    }
    
    public SubEnvironment createSubEnvironment(int namespaceId) {
        if (namespaceId < 0 || namespaceId >= namespaceNames.size()) {
            throw new IllegalArgumentException("Invalid namespace ID: " + namespaceId);
        }
        return new SubEnvironment(namespaceId, symbolTable, typeTable);
    }
    
    public SubEnvironment createSubEnvironment(String namespaceName) {
        Integer namespaceId = namespaceMap.get(namespaceName);
        if (namespaceId == null) {
            throw new IllegalArgumentException("Unknown namespace: " + namespaceName);
        }
        return createSubEnvironment(namespaceId);
    }
    
    public SubEnvironment createMainSubEnvironment() {
        return createSubEnvironment("main");
    }
    
    public SymbolTable getSymbolTable() {
        return symbolTable;
    }
    
    public TypeTable getTypeTable() {
        return typeTable;
    }
    
    public List<String> getAllNamespaceNames() {
        return new ArrayList<>(namespaceNames);
    }
    
    public Result<NamespaceTree.NamespaceNode, NamespaceError> registerNamespacePath(String path) {
        return namespaceTree.registerPath(path);
    }
    
    public Optional<NamespaceTree.NamespaceNode> resolveNamespacePath(String path) {
        return namespaceTree.resolvePath(path);
    }
    
    public Optional<Integer> getHierarchicalNamespaceId(String path) {
        return namespaceTree.getNamespaceId(path);
    }
    
    
    public NamespaceTree getNamespaceTree() {
        return namespaceTree;
    }

    @Override
    public String toString() {
        return "Environment{" +
               "namespaces=" + namespaceNames +
               ", nextId=" + nextNamespaceId +
               '}';
    }
}