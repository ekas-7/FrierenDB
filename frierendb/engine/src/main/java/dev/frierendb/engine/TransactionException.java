package dev.frierendb.engine;

/** A transaction was used incorrectly, or was rolled back because a statement failed inside it. */
public class TransactionException extends FrierenException {

    private static final long serialVersionUID = 1L;

    public TransactionException(String message) {
        super(message);
    }

    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
