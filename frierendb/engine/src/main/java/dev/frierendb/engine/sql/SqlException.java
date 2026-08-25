package dev.frierendb.engine.sql;

import dev.frierendb.engine.FrierenException;

public class SqlException extends FrierenException {

    private static final long serialVersionUID = 1L;

    public SqlException(String message) {
        super(message);
    }

    public SqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
