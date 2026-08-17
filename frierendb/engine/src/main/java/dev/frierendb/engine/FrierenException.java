package dev.frierendb.engine;

public class FrierenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FrierenException(String message) {
        super(message);
    }

    public FrierenException(String message, Throwable cause) {
        super(message, cause);
    }
}
