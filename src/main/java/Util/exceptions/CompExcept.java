package Util.exceptions;

public abstract class CompExcept extends Exception {
    public CompExcept(String message) {
        super(message);
    }
    
    public abstract int line();
    public abstract int column();
}