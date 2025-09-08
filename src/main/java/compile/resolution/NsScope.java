package compile.resolution;

public record NsScope(int ns, int scope) {
    public static NsScope of(int ns, int scope) {
        return new NsScope(ns, scope);
    }
}
