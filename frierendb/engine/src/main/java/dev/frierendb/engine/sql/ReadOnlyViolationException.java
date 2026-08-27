package dev.frierendb.engine.sql;

public class ReadOnlyViolationException extends SqlException {

    private static final long serialVersionUID = 1L;

    public ReadOnlyViolationException() {
        super("Only SELECT and EXPLAIN statements are allowed in read-only mode");
    }
}
