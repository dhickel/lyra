package Parse;

public sealed interface TokenData {
    record StringData(String data) implements TokenData {}
    record FloatData(double data) implements TokenData {}
    record IntegerData(long data) implements TokenData {}
}
