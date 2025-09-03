package util.exceptions;

public class InternalException extends CompExcept {
    public InternalException(String message) {
        super(message);
    }

    @Override
    public int line() {
        return -1;
    }

    @Override
    public int column() {
        return -1;
    }

    public static CompExcept of(String msg) {
        return new InternalException(msg);
    }
}
