package parse;

public sealed interface TokenData {
    TokenData EMPTY = new Empty();

    record Empty() implements TokenData { }

    record StringData(String data) implements TokenData { }

    record FloatData(double data) implements TokenData { }

    record IntegerData(long data) implements TokenData { }
}
