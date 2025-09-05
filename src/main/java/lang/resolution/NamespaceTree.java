package lang.resolution;

import util.Result;
import java.util.*;

public class NamespaceTree {
    private final NamespaceNode root;
    private final Map<Integer, String> idToPath;
    private final Map<String, Integer> pathToId;
    private int nextCollisionId = 0;

    public static class NamespaceNode {
        private final String name;
        private final String fullPath;
        private final int id;
        private final Map<String, NamespaceNode> children;
        private final NamespaceNode parent;
        
        NamespaceNode(String name, String fullPath, int id, NamespaceNode parent) {
            this.name = name;
            this.fullPath = fullPath;
            this.id = id;
            this.parent = parent;
            this.children = new HashMap<>();
        }
        
        public String getName() { return name; }
        public String getFullPath() { return fullPath; }
        public int getId() { return id; }
        public NamespaceNode getParent() { return parent; }
        public boolean isRoot() { return parent == null; }
        
        public Optional<NamespaceNode> getChild(String name) {
            return Optional.ofNullable(children.get(name));
        }
        
        public Collection<NamespaceNode> getChildren() {
            return Collections.unmodifiableCollection(children.values());
        }
    }

    public NamespaceTree() {
        this.idToPath = new HashMap<>();
        this.pathToId = new HashMap<>();
        
        this.root = new NamespaceNode("", "", generateId(""), null);
        idToPath.put(root.getId(), "");
        pathToId.put("", root.getId());
        
        registerPath("main");
    }

    public Result<NamespaceNode, NamespaceError> registerPath(String path) {
        if (path.isEmpty()) {
            return Result.ok(root);
        }
        
        String[] parts = path.split("\\.");
        NamespaceNode current = root;
        StringBuilder currentPath = new StringBuilder();
        
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i > 0) currentPath.append(".");
            currentPath.append(part);
            
            NamespaceNode child = current.children.get(part);
            if (child == null) {
                String fullPath = currentPath.toString();
                int id = generateId(fullPath);
                child = new NamespaceNode(part, fullPath, id, current);
                
                current.children.put(part, child);
                idToPath.put(id, fullPath);
                pathToId.put(fullPath, id);
            }
            current = child;
        }
        
        return Result.ok(current);
    }

    public Optional<NamespaceNode> resolvePath(String path) {
        if (path.isEmpty()) return Optional.of(root);
        
        String[] parts = path.split("\\.");
        NamespaceNode current = root;
        
        for (String part : parts) {
            current = current.children.get(part);
            if (current == null) return Optional.empty();
        }
        
        return Optional.of(current);
    }

    public Optional<NamespaceNode> resolveRelativePath(NamespaceNode from, String relativePath) {
        String fullPath = from.fullPath.isEmpty() ? relativePath : from.fullPath + "." + relativePath;
        return resolvePath(fullPath);
    }

    public Optional<Integer> getNamespaceId(String path) {
        return Optional.ofNullable(pathToId.get(path));
    }

    public Optional<String> getNamespacePath(int id) {
        return Optional.ofNullable(idToPath.get(id));
    }

    private int generateId(String path) {
        int hash = Objects.hash(path);
        
        while (idToPath.containsKey(hash)) {
            hash = Objects.hash(path, nextCollisionId++);
        }
        
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NamespaceTree{\n");
        printNode(sb, root, 0);
        sb.append("}");
        return sb.toString();
    }

    private void printNode(StringBuilder sb, NamespaceNode node, int depth) {
        String indent = "  ".repeat(depth);
        sb.append(String.format("%s%s [id:%d, path:'%s']\n", 
            indent, node.name.isEmpty() ? "ROOT" : node.name, node.id, node.fullPath));
        
        for (NamespaceNode child : node.children.values()) {
            printNode(sb, child, depth + 1);
        }
    }
}