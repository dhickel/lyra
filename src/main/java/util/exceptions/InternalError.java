package util.exceptions;

public class InternalError extends CError {
    public InternalError(String message) {
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

    public static CError of(String msg) {
        return new InternalError(msg);
    }
}
