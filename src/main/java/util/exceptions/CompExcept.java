package util.exceptions;

public abstract class CompExcept extends Exception {
    public CompExcept(String message) {
        super(message);
    }
    
    public abstract int line();
    public abstract int column();
    
    @Override
    public String getMessage() {
        String originalMessage = super.getMessage();
        if (line() >= 0 && column() >= 0) {
            return String.format("[Line: %d, Char: %d] %s", line(), column(), originalMessage);
        }
        return originalMessage;
    }
}